package dev.codexlan.remote;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.LinkedHashMap;
import java.util.Iterator;

/** Merge wire snapshots without erasing locally retained history on a reconnect. */
final class MobileState {
    static String replacementSelection(JSONArray catalog,String missing){
        if(catalog!=null)for(int i=0;i<catalog.length();i++){JSONObject row=catalog.optJSONObject(i);if(row!=null&&!row.optString("id").isEmpty()&&!row.optString("id").equals(missing))return row.optString("id");}return "";
    }
    static JSONObject mergeHistory(JSONObject previous,JSONObject history) throws Exception {
        JSONObject result=copyFields(previous);JSONArray before=previous.optJSONArray("threads"),after=new JSONArray();boolean found=false;
        if(before!=null)for(int i=0;i<before.length();i++){
            JSONObject row=before.getJSONObject(i);
            if(row.optString("id").equals(history.optString("id"))){found=true;
                if(row.optBoolean("ready")&&!row.optBoolean("metadataOnly"))row=copyFields(row);
                else{
                    JSONObject live=row;
                    LinkedHashMap<String,JSONObject> turns=new LinkedHashMap<>();for(JSONObject source:new JSONObject[]{row,history}){JSONArray items=source.optJSONArray("turns");if(items!=null)for(int j=0;j<items.length();j++){JSONObject turn=items.getJSONObject(j);String id=turn.getString("id");turns.remove(id);turns.put(id,turn);}}
                    // Stored content fills the cache; the latest live status always wins.
                    JSONArray liveTurns=live.optJSONArray("turns");if(live.optBoolean("ready")&&liveTurns!=null)for(int j=0;j<liveTurns.length();j++){JSONObject current=liveTurns.getJSONObject(j),cached=turns.get(current.optString("id"));if(cached!=null){cached=copyFields(cached);for(String field:new String[]{"status","failureReason","startedAtMs"})if(current.has(field))cached.put(field,current.get(field));turns.put(current.optString("id"),cached);}}
                    while(turns.size()>50)turns.remove(turns.keySet().iterator().next());row=copyFields(history).put("turns",new JSONArray(turns.values()));
                    if(live.optBoolean("ready"))for(String field:new String[]{"ready","writable","activeTurnId","approvals","questions","queueSupported","queuedMessages","metadataOnly","model","reasoningEffort","serviceTier","revision","runtimeStatus","activity"})if(live.has(field))row.put(field,live.get(field));
                }
                row.put("historyUpdatedAt",history.optLong("historyUpdatedAt"));}
            after.put(row);
        }
        if(!found)after.put(history);result.put("threads",after);return result;
    }
    static boolean viewportAtBottom(int count,int lastVisible,int lastBottom,int height,int paddingBottom,int tolerance){
        return count==0||(lastVisible>=count-1&&lastBottom<=height-paddingBottom+tolerance);
    }
    static boolean pollObsolete(long started,long current,String requested,String selected,boolean sameClient){
        return started!=current || !sameClient || !java.util.Objects.equals(requested,selected);
    }
    static String connectionAddress(String value) throws Exception {
        String input=value.trim();if(!input.contains("://"))input="https://"+input;
        java.net.URI uri=new java.net.URI(input);int port=uri.getPort()==-1?9443:uri.getPort();
        if(!"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getRawUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null || (uri.getPath()!=null&&!uri.getPath().isEmpty()&&!"/".equals(uri.getPath())) || port<1 || port>65535)throw new Exception("请输入电脑 IP:端口，仅支持 HTTPS 连接");
        return new java.net.URI("https",null,uri.getHost(),port,null,null,null).toASCIIString();
    }
    static String composerAction(JSONObject state, JSONObject thread, boolean sending, String draft) {
        return composerAction(state,thread,sending,draft,"auto");
    }
    static String composerAction(JSONObject state, JSONObject thread, boolean sending, String draft,String mode) {
        if (!unavailableReason(state,thread,sending).isEmpty()) return "none";
        boolean active=!activeTurnId(thread).isEmpty(), hasText=draft!=null && !draft.trim().isEmpty();
        if(active&&hasText&&"steer".equals(mode))return "steer";
        if(active&&hasText&&"queue".equals(mode)&&!thread.optBoolean("queueSupported"))return "none";
        boolean queued=thread.optBoolean("queueSupported")&&(active||thread.optJSONArray("queuedMessages")!=null&&thread.optJSONArray("queuedMessages").length()>0);
        return hasText ? (queued?"queue":active ? "steer" : "start") : active ? "interrupt" : "none";
    }
    static JSONArray timeline(JSONObject turn) throws Exception {
        JSONArray ordered=turn.optJSONArray("timeline");
        if(ordered!=null && ordered.length()>0){
            boolean hasUser=false;for(int i=0;i<ordered.length();i++)if("user".equals(ordered.getJSONObject(i).optString("type")))hasUser=true;
            if(hasUser || turn.optString("input").isEmpty())return ordered;
            JSONArray result=new JSONArray().put(new JSONObject().put("id","legacy-input").put("type","user").put("text",turn.optString("input")));
            for(int i=0;i<ordered.length();i++)result.put(ordered.get(i));return result;
        }
        JSONArray result=new JSONArray(), replies=turn.optJSONArray("agentMessages"), tools=turn.optJSONArray("toolResults"), types=turn.optJSONArray("itemTypes");
        if(!turn.optString("input").isEmpty())result.put(new JSONObject().put("type","user").put("text",turn.optString("input")));
        int reply=0;java.util.HashSet<Integer> used=new java.util.HashSet<>();
        if(types!=null)for(int i=0;i<types.length();i++){
            String type=types.optString(i);
            if("agentMessage".equals(type) && replies!=null && reply<replies.length())result.put(new JSONObject().put("type","message").put("text",replies.getString(reply++)));
            else if(tools!=null)for(int j=0;j<tools.length();j++)if(!used.contains(j) && type.equals(tools.getJSONObject(j).optString("type"))){JSONObject tool=new JSONObject(tools.getJSONObject(j).toString());tool.put("toolType",type).put("type","tool");result.put(tool);used.add(j);break;}
        }
        if(replies!=null)while(reply<replies.length())result.put(new JSONObject().put("type","message").put("text",replies.getString(reply++)));
        if(tools!=null)for(int j=0;j<tools.length();j++)if(!used.contains(j)){JSONObject tool=new JSONObject(tools.getJSONObject(j).toString());tool.put("toolType",tool.optString("type")).put("type","tool");result.put(tool);}
        return result;
    }
    static boolean canQuickApprove(JSONObject request, String details) {
        if (details == null || details.trim().isEmpty() || details.length() > 1200 || request.optBoolean("truncated")) return false;
        if ("command".equals(request.optString("kind"))) {
            Object command = request.opt("command");
            return command instanceof String && !((String) command).trim().isEmpty();
        }
        if (!"file".equals(request.optString("kind"))) return false;
        JSONArray changes = request.optJSONArray("changes");
        if (changes == null || changes.length() == 0) return false;
        for (int i = 0; i < changes.length(); i++) {
            JSONObject change = changes.optJSONObject(i);
            if (change == null || change.optBoolean("truncated")) return false;
            Object diff = change.opt("diff");
            // The desktop's actual file-change schema supplies a complete diff
            // string. Unknown object encodings must be reviewed in the chat.
            if (!(diff instanceof String) || ((String) diff).trim().isEmpty()) return false;
        }
        return true;
    }
    static String activeTurnId(JSONObject thread) {
        if (thread == null || !thread.optBoolean("ready")) return "";
        if (thread.has("activeTurnId")) {
            Object id = thread.opt("activeTurnId");
            return id instanceof String && !"null".equals(id) ? (String) id : "";
        }
        // Compatibility with older gateways: only the latest live turn counts.
        JSONArray turns = thread.optJSONArray("turns");
        JSONObject last = turns == null || turns.length() == 0 ? null : turns.optJSONObject(turns.length() - 1);
        return last != null && "inProgress".equals(last.optString("status")) ? last.optString("id") : "";
    }
    static String unavailableReason(JSONObject state, JSONObject thread, boolean sending) {
        if (sending) return "正在提交，请稍候…";
        if (state == null || !state.optBoolean("online")) return "连接中断，请检查 EasyTier 或重新连接";
        if (thread == null) return "正在加载所选聊天，请稍候";
        if (!thread.optBoolean("ready")) return "聊天历史可读，请先在电脑打开该聊天，或点击重新同步";
        if (!thread.optBoolean("writable", true)) return "此聊天仅允许读取，需要在电脑授权后发送";
        return "";
    }
    private static JSONObject copyFields(JSONObject source) throws Exception {
        JSONObject result=new JSONObject();java.util.Iterator<String> keys=source.keys();
        while(keys.hasNext()){String key=keys.next();result.put(key,source.get(key));}return result;
    }
    static JSONObject merge(JSONObject previous, JSONObject incoming) throws Exception {
        if(incoming!=null&&"delta".equals(incoming.optString("sync"))&&(previous==null||incoming.optLong("baseVersion",-1)!=previous.optLong("version",-2)))throw new Exception("增量同步基线已变化，需要完整同步");
        LinkedHashMap<String, JSONObject> threads = new LinkedHashMap<>();
        for (JSONObject source : new JSONObject[] { previous, incoming }) {
            JSONArray values = source == null ? null : source.optJSONArray("threads");
            if (values == null) continue;
            for (int i = 0; i < values.length(); i++) {
                JSONObject thread = copyFields(values.getJSONObject(i));
                if (source == previous) thread.put("ready", false).put("approvals", new JSONArray()).put("questions", new JSONArray());
                String id = thread.getString("id"); JSONObject old = threads.get(id);
                LinkedHashMap<String, JSONObject> turns = new LinkedHashMap<>();
                for (JSONObject version : new JSONObject[] { old, thread }) {
                    JSONArray entries = version == null ? null : version.optJSONArray("turns"); if (entries == null) continue;
                    for (int j = 0; j < entries.length(); j++) {
                        JSONObject next = copyFields(entries.getJSONObject(j)); String key = next.getString("id");
                        JSONObject prior = turns.get(key);
                        if (prior != null) {
                            if (next.optString("input").isEmpty()) next.put("input", prior.optString("input"));
                            if (next.optJSONArray("agentMessages") == null || next.getJSONArray("agentMessages").length() == 0) next.put("agentMessages", prior.optJSONArray("agentMessages"));
                            if (next.optJSONArray("timeline") == null || next.getJSONArray("timeline").length() == 0) next.put("timeline", prior.optJSONArray("timeline"));
                        }
                        // Incoming order is authoritative, including when repairing
                        // a cached snapshot whose last entry was actually old.
                        turns.remove(key); turns.put(key, next);
                    }
                }
                JSONArray order=thread.optJSONArray("turnOrder");
                if(source==incoming&&order!=null){LinkedHashMap<String,JSONObject> ordered=new LinkedHashMap<>();for(int j=0;j<order.length();j++){String key=order.getString(j);JSONObject value=turns.get(key);if(value==null)throw new Exception("聊天缓存缺失，需要完整同步");ordered.put(key,value);}turns=ordered;}
                while (turns.size() > 50) turns.remove(turns.keySet().iterator().next());
                thread.put("turns", new JSONArray(turns.values())); threads.put(id, thread);
            }
        }
        // Keep all catalog chats; each chat retains a bounded recent-turn window.
        JSONObject result = new JSONObject().put("threads", new JSONArray(threads.values()));
        if (previous != null) result.put("selectedThreadId", previous.optString("selectedThreadId"));
        if (incoming != null) result.put("version", incoming.optLong("version")).put("savedAt", System.currentTimeMillis());
        if(incoming!=null&&incoming.has("desktopVersion"))result.put("desktopVersion",incoming.optString("desktopVersion"));else if(previous!=null)result.put("desktopVersion",previous.optString("desktopVersion"));
        JSONObject connections=incoming==null?null:incoming.optJSONObject("connectionAddresses");if(connections==null&&previous!=null)connections=previous.optJSONObject("connectionAddresses");if(connections!=null)result.put("connectionAddresses",connections);
        return result;
    }
    static JSONObject offline(JSONObject value) throws Exception {
        JSONObject result = new JSONObject(value.toString()); result.put("online", false);
        JSONArray threads = result.optJSONArray("threads");
        if (threads != null) for (int i = 0; i < threads.length(); i++) threads.getJSONObject(i).put("ready", false).put("approvals", new JSONArray()).put("questions", new JSONArray());
        return result;
    }
    static JSONArray alerts(JSONObject previous, JSONObject current) throws Exception {
        LinkedHashMap<String, String> statuses = new LinkedHashMap<>();
        LinkedHashMap<String, Long> liveBaselines = new LinkedHashMap<>();
        JSONArray before = previous == null ? null : previous.optJSONArray("threads");
        if (before != null) for (int i = 0; i < before.length(); i++) {
            JSONArray turns = before.getJSONObject(i).optJSONArray("turns"); if (turns == null) continue;
            JSONObject thread = before.getJSONObject(i); long latest = 0;
            for (int j = 0; j < turns.length(); j++) { JSONObject turn = turns.getJSONObject(j); statuses.put(thread.getString("id") + ":" + turn.getString("id"), turn.optString("status")); latest = Math.max(latest, turn.optLong("startedAtMs")); }
            if (previous.optBoolean("online") && thread.optBoolean("ready") && thread.optBoolean("historyReady")) liveBaselines.put(thread.getString("id"), latest);
        }
        JSONArray result = new JSONArray(), threads = current.optJSONArray("threads");
        if (threads == null) return result;
        for (int i = 0; i < threads.length(); i++) {
            JSONObject thread = threads.getJSONObject(i); String threadId = thread.getString("id");
            if (!thread.optBoolean("ready")) continue;
            JSONArray turns = thread.optJSONArray("turns");
            if (turns != null) for (int j = 0; j < turns.length(); j++) {
                JSONObject turn = turns.getJSONObject(j); String id = turn.getString("id"), status = turn.optString("status");
                String failure=turn.optString("failureReason");boolean limited=failure.equals("quota")||failure.equals("rateLimit")||failure.equals("contextLimit");
                boolean finished = status.equals("completed") || status.equals("failed") || limited&&(status.equals("interrupted")||status.equals("cancelled"));
                boolean newlyFinished = !statuses.containsKey(threadId + ":" + id) && liveBaselines.containsKey(threadId)
                    && turn.optLong("startedAtMs") > liveBaselines.get(threadId);
                if (finished && ("inProgress".equals(statuses.get(threadId + ":" + id)) || newlyFinished))
                    result.put(new JSONObject().put("kind", "result").put("threadId", threadId).put("turnId", id).put("status", status).put("failureReason",failure));
            }
            JSONArray approvals = thread.optJSONArray("approvals");
            if (approvals != null) for (int j = 0; j < approvals.length(); j++) {
                JSONObject approval = approvals.getJSONObject(j);
                if (!approval.optBoolean("submitting")) result.put(new JSONObject().put("kind", "approval").put("threadId", threadId).put("request", approval));
            }
        }
        return result;
    }
}
