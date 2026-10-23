package dev.codexlan.remote;
import android.app.*;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import android.os.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Explicit first-use consent; verified, atomic installation in app-private storage. */
final class VoiceModels {
    static final String URL="https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip";
    static final String SHA="3af8b0e7e0f835ae9d414ce5df580237a3cfb08d586c9fbbb0f7ff29ad5b14ba";
    static final long SIZE=43898754;
    private final Activity activity;private final ExecutorService worker=Executors.newSingleThreadExecutor();private final Handler ui=new Handler(Looper.getMainLooper());
    private volatile boolean canceled;private volatile HttpURLConnection connection;private Dialog dialog;private int generation;
    VoiceModels(Activity activity){this.activity=activity;}
    static File directory(android.content.Context context){return new File(new File(context.getFilesDir(),"voice"),VoiceModelArchive.NAME);}
    static boolean ready(android.content.Context context){try{File model=directory(context);VoiceModelArchive.validate(model);try(InputStream in=new FileInputStream(new File(model,"ready.sha256"))){return SHA.equals(new String(DocumentContent.bounded(in,128),StandardCharsets.UTF_8));}}catch(Exception e){return false;}}
    private int dp(int value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
    private GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private TextView label(String text,int size,int color){TextView v=new TextView(activity);v.setText(text);v.setTextSize(size);v.setTextColor(color);v.setPadding(0,dp(8),0,dp(8));return v;}
    void open(Runnable after){
        if(dialog!=null&&dialog.isShowing())return;UiPalette p=UiPalette.of(activity);dialog=new Dialog(activity);LinearLayout card=new LinearLayout(activity);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(22),dp(20),dp(22),dp(20));card.setBackground(shape(p.dark?0xff262a27:0xfffafcfb,24));
        LinearLayout header=new LinearLayout(activity);header.setGravity(Gravity.CENTER_VERTICAL);ImageView icon=new ImageView(activity);icon.setImageDrawable(new UiIcon("voice",Color.WHITE));icon.setPadding(dp(10),dp(10),dp(10),dp(10));icon.setBackground(shape(p.dark?0xff3478f6:0xff2867d7,20));header.addView(icon,new LinearLayout.LayoutParams(dp(40),dp(40)));TextView title=label("离线语音模型",19,p.foreground);title.setTypeface(null,android.graphics.Typeface.BOLD);LinearLayout.LayoutParams titleSpace=new LinearLayout.LayoutParams(0,-2,1);titleSpace.leftMargin=dp(12);header.addView(title,titleSpace);card.addView(header);
        boolean installed=ready(activity);card.addView(label("中文识别 · 约 42 MB · Vosk\n首次下载后无需联网识别，语音不上传。",14,p.foreground));
        TextView status=label(installed?"模型已下载，可直接使用。":"首次语音输入需要下载模型。请确认当前网络，移动网络也会消耗流量。",13,p.muted);card.addView(status);
        ProgressBar progress=new ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);progress.setProgressTintList(android.content.res.ColorStateList.valueOf(p.dark?0xff6ea0ff:0xff2867d7));progress.setVisibility(View.GONE);LinearLayout.LayoutParams barSpace=new LinearLayout.LayoutParams(-1,dp(8));barSpace.topMargin=dp(10);barSpace.bottomMargin=dp(16);card.addView(progress,barSpace);
        Button primary=new Button(activity);primary.setText(installed?"已准备好":"下载并使用");primary.setEnabled(!installed);primary.setTextColor(Color.WHITE);primary.setBackground(shape(p.dark?0xff3478f6:0xff2867d7,16));card.addView(primary,new LinearLayout.LayoutParams(-1,dp(48)));
        Button back=new Button(activity);back.setText(installed?"关闭":"暂不下载");back.setTextColor(p.foreground);back.setBackground(shape(p.dark?0xff363d37:0xffe8eeea,16));LinearLayout.LayoutParams second=new LinearLayout.LayoutParams(-1,dp(48));second.topMargin=dp(10);card.addView(back,second);back.setOnClickListener(v->dialog.dismiss());
        primary.setOnClickListener(v->{canceled=false;int epoch=++generation;primary.setEnabled(false);primary.setText("正在下载…");back.setText("取消下载");progress.setVisibility(View.VISIBLE);progress.setIndeterminate(false);progress.setProgress(0);status.setText("正在连接模型官方站…");
            worker.execute(()->{try{download(value->ui.post(()->{if(epoch!=generation||canceled)return;progress.setIndeterminate(value<0);if(value>=0){progress.setProgress(value);status.setText("下载中 · "+value+"% / 约 42 MB");}else{primary.setText("正在校验并解压…");status.setText("下载完成，正在准备离线模型…");}}));ui.post(()->{if(epoch!=generation||canceled||activity.isDestroyed())return;dialog.dismiss();if(after!=null)after.run();});}
                catch(Exception error){ui.post(()->{if(epoch!=generation||canceled||activity.isDestroyed())return;progress.setIndeterminate(false);primary.setEnabled(true);primary.setText("重新下载");back.setText("暂不下载");status.setText("下载未完成，请检查网络和可用存储后重试。已安装的完整模型不会受影响。");});}});
        });dialog.setContentView(card);dialog.setOnDismissListener(v->{canceled=true;generation++;if(connection!=null)connection.disconnect();});dialog.show();Window window=dialog.getWindow();if(window!=null){window.setBackgroundDrawableResource(android.R.color.transparent);window.setLayout(Math.min(dp(400),activity.getResources().getDisplayMetrics().widthPixels-dp(32)),-2);}
    }
    private interface Progress{void update(int value);}
    private void download(Progress progress)throws Exception{
        File root=new File(activity.getFilesDir(),"voice");if(!root.isDirectory()&&!root.mkdirs())throw new IOException("存储不可用");if(root.getUsableSpace()<240L*1024*1024)throw new IOException("存储不足");
        File archive=new File(root,"download-"+UUID.randomUUID()+".zip"),staging=new File(root,"install-"+UUID.randomUUID());
        try{HttpURLConnection http=(HttpURLConnection)new java.net.URL(URL).openConnection();connection=http;http.setConnectTimeout(15000);http.setReadTimeout(30000);http.setInstanceFollowRedirects(false);http.setRequestProperty("Accept-Encoding","identity");if(http.getResponseCode()!=200)throw new IOException("模型下载失败");MessageDigest digest=MessageDigest.getInstance("SHA-256");long size=0;int previous=-1;
            try(InputStream in=http.getInputStream();OutputStream out=new FileOutputStream(archive)){byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1){if(canceled)throw new IOException("下载取消");size+=n;if(size>SIZE)throw new IOException("模型大小不匹配");digest.update(buffer,0,n);out.write(buffer,0,n);int value=(int)(size*100/SIZE);if(value!=previous){previous=value;progress.update(value);}}}
            StringBuilder hash=new StringBuilder();for(byte b:digest.digest())hash.append(String.format(Locale.ROOT,"%02x",b&255));if(size!=SIZE||!SHA.equals(hash.toString()))throw new IOException("模型校验失败");if(canceled)throw new IOException("下载取消");progress.update(-1);
            if(!staging.mkdirs())throw new IOException("无法准备模型");VoiceModelArchive.extract(archive,staging);if(canceled)throw new IOException("下载取消");File model=new File(staging,VoiceModelArchive.NAME);try(OutputStream out=new FileOutputStream(new File(model,"ready.sha256"))){out.write(SHA.getBytes(StandardCharsets.UTF_8));}
            File target=directory(activity);if(!ready(activity)){remove(target,root);if(!model.renameTo(target))throw new IOException("模型安装失败");}
        }finally{if(connection!=null)connection.disconnect();connection=null;remove(archive,root);remove(staging,root);}
    }
    private static void remove(File file,File root)throws IOException{if(!file.exists())return;if(!file.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator))throw new IOException("无效模型路径");if(file.isDirectory()){File[] children=file.listFiles();if(children!=null)for(File child:children)remove(child,root);}if(!file.delete())throw new IOException("无法清理模型临时文件");}
    void close(){canceled=true;generation++;if(connection!=null)connection.disconnect();if(dialog!=null)dialog.dismiss();worker.shutdownNow();}
}
