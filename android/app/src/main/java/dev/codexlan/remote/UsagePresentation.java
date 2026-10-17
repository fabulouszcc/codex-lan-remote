package dev.codexlan.remote;

import org.json.JSONObject;

final class UsagePresentation {
    static float remaining(JSONObject window) {
        Object value=window==null?null:window.opt("remainingPercent");
        if(!(value instanceof Number))return -1;
        double number=((Number)value).doubleValue();
        return Double.isFinite(number)&&number>=0&&number<=100?(float)number:-1;
    }
    static String period(JSONObject window,String fallback) {
        long minutes=window==null?0:window.optLong("windowDurationMins");
        if(minutes<=0)return fallback;
        if(minutes%1440==0)return(minutes/1440)+" 天";
        if(minutes%60==0)return(minutes/60)+" 小时";
        return minutes+" 分钟";
    }
    static String percent(JSONObject window) {
        float value=remaining(window);return value<0?"暂不可用":Math.round(value)+"% 剩余";
    }
    static String connection(JSONObject state,JSONObject thread,String message) {
        if(state==null)return "connecting";
        if(!state.optBoolean("online"))return message!=null&&(message.contains("中断")||message.contains("失败")||message.contains("无法"))?"error":"offline";
        if(thread==null||!thread.optBoolean("ready"))return "waiting";
        if((thread.optJSONArray("approvals")!=null&&thread.optJSONArray("approvals").length()>0)||(thread.optJSONArray("questions")!=null&&thread.optJSONArray("questions").length()>0))return "waiting";
        return MobileState.activeTurnId(thread).isEmpty()?"connected":"busy";
    }
}
