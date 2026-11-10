import {spawn} from 'node:child_process';
import {createInterface} from 'node:readline';
import {EventEmitter} from 'node:events';
import {resolveDesktopBinary} from './desktop-binary.mjs';

// This client never resumes a user thread or starts a turn in it.
class TitleClient extends EventEmitter {
  constructor(binary){
    super();this.sequence=0;this.pending=new Map();
    this.child=spawn(binary,['app-server','--listen','stdio://'],{windowsHide:true,stdio:['pipe','pipe','pipe']});
    this.child.stderr.on('data',()=>{});this.child.stdin.on('error',()=>{});
    const failed=()=>{for(const p of this.pending.values()){clearTimeout(p.timer);p.reject(Error('自动标题连接中断'));}this.pending.clear();};
    this.child.on('error',failed);this.child.on('exit',failed);
    this.lines=createInterface({input:this.child.stdout});this.lines.on('line',line=>{
      let value;try{value=JSON.parse(line);}catch{return;}
      if(value.method){
        if(value.id!==undefined)this.child.stdin.write(JSON.stringify({id:value.id,error:{code:-32601,message:'Title generation does not allow tools'}})+'\n');
        else this.emit('notification',value);
        return;
      }
      const p=this.pending.get(value.id);if(!p)return;this.pending.delete(value.id);clearTimeout(p.timer);
      value.error?p.reject(Error('自动标题接口请求失败')):p.resolve(value.result);
    });
  }
  request(method,params){return new Promise((resolve,reject)=>{
    const id=++this.sequence,timer=setTimeout(()=>{this.pending.delete(id);reject(Error('自动标题接口超时'));},20000);
    this.pending.set(id,{resolve,reject,timer});this.child.stdin.write(JSON.stringify({id,method,params})+'\n',error=>{if(error){clearTimeout(timer);this.pending.delete(id);reject(Error('自动标题连接不可用'));}});
  });}
  async initialize(){await this.request('initialize',{clientInfo:{name:'codex_lan_title',version:'0.5.0'},capabilities:{experimentalApi:true}});this.child.stdin.write('{"method":"initialized"}\n');}
  close(){this.lines.close();this.child.stdin.end();for(const p of this.pending.values()){clearTimeout(p.timer);p.reject(Error('自动标题已关闭'));}this.pending.clear();const timer=setTimeout(()=>this.child.kill(),750);timer.unref();}
}

export class AutoTitles {
  constructor(binary,{entries={},save=async()=>{},openClient=async binary=>{const c=new TitleClient(await resolveDesktopBinary(binary));try{await c.initialize();return c;}catch(error){c.close();throw error;}}}={}){
    this.binary=binary;this.entries=entries;this.save=save;this.openClient=openClient;this.pending=new Set();this.clients=new Set();this.closed=false;this.saving=Promise.resolve();
  }
  persist(){this.saving=this.saving.catch(()=>{}).then(()=>this.save(this.entries));return this.saving;}
  async remember(id,expected){this.entries[id]={expected};await this.persist();}
  wants(id){return !!this.entries[id]&&!this.entries[id].failed;}
  async generate({threadId,cwd,prompt,canApply=()=>true}){
    const entry=this.entries[threadId];if(this.closed||!entry||entry.failed||this.pending.has(threadId)||!prompt)return null;
    this.pending.add(threadId);let client,ephemeralId,turnId,timer,onNotification;
    try{
      client=await this.openClient(this.binary);this.clients.add(client);
      const matches=async()=>{
        if(this.closed||!canApply()||this.entries[threadId]!==entry)return false;
        const current=await client.request('thread/read',{threadId,includeTurns:false});
        if(current.thread?.name!==entry.expected){delete this.entries[threadId];await this.persist();return false;}return true;
      };
      if(!await matches())return null;
      if(!entry.title){
        const models=await client.request('model/list',{limit:100,includeHidden:false});
        const eligible=(models.data||[]).filter(m=>!m.hidden&&m.supportedReasoningEfforts?.some(e=>e.reasoningEffort==='low'));
        const model=eligible.find(m=>m.model==='gpt-6-luna')?.model??eligible.find(m=>m.isDefault)?.model??eligible[0]?.model;
        if(!model)throw Error('没有可用的标题模型');
        const config=await client.request('config/read',{cwd,includeLayers:false});
        const mcp=Object.fromEntries(Object.keys(config.config?.mcp_servers||{}).map(name=>[name,{enabled:false}]));
        const overrides={mcp_servers:mcp,'mcp_servers.codex_app':{enabled:false,command:''},project_doc_max_bytes:0,
          include_permissions_instructions:false,include_apps_instructions:false,include_environment_context:false,
          skills:{bundled:{enabled:false},config:[]},memories:{use_memories:false,generate_memories:false},web_search:'disabled',model_reasoning_effort:'low'};
        for(const feature of ['apps','plugins','hooks','shell_tool','unified_exec','shell_snapshot','apply_patch_freeform','code_mode','code_mode_only','js_repl','js_repl_tools_only','multi_agent','multi_agent_v2','enable_fanout','request_permissions_tool','image_generation'])overrides['features.'+feature]=false;
        const started=await client.request('thread/start',{model,cwd,approvalPolicy:'never',permissions:':read-only',runtimeWorkspaceRoots:[],config:overrides,
          personality:null,ephemeral:true,threadSource:'thread_title',experimentalRawEvents:false,dynamicTools:null,baseInstructions:'Generate a concise chat title only. Do not use tools.'});
        ephemeralId=started.thread.id;let output='';
        const completed=new Promise((resolve,reject)=>{
          timer=setTimeout(()=>reject(Error('标题模型响应超时')),45000);
          onNotification=value=>{
            if(value.params?.threadId!==ephemeralId)return;
            if(value.method==='item/completed'&&value.params.item?.type==='agentMessage')output=value.params.item.text||'';
            if(value.method==='turn/completed'){turnId=value.params.turn.id;value.params.turn.status==='completed'?resolve():reject(Error('标题生成未完成'));}
          };client.on('notification',onNotification);
        });
        // Attach a rejection handler before awaiting turn/start, which may itself fail.
        completed.catch(()=>{});
        const turn=await client.request('turn/start',{threadId:ephemeralId,input:[{type:'text',text:'为以下用户请求生成简短标题，使用用户的语言，最多30字，不调用工具。只返回JSON的title字段。\n'+prompt.slice(0,8000),text_elements:[]}],
          permissions:':read-only',runtimeWorkspaceRoots:[],summary:'none',turnTrigger:'thread_title',outputSchema:{type:'object',properties:{title:{type:'string',minLength:1,maxLength:60}},required:['title'],additionalProperties:false}});
        turnId=turn.turn.id;await completed;
        const title=JSON.parse(output).title;if(typeof title!=='string'||!title.trim()||title.length>60||/[\r\n\x00-\x1f]/.test(title))throw Error('标题模型返回无效结果');
        entry.title=title.trim();await this.persist();
      }
      if(!await matches())return null;
      // The native API has no compare-and-set. Recheck immediately before write.
      await client.request('thread/name/set',{threadId,name:entry.title});
      const title=entry.title;delete this.entries[threadId];await this.persist();return title;
    }catch{
      // Never turn every sync event into another billed model retry.
      if(!this.closed&&this.entries[threadId]===entry){entry.failed=true;await this.persist().catch(()=>{});}return null;
    }finally{
      clearTimeout(timer);if(onNotification)client?.off('notification',onNotification);
      if(ephemeralId){if(turnId&&!entry.title)await client.request('turn/interrupt',{threadId:ephemeralId,turnId}).catch(()=>{});await client.request('thread/unsubscribe',{threadId:ephemeralId}).catch(()=>{});}
      client?.close();this.clients.delete(client);this.pending.delete(threadId);
    }
  }
  close(){this.closed=true;for(const client of this.clients)client.close();this.clients.clear();}
}
