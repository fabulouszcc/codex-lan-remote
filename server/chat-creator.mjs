import {spawn} from 'node:child_process';
import {createInterface} from 'node:readline';
import {resolveDesktopBinary} from './desktop-binary.mjs';

// A separate, narrowly scoped client creates empty chats only. Existing chats are never resumed here.
export class ChatCreator {
  constructor(binary,{launch=launchDesktopThread}={}){this.binary=binary;this.launch=launch;}
  async create({cwd,name,model}){
    const binary=await resolveDesktopBinary(this.binary),child=spawn(binary,['app-server','--listen','stdio://'],{windowsHide:true,stdio:['pipe','pipe','pipe']});
    const pending=new Map();let sequence=0,startedId;
    const fail=()=>{for(const entry of pending.values()){clearTimeout(entry.timer);entry.reject(Error('新建会话连接中断'));}pending.clear();};
    child.on('error',fail);child.on('exit',fail);child.stdin.on('error',()=>{});child.stderr.on('data',()=>{});
    const lines=createInterface({input:child.stdout});lines.on('line',line=>{let value;try{value=JSON.parse(line);}catch{return;}if(value.method&&value.id!==undefined){child.stdin.write(JSON.stringify({id:value.id,error:{code:-32601,message:'Chat creation only'}})+'\n');return;}const request=pending.get(value.id);if(!request)return;pending.delete(value.id);clearTimeout(request.timer);value.error?request.reject(Error('桌面拒绝新建会话操作')):request.resolve(value.result);});
    const request=(method,params)=>new Promise((resolve,reject)=>{const id=++sequence,timer=setTimeout(()=>{pending.delete(id);reject(Error('新建会话回执超时，请先检查列表，避免重复创建'));},20000);pending.set(id,{resolve,reject,timer});child.stdin.write(JSON.stringify({id,method,params})+'\n',error=>{if(error){pending.delete(id);clearTimeout(timer);reject(Error('新建会话连接不可用'));}});});
    try{
      await request('initialize',{clientInfo:{name:'codex_lan_create',version:'0.5.0'},capabilities:{experimentalApi:true}});child.stdin.write('{"method":"initialized"}\n');
      const result=await request('thread/start',{cwd,ephemeral:false,...model?{model}:{}});startedId=result?.thread?.id;
      if(!/^[a-f0-9-]{36}$/.test(startedId||''))throw Error('桌面返回的会话编号无效');
      // An untouched thread/start is not reopenable by the desktop yet. Persist
      // a default title before handing it off, without asking the user to name it.
      const title=name?.trim()||'新聊天';
      try{await request('thread/name/set',{threadId:startedId,name:title});}
      catch{throw Error('新会话已创建但尚未保存，请先同步列表，避免重复创建');}
      return {id:startedId,name:title,cwd};
    }finally{lines.close();child.stdin.end();const timer=setTimeout(()=>child.kill(),1000);timer.unref();fail();}
  }
  async show(id){return this.launch(id);}
}
export function launchDesktopThread(id){
  if(!/^[a-f0-9-]{36}$/.test(id))throw Error('会话编号无效');
  return new Promise((resolve,reject)=>{const child=spawn('explorer.exe',['codex://threads/'+id],{windowsHide:true,stdio:'ignore'});child.once('spawn',resolve);child.once('error',()=>reject(Error('会话已创建，请在电脑 Codex 列表中打开它')));});
}
