package dev.codexlan.remote;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private final ExecutorService worker = Executors.newFixedThreadPool(3);
    private final int background = Color.rgb(18, 18, 18), foreground = Color.rgb(242, 242, 242), muted = Color.rgb(157, 157, 157), accent = Color.rgb(220, 233, 225);
    private SecureSession storage;
    private RemoteApi api;
    private volatile int generation;
    private boolean visible;
    private boolean connectionGuideVisible;
    private LinearLayout pairingProgressCard;
    private TextView pairingProgressText;
    private ProgressBar pairingSpinner;
    private Button pairingContinue,pairingRescan;
    private Runnable pairingRefreshControls;
    private LaunchAnimationView launchAnimation;
    private LinearLayout root;
    private TextView status, sendStatus;
    private EditText composer;
    private Button send;
    private TextView modelChoice,latestJump;
    private ConversationRail jumpRail;
    private PopupWindow railPreview,modelPopup;
    private final java.util.Map<String,JSONObject> modelCache=new java.util.LinkedHashMap<>();
    private final java.util.Map<String,Long> modelCacheAt=new java.util.HashMap<>();
    private static final class ModelLoad {final RemoteApi client;final int epoch;ModelLoad(RemoteApi client,int epoch){this.client=client;this.epoch=epoch;}}
    private final java.util.Map<String,ModelLoad> modelLoading=new java.util.HashMap<>();
    private String modelOpenWanted="";
    private TextView railPreviewTitle,railPreviewPrompt,railPreviewReply;
    private String railChat="",railPreviewContent="";
    private final java.util.Map<String,String> questionDrafts=new java.util.HashMap<>();
    private final java.util.Set<String> expandedTools = new java.util.HashSet<>();
    private final java.util.Map<String, String> drafts = new java.util.HashMap<>();
    private JSONObject currentThread;
    private TranscriptListView scroll;
    private ChatAdapter chatAdapter;
    private final ExecutorService renderWorker = Executors.newSingleThreadExecutor();
    private final android.os.Handler renderHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private int renderRevision;
    private final RenderGate renderGate=new RenderGate();
    private boolean listScrolling;
    private java.util.List<ChatRows.Row> deferredRows;
    private int deferredFirst,deferredTop;
    private final Runnable flushRows=this::flushRowsWhenStill;
    private String displayedChat = "";
    private final Runnable prepareRows = () -> {
        final JSONObject thread=currentThread; final int revision=renderRevision;
        if(thread==null||isDestroyed()||!renderGate.begin())return;
        final ChatAdapter page=chatAdapter;final String chat=thread.optString("id");
        renderWorker.execute(()->{try{
            java.util.List<ChatRows.Row> rows=ChatRows.build(thread);
            runOnUiThread(()->{
                boolean samePage=!isDestroyed()&&page==chatAdapter&&currentThread!=null&&chat.equals(currentThread.optString("id"));
                // Apply a completed same-chat result even if a newer snapshot is
                // pending. Otherwise a busy stream can starve the UI forever.
                if(renderGate.complete(revision,samePage)){
                    if(listScrolling&&chat.equals(displayedChat))deferRows(rows);
                    else{listScrolling=false;deferredRows=null;renderHandler.removeCallbacks(flushRows);applyRows(rows);}
                }
                if(renderRevision>revision)scheduleRows();
            });
        }catch(Exception e){runOnUiThread(()->{renderGate.failed();if(isDestroyed())return;if(revision==renderRevision)toast("聊天显示失败："+error(e));else scheduleRows();});}});
    };
    private boolean sending;
    private void scheduleRows(){if(!isDestroyed()&&currentThread!=null&&!renderHandler.hasCallbacks(prepareRows))renderHandler.post(prepareRows);}
    private int firstRowTop(){View first=scroll.getChildAt(0);return first==null?0:first.getTop();}
    private void deferRows(java.util.List<ChatRows.Row> rows){
        deferredRows=rows;
        if(!renderHandler.hasCallbacks(flushRows)){deferredFirst=scroll.getFirstVisiblePosition();deferredTop=firstRowTop();renderHandler.postDelayed(flushRows,250);}
    }
    private void flushRowsWhenStill(){
        if(isDestroyed()||deferredRows==null)return;
        int first=scroll.getFirstVisiblePosition(),top=firstRowTop();
        // Native lists can miss IDLE when a fling is interrupted by selection
        // or layout. Observe actual movement instead of waiting indefinitely.
        if(listScrolling&&(first!=deferredFirst||top!=deferredTop)){deferredFirst=first;deferredTop=top;renderHandler.postDelayed(flushRows,250);return;}
        listScrolling=false;java.util.List<ChatRows.Row> rows=deferredRows;deferredRows=null;applyRows(rows);
    }
    private JSONObject currentState;
    private String selectedThreadId = "";
    private Button selectChat;
    private final android.content.BroadcastReceiver updates = new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context context, android.content.Intent intent) {
            if(PairingCoordinator.UPDATE.equals(intent.getAction())){refreshPairingUi();return;}
            if(connectionGuideVisible)return;
            if (api == null || SessionService.latest == null || !api.fingerprint.equals(SessionService.latest.optString("computer"))) return;
            try { render(SessionService.latest); } catch (Exception error) { status.setText("同步显示失败：" + error.getMessage()); }
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state); storage = new SecureSession(this);
        try {
            JSONObject saved = storage.load();
            if (saved != null) api = new RemoteApi(saved.getString("address"), saved.getString("fingerprint"), saved.getString("token"));
        } catch (Exception e) { storage.clear(); }
        selectedThreadId = getIntent().getStringExtra("threadId") == null ? (api == null ? "" : getPreferences(MODE_PRIVATE).getString("selected:" + api.fingerprint, "")) : getIntent().getStringExtra("threadId");
        if (state != null) {
            selectedThreadId = state.getString("selectedChat", selectedThreadId);
            java.util.ArrayList<String> ids = state.getStringArrayList("draftIds"), values = state.getStringArrayList("draftValues");
            if(ids != null && values != null) for(int i=0;i<Math.min(ids.size(),values.size());i++)drafts.put(ids.get(i),values.get(i));
        }
        if (api == null || state!=null&&state.getBoolean("connectionGuideVisible",false)&&!state.getBoolean("pairingInProgress",false)) connectionPage(); else chatPage();
        if(state==null)showLaunchAnimation();
    }
    private int dp(int value) { return (int) (getResources().getDisplayMetrics().density * value + .5f); }
    private LinearLayout vertical() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); return layout; }
    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); view.setPadding(0, dp(7), 0, dp(7)); view.setTextIsSelectable(true); return view;
    }
    private void base(String title, String subtitle) {
        root = vertical(); root.setBackgroundColor(background); root.setPadding(dp(20), dp(14), dp(20), dp(10));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.ime());
                view.setPadding(dp(20) + bars.left, dp(10) + bars.top, dp(20) + bars.right, dp(10) + bars.bottom);
            }
            return insets;
        });
        setContentView(root); root.addView(text(title, 26, foreground)); root.addView(text(subtitle, 12, muted));
    }
    private EditText field(String hint) {
        EditText input = new EditText(this); input.setHint(hint); input.setTextColor(foreground); input.setHintTextColor(muted); input.setTextSize(15); input.setSingleLine(true);
        input.setPadding(dp(10), dp(12), dp(10), dp(12)); input.setBackgroundColor(Color.rgb(38, 42, 45));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(8); input.setLayoutParams(params);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO); return input;
    }
    private Button button(String label) {
        Button button = new Button(this); button.setText(label); button.setTextColor(background); button.setTextSize(14); button.setAllCaps(false);
        GradientDrawable shape = new GradientDrawable(); shape.setColor(accent); shape.setCornerRadius(dp(16)); button.setBackground(shape);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(46)); params.topMargin = dp(10); button.setLayoutParams(params); return button;
    }
    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(radius)); return shape;
    }
    private Button icon(String name, String description, boolean primary) {
        Button view = new Button(this); view.setText(""); view.setContentDescription(description); view.setMinWidth(0); view.setMinimumWidth(0); view.setMinHeight(0); view.setMinimumHeight(0); view.setPadding(dp(12),dp(12),dp(12),dp(12));
        UiIcon image = new UiIcon(name, primary ? background : foreground); image.setBounds(0,0,dp(22),dp(22)); view.setCompoundDrawables(image,null,null,null);
        view.setBackground(rounded(primary ? foreground : Color.TRANSPARENT,24)); return view;
    }
    private LinearLayout toolbar(String title, Runnable back) {
        LinearLayout bar = new LinearLayout(this); bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        Button close = icon("back", "返回", false); bar.addView(close,new LinearLayout.LayoutParams(dp(48),dp(48))); close.setOnClickListener(v -> back.run());
        TextView heading = text(title,20,foreground); heading.setTypeface(null,Typeface.BOLD); heading.setTextIsSelectable(false); bar.addView(heading,new LinearLayout.LayoutParams(0,dp(52),1)); return bar;
    }
    private java.util.function.Consumer<String> pairingInput;
    private void connectionPage() {
        connectionGuideVisible=true;
        base("连接你的电脑", "扫码配对，从手机继续电脑上的工作");
        ScrollView formScroll=new ScrollView(this);formScroll.setFillViewport(true);LinearLayout form=vertical();formScroll.addView(form);root.addView(formScroll,new LinearLayout.LayoutParams(-1,0,1));
        pairingProgressCard=vertical();pairingProgressCard.setPadding(dp(18),dp(14),dp(18),dp(18));pairingProgressCard.setBackground(rounded(Color.rgb(31,39,35),20));form.addView(pairingProgressCard);pairingProgressCard.setVisibility(View.GONE);
        pairingProgressText=text("正在完成配对…",15,accent);pairingProgressCard.addView(pairingProgressText);pairingSpinner=new ProgressBar(this);pairingProgressCard.addView(pairingSpinner,new LinearLayout.LayoutParams(dp(28),dp(28)));
        pairingContinue=button("继续配对");pairingProgressCard.addView(pairingContinue);pairingContinue.setOnClickListener(v->continuePendingPairing());
        pairingRescan=button("重新扫码");pairingRescan.setBackground(rounded(Color.rgb(46,46,46),16));pairingRescan.setTextColor(foreground);pairingProgressCard.addView(pairingRescan);pairingRescan.setOnClickListener(v->{PairingCoordinator.discard(this);refreshPairingUi();startActivityForResult(new android.content.Intent(this,PairingScanActivity.class),204);});
        LinearLayout guide=vertical();guide.setPadding(dp(18),dp(16),dp(18),dp(16));guide.setBackground(rounded(Color.rgb(28,28,28),22));
        guide.addView(text("让工作接着进行",21,foreground));guide.addView(text("在电脑 Codex 中打开「手机连接」设置，启动连接服务并生成配对二维码。",14,muted));LinearLayout.LayoutParams guideSpace=new LinearLayout.LayoutParams(-1,-2);guideSpace.topMargin=dp(16);guideSpace.bottomMargin=dp(14);form.addView(guide,guideSpace);
        LinearLayout network=vertical();network.setPadding(dp(16),dp(8),dp(16),dp(12));network.setBackground(rounded(Color.rgb(28,28,28),20));network.addView(text("连接方式",12,muted));
        RadioGroup modes=new RadioGroup(this);modes.setOrientation(LinearLayout.HORIZONTAL);RadioButton lan=new RadioButton(this),et=new RadioButton(this);lan.setText("局域网直连");et.setText("EasyTier 网络");lan.setTextSize(14);et.setTextSize(14);lan.setTextColor(foreground);et.setTextColor(foreground);lan.setId(View.generateViewId());et.setId(View.generateViewId());modes.addView(lan,new LinearLayout.LayoutParams(0,dp(46),1));modes.addView(et,new LinearLayout.LayoutParams(0,dp(46),1));network.addView(modes);
        TextView networkHint=text("",12,muted);network.addView(networkHint);Button easytier=button("设置 EasyTier 组网");easytier.setBackground(rounded(Color.rgb(46,46,46),14));easytier.setTextColor(foreground);network.addView(easytier);easytier.setOnClickListener(v->easyTierDialog());form.addView(network);
        LinearLayout card=vertical();card.setPadding(dp(16),dp(12),dp(16),dp(16));card.setBackground(rounded(Color.rgb(28,28,28),22));LinearLayout.LayoutParams cardSpace=new LinearLayout.LayoutParams(-1,-2);cardSpace.topMargin=dp(14);form.addView(card,cardSpace);card.addView(text("添加电脑",16,foreground));
        Button scan=button("扫码添加电脑");UiIcon scanIcon=new UiIcon("scan",background);scanIcon.setBounds(0,0,dp(20),dp(20));android.text.SpannableString scanLabel=new android.text.SpannableString("\uFFFC  扫码添加电脑");scanLabel.setSpan(new android.text.style.ImageSpan(scanIcon,android.text.style.DynamicDrawableSpan.ALIGN_BOTTOM),0,1,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);scan.setText(scanLabel);scan.setGravity(android.view.Gravity.CENTER);scan.setContentDescription("扫码添加电脑");card.addView(scan);scan.setOnClickListener(v->startActivityForResult(new android.content.Intent(this,PairingScanActivity.class),204));
        Button paste=button("粘贴配对信息");paste.setBackground(rounded(Color.rgb(46,46,46),16));paste.setTextColor(foreground);card.addView(paste);
        TextView manual=text("手动输入配对信息  ›",13,muted);manual.setTextIsSelectable(false);manual.setGravity(android.view.Gravity.CENTER);manual.setPadding(0,dp(16),0,dp(10));card.addView(manual);
        LinearLayout fields=vertical();fields.setVisibility(View.GONE);card.addView(fields);
        EditText address=field("电脑地址，例如 IP:9443"),fingerprint=field("电脑证书指纹"),code=field("配对码");
        address.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);fingerprint.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);code.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        for(EditText input:new EditText[]{address,fingerprint,code}){input.setBackground(rounded(Color.rgb(42,42,42),14));fields.addView(input);}
        TextView feedback=text("配对二维码只在手机本地识别。",12,muted);card.addView(feedback);
        Button pair=button("配对并连接");pair.setVisibility(View.GONE);card.addView(pair);
        manual.setOnClickListener(v->{boolean show=fields.getVisibility()!=View.VISIBLE;fields.setVisibility(show?View.VISIBLE:View.GONE);pair.setVisibility(show?View.VISIBLE:View.GONE);manual.setText(show?"收起手动填写  ⌃":"手动输入配对信息  ›");});
        modes.setOnCheckedChangeListener((group,id)->{boolean direct=id==lan.getId();if(api==null)getPreferences(MODE_PRIVATE).edit().putString("networkMode",direct?"lan":"easytier").apply();easytier.setVisibility(direct||api!=null?View.GONE:View.VISIBLE);networkHint.setText(direct?"手机和电脑连接同一 Wi-Fi，即可直接配对。":"适合异地连接，先完成组网，再扫描电脑配对码。");});
        modes.check("easytier".equals(getPreferences(MODE_PRIVATE).getString("networkMode","lan"))?et.getId():lan.getId());
        pairingRefreshControls=()->{boolean idle=!PairingCoordinator.busy();scan.setEnabled(idle);paste.setEnabled(idle);pair.setEnabled(idle&&api==null);};
        final PairingInfo[] scanned={null};
        pairingInput=value->{try{PairingCoordinator.discard(this);PairingInfo info=PairingInfo.parse(value,System.currentTimeMillis());scanned[0]=info;address.setText(info.address);fingerprint.setText(info.fingerprint);code.setText(info.code);fields.setVisibility(View.VISIBLE);pair.setVisibility(View.VISIBLE);manual.setText("收起配对信息  ⌃");feedback.setText(info.easytier==null?"已读取配对信息，确认电脑地址后即可连接。":"已读取 EasyTier 入网与配对信息。点击下方确认导入，网络密钥不显示。");pair.setText(info.easytier==null?"配对并连接":"加入 EasyTier 并配对");if(api==null&&!"existing".equals(info.networkMode))modes.check(info.easytier==null?lan.getId():et.getId());}catch(Exception e){scanned[0]=null;code.setText("");pair.setVisibility(View.GONE);feedback.setText(e.getMessage());}};
        paste.setOnClickListener(v->{android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);if(clipboard.getPrimaryClip()==null){feedback.setText("请先复制电脑端的完整配对信息");return;}pairingInput.accept(clipboard.getPrimaryClip().getItemAt(0).coerceToText(this).toString());});
        View.OnClickListener pairAction=v->{try{
            RemoteApi candidate=new RemoteApi(MobileState.connectionAddress(address.getText().toString()),fingerprint.getText().toString(),null);String pairCode=code.getText().toString().trim();if(pairCode.isEmpty())throw new Exception("请输入配对码");pair.setEnabled(false);scan.setEnabled(false);paste.setEnabled(false);feedback.setText("正在验证电脑并配对…");
            java.util.function.Consumer<String> failed=message->{if(isDestroyed())return;pair.setEnabled(true);scan.setEnabled(true);paste.setEnabled(true);feedback.setText("连接失败："+message);};
            Runnable sendPair=()->worker.execute(()->{try{JSONObject result=candidate.call("POST","/v1/pair",new JSONObject().put("code",pairCode).put("name",android.os.Build.MODEL),null);candidate.token=result.getString("token");storage.save(new JSONObject().put("address",candidate.address).put("fingerprint",candidate.fingerprint).put("token",candidate.token));runOnUiThread(()->{if(isDestroyed())return;pairingInput=null;scanned[0]=null;api=candidate;selectedThreadId="";currentState=null;code.setText("");chatPage();if(visible)beginPolling();});}catch(Exception e){runOnUiThread(()->failed.accept(error(e)));}});
            PairingInfo info=scanned[0];
            if(info!=null&&info.easytier!=null){
                if(!candidate.address.equals(info.address)||!candidate.fingerprint.equals(info.fingerprint)||!pairCode.equals(info.code)){failed.accept("配对信息已修改，请重新扫码确认");return;}
                feedback.setText("请确认导入 EasyTier 组网配置…");
                new AlertDialog.Builder(this).setTitle("加入 EasyTier 网络？").setMessage("网络："+info.easytier.getString("name")+"\n手机虚拟地址："+(info.easytier.optBoolean("dhcp")?"自动分配（DHCP）":info.easytier.getString("cidr"))+"\n\n将导入二维码中的网络参数并启动本 App 的 VPN，再验证电脑证书和配对。已有组网配置将被替换。")
                    .setNegativeButton("取消",(d,w)->failed.accept("已取消导入"))
                    .setPositiveButton("确认加入并配对",(d,w)->joinPairingNetwork(info,sendPair,failed))
                    .setOnCancelListener(d->failed.accept("已取消导入")).show();
            }else {
                if(info!=null&&"lan".equals(info.networkMode)){getPreferences(MODE_PRIVATE).edit().putString("networkMode","lan").apply();if(EasyTierVpnService.running)EasyTierVpnService.disconnect(this);}
                sendPair.run();
            }
        }catch(Exception e){pair.setEnabled(api==null);scan.setEnabled(true);paste.setEnabled(true);feedback.setText(error(e));}};
        pair.setOnClickListener(v->{if(api==null)pairAction.onClick(v);});
        TextView footer=text("连接后可继续对话、查看进度和处理审批。",12,muted);footer.setGravity(android.view.Gravity.CENTER);footer.setPadding(0,dp(18),0,dp(10));form.addView(footer);
        refreshPairingUi();
        if(api!=null){pair.setEnabled(false);footer.setText("正在预览连接引导，当前配对保持不变。");Button back=button("返回当前对话");back.setBackground(rounded(Color.rgb(42,42,42),16));back.setTextColor(foreground);form.addView(back);back.setOnClickListener(v->leaveConnectionGuide());}
    }
    private void leaveConnectionGuide(){connectionGuideVisible=false;pairingInput=null;chatPage();try{if(SessionService.latest!=null)render(SessionService.latest);}catch(Exception ignored){}beginPolling();}
    private void joinPairingNetwork(PairingInfo info,Runnable paired,java.util.function.Consumer<String> failed){
        try{PairingCoordinator.prepare(this,info);continuePendingPairing();}catch(Exception error){failed.accept(error(error));}
    }
    private void continuePendingPairing(){
        try{android.content.Intent permission=android.net.VpnService.prepare(this);if(permission==null)PairingCoordinator.start(this);else startActivityForResult(permission,205);refreshPairingUi();}
        catch(Exception error){toast(error(error));}
    }
    private void refreshPairingUi(){
        if(!connectionGuideVisible||api!=null)return;
        try{
            JSONObject saved=storage.load();
            if(saved!=null){api=new RemoteApi(saved.getString("address"),saved.getString("fingerprint"),saved.getString("token"));selectedThreadId="";currentState=null;pairingInput=null;chatPage();if(visible)beginPolling();return;}
            JSONObject pending=PairingCoordinator.pending(this);if(pairingProgressCard==null)return;
            pairingProgressCard.setVisibility(pending==null?View.GONE:View.VISIBLE);boolean busy=PairingCoordinator.busy();
            if(pending!=null){boolean uncertain="submitting".equals(pending.optString("phase"));String message=PairingCoordinator.message();pairingProgressText.setText(message.isEmpty()?(uncertain?"上次配对结果未确认，请重新扫码":"入网流程尚未完成，点击继续配对"):message);pairingProgressText.setTextColor(PairingCoordinator.failed()?Color.rgb(241,175,157):accent);pairingSpinner.setVisibility(busy?View.VISIBLE:View.GONE);pairingContinue.setVisibility(!busy&&!uncertain?View.VISIBLE:View.GONE);pairingRescan.setVisibility(busy?View.GONE:View.VISIBLE);}
            if(pairingRefreshControls!=null)pairingRefreshControls.run();
        }catch(Exception error){toast("无法恢复配对流程，请重新扫码");}
    }
    private void showLaunchAnimation(){
        if(isFinishing()||isDestroyed()||!android.animation.ValueAnimator.areAnimatorsEnabled())return;
        android.view.ViewGroup decor=(android.view.ViewGroup)getWindow().getDecorView();if(launchAnimation!=null&&launchAnimation.getParent()!=null)return;
        launchAnimation=new LaunchAnimationView(this);decor.addView(launchAnimation,new android.view.ViewGroup.LayoutParams(-1,-1));LaunchAnimationView current=launchAnimation;
        current.play(()->{if(current.getParent() instanceof android.view.ViewGroup)((android.view.ViewGroup)current.getParent()).removeView(current);if(launchAnimation==current)launchAnimation=null;});
    }
    @Override public void onBackPressed(){if(connectionGuideVisible&&api!=null){leaveConnectionGuide();return;}super.onBackPressed();}
    private String networkModeLabel(){String mode=getPreferences(MODE_PRIVATE).getString("networkMode","");return "lan".equals(mode)?"局域网直连  ›":"easytier".equals(mode)?"EasyTier 网络  ›":"连接方式  ›";}
    private void connectionModeDialog(){connectionModeDialog(status!=null&&status.isAttachedToWindow()?status:root);}
    private java.util.function.Consumer<Boolean> switchVpnPermission;
    private void connectionModeDialog(View anchor){
        if(api==null){connectionPage();return;}if(sending)return;
        final RemoteApi original=api;final int epoch=generation;
        try{
            JSONObject saved=storage.load(),profiles=saved.optJSONObject("addresses");if(profiles==null)profiles=new JSONObject();final JSONObject addresses=profiles;
            JSONObject advertised=currentState==null?null:currentState.optJSONObject("connectionAddresses");final JSONObject hints=advertised==null?new JSONObject():advertised;
            String previous=getPreferences(MODE_PRIVATE).getString("networkMode","lan");
            LinearLayout card=vertical();card.setPadding(dp(10),dp(8),dp(10),dp(8));card.setBackground(rounded(Color.rgb(40,40,40),22));
            TextView title=text("连接方式",12,muted);title.setPadding(dp(10),dp(4),dp(10),dp(6));card.addView(title);
            final PopupWindow[] menu={null};final boolean[] busy={false},startedEt={false};Runnable cleanup=()->{if(startedEt[0]&&api==original&&"lan".equals(previous)){EasyTierVpnService.disconnect(this);startedEt[0]=false;}};TextView feedback=text("",12,muted);feedback.setPadding(dp(10),dp(4),dp(10),dp(6));feedback.setVisibility(View.GONE);
            for(String mode:new String[]{"lan","easytier"}){
                boolean direct="lan".equals(mode);TextView row=text((direct?"局域网直连":"EasyTier 网络")+(previous.equals(mode)?"    ✓":""),15,foreground);row.setTextIsSelectable(false);row.setPadding(dp(12),dp(12),dp(12),dp(12));row.setBackground(rounded(previous.equals(mode)?Color.rgb(52,52,52):Color.TRANSPARENT,12));card.addView(row,new LinearLayout.LayoutParams(-1,dp(48)));
                row.setOnClickListener(v->{if(busy[0])return;
                    String address=addresses.optString(mode,hints.optString(direct?"lan":"primary",previous.equals(mode)?original.address:""));
                    if(address.isEmpty()){toast("请先在设置 → 连接方式中配置电脑地址");return;}
                    final RemoteApi candidate;try{candidate=new RemoteApi(MobileState.connectionAddress(address),original.fingerprint,original.token);}catch(Exception e){toast("请在设置中检查电脑地址");return;}
                    busy[0]=true;feedback.setVisibility(View.VISIBLE);feedback.setText("正在验证连接…");AnchoredMenu.resize(this,anchor,card,menu[0]);
                    Runnable verify=()->{
                        if(!direct&&!EasyTierVpnService.running){try{startedEt[0]=true;startForegroundService(new android.content.Intent(this,EasyTierVpnService.class));}catch(Exception e){busy[0]=false;feedback.setText("无法启动组网，请在设置中检查 EasyTier");return;}}
                        worker.execute(()->{try{
                            if(!direct){long until=android.os.SystemClock.uptimeMillis()+20000;while(!(EasyTierVpnService.running&&EasyTierVpnService.state.startsWith("虚拟接口"))&&android.os.SystemClock.uptimeMillis()<until)Thread.sleep(200);if(!(EasyTierVpnService.running&&EasyTierVpnService.state.startsWith("虚拟接口")))throw new Exception("EasyTier 未就绪，请在设置中检查组网");}
                            JSONObject verified=candidate.call("GET","/v1/state",null,null);
                            runOnUiThread(()->{if(isDestroyed()||api!=original||generation!=epoch||!menu[0].isShowing())return;try{
                                JSONObject connection=storage.load();if(connection==null)throw new Exception("配对信息已清除");addresses.put(previous,original.address).put(mode,candidate.address);connection.put("address",candidate.address).put("addresses",addresses);storage.save(connection);
                                if(direct)EasyTierVpnService.disconnect(this);original.cancel();api=candidate;generation++;getPreferences(MODE_PRIVATE).edit().putString("networkMode",mode).apply();verified.put("online",true);render(verified);
                                android.content.Intent intent=new android.content.Intent(this,SessionService.class).setAction("switch-address").putExtra("networkMode",mode);if(!selectedThreadId.isEmpty())intent.putExtra("threadId",selectedThreadId);startForegroundService(intent);menu[0].dismiss();
                            }catch(Exception e){busy[0]=false;feedback.setText("切换未确认，请重试");}});
                        }catch(Exception e){runOnUiThread(()->{cleanup.run();if(menu[0].isShowing()){busy[0]=false;feedback.setText("连接未确认，请在设置中检查网络和地址");}});}});
                    };
                    if(!direct&&!EasyTierVpnService.running){try{if(new SecureSession(this,"easytier").load()==null){busy[0]=false;feedback.setText("请先在设置 → EasyTier 组网中保存配置");return;}android.content.Intent permission=android.net.VpnService.prepare(this);if(permission!=null){switchVpnPermission=allowed->{if(allowed&&menu[0].isShowing())verify.run();else{busy[0]=false;feedback.setText("未授予 VPN 权限");}};startActivityForResult(permission,202);return;}}catch(Exception e){busy[0]=false;feedback.setText("无法读取组网配置，请在设置中检查");return;}}
                    verify.run();
                });
            }
            card.addView(feedback);menu[0]=AnchoredMenu.show(this,anchor,card,dp(240),false);menu[0].setOnDismissListener(()->{switchVpnPermission=null;cleanup.run();});
        }catch(Exception e){toast("无法读取连接方式");}
    }
    private void connectionSettingsDialog(View anchor) {
        if(api==null){connectionPage();return;}if(sending){toast("请等待当前提交完成");return;}
        try {
            RemoteApi original=api;int epoch=generation;JSONObject saved=storage.load();JSONObject addresses=saved==null?null:saved.optJSONObject("addresses");if(addresses==null)addresses=new JSONObject();final JSONObject profiles=addresses;
            JSONObject advertised=currentState==null?null:currentState.optJSONObject("connectionAddresses");final JSONObject hints=advertised==null?new JSONObject():advertised;
            final PopupWindow[] connectionMenu={null};final LinearLayout[] connectionCard={null};LinearLayout form=vertical();form.setPadding(dp(20),0,dp(20),dp(12));RadioGroup modes=new RadioGroup(this);RadioButton lan=new RadioButton(this),et=new RadioButton(this);lan.setId(View.generateViewId());et.setId(View.generateViewId());lan.setText("局域网直连 · 同一 Wi-Fi");et.setText("EasyTier · 异地组网");lan.setTextColor(foreground);et.setTextColor(foreground);modes.addView(lan);modes.addView(et);form.addView(modes);
            TextView hint=text("",13,muted);form.addView(hint);EditText address=field("电脑 IP:9443");address.setBackground(rounded(Color.rgb(46,46,46),14));address.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);form.addView(address);
            Button etSettings=button("配置 / 连接 EasyTier");form.addView(etSettings);etSettings.setOnClickListener(v->easyTierDialog());
            modes.setOnCheckedChangeListener((group,id)->{boolean direct=id==lan.getId();String key=direct?"lan":"easytier";address.setText(profiles.optString(key,hints.optString(direct?"lan":"primary",direct?"":original.address)));etSettings.setVisibility(direct?View.GONE:View.VISIBLE);hint.setText(direct?"填写电脑的局域网 IPv4。电脑插件需绑定此地址，或开启额外局域网直连。":"先连接 EasyTier，再填写电脑虚拟 IP。切换地址仍会校验同一电脑证书及授权。");if(connectionMenu[0]!=null)form.post(()->AnchoredMenu.resize(this,anchor,connectionCard[0],connectionMenu[0]));});
            modes.check("easytier".equals(getPreferences(MODE_PRIVATE).getString("networkMode",""))?et.getId():lan.getId());
            TextView feedback=text("验证成功后才会保存，不需要重新配对同一台电脑。",12,muted);form.addView(feedback);ScrollView body=new ScrollView(this);body.addView(form);
            LinearLayout card=vertical();card.setPadding(dp(8),dp(8),dp(8),dp(12));card.setBackground(rounded(Color.rgb(35,35,35),24));card.addView(text("连接方式",16,foreground));card.addView(body,new LinearLayout.LayoutParams(-1,-2));Button verify=button("验证并切换");card.addView(verify);PopupWindow dialog=AnchoredMenu.show(this,anchor,card,dp(350),true);connectionMenu[0]=dialog;connectionCard[0]=card;
            verify.setOnClickListener(v->{
                final String mode=modes.getCheckedRadioButtonId()==lan.getId()?"lan":"easytier";
                final RemoteApi candidate;try{candidate=new RemoteApi(MobileState.connectionAddress(address.getText().toString()),original.fingerprint,original.token);}catch(Exception e){feedback.setText(error(e));return;}
                verify.setEnabled(false);feedback.setText("正在验证电脑身份和连接…");
                worker.execute(()->{try{JSONObject verified=candidate.call("GET","/v1/state",null,null);runOnUiThread(()->{
                    if(isDestroyed()||api!=original||epoch!=generation||!dialog.isShowing())return;
                    try{JSONObject connection=storage.load();if(connection==null)throw new Exception("配对信息已清除");String previous=getPreferences(MODE_PRIVATE).getString("networkMode","");if(!previous.isEmpty())profiles.put(previous,original.address);profiles.put(mode,candidate.address);connection.put("address",candidate.address).put("addresses",profiles);storage.save(connection);
                        if("lan".equals(mode))EasyTierVpnService.disconnect(this);
                        original.cancel();api=candidate;generation++;getPreferences(MODE_PRIVATE).edit().putString("networkMode",mode).apply();verified.put("online",true);render(verified);
                        android.content.Intent intent=new android.content.Intent(this,SessionService.class).setAction("switch-address").putExtra("networkMode",mode);if(!selectedThreadId.isEmpty())intent.putExtra("threadId",selectedThreadId);startForegroundService(intent);dialog.dismiss();toast("连接方式已切换");
                    }catch(Exception e){feedback.setText(error(e));verify.setEnabled(true);}
                });}catch(Exception e){runOnUiThread(()->{if(dialog.isShowing()){feedback.setText("连接未确认："+error(e));verify.setEnabled(true);}});}});
            });
        }catch(Exception e){toast("无法读取连接配置："+error(e));}
    }
    private void chatPage() {
        connectionGuideVisible=false;
        currentThread = null;
        base("", ""); root.removeAllViews();
        LinearLayout header = new LinearLayout(this); header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        Button menu = icon("menu","会话列表",false); header.addView(menu,new LinearLayout.LayoutParams(dp(48),dp(48))); menu.setOnClickListener(v -> chooseChat());
        LinearLayout heading = vertical(); selectChat = new Button(this); selectChat.setText("Codex"); selectChat.setTextColor(foreground); selectChat.setTextSize(18); selectChat.setAllCaps(false); selectChat.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL); selectChat.setBackgroundColor(Color.TRANSPARENT); selectChat.setPadding(dp(4),0,0,0); selectChat.setSingleLine(true); selectChat.setEllipsize(android.text.TextUtils.TruncateAt.END);
        heading.addView(selectChat,new LinearLayout.LayoutParams(-1,dp(31))); selectChat.setOnClickListener(v -> chooseChat());
        status = text("正在连接电脑…",11,muted); status.setPadding(dp(4),0,0,0); status.setTextIsSelectable(false); status.setSingleLine(true); heading.addView(status);
        status.setContentDescription("切换连接方式");status.setOnClickListener(v->connectionModeDialog(v));
        header.addView(heading,new LinearLayout.LayoutParams(0,dp(56),1)); Button settings = icon("settings","设置",false); header.addView(settings,new LinearLayout.LayoutParams(dp(48),dp(48))); settings.setOnClickListener(v -> settingsPage()); root.addView(header);
        renderRevision++;deferredRows=null;displayedChat="";listScrolling=false;
        scroll=new TranscriptListView(this);scroll.onViewportSettled=this::updateLatestJump;scroll.setDivider(null);scroll.setDividerHeight(0);scroll.setCacheColorHint(Color.TRANSPARENT);scroll.setSelector(android.R.color.transparent);scroll.setClipToPadding(false);scroll.setPadding(0,dp(20),0,dp(20));scroll.setVerticalScrollBarEnabled(false);
        chatAdapter=new ChatAdapter();scroll.setAdapter(chatAdapter);
        LinearLayout historyArea=new LinearLayout(this);FrameLayout transcript=new FrameLayout(this);transcript.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        latestJump=text("↓ 回到最新",12,foreground);latestJump.setTextIsSelectable(false);latestJump.setGravity(android.view.Gravity.CENTER);latestJump.setPadding(dp(14),0,dp(14),0);latestJump.setBackground(rounded(Color.rgb(49,49,49),22));latestJump.setElevation(dp(4));latestJump.setOnClickListener(v->{if(chatAdapter.getCount()>0)scroll.setSelection(chatAdapter.getCount()-1);});FrameLayout.LayoutParams jumpParams=new FrameLayout.LayoutParams(-2,dp(40),android.view.Gravity.BOTTOM|android.view.Gravity.RIGHT);jumpParams.setMargins(0,0,dp(12),dp(10));transcript.addView(latestJump,jumpParams);latestJump.setVisibility(View.GONE);historyArea.addView(transcript,new LinearLayout.LayoutParams(0,-1,1));
        jumpRail=new ConversationRail(this,new ConversationRail.Listener(){public void preview(ConversationRail.Entry entry,int index,int count,float y){showRailPreview(entry,index,count,y);}public void commit(ConversationRail.Entry entry){jumpTo(entry.key);}public void end(){dismissRailPreview();}});
        jumpRail.setOnClickListener(v->jumpDialog());historyArea.addView(jumpRail,new LinearLayout.LayoutParams(dp(30),-1));railChat="";root.addView(historyArea,new LinearLayout.LayoutParams(-1,0,1));
        scroll.setOnScrollListener(new AbsListView.OnScrollListener(){public void onScroll(AbsListView v,int first,int count,int total){highlightJumpRail(first);updateLatestJump();}public void onScrollStateChanged(AbsListView v,int state){listScrolling=state!=SCROLL_STATE_IDLE;if(!listScrolling&&deferredRows!=null){renderHandler.removeCallbacks(flushRows);java.util.List<ChatRows.Row> rows=deferredRows;deferredRows=null;applyRows(rows);}}});
        LinearLayout dock = vertical(); dock.setPadding(dp(10),dp(4),dp(10),dp(8)); dock.setBackground(rounded(Color.rgb(38,38,38),24));
        composer = field("发送消息"); composer.setBackgroundColor(Color.TRANSPARENT); composer.setSingleLine(false); composer.setMaxLines(6); composer.setMinLines(1); composer.setPadding(dp(12),dp(12),dp(8),dp(12)); composer.setText(drafts.containsKey(selectedThreadId) ? drafts.get(selectedThreadId) : "");
        dock.addView(composer,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout controls=new LinearLayout(this);controls.setGravity(android.view.Gravity.CENTER_VERTICAL);
        modelChoice=powerText("选择模型  ⌄",12,muted);modelChoice.setTextIsSelectable(false);modelChoice.setSingleLine(true);modelChoice.setEllipsize(android.text.TextUtils.TruncateAt.END);modelChoice.setPadding(dp(10),dp(10),dp(4),dp(10));modelChoice.setContentDescription("选择模型与思考强度");modelChoice.setOnClickListener(v->modelDialog());controls.addView(modelChoice,new LinearLayout.LayoutParams(0,dp(48),1));
        send = icon("send","发送消息",true); controls.addView(send,new LinearLayout.LayoutParams(dp(48),dp(48))); dock.addView(controls);root.addView(dock);
        sendStatus = text("正在同步聊天状态…", 11, muted); sendStatus.setGravity(android.view.Gravity.CENTER); sendStatus.setTextIsSelectable(false); root.addView(sendStatus);
        sendStatus.setOnClickListener(view -> { toast("正在重新同步所选聊天"); beginPolling(true); });
        send.setOnClickListener(view -> {
            String action=MobileState.composerAction(currentState,currentThread,sending,composer.getText().toString());
            if("interrupt".equals(action)){try{command(new JSONObject().put("op","interrupt").put("turnId",MobileState.activeTurnId(currentThread)),false);}catch(Exception e){toast(error(e));}}
            else if(!"none".equals(action))submit(action);
        });
        composer.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int start,int before,int count){setButtons(currentThread!=null&&currentThread.optBoolean("ready"));}public void afterTextChanged(android.text.Editable e){}});
        setButtons(false);
        RemoteApi client = api;
        worker.execute(() -> {
            try {
                JSONObject cached = MobileState.offline(new HistoryCache(this, client.fingerprint).load());
                runOnUiThread(() -> { if (api != client || isDestroyed() || (currentState != null && currentState.optBoolean("online"))) return; try { render(cached); } catch (Exception ignored) {} });
            } catch (Exception error) { runOnUiThread(() -> { if (api == client) status.setText("本地缓存读取失败，将重新同步电脑历史"); }); }
        });
    }
    private void submit(String operation) {
        String blocked = MobileState.unavailableReason(currentState, currentThread, sending);
        if (!blocked.isEmpty()) { toast(blocked); return; }
        String value = composer.getText().toString();
        if (value.trim().isEmpty()) { toast("请先输入消息"); return; }
        try { command(new JSONObject().put("op", operation).put("text", value), true); } catch (Exception e) { toast(error(e)); }
    }
    private void command(JSONObject payload, boolean clearComposer){command(payload,clearComposer,null);}
    private void command(JSONObject payload, boolean clearComposer,Runnable confirmed){command(payload,clearComposer,confirmed,null);}
    private void command(JSONObject payload, boolean clearComposer,Runnable confirmed,Runnable failed) {
        if (currentThread == null || api == null || sending) return;
        try { if (!payload.has("threadId")) payload.put("threadId", currentThread.getString("id")); } catch (Exception e) { return; }
        RemoteApi client = api; int epoch = generation; String textBefore = composer.getText().toString();
        sending = true; setButtons(false); String id = UUID.randomUUID().toString();
        worker.execute(() -> {
            try {
                client.call("POST", "/v1/command", payload, id);
                runOnUiThread(() -> { if (epoch == generation && client == api) {
                    if (clearComposer && payload.optString("threadId").equals(selectedThreadId) && composer.getText().toString().equals(textBefore)) { composer.setText(""); drafts.remove(selectedThreadId); }
                    if("model".equals(payload.optString("op"))){JSONObject cached=modelCache.get(modelCacheKey(client,payload.optString("threadId")));if(cached!=null)try{cached.put("current",new JSONObject().put("model",payload.optString("model")).put("effort",payload.optString("effort")).put("serviceTier",payload.optString("serviceTier",cached.optJSONObject("current")==null?"default":cached.optJSONObject("current").optString("serviceTier","default"))));}catch(Exception ignored){}}
                    if(confirmed!=null)confirmed.run();if(confirmed==null||!"model".equals(payload.optString("op")))toast("电脑已接收");
                } });
            } catch (Exception e) { runOnUiThread(() -> { if (epoch == generation && client == api){if(failed!=null)failed.run();toast("提交未确认，请检查聊天状态后再操作：" + error(e));} }); }
            finally { runOnUiThread(() -> { if (epoch == generation && client == api) { sending = false; setButtons(currentThread != null && currentThread.optBoolean("ready")); } }); }
        });
    }
    private void setButtons(boolean ready) {
        if (send == null) return;
        boolean active = !MobileState.activeTurnId(currentThread).isEmpty();
        String reason = MobileState.unavailableReason(currentState, currentThread, sending);
        ready = ready && reason.isEmpty();
        String action=MobileState.composerAction(currentState,currentThread,sending,composer.getText().toString());
        boolean stop="interrupt".equals(action);send.setEnabled(ready && !"none".equals(action));send.setAlpha(send.isEnabled()?1f:.35f);
        UiIcon image=new UiIcon(stop?"stop":"send",background);image.setBounds(0,0,dp(22),dp(22));send.setCompoundDrawables(image,null,null,null);
        send.setContentDescription(sending?"正在提交":stop?"停止当前回复":"steer".equals(action)?"追加指令":"发送消息");
        if (sendStatus != null) sendStatus.setText(reason.isEmpty()
            ? active ? (stop?"正在回复 · 点击方形按钮停止":"发送会追加到当前任务") : "消息发送至电脑上的 Codex"
            : reason + " · 点此重新同步");
    }
    private void render(JSONObject state) throws Exception {
        if(connectionGuideVisible){currentState=state;return;}
        currentState = state;
        JSONArray threads = state.optJSONArray("threads"); if (threads == null) threads = new JSONArray();
        if (selectedThreadId.isEmpty()) selectedThreadId = state.optString("selectedThreadId");
        if (selectedThreadId.isEmpty() && threads.length() > 0) selectedThreadId = threads.getJSONObject(0).getString("id");
        currentThread = null;
        for (int i = 0; i < threads.length(); i++) if (threads.getJSONObject(i).getString("id").equals(selectedThreadId)) currentThread = threads.getJSONObject(i);
        if (currentThread == null) {
            status.setText(state.optBoolean("online") ? "正在加载所选聊天历史…" : "离线 · 暂无该聊天的本地记录");
            renderRevision++;renderHandler.removeCallbacks(prepareRows);deferredRows=null;chatAdapter.replace(java.util.Collections.emptyList());jumpRail.entries(java.util.Collections.emptyList());jumpRail.cancel();railChat="";setButtons(false);displayedChat="";return;
        }
        selectChat.setText(currentThread.optString("name", "Codex"));
        status.setText(!state.optBoolean("online") ? "离线 · 显示本地保存记录 · " + SessionService.message
            : currentThread.optBoolean("ready") ? (!MobileState.activeTurnId(currentThread).isEmpty() ? "● 正在处理" : "● 已连接电脑")
            : "已连接 · 历史可读，桌面聊天尚未就绪");
        if (SessionService.message.contains("保存失败")) status.append(" · " + SessionService.message);
        status.append(" · "+networkModeLabel());
        String selectedModel=currentThread.optString("model"),effort=currentThread.optString("reasoningEffort");((PowerLabel)modelChoice).power("ultra".equals(effort),"priority".equals(currentThread.optString("serviceTier")));modelChoice.setText(selectedModel.isEmpty()?"选择模型  ⌄":ModelPresentation.name(selectedModel)+(effort.isEmpty()?"":" · "+effortLabel(effort))+"  ⌄");
        setButtons(currentThread.optBoolean("ready"));loadModels(false);
        renderRevision++;if(!currentThread.optString("id").equals(displayedChat))deferredRows=null;if(!renderHandler.hasCallbacks(prepareRows))renderHandler.postDelayed(prepareRows,100);
    }
    private View toolCard(JSONObject tool,String key) {
        LinearLayout card=vertical();card.setPadding(dp(12),dp(4),dp(12),dp(8));card.setBackground(rounded(Color.rgb(29,29,29),12));
        LinearLayout header=new LinearLayout(this);header.setGravity(android.view.Gravity.CENTER_VERTICAL);header.setMinimumHeight(dp(46));
        ImageView arrow=new ImageView(this);arrow.setImageDrawable(new UiIcon("next",muted));header.addView(arrow,new LinearLayout.LayoutParams(dp(16),dp(16)));
        String state=tool.optString("status");String label="inProgress".equals(state)?"进行中":"failed".equals(state)?"失败":"completed".equals(state)?"已完成":"declined".equals(state)?"已拒绝":"";
        TextView title=text(tool.optString("title","commandExecution".equals(tool.optString("toolType"))?"执行命令":"fileChange".equals(tool.optString("toolType"))?"修改文件":"工具调用")+(label.isEmpty()?"":" · "+label),13,muted);title.setTextIsSelectable(false);title.setPadding(dp(10),dp(8),0,dp(8));header.addView(title,new LinearLayout.LayoutParams(0,-2,1));card.addView(header);
        LinearLayout details=vertical();
        boolean expanded=expandedTools.contains(key);if(expanded)fillToolDetails(details,tool);details.setVisibility(expanded?View.VISIBLE:View.GONE);arrow.setRotation(expanded?90:0);card.addView(details);
        header.setContentDescription((expanded?"收起工具：":"展开工具：")+title.getText());header.setOnClickListener(v->{boolean open=details.getVisibility()!=View.VISIBLE;if(open){expandedTools.add(key);if(details.getChildCount()==0)fillToolDetails(details,tool);}else expandedTools.remove(key);details.setVisibility(open?View.VISIBLE:View.GONE);arrow.setRotation(open?90:0);header.setContentDescription((open?"收起工具：":"展开工具：")+title.getText());});
        return card;
    }
    private void fillToolDetails(LinearLayout details,JSONObject tool){
        String input=tool.optString("input"),output=tool.optString("output");
        if(!input.isEmpty())toolText(details,"输入",input);
        if(!output.isEmpty())toolText(details,"输出",output);
        if(tool.has("exitCode"))details.addView(text("退出码 "+tool.optInt("exitCode"),12,muted));
        if(tool.optBoolean("truncated"))details.addView(text("内容较长，完整记录请在电脑查看。",12,muted));
        if(details.getChildCount()==0)details.addView(text("当前工具未提供更多详情。",12,muted));
    }
    private View toolGroup(ChatRows.Row row){
        LinearLayout card=vertical();card.setPadding(dp(8),dp(4),dp(8),dp(6));card.setBackground(rounded(Color.rgb(29,29,29),12));
        JSONArray tools=row.data.optJSONArray("items");int count=tools==null?0:tools.length();int running=0,failed=0;
        for(int i=0;i<count;i++){String state=tools.optJSONObject(i).optString("status");if("inProgress".equals(state))running++;if("failed".equals(state))failed++;}
        String label="工具调用 · "+count+" 项"+(running>0?" · "+running+" 项进行中":failed>0?" · "+failed+" 项失败":"");
        TextView header=text("",13,muted);header.setTextIsSelectable(false);header.setPadding(dp(12),dp(12),dp(12),dp(12));header.setMinHeight(dp(48));card.addView(header);
        LinearLayout details=vertical();card.addView(details);
        Runnable fill=()->{if(details.getChildCount()>0||tools==null)return;for(int i=0;i<tools.length();i++){JSONObject tool=tools.optJSONObject(i);details.addView(toolCard(tool,row.key+":"+tool.optString("id","tool-"+i)));}};
        boolean open=expandedTools.contains(row.key);if(open)fill.run();details.setVisibility(open?View.VISIBLE:View.GONE);header.setText((open?"⌄  ":"›  ")+label);header.setContentDescription((open?"收起工具调用":"展开工具调用")+"，共 "+count+" 项");
        header.setOnClickListener(v->{boolean expand=details.getVisibility()!=View.VISIBLE;if(expand){expandedTools.add(row.key);fill.run();}else expandedTools.remove(row.key);details.setVisibility(expand?View.VISIBLE:View.GONE);header.setText((expand?"⌄  ":"›  ")+label);header.setContentDescription((expand?"收起工具调用":"展开工具调用")+"，共 "+count+" 项");});
        return card;
    }
    private void applyRows(java.util.List<ChatRows.Row> rows){
        if(chatAdapter==null||currentThread==null)return;
        String chat=currentThread.optString("id");boolean switched=!chat.equals(displayedChat);
        if(!switched&&chatAdapter.matches(rows))return;
        int first=scroll.getFirstVisiblePosition();View firstView=scroll.getChildAt(0);int top=firstView==null?scroll.getPaddingTop():firstView.getTop();
        String anchor=first<chatAdapter.getCount()?chatAdapter.getItem(first).key:"";
        View last=scroll.getChildAt(scroll.getChildCount()-1);
        boolean bottom=switched||scroll.followingResize()||chatAdapter.getCount()==0||(scroll.getLastVisiblePosition()>=chatAdapter.getCount()-1&&(last==null||last.getBottom()<=scroll.getHeight()-scroll.getPaddingBottom()+dp(80)));
        displayedChat=chat;chatAdapter.replace(rows);updateJumpRail();
        if(bottom){if(!rows.isEmpty())scroll.setSelection(rows.size()-1);}
        else{int position=0;for(int i=0;i<rows.size();i++)if(rows.get(i).key.equals(anchor)){position=i;break;}scroll.setSelectionFromTop(position,top-scroll.getPaddingTop());}
    }
    private void updateLatestJump(){
        if(latestJump==null||scroll==null||chatAdapter==null)return;View last=scroll.getChildAt(scroll.getChildCount()-1);
        boolean bottom=scroll.followingResize()||chatAdapter.getCount()==0||(scroll.getLastVisiblePosition()>=chatAdapter.getCount()-1&&(last==null||last.getBottom()<=scroll.getHeight()-scroll.getPaddingBottom()+dp(48)));
        latestJump.setVisibility(bottom?View.GONE:View.VISIBLE);
    }
    private void jumpTo(String key){
        dismissRailPreview();
        String lastUser="";for(int i=chatAdapter.getCount()-1;i>=0;i--)if("user".equals(chatAdapter.getItem(i).type)){lastUser=chatAdapter.getItem(i).key;break;}
        if(key.equals(lastUser)){scroll.setSelection(Math.max(0,chatAdapter.getCount()-1));return;}
        for(int i=0;i<chatAdapter.getCount();i++)if(chatAdapter.getItem(i).key.equals(key)){scroll.setSelectionFromTop(i,0);return;}
        toast("这条记录已更新，请重新选择");
    }
    private void updateJumpRail(){
        if(!displayedChat.equals(railChat)){jumpRail.cancel();dismissModelPopup();railChat=displayedChat;}
        java.util.List<ConversationRail.Entry> entries=new java.util.ArrayList<>();
        for(int i=0;i<chatAdapter.getCount();i++){
            ChatRows.Row row=chatAdapter.getItem(i);if(!"user".equals(row.type))continue;
            String reply="";for(int j=i+1;j<chatAdapter.getCount();j++){ChatRows.Row next=chatAdapter.getItem(j);if("user".equals(next.type))break;if("message".equals(next.type)){reply=next.data.optString("text");break;}}
            entries.add(new ConversationRail.Entry(row.key,ChatRows.displayText(row.data.optString("text")),reply,i));
        }
        jumpRail.entries(java.util.Collections.unmodifiableList(entries));highlightJumpRail(scroll.getFirstVisiblePosition());
    }
    private void highlightJumpRail(int first){if(jumpRail!=null){View child=scroll.getChildAt(0);float fraction=child==null||child.getHeight()==0?0:Math.max(0,Math.min(1,(scroll.getPaddingTop()-child.getTop())/(float)child.getHeight()));jumpRail.activePosition(first+fraction);}}
    private void dismissRailPreview(){if(railPreview!=null){railPreview.dismiss();railPreview=null;railPreviewContent="";}}
    private void showRailPreview(ConversationRail.Entry entry,int index,int count,float y){
        if(railPreview==null){
            LinearLayout preview=vertical();preview.setPadding(dp(14),dp(12),dp(14),dp(12));preview.setBackground(rounded(Color.rgb(43,43,43),16));
            railPreviewTitle=text("",12,muted);railPreviewTitle.setTextIsSelectable(false);preview.addView(railPreviewTitle);
            railPreviewPrompt=text("",14,foreground);railPreviewPrompt.setMaxLines(3);railPreviewPrompt.setEllipsize(android.text.TextUtils.TruncateAt.END);railPreviewPrompt.setTextIsSelectable(false);preview.addView(railPreviewPrompt);
            railPreviewReply=text("",12,muted);railPreviewReply.setMaxLines(3);railPreviewReply.setEllipsize(android.text.TextUtils.TruncateAt.END);railPreviewReply.setTextIsSelectable(false);preview.addView(railPreviewReply);
            railPreview=new PopupWindow(preview,Math.min(dp(330),getResources().getDisplayMetrics().widthPixels-dp(64)),-2,false);railPreview.setTouchable(false);railPreview.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);railPreview.setBackgroundDrawable(rounded(Color.rgb(43,43,43),16));railPreview.setElevation(dp(8));
        }
        View panel=railPreview.getContentView();String content=entry.key+":"+index+":"+count+":"+entry.prompt+":"+entry.response;
        if(!content.equals(railPreviewContent)){
            railPreviewContent=content;railPreviewTitle.setText((index+1)+" / "+count+" 条提问 · 松手定位 · 左滑取消快速定位");
            railPreviewPrompt.setText(entry.prompt.substring(0,Math.min(500,entry.prompt.length())));
            railPreviewReply.setText(entry.response.substring(0,Math.min(500,entry.response.length())));railPreviewReply.setVisibility(entry.response.isEmpty()?View.GONE:View.VISIBLE);
            panel.measure(View.MeasureSpec.makeMeasureSpec(railPreview.getWidth(),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(getResources().getDisplayMetrics().heightPixels,View.MeasureSpec.AT_MOST));
        }
        int[] railLocation=new int[2];jumpRail.getLocationInWindow(railLocation);int height=panel.getMeasuredHeight();
        int left=Math.max(dp(8),railLocation[0]-railPreview.getWidth()-dp(8));int top=Math.max(railLocation[1],Math.min(railLocation[1]+jumpRail.getHeight()-height,(int)(railLocation[1]+y-height/2f)));
        if(!railPreview.isShowing()){railPreview.setAnimationStyle(0);railPreview.showAtLocation(root,android.view.Gravity.TOP|android.view.Gravity.LEFT,left,top);panel.setAlpha(0);panel.animate().alpha(1).setDuration(100).start();}else railPreview.update(left,top,railPreview.getWidth(),height);
    }
    private void jumpDialog(){
        java.util.List<ChatRows.Row> users=new java.util.ArrayList<>();java.util.List<String> labels=new java.util.ArrayList<>();
        for(int i=0;i<chatAdapter.getCount();i++){ChatRows.Row row=chatAdapter.getItem(i);if("user".equals(row.type)){users.add(row);labels.add(users.size()+". "+ChatRows.navigationText(row.data));}}
        android.app.Dialog drawer=new android.app.Dialog(this);LinearLayout panel=vertical();panel.setPadding(dp(14),dp(12),dp(14),dp(12));panel.setBackgroundColor(background);panel.addView(toolbar("快捷定位 · 已加载记录",drawer::dismiss));
        ListView list=new ListView(this);list.setDividerHeight(0);list.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,labels){public View getView(int position,View reused,android.view.ViewGroup parent){TextView view=(TextView)super.getView(position,reused,parent);view.setTextColor(foreground);view.setTextSize(14);view.setMaxLines(2);view.setEllipsize(android.text.TextUtils.TruncateAt.END);view.setPadding(dp(12),dp(14),dp(12),dp(14));return view;}});panel.addView(list,new LinearLayout.LayoutParams(-1,0,1));list.setOnItemClickListener((parent,view,position,id)->{jumpTo(users.get(position).key);drawer.dismiss();});drawer.setContentView(panel);android.view.Window window=drawer.getWindow();if(window!=null){window.setBackgroundDrawableResource(android.R.color.transparent);window.setGravity(android.view.Gravity.RIGHT);window.setLayout((int)(getResources().getDisplayMetrics().widthPixels*.86),-1);}drawer.show();int active=0;for(int i=0;i<users.size();i++){for(int j=0;j<=scroll.getFirstVisiblePosition()&&j<chatAdapter.getCount();j++)if(chatAdapter.getItem(j).key.equals(users.get(i).key))active=i;}list.setSelection(active);if(window!=null)window.setLayout((int)(getResources().getDisplayMetrics().widthPixels*.86),-1);
    }
    private String modelCacheKey(RemoteApi client,String threadId){return client.fingerprint+":"+threadId;}
    private void loadModels(boolean open){
        if(api==null||currentThread==null||currentState==null||!currentState.optBoolean("online")||!currentThread.optBoolean("ready")){if(open)toast("连接电脑并等待聊天就绪后选择模型");return;}
        RemoteApi client=api;String threadId=currentThread.optString("id"),key=modelCacheKey(client,threadId);int epoch=generation;
        JSONObject cached=modelCache.get(key);if(cached!=null&&!currentThread.optString("model").isEmpty())try{cached.put("current",new JSONObject().put("model",currentThread.optString("model")).put("effort",currentThread.optString("reasoningEffort")).put("serviceTier",currentThread.optString("serviceTier","default")));}catch(Exception ignored){}
        if(open&&cached!=null){modelOpenWanted="";modelChoice.setAlpha(1);showModelMenu(cached.optJSONArray("models"),cached.optJSONObject("current"),threadId,client,epoch);open=false;}
        if(open){modelOpenWanted=key;modelChoice.setAlpha(.6f);}
        ModelLoad prior=modelLoading.get(key);if(prior!=null&&prior.client==client&&prior.epoch==epoch)return;
        long age=android.os.SystemClock.uptimeMillis()-modelCacheAt.getOrDefault(key,0L);
        if(!open&&age<(cached==null?15000:60000))return;
        ModelLoad request=new ModelLoad(client,epoch);modelLoading.put(key,request);
        worker.execute(()->{try{
            JSONObject result=client.call("GET","/v1/models?threadId="+java.net.URLEncoder.encode(threadId,"UTF-8"),null,null);
            runOnUiThread(()->{
                if(modelLoading.get(key)==request)modelLoading.remove(key);if(isDestroyed()||client!=api||epoch!=generation)return;
                JSONArray models=result.optJSONArray("models");if(models==null||models.length()==0){if(key.equals(modelOpenWanted)){modelOpenWanted="";modelChoice.setAlpha(1);toast("电脑未提供可选模型");}return;}
                if(modelCache.size()>=24&&!modelCache.containsKey(key)){String oldest=modelCache.keySet().iterator().next();modelCache.remove(oldest);modelCacheAt.remove(oldest);}
                modelCache.put(key,result);modelCacheAt.put(key,android.os.SystemClock.uptimeMillis());
                if(key.equals(modelOpenWanted)){modelOpenWanted="";modelChoice.setAlpha(1);if(threadId.equals(selectedThreadId))showModelMenu(models,result.optJSONObject("current"),threadId,client,epoch);}
            });
        }catch(Exception e){runOnUiThread(()->{if(modelLoading.get(key)==request)modelLoading.remove(key);if(isDestroyed()||client!=api||epoch!=generation)return;modelCacheAt.put(key,android.os.SystemClock.uptimeMillis());if(key.equals(modelOpenWanted)){modelOpenWanted="";modelChoice.setAlpha(1);toast("读取模型失败："+error(e));}});}});
    }
    private void modelDialog(){if(!sending)loadModels(true);}
    private String effortLabel(String value){return ModelPresentation.effort(value);}
    private PowerLabel powerText(String value,int size,int color){PowerLabel label=new PowerLabel(this);label.setText(value);label.setTextSize(size);label.setTextColor(color);label.setPadding(0,dp(7),0,dp(7));return label;}
    private boolean modelContextValid(String threadId,RemoteApi client,int epoch){return !isDestroyed()&&client==api&&epoch==generation&&threadId.equals(selectedThreadId)&&currentState.optBoolean("online")&&currentThread!=null&&currentThread.optBoolean("ready");}
    private void applyModel(JSONObject model,String effort,String threadId,RemoteApi client,int epoch){
        if(!modelContextValid(threadId,client,epoch)){toast("聊天或连接已变化，请重新打开模型设置");return;}if(sending){toast("正在提交，请稍候");return;}
        try{command(new JSONObject().put("op","model").put("model",model.getString("model")).put("effort",effort),false);}catch(Exception e){toast(error(e));}
    }
    private void dismissModelPopup(){if(modelPopup!=null){modelPopup.dismiss();modelPopup=null;}}
    private void showModelMenu(JSONArray models,JSONObject supplied,String threadId,RemoteApi client,int epoch){
        JSONObject selected=models.optJSONObject(0);for(int i=0;i<models.length();i++){JSONObject candidate=models.optJSONObject(i);if(supplied!=null&&candidate.optString("model").equals(supplied.optString("model")))selected=candidate;}
        final JSONObject model=selected;final JSONObject current=supplied==null?new JSONObject():supplied;
        JSONArray available=model.optJSONArray("efforts");if(available==null||available.length()==0){toast("此模型未提供思考强度");return;}
        final JSONArray efforts=ModelPresentation.sliderEfforts(available,current.optString("effort"));
        String effort=current.optString("effort",model.optString("defaultEffort"));int initial=0;for(int i=0;i<efforts.length();i++)if(efforts.optString(i).equals(effort))initial=i;
        dismissModelPopup();LinearLayout panel=vertical();panel.setPadding(dp(12),dp(4),dp(12),dp(6));GradientDrawable background=rounded(Color.rgb(42,42,42),22);background.setStroke(dp(1),Color.rgb(65,65,65));panel.setBackground(background);
        LinearLayout heading=new LinearLayout(this);heading.setGravity(android.view.Gravity.CENTER_VERTICAL);
        JSONObject fastOption=null;JSONArray tiers=model.optJSONArray("serviceTiers");if(tiers!=null)for(int i=0;i<tiers.length();i++)if("priority".equals(tiers.optJSONObject(i).optString("id")))fastOption=tiers.optJSONObject(i);final JSONObject fastTier=fastOption;
        final boolean[] fast={"priority".equals(current.optString("serviceTier"))},pending={false};final int[] confirmed={initial};
        ImageView lightning=new ImageView(this);lightning.setPadding(dp(8),dp(8),dp(8),dp(8));heading.addView(lightning,new LinearLayout.LayoutParams(dp(34),dp(34)));lightning.setEnabled(fastTier!=null);
        TextView strength=text(effortLabel(efforts.optString(initial)),16,Color.rgb(65,137,251));strength.setGravity(android.view.Gravity.CENTER);strength.setTextIsSelectable(false);strength.setPadding(0,0,0,0);heading.addView(strength,new LinearLayout.LayoutParams(0,dp(34),1));
        ImageView reset=new ImageView(this);reset.setImageDrawable(new UiIcon("reset",muted));reset.setPadding(dp(8),dp(8),dp(8),dp(8));reset.setContentDescription("恢复默认推理强度");heading.addView(reset,new LinearLayout.LayoutParams(dp(34),dp(34)));panel.addView(heading);
        TextView modelName=text(ModelPresentation.name(model.optString("displayName",model.optString("model")))+" ›",13,Color.rgb(191,191,191));modelName.setGravity(android.view.Gravity.CENTER);modelName.setPadding(0,0,0,0);modelName.setTextIsSelectable(false);modelName.setSingleLine(true);modelName.setEllipsize(android.text.TextUtils.TruncateAt.END);modelName.setContentDescription("选择模型");panel.addView(modelName,new LinearLayout.LayoutParams(-1,dp(22)));
        final EffortSlider[] sliderView={null};
        Runnable style=()->{
            boolean ultra="ultra".equals(efforts.optString(sliderView[0]==null?confirmed[0]:sliderView[0].getProgress()));
            strength.setTextColor(ultra?Color.rgb(176,121,255):Color.rgb(65,137,251));
            lightning.setImageDrawable(new UiIcon(fast[0]?"bolt-filled":"bolt",fast[0]?(ultra?Color.rgb(174,116,255):Color.rgb(85,155,255)):muted));lightning.setBackground(rounded(fast[0]?Color.rgb(59,59,59):Color.TRANSPARENT,10));lightning.setAlpha(fastTier==null?.35f:pending[0]?.55f:1f);lightning.setContentDescription(fastTier==null?"此模型未提供快速模式":fast[0]?"快速模式已开启，点按切换标准模式，用量更多":"标准模式，点按启用快速模式，用量更多");
            if(sliderView[0]!=null){sliderView[0].ultra(ultra);sliderView[0].fast(fast[0]);}
        };
        java.util.function.BiConsumer<Integer,String> submit=(index,tier)->{
            if(pending[0]||sending||!modelContextValid(threadId,client,epoch))return;
            try{JSONObject payload=new JSONObject().put("op","model").put("threadId",threadId).put("model",model.getString("model")).put("effort",efforts.optString(index));if(tier!=null)payload.put("serviceTier",tier);
                pending[0]=true;sliderView[0].setEnabled(false);lightning.setEnabled(false);reset.setEnabled(false);modelName.setEnabled(false);style.run();
                Runnable enable=()->{pending[0]=false;sliderView[0].setEnabled(efforts.length()>1);lightning.setEnabled(fastTier!=null);reset.setEnabled(true);modelName.setEnabled(true);style.run();};
                command(payload,false,()->{confirmed[0]=index;if(tier!=null)fast[0]="priority".equals(tier);try{current.put("model",model.getString("model")).put("effort",efforts.optString(index)).put("serviceTier",fast[0]?"priority":"default");}catch(Exception ignored){}enable.run();},()->{sliderView[0].setProgress(confirmed[0]);strength.setText(effortLabel(efforts.optString(confirmed[0])));enable.run();});
            }catch(Exception e){toast(error(e));}
        };
        EffortSlider slider=new EffortSlider(this,efforts.length(),initial,new EffortSlider.Listener(){
            public void preview(int index){strength.setText(effortLabel(efforts.optString(index)));style.run();}
            public void commit(int index){if(index!=confirmed[0])submit.accept(index,null);}
        });sliderView[0]=slider;panel.addView(slider,new LinearLayout.LayoutParams(-1,dp(46)));style.run();
        lightning.setOnClickListener(v->{if(fastTier!=null)submit.accept(confirmed[0],fast[0]?"default":"priority");});
        reset.setOnClickListener(v->{String defaultEffort=model.optString("defaultEffort");for(int i=0;i<efforts.length();i++)if(efforts.optString(i).equals(defaultEffort)){slider.setProgress(i);if(i!=confirmed[0])submit.accept(i,null);return;}});
        modelName.setOnClickListener(v->{dismissModelPopup();showModelPicker(models,current,threadId,client,epoch);});
        modelPopup=AnchoredMenu.show(this,modelChoice,panel,dp(320),false);
    }
    private void showModelPicker(JSONArray models,JSONObject current,String threadId,RemoteApi client,int epoch){
        final PopupWindow[] menu={null};LinearLayout panel=vertical();panel.setPadding(dp(14),dp(10),dp(14),dp(12));panel.setBackground(rounded(Color.rgb(42,42,42),22));TextView title=text("选择模型",13,muted);title.setTextIsSelectable(false);panel.addView(title);
        TextView group=text("默认",15,foreground);group.setTextIsSelectable(false);group.setPadding(0,dp(6),0,0);panel.addView(group);TextView subtitle=text("推荐模型集",13,muted);subtitle.setTextIsSelectable(false);subtitle.setPadding(0,dp(2),0,dp(8));panel.addView(subtitle);
        ScrollView options=new ScrollView(this);LinearLayout rows=vertical();options.addView(rows);
        for(int i=0;i<models.length();i++){
            JSONObject model=models.optJSONObject(i);boolean selected=current!=null&&model.optString("model").equals(current.optString("model"));LinearLayout row=new LinearLayout(this);row.setGravity(android.view.Gravity.CENTER_VERTICAL);row.setPadding(0,0,0,0);
            TextView name=text(ModelPresentation.name(model.optString("displayName",model.optString("model"))),15,foreground);name.setTextIsSelectable(false);row.addView(name,new LinearLayout.LayoutParams(0,dp(36),1));TextView check=text(selected?"✓":"",17,foreground);check.setTextIsSelectable(false);check.setGravity(android.view.Gravity.CENTER);row.addView(check,new LinearLayout.LayoutParams(dp(24),dp(36)));row.setContentDescription("选择模型 "+name.getText()+(selected?"，当前模型":""));
            row.setOnClickListener(v->{menu[0].dismiss();if(selected)showModelMenu(models,current,threadId,client,epoch);else applyModel(model,model.optString("defaultEffort"),threadId,client,epoch);});rows.addView(row);
        }
        panel.addView(options,new LinearLayout.LayoutParams(-1,Math.min(dp(models.length()*36),(int)(getResources().getDisplayMetrics().heightPixels*.55))));menu[0]=AnchoredMenu.show(this,modelChoice,panel,dp(320),false);modelPopup=menu[0];
    }
    private View questionCard(ChatRows.Row row){
        JSONObject request=row.data;String threadId=currentThread.optString("id");RemoteApi client=api;int epoch=generation;
        LinearLayout card=vertical();card.setPadding(dp(16),dp(14),dp(16),dp(14));card.setBackground(rounded(Color.rgb(35,35,35),18));card.addView(text("需要你的回复",13,muted));
        JSONArray questions=request.optJSONArray("questions");java.util.Map<String,java.util.function.Supplier<String>> answers=new java.util.LinkedHashMap<>();
        if(questions!=null)for(int i=0;i<questions.length();i++){
            JSONObject q=questions.optJSONObject(i);String id=q.optString("id"),draftKey=threadId+":"+row.key+":"+id;card.addView(text((questions.length()>1?(i+1)+" / "+questions.length()+"  ":"")+q.optString("title"),16,foreground));
            RadioGroup choices=new RadioGroup(this);JSONArray options=q.optJSONArray("options");EditText custom=new EditText(this);custom.setTextColor(foreground);custom.setHintTextColor(muted);custom.setTextSize(15);custom.setHint(options!=null&&options.length()>0?"其他，请填写":"输入回答");custom.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);if(q.optBoolean("isSecret"))custom.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
            java.util.Map<Integer,String> values=new java.util.HashMap<>();String saved=q.optBoolean("isSecret")?"":questionDrafts.getOrDefault(draftKey,"");int selected=-1;
            if(options!=null)for(int j=0;j<options.length();j++){JSONObject option=options.optJSONObject(j);RadioButton choice=new RadioButton(this);int viewId=View.generateViewId();choice.setId(viewId);String label=option.optString("label");choice.setText(label+(option.optString("description").isEmpty()?"":"\n"+option.optString("description")));choice.setTextColor(foreground);choice.setTextSize(14);choice.setPadding(0,dp(8),0,dp(8));values.put(viewId,label);choices.addView(choice);if(label.equals(saved)||(j==0&&saved.isEmpty()))selected=viewId;}
            choices.check(selected);card.addView(choices);boolean free=values.isEmpty()||q.optBoolean("isOther");if(free){if(selected<0)custom.setText(saved);card.addView(custom);}
            choices.setOnCheckedChangeListener((g,checked)->{if(checked!=-1){custom.setText("");if(!q.optBoolean("isSecret"))questionDrafts.put(draftKey,values.get(checked));}});
            custom.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence t,int a,int c,int f){}public void onTextChanged(CharSequence t,int a,int before,int count){if(t.length()>0){choices.clearCheck();if(!q.optBoolean("isSecret"))questionDrafts.put(draftKey,t.toString());}}public void afterTextChanged(android.text.Editable e){}});
            answers.put(id,()->choices.getCheckedRadioButtonId()!=-1?values.get(choices.getCheckedRadioButtonId()):custom.getText().toString());
        }
        Button submit=button(request.optBoolean("submitting")?"已提交 · 等待电脑确认":"提交回答");submit.setEnabled(!request.optBoolean("submitting"));submit.setOnClickListener(v->{if(sending){toast("正在提交，请稍候");return;}if(!threadId.equals(selectedThreadId)||client!=api||epoch!=generation){toast("聊天已切换，请重新打开问题");return;}try{JSONObject response=new JSONObject();for(java.util.Map.Entry<String,java.util.function.Supplier<String>> entry:answers.entrySet()){String value=entry.getValue().get();if(value==null||value.trim().isEmpty()){toast("请回答全部问题");return;}response.put(entry.getKey(),new JSONObject().put("answers",new JSONArray().put(value)));}submit.setEnabled(false);command(new JSONObject().put("op","answer").put("requestId",request.get("id")).put("turnId",request.get("turnId")).put("answers",response),false);}catch(Exception e){submit.setEnabled(true);toast(error(e));}});card.addView(submit);return card;
    }
    private final class ChatAdapter extends BaseAdapter {
        private java.util.List<ChatRows.Row> rows=java.util.Collections.emptyList();
        private final java.util.Map<String,Long> ids=new java.util.HashMap<>();private long nextId;
        boolean matches(java.util.List<ChatRows.Row> value){if(value.size()!=rows.size())return false;for(int i=0;i<value.size();i++)if(!value.get(i).key.equals(rows.get(i).key)||!value.get(i).signature.equals(rows.get(i).signature))return false;return true;}
        void replace(java.util.List<ChatRows.Row> value){rows=value;notifyDataSetChanged();}
        public int getCount(){return rows.size();}public ChatRows.Row getItem(int position){return rows.get(position);}
        public long getItemId(int position){String key=getItem(position).key;Long id=ids.get(key);if(id==null){id=++nextId;ids.put(key,id);}return id;}
        public boolean hasStableIds(){return true;}public boolean areAllItemsEnabled(){return false;}public boolean isEnabled(int position){return false;}
        public View getView(int position,View recycled,android.view.ViewGroup parent){
            ChatRows.Row row=getItem(position);LinearLayout wrapper=recycled instanceof LinearLayout?(LinearLayout)recycled:vertical();
            String bound=row.key+":"+row.signature+":"+expandedTools.contains(row.key);
            if(bound.equals(wrapper.getTag()))return wrapper;
            wrapper.removeAllViews();wrapper.setTag(bound);wrapper.setPadding(0,0,0,dp(6));
            if("tools".equals(row.type))wrapper.addView(toolGroup(row));
            else if("user".equals(row.type)||"message".equals(row.type))wrapper.addView(bubble("user".equals(row.type)?ChatRows.userText(row.data.optString("text")):ChatRows.displayText(row.data.optString("text")),"user".equals(row.type)));
            else if("activity".equals(row.type)){
                LinearLayout activity=vertical();TextView label=text(row.data.optString("label","正在思考…"),13,muted);activity.addView(label);String summary=row.data.optString("summary");if(!summary.isEmpty())activity.addView(text(summary,13,muted));wrapper.addView(activity);
            }
            else if("question".equals(row.type))wrapper.addView(questionCard(row));
            else if("approval".equals(row.type)){
                JSONObject approval=row.data;String threadId=currentThread.optString("id");
                Button reviewButton=button(approval.optBoolean("submitting")?"审批已提交 · 等待确认":"查看并处理"+("file".equals(approval.optString("kind"))?"文件审批":"命令审批"));reviewButton.setEnabled(!approval.optBoolean("submitting"));reviewButton.setOnClickListener(v->review(approval,threadId));wrapper.addView(reviewButton);
            }else {TextView welcome=text("一起把想法变成现实\n发送任务，继续电脑上的工作。",20,foreground);welcome.setPadding(dp(8),dp(90),dp(8),dp(32));wrapper.addView(welcome);}
            return wrapper;
        }
    }
    private void toolText(LinearLayout parent,String label,String value){parent.addView(text(label,11,muted));TextView body=text(value,12,foreground);body.setTypeface(Typeface.MONOSPACE);body.setHorizontallyScrolling(true);HorizontalScrollView horizontal=new HorizontalScrollView(this);horizontal.addView(body);parent.addView(horizontal);}
    private View bubble(String content, boolean user) {
        LinearLayout card = vertical(); card.setPadding(dp(14),dp(8),dp(14),dp(10));
        card.setBackground(rounded(user?Color.rgb(43,43,43):Color.rgb(27,27,27),20));
        if(user) card.addView(text(content,16,foreground)); else MessageBody.add(this, card, content, foreground, muted);
        LinearLayout wrapper=vertical();LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(user ? -2 : -1, -2); params.gravity = user ? android.view.Gravity.END : android.view.Gravity.START; params.setMargins(user ? dp(32) : 0,dp(10),user?0:dp(12),dp(10));wrapper.addView(card,params);return wrapper;
    }
    private void review(JSONObject approval, String threadId) {
        try {
            String details = "命令：\n" + approval.optString("command", "") + "\n\n目录：" + approval.optString("cwd", "") + "\n\n原因：" + approval.optString("reason", "");
            if ("file".equals(approval.optString("kind"))) details = "文件变更：\n" + approval.getJSONArray("changes").toString(2);
            TextView content = text(details, 14, foreground); content.setTypeface(Typeface.MONOSPACE); content.setPadding(dp(20), dp(10), dp(20), dp(10));
            ScrollView detailsScroll = new ScrollView(this); detailsScroll.addView(content);
            AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle("桌面请求审批").setView(detailsScroll).setNeutralButton("稍后处理", null);
            JSONArray decisions = approval.getJSONArray("decisions");
            boolean hasDecline = decisions.toString().contains("\"decline\"");
            for (int i = 0; i < decisions.length(); i++) {
                String decision = decisions.getString(i);
                android.content.DialogInterface.OnClickListener action = (d, w) -> { try { command(new JSONObject().put("op", "approve").put("threadId", threadId).put("turnId", approval.optString("turnId")).put("requestId", approval.get("id")).put("decision", decision), false); } catch (Exception e) { toast(error(e)); } };
                if ("accept".equals(decision)) dialog.setPositiveButton("允许一次", action);
                else if ("cancel".equals(decision)) { if (hasDecline) dialog.setNeutralButton("取消任务", action); else dialog.setNegativeButton("取消任务", action); }
                else if ("decline".equals(decision)) dialog.setNegativeButton("拒绝", action);
            }
            dialog.show();
        } catch (Exception e) { toast(error(e)); }
    }
    private void easyTierDialog() {
        try {
            SecureSession settings = new SecureSession(this, "easytier"); JSONObject saved = settings.load();
            if (saved == null) saved = new JSONObject();
            android.app.Dialog dialog = new android.app.Dialog(this); LinearLayout page = panel(dialog, "EasyTier 组网");
            page.setFocusableInTouchMode(true); page.requestFocus();
            dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            ScrollView settingsScroll = new ScrollView(this); settingsScroll.setClipToPadding(false); settingsScroll.setPadding(0, 0, 0, dp(12));
            LinearLayout form = vertical(); settingsScroll.addView(form); page.addView(settingsScroll, new LinearLayout.LayoutParams(-1, 0, 1));
            LinearLayout statusCard = easyTierCard(form, "连接状态");
            TextView networkStatus = text(EasyTierVpnService.state, 16, accent); statusCard.addView(networkStatus);
            statusCard.addView(text("通过虚拟网络连接电脑，首次启动需允许 Android VPN。", 13, muted));
            LinearLayout identityCard = easyTierCard(form, "网络身份");
            identityCard.addView(text("与电脑填写相同的网络名称和密钥。密钥加密保存在本机。", 12, muted));
            EditText name = field("网络名称"), secret = field("网络密钥"), cidr = field("手机虚拟 IPv4/前缀"), peers = field("节点地址，每行一个，例如 tcp://节点IP:11010");
            name.setText(saved.optString("name")); secret.setText(saved.optString("secret")); cidr.setText(saved.optString(saved.optBoolean("dhcp")?"networkCidr":"cidr")); peers.setText(saved.optString("peers"));
            secret.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
            peers.setSingleLine(false); peers.setMinLines(2); peers.setMaxLines(4);
            easyTierField(identityCard, "网络名称", name); easyTierField(identityCard, "网络密钥", secret);
            LinearLayout addressCard = easyTierCard(form, "虚拟地址与节点");
            android.widget.Switch dhcp = new android.widget.Switch(this); dhcp.setText("自动分配手机地址（DHCP）"); dhcp.setTextColor(foreground); dhcp.setChecked(saved.optBoolean("dhcp")); addressCard.addView(dhcp);
            TextView addressLabel = text(dhcp.isChecked()?"电脑虚拟 IPv4 / 前缀（用于校验子网）":"手机虚拟 IPv4 / 前缀", 13, muted); addressCard.addView(addressLabel); addressCard.addView(cidr);
            dhcp.setOnCheckedChangeListener((control, checked)->addressLabel.setText(checked?"电脑虚拟 IPv4 / 前缀（用于校验子网）":"手机虚拟 IPv4 / 前缀"));
            addressCard.addView(text("扫码入网默认自动分配。仅路由电脑虚拟子网；关闭 DHCP 时需确保静态手机地址不重复。", 12, muted));
            easyTierField(addressCard, "组网节点 · 每行一个", peers);
            Button official = easyTierSecondary("添加官方公共节点"); addressCard.addView(official);
            official.setOnClickListener(v -> {
                String node = "tcp://public.easytier.cn:11010", current = peers.getText().toString().trim();
                for (String entry : current.split("\\r?\\n")) if (node.equals(entry.trim())) { toast("已包含官方公共节点"); return; }
                peers.setText(current.isEmpty() ? node : current + "\n" + node); toast("已填入，保存后生效");
            });
            addressCard.addView(text("公共节点用于发现和中转，可保留自己的节点。公共节点负载和可用性可能变化。", 12, muted));
            Button connect = button("保存并连接"), saveOnly = easyTierSecondary("仅保存配置"), disconnect = easyTierSecondary("断开 EasyTier");
            form.addView(connect); form.addView(saveOnly); form.addView(disconnect);
            LinearLayout helpCard = easyTierCard(form, "使用帮助");
            helpCard.addView(text("1. 手机与电脑使用相同的网络名称、密钥和可达节点。\n2. 启用 DHCP 自动分配手机地址，再保存并连接。\n3. 返回主页选择 EasyTier，通过电脑虚拟 IP 连接。", 13, muted));
            settingRow(helpCard, "官方使用说明", "打开 EasyTier 快速组网文档 ↗", () -> openEasyTierHelp("https://www.easytier.cn/guide/network/quick-networking.html"));
            settingRow(helpCard, "官方节点说明", "查看共享节点的使用方式 ↗", () -> openEasyTierHelp("https://doc.easytier.cn/guide/network/fast-networking.html"));
            Button licenses = easyTierSecondary("来源与开源许可"); helpCard.addView(licenses); licenses.setOnClickListener(view -> {
                try (java.io.InputStream stream = getAssets().open("easytier-NOTICES.txt")) {
                    java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream(); byte[] bytes = new byte[8192]; int read;
                    while ((read = stream.read(bytes)) != -1) buffer.write(bytes, 0, read);
                    TextView content = text("此预览使用 Moonlight V+ 仓库固定提交中的 EasyTier ARM64 原生库，未修改其二进制。\nhttps://github.com/qiin2333/moonlight-vplus/tree/799f50d6e7bd288cd69fe6df2081c9d9502c45b4\n\nEasyTier 上游：https://github.com/EasyTier/EasyTier\n\n" + buffer.toString("UTF-8"), 12, foreground);
                    android.app.Dialog licenseDialog = new android.app.Dialog(this); LinearLayout licensePage = panel(licenseDialog, "来源与开源许可");
                    ScrollView notice = new ScrollView(this); notice.addView(content); licensePage.addView(notice, new LinearLayout.LayoutParams(-1, 0, 1));
                    licenseDialog.show(); licenseDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent); licenseDialog.getWindow().setLayout(-1, -1);
                } catch (Exception e) { toast("无法读取许可文件"); }
            });
                View.OnClickListener save = view -> {
                    try {
                        if (EasyTierVpnService.running) throw new Exception("请先断开 EasyTier，再修改配置或重连");
                        new EasyTierProfile(name.getText().toString(), secret.getText().toString(), cidr.getText().toString(), peers.getText().toString(), dhcp.isChecked());
                        settings.save(new JSONObject().put("name", name.getText().toString()).put("secret", secret.getText().toString()).put(dhcp.isChecked()?"networkCidr":"cidr", cidr.getText().toString()).put("dhcp",dhcp.isChecked()).put("peers", peers.getText().toString()));
                        boolean shouldConnect = view == connect;
                        dialog.dismiss();
                        if (shouldConnect) {
                            if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) requestPermissions(new String[] { android.Manifest.permission.POST_NOTIFICATIONS }, 201);
                            else prepareEasyTier();
                        } else toast("组网配置已加密保存");
                    } catch (Exception e) { toast(error(e)); }
                };
                connect.setOnClickListener(save);
                saveOnly.setOnClickListener(save);
                disconnect.setOnClickListener(view -> {
                    if (EasyTierVpnService.running) EasyTierVpnService.disconnect(this);
                    dialog.dismiss();
                });
            android.os.Handler updater = new android.os.Handler(getMainLooper());
            Runnable refresh = new Runnable() { public void run() {
                networkStatus.setText(EasyTierVpnService.state); boolean editable = !EasyTierVpnService.running;
                for (View input : new View[] { name, secret, cidr, dhcp, peers, official, connect, saveOnly }) { input.setEnabled(editable); input.setAlpha(editable ? 1f : .45f); }
                disconnect.setVisibility(editable ? View.GONE : View.VISIBLE);
                if (dialog.isShowing()) updater.postDelayed(this, 1000);
            } };
            dialog.setOnDismissListener(ignored -> updater.removeCallbacks(refresh)); dialog.show();
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent); dialog.getWindow().setLayout(-1, -1); updater.post(refresh);
        } catch (Exception e) { toast("无法读取组网配置，请重装或检查手机密钥存储"); }
    }
    private LinearLayout easyTierCard(LinearLayout parent, String title) {
        LinearLayout card = vertical(); card.setPadding(dp(16), dp(10), dp(16), dp(16)); card.setBackground(rounded(Color.rgb(31,31,31), 20));
        LinearLayout.LayoutParams spacing = new LinearLayout.LayoutParams(-1, -2); spacing.topMargin = dp(12); parent.addView(card, spacing);
        TextView heading = text(title, 15, foreground); heading.setTypeface(null, Typeface.BOLD); heading.setTextIsSelectable(false); card.addView(heading); return card;
    }
    private void easyTierField(LinearLayout parent, String label, EditText input) {
        TextView heading = text(label, 13, muted); heading.setTextIsSelectable(false); parent.addView(heading);
        input.setBackground(rounded(Color.rgb(43,43,43), 12)); input.setPadding(dp(14), dp(13), dp(14), dp(13)); parent.addView(input);
        heading.setLabelFor(View.generateViewId()); input.setId(heading.getLabelFor());
    }
    private Button easyTierSecondary(String label) {
        Button control = button(label); control.setBackground(rounded(Color.rgb(46,46,46), 16)); control.setTextColor(foreground); return control;
    }
    private void openEasyTierHelp(String url) {
        try { startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))); }
        catch (android.content.ActivityNotFoundException e) { toast("未找到可打开文档的浏览器"); }
    }
    private void startEasyTier() {
        try { startForegroundService(new android.content.Intent(this, EasyTierVpnService.class)); toast("正在启动，请在 EasyTier 设置中查看状态"); }
        catch (Exception e) { toast("系统未允许启动 VPN 服务，请保持 App 在前台重试"); }
    }
    private void prepareEasyTier() {
        android.content.Intent permission = android.net.VpnService.prepare(this);
        if (permission == null) startEasyTier(); else startActivityForResult(permission, 200);
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 201) prepareEasyTier();
    }
    @Override protected void onActivityResult(int request, int result, android.content.Intent data) {
        super.onActivityResult(request, result, data);
        if(request==204){if(result==RESULT_OK&&data!=null&&pairingInput!=null)pairingInput.accept(data.getStringExtra("pairing"));return;}
        if(request==205){if(result==RESULT_OK)PairingCoordinator.start(this);else PairingCoordinator.permissionDenied(this);refreshPairingUi();return;}
        if(request==202){java.util.function.Consumer<Boolean> callback=switchVpnPermission;switchVpnPermission=null;if(callback!=null)callback.accept(result==RESULT_OK);return;}
        if (request == 200) { if (result == RESULT_OK) startEasyTier(); else toast("未授予 VPN 权限"); }
    }
    private void beginPolling() { beginPolling(false); }
    private void beginPolling(boolean refresh) {
        if (api == null) return;
        try {
            android.content.Intent intent = new android.content.Intent(this, SessionService.class);
            if (refresh) intent.setAction("refresh");
            if (!selectedThreadId.isEmpty()) intent.putExtra("threadId", selectedThreadId);
            startForegroundService(intent);
            if (SessionService.latest != null && api.fingerprint.equals(SessionService.latest.optString("computer"))) render(SessionService.latest);
            if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED && !getPreferences(MODE_PRIVATE).getBoolean("notificationAsked", false)) {
                getPreferences(MODE_PRIVATE).edit().putBoolean("notificationAsked", true).apply();
                requestPermissions(new String[] { android.Manifest.permission.POST_NOTIFICATIONS }, 202);
            }
        } catch (Exception error) { status.setText("后台同步启动失败，请重新打开 App：" + error.getMessage()); }
    }
    private void chooseChat() {
        if (currentState == null) { toast("尚无聊天列表，请先连接电脑"); return; }
        try {
            java.util.LinkedHashMap<String, String> rows = new java.util.LinkedHashMap<>();
            for (String key : new String[] { "catalog", "threads" }) {
                JSONArray values = currentState.optJSONArray(key); if (values == null) continue;
                for (int i = 0; i < values.length(); i++) { JSONObject row = values.getJSONObject(i); rows.put(row.getString("id"), row.optString("name", "桌面聊天")); }
            }
            android.app.Dialog drawer = new android.app.Dialog(this); LinearLayout panel = panel(drawer,"会话");
            LinearLayout searchBar=new LinearLayout(this);searchBar.setGravity(android.view.Gravity.CENTER_VERTICAL);searchBar.setPadding(dp(14),0,dp(14),0);searchBar.setBackground(rounded(Color.rgb(38,38,38),24));
            ImageView searchIcon=new ImageView(this);searchIcon.setImageDrawable(new UiIcon("search",muted));searchBar.addView(searchIcon,new LinearLayout.LayoutParams(dp(20),dp(20)));searchIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            EditText search = field("搜索会话");search.setBackgroundColor(Color.TRANSPARENT);search.setPadding(dp(10),dp(12),0,dp(12));search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);searchBar.addView(search,new LinearLayout.LayoutParams(0,dp(48),1));
            LinearLayout.LayoutParams searchSpacing=new LinearLayout.LayoutParams(-1,dp(48));searchSpacing.topMargin=dp(8);searchSpacing.bottomMargin=dp(12);panel.addView(searchBar,searchSpacing);
            ScrollView listScroll = new ScrollView(this); LinearLayout list = vertical(); listScroll.addView(list); panel.addView(listScroll,new LinearLayout.LayoutParams(-1,0,1));
            Runnable filter = () -> {
                list.removeAllViews(); String query=search.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
                for(java.util.Map.Entry<String,String> row:rows.entrySet()) {
                    if(!row.getValue().toLowerCase(java.util.Locale.ROOT).contains(query))continue;
                    LinearLayout item=vertical();item.setPadding(dp(14),dp(10),dp(14),dp(10));
                    if(row.getKey().equals(selectedThreadId))item.setBackground(rounded(Color.rgb(43,43,43),12));
                    TextView name=text(row.getValue(),15,foreground);name.setTextIsSelectable(false);name.setMaxLines(2);item.addView(name);
                    TextView detail=text(row.getKey().equals(selectedThreadId)?"当前会话":"点击继续",11,muted);detail.setTextIsSelectable(false);detail.setPadding(0,0,0,0);item.addView(detail);list.addView(item);
                    item.setOnClickListener(v->{
                        drafts.put(selectedThreadId,composer.getText().toString()); selectedThreadId=row.getKey(); composer.setText(drafts.containsKey(selectedThreadId)?drafts.get(selectedThreadId):"");
                        try{render(currentState);}catch(Exception ignored){} beginPolling(); drawer.dismiss();
                    });
                }
                if(list.getChildCount()==0)list.addView(text("没有找到匹配的会话",14,muted));
            };
            search.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){} public void onTextChanged(CharSequence s,int start,int before,int count){filter.run();}public void afterTextChanged(android.text.Editable e){}});
            filter.run(); settingRow(panel,"设置","电脑连接、EasyTier 与通知",()->{drawer.dismiss();settingsPage();});
            drawer.show(); drawer.getWindow().setBackgroundDrawableResource(android.R.color.transparent); drawer.getWindow().setLayout(Math.min(getResources().getDisplayMetrics().widthPixels-dp(28),dp(400)),-1); drawer.getWindow().setGravity(android.view.Gravity.START);
        } catch (Exception error) { toast("聊天列表读取失败"); }
    }
    private LinearLayout panel(android.app.Dialog dialog,String title) {
        LinearLayout page=vertical();page.setPadding(dp(16),dp(16),dp(16),dp(12));page.setBackgroundColor(background); page.setFitsSystemWindows(true);
        page.setOnApplyWindowInsetsListener((v,insets)->{if(android.os.Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.ime());v.setPadding(dp(16)+bars.left,dp(8)+bars.top,dp(16)+bars.right,dp(12)+bars.bottom);}return insets;});
        page.addView(toolbar(title,dialog::dismiss));dialog.setContentView(page);dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);return page;
    }
    private TextView settingRow(LinearLayout parent,String title,String subtitle,Runnable action) {
        LinearLayout row=new LinearLayout(this);row.setGravity(android.view.Gravity.CENTER_VERTICAL);row.setPadding(dp(12),dp(8),dp(12),dp(8));row.setMinimumHeight(dp(76));
        LinearLayout labels=vertical();TextView label=text(title,16,foreground);label.setTextIsSelectable(false);labels.addView(label);TextView description=text(subtitle,12,muted);description.setTextIsSelectable(false);description.setPadding(0,0,0,dp(6));labels.addView(description);row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        ImageView arrow=new ImageView(this);arrow.setImageDrawable(new UiIcon("next",muted));row.addView(arrow,new LinearLayout.LayoutParams(dp(20),dp(20)));row.setBackground(rounded(Color.rgb(31,31,31),14));LinearLayout.LayoutParams spacing=new LinearLayout.LayoutParams(-1,-2);spacing.topMargin=dp(8);parent.addView(row,spacing);row.setOnClickListener(v->action.run());return description;
    }
    private void settingsPage() {
        android.app.Dialog dialog=new android.app.Dialog(this);LinearLayout page=panel(dialog,"设置");ScrollView settingsScroll=new ScrollView(this);LinearLayout items=vertical();settingsScroll.addView(items);page.addView(settingsScroll,new LinearLayout.LayoutParams(-1,0,1));
        items.addView(text("连接与设备",13,muted));
        TextView connectionHint=settingRow(items,"连接方式",networkModeLabel(),()->{});((View)connectionHint.getParent()).setOnClickListener(v->connectionSettingsDialog(v));
        settingRow(items,"当前电脑",api==null?"尚未配对":api.address,()->new AlertDialog.Builder(this).setTitle("电脑连接").setMessage(api==null?"尚未配对":api.address+"\n\n"+SessionService.message).setPositiveButton("重新同步",(d,w)->beginPolling(true)).setNegativeButton("关闭",null).show());
        TextView networkHint=settingRow(items,"EasyTier 组网",EasyTierVpnService.state,this::easyTierDialog);
        settingRow(items,"通知与后台运行","任务完成、审批提醒与系统权限",this::notificationSettings);
        settingRow(items,"重新同步","重新连接电脑并刷新聊天状态",()->{beginPolling(true);toast("正在重新同步");});
        settingRow(items,"查看连接引导","预览扫码与配对页面，保留当前连接",()->{dialog.dismiss();connectionPage();});
        items.addView(text("关于",13,muted));items.addView(text("Codex LAN · 0.4.12 Preview\n非官方远控客户端",14,muted));
        settingRow(items,"清除本机配对","删除本机连接信息，重新配对电脑",()->new AlertDialog.Builder(this).setTitle("清除本机配对？").setMessage("手机上的连接信息将删除。电脑端的授权记录仍需在电脑撤销；EasyTier 配置保留。")
            .setNegativeButton("返回",null).setPositiveButton("清除",(d,w)->{generation++;api.cancel();stopService(new android.content.Intent(this,SessionService.class));SessionService.latest=null;api=null;currentState=null;selectedThreadId="";drafts.clear();storage.clear();dialog.dismiss();connectionPage();}).show());
        android.os.Handler refresh=new android.os.Handler(getMainLooper());Runnable networkRefresh=new Runnable(){public void run(){networkHint.setText(EasyTierVpnService.state);if(dialog.isShowing())refresh.postDelayed(this,1000);}};dialog.setOnDismissListener(d->refresh.removeCallbacks(networkRefresh));
        dialog.show();dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);dialog.getWindow().setLayout(-1,-1);refresh.post(networkRefresh);
    }
    private void notificationSettings() {
        new AlertDialog.Builder(this).setTitle("通知与后台同步")
            .setItems(new String[]{"隐藏后台同步运行提示", "隐藏 EasyTier 运行提示", "任务完成：横幅/锁屏提醒", "需要审批：横幅/锁屏提醒", "全部通知类别与权限", "HyperOS 后台/省电设置"}, (dialog, which) -> {
                if (which < 2) {
                    String channel = which == 0 ? "chat_connection" : "easytier";
                    new AlertDialog.Builder(this).setTitle("隐藏运行提示")
                        .setMessage("在下一页关闭此通知类别即可隐藏常驻提示。请保留应用总通知权限，以及“连接成功”“需要审批”“任务完成或失败”类别。Android 系统自己的 VPN/活动应用标识仍由系统管理。")
                        .setPositiveButton("打开此类别", (details, button) -> openNotificationChannel(channel))
                        .setNegativeButton("返回", null).show();
                } else if (which == 2 || which == 3) {
                    String channel = which == 2 ? ChatNotifications.resultChannel(this) : "chat_approval";
                    new AlertDialog.Builder(this).setTitle("开启横幅与锁屏提醒")
                        .setMessage("请在下一页启用通知、悬浮通知/横幅、锁屏通知，并选择提醒而非静默。若类别页面没有这些选项，请到应用通知设置开启“Codex LAN”的悬浮通知和锁屏通知；勿扰模式也可能抑制提示。锁屏息屏时不会显示亮屏顶部横幅，是否亮屏由系统通知设置决定。")
                        .setPositiveButton("打开此类别", (details, button) -> openNotificationChannel(channel))
                        .setNeutralButton("应用通知设置", (details, button) -> startActivity(new android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName())))
                        .setNegativeButton("返回", null).show();
                } else if (which == 4) {
                    startActivity(new android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName()));
                } else startActivity(new android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            })
            .setNegativeButton("返回", null).show();
    }
    private void openNotificationChannel(String channel) {
        try {
            startActivity(new android.content.Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName())
                .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, channel));
        } catch (android.content.ActivityNotFoundException unavailable) {
            startActivity(new android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName()));
        }
    }
    @Override protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        if(android.content.Intent.ACTION_MAIN.equals(intent.getAction())&&intent.hasCategory(android.content.Intent.CATEGORY_LAUNCHER))showLaunchAnimation();
        refreshPairingUi();
        String id = intent.getStringExtra("threadId"); if (id != null && !id.isEmpty() && !id.equals(selectedThreadId)) {
            if(composer != null)drafts.put(selectedThreadId,composer.getText().toString()); selectedThreadId = id;
            if(composer != null)composer.setText(drafts.containsKey(id)?drafts.get(id):"");
        }
        if (api != null) beginPolling();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putBoolean("connectionGuideVisible",connectionGuideVisible);out.putBoolean("pairingInProgress",PairingCoordinator.busy()||pairingProgressCard!=null&&pairingProgressCard.getVisibility()==View.VISIBLE);
        if(api != null && composer != null)drafts.put(selectedThreadId,composer.getText().toString());
        out.putString("selectedChat",selectedThreadId);out.putStringArrayList("draftIds",new java.util.ArrayList<>(drafts.keySet()));out.putStringArrayList("draftValues",new java.util.ArrayList<>(drafts.values()));super.onSaveInstanceState(out);
    }
    private String error(Exception error) { String value = error.getMessage(); return value == null ? "连接失败，请检查地址、配对信息和网络" : value; }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
    @Override protected void onStart() {
        super.onStart(); visible = true;
        android.content.IntentFilter filter = new android.content.IntentFilter(SessionService.UPDATE);filter.addAction(PairingCoordinator.UPDATE);
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(updates, filter, RECEIVER_NOT_EXPORTED); else registerReceiver(updates, filter);
        refreshPairingUi();
        if(api==null&&connectionGuideVisible&&!PairingCoordinator.busy()&&!PairingCoordinator.failed())try{JSONObject pending=PairingCoordinator.pending(this);if(pending!=null&&"waiting".equals(pending.optString("phase"))&&android.net.VpnService.prepare(this)==null)PairingCoordinator.start(this);}catch(Exception ignored){}
        if (api != null) beginPolling();
    }
    @Override protected void onStop() { visible = false; generation++;modelOpenWanted="";modelLoading.clear(); unregisterReceiver(updates); if (api != null) { getPreferences(MODE_PRIVATE).edit().putString("selected:" + api.fingerprint,selectedThreadId).apply(); api.cancel(); } sending = false; dismissRailPreview();dismissModelPopup();if(jumpRail!=null)jumpRail.cancel();super.onStop(); }
    @Override protected void onDestroy() { generation++;renderRevision++;renderHandler.removeCallbacks(prepareRows);renderHandler.removeCallbacks(flushRows);renderWorker.shutdownNow(); if (api != null) api.cancel(); worker.shutdownNow(); super.onDestroy(); }
}
