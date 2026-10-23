package dev.codexlan.remote;

import org.json.*;
import java.util.*;

/** Display acknowledged sends immediately; never re-submit an uncertain operation. */
final class SentMessages {
    private static final class Pending{final String id,text;final Set<String> before;Pending(String id,String text,Set<String> before){this.id=id;this.text=text;this.before=new HashSet<>(before);}}
    private final Map<String,List<Pending>> pending=new HashMap<>();private final Map<String,Set<String>> matched=new HashMap<>();
    static Map<String,String> users(JSONObject thread)throws Exception{
        Map<String,String> out=new LinkedHashMap<>();JSONArray turns=thread==null?null:thread.optJSONArray("turns");if(turns!=null)for(int i=0;i<turns.length();i++){JSONObject turn=turns.getJSONObject(i);JSONArray timeline=MobileState.timeline(turn);for(int j=0;j<timeline.length();j++){JSONObject item=timeline.getJSONObject(j);if(item.optString("type").equals("user"))out.put(item.optString("clientMessageId").isEmpty()?turn.optString("id")+":"+item.optString("id","item-"+j):"client:"+item.optString("clientMessageId"),item.optString("text").trim());}}return out;
    }
    synchronized void accepted(String chat,String id,String text,Set<String> before){List<Pending> rows=pending.computeIfAbsent(chat,k->new ArrayList<>());if(rows.stream().noneMatch(row->row.id.equals(id)))rows.add(new Pending(id,text,before));}
    synchronized JSONObject project(JSONObject thread)throws Exception{
        String chat=thread.optString("id");List<Pending> rows=pending.get(chat);if(rows==null||rows.isEmpty())return thread;
        Map<String,String> canonical=users(thread);Set<String> consumed=matched.computeIfAbsent(chat,k->new HashSet<>());JSONArray visible=new JSONArray();Iterator<Pending> iterator=rows.iterator();
        while(iterator.hasNext()){Pending row=iterator.next();String found=null;for(Map.Entry<String,String> entry:canonical.entrySet())if(!row.before.contains(entry.getKey())&&!consumed.contains(entry.getKey())&&row.text.trim().equals(entry.getValue())){found=entry.getKey();break;}
            if(found!=null){consumed.add(found);iterator.remove();}else visible.put(new JSONObject().put("id",row.id).put("text",row.text));
        }
        consumed.retainAll(canonical.keySet());if(rows.isEmpty())pending.remove(chat);JSONObject copy=new JSONObject();Iterator<String> keys=thread.keys();while(keys.hasNext()){String key=keys.next();copy.put(key,thread.get(key));}copy.put("confirmedMessages",visible);return copy;
    }
}
