package dev.codexlan.remote;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.provider.OpenableColumns;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

/** URI metadata only: large files are streamed when the user explicitly sends. */
final class PhoneAttachments {
    static final int REQUEST=209;
    static final long MAX_BYTES=32L*1024*1024;
    static final class Item {
        final Uri uri;final String name;final long size;
        Item(Uri uri,String name,long size){this.uri=uri;this.name=name;this.size=size;}
    }
    final Map<String,List<Item>> drafts=new HashMap<>();
    String picking="";
    List<Item> snapshot(String key){return new ArrayList<>(drafts.getOrDefault(key,Collections.emptyList()));}
    boolean has(String key){return !snapshot(key).isEmpty();}
    void pick(Activity activity,String key){picking=key;Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);activity.startActivityForResult(intent,REQUEST);}
    List<Item> read(Activity activity,Intent data)throws Exception{
        List<Uri> uris=new ArrayList<>();if(data.getClipData()!=null){for(int i=0;i<data.getClipData().getItemCount();i++){if(uris.size()>=8)throw new Exception("每次最多选择 8 个文件");uris.add(data.getClipData().getItemAt(i).getUri());}}else if(data.getData()!=null)uris.add(data.getData());
        List<Item> result=new ArrayList<>();
        for(Uri uri:uris){
            if(!"content".equals(uri.getScheme()))throw new Exception("请选择文件提供程序中的文件");
            String name="";long size=-1;
            try(android.database.Cursor cursor=activity.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE},null,null,null)){if(cursor!=null&&cursor.moveToFirst()){name=cursor.getString(0);if(!cursor.isNull(1))size=cursor.getLong(1);}}
            if(name==null||name.isEmpty()||name.length()>160)throw new Exception("无法读取文件名");
            if(size>MAX_BYTES||size==0)throw new Exception("请选择非空且不超过 32 MB 的文件");
            if((data.getFlags()&Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)!=0)try{activity.getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(SecurityException ignored){}
            result.add(new Item(uri,name,size));
        }
        return result;
    }
    void add(String key,List<Item> values)throws Exception{List<Item> next=snapshot(key);for(Item item:values)if(next.stream().noneMatch(old->old.uri.equals(item.uri)))next.add(item);if(next.size()>8)throw new Exception("每条消息最多附加 8 个文件");drafts.put(key,next);}
    void remove(String key,Item item){List<Item> next=snapshot(key);next.remove(item);drafts.put(key,next);}
    void accepted(String key,List<Item> sent){List<Item> next=snapshot(key);next.removeAll(sent);drafts.put(key,next);}
    String save(){try{JSONObject out=new JSONObject();for(String key:drafts.keySet()){JSONArray rows=new JSONArray();for(Item item:snapshot(key))rows.put(new JSONObject().put("uri",item.uri.toString()).put("name",item.name).put("size",item.size));out.put(key,rows);}return out.toString();}catch(Exception ignored){return "{}";}}
    void restore(String raw){try{JSONObject root=new JSONObject(raw);Iterator<String> keys=root.keys();while(keys.hasNext()){String key=keys.next();JSONArray rows=root.getJSONArray(key);List<Item> values=new ArrayList<>();for(int i=0;i<Math.min(8,rows.length());i++){JSONObject row=rows.getJSONObject(i);values.add(new Item(Uri.parse(row.getString("uri")),row.getString("name"),row.getLong("size")));}drafts.put(key,values);}}catch(Exception ignored){}}
}
