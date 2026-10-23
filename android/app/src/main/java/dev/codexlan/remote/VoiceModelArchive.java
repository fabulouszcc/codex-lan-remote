package dev.codexlan.remote;
import java.io.*;
import java.util.zip.*;

/** Extract only the published model tree, with bounded file counts and expanded size. */
final class VoiceModelArchive {
    static final String NAME="vosk-model-small-cn-0.22";
    static void extract(File archive,File staging)throws Exception{
        String base=staging.getCanonicalPath()+File.separator;long total=0;int count=0;
        try(ZipInputStream zip=new ZipInputStream(new FileInputStream(archive))){ZipEntry entry;byte[] buffer=new byte[32768];while((entry=zip.getNextEntry())!=null){
            String name=entry.getName();if(++count>256||!name.startsWith(NAME+"/")||name.contains("\\"))throw new IOException("模型目录无效");
            File target=new File(staging,name);if(!target.getCanonicalPath().startsWith(base))throw new IOException("模型目录无效");
            if(entry.isDirectory()){if(!target.isDirectory()&&!target.mkdirs())throw new IOException("无法创建模型目录");continue;}
            if(!target.getParentFile().isDirectory()&&!target.getParentFile().mkdirs())throw new IOException("无法创建模型目录");
            try(OutputStream out=new FileOutputStream(target)){int n;while((n=zip.read(buffer))!=-1){total+=n;if(total>200L*1024*1024)throw new IOException("模型解压内容过大");out.write(buffer,0,n);}}
        }}
        validate(new File(staging,NAME));
    }
    static void validate(File model)throws IOException{for(String path:new String[]{"am/final.mdl","graph/HCLr.fst","graph/Gr.fst","conf/model.conf"})if(new File(model,path).length()==0)throw new IOException("模型文件不完整");}
}
