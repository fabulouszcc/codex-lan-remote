package dev.codexlan.remote;

import org.json.JSONObject;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Bounded authenticated chunks; final authenticated record detects truncation. */
final class EncryptedHistory {
    static final byte[] MAGIC=new byte[]{'C','L','H','2'};
    static final int CHUNK=64*1024;
    private static Cipher cipher(int mode,SecretKey key,byte[] iv,int index,int length)throws Exception{
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        if(mode==Cipher.ENCRYPT_MODE)cipher.init(mode,key);else cipher.init(mode,key,new GCMParameterSpec(128,iv));
        cipher.updateAAD(ByteBuffer.allocate(12).put(MAGIC).putInt(index).putInt(length).array());return cipher;
    }
    static void write(OutputStream target,SecretKey key,JSONObject value)throws Exception{
        DataOutputStream out=new DataOutputStream(target);out.write(MAGIC);
        ChunkOutput chunks=new ChunkOutput(out,key);Writer writer=new BufferedWriter(new OutputStreamWriter(chunks,StandardCharsets.UTF_8),4096);
        MemoryJson.write(value,writer);writer.flush();chunks.finish();out.flush();
    }
    static JSONObject read(InputStream source,SecretKey key)throws Exception{
        BufferedInputStream in=new BufferedInputStream(source);in.mark(4);byte[] magic=new byte[4];int n=new DataInputStream(in).read(magic);
        if(n==4&&Arrays.equals(magic,MAGIC))return MemoryJson.readObject(new InputStreamReader(new ChunkInput(new DataInputStream(in),key),StandardCharsets.UTF_8));
        in.reset();return MemoryJson.readObject(new InputStreamReader(legacy(in,key),StandardCharsets.UTF_8));
    }
    private static InputStream legacy(InputStream in,SecretKey key)throws Exception{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)bytes.write(buffer,0,n);
        byte[] encoded=bytes.toByteArray();if(encoded.length<28)throw new IOException("Incomplete legacy history");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,encoded,0,12));
        return new ByteArrayInputStream(cipher.doFinal(encoded,12,encoded.length-12));
    }
    private static final class ChunkOutput extends OutputStream{
        final DataOutputStream out;final SecretKey key;final byte[] buffer=new byte[CHUNK];int used,index;
        ChunkOutput(DataOutputStream out,SecretKey key){this.out=out;this.key=key;}
        public void write(int b)throws IOException{buffer[used++]=(byte)b;if(used==buffer.length)seal();}
        public void write(byte[] b,int off,int len)throws IOException{while(len>0){int n=Math.min(len,buffer.length-used);System.arraycopy(b,off,buffer,used,n);off+=n;len-=n;used+=n;if(used==buffer.length)seal();}}
        void seal()throws IOException{try{Cipher cipher=cipher(Cipher.ENCRYPT_MODE,key,null,index++,used);byte[] encrypted=cipher.doFinal(buffer,0,used);out.writeInt(used);out.write(cipher.getIV());out.write(encrypted);used=0;}catch(Exception e){throw new IOException("History encryption failed",e);}}
        void finish()throws IOException{if(used>0)seal();seal();}
    }
    private static final class ChunkInput extends InputStream{
        final DataInputStream in;final SecretKey key;byte[] buffer=new byte[0];int offset,index;boolean ended;
        ChunkInput(DataInputStream in,SecretKey key){this.in=in;this.key=key;}
        private boolean next()throws IOException{
            if(ended)return false;
            try{int length=in.readInt();if(length<0||length>CHUNK)throw new IOException("Invalid history chunk");byte[] iv=new byte[12],encoded=new byte[length+16];in.readFully(iv);in.readFully(encoded);
                buffer=cipher(Cipher.DECRYPT_MODE,key,iv,index++,length).doFinal(encoded);offset=0;
                if(length==0){ended=true;if(in.read()!=-1)throw new IOException("Trailing history data");return false;}return true;
            }catch(Exception e){throw new IOException("History authentication failed",e);}
        }
        public int read()throws IOException{if(offset==buffer.length&&!next())return -1;return buffer[offset++]&255;}
        public int read(byte[] b,int off,int len)throws IOException{if(len==0)return 0;if(offset==buffer.length&&!next())return -1;int n=Math.min(len,buffer.length-offset);System.arraycopy(buffer,offset,b,off,n);offset+=n;return n;}
    }
}
