package dev.codexlan.remote;

final class ModelPresentation {
    static org.json.JSONArray sliderEfforts(org.json.JSONArray available,String current){
        boolean hasUltra=false;for(int i=0;i<available.length();i++)if("ultra".equals(available.optString(i)))hasUltra=true;
        org.json.JSONArray result=new org.json.JSONArray();for(int i=0;i<available.length();i++){String value=available.optString(i);if(hasUltra&&"max".equals(value)&&!"max".equals(current))continue;result.put(value);}return result;
    }
    static String name(String value){return value.replaceFirst("(?i)^gpt-","GPT-").replaceFirst("-(?i:sol|astra|luna|terra)$"," "+family(value));}
    private static String family(String value){String last=value.substring(value.lastIndexOf('-')+1);return last.isEmpty()?last:Character.toUpperCase(last.charAt(0))+last.substring(1).toLowerCase(java.util.Locale.ROOT);}
    static String effort(String value){switch(value){case "none":return "无";case "minimal":return "最低";case "low":return "轻度";case "medium":return "中";case "high":return "高";case "xhigh":return "极高";case "max":return "Max";case "ultra":return "Ultra";default:return value;}}
    static String speed(String description){return description.contains("2x")?"2 倍速度，用量更高":"1.5 倍速，增加使用量";}
}
