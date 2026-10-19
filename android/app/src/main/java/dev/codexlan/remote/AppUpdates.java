package dev.codexlan.remote;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.concurrent.*;

/** Update checks and system-managed downloads never share the chat worker. */
final class AppUpdates {
    private final Activity activity;
    private final java.util.function.Supplier<RemoteApi> connection;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final java.util.Set<String> prompted=new java.util.HashSet<>();
    private final SharedPreferences preferences;
    private JSONObject available;
    private String desktop="",computer="";
    private boolean checking,manualWanted,closed,started,skipResume;
    private TextView status,versions;
    private Dialog prompt,settings,permissionPrompt;
    private TextView installPermissionState;
    private boolean permissionPending,permissionPageOpen;
    private TextView checkButton;
    private ProgressBar progress;
    private final Runnable startup=()->{if(!offerInstallPermission())check(false);};
    AppUpdates(Activity activity,java.util.function.Supplier<RemoteApi> connection){this.activity=activity;this.connection=connection;preferences=activity.getSharedPreferences("updates",Context.MODE_PRIVATE);try{available=new JSONObject(preferences.getString("release","{}"));}catch(Exception ignored){}}
    private String installed(){try{return activity.getPackageManager().getPackageInfo(activity.getPackageName(),0).versionName;}catch(Exception e){return "";}}
    private void ui(Runnable action){activity.runOnUiThread(()->{if(!closed&&!activity.isDestroyed())action.run();});}
    private void toast(String value){Toast.makeText(activity,value,Toast.LENGTH_LONG).show();}
    void launch(){if(prompt!=null&&prompt.isShowing())return;prompted.clear();handler.removeCallbacks(startup);handler.postDelayed(startup,2200);}
    void resume(){if(!started){started=true;return;}if(skipResume){skipResume=false;return;}launch();}
    void external(){skipResume=true;}
    void navigationFailed(){skipResume=false;}
    void desktop(String value,String fingerprint){if(!computer.equals(fingerprint)){computer=fingerprint;desktop="";}if(UpdatePolicy.numbers(value)==null)return;boolean changed=!value.equals(desktop);desktop=value;if(changed){refreshVersions();maybePrompt(false);}}
    private String computerKey(){RemoteApi api=connection.get();return api==null?"":api.fingerprint;}
    private void refreshVersions(){if(versions!=null)versions.setText(desktop.isEmpty()||!computer.equals(computerKey())?"连接电脑后确认":desktop);}
    private TextView text(String value,int size){TextView view=new TextView(activity);view.setText(value);view.setTextSize(size);view.setTextColor(UiPalette.of(activity).foreground);view.setPadding(0,dp(6),0,dp(6));return view;}
    private int dp(int n){return Math.round(n*activity.getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout view=new LinearLayout(activity);view.setOrientation(LinearLayout.VERTICAL);return view;}
    private android.graphics.drawable.GradientDrawable rounded(int color,int radius){android.graphics.drawable.GradientDrawable shape=new android.graphics.drawable.GradientDrawable();shape.setColor(color);shape.setCornerRadius(dp(radius));return shape;}
    private int surface(){return UiPalette.color(activity,android.graphics.Color.rgb(31,31,31));}
    private void section(LinearLayout parent,View view,int top){LinearLayout.LayoutParams space=new LinearLayout.LayoutParams(-1,-2);space.topMargin=dp(top);parent.addView(view,space);}
    private TextView button(String label,boolean primary,Runnable action){UiPalette palette=UiPalette.of(activity);TextView view=text(label,15);view.setTypeface(null,android.graphics.Typeface.BOLD);view.setGravity(Gravity.CENTER);view.setMinHeight(dp(48));view.setPadding(dp(16),dp(10),dp(16),dp(10));view.setTextColor(primary?(palette.dark?android.graphics.Color.rgb(24,36,29):android.graphics.Color.WHITE):palette.foreground);view.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(palette.dark?0x22ffffff:0x16000000),rounded(primary?palette.accent:surface(),16),null));view.setOnClickListener(v->action.run());return view;}
    private LinearLayout card(){LinearLayout view=column();view.setPadding(dp(16),dp(12),dp(16),dp(12));view.setBackground(rounded(surface(),18));return view;}
    private TextView versionRow(LinearLayout card,String title,String value,String symbol){
        UiPalette palette=UiPalette.of(activity);LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.CENTER_VERTICAL);ImageView icon=new ImageView(activity);icon.setImageDrawable(new UiIcon(symbol,palette.muted));row.addView(icon,new LinearLayout.LayoutParams(dp(22),dp(22)));TextView label=text(title,14);label.setPadding(dp(12),dp(10),dp(8),dp(10));row.addView(label,new LinearLayout.LayoutParams(0,-2,1));TextView version=text(value,13);version.setTextColor(palette.muted);version.setGravity(Gravity.END);version.setMaxWidth(dp(190));row.addView(version);card.addView(row);return version;
    }
    private TextView actionRow(LinearLayout parent,String title,String subtitle,String symbol,Runnable action){
        UiPalette palette=UiPalette.of(activity);LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(14),dp(10),dp(12),dp(10));row.setMinimumHeight(dp(72));ImageView icon=new ImageView(activity);icon.setImageDrawable(new UiIcon(symbol,palette.accent));row.addView(icon,new LinearLayout.LayoutParams(dp(23),dp(23)));LinearLayout labels=column();TextView name=text(title,15);name.setPadding(0,0,0,dp(3));labels.addView(name);TextView hint=text(subtitle,12);hint.setPadding(0,0,0,0);hint.setTextColor(palette.muted);labels.addView(hint);LinearLayout.LayoutParams grow=new LinearLayout.LayoutParams(0,-2,1);grow.leftMargin=dp(12);grow.rightMargin=dp(8);row.addView(labels,grow);ImageView arrow=new ImageView(activity);arrow.setImageDrawable(new UiIcon("next",palette.muted));row.addView(arrow,new LinearLayout.LayoutParams(dp(18),dp(18)));row.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(palette.dark?0x18ffffff:0x10000000),rounded(surface(),14),null));row.setOnClickListener(v->action.run());parent.addView(row);return hint;
    }
    private void checkingUi(boolean busy){if(checkButton!=null){checkButton.setEnabled(!busy);checkButton.setAlpha(busy?.55f:1);checkButton.setText(busy?"正在检查…":"立即检查");}if(progress!=null)progress.setVisibility(busy?View.VISIBLE:View.GONE);}
    void open(){
        if(settings!=null&&settings.isShowing())return;UiPalette palette=UiPalette.of(activity);Dialog dialog=new Dialog(activity);settings=dialog;dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);LinearLayout page=column();page.setPadding(dp(16),dp(12),dp(16),dp(12));page.setBackgroundColor(palette.background);page.setFitsSystemWindows(true);
        page.setOnApplyWindowInsetsListener((view,insets)->{if(Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.ime());view.setPadding(dp(16)+bars.left,dp(8)+bars.top,dp(16)+bars.right,dp(12)+bars.bottom);}return insets;});
        LinearLayout bar=new LinearLayout(activity);bar.setGravity(Gravity.CENTER_VERTICAL);ImageButton back=new ImageButton(activity);back.setImageDrawable(new UiIcon("back",palette.foreground));back.setBackgroundColor(android.graphics.Color.TRANSPARENT);back.setContentDescription("返回");bar.addView(back,new LinearLayout.LayoutParams(dp(44),dp(48)));back.setOnClickListener(v->dialog.dismiss());TextView heading=text("检测更新",19);heading.setTypeface(null,android.graphics.Typeface.BOLD);bar.addView(heading);page.addView(bar);
        ScrollView scroll=new ScrollView(activity);scroll.setClipToPadding(false);scroll.setVerticalScrollBarEnabled(false);LinearLayout body=column();body.setPadding(0,dp(14),0,dp(20));scroll.addView(body);page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout brand=column();brand.setGravity(Gravity.CENTER_HORIZONTAL);brand.setPadding(0,dp(8),0,dp(18));ImageView mark=new ImageView(activity);mark.setImageResource(R.drawable.app_icon);brand.addView(mark,new LinearLayout.LayoutParams(dp(64),dp(64)));TextView name=text("Codex LAN",22);name.setTypeface(null,android.graphics.Typeface.BOLD);brand.addView(name);TextView caption=text("应用与电脑插件更新",13);caption.setTextColor(palette.muted);brand.addView(caption);body.addView(brand);
        LinearLayout current=card();versionRow(current,"手机 App",installed(),"phone");versions=versionRow(current,"电脑插件","","desktop");refreshVersions();body.addView(current);
        LinearLayout state=card();LinearLayout stateTitle=new LinearLayout(activity);stateTitle.setGravity(Gravity.CENTER_VERTICAL);TextView title=text("版本状态",15);title.setTypeface(null,android.graphics.Typeface.BOLD);stateTitle.addView(title,new LinearLayout.LayoutParams(0,-2,1));progress=new ProgressBar(activity);progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(palette.accent));progress.setVisibility(View.GONE);stateTitle.addView(progress,new LinearLayout.LayoutParams(dp(18),dp(18)));state.addView(stateTitle);status=text(downloadStatus(),13);status.setTextColor(palette.muted);status.setLineSpacing(dp(2),1.1f);state.addView(status);checkButton=button("立即检查",true,()->check(true));section(state,checkButton,10);section(body,state,12);
        LinearLayout actions=card();actions.setPadding(dp(2),dp(2),dp(2),dp(2));installPermissionState=actionRow(actions,"安装权限",installPermissionLabel(),"settings",this::requestInstallPermission);actionRow(actions,"下载管理","查看安装包下载进度与记录","download",this::downloads);actionRow(actions,"版本说明","查看 GitHub 发布内容","file",()->browse(UpdatePolicy.PAGE));actionRow(actions,"更新提醒","恢复已忽略版本的提醒","reset",()->{preferences.edit().remove("ignoredAndroid").remove("ignoredDesktop").apply();prompted.clear();toast("已恢复更新提醒");});section(body,actions,12);
        TextView rule=text("自动提醒主／次版本升级，例如 0.4 → 0.5。补丁版本可手动检查，忽略仅对当前版本生效。",12);rule.setTextColor(palette.muted);rule.setPadding(dp(12),dp(16),dp(12),0);body.addView(rule);
        dialog.setContentView(page);dialog.setOnDismissListener(d->{if(settings==dialog){settings=null;status=null;versions=null;checkButton=null;progress=null;installPermissionState=null;}});dialog.show();if(dialog.getWindow()!=null){dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);dialog.getWindow().setLayout(-1,-1);}check(true);
    }
    void check(boolean manual){
        manualWanted|=manual;checkingUi(true);if(checking){if(manual&&status!=null)status.setText("正在检查…");return;}checking=true;if(status!=null)status.setText("正在检查 GitHub Release…");
        String current=installed();RemoteApi api=connection.get();
        worker.execute(()->{
            JSONObject result=null;String remoteDesktop="",failure=null;
            if(api!=null){RemoteApi channel=api.readChannel();try{remoteDesktop=channel.call("GET","/v1/info",null,null,3000,5000).optString("desktopVersion");}catch(Exception ignored){}finally{channel.cancel();}}
            try{long age=System.currentTimeMillis()-preferences.getLong("checkedAt",0);if(!manual&&available!=null&&available.has("tag")&&(current.contains("-")||!available.optString("tag").contains("-"))&&age>=0&&age<6*60*60*1000)result=available;else{result=GitHubUpdates.fetch(current);if(result==null||!result.has("tag")||!result.has("android")&&!result.has("desktop"))throw new Exception("发布页尚无可下载安装包");preferences.edit().putString("release",result.toString()).putLong("checkedAt",System.currentTimeMillis()).apply();}}catch(Exception error){failure=error.getMessage();}
            JSONObject release=result;String version=remoteDesktop,error=failure;
            ui(()->{checking=false;checkingUi(false);boolean wanted=manualWanted;manualWanted=false;if(api==connection.get()&&UpdatePolicy.numbers(version)!=null){computer=computerKey();desktop=version;refreshVersions();}if(release!=null)available=release;if(error!=null){if(status!=null)status.setText("检查失败："+error+"\n可稍后重试或查看发布页。");else if(wanted)toast("更新检查失败，请稍后重试");return;}if(status!=null)status.setText("GitHub 最新版本  "+available.optString("tag")+"\n"+downloadStatus());boolean shown=maybePrompt(wanted);if(wanted&&!shown&&status!=null)status.append("\n未发现可用更新。");});
        });
    }
    void focus(){if(permissionPending&&offerInstallPermission())return;maybePrompt(false);}
    private boolean maybePrompt(boolean manual){
        if(closed||permissionPageOpen||permissionPrompt!=null&&permissionPrompt.isShowing()||!manual&&!preferences.getBoolean("installPermissionAsked",false)&&!installAllowed()||available==null||!available.has("tag")||activity.isFinishing()||!manual&&!activity.hasWindowFocus()||prompt!=null&&prompt.isShowing())return false;
        String tag=available.optString("tag");
        boolean phone=available.has("android")&&UpdatePolicy.notify(tag,installed(),preferences.getString("ignoredAndroid",""),manual);
        boolean pc=computer.equals(computerKey())&&available.has("desktop")&&UpdatePolicy.notify(tag,desktop,preferences.getString("ignoredDesktop",""),manual);
        String minimum=available.optString("minDesktopVersion");if(computer.equals(computerKey())&&available.has("desktop")&&UpdatePolicy.numbers(desktop)!=null&&UpdatePolicy.numbers(minimum)!=null&&UpdatePolicy.compare(minimum,desktop)>0&&(manual||!tag.equals(preferences.getString("ignoredDesktop",""))))pc=true;
        if(!manual){phone&=!prompted.contains(tag+":android");pc&=!prompted.contains(tag+":desktop:"+computerKey());}if(!phone&&!pc)return false;
        final boolean app=phone,plugin=pc;if(app)prompted.add(tag+":android");if(plugin)prompted.add(tag+":desktop:"+computerKey());
        UiPalette palette=UiPalette.of(activity);Dialog dialog=new Dialog(activity);prompt=dialog;dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);LinearLayout body=column();body.setPadding(dp(22),dp(20),dp(22),dp(16));body.setBackground(rounded(palette.dark?android.graphics.Color.rgb(35,35,35):android.graphics.Color.rgb(250,251,250),24));
        LinearLayout heading=new LinearLayout(activity);heading.setGravity(Gravity.CENTER_VERTICAL);ImageView icon=new ImageView(activity);icon.setImageDrawable(new UiIcon("download",palette.accent));heading.addView(icon,new LinearLayout.LayoutParams(dp(26),dp(26)));TextView title=text("发现新版本",20);title.setTypeface(null,android.graphics.Typeface.BOLD);title.setPadding(dp(12),0,0,0);heading.addView(title);body.addView(heading);TextView badge=text(tag,14);badge.setTextColor(palette.accent);section(body,badge,8);
        LinearLayout versionCard=card();if(app)versionRow(versionCard,"手机 App",installed()+" → "+tag,"phone");if(plugin)versionRow(versionCard,"电脑插件",desktop+" → "+tag,"desktop");section(body,versionCard,8);
        if(plugin){TextView instruction=text("电脑插件需在电脑上安装更新，原连接配置可保留。",13);instruction.setTextColor(palette.muted);section(body,instruction,8);}
        TextView reminder=text("下次更新将在下次打开时提醒；忽略仅对当前版本生效。",12);reminder.setTextColor(palette.muted);section(body,reminder,8);
        section(body,button(app?"现在更新":"查看电脑端更新",true,()->{dialog.dismiss();if(app)download(available.optJSONObject("android"),tag);else browse(available.optString("page",UpdatePolicy.PAGE));}),14);
        LinearLayout choices=new LinearLayout(activity);TextView later=button("下次更新",false,dialog::dismiss);choices.addView(later,new LinearLayout.LayoutParams(0,-2,1));TextView ignore=button("忽略更新",false,()->{SharedPreferences.Editor edit=preferences.edit();if(app)edit.putString("ignoredAndroid",tag);if(plugin)edit.putString("ignoredDesktop",tag);edit.apply();dialog.dismiss();});LinearLayout.LayoutParams right=new LinearLayout.LayoutParams(0,-2,1);right.leftMargin=dp(8);choices.addView(ignore,right);section(body,choices,8);
        ScrollView content=new ScrollView(activity);content.setFillViewport(false);content.setBackground(body.getBackground());content.setClipToOutline(true);body.setBackground(null);content.addView(body);dialog.setContentView(content);dialog.show();Window window=dialog.getWindow();if(window!=null){window.setBackgroundDrawableResource(android.R.color.transparent);window.setDimAmount(.45f);int width=Math.min(dp(380),activity.getResources().getDisplayMetrics().widthPixels-dp(32));int max=activity.getResources().getDisplayMetrics().heightPixels-dp(96);body.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(max,View.MeasureSpec.UNSPECIFIED));window.setLayout(width,Math.min(body.getMeasuredHeight(),max));}return true;
    }
    private boolean installAllowed(){try{return activity.getPackageManager().canRequestPackageInstalls();}catch(Exception ignored){return false;}}
    private String installPermissionLabel(){return installAllowed()?"已允许安装应用":"未开启 · 点击前往系统设置";}
    private boolean offerInstallPermission(){
        if(closed||activity.isFinishing())return false;
        if(installAllowed()){preferences.edit().putBoolean("installPermissionAsked",true).apply();permissionPending=false;return false;}
        if(preferences.getBoolean("installPermissionAsked",false)){permissionPending=false;return false;}
        if(!activity.hasWindowFocus()){permissionPending=true;return true;}
        if(permissionPrompt!=null&&permissionPrompt.isShowing())return true;
        permissionPending=false;preferences.edit().putBoolean("installPermissionAsked",true).apply();UiPalette palette=UiPalette.of(activity);Dialog dialog=new Dialog(activity);permissionPrompt=dialog;dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);LinearLayout body=column();body.setPadding(dp(22),dp(22),dp(22),dp(18));
        TextView title=text("开启应用安装权限",19);title.setTypeface(null,android.graphics.Typeface.BOLD);body.addView(title);TextView description=text("用于安装你确认下载的 Codex LAN 更新。请在系统页面开启“允许来自此来源的应用”。暂时跳过也能继续聊天，之后可在检测更新中开启。",14);description.setTextColor(palette.muted);description.setLineSpacing(dp(3),1.1f);section(body,description,8);
        section(body,button("去开启",true,()->{permissionPageOpen=true;dialog.dismiss();requestInstallPermission();}),16);section(body,button("暂时跳过",false,dialog::dismiss),8);dialog.setOnDismissListener(d->{permissionPrompt=null;if(!closed&&!permissionPageOpen)handler.postDelayed(startup,300);});showCard(dialog,body);
        return true;
    }
    private void requestInstallPermission(){
        preferences.edit().putBoolean("installPermissionAsked",true).apply();permissionPageOpen=true;Intent intent=new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+activity.getPackageName()));
        try{activity.startActivityForResult(intent,209);}catch(ActivityNotFoundException unavailable){try{activity.startActivityForResult(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+activity.getPackageName())),209);toast("请在应用信息中查找安装未知应用权限");}catch(ActivityNotFoundException missing){permissionPageOpen=false;toast("请在系统设置中为 Codex LAN 开启安装应用权限");}}
    }
    boolean activityResult(int request){if(request!=209)return false;permissionPageOpen=false;if(installPermissionState!=null)installPermissionState.setText(installPermissionLabel());toast(installAllowed()?"应用安装权限已开启":"可稍后在检测更新中开启安装权限");handler.postDelayed(startup,300);return true;}
    private void showCard(Dialog dialog,LinearLayout body){
        UiPalette palette=UiPalette.of(activity);ScrollView content=new ScrollView(activity);content.setFillViewport(false);content.setBackground(rounded(palette.dark?android.graphics.Color.rgb(35,35,35):android.graphics.Color.rgb(250,251,250),24));content.setClipToOutline(true);content.addView(body);dialog.setContentView(content);dialog.show();Window window=dialog.getWindow();if(window!=null){window.setBackgroundDrawableResource(android.R.color.transparent);window.setDimAmount(.45f);int width=Math.min(dp(380),activity.getResources().getDisplayMetrics().widthPixels-dp(32)),max=activity.getResources().getDisplayMetrics().heightPixels-dp(96);body.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));window.setLayout(width,Math.min(body.getMeasuredHeight(),max));}
    }
    private DownloadManager manager(){return (DownloadManager)activity.getSystemService(Context.DOWNLOAD_SERVICE);}
    private int state(){long id=preferences.getLong("downloadId",-1);if(id<0)return -1;try(Cursor cursor=manager().query(new DownloadManager.Query().setFilterById(id))){return cursor!=null&&cursor.moveToFirst()?cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)):-1;}catch(Exception e){return -1;}}
    private String downloadStatus(){int state=state();if(state==DownloadManager.STATUS_SUCCESSFUL)return "更新包已下载，可在下载列表中安装。";if(state==DownloadManager.STATUS_PENDING||state==DownloadManager.STATUS_RUNNING||state==DownloadManager.STATUS_PAUSED)return "更新包正在由系统下载，可在下载列表查看进度。";if(state==DownloadManager.STATUS_FAILED)return "上次下载失败，可重新检查并下载。";return "仅检测 GitHub 已发布的 Release，不检测代码提交。";}
    private void download(JSONObject asset,String tag){
        if(asset==null||UpdatePolicy.downloadUrl(asset.optString("url")).isEmpty()){toast("此版本尚无安卓安装包");return;}
        int existing=state();if(tag.equals(preferences.getString("downloadTag",""))&&(existing==DownloadManager.STATUS_PENDING||existing==DownloadManager.STATUS_RUNNING||existing==DownloadManager.STATUS_PAUSED||existing==DownloadManager.STATUS_SUCCESSFUL)){downloads();return;}
        try{String name=asset.optString("name").replaceAll("[^a-zA-Z0-9._-]","_");if(!name.endsWith(".apk"))throw new Exception("安装包格式无效");DownloadManager.Request request=new DownloadManager.Request(Uri.parse(asset.getString("url"))).setTitle("Codex LAN "+tag).setDescription("从 GitHub 下载应用更新").setMimeType("application/vnd.android.package-archive").setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED).setAllowedOverMetered(true);
            name=name.substring(0,name.length()-4)+"-"+System.currentTimeMillis()+".apk";
            if(Build.VERSION.SDK_INT>=29)request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,name);else request.setDestinationInExternalFilesDir(activity,Environment.DIRECTORY_DOWNLOADS,name);
            long id=manager().enqueue(request);preferences.edit().putLong("downloadId",id).putString("downloadTag",tag).apply();toast("已开始从 GitHub 下载，完成后可在系统下载列表安装");if(status!=null)status.setText(downloadStatus());
        }catch(Exception e){toast("无法开始下载："+e.getMessage());}
    }
    private void downloads(){try{activity.startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS));}catch(ActivityNotFoundException e){toast("系统未提供下载列表，请在文件管理器查看 Downloads");}}
    private void browse(String page){try{activity.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(page)));}catch(ActivityNotFoundException e){toast("请使用浏览器打开项目发布页");}}
    void close(){closed=true;handler.removeCallbacks(startup);if(prompt!=null)prompt.dismiss();if(settings!=null)settings.dismiss();if(permissionPrompt!=null)permissionPrompt.dismiss();worker.shutdownNow();}
}
