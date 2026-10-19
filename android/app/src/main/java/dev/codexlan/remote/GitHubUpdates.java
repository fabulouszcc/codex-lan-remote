package dev.codexlan.remote;

import org.json.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.regex.*;

final class GitHubUpdates {
    interface Fetch {String get(String target)throws Exception;}
    static final String API="https://api.github.com/repos/"+UpdatePolicy.REPO+"/releases?per_page=30";
    static String get(String target)throws Exception {
        HttpURLConnection connection=(HttpURLConnection)new URL(target).openConnection();
        try{connection.setConnectTimeout(7000);connection.setReadTimeout(10000);connection.setInstanceFollowRedirects(false);connection.setRequestProperty("User-Agent","Codex-LAN-Android");connection.setRequestProperty("Accept",target.startsWith("https://api.github.com/")?"application/vnd.github+json":"application/atom+xml,text/html");int code=connection.getResponseCode();if(code!=200)throw new IOException(code==403||code==429?"GitHub 暂时限制检查频率，请稍后再试":"GitHub 返回 "+code);try(InputStream in=connection.getInputStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){byte[] block=new byte[8192];int n;while((n=in.read(block))!=-1){if(bytes.size()+n>2*1024*1024)throw new IOException("更新信息过大");bytes.write(block,0,n);}return bytes.toString("UTF-8");}}finally{connection.disconnect();}
    }
    static JSONObject fetch(String current)throws Exception {
        return fetch(current,GitHubUpdates::get);
    }
    static JSONObject fetch(String current,Fetch source)throws Exception {
        try{JSONObject result=UpdatePolicy.latest(new JSONArray(source.get(API)),current);if(result!=null)return result;}catch(Exception apiError){/* Published feed still works when anonymous API quota is exhausted. */}
        return feed(source.get(UpdatePolicy.PAGE+".atom"),current,source);
    }
    static JSONObject feed(String xml,String current,Fetch source)throws Exception {
        javax.xml.parsers.DocumentBuilderFactory factory=javax.xml.parsers.DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);factory.setFeature("http://xml.org/sax/features/external-general-entities",false);factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        org.w3c.dom.NodeList entries=factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))).getElementsByTagNameNS("*","entry");String best="",notes="";
        for(int i=0;i<entries.getLength();i++){org.w3c.dom.Element entry=(org.w3c.dom.Element)entries.item(i);org.w3c.dom.NodeList links=entry.getElementsByTagNameNS("*","link");for(int j=0;j<links.getLength();j++){String href=((org.w3c.dom.Element)links.item(j)).getAttribute("href"),prefix=UpdatePolicy.PAGE+"/tag/";if(!href.startsWith(prefix))continue;String tag=href.substring(prefix.length());if(UpdatePolicy.numbers(tag)==null||!current.contains("-")&&tag.contains("-"))continue;if(best.isEmpty()||UpdatePolicy.compare(tag,best)>0){best=tag;org.w3c.dom.NodeList contents=entry.getElementsByTagNameNS("*","content");notes=contents.getLength()>0?contents.item(0).getTextContent().replaceAll("<[^>]+>",""):"";}}}
        if(best.isEmpty())throw new IOException("未找到可用的已发布版本");String html=source.get("https://github.com/"+UpdatePolicy.REPO+"/releases/expanded_assets/"+best);JSONArray assets=new JSONArray();Matcher links=Pattern.compile("href=\"(/"+UpdatePolicy.REPO+"/releases/download/[^\"]+)\"").matcher(html);
        while(links.find()){String path=links.group(1).replace("&amp;","&");String name=URLDecoder.decode(path.substring(path.lastIndexOf('/')+1),"UTF-8");JSONObject file=UpdatePolicy.asset(name,"https://github.com"+path,"",0);if(file!=null)assets.put(file);}return UpdatePolicy.release(best,notes,assets);
    }
}
