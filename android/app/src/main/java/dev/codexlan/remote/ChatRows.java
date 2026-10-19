package dev.codexlan.remote;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Presentation data only; prepare off the UI thread, instantiate views on demand. */
final class ChatRows {
    static String stripAmbient(String value){
        String text=value.trim();boolean changed=false;
        for(int pass=0;pass<24;pass++){
            String close=null;
            if(text.startsWith("<in-app-browser-context source=\"ambient-ui-state\""))close="</in-app-browser-context>";
            else if(text.startsWith("<external_codex_apps_open_page>"))close="</external_codex_apps_open_page>";
            if(close!=null){int end=text.indexOf(close);if(end<0)break;text=text.substring(end+close.length()).trim();changed=true;continue;}
            if(changed){String next=text.replaceFirst("^#{1,6}\\s*My request:\\s*","").trim();if(!next.equals(text)){text=next;continue;}}
            break;
        }
        return changed?text:value;
    }
    static String userText(String value){
        String text=stripAmbient(value),wrapper=text.trim();
        if(wrapper.startsWith("# Files mentioned by the user:")||wrapper.startsWith("# Files pasted by the user:")){
            text=wrapper;
            java.util.regex.Matcher request=java.util.regex.Pattern.compile("(?m)^## My request:\\s*").matcher(text);
            if(request.find())text=text.substring(request.end()).trim();
            else text="";
            text=text.replaceAll("<image\\b[^>]*>\\s*(?:</image>)?","").trim();
            return text.isEmpty()?"附件":displayText(text);
        }
        return text.isEmpty()?"已同步页面上下文":displayText(text);
    }
    static String displayText(String value){
        String trimmed=value.trim();
        if(!trimmed.startsWith("<send_user_message_question_reply>")||!trimmed.endsWith("</send_user_message_question_reply>"))return value;
        try{
            Object parsed=new org.json.JSONTokener(trimmed.substring(34,trimmed.length()-35)).nextValue();
            JSONArray replies=parsed instanceof JSONArray?(JSONArray)parsed:new JSONArray().put(parsed);StringBuilder result=new StringBuilder();
            for(int i=0;i<replies.length();i++){JSONObject reply=replies.getJSONObject(i);if(result.length()>0)result.append("\n\n");String question=reply.optString("question");if(!question.isEmpty())result.append(question).append("\n");result.append("已回复：").append(reply.getString("answer"));}
            return result.toString();
        }catch(Exception ignored){return value;}
    }
    static String navigationText(JSONObject item){
        String value=stripAmbient(item.optString("text")).trim();
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
            int lastMessage=-1;for(int j=0;j<timeline.length();j++)if("message".equals(timeline.getJSONObject(j).optString("type")))lastMessage=j;
            for(int j=0;j<timeline.length();j++) if("tool".equals(timeline.getJSONObject(j).optString("type"))) tools.put(timeline.getJSONObject(j));
            boolean grouped=false;
            for(int j=0;j<timeline.length();j++) {
                JSONObject item=timeline.getJSONObject(j);String type=item.optString("type");
                if("tool".equals(type)) {
                    if(!grouped)rows.add(new Row(prefix+"tools","tools",new JSONObject().put("items",tools)));
                    grouped=true;
                } else if(("user".equals(type)||"message".equals(type))&&(!item.optString("text").isEmpty()||item.optJSONArray("files")!=null&&item.optJSONArray("files").length()>0)) {
                    JSONObject display=item;
                    boolean finalFiles="completed".equals(turn.optString("status"))&&("final_answer".equals(item.optString("phase"))||j==lastMessage&&!"commentary".equals(item.optString("phase")));
                    if("message".equals(type)&&!finalFiles){display=new JSONObject();java.util.Iterator<String> keys=item.keys();while(keys.hasNext()){String key=keys.next();if(!"files".equals(key))display.put(key,item.get(key));}}
                    rows.add(new Row(prefix+item.optString("id","item-"+j),type,display));
                }
            }
        }
        JSONObject activity=thread.optJSONObject("activity");
        if(thread.optBoolean("ready")&&!thread.optString("activeTurnId").isEmpty())rows.add(new Row(chat+":activity","activity",activity==null?new JSONObject().put("label","正在思考…"):activity));
        JSONArray approvals=thread.optJSONArray("approvals");
        JSONArray questions=thread.optJSONArray("questions");
        if(thread.optBoolean("ready")&&!MobileState.activeTurnId(thread).isEmpty()&&questions!=null)for(int i=0;i<questions.length();i++)rows.add(new Row(chat+":question:"+questions.getJSONObject(i).optString("id"),"question",questions.getJSONObject(i)));
        if(approvals!=null)for(int i=0;i<approvals.length();i++)rows.add(new Row(chat+":approval:"+approvals.getJSONObject(i).optString("id",String.valueOf(i)),"approval",approvals.getJSONObject(i)));
        return rows;
    }
}
