package dev.codexlan.remote;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import javax.crypto.*;
import java.security.KeyStore;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

public final class MemoryDeviceTest {
    static final class NoSerialization extends JSONObject {public String toString(){throw new AssertionError("Whole history serialization");}}
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        Class.forName("android.security.keystore2.AndroidKeyStoreProvider").getMethod("install").invoke(null);
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        String alias="codex_lan_memory_regression_v1";
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        SecretKey key=generator.generateKey();try{run(args,key);}finally{store.deleteEntry(alias);}
    }
    private static void run(String[] args,SecretKey key)throws Exception{
        File root=new File(args[0]);if(!root.getCanonicalPath().contains("/cache/codex-memory-regression-"))throw new IllegalArgumentException("Use the isolated regression cache directory");root.mkdirs();
        String body=new String(new char[512*1024]).replace('\0','x')+"中文\n\"\\🙂";
        JSONArray turns=new JSONArray();for(int i=0;i<50;i++)turns.put(new JSONObject().put("id","t"+i).put("input",body));
        JSONObject state=new NoSerialization().put("online",true).put("threads",new JSONArray().put(new JSONObject().put("id","large").put("turns",turns)));
        File file=new File(root,"large.bin");HistoryCache cache=new HistoryCache(file,key);
        long started=System.currentTimeMillis();cache.save(state);
        JSONObject restored;try(InputStream input=new FileInputStream(file)){restored=EncryptedHistory.read(input,key);}
        check(restored.getJSONArray("threads").getJSONObject(0).getJSONArray("turns").length()==50,"All turns must survive");
        check(MemoryJson.fingerprint(state).equals(MemoryJson.fingerprint(restored)),"All text and metadata must survive");
        check(cache.load()==state,"Startup reads should share an existing parsed snapshot");
        long size=file.length();
        try(RandomAccessFile edit=new RandomAccessFile(file,"rw")){edit.seek(24);int old=edit.read();edit.seek(24);edit.write(old^1);}
        boolean bad=false;try(InputStream input=new FileInputStream(file)){EncryptedHistory.read(input,key);}catch(Exception expected){bad=true;}check(bad,"Tampering must fail authentication");
        cache.save(state);
        try(RandomAccessFile edit=new RandomAccessFile(file,"rw")){edit.setLength(size-3);}
        bad=false;try(InputStream input=new FileInputStream(file)){EncryptedHistory.read(input,key);}catch(Exception expected){bad=true;}check(bad,"Truncation must be rejected");
        cache.save(state);
        Object fails=new Object(){public String toString(){throw new IllegalStateException("Injected write failure");}};
        bad=false;try{cache.save(new JSONObject().put("safe","prefix").put("fails",fails));}catch(Exception expected){bad=true;}check(bad,"Injected write must fail");
        try(InputStream input=new FileInputStream(file)){check(MemoryJson.fingerprint(state).equals(MemoryJson.fingerprint(EncryptedHistory.read(input,key))),"Failed write must retain prior history");}
        File oldFile=new File(root,"legacy.bin");Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key);
        byte[] encrypted=cipher.doFinal("{\"threads\":[{\"id\":\"old\",\"text\":\"保留旧记录\"}]}".getBytes(StandardCharsets.UTF_8));
        try(OutputStream output=new FileOutputStream(oldFile)){output.write(cipher.getIV());output.write(encrypted);}
        byte[] legacy=Files.readAllBytes(oldFile.toPath());HistoryCache old=new HistoryCache(oldFile,key);JSONObject previous=old.load();old.save(previous);
        check(java.util.Arrays.equals(legacy,Files.readAllBytes(new File(oldFile.getPath()+".legacy.bak").toPath())),"Original encrypted legacy backup must be byte-identical");
        old.clear();check(!oldFile.exists()&&!new File(oldFile.getPath()+".legacy.bak").exists(),"Explicit cache clearing must also remove the legacy backup");
        System.out.println("PASS: 50 turns / "+size+" encrypted bytes preserved; corruption/truncation rejected; atomic failure retains prior data; legacy backup retained; elapsedMs="+(System.currentTimeMillis()-started));
    }
}

