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
        for(MarkdownContent.Block block:MarkdownContent.blocks(body)) {
            if(block.kind.equals("table"))table(context,parent,block,foreground,muted);
            else if(block.kind.equals("code"))code(context,parent,block.language+"\n"+block.text,foreground,muted);
            else {TextView text=new TextView(context);text.setText(DocumentStyle.render(block.text.trim(),UiPalette.of(context).accent,muted));text.setTextColor(foreground);text.setTextSize(16);text.setLineSpacing(dp(context,3),1.12f);text.setTextIsSelectable(true);text.setPadding(0,dp(context,6),0,dp(context,8));parent.addView(text);}
        }
    }
    private static void table(Context c,LinearLayout parent,MarkdownContent.Block block,int fg,int muted){
        UiPalette palette=UiPalette.of(c);HorizontalScrollView scroll=new HorizontalScrollView(c);scroll.setFillViewport(true);scroll.setHorizontalScrollBarEnabled(true);scroll.setContentDescription("表格，可左右滑动查看全部列");TableLayout table=new TableLayout(c);table.setPadding(0,dp(c,1),0,dp(c,1));
        table.setStretchAllColumns(true);int columns=block.align.size();int[] widths=new int[columns];for(int col=0;col<columns;col++){int longest=0;for(java.util.List<String> row:block.rows)if(col<row.size())longest=Math.max(longest,row.get(col).length());widths[col]=Math.max(110,Math.min(230,longest*9+24));}
        for(int r=0;r<block.rows.size();r++){TableRow row=new TableRow(c);for(int col=0;col<columns;col++){TextView cell=new TextView(c);java.util.List<String> values=block.rows.get(r);cell.setText(DocumentStyle.render(col<values.size()?values.get(col):"",palette.accent,muted));cell.setTextSize(14);cell.setTextColor(fg);cell.setTextIsSelectable(true);cell.setPadding(dp(c,12),dp(c,10),dp(c,12),dp(c,8));cell.setWidth(dp(c,widths[col]));cell.setMaxWidth(dp(c,widths[col]));cell.setLineSpacing(dp(c,2),1.12f);cell.setGravity(android.view.Gravity.TOP|("right".equals(block.align.get(col))?android.view.Gravity.RIGHT:"center".equals(block.align.get(col))?android.view.Gravity.CENTER_HORIZONTAL:android.view.Gravity.LEFT));if(r==0)cell.setTypeface(null,Typeface.BOLD);
                GradientDrawable bg=new GradientDrawable();bg.setColor(palette.dark?(r==0?0xff343a36:0xff232724):(r==0?0xffe6eee8:0xfffafcfb));bg.setStroke(dp(c,1),palette.dark?0xff49514b:0xffd9e1db);cell.setBackground(bg);row.addView(cell,new TableRow.LayoutParams(dp(c,widths[col]),-1));}table.addView(row,new TableLayout.LayoutParams(-2,-2));}
        scroll.addView(table,new HorizontalScrollView.LayoutParams(-2,-2));LinearLayout.LayoutParams space=new LinearLayout.LayoutParams(-1,-2);space.topMargin=dp(c,8);space.bottomMargin=dp(c,10);parent.addView(scroll,space);
    }
    private static void code(Context c,LinearLayout parent,String raw,int fg,int muted) {
        int newline=raw.indexOf('\n');String language=newline>=0?raw.substring(0,newline).trim():"";
        String content=newline>=0?raw.substring(newline+1):raw;
        LinearLayout box=new LinearLayout(c);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(c,12),dp(c,6),dp(c,12),dp(c,12));GradientDrawable background=new GradientDrawable();background.setColor(UiPalette.of(c).dark?0xff252525:0xffeef2ef);background.setCornerRadius(dp(c,12));box.setBackground(background);
        LinearLayout top=new LinearLayout(c);top.setGravity(android.view.Gravity.CENTER_VERTICAL);TextView label=new TextView(c);label.setText(language.isEmpty()?"代码":language);label.setTextColor(muted);label.setTextSize(12);top.addView(label,new LinearLayout.LayoutParams(0,-2,1));
        Button copy=new Button(c);copy.setText("复制");copy.setTextSize(12);copy.setTextColor(fg);copy.setBackgroundColor(android.graphics.Color.TRANSPARENT);top.addView(copy,new LinearLayout.LayoutParams(dp(c,64),dp(c,44)));
        copy.setOnClickListener(v->{((android.content.ClipboardManager)c.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("代码",content));Toast.makeText(c,"代码已复制",Toast.LENGTH_SHORT).show();});box.addView(top);
        HorizontalScrollView scroll=new HorizontalScrollView(c);TextView text=new TextView(c);text.setText(content);text.setTypeface(Typeface.MONOSPACE);text.setTextSize(13);text.setTextColor(fg);text.setTextIsSelectable(true);scroll.addView(text);box.addView(scroll);parent.addView(box,new LinearLayout.LayoutParams(-1,-2));
    }
}
