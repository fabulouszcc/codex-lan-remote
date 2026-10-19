package dev.codexlan.remote;

import org.json.*;
import java.net.URI;
import java.util.regex.*;

/** Public release metadata only. No desktop credentials enter GitHub requests. */
final class UpdatePolicy {
    static final String REPO="fabulouszcc/codex-lan-remote";
    static final String PAGE="https://github.com/"+REPO+"/releases";
    static final Pattern VERSION=Pattern.compile("^v?(\\d{1,6})\\.(\\d{1,6})\\.(\\d{1,6})(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$");
    static int[] numbers(String value){Matcher m=VERSION.matcher(value);if(!m.matches())return null;return new int[]{Integer.parseInt(m.group(1)),Integer.parseInt(m.group(2)),Integer.parseInt(m.group(3))};}
    static int compare(String a,String b){
        int[] left=numbers(a),right=numbers(b);if(left==null||right==null)return 0;
        for(int i=0;i<3;i++)if(left[i]!=right[i])return Integer.compare(left[i],right[i]);
        Matcher x=VERSION.matcher(a),y=VERSION.matcher(b);x.matches();y.matches();String ap=x.group(4),bp=y.group(4);if(ap==null||bp==null)return ap==bp?0:ap==null?1:-1;
        String[] ax=ap.split("\\."),by=bp.split("\\.");for(int i=0;i<Math.min(ax.length,by.length);i++){String av=ax[i],bv=by[i];int c;if(av.matches("\\d+")&&bv.matches("\\d+"))c=new java.math.BigInteger(av).compareTo(new java.math.BigInteger(bv));else if(av.matches("\\d+")!=bv.matches("\\d+"))c=av.matches("\\d+")?-1:1;else c=av.compareTo(bv);if(c!=0)return c;}return Integer.compare(ax.length,by.length);
    }
    static boolean majorMinor(String available,String installed){int[] a=numbers(available),b=numbers(installed);return a!=null&&b!=null&&(a[0]>b[0]||a[0]==b[0]&&a[1]>b[1]);}
    static boolean notify(String available,String installed,String ignored,boolean manual){return numbers(available)!=null&&numbers(installed)!=null&&compare(available,installed)>0&&(manual||!available.equals(ignored)&&majorMinor(available,installed));}
    static String downloadUrl(String value){
        try{URI url=new URI(value);if(!"https".equals(url.getScheme())||!"github.com".equalsIgnoreCase(url.getHost())||url.getUserInfo()!=null||url.getPort()!=-1||url.getQuery()!=null||url.getFragment()!=null||!url.getPath().startsWith("/"+REPO+"/releases/download/")||url.getPath().contains("/../"))return "";return url.toASCIIString();}catch(Exception e){return "";}
    }
    static JSONObject asset(String name,String url,String digest,long size)throws Exception {String safe=downloadUrl(url);if(safe.isEmpty()||size>512L*1024*1024)return null;return new JSONObject().put("name",name).put("url",safe).put("digest",digest==null?"":digest).put("size",Math.max(0,size));}
    static JSONObject release(String tag,String notes,JSONArray assets)throws Exception {
        if(numbers(tag)==null)return null;JSONObject result=new JSONObject().put("tag",tag).put("page",PAGE+"/tag/"+tag).put("notes",notes==null?"":notes.substring(0,Math.min(12000,notes.length())));
        for(int i=0;i<assets.length();i++){JSONObject asset=assets.optJSONObject(i);if(asset==null)continue;String name=asset.optString("name").toLowerCase(java.util.Locale.ROOT);if(downloadUrl(asset.optString("url")).isEmpty())continue;if(name.startsWith("codex-lan-")&&name.endsWith(".apk")&&!name.contains("x86")&&!name.contains("armeabi")&&!result.has("android"))result.put("android",asset);if(name.startsWith("codex-lan-desktop-")&&name.endsWith(".zip")&&!result.has("desktop"))result.put("desktop",asset);}
        Matcher marker=Pattern.compile("<!--\\s*codex-lan-update:\\s*(\\{[^>]+\\})\\s*-->").matcher(notes==null?"":notes);if(marker.find())try{String min=new JSONObject(marker.group(1)).optString("minDesktopVersion");if(numbers(min)!=null)result.put("minDesktopVersion",min);}catch(Exception ignored){}
        return result;
    }
    static JSONObject latest(JSONArray values,String installed)throws Exception {
        JSONObject best=null;boolean preview=installed.contains("-");
        for(int i=0;i<values.length();i++){
            JSONObject release=values.optJSONObject(i);if(release==null||release.optBoolean("draft")||!preview&&release.optBoolean("prerelease"))continue;String tag=release.optString("tag_name");if(numbers(tag)==null||!preview&&tag.contains("-"))continue;
            JSONArray assets=new JSONArray(),raw=release.optJSONArray("assets");if(raw!=null)for(int j=0;j<raw.length();j++){JSONObject entry=raw.optJSONObject(j);if(entry==null||!"uploaded".equals(entry.optString("state","uploaded")))continue;JSONObject file=asset(entry.optString("name"),entry.optString("browser_download_url"),entry.optString("digest"),entry.optLong("size"));if(file!=null)assets.put(file);}
            JSONObject candidate=release(tag,release.optString("body"),assets);if(candidate!=null&&(candidate.has("android")||candidate.has("desktop"))&&(best==null||compare(tag,best.optString("tag"))>0))best=candidate;
        }
        return best;
    }
}
