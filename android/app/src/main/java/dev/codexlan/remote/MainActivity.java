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
    private final int background = Color.rgb(20, 22, 24), foreground = Color.rgb(236, 238, 239), muted = Color.rgb(160, 168, 172), accent = Color.rgb(130, 221, 185);
    private SecureSession storage;
    private RemoteApi api;
    private volatile int generation;
    private boolean visible;
    private LinearLayout root, messages;
    private TextView status, sendStatus;
    private EditText composer;
    private Button send, steer, interrupt;
    private JSONObject currentThread;
    private ScrollView scroll;
    private String lastRendered = "";
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
        selectedThreadId = getIntent().getStringExtra("threadId") == null ? "" : getIntent().getStringExtra("threadId");
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
    private void connectionPage() {
        base("连接你的电脑", "Codex LAN · 预览版 · 已有网络模式");
        ScrollView formScroll = new ScrollView(this); LinearLayout form = vertical(); formScroll.addView(form); root.addView(formScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        form.addView(text("先配置内置 EasyTier 进入你的网络，再填写电脑虚拟 IP；也可直接使用已有网络。", 15, muted));
        Button easytier = button("内置 EasyTier · 配置与连接"); form.addView(easytier); easytier.setOnClickListener(view -> easyTierDialog());
        EditText address = field("https://电脑IP:9443"), fingerprint = field("电脑证书 SHA-256 指纹"), code = field("有效期内的配对码 code");
        address.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
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
                RemoteApi candidate = new RemoteApi(address.getText().toString(), fingerprint.getText().toString(), null);
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
        form.addView(text("本版支持聊天、追加指令、打断和审批，以及 ARM64 EasyTier 原生库预览。自动发现和任务后台通知尚未接入。", 13, muted));
    }
    private void chatPage() {
        lastRendered = ""; currentThread = null;
        base("Codex LAN", api.address);
        status = text("连接中…", 13, accent); root.addView(status);
        selectChat = button("切换聊天"); root.addView(selectChat); selectChat.setOnClickListener(view -> chooseChat());
        Button notifications = button("通知与后台同步设置"); root.addView(notifications); notifications.setOnClickListener(view -> notificationSettings());
        Button network = button("EasyTier 连接设置"); root.addView(network); network.setOnClickListener(view -> easyTierDialog());
        scroll = new ScrollView(this); messages = vertical(); scroll.addView(messages); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        composer = field("给桌面 Codex 发送任务"); composer.setSingleLine(false); composer.setMaxLines(5); root.addView(composer);
        LinearLayout actions = new LinearLayout(this); send = button("发送"); steer = button("追加指令"); interrupt = button("打断对话");
        for (Button button : new Button[] { send, steer, interrupt }) { LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(46), 1); params.setMargins(dp(2), dp(8), dp(2), 0); actions.addView(button, params); }
        root.addView(actions);
        sendStatus = text("正在同步聊天状态…", 12, muted); root.addView(sendStatus);
        sendStatus.setOnClickListener(view -> { toast("正在重新同步所选聊天"); beginPolling(true); });
        send.setOnClickListener(view -> submit(MobileState.activeTurnId(currentThread).isEmpty() ? "start" : "steer")); steer.setOnClickListener(view -> submit("steer"));
        interrupt.setOnClickListener(view -> {
            if (currentThread == null) return;
            new AlertDialog.Builder(this).setTitle("打断当前对话？").setMessage("已启动的底层命令可能继续运行。此操作不代表停止项目进程。")
                .setNegativeButton("返回", null).setPositiveButton("打断对话", (dialog, which) -> {
                    try {
                        String turnId = MobileState.activeTurnId(currentThread);
                        command(new JSONObject().put("op", "interrupt").put("turnId", turnId), false);
                    } catch (Exception e) { toast(error(e)); }
                }).show();
        });
        Button disconnect = button("切换电脑 / 清除本机配对"); root.addView(disconnect);
        disconnect.setOnClickListener(view -> new AlertDialog.Builder(this).setTitle("清除本机配对？")
            .setMessage("将删除手机保存的连接信息。电脑端授权记录可在电脑撤销。")
            .setNegativeButton("返回", null).setPositiveButton("清除", (dialog, which) -> { generation++; api.cancel(); stopService(new android.content.Intent(this, SessionService.class)); SessionService.latest = null; api = null; currentState = null; selectedThreadId = ""; storage.clear(); connectionPage(); }).show());
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
                    if (clearComposer && payload.optString("threadId").equals(selectedThreadId) && composer.getText().toString().equals(textBefore)) composer.setText("");
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
        // Keep the main action available during a response and explicitly label
        // its existing steer semantics instead of silently disabling Send.
        send.setEnabled(ready || !sending); send.setAlpha(ready ? 1f : .5f);
        send.setText(sending ? "提交中…" : active ? "发送追加" : "发送");
        steer.setEnabled(ready && active); interrupt.setEnabled(ready && active);
        if (sendStatus != null) sendStatus.setText(reason.isEmpty()
            ? active ? "Codex 正在回复，发送会追加到当前任务" : "可以发送新消息"
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
            messages.removeAllViews(); setButtons(false); lastRendered = ""; return;
        }
        selectChat.setText("切换聊天 · " + currentThread.optString("name", "桌面聊天"));
        status.setText(!state.optBoolean("online") ? "离线 · 显示本地保存记录 · " + SessionService.message
            : currentThread.optBoolean("ready") ? "已连接 · " + currentThread.optString("runtimeStatus")
            : "已连接 · 历史可读，桌面聊天尚未就绪");
        if (SessionService.message.contains("保存失败")) status.append(" · " + SessionService.message);
        setButtons(currentThread.optBoolean("ready"));
        String serialized = currentThread.toString();
        if (serialized.equals(lastRendered)) return;
        lastRendered = serialized;
        boolean atBottom = scroll.getChildAt(0).getHeight() - scroll.getHeight() - scroll.getScrollY() < dp(100);
        messages.removeAllViews();
        JSONArray turns = currentThread.getJSONArray("turns");
        for (int i = Math.max(0, turns.length() - 50); i < turns.length(); i++) {
            JSONObject turn = turns.getJSONObject(i);
            String input = turn.optString("input", "");
            if (!input.isEmpty()) bubble("你", input, true);
            JSONArray replies = turn.optJSONArray("agentMessages");
            if (replies != null) for (int j = 0; j < replies.length(); j++) bubble("Codex", replies.getString(j), false);
            JSONArray tools = turn.optJSONArray("toolResults");
            if (tools != null) for (int j = 0; j < tools.length(); j++) { JSONObject tool = tools.getJSONObject(j); messages.addView(text("↳ " + tool.optString("type") + " · " + tool.optString("status"), 12, muted)); }
        }
        JSONArray approvals = currentThread.optJSONArray("approvals");
        if (approvals != null) for (int i = 0; i < approvals.length(); i++) {
            JSONObject approval = approvals.getJSONObject(i);
            Button review = button(approval.optBoolean("submitting") ? "审批已提交 · 等待确认" : "查看并处理" + ("file".equals(approval.optString("kind")) ? "文件审批" : "命令审批"));
            review.setEnabled(!approval.optBoolean("submitting")); messages.addView(review);
            String approvalThreadId = currentThread.getString("id");
            review.setOnClickListener(view -> review(approval, approvalThreadId));
        }
        if (atBottom) scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }
    private void bubble(String who, String content, boolean user) {
        LinearLayout card = vertical(); card.setPadding(dp(14), dp(8), dp(14), dp(10));
        GradientDrawable shape = new GradientDrawable(); shape.setColor(user ? Color.rgb(37, 55, 49) : Color.rgb(31, 34, 37)); shape.setCornerRadius(dp(16)); card.setBackground(shape);
        card.addView(text(who, 12, user ? accent : muted)); card.addView(text(content, 16, foreground));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.setMargins(user ? dp(24) : 0, dp(8), user ? 0 : dp(12), dp(6)); messages.addView(card, params);
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
            String[] ids = rows.keySet().toArray(new String[0]), labels = rows.values().toArray(new String[0]);
            new AlertDialog.Builder(this).setTitle("选择电脑上的聊天").setItems(labels, (dialog, which) -> {
                selectedThreadId = ids[which]; lastRendered = "";
                try { render(currentState); } catch (Exception ignored) {}
                beginPolling();
            }).setNegativeButton("返回", null).show();
        } catch (Exception error) { toast("聊天列表读取失败"); }
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
        String id = intent.getStringExtra("threadId"); if (id != null && !id.isEmpty()) selectedThreadId = id;
        if (api != null) { lastRendered = ""; beginPolling(); }
    }
    private String error(Exception error) { String value = error.getMessage(); return value == null ? "连接失败，请检查地址、配对信息和网络" : value; }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
    @Override protected void onStart() {
        super.onStart(); visible = true;
        android.content.IntentFilter filter = new android.content.IntentFilter(SessionService.UPDATE);
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(updates, filter, RECEIVER_NOT_EXPORTED); else registerReceiver(updates, filter);
        if (api != null) beginPolling();
    }
    @Override protected void onStop() { visible = false; generation++; unregisterReceiver(updates); if (api != null) api.cancel(); sending = false; super.onStop(); }
    @Override protected void onDestroy() { generation++; if (api != null) api.cancel(); worker.shutdownNow(); super.onDestroy(); }
}
