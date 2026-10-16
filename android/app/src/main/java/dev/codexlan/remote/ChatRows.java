package dev.codexlan.remote;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Presentation data only; prepare off the UI thread, instantiate views on demand. */
final class ChatRows {
    static String navigationText(JSONObject item){
        String value=item.optString("text").trim();
        if(value.startsWith("<send_user_message_question_reply>"))try{
            JSONArray replies=new JSONArray(value.substring(value.indexOf('['),value.lastIndexOf(']')+1));StringBuilder answers=new StringBuilder();
            for(int i=0;i<replies.length();i++){String answer=replies.getJSONObject(i).optString("answer");if(!answer.isEmpty()){if(answers.length()>0)answers.append("；");answers.append(answer);}}
            if(answers.length()>0)value=answers.toString();
        }catch(Exception ignored){}
        int contextEnd=value.indexOf("</in-app-browser-context>");if(value.startsWith("<in-app-browser-context")&&contextEnd>=0)value=value.substring(contextEnd+25).trim();
        value=value.replaceFirst("^#+\\s*My request:\\s*","").replace('\n',' ').trim();
        return value.substring(0,Math.min(120,value.length()));
    }
    static final class Row {
        final String key, type, signature;
        final JSONObject data;
        Row(String key, String type, JSONObject data) {
            this.key=key; this.type=type; this.data=data; signature=type+":"+data.toString();
        }
    }
    static List<Row> build(JSONObject thread) throws Exception {
        List<Row> rows=new ArrayList<>();
        String chat=thread.optString("id");
        JSONArray turns=thread.optJSONArray("turns");
        if(turns==null || turns.length()==0) rows.add(new Row(chat+":welcome","welcome",new JSONObject()));
        else for(int i=0;i<turns.length();i++) {
            JSONObject turn=turns.getJSONObject(i);
            String prefix=chat+":"+turn.optString("id","turn-"+i)+":";
            JSONArray timeline=MobileState.timeline(turn), tools=new JSONArray();
            for(int j=0;j<timeline.length();j++) if("tool".equals(timeline.getJSONObject(j).optString("type"))) tools.put(timeline.getJSONObject(j));
            boolean grouped=false;
            for(int j=0;j<timeline.length();j++) {
                JSONObject item=timeline.getJSONObject(j);String type=item.optString("type");
                if("tool".equals(type)) {
                    if(!grouped)rows.add(new Row(prefix+"tools","tools",new JSONObject().put("items",tools)));
                    grouped=true;
                } else if(("user".equals(type)||"message".equals(type))&&!item.optString("text").isEmpty()) {
                    rows.add(new Row(prefix+item.optString("id","item-"+j),type,item));
                }
            }
        }
        JSONObject activity=thread.optJSONObject("activity");
        if(thread.optBoolean("ready")&&!thread.optString("activeTurnId").isEmpty())rows.add(new Row(chat+":activity","activity",activity==null?new JSONObject().put("label","正在思考…"):activity));
        JSONArray approvals=thread.optJSONArray("approvals");
        if(approvals!=null)for(int i=0;i<approvals.length();i++)rows.add(new Row(chat+":approval:"+approvals.getJSONObject(i).optString("id",String.valueOf(i)),"approval",approvals.getJSONObject(i)));
        return rows;
    }
}
