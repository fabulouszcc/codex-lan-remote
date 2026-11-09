package dev.codexlan.remote;

import android.util.JsonReader;
import android.util.JsonToken;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/** No whole-document String or UTF-8 byte array while writing or comparing. */
final class MemoryJson {
    static void write(Object value, Writer out) throws Exception {
        write(value,out,false);
    }
    private static void write(Object value,Writer out,boolean ordered)throws Exception{
        if(value==null||value==JSONObject.NULL){out.write("null");return;}
        if(value instanceof JSONObject){
            JSONObject object=(JSONObject)value;out.write('{');boolean comma=false;
            java.util.List<String> names=new java.util.ArrayList<>();java.util.Iterator<String> keys=object.keys();while(keys.hasNext())names.add(keys.next());if(ordered)java.util.Collections.sort(names);
            for(String key:names){
                if(comma)out.write(',');comma=true;string(key,out);out.write(':');write(object.get(key),out,ordered);
            }out.write('}');return;
        }
        if(value instanceof JSONArray){JSONArray array=(JSONArray)value;out.write('[');for(int i=0;i<array.length();i++){if(i>0)out.write(',');write(array.get(i),out,ordered);}out.write(']');return;}
        if(value instanceof Number){out.write(JSONObject.numberToString((Number)value));return;}
        if(value instanceof Boolean){out.write(value.toString());return;}
        string(String.valueOf(value),out);
    }
    private static void part(String value,int from,int to,Writer out)throws IOException{
        while(from<to){int count=Math.min(2048,to-from);out.write(value,from,count);from+=count;}
    }
    private static void string(String value,Writer out)throws IOException{
        out.write('"');int start=0;
        for(int i=0;i<value.length();i++){
            char c=value.charAt(i);String escaped=null;
            switch(c){case '"':escaped="\\\"";break;case '\\':escaped="\\\\";break;case '\b':escaped="\\b";break;case '\f':escaped="\\f";break;case '\n':escaped="\\n";break;case '\r':escaped="\\r";break;case '\t':escaped="\\t";break;default:if(c<32)escaped=String.format(java.util.Locale.ROOT,"\\u%04x",(int)c);}
            if(escaped!=null){part(value,start,i,out);out.write(escaped);start=i+1;}
        }part(value,start,value.length(),out);out.write('"');
    }
    static String fingerprint(Object value){
        try{
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            OutputStream sink=new OutputStream(){public void write(int b){}public void write(byte[] b,int off,int len){}};
            Writer writer=new BufferedWriter(new OutputStreamWriter(new DigestOutputStream(sink,digest),StandardCharsets.UTF_8),4096);
            write(value,writer,true);writer.flush();StringBuilder hex=new StringBuilder(64);for(byte b:digest.digest())hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return hex.toString();
        }catch(Exception e){throw new IllegalArgumentException("Cannot fingerprint chat state",e);}
    }
    static JSONObject readObject(Reader input)throws Exception{
        JsonReader reader=new JsonReader(input);Object value=read(reader);
        if(!(value instanceof JSONObject)||reader.peek()!=JsonToken.END_DOCUMENT)throw new IOException("Invalid history document");
        return (JSONObject)value;
    }
    private static Object read(JsonReader r)throws Exception{
        switch(r.peek()){
            case BEGIN_OBJECT:JSONObject object=new JSONObject();r.beginObject();while(r.hasNext())object.put(r.nextName(),read(r));r.endObject();return object;
            case BEGIN_ARRAY:JSONArray array=new JSONArray();r.beginArray();while(r.hasNext())array.put(read(r));r.endArray();return array;
            case STRING:return r.nextString();
            case BOOLEAN:return r.nextBoolean();
            case NULL:r.nextNull();return JSONObject.NULL;
            case NUMBER:String number=r.nextString();try{return Long.valueOf(number);}catch(NumberFormatException ignored){return Double.valueOf(number);}
            default:throw new IOException("Invalid history value");
        }
    }
}
