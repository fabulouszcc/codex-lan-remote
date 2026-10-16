package dev.codexlan.remote;

import android.content.*;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.*;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.widget.*;
import java.util.regex.*;

/** Native, selectable text. Remote Markdown never executes HTML or loads resources. */
final class MessageBody {
    private static int dp(Context c,int n){return Math.round(c.getResources().getDisplayMetrics().density*n);}
    static void add(Context context, LinearLayout parent, String body, int foreground, int muted) {
        String[] pieces=body.split("```",-1);
        for(int i=0;i<pieces.length;i++) {
            if(pieces[i].isEmpty())continue;
            if(i%2==0){TextView text=new TextView(context);text.setText(inline(pieces[i]));text.setTextColor(foreground);text.setTextSize(16);text.setLineSpacing(dp(context,3),1.12f);text.setTextIsSelectable(true);text.setPadding(0,dp(context,6),0,dp(context,8));parent.addView(text);}
            else code(context,parent,pieces[i],foreground,muted);
        }
    }
    private static CharSequence inline(String text) {
        SpannableStringBuilder out=new SpannableStringBuilder();
        Matcher matcher=Pattern.compile("\\*\\*(.+?)\\*\\*|`([^`\\n]+)`").matcher(text);int cursor=0;
        while(matcher.find()){out.append(text.substring(cursor,matcher.start()));int start=out.length();out.append(matcher.group(1)!=null?matcher.group(1):matcher.group(2));out.setSpan(matcher.group(1)!=null?new StyleSpan(Typeface.BOLD):new TypefaceSpan("monospace"),start,out.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);cursor=matcher.end();}
        out.append(text.substring(cursor));
        Matcher headings=Pattern.compile("(?m)^#{1,6} (.+)$").matcher(out.toString());
        while(headings.find())out.setSpan(new StyleSpan(Typeface.BOLD),headings.start(),headings.end(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return out;
    }
    private static void code(Context c,LinearLayout parent,String raw,int fg,int muted) {
        int newline=raw.indexOf('\n');String language=newline>=0?raw.substring(0,newline).trim():"";
        String content=newline>=0?raw.substring(newline+1):raw;
        LinearLayout box=new LinearLayout(c);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(c,12),dp(c,6),dp(c,12),dp(c,12));GradientDrawable background=new GradientDrawable();background.setColor(0xff252525);background.setCornerRadius(dp(c,12));box.setBackground(background);
        LinearLayout top=new LinearLayout(c);top.setGravity(android.view.Gravity.CENTER_VERTICAL);TextView label=new TextView(c);label.setText(language.isEmpty()?"代码":language);label.setTextColor(muted);label.setTextSize(12);top.addView(label,new LinearLayout.LayoutParams(0,-2,1));
        Button copy=new Button(c);copy.setText("复制");copy.setTextSize(12);copy.setTextColor(fg);copy.setBackgroundColor(android.graphics.Color.TRANSPARENT);top.addView(copy,new LinearLayout.LayoutParams(dp(c,64),dp(c,44)));
        copy.setOnClickListener(v->{((android.content.ClipboardManager)c.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("代码",content));Toast.makeText(c,"代码已复制",Toast.LENGTH_SHORT).show();});box.addView(top);
        HorizontalScrollView scroll=new HorizontalScrollView(c);TextView text=new TextView(c);text.setText(content);text.setTypeface(Typeface.MONOSPACE);text.setTextSize(13);text.setTextColor(fg);text.setTextIsSelectable(true);scroll.addView(text);box.addView(scroll);parent.addView(box,new LinearLayout.LayoutParams(-1,-2));
    }
}
