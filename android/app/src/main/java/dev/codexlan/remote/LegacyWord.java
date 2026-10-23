package dev.codexlan.remote;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;

/** Read Word 97–2003 main-body piece tables only; no macros, fields or OLE objects. */
final class LegacyWord {
    private final byte[] bytes;private final int sector;private final int[] fat;private byte[] mini;private int[] miniFat;
    private static IOException invalid(){return new IOException("此 Word 文件不支持预览，请使用其他应用打开");}
    private int u16(byte[] b,int p)throws IOException{range(b,p,2);return (b[p]&255)|((b[p+1]&255)<<8);}
    private int i32(byte[] b,int p)throws IOException{range(b,p,4);return ByteBuffer.wrap(b,p,4).order(ByteOrder.LITTLE_ENDIAN).getInt();}
    private static void range(byte[] b,int p,int n)throws IOException{if(p<0||n<0||p>b.length-n)throw invalid();}
    private byte[] sector(int id)throws IOException{if(id<0||id>=bytes.length/sector-1)throw invalid();return Arrays.copyOfRange(bytes,(id+1)*sector,(id+2)*sector);}
    private int[] ints(byte[] b)throws IOException{int[] a=new int[b.length/4];for(int i=0;i<a.length;i++)a[i]=i32(b,i*4);return a;}
    private byte[] chain(int first,int[] links,byte[] source,int size,int max)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();Set<Integer> seen=new HashSet<>();int id=first;
        while(id>=0){if(id>=links.length||!seen.add(id)||out.size()>max-size)throw invalid();byte[] part;if(source==null)part=sector(id);else{range(source,id*size,size);part=Arrays.copyOfRange(source,id*size,(id+1)*size);}out.write(part);id=links[id];}
        if(id!=-2&&id!=-1)throw invalid();return out.toByteArray();
    }
    private LegacyWord(byte[] b)throws IOException{
        bytes=b;range(b,0,512);byte[] magic={(byte)0xd0,(byte)0xcf,0x11,(byte)0xe0,(byte)0xa1,(byte)0xb1,0x1a,(byte)0xe1};if(!Arrays.equals(Arrays.copyOf(b,8),magic))throw invalid();
        int shift=u16(b,30);if((shift!=9&&shift!=12)||u16(b,32)!=6)throw invalid();sector=1<<shift;
        List<Integer> ids=new ArrayList<>();for(int i=0;i<109;i++){int id=i32(b,76+i*4);if(id>=0)ids.add(id);}
        int next=i32(b,68),count=i32(b,72);if(count<0||count>b.length/sector)throw invalid();Set<Integer> seen=new HashSet<>();
        for(int i=0;i<count;i++){if(!seen.add(next))throw invalid();byte[] difat=sector(next);for(int p=0;p<sector-4;p+=4){int id=i32(difat,p);if(id>=0)ids.add(id);}next=i32(difat,sector-4);}
        ByteArrayOutputStream all=new ByteArrayOutputStream();if(ids.size()>b.length/sector)throw invalid();for(int id:ids)all.write(sector(id),0,sector);fat=ints(all.toByteArray());
    }
    private String extract()throws IOException{
        byte[] dir=chain(i32(bytes,48),fat,null,sector,32*1024*1024);Map<String,int[]> streams=new HashMap<>();int[] root=null;
        for(int p=0;p+128<=dir.length;p+=128){int len=u16(dir,p+64),type=dir[p+66]&255;if(type!=2&&type!=5)continue;if(len<2||len>64||len%2!=0)throw invalid();int length=i32(dir,p+120);if(length<0||length>32*1024*1024)throw invalid();int[] value={i32(dir,p+116),length};if(type==5)root=value;else streams.put(new String(dir,p,len-2,StandardCharsets.UTF_16LE),value);}
        if(root!=null&&root[1]>0){mini=Arrays.copyOf(chain(root[0],fat,null,sector,32*1024*1024),root[1]);miniFat=ints(chain(i32(bytes,60),fat,null,sector,32*1024*1024));}
        byte[] word=stream(streams.get("WordDocument"));if(u16(word,0)!=0xa5ec||(u16(word,10)&0x100)!=0)throw invalid();
        byte[] table=stream(streams.get((u16(word,10)&0x200)!=0?"1Table":"0Table"));int csw=u16(word,32),p=34+csw*2,cslw=u16(word,p),lw=p+2;p=lw+cslw*4;
        if(cslw<4||u16(word,p)<=33)throw invalid();int ccp=i32(word,lw+12),fc=i32(word,p+2+33*8),length=i32(word,p+6+33*8);range(table,fc,length);int end=fc+length;
        while(fc<end&&table[fc]==1){fc+=3+u16(table,fc+1);}if(fc>=end||table[fc++]!=2)throw invalid();int size=i32(table,fc);fc+=4;range(table,fc,size);if(size<4||(size-4)%12!=0)throw invalid();int pieces=(size-4)/12;StringBuilder out=new StringBuilder();
        for(int i=0;i<pieces;i++){int start=i32(table,fc+i*4),stop=Math.min(ccp,i32(table,fc+(i+1)*4));if(start>=ccp)break;int chars=stop-start;if(chars<0||chars>DocumentContent.LIMIT-out.length())throw invalid();int offset=i32(table,fc+(pieces+1)*4+i*8+2);boolean compressed=(offset&0x40000000)!=0;offset&=0x3fffffff;if(compressed)offset/=2;range(word,offset,chars*(compressed?1:2));out.append(new String(word,offset,chars*(compressed?1:2),compressed?Charset.forName("windows-1252"):StandardCharsets.UTF_16LE));}
        return out.toString().replace('\r','\n').replace('\u0007','\t').replaceAll("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f]","");
    }
    private byte[] stream(int[] ref)throws IOException{if(ref==null)throw invalid();byte[] result=ref[1]<4096&&mini!=null?chain(ref[0],miniFat,mini,64,32*1024*1024):chain(ref[0],fat,null,sector,32*1024*1024);if(result.length<ref[1])throw invalid();return Arrays.copyOf(result,ref[1]);}
    static String read(File file)throws Exception{byte[] b;try(InputStream in=new FileInputStream(file)){b=DocumentContent.bounded(in,32*1024*1024);}return new LegacyWord(b).extract();}
}
