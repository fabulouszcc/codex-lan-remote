package dev.codexlan.remote;

import java.io.*;
import java.nio.charset.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;
import javax.xml.parsers.*;
import org.w3c.dom.*;
import org.xml.sax.*;

/** Bounded, offline Office/table previews. Formulae and embedded objects are never run. */
final class OfficeContent {
    static String escape(String text){return text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
    static Document xml(ZipFile zip,String name)throws Exception{
        ZipEntry entry=zip.getEntry(name);if(entry==null)throw new IOException("文档结构不完整");byte[] bytes;try(InputStream in=zip.getInputStream(entry)){bytes=DocumentContent.bounded(in,8*1024*1024);}
        String check=new String(bytes,StandardCharsets.UTF_8).replace("\0","").toUpperCase(Locale.ROOT);if(check.contains("<!DOCTYPE")||check.contains("<!ENTITY"))throw new IOException("不支持外部 XML 声明");
        DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(true);DocumentBuilder builder=f.newDocumentBuilder();builder.setEntityResolver((p,s)->{throw new SAXException("禁止外部资源");});return builder.parse(new ByteArrayInputStream(bytes));
    }
    static List<Element> elements(Node node,String local){NodeList nodes=((Element)(node instanceof Document?((Document)node).getDocumentElement():node)).getElementsByTagNameNS("*",local);List<Element> result=new ArrayList<>();for(int i=0;i<nodes.getLength();i++)result.add((Element)nodes.item(i));return result;}
    static String joined(Node node,String local){StringBuilder out=new StringBuilder();for(Element element:elements(node,local))out.append(element.getTextContent());return out.toString();}
    static String first(Node node,String local){List<Element> all=elements(node,local);return all.isEmpty()?"":all.get(0).getTextContent();}
    static void checkSize(StringBuilder html)throws IOException{if(html.length()>2*1024*1024)throw new IOException("预览内容过大，请使用其他应用打开");}
    static String preview(File file,String extension)throws Exception{
        if(extension.equals("csv")||extension.equals("tsv"))return separated(DocumentContent.text(file),extension.equals("csv")?',':'\t');
        if(extension.equals("rtf"))return "<pre class='prose'>"+escape(rtf(file))+"</pre>";
        try(ZipFile zip=new ZipFile(file)){
            if(extension.equals("xlsx"))return spreadsheet(zip);
            if(extension.equals("pptx"))return presentation(zip);
            if(extension.equals("odt")||extension.equals("ods")||extension.equals("odp"))return openDocument(zip,extension);
            if(extension.equals("zip")){StringBuilder out=new StringBuilder("<h2>压缩包内容</h2><table><tr><th>文件</th><th>大小</th></tr>");Enumeration<? extends ZipEntry> entries=zip.entries();int count=0;while(entries.hasMoreElements()&&count++<2000){ZipEntry entry=entries.nextElement();out.append("<tr><td>").append(escape(entry.getName())).append("</td><td>").append(Math.max(0,entry.getSize())).append(" B</td></tr>");checkSize(out);}if(entries.hasMoreElements())out.append("<caption>仅显示前 2000 项</caption>");return out.append("</table>").toString();}
        }
        throw new IOException("暂不支持此文档格式");
    }
    static String spreadsheet(ZipFile zip)throws Exception{
        List<String> shared=new ArrayList<>();if(zip.getEntry("xl/sharedStrings.xml")!=null)for(Element si:elements(xml(zip,"xl/sharedStrings.xml"),"si"))shared.add(joined(si,"t"));
        Map<String,String> targets=new HashMap<>();for(Element rel:elements(xml(zip,"xl/_rels/workbook.xml.rels"),"Relationship"))if(!"External".equals(rel.getAttribute("TargetMode"))){String name=rel.getAttribute("Target");if(!name.contains("..")&&!name.contains(":"))targets.put(rel.getAttribute("Id"),name.startsWith("/")?name.substring(1):"xl/"+name);}
        StringBuilder out=new StringBuilder("<p>表格内容预览 · 最多 1000 行、50 列，公式显示已保存的结果。</p>");int sheets=0;
        for(Element sheet:elements(xml(zip,"xl/workbook.xml"),"sheet")){
            if(sheets++==30){out.append("<p>仅预览前 30 个工作表</p>");break;}
            String id=sheet.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships","id"),target=targets.get(id);if(target==null)continue;
            out.append("<h2>").append(escape(sheet.getAttribute("name"))).append("</h2><div class='wide'><table>");int rows=0;
            for(Element row:elements(xml(zip,target),"row")){
                if(rows++==1000){out.append("<caption>仅预览前 1000 行、50 列；公式显示已保存的结果</caption>");break;}
                out.append("<tr><th>").append(escape(row.getAttribute("r"))).append("</th>");int column=0;
                for(Element cell:elements(row,"c")){
                    String address=cell.getAttribute("r");int desired=0;for(int i=0;i<address.length()&&Character.isLetter(address.charAt(i));i++)desired=Math.min(10000,desired*26+Character.toUpperCase(address.charAt(i))-'A'+1);
                    if(desired>50)break;while(column+1<desired){out.append("<td></td>");column++;}
                    String value=first(cell,"v"),type=cell.getAttribute("t");if(type.equals("s")){try{int index=Integer.parseInt(value);value=index>=0&&index<shared.size()?shared.get(index):"";}catch(NumberFormatException e){value="";}}
                    else if(type.equals("inlineStr"))value=joined(cell,"t");else if(type.equals("b"))value=value.equals("1")?"TRUE":"FALSE";
                    if(value.isEmpty()&&!first(cell,"f").isEmpty())value="="+first(cell,"f");out.append("<td>").append(escape(value)).append("</td>");column++;checkSize(out);
                }out.append("</tr>");
            }out.append("</table></div>");
        }return out.toString();
    }
    static String presentation(ZipFile zip)throws Exception{
        Map<String,String> targets=new HashMap<>();for(Element rel:elements(xml(zip,"ppt/_rels/presentation.xml.rels"),"Relationship"))if(!"External".equals(rel.getAttribute("TargetMode"))){String name=rel.getAttribute("Target");if(!name.contains("..")&&!name.contains(":"))targets.put(rel.getAttribute("Id"),name.startsWith("/")?name.substring(1):"ppt/"+name);}
        StringBuilder out=new StringBuilder("<p>幻灯片文字预览</p>");int slide=0;for(Element entry:elements(xml(zip,"ppt/presentation.xml"),"sldId")){
            if(slide++==100){out.append("<p>仅预览前 100 张幻灯片</p>");break;}String target=targets.get(entry.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships","id"));if(target==null)continue;
            out.append("<section><h2>幻灯片 ").append(slide).append("</h2>");for(Element paragraph:elements(xml(zip,target),"p")){String value=joined(paragraph,"t");if(!value.isEmpty())out.append("<p>").append(escape(value)).append("</p>");checkSize(out);}out.append("</section>");
        }return out.toString();
    }
    static String openDocument(ZipFile zip,String kind)throws Exception{
        Document doc=xml(zip,"content.xml");StringBuilder out=new StringBuilder();
        if(kind.equals("ods")){for(Element table:elements(doc,"table")){out.append("<h2>").append(escape(table.getAttributeNS("urn:oasis:names:tc:opendocument:xmlns:table:1.0","name"))).append("</h2><div class='wide'><table>");int rows=0;for(Element row:elements(table,"table-row")){if(rows++==1000)break;out.append("<tr>");int cols=0;for(Element cell:elements(row,"table-cell")){int repeat=1;try{repeat=Math.min(50,Integer.parseInt(cell.getAttributeNS("urn:oasis:names:tc:opendocument:xmlns:table:1.0","number-columns-repeated")));}catch(NumberFormatException ignored){}for(int i=0;i<repeat&&cols++<50;i++)out.append("<td>").append(escape(cell.getTextContent())).append("</td>");checkSize(out);}out.append("</tr>");}out.append("</table></div>");}}
        else{List<Element> pages=kind.equals("odp")?elements(doc,"page"):Collections.singletonList(doc.getDocumentElement());int count=0;for(Element page:pages){if(kind.equals("odp"))out.append("<h2>幻灯片 ").append(++count).append("</h2>");NodeList all=page.getElementsByTagNameNS("urn:oasis:names:tc:opendocument:xmlns:text:1.0","*");for(int i=0;i<all.getLength();i++){Element p=(Element)all.item(i);if(p.getLocalName().equals("p")||p.getLocalName().equals("h"))out.append(p.getLocalName().equals("h")?"<h2>":"<p>").append(escape(p.getTextContent())).append(p.getLocalName().equals("h")?"</h2>":"</p>");checkSize(out);}}}
        return out.toString();
    }
    static String separated(String text,char separator)throws IOException{
        StringBuilder out=new StringBuilder("<div class='wide'><table><tr>"),cell=new StringBuilder();boolean quote=false;int rows=0,cols=0;
        for(int i=0;i<=text.length();i++){char c=i==text.length()?'\n':text.charAt(i);if(c=='"'){if(quote&&i+1<text.length()&&text.charAt(i+1)=='"'){cell.append('"');i++;}else quote=!quote;}
            else if(!quote&&(c==separator||c=='\n')){if(cols++<50)out.append("<td>").append(escape(cell.toString())).append("</td>");cell.setLength(0);if(c=='\n'){out.append("</tr><tr>");cols=0;if(++rows>=1000){out.append("<td>仅预览前 1000 行、50 列</td>");break;}}}else if(c!='\r')cell.append(c);checkSize(out);}
        return out.append("</tr></table></div>").toString();
    }
    static String rtf(File file)throws Exception{
        byte[] bytes;try(InputStream in=new FileInputStream(file)){bytes=DocumentContent.bounded(in,DocumentContent.LIMIT);}String raw=new String(bytes,StandardCharsets.ISO_8859_1);if(!raw.startsWith("{\\rtf"))throw new IOException("无效的 RTF 文档");
        Charset charset=Charset.forName("windows-1252");Matcher cp=Pattern.compile("\\\\ansicpg(\\d+)").matcher(raw);if(cp.find())try{charset=Charset.forName(cp.group(1).equals("936")?"GBK":"windows-"+cp.group(1));}catch(Exception ignored){}
        Matcher hex=Pattern.compile("(?:\\\\'[0-9a-fA-F]{2})+").matcher(raw);StringBuffer decoded=new StringBuffer();while(hex.find()){String run=hex.group();byte[] encoded=new byte[run.length()/4];for(int i=0;i<encoded.length;i++)encoded[i]=(byte)Integer.parseInt(run.substring(i*4+2,i*4+4),16);hex.appendReplacement(decoded,Matcher.quoteReplacement(new String(encoded,charset).replace("\\","\\\\").replace("{","\\{").replace("}","\\}")));}hex.appendTail(decoded);raw=decoded.toString();
        StringBuilder out=new StringBuilder();Stack<Boolean> skip=new Stack<>();boolean ignored=false;int fallback=1;
        for(int i=0;i<raw.length();i++){char c=raw.charAt(i);if(c=='{'){skip.push(ignored);}else if(c=='}'){if(!skip.empty())ignored=skip.pop();}else if(c=='\\'&&i+1<raw.length()){
            char n=raw.charAt(++i);if(n=='\\'||n=='{'||n=='}'){if(!ignored)out.append(n);continue;}if(n=='*'){ignored=true;continue;}if(!Character.isLetter(n))continue;
            int start=i;while(i<raw.length()&&Character.isLetter(raw.charAt(i)))i++;String word=raw.substring(start,i);int numberStart=i;if(i<raw.length()&&raw.charAt(i)=='-')i++;while(i<raw.length()&&Character.isDigit(raw.charAt(i)))i++;int number=0;if(i>numberStart)try{number=Integer.parseInt(raw.substring(numberStart,i));}catch(NumberFormatException e){throw new IOException("无效的 RTF 控制码");}if(i>=raw.length()||raw.charAt(i)!=' ')i--;
            if(Arrays.asList("fonttbl","colortbl","stylesheet","info","pict","object","header","footer").contains(word))ignored=true;
            if(word.equals("uc"))fallback=Math.max(0,Math.min(8,number));if(!ignored){if(word.equals("par")||word.equals("line"))out.append('\n');if(word.equals("tab"))out.append("    ");if(word.equals("u")){out.append((char)number);i=Math.min(raw.length()-1,i+fallback);}if(word.equals("bin"))i=Math.min(raw.length()-1,i+Math.max(0,number));}
        }else if(!ignored&&c!='\r'&&c!='\n')out.append(c);}
        return out.toString();
    }
}
