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
    private static volatile EasyTierVpnService instance;
    private ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private ParcelFileDescriptor tun;
    private boolean nativeStarted;
    private volatile boolean stopping;
    private volatile boolean cleanupComplete;
    private EasyTierProfile profile;
    private long startedAt;
    private String notifiedState = "";
    private String tunCidr = "";

    @Override public void onCreate(){super.onCreate();instance=this;}

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || "stop".equals(intent.getAction())) { stopNetwork("已断开"); return START_NOT_STICKY; }
        if(stopping){
            if(!cleanupComplete)return START_NOT_STICKY;
            // The system may retain its VPN binding after disconnection.
            worker=Executors.newSingleThreadScheduledExecutor();stopping=false;cleanupComplete=false;
        }
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
                boolean dhcp = saved.optBoolean("dhcp");
                profile = new EasyTierProfile(saved.getString("name"), saved.optString("secret"), saved.getString(dhcp?"networkCidr":"cidr"), saved.getString("peers"),dhcp);
                if (stopping) return;
                // Never log config or native error strings: they may contain network credentials.
                if (EasyTierJNI.INSTANCE.parseConfig(profile.toml()) != 0 || EasyTierJNI.INSTANCE.runNetworkInstance(profile.toml()) != 0) throw new Exception();
                nativeStarted = true; startedAt = System.currentTimeMillis(); state = "正在等待虚拟网络…";
                worker.scheduleWithFixedDelay(this::monitor, 0, 3, TimeUnit.SECONDS);
            } catch (Throwable error) { stopNetwork("EasyTier 启动失败，请检查配置和原生库兼容性"); }
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
                if (System.currentTimeMillis() - startedAt > 30000) { stopNetwork("EasyTier 实例未就绪，请检查配置"); }
                return;
            }
            JSONObject node = instance.optJSONObject("my_node_info");
            if (node == null || node.optJSONObject("virtual_ipv4") == null) {
                state=profile.dhcp?"正在自动分配虚拟 IP…":"正在等待虚拟地址…";
                if(System.currentTimeMillis()-startedAt>60000)stopNetwork("未获得虚拟地址，请检查网络节点及电脑是否在线");
                return;
            }
            EasyTierProfile assigned=profile.assigned(node.getJSONObject("virtual_ipv4"));
            String cidr=assigned.address+"/"+assigned.prefix;
            if (tun == null || !cidr.equals(tunCidr)) {
                Builder builder = new Builder().setSession("Codex LAN EasyTier").setMtu(1380).setBlocking(false)
                    .addAddress(assigned.address, assigned.prefix).addRoute(assigned.subnet, assigned.prefix);
                if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false);
                ParcelFileDescriptor previous=tun;
                tun = builder.establish(); if (tun == null) {tun=previous;throw new Exception();}
                // EasyTier borrows this FD (close_fd_on_drop=false). Java owns it.
                // A detached duplicate would survive both native shutdown and tun.close().
                try { if (EasyTierJNI.INSTANCE.setTunFd(EasyTierProfile.INSTANCE, tun.getFd()) != 0) throw new Exception(); }
                finally { if(previous!=null)previous.close(); }
                tunCidr=cidr;
            }
            int peers = instance.optJSONArray("routes") == null ? 0 : instance.optJSONArray("routes").length();
            state = "虚拟接口 " + cidr + (profile.dhcp?" · DHCP":"") + " · 路由节点 " + peers;
            if (!state.equals(notifiedState)) {
                notifiedState = state;
                getSystemService(NotificationManager.class).notify(7, notification());
            }
        } catch (Throwable error) { stopNetwork("虚拟网络连接异常，请检查配置后重连"); }
    }
    public static void disconnect(android.content.Context context){
        EasyTierVpnService service=instance;
        if(service!=null)service.stopNetwork("已断开");
        else context.stopService(new Intent(context,EasyTierVpnService.class));
    }
    private synchronized void stopNetwork(String finalState){
        if(stopping)return;stopping=true;running=false;
        // Android binds active VPN services. Release the native-owned duplicate
        // and our TUN before stopSelf, otherwise onDestroy may never be called.
        worker.execute(()->{
            try{if(nativeStarted)EasyTierJNI.INSTANCE.retainNetworkInstance(null);}catch(Throwable ignored){}
            try{if(tun!=null)tun.close();}catch(Exception ignored){}
            tun=null;tunCidr="";nativeStarted=false;state=finalState;
            new android.os.Handler(getMainLooper()).post(()->{stopForeground(STOP_FOREGROUND_REMOVE);cleanupComplete=true;stopSelf();});
        });
        worker.shutdown();
    }
    @Override public void onRevoke(){stopNetwork("VPN 授权已撤销");}
    @Override public void onDestroy(){
        stopNetwork(state.startsWith("虚拟接口")||state.startsWith("正在")?"已断开":state);
        if(instance==this)instance=null;
        super.onDestroy();
    }
}
