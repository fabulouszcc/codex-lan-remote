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
    private LinearLayout root;
    private TextView status, sendStatus;
    private EditText composer;
    private Button send;
    private TextView modelChoice;
    private LinearLayout jumpRail;
    private String jumpSignature="";
    private final java.util.Set<String> expandedTools = new java.util.HashSet<>();
    private final java.util.Map<String, String> drafts = new java.util.HashMap<>();
    private JSONObject currentThread;
    private ListView scroll;
    private ChatAdapter chatAdapter;
    private final ExecutorService renderWorker = Executors.newSingleThreadExecutor();
    private final android.os.Handler renderHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private int renderRevision;
    private boolean listScrolling;
    private java.util.List<ChatRows.Row> deferredRows;
    private String displayedChat = "";
    private final Runnable prepareRows = () -> {
        final JSONObject thread=currentThread; final int revision=renderRevision;
        if(thread==null||isDestroyed())return;
        renderWorker.execute(()->{try{
            java.util.List<ChatRows.Row> rows=ChatRows.build(thread);
            runOnUiThread(()->{if(isDestroyed()||revision!=renderRevision)return;
                if(listScrolling)deferredRows=rows;else applyRows(rows);
            });
        }catch(Exception e){runOnUiThread(()->{if(revision==renderRevision)toast("聊天显示失败："+error(e));});}});
    };
    private boolean sending;
    private JSONObject currentState;
    private String selectedThreadId = "";
    private Button selectChat;
    private final android.content.BroadcastReceiver updates = new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context context, android.content.Intent intent) {
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
        if (api == null) connectionPage(); else chatPage();
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
    private void connectionPage() {
        base("连接你的电脑", "Codex LAN · 从手机继续桌面工作");
        ScrollView formScroll = new ScrollView(this); LinearLayout form = vertical(); formScroll.addView(form); root.addView(formScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        RadioGroup modes=new RadioGroup(this);modes.setOrientation(LinearLayout.HORIZONTAL);RadioButton lan=new RadioButton(this), et=new RadioButton(this);lan.setText("局域网直连");et.setText("EasyTier 网络");lan.setTextColor(foreground);et.setTextColor(foreground);lan.setId(View.generateViewId());et.setId(View.generateViewId());modes.addView(lan);modes.addView(et);form.addView(modes);
        TextView networkHint=text("手机和电脑连接同一 Wi-Fi，填写电脑的局域网地址。",14,muted);form.addView(networkHint);
        Button easytier = button("配置 EasyTier 网络"); form.addView(easytier); easytier.setOnClickListener(view -> easyTierDialog());
        form.addView(text("电脑配对",18,foreground));
        EditText address = field("https://电脑IP:9443"), fingerprint = field("电脑证书 SHA-256 指纹"), code = field("有效期内的配对码 code");
        address.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        modes.setOnCheckedChangeListener((group,id)->{boolean direct=id==lan.getId();getPreferences(MODE_PRIVATE).edit().putString("networkMode",direct?"lan":"easytier").apply();easytier.setVisibility(direct?View.GONE:View.VISIBLE);networkHint.setText(direct?"手机和电脑连接同一 Wi-Fi。电脑插件填写局域网 IPv4，或启用额外局域网直连地址。":"先配置 EasyTier 网络，再使用电脑的虚拟 IP 配对。");address.setHint(direct?"电脑局域网 IP:9443":"电脑虚拟 IP:9443");});
        modes.check("easytier".equals(getPreferences(MODE_PRIVATE).getString("networkMode","lan"))?et.getId():lan.getId());
        fingerprint.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        code.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        form.addView(address); form.addView(fingerprint); form.addView(code);
        Button paste = button("粘贴电脑配对信息"); form.addView(paste);
        paste.setOnClickListener(view -> {
            try {
                android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (clipboard.getPrimaryClip() == null) throw new Exception();
                JSONObject data = new JSONObject(clipboard.getPrimaryClip().getItemAt(0).coerceToText(this).toString());
                address.setText(data.getString("address")); fingerprint.setText(data.getString("fingerprint")); code.setText(data.getString("code"));
            } catch (Exception e) { toast("请先复制电脑 phone-pairing.local.json 的完整内容"); }
        });
        TextView feedback = text("仅连接证书指纹匹配的电脑；首次配对信息请从可信渠道取得。", 13, muted); form.addView(feedback);
        Button pair = button("配对并连接"); form.addView(pair);
        pair.setOnClickListener(view -> {
            try {
                RemoteApi candidate = new RemoteApi(MobileState.connectionAddress(address.getText().toString()), fingerprint.getText().toString(), null);
                String pairCode = code.getText().toString().trim();
                if (pairCode.isEmpty()) throw new Exception("请输入配对码");
                pair.setEnabled(false); feedback.setText("正在验证电脑并配对…");
                worker.execute(() -> {
                    try {
                        JSONObject result = candidate.call("POST", "/v1/pair", new JSONObject().put("code", pairCode).put("name", android.os.Build.MODEL), null);
                        candidate.token = result.getString("token");
                        storage.save(new JSONObject().put("address", candidate.address).put("fingerprint", candidate.fingerprint).put("token", candidate.token));
                        runOnUiThread(() -> { if (isDestroyed()) return; api = candidate; selectedThreadId = ""; currentState = null; code.setText(""); chatPage(); if (visible) beginPolling(); });
                    } catch (Exception e) { runOnUiThread(() -> { pair.setEnabled(true); feedback.setText("连接失败：" + error(e)); }); }
                });
            } catch (Exception e) { feedback.setText(error(e)); }
        });
        form.addView(text("已配对后可同步会话历史、继续任务和处理审批。完成与审批提醒可在设置中调整。", 13, muted));
    }
    private String networkModeLabel(){String mode=getPreferences(MODE_PRIVATE).getString("networkMode","");return "lan".equals(mode)?"局域网直连  ›":"easytier".equals(mode)?"EasyTier 网络  ›":"连接方式  ›";}
    private void connectionModeDialog() {
        if(api==null){connectionPage();return;}if(sending){toast("请等待当前提交完成");return;}
        try {
            RemoteApi original=api;int epoch=generation;JSONObject saved=storage.load();JSONObject addresses=saved==null?null:saved.optJSONObject("addresses");if(addresses==null)addresses=new JSONObject();final JSONObject profiles=addresses;
            JSONObject advertised=currentState==null?null:currentState.optJSONObject("connectionAddresses");final JSONObject hints=advertised==null?new JSONObject():advertised;
            LinearLayout form=vertical();form.setPadding(dp(20),0,dp(20),dp(12));RadioGroup modes=new RadioGroup(this);RadioButton lan=new RadioButton(this),et=new RadioButton(this);lan.setId(View.generateViewId());et.setId(View.generateViewId());lan.setText("局域网直连 · 同一 Wi-Fi");et.setText("EasyTier · 异地组网");lan.setTextColor(foreground);et.setTextColor(foreground);modes.addView(lan);modes.addView(et);form.addView(modes);
            TextView hint=text("",13,muted);form.addView(hint);EditText address=field("电脑 IP:9443");address.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);form.addView(address);
            Button etSettings=button("配置 / 连接 EasyTier");form.addView(etSettings);etSettings.setOnClickListener(v->easyTierDialog());
            modes.setOnCheckedChangeListener((group,id)->{boolean direct=id==lan.getId();String key=direct?"lan":"easytier";address.setText(profiles.optString(key,hints.optString(direct?"lan":"primary",direct?"":original.address)));etSettings.setVisibility(direct?View.GONE:View.VISIBLE);hint.setText(direct?"填写电脑的局域网 IPv4。电脑插件需绑定此地址，或开启额外局域网直连。":"先连接 EasyTier，再填写电脑虚拟 IP。切换地址仍会校验同一电脑证书及授权。");});
            modes.check("easytier".equals(getPreferences(MODE_PRIVATE).getString("networkMode",""))?et.getId():lan.getId());
            TextView feedback=text("验证成功后才会保存，不需要重新配对同一台电脑。",12,muted);form.addView(feedback);ScrollView body=new ScrollView(this);body.addView(form);
            AlertDialog dialog=new AlertDialog.Builder(this).setTitle("连接方式").setView(body).setNegativeButton("取消",null).setPositiveButton("验证并切换",null).create();
            dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                final String mode=modes.getCheckedRadioButtonId()==lan.getId()?"lan":"easytier";
                final RemoteApi candidate;try{candidate=new RemoteApi(MobileState.connectionAddress(address.getText().toString()),original.fingerprint,original.token);}catch(Exception e){feedback.setText(error(e));return;}
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);feedback.setText("正在验证电脑身份和连接…");
                worker.execute(()->{try{JSONObject verified=candidate.call("GET","/v1/state",null,null);runOnUiThread(()->{
                    if(isDestroyed()||api!=original||epoch!=generation||!dialog.isShowing())return;
                    try{JSONObject connection=storage.load();if(connection==null)throw new Exception("配对信息已清除");String previous=getPreferences(MODE_PRIVATE).getString("networkMode","");if(!previous.isEmpty())profiles.put(previous,original.address);profiles.put(mode,candidate.address);connection.put("address",candidate.address).put("addresses",profiles);storage.save(connection);
                        original.cancel();api=candidate;generation++;getPreferences(MODE_PRIVATE).edit().putString("networkMode",mode).apply();verified.put("online",true);render(verified);
                        android.content.Intent intent=new android.content.Intent(this,SessionService.class).setAction("switch-address");if(!selectedThreadId.isEmpty())intent.putExtra("threadId",selectedThreadId);startForegroundService(intent);dialog.dismiss();toast("连接方式已切换");
                    }catch(Exception e){feedback.setText(error(e));dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);}
                });}catch(Exception e){runOnUiThread(()->{if(dialog.isShowing()){feedback.setText("连接未确认："+error(e));dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);}});}});
            }));dialog.show();
        }catch(Exception e){toast("无法读取连接配置："+error(e));}
    }
    private void chatPage() {
        currentThread = null;
        base("", ""); root.removeAllViews();
        LinearLayout header = new LinearLayout(this); header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        Button menu = icon("menu","会话列表",false); header.addView(menu,new LinearLayout.LayoutParams(dp(48),dp(48))); menu.setOnClickListener(v -> chooseChat());
        LinearLayout heading = vertical(); selectChat = new Button(this); selectChat.setText("Codex"); selectChat.setTextColor(foreground); selectChat.setTextSize(18); selectChat.setAllCaps(false); selectChat.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL); selectChat.setBackgroundColor(Color.TRANSPARENT); selectChat.setPadding(dp(4),0,0,0); selectChat.setSingleLine(true); selectChat.setEllipsize(android.text.TextUtils.TruncateAt.END);
        heading.addView(selectChat,new LinearLayout.LayoutParams(-1,dp(31))); selectChat.setOnClickListener(v -> chooseChat());
        status = text("正在连接电脑…",11,muted); status.setPadding(dp(4),0,0,0); status.setTextIsSelectable(false); status.setSingleLine(true); heading.addView(status);
        status.setContentDescription("切换连接方式");status.setOnClickListener(v->connectionModeDialog());
        header.addView(heading,new LinearLayout.LayoutParams(0,dp(56),1)); Button settings = icon("settings","设置",false); header.addView(settings,new LinearLayout.LayoutParams(dp(48),dp(48))); settings.setOnClickListener(v -> settingsPage()); root.addView(header);
        renderRevision++;deferredRows=null;displayedChat="";listScrolling=false;
        scroll=new ListView(this);scroll.setDivider(null);scroll.setDividerHeight(0);scroll.setCacheColorHint(Color.TRANSPARENT);scroll.setSelector(android.R.color.transparent);scroll.setClipToPadding(false);scroll.setPadding(0,dp(20),0,dp(20));scroll.setVerticalScrollBarEnabled(false);
        chatAdapter=new ChatAdapter();scroll.setAdapter(chatAdapter);
        LinearLayout historyArea=new LinearLayout(this);historyArea.addView(scroll,new LinearLayout.LayoutParams(0,-1,1));jumpRail=vertical();jumpRail.setGravity(android.view.Gravity.CENTER);historyArea.addView(jumpRail,new LinearLayout.LayoutParams(dp(28),-1));jumpSignature="";root.addView(historyArea,new LinearLayout.LayoutParams(-1,0,1));
        scroll.setOnScrollListener(new AbsListView.OnScrollListener(){public void onScroll(AbsListView v,int first,int count,int total){}public void onScrollStateChanged(AbsListView v,int state){listScrolling=state!=SCROLL_STATE_IDLE;if(!listScrolling&&deferredRows!=null){java.util.List<ChatRows.Row> rows=deferredRows;deferredRows=null;applyRows(rows);}}});
        LinearLayout dock = vertical(); dock.setPadding(dp(10),dp(4),dp(10),dp(8)); dock.setBackground(rounded(Color.rgb(38,38,38),24));
        composer = field("发送消息"); composer.setBackgroundColor(Color.TRANSPARENT); composer.setSingleLine(false); composer.setMaxLines(6); composer.setMinLines(1); composer.setPadding(dp(12),dp(12),dp(8),dp(12)); composer.setText(drafts.containsKey(selectedThreadId) ? drafts.get(selectedThreadId) : "");
        dock.addView(composer,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout controls=new LinearLayout(this);controls.setGravity(android.view.Gravity.CENTER_VERTICAL);
        modelChoice=text("选择模型  ⌄",12,muted);modelChoice.setTextIsSelectable(false);modelChoice.setSingleLine(true);modelChoice.setEllipsize(android.text.TextUtils.TruncateAt.END);modelChoice.setPadding(dp(10),dp(10),dp(4),dp(10));modelChoice.setContentDescription("选择模型与思考强度");modelChoice.setOnClickListener(v->modelDialog());controls.addView(modelChoice,new LinearLayout.LayoutParams(0,dp(48),1));
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
    private void command(JSONObject payload, boolean clearComposer) {
        if (currentThread == null || api == null || sending) return;
        try { if (!payload.has("threadId")) payload.put("threadId", currentThread.getString("id")); } catch (Exception e) { return; }
        RemoteApi client = api; int epoch = generation; String textBefore = composer.getText().toString();
        sending = true; setButtons(false); String id = UUID.randomUUID().toString();
        worker.execute(() -> {
            try {
                client.call("POST", "/v1/command", payload, id);
                runOnUiThread(() -> { if (epoch == generation && client == api) {
                    if (clearComposer && payload.optString("threadId").equals(selectedThreadId) && composer.getText().toString().equals(textBefore)) { composer.setText(""); drafts.remove(selectedThreadId); }
                    toast("电脑已接收");
                } });
            } catch (Exception e) { runOnUiThread(() -> { if (epoch == generation && client == api) toast("提交未确认，请检查聊天状态后再操作：" + error(e)); }); }
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
        currentState = state;
        JSONArray threads = state.optJSONArray("threads"); if (threads == null) threads = new JSONArray();
        if (selectedThreadId.isEmpty()) selectedThreadId = state.optString("selectedThreadId");
        if (selectedThreadId.isEmpty() && threads.length() > 0) selectedThreadId = threads.getJSONObject(0).getString("id");
        currentThread = null;
        for (int i = 0; i < threads.length(); i++) if (threads.getJSONObject(i).getString("id").equals(selectedThreadId)) currentThread = threads.getJSONObject(i);
        if (currentThread == null) {
            status.setText(state.optBoolean("online") ? "正在加载所选聊天历史…" : "离线 · 暂无该聊天的本地记录");
            renderRevision++;renderHandler.removeCallbacks(prepareRows);deferredRows=null;chatAdapter.replace(java.util.Collections.emptyList());jumpRail.removeAllViews();jumpSignature="";setButtons(false);displayedChat="";return;
        }
        selectChat.setText(currentThread.optString("name", "Codex"));
        status.setText(!state.optBoolean("online") ? "离线 · 显示本地保存记录 · " + SessionService.message
            : currentThread.optBoolean("ready") ? (!MobileState.activeTurnId(currentThread).isEmpty() ? "● 正在处理" : "● 已连接电脑")
            : "已连接 · 历史可读，桌面聊天尚未就绪");
        if (SessionService.message.contains("保存失败")) status.append(" · " + SessionService.message);
        status.append(" · "+networkModeLabel());
        String selectedModel=currentThread.optString("model"),effort=currentThread.optString("reasoningEffort");modelChoice.setText(selectedModel.isEmpty()?"选择模型  ⌄":selectedModel+(effort.isEmpty()?"":" · "+effort)+"  ⌄");
        setButtons(currentThread.optBoolean("ready"));
        renderRevision++;deferredRows=null;if(!renderHandler.hasCallbacks(prepareRows))renderHandler.postDelayed(prepareRows,100);
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
        boolean bottom=switched||chatAdapter.getCount()==0||(scroll.getLastVisiblePosition()>=chatAdapter.getCount()-1&&(last==null||last.getBottom()<=scroll.getHeight()-scroll.getPaddingBottom()+dp(80)));
        displayedChat=chat;chatAdapter.replace(rows);updateJumpRail();
        if(bottom){if(!rows.isEmpty())scroll.setSelection(rows.size()-1);}
        else{int position=0;for(int i=0;i<rows.size();i++)if(rows.get(i).key.equals(anchor)){position=i;break;}scroll.setSelectionFromTop(position,top-scroll.getPaddingTop());}
    }
    private void jumpTo(String key){
        for(int i=0;i<chatAdapter.getCount();i++)if(chatAdapter.getItem(i).key.equals(key)){scroll.setSelectionFromTop(i,0);return;}
        toast("这条记录已更新，请重新选择");
    }
    private void updateJumpRail(){
        java.util.List<ChatRows.Row> users=new java.util.ArrayList<>();StringBuilder signature=new StringBuilder(displayedChat);
        for(int i=0;i<chatAdapter.getCount();i++){ChatRows.Row row=chatAdapter.getItem(i);if("user".equals(row.type)){users.add(row);signature.append('|').append(row.key);}}
        if(signature.toString().equals(jumpSignature))return;jumpSignature=signature.toString();jumpRail.removeAllViews();
        railButton("≡","打开全部快捷定位记录",this::jumpDialog);railButton("↑","跳到最早已加载记录",()->scroll.setSelection(0));
        for(int i=Math.max(0,users.size()-8);i<users.size();i++){ChatRows.Row row=users.get(i);railButton(String.valueOf(i+1),"定位："+ChatRows.navigationText(row.data),()->jumpTo(row.key));}
        railButton("↓","跳到最新记录",()->scroll.setSelection(Math.max(0,chatAdapter.getCount()-1)));
    }
    private void railButton(String label,String description,Runnable action){
        TextView button=text(label,11,muted);button.setGravity(android.view.Gravity.CENTER);button.setTextIsSelectable(false);button.setPadding(0,0,0,0);button.setContentDescription(description);button.setOnClickListener(v->action.run());jumpRail.addView(button,new LinearLayout.LayoutParams(-1,dp(32)));
    }
    private void jumpDialog(){
        java.util.List<ChatRows.Row> users=new java.util.ArrayList<>();java.util.List<String> labels=new java.util.ArrayList<>();
        for(int i=0;i<chatAdapter.getCount();i++){ChatRows.Row row=chatAdapter.getItem(i);if("user".equals(row.type)){users.add(row);labels.add(users.size()+". "+ChatRows.navigationText(row.data));}}
        android.app.Dialog drawer=new android.app.Dialog(this);LinearLayout panel=vertical();panel.setPadding(dp(14),dp(12),dp(14),dp(12));panel.setBackgroundColor(background);panel.addView(toolbar("快捷定位 · 已加载记录",drawer::dismiss));
        ListView list=new ListView(this);list.setDividerHeight(0);list.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,labels){public View getView(int position,View reused,android.view.ViewGroup parent){TextView view=(TextView)super.getView(position,reused,parent);view.setTextColor(foreground);view.setTextSize(14);view.setMaxLines(2);view.setEllipsize(android.text.TextUtils.TruncateAt.END);view.setPadding(dp(12),dp(14),dp(12),dp(14));return view;}});panel.addView(list,new LinearLayout.LayoutParams(-1,0,1));list.setOnItemClickListener((parent,view,position,id)->{jumpTo(users.get(position).key);drawer.dismiss();});drawer.setContentView(panel);android.view.Window window=drawer.getWindow();if(window!=null){window.setBackgroundDrawableResource(android.R.color.transparent);window.setGravity(android.view.Gravity.RIGHT);window.setLayout((int)(getResources().getDisplayMetrics().widthPixels*.86),-1);}drawer.show();if(window!=null)window.setLayout((int)(getResources().getDisplayMetrics().widthPixels*.86),-1);
    }
    private void modelDialog(){
        if(api==null||currentThread==null||!currentState.optBoolean("online")||!currentThread.optBoolean("ready")){toast("连接电脑并等待聊天就绪后选择模型");return;}if(sending)return;
        RemoteApi client=api;String threadId=currentThread.optString("id");int epoch=generation;modelChoice.setEnabled(false);
        worker.execute(()->{try{
            JSONObject result=client.call("GET","/v1/models?threadId="+java.net.URLEncoder.encode(threadId,"UTF-8"),null,null);
            runOnUiThread(()->{modelChoice.setEnabled(true);if(isDestroyed()||client!=api||epoch!=generation||!threadId.equals(selectedThreadId))return;
                JSONArray models=result.optJSONArray("models");if(models==null||models.length()==0){toast("电脑未提供可选模型");return;}
                LinearLayout form=vertical();form.setPadding(dp(20),0,dp(20),dp(12));form.addView(text("对后续任务生效，正在执行的任务继续使用原模型。",13,muted));Spinner model=new Spinner(this),effort=new Spinner(this);form.addView(model);form.addView(text("思考强度",13,muted));form.addView(effort);
                java.util.List<String> names=new java.util.ArrayList<>();int selected=0;JSONObject current=result.optJSONObject("current");for(int i=0;i<models.length();i++){JSONObject row=models.optJSONObject(i);names.add(row.optString("displayName",row.optString("model")));if(current!=null&&row.optString("model").equals(current.optString("model")))selected=i;}
                model.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,names));
                model.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> parent){}public void onItemSelected(AdapterView<?> parent,View view,int position,long id){JSONObject row=models.optJSONObject(position);JSONArray choices=row.optJSONArray("efforts");java.util.List<String> values=new java.util.ArrayList<>();String preferred=current!=null&&!current.optString("effort").isEmpty()&&row.optString("model").equals(current.optString("model"))?current.optString("effort"):row.optString("defaultEffort");int index=0;if(choices!=null)for(int i=0;i<choices.length();i++){String value=choices.optString(i);values.add(value);if(value.equals(preferred))index=i;}effort.setAdapter(new ArrayAdapter<String>(MainActivity.this,android.R.layout.simple_spinner_dropdown_item,values));effort.setSelection(index);}});model.setSelection(selected);
                new AlertDialog.Builder(this).setTitle("选择模型").setView(form).setNegativeButton("取消",null).setPositiveButton("应用",(dialog,which)->{if(!threadId.equals(selectedThreadId)||client!=api||epoch!=generation){toast("聊天已切换，请重新选择");return;}try{Object strength=effort.getSelectedItem();if(strength==null)throw new Exception("此模型未提供思考强度");command(new JSONObject().put("op","model").put("model",models.getJSONObject(model.getSelectedItemPosition()).getString("model")).put("effort",strength.toString()),false);}catch(Exception e){toast(error(e));}}).show();
            });
        }catch(Exception e){runOnUiThread(()->{if(!isDestroyed()){modelChoice.setEnabled(true);toast("读取模型失败："+error(e));}});}});
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
            else if("user".equals(row.type)||"message".equals(row.type))wrapper.addView(bubble(row.data.optString("text"),"user".equals(row.type)));
            else if("activity".equals(row.type)){
                LinearLayout activity=vertical();TextView label=text(row.data.optString("label","正在思考…"),13,muted);activity.addView(label);String summary=row.data.optString("summary");if(!summary.isEmpty())activity.addView(text(summary,13,muted));wrapper.addView(activity);
            }
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
            LinearLayout form = vertical(); form.setPadding(dp(18), 0, dp(18), dp(12));
            TextView networkStatus = text(EasyTierVpnService.state, 13, accent); form.addView(networkStatus);
            form.addView(text("填写你的组网参数。本版使用静态虚拟 IPv4，只路由该虚拟子网。首次连接需同意 Android VPN 授权。", 13, muted));
            EditText name = field("网络名称"), secret = field("网络密钥"), cidr = field("手机虚拟 IPv4/前缀"), peers = field("节点地址，每行一个，例如 tcp://节点IP:11010");
            name.setText(saved.optString("name")); secret.setText(saved.optString("secret")); cidr.setText(saved.optString("cidr")); peers.setText(saved.optString("peers"));
            secret.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
            peers.setSingleLine(false); peers.setMinLines(2); peers.setMaxLines(4);
            for (EditText input : new EditText[] { name, secret, cidr, peers }) form.addView(input);
            form.addView(text("手机虚拟 IP 不要与电脑或其他节点重复。网络密钥仅加密保存在本机。内置组网会占用 Android VPN；请留意已有 VPN。", 12, muted));
            Button licenses = button("EasyTier 来源与开源许可"); form.addView(licenses); licenses.setOnClickListener(view -> {
                try (java.io.InputStream stream = getAssets().open("easytier-NOTICES.txt")) {
                    java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream(); byte[] bytes = new byte[8192]; int read;
                    while ((read = stream.read(bytes)) != -1) buffer.write(bytes, 0, read);
                    TextView content = text("此预览使用 Moonlight V+ 仓库固定提交中的 EasyTier ARM64 原生库，未修改其二进制。\nhttps://github.com/qiin2333/moonlight-vplus/tree/799f50d6e7bd288cd69fe6df2081c9d9502c45b4\n\nEasyTier 上游：https://github.com/EasyTier/EasyTier\n\n" + buffer.toString("UTF-8"), 12, foreground);
                    ScrollView notice = new ScrollView(this); notice.addView(content);
                    new AlertDialog.Builder(this).setTitle("EasyTier · LGPL-3.0").setView(notice).setPositiveButton("关闭", null).show();
                } catch (Exception e) { toast("无法读取许可文件"); }
            });
            ScrollView settingsScroll = new ScrollView(this); settingsScroll.addView(form);
            AlertDialog dialog = new AlertDialog.Builder(this).setTitle("内置 EasyTier")
                .setView(settingsScroll).setPositiveButton("保存并连接", null).setNeutralButton("仅保存", null)
                .setNegativeButton(EasyTierVpnService.running ? "断开" : "关闭", null).create();
            dialog.setOnShowListener(ignored -> {
                View.OnClickListener save = view -> {
                    try {
                        if (EasyTierVpnService.running) throw new Exception("请先断开 EasyTier，再修改配置或重连");
                        new EasyTierProfile(name.getText().toString(), secret.getText().toString(), cidr.getText().toString(), peers.getText().toString());
                        settings.save(new JSONObject().put("name", name.getText().toString()).put("secret", secret.getText().toString()).put("cidr", cidr.getText().toString()).put("peers", peers.getText().toString()));
                        boolean connect = view == dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                        dialog.dismiss();
                        if (connect) {
                            if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) requestPermissions(new String[] { android.Manifest.permission.POST_NOTIFICATIONS }, 201);
                            else prepareEasyTier();
                        } else toast("组网配置已加密保存");
                    } catch (Exception e) { toast(error(e)); }
                };
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(save);
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(save);
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(view -> {
                    if (EasyTierVpnService.running) stopService(new android.content.Intent(this, EasyTierVpnService.class));
                    dialog.dismiss();
                });
            });
            android.os.Handler updater = new android.os.Handler(getMainLooper());
            Runnable refresh = new Runnable() { public void run() { networkStatus.setText(EasyTierVpnService.state); if (dialog.isShowing()) updater.postDelayed(this, 1000); } };
            dialog.setOnDismissListener(ignored -> updater.removeCallbacks(refresh)); dialog.show(); updater.post(refresh);
        } catch (Exception e) { toast("无法读取组网配置，请重装或检查手机密钥存储"); }
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
            EditText search = field("搜索会话"); panel.addView(search);
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
        settingRow(items,"连接方式",networkModeLabel(),this::connectionModeDialog);
        settingRow(items,"当前电脑",api==null?"尚未配对":api.address,()->new AlertDialog.Builder(this).setTitle("电脑连接").setMessage(api==null?"尚未配对":api.address+"\n\n"+SessionService.message).setPositiveButton("重新同步",(d,w)->beginPolling(true)).setNegativeButton("关闭",null).show());
        TextView networkHint=settingRow(items,"EasyTier 组网",EasyTierVpnService.state,this::easyTierDialog);
        settingRow(items,"通知与后台运行","任务完成、审批提醒与系统权限",this::notificationSettings);
        settingRow(items,"重新同步","重新连接电脑并刷新聊天状态",()->{beginPolling(true);toast("正在重新同步");});
        items.addView(text("关于",13,muted));items.addView(text("Codex LAN · 0.3.4 Preview\n非官方远控客户端",14,muted));
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
        String id = intent.getStringExtra("threadId"); if (id != null && !id.isEmpty() && !id.equals(selectedThreadId)) {
            if(composer != null)drafts.put(selectedThreadId,composer.getText().toString()); selectedThreadId = id;
            if(composer != null)composer.setText(drafts.containsKey(id)?drafts.get(id):"");
        }
        if (api != null) beginPolling();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        if(api != null && composer != null)drafts.put(selectedThreadId,composer.getText().toString());
        out.putString("selectedChat",selectedThreadId);out.putStringArrayList("draftIds",new java.util.ArrayList<>(drafts.keySet()));out.putStringArrayList("draftValues",new java.util.ArrayList<>(drafts.values()));super.onSaveInstanceState(out);
    }
    private String error(Exception error) { String value = error.getMessage(); return value == null ? "连接失败，请检查地址、配对信息和网络" : value; }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
    @Override protected void onStart() {
        super.onStart(); visible = true;
        android.content.IntentFilter filter = new android.content.IntentFilter(SessionService.UPDATE);
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(updates, filter, RECEIVER_NOT_EXPORTED); else registerReceiver(updates, filter);
        if (api != null) beginPolling();
    }
    @Override protected void onStop() { visible = false; generation++; unregisterReceiver(updates); if (api != null) { getPreferences(MODE_PRIVATE).edit().putString("selected:" + api.fingerprint,selectedThreadId).apply(); api.cancel(); } sending = false; super.onStop(); }
    @Override protected void onDestroy() { generation++;renderRevision++;renderHandler.removeCallbacks(prepareRows);renderWorker.shutdownNow(); if (api != null) api.cancel(); worker.shutdownNow(); super.onDestroy(); }
}
