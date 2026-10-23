package dev.codexlan.remote;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Set;

final class ChatNotifications {
    static String resultChannel(Context context) {
        NotificationChannel old = context.getSystemService(NotificationManager.class).getNotificationChannel("chat_result");
        // Preserve an explicit user choice; only migrate the former default.
        if (old != null && (old.getImportance() == NotificationManager.IMPORTANCE_NONE
            || (Build.VERSION.SDK_INT >= 29 && old.hasUserSetImportance()))) return "chat_result";
        return "chat_result_alerts";
    }
    private final Context context;
    private final NotificationManager manager;
    private final String computer;
    private final Set<String> posted = new HashSet<>(), approvalTags = new HashSet<>();
    ChatNotifications(Context context, String fingerprint) {
        this.context = context; computer = fingerprint; manager = context.getSystemService(NotificationManager.class);
        NotificationChannel background = new NotificationChannel("chat_connection", "后台同步运行提示（可关闭）", NotificationManager.IMPORTANCE_LOW);
        background.setDescription("可单独关闭此类别，保留连接成功、审批和任务完成提醒。");
        background.setShowBadge(false);
        background.setSound(null, null);
        background.enableVibration(false);
        manager.createNotificationChannel(background);
        NotificationChannel connected = new NotificationChannel("chat_connected", "连接成功（短暂显示）", NotificationManager.IMPORTANCE_LOW);
        connected.setShowBadge(false);
        manager.createNotificationChannel(connected);
        manager.createNotificationChannel(new NotificationChannel("chat_approval", "需要审批", NotificationManager.IMPORTANCE_HIGH));
        NotificationChannel results = new NotificationChannel(resultChannel(context), "任务完成或失败（横幅提醒）", NotificationManager.IMPORTANCE_HIGH);
        results.setDescription("任务结束时提醒。小米手机请同时开启应用和此类别的悬浮通知、锁屏通知。");
        manager.createNotificationChannel(results);
        for (android.service.notification.StatusBarNotification notification : manager.getActiveNotifications())
            if (notification.getTag() != null && notification.getTag().startsWith("approval:")) manager.cancel(notification.getTag(), 1);
    }
    private PendingIntent open(String threadId) {
        Intent intent = new Intent(context, MainActivity.class).putExtra("threadId", threadId)
            .setData(Uri.parse("codexlan://chat/" + computer + "/" + threadId)).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
    Notification background() {
        PendingIntent stop = PendingIntent.getService(context, 0, new Intent(context, SessionService.class).setAction("stop"), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(context, "chat_connection").setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Codex LAN · 后台同步").setContentText("接收任务和审批提醒；可在 App 的通知设置中隐藏此提示")
            .setOngoing(false).setOnlyAlertOnce(true).setShowWhen(false).setContentIntent(open(""))
            .addAction(new Notification.Action.Builder(null, "停止同步", stop).build()).build();
    }
    void connected() {
        manager.notify("connected:" + computer, 1, new Notification.Builder(context, "chat_connected")
            .setSmallIcon(android.R.drawable.stat_notify_chat).setContentTitle("已连接电脑")
            .setContentText("可以继续聊天，并接收任务和审批提醒").setContentIntent(open(""))
            .setAutoCancel(true).setTimeoutAfter(4000).setOnlyAlertOnce(true).setShowWhen(false).build());
    }
    void disconnected() {
        for (String tag : approvalTags) { manager.cancel(tag, 1); posted.remove(tag); } approvalTags.clear();
    }
    void update(JSONObject previous, JSONObject current) throws Exception {
        if (!manager.areNotificationsEnabled()) { disconnected(); return; }
        JSONArray alerts = MobileState.alerts(previous, current); Set<String> active = new HashSet<>();
        for (int i = 0; i < alerts.length(); i++) {
            JSONObject alert = alerts.getJSONObject(i); String thread = alert.getString("threadId");
            if (alert.getString("kind").equals("result")) {
                String tag = "result:" + computer + ":" + thread + ":" + alert.getString("turnId"); if (!posted.add(tag)) continue;
                manager.notify(tag, 1, new Notification.Builder(context, resultChannel(context)).setSmallIcon(android.R.drawable.stat_notify_chat)
                    .setContentTitle("quota".equals(alert.optString("failureReason"))?"Codex 额度不足，任务已中断":"rateLimit".equals(alert.optString("failureReason"))?"Codex 请求受限，任务已中断":"contextLimit".equals(alert.optString("failureReason"))?"Codex 上下文已满，任务已中断":alert.getString("status").equals("completed") ? "Codex 任务已完成" : "Codex 任务失败")
                    .setContentText("quota".equals(alert.optString("failureReason"))?"请检查账号额度或服务商余额，点击查看聊天":"点击查看对应聊天").setContentIntent(open(thread)).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).build());
            } else {
                JSONObject request = alert.getJSONObject("request"); String tag = "approval:" + computer + ":" + thread + ":" + request.optString("turnId") + ":" + request.get("id"); active.add(tag);
                if (!posted.add(tag)) continue;
                String details = request.optString("command", "");
                if (request.optString("kind").equals("file")) details = "文件变更：" + request.optJSONArray("changes");
                boolean canQuickApprove = MobileState.canQuickApprove(request, details);
                Notification.Builder builder = new Notification.Builder(context, "chat_approval").setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle("Codex 需要审批 · " + (request.optString("kind").equals("file") ? "文件修改" : "命令执行"))
                    .setContentText(details.length() > 140 ? details.substring(0, 140) + "…" : details)
                    .setStyle(new Notification.BigTextStyle().bigText(details.length() > 1800 ? details.substring(0, 1800) + "…请进入聊天查看完整内容" : details))
                    .setContentIntent(open(thread)).setVisibility(Notification.VISIBILITY_PRIVATE).setOnlyAlertOnce(true);
                if (Build.VERSION.SDK_INT >= 29) builder.setAllowSystemGeneratedContextualActions(false);
                JSONArray decisions = request.getJSONArray("decisions");
                for (int j = 0; j < decisions.length(); j++) {
                    String decision = decisions.getString(j);
                    if (decision.equals("accept") && !canQuickApprove) continue;
                    String label = decision.equals("accept") ? "允许一次" : decision.equals("cancel") ? "取消任务" : "拒绝";
                    JSONObject payload = new JSONObject().put("op", "approve").put("threadId", thread).put("requestId", request.get("id"))
                        .put("decision", decision).put("turnId", request.optString("turnId"));
                    Intent action = new Intent(context, SessionService.class).setAction("approval").putExtra("command", payload.toString())
                        .setData(Uri.parse("codexlan://approval/" + computer + "/" + thread + "/" + Uri.encode(request.get("id").toString()) + "/" + decision));
                    PendingIntent pending = PendingIntent.getForegroundService(context, 0, action, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                    Notification.Action.Builder button = new Notification.Action.Builder(null, label, pending);
                    if (Build.VERSION.SDK_INT >= 31) button.setAuthenticationRequired(true);
                    builder.addAction(button.build());
                }
                manager.notify(tag, 1, builder.build());
            }
        }
        for (String tag : approvalTags) if (!active.contains(tag)) manager.cancel(tag, 1);
        approvalTags.clear(); approvalTags.addAll(active);
        if (posted.size() > 2000) { posted.clear(); posted.addAll(approvalTags); }
    }
}
