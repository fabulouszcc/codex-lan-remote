package dev.codexlan.remote;

import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.w3c.dom.*;

/** Structural DOCX preview: no macros, external links, embedded images or active content. */
final class WordContent {
    private static final String W="http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String M="http://schemas.openxmlformats.org/officeDocument/2006/math";
    static String preview(File file)throws Exception{
        if(file.getName().toLowerCase(Locale.ROOT).endsWith(".doc"))return "<div class='prose'>"+OfficeContent.escape(LegacyWord.read(file))+"</div>";
        try(ZipFile zip=new ZipFile(file)){
            Map<String,Integer> headings=new HashMap<>();
            if(zip.getEntry("word/styles.xml")!=null)for(Element style:OfficeContent.elements(OfficeContent.xml(zip,"word/styles.xml"),"style")){
                String name=val(child(style,"name")),id=attr(style,"styleId");int level=heading(name);Element props=child(style,"pPr"),outline=child(props,"outlineLvl");if(outline!=null)level=Math.min(6,number(val(outline),8)+1);if(level>=1&&level<=6)headings.put(id,level);
            }
            Element body=child(OfficeContent.xml(zip,"word/document.xml").getDocumentElement(),"body");if(body==null)throw new IOException("Word 正文缺失");
            return blocks(body,headings,0);
        }
    }
    private static int heading(String name){String normalized=name.replace(" ","").toLowerCase(Locale.ROOT);if(normalized.matches("(?:heading|标题)[1-6]"))return normalized.charAt(normalized.length()-1)-'0';return 0;}
    private static int number(String value,int fallback){try{return Integer.parseInt(value);}catch(Exception e){return fallback;}}
    private static String attr(Element e,String name){return e==null?"":e.getAttributeNS(W,name);}
    private static String val(Element e){return attr(e,"val");}
    private static Element child(Element parent,String name){if(parent!=null)for(Node n=parent.getFirstChild();n!=null;n=n.getNextSibling())if(n instanceof Element&&name.equals(n.getLocalName())&&W.equals(n.getNamespaceURI()))return (Element)n;return null;}
    private static List<Element> children(Element parent,String name){List<Element> out=new ArrayList<>();if(parent!=null)for(Node n=parent.getFirstChild();n!=null;n=n.getNextSibling())if(n instanceof Element&&(name==null||name.equals(n.getLocalName())))out.add((Element)n);return out;}
    private static String blocks(Element parent,Map<String,Integer> headings,int depth)throws IOException{
        if(depth>12)throw new IOException("文档结构过深");StringBuilder out=new StringBuilder();for(Element e:children(parent,null)){
            if(W.equals(e.getNamespaceURI())&&e.getLocalName().equals("p"))out.append(paragraph(e,headings));
            else if(W.equals(e.getNamespaceURI())&&e.getLocalName().equals("tbl"))out.append(table(e,headings,depth+1));
            else if(e.getLocalName().equals("sdt")||e.getLocalName().equals("sdtContent"))out.append(blocks(e,headings,depth+1));
            OfficeContent.checkSize(out);
        }return out.toString();
    }
    private static String paragraph(Element e,Map<String,Integer> headings){
        Element props=child(e,"pPr");String style=val(child(props,"pStyle"));int level=headings.containsKey(style)?headings.get(style):heading(style);Element outline=child(props,"outlineLvl");if(outline!=null)level=Math.min(6,number(val(outline),8)+1);String tag=level>0&&level<=6?"h"+level:"p";
        String align=val(child(props,"jc"));if(align.equals("both"))align="justify";String css=Arrays.asList("left","right","center","justify").contains(align)?"text-align:"+align+";":"";
        Element indent=child(props,"ind");if(number(attr(indent,"firstLineChars"),0)>0||number(attr(indent,"firstLine"),0)>0)css+="text-indent:2em;";
        StringBuilder text=new StringBuilder();if(child(props,"numPr")!=null)text.append("<span class='bullet'>• </span>");for(Element n:children(e,null))if(!n.getLocalName().equals("pPr"))text.append(inline(n));
        return "<"+tag+" style='"+css+"'>"+(text.length()==0?"<br>":text.toString())+"</"+tag+">";
    }
    private static String inline(Element e){
        String name=e.getLocalName(),ns=e.getNamespaceURI();
        if(W.equals(ns)){
            if(name.equals("t"))return OfficeContent.escape(e.getTextContent());
            if(name.equals("tab"))return "&emsp;";if(name.equals("br")||name.equals("cr"))return "<br>";
            if(Arrays.asList("drawing","pict","object","instrText","del","rPr","pPr").contains(name))return "";
            if(name.equals("r")){StringBuilder out=new StringBuilder();for(Element n:children(e,null))if(!n.getLocalName().equals("rPr"))out.append(inline(n));String value=out.toString();Element props=child(e,"rPr");if(on(child(props,"b")))value="<strong>"+value+"</strong>";if(on(child(props,"i")))value="<em>"+value+"</em>";if(on(child(props,"u")))value="<u>"+value+"</u>";String position=val(child(props,"vertAlign"));if(position.equals("superscript"))value="<sup>"+value+"</sup>";if(position.equals("subscript"))value="<sub>"+value+"</sub>";return value;}
        }
        if(M.equals(ns)){
            if(name.equals("t"))return OfficeContent.escape(e.getTextContent());
            if(name.endsWith("Pr"))return "";
            if(name.equals("f"))return "<span class='fraction'><span>"+mathChild(e,"num")+"</span><span>"+mathChild(e,"den")+"</span></span>";
            if(name.equals("sSub")||name.equals("sSup")||name.equals("sSubSup"))return mathChild(e,"e")+(name.equals("sSup")?"":"<sub>"+mathChild(e,"sub")+"</sub>")+(name.equals("sSub")?"":"<sup>"+mathChild(e,"sup")+"</sup>");
            if(name.equals("rad"))return "√("+mathChild(e,"e")+")";
        }
        StringBuilder out=new StringBuilder();for(Element n:children(e,null))out.append(inline(n));return out.toString();
    }
    private static String mathChild(Element e,String name){for(Element n:children(e,name))if(M.equals(n.getNamespaceURI()))return inline(n);return "";}
    private static boolean on(Element e){return e!=null&&!Arrays.asList("0","false","off","none").contains(val(e));}
    private static final class Cell{String html;int span,rows=1;Cell(String html,int span){this.html=html;this.span=span;}}
    private static String table(Element e,Map<String,Integer> headings,int depth)throws IOException{
        List<List<Cell>> rows=new ArrayList<>();Map<Integer,Cell> previous=new HashMap<>();
        for(Element tr:children(e,"tr")){if(rows.size()>=1000)break;List<Cell> cells=new ArrayList<>();Map<Integer,Cell> current=new HashMap<>();int col=0;
            for(Element tc:children(tr,"tc")){if(col>=50)break;Element props=child(tc,"tcPr");int span=Math.max(1,Math.min(50-col,number(val(child(props,"gridSpan")),1)));Element merge=child(props,"vMerge");Cell above=previous.get(col);
                if(merge!=null&&!val(merge).equals("restart")&&above!=null&&above.span==span){above.rows++;for(int k=0;k<span;k++)current.put(col+k,above);}
                else{Cell cell=new Cell(blocks(tc,headings,depth),span);cells.add(cell);if(merge!=null)for(int k=0;k<span;k++)current.put(col+k,cell);}col+=span;
            }rows.add(cells);previous=current;
        }
        StringBuilder out=new StringBuilder("<div class='wide'><table class='word-table'>");for(List<Cell> row:rows){out.append("<tr>");for(Cell cell:row)out.append("<td colspan='").append(cell.span).append("' rowspan='").append(cell.rows).append("'>").append(cell.html).append("</td>");out.append("</tr>");OfficeContent.checkSize(out);}return out.append("</table></div>").toString();
    }
}
