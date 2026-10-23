package dev.codexlan.remote;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import java.util.zip.*;
import javax.xml.parsers.*;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;

/** Offline text/layout preview. Never executes macros, follows links or loads images. */
final class DocumentContent {
    static final int LIMIT=1024*1024;
    static String text(File file)throws Exception{
        byte[] bytes;try(InputStream in=new FileInputStream(file)){bytes=bounded(in,LIMIT);}
        if(bytes.length>=2&&(bytes[0]&255)==255&&(bytes[1]&255)==254)return new String(bytes,2,bytes.length-2,StandardCharsets.UTF_16LE);
        if(bytes.length>=2&&(bytes[0]&255)==254&&(bytes[1]&255)==255)return new String(bytes,2,bytes.length-2,StandardCharsets.UTF_16BE);
        try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().replaceFirst("^\uFEFF","");}
        catch(CharacterCodingException e){return new String(bytes,Charset.forName("GB18030"));}
    }
    static byte[] bounded(InputStream in,int limit)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] block=new byte[8192];int n;
        while((n=in.read(block))!=-1){if(out.size()+n>limit)throw new IOException("文档内容过大，请使用其他应用打开");out.write(block,0,n);}return out.toByteArray();
    }
    static String word(File file)throws Exception{
        if(file.getName().toLowerCase(Locale.ROOT).endsWith(".doc"))return LegacyWord.read(file);
        try(ZipFile zip=new ZipFile(file)){
            ZipEntry entry=zip.getEntry("word/document.xml");if(entry==null)throw new IOException("不是有效的 Word 文档");
            byte[] xml;try(InputStream in=zip.getInputStream(entry)){xml=bounded(in,8*1024*1024);}
            // Reject declarations before parsing even on platforms without JAXP security attributes.
            String check=new String(xml,StandardCharsets.UTF_8).replace("\0","");
            if(check.toUpperCase(Locale.ROOT).contains("<!DOCTYPE")||check.toUpperCase(Locale.ROOT).contains("<!ENTITY"))throw new IOException("文档包含不支持的 XML 声明");
            SAXParserFactory factory=SAXParserFactory.newInstance();factory.setNamespaceAware(true);
            XMLReader reader=factory.newSAXParser().getXMLReader();reader.setEntityResolver((publicId,systemId)->{throw new SAXException("禁止外部资源");});
            StringBuilder out=new StringBuilder();DefaultHandler handler=new DefaultHandler(){boolean inText=false;int heading=0;boolean numbered=false;StringBuilder paragraph=new StringBuilder();
                public void startElement(String uri,String name,String q,Attributes a)throws SAXException{
                    if(!"http://schemas.openxmlformats.org/wordprocessingml/2006/main".equals(uri))return;
                    if(name.equals("p")){paragraph.setLength(0);heading=0;numbered=false;}
                    if(name.equals("t"))inText=true;
                    if(name.equals("tab"))paragraph.append("    ");if(name.equals("br"))paragraph.append('\n');
                    if(name.equals("pStyle")){String style=a.getValue(uri,"val");if(style!=null&&style.matches("(?i)heading[1-6]"))heading=style.charAt(style.length()-1)-'0';}
                    if(name.equals("numPr"))numbered=true;
                    if(out.length()+paragraph.length()>LIMIT)throw new SAXException("文档内容过大，请使用其他应用打开");
                }
                public void characters(char[] ch,int start,int length){if(inText)paragraph.append(ch,start,length);}
                public void endElement(String uri,String name,String q){
                    if(!"http://schemas.openxmlformats.org/wordprocessingml/2006/main".equals(uri))return;
                    if(name.equals("t"))inText=false;
                    if(name.equals("p")){for(int i=0;i<heading;i++)out.append('#');if(heading>0)out.append(' ');if(numbered)out.append("• ");out.append(paragraph).append('\n');}
                    if(name.equals("tc"))out.append("    ");if(name.equals("tr"))out.append('\n');
                }
            };
            reader.setContentHandler(handler);reader.parse(new InputSource(new ByteArrayInputStream(xml)));return out.toString();
        }
    }
}
