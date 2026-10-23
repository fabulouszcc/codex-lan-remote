package dev.codexlan.remote;

import java.util.*;
import java.util.regex.*;

/** Shared block structure for native chat rendering and offline file previews. */
final class MarkdownContent {
    static final class Block {final String kind,text,language;final List<List<String>> rows;final List<String> align;
        Block(String kind,String text,String language,List<List<String>> rows,List<String> align){this.kind=kind;this.text=text;this.language=language;this.rows=rows;this.align=align;}}
    static List<String> cells(String line){
        String value=line.trim();if(value.startsWith("|"))value=value.substring(1);if(value.endsWith("|")&&!value.endsWith("\\|"))value=value.substring(0,value.length()-1);
        List<String> out=new ArrayList<>();StringBuilder cell=new StringBuilder();int code=0;
        for(int i=0;i<value.length();i++){char c=value.charAt(i);if(c=='\\'&&i+1<value.length()&&value.charAt(i+1)=='|'){cell.append('|');i++;continue;}
            if(c=='`'){int n=1;while(i+n<value.length()&&value.charAt(i+n)=='`')n++;if(code==0)code=n;else if(code==n)code=0;for(int j=0;j<n;j++)cell.append('`');i+=n-1;continue;}
            if(c=='|'&&code==0){out.add(cell.toString().trim());cell.setLength(0);}else cell.append(c);
        }out.add(cell.toString().trim());return out;
    }
    static List<String> alignment(String line){List<String> result=new ArrayList<>();for(String cell:cells(line)){if(!cell.matches(":?-{3,}:?"))return Collections.emptyList();result.add(cell.startsWith(":")&&cell.endsWith(":")?"center":cell.endsWith(":")?"right":"left");}return result;}
    static List<Block> blocks(String source){
        String[] lines=source.replace("\r\n","\n").replace('\r','\n').split("\n",-1);List<Block> out=new ArrayList<>();StringBuilder prose=new StringBuilder();
        for(int i=0;i<lines.length;i++){
            Matcher fence=Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$").matcher(lines[i]);
            if(fence.matches()){flush(out,prose);String mark=fence.group(1),language=fence.group(2).trim();StringBuilder code=new StringBuilder();for(i++;i<lines.length;i++){String end=lines[i].trim();if(end.matches(Pattern.quote(mark.substring(0,1))+"{"+mark.length()+",}"))break;if(code.length()>0)code.append('\n');code.append(lines[i]);}out.add(new Block("code",code.toString(),language,null,null));continue;}
            List<String> align=i+1<lines.length?alignment(lines[i+1]):Collections.emptyList();List<String> header=cells(lines[i]);
            if(!align.isEmpty()&&header.size()==align.size()&&lines[i].contains("|")){
                flush(out,prose);List<List<String>> rows=new ArrayList<>();rows.add(header);i++;
                while(i+1<lines.length&&!lines[i+1].trim().isEmpty()&&lines[i+1].contains("|")){List<String> row=cells(lines[++i]);while(row.size()<header.size())row.add("");rows.add(row);}
                out.add(new Block("table","","",rows,align));continue;
            }
            if(prose.length()>0)prose.append('\n');prose.append(lines[i]);
        }flush(out,prose);return out;
    }
    private static void flush(List<Block> out,StringBuilder value){if(value.length()>0&&!value.toString().trim().isEmpty())out.add(new Block("text",value.toString(),"",null,null));value.setLength(0);}
    static final Pattern INLINE=Pattern.compile("(`+)(.+?)\\1|\\*\\*([^*\\n]+)\\*\\*|__([^_\\n]+)__|\\[([^]\\n]+)\\]\\(([^)\\n]+)\\)");
    static String inline(String text){Matcher match=INLINE.matcher(text);StringBuilder out=new StringBuilder();int cursor=0;while(match.find()){out.append(OfficeContent.escape(text.substring(cursor,match.start())));if(match.group(2)!=null)out.append("<code>").append(OfficeContent.escape(match.group(2))).append("</code>");else if(match.group(3)!=null||match.group(4)!=null)out.append("<strong>").append(OfficeContent.escape(match.group(3)!=null?match.group(3):match.group(4))).append("</strong>");else out.append("<span class='link'>").append(OfficeContent.escape(match.group(5))).append("</span>");cursor=match.end();}return out.append(OfficeContent.escape(text.substring(cursor))).toString();}
    static String html(String text){StringBuilder out=new StringBuilder();for(Block block:blocks(text)){
        if(block.kind.equals("table")){out.append("<div class='wide'><table>");for(int r=0;r<block.rows.size();r++){out.append("<tr>");List<String> row=block.rows.get(r);for(int c=0;c<block.align.size();c++){String tag=r==0?"th":"td";out.append('<').append(tag).append(" style='text-align:").append(block.align.get(c)).append("'>").append(inline(c<row.size()?row.get(c):"")).append("</").append(tag).append('>');}out.append("</tr>");}out.append("</table></div>");}
        else if(block.kind.equals("code"))out.append("<pre><code>").append(OfficeContent.escape(block.text)).append("</code></pre>");
        else for(String line:block.text.split("\n",-1)){Matcher h=Pattern.compile("^(#{1,6})\\s+(.+?)(?:\\s+#+)?$").matcher(line);if(h.matches())out.append("<h").append(h.group(1).length()).append('>').append(inline(h.group(2))).append("</h").append(h.group(1).length()).append('>');else if(line.trim().isEmpty())out.append("<div class='gap'></div>");else if(line.matches("^\\s*[-*+]\\s+.*"))out.append("<p class='list'>• ").append(inline(line.replaceFirst("^\\s*[-*+]\\s+", ""))).append("</p>");else if(line.startsWith("> "))out.append("<blockquote>").append(inline(line.substring(2))).append("</blockquote>");else out.append("<p>").append(inline(line)).append("</p>");}
    }return out.toString();}
}
