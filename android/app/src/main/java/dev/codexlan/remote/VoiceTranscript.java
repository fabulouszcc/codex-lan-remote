package dev.codexlan.remote;
import org.json.JSONObject;
final class VoiceTranscript {
    static String text(String json,String key){try{return new JSONObject(json).optString(key).trim().replaceAll("(?<=\\p{IsHan})\\s+(?=\\p{IsHan})","");}catch(Exception ignored){return "";}}
}
