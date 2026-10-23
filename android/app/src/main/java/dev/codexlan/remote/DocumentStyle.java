package dev.codexlan.remote;

import android.graphics.Typeface;
import android.text.*;
import android.text.style.*;
import java.util.regex.*;

/** Native Markdown typography; remote HTML/resources are never executed. */
final class DocumentStyle {
    static CharSequence render(String value,int accent,int muted){
        SpannableStringBuilder out=new SpannableStringBuilder();boolean code=false;
        for(String line:value.split("\n",-1)){
            if(line.trim().startsWith("```")){code=!code;continue;}
            int level=0;Matcher heading=Pattern.compile("^(#{1,6})\\s+(.+)$").matcher(line);
            if(!code&&heading.matches()){level=heading.group(1).length();line=heading.group(2);}
            if(!code)line=line.replaceFirst("^\\s*[-*+]\\s+","• ");
            int start=out.length();out.append(line).append('\n');
            if(code)out.setSpan(new TypefaceSpan("monospace"),start,out.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            else if(level>0){out.setSpan(new StyleSpan(Typeface.BOLD),start,out.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);out.setSpan(new RelativeSizeSpan(level==1?1.55f:level==2?1.3f:1.12f),start,out.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);}
        }
        if(out.length()>0&&out.charAt(out.length()-1)=='\n')out.delete(out.length()-1,out.length());
        Matcher inline=MarkdownContent.INLINE.matcher(out.toString());
        java.util.List<int[]> ranges=new java.util.ArrayList<>();java.util.List<String> labels=new java.util.ArrayList<>();
        while(inline.find()){int kind=inline.group(2)!=null?2:inline.group(3)!=null||inline.group(4)!=null?1:3;ranges.add(new int[]{inline.start(),inline.end(),kind});labels.add(kind==2?inline.group(2):kind==1?(inline.group(3)!=null?inline.group(3):inline.group(4)):inline.group(5));}
        for(int i=ranges.size()-1;i>=0;i--){int[] r=ranges.get(i);String label=labels.get(i);out.replace(r[0],r[1],label);out.setSpan(r[2]==1?new StyleSpan(Typeface.BOLD):r[2]==2?new TypefaceSpan("monospace"):new ForegroundColorSpan(accent),r[0],r[0]+label.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);}
        return out;
    }
}
