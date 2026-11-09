package dev.codexlan.remote;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.*;
import java.lang.ref.SoftReference;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;

/** Lossless atomic history, bounded encryption buffers and shared startup reads. */
final class HistoryCache {
    private static final Object LOCK=new Object();
    private static String cachedPath="";
    private static long cachedStamp;
    private static SoftReference<JSONObject> cached=new SoftReference<>(null);
    private final AtomicFile file;
    private final SecretKey suppliedKey;
    HistoryCache(Context context,String fingerprint)throws Exception{this(location(context,fingerprint),null);}
    HistoryCache(File target,SecretKey key)throws Exception{
        if(!target.getParentFile().isDirectory()&&!target.getParentFile().mkdirs())throw new IOException("Cannot create history directory");
        file=new AtomicFile(target);suppliedKey=key;
    }
    private static File location(Context context,String fingerprint)throws Exception{
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(fingerprint.getBytes(StandardCharsets.UTF_8));
        StringBuilder name=new StringBuilder();for(byte b:digest)name.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        return new File(new File(context.getFilesDir(),"history"),name+".bin");
    }
    private SecretKey key()throws Exception{return suppliedKey!=null?suppliedKey:SecureSession.key();}
    private void remember(JSONObject value){cachedPath=file.getBaseFile().getAbsolutePath();cachedStamp=file.getBaseFile().lastModified();cached=new SoftReference<>(value);}
    JSONObject load()throws Exception{
        synchronized(LOCK){
            JSONObject existing=cached.get();
            if(existing!=null&&cachedPath.equals(file.getBaseFile().getAbsolutePath())&&cachedStamp==file.getBaseFile().lastModified())return existing;
            try(InputStream input=file.openRead()){
                JSONObject value=EncryptedHistory.read(input,key());remember(value);return value;
            }catch(FileNotFoundException missing){return new JSONObject();}
        }
    }
    void save(JSONObject value)throws Exception{
        synchronized(LOCK){
            preserveLegacy();FileOutputStream output=null;
            try{output=file.startWrite();EncryptedHistory.write(output,key(),value);file.finishWrite(output);remember(value);}
            catch(Exception error){if(output!=null)file.failWrite(output);throw error;}
        }
    }
    private void preserveLegacy()throws IOException{
        File base=file.getBaseFile(),backup=new File(base.getPath()+".legacy.bak");if(!base.isFile()||backup.exists())return;
        try(InputStream input=new FileInputStream(base)){
            byte[] header=new byte[4];if(input.read(header)==4&&java.util.Arrays.equals(header,EncryptedHistory.MAGIC))return;
        }
        AtomicFile preserved=new AtomicFile(backup);FileOutputStream output=null;
        try(InputStream input=new FileInputStream(base)){
            output=preserved.startWrite();byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1)output.write(buffer,0,n);preserved.finishWrite(output);
        }catch(IOException error){if(output!=null)preserved.failWrite(output);throw error;}
    }
    void clear(){synchronized(LOCK){file.delete();new AtomicFile(new File(file.getBaseFile().getPath()+".legacy.bak")).delete();cached.clear();cachedPath="";}}
}
