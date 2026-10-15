package dev.codexlan.remote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import com.easytier.jni.EasyTierJNI;
import org.json.JSONObject;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class EasyTierVpnService extends VpnService {
    public static volatile String state = "未连接";
    public static volatile boolean running;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private ParcelFileDescriptor tun;
    private boolean nativeStarted;
    private volatile boolean stopping;
    private EasyTierProfile profile;
    private long startedAt;
    private String notifiedState = "";

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || "stop".equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        if (running) return START_NOT_STICKY;
        running = true; state = "正在启动 EasyTier…";
        NotificationManager notifications = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel("easytier", "EasyTier 运行提示（可关闭）", NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false); channel.setSound(null, null); channel.enableVibration(false);
        notifications.createNotificationChannel(channel);
        if (Build.VERSION.SDK_INT >= 34) startForeground(7, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(7, notification());
        worker.execute(() -> {
            try {
                JSONObject saved = new SecureSession(this, "easytier").load();
                if (saved == null) throw new Exception();
                profile = new EasyTierProfile(saved.getString("name"), saved.optString("secret"), saved.getString("cidr"), saved.getString("peers"));
                if (stopping) return;
                // Never log config or native error strings: they may contain network credentials.
                if (EasyTierJNI.INSTANCE.parseConfig(profile.toml()) != 0 || EasyTierJNI.INSTANCE.runNetworkInstance(profile.toml()) != 0) throw new Exception();
                nativeStarted = true; startedAt = System.currentTimeMillis(); state = "正在等待虚拟网络…";
                worker.scheduleWithFixedDelay(this::monitor, 0, 3, TimeUnit.SECONDS);
            } catch (Throwable error) { state = "EasyTier 启动失败，请检查配置和原生库兼容性"; stopSelf(); }
        });
        return START_NOT_STICKY;
    }
    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, EasyTierVpnService.class).setAction("stop"), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "easytier").setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("Codex LAN · EasyTier").setContentText(state).setContentIntent(open)
            .setOngoing(false).setOnlyAlertOnce(true).setShowWhen(false)
            .addAction(new Notification.Action.Builder(null, "断开组网", stop).build()).build();
    }
    private void monitor() {
        if (stopping) return;
        try {
            JSONObject map = new JSONObject(EasyTierJNI.INSTANCE.collectNetworkInfos()).optJSONObject("map");
            JSONObject instance = map == null ? null : map.optJSONObject(EasyTierProfile.INSTANCE);
            if (instance == null || !instance.optBoolean("running")) {
                if (System.currentTimeMillis() - startedAt > 30000) { state = "EasyTier 实例未就绪，请检查配置"; stopSelf(); }
                return;
            }
            JSONObject node = instance.optJSONObject("my_node_info");
            if (node == null || node.optJSONObject("virtual_ipv4") == null) return;
            if (tun == null) {
                Builder builder = new Builder().setSession("Codex LAN EasyTier").setMtu(1380).setBlocking(false)
                    .addAddress(profile.address, profile.prefix).addRoute(profile.subnet, profile.prefix);
                if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false);
                tun = builder.establish(); if (tun == null) throw new Exception();
                // Transfer a duplicate to the native core; keep our own FD for service cleanup.
                int fd = ParcelFileDescriptor.dup(tun.getFileDescriptor()).detachFd();
                boolean handedOff = false;
                try { if (EasyTierJNI.INSTANCE.setTunFd(EasyTierProfile.INSTANCE, fd) != 0) throw new Exception(); handedOff = true; }
                finally { if (!handedOff) ParcelFileDescriptor.adoptFd(fd).close(); }
            }
            int peers = instance.optJSONArray("routes") == null ? 0 : instance.optJSONArray("routes").length();
            state = "虚拟接口 " + profile.address + "/" + profile.prefix + " · 路由节点 " + peers;
            if (!state.equals(notifiedState)) {
                notifiedState = state;
                getSystemService(NotificationManager.class).notify(7, notification());
            }
        } catch (Throwable error) { state = "虚拟网络连接异常，请检查配置后重连"; stopSelf(); }
    }
    @Override public void onRevoke() { state = "VPN 授权已撤销"; stopSelf(); }
    @Override public void onDestroy() {
        stopping = true; running = false;
        worker.execute(() -> {
            try { if (nativeStarted) EasyTierJNI.INSTANCE.retainNetworkInstance(null); } catch (Throwable ignored) {}
            try { if (tun != null) tun.close(); } catch (Exception ignored) {}
            tun = null; nativeStarted = false;
        });
        worker.shutdown(); stopForeground(STOP_FOREGROUND_REMOVE);
        if (state.startsWith("虚拟接口") || state.startsWith("正在")) state = "已断开";
        super.onDestroy();
    }
}
