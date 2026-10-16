package dev.codexlan.remote;

import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.os.Build;
import org.json.JSONObject;
import org.json.JSONArray;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owns background sync; the Activity renders this snapshot or the encrypted cache. */
public final class SessionService extends Service {
    static final String UPDATE = "dev.codexlan.remote.STATE_CHANGED";
    static volatile JSONObject latest;
    static volatile String message = "尚未连接";
    private volatile boolean stopped, started;
    private volatile String selection = "";
    private volatile long refreshVersion;
    private volatile RemoteApi api;
    private HistoryCache cache;
    private final ExecutorService catalogWorker=Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicBoolean catalogLoading=new java.util.concurrent.atomic.AtomicBoolean();
    private volatile JSONArray catalogRows=new JSONArray();
    private volatile long catalogFetchedAt;
    private volatile RemoteApi catalogApi;
    private ChatNotifications notifications;
    private boolean connectionAnnounced;
    private final ExecutorService worker = Executors.newFixedThreadPool(2);
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent == null || "stop".equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        try {
            long beforeRefresh=refreshVersion;
            if(started && "switch-address".equals(intent.getAction())) {
                JSONObject saved=new SecureSession(this).load();
                if(saved==null || !api.fingerprint.equals(saved.optString("fingerprint")))throw new Exception("切换地址需要相同电脑身份");
                RemoteApi replacement=new RemoteApi(saved.getString("address"),saved.getString("fingerprint"),saved.getString("token"));
                api=replacement;refreshVersion++;
                if("lan".equals(intent.getStringExtra("networkMode")))EasyTierVpnService.disconnect(this);
            }
            if (!started) {
                JSONObject connection = new SecureSession(this).load(); if (connection == null) { stopSelf(); return START_NOT_STICKY; }
                api = new RemoteApi(connection.getString("address"), connection.getString("fingerprint"), connection.getString("token"));
                cache = new HistoryCache(this, api.fingerprint);
                try { latest = MobileState.offline(cache.load()); } catch (Exception damaged) { latest = new JSONObject(); }
                latest.put("computer", api.fingerprint); selection = latest.optString("selectedThreadId");
                notifications = new ChatNotifications(this, api.fingerprint);
                if (Build.VERSION.SDK_INT >= 34) startForeground(8, notifications.background(), ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING);
                else startForeground(8, notifications.background());
                started = true; worker.execute(this::poll);
            }
            if (intent.hasExtra("threadId") && !intent.getStringExtra("threadId").equals(selection)) { selection = intent.getStringExtra("threadId"); refreshVersion++; }
            if ("refresh".equals(intent.getAction())) refreshVersion++;
            // A refresh wakes only the read poll, never an approval submission.
            if (beforeRefresh!=refreshVersion) cancelPoll();
            if ("approval".equals(intent.getAction())) {
                String payload = intent.getStringExtra("command");
                worker.execute(() -> {
                    try {
                        JSONObject command = new JSONObject(payload);
                        String key = UUID.nameUUIDFromBytes((api.fingerprint + payload).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
                        api.call("POST", "/v1/command", command, key); message = "审批已提交，等待电脑状态确认";
                    } catch (Exception error) { message = "审批未确认，请打开聊天查看：" + error.getMessage(); }
                    broadcast();
                });
            }
        } catch (Exception error) { message = "无法启动后台同步，请打开 App 重新连接"; stopSelf(); }
        return START_NOT_STICKY;
    }
    private void broadcast() { sendBroadcast(new Intent(UPDATE).setPackage(getPackageName())); }
    private final Object pollLock = new Object();
    private RemoteApi pollApi;
    private void cancelPoll(){synchronized(pollLock){if(pollApi!=null)pollApi.cancel();}}
    private void refreshCatalog(RemoteApi owner,long epoch){
        if(stopped||System.currentTimeMillis()-catalogFetchedAt<30000||!catalogLoading.compareAndSet(false,true))return;
        catalogWorker.execute(()->{RemoteApi channel=owner.readChannel();catalogApi=channel;try{
            JSONArray rows=channel.call("GET","/v1/threads",null,null).getJSONArray("threads");
            if(!stopped&&owner==api&&epoch==refreshVersion)catalogRows=rows;
        }catch(Exception ignored){}finally{if(owner==api&&epoch==refreshVersion)catalogFetchedAt=System.currentTimeMillis();channel.cancel();catalogApi=null;catalogLoading.set(false);}});
    }
    private void poll() {
        String version = "", opened = ""; long savedAt = 0, refreshed = refreshVersion;
        JSONArray cachedCatalog=latest.optJSONArray("catalog");if(cachedCatalog!=null)catalogRows=cachedCatalog;
        while (!stopped) {
            long epoch=refreshVersion;String requestedSelection=selection;RemoteApi owner=api;
            RemoteApi polling=null;
            try {
                polling=owner.readChannel();
                synchronized(pollLock){pollApi=polling;}
                String desired = selection == null ? "" : selection;
                if (refreshed != refreshVersion) { refreshed = refreshVersion; version = ""; opened = ""; catalogFetchedAt = 0; }
                if (!desired.isEmpty() && !desired.equals(opened)) {
                    polling.call("POST", "/v1/select", new JSONObject().put("threadId", desired), null); opened = desired; version = "";
                }
                if(MobileState.pollObsolete(epoch,refreshVersion,requestedSelection,selection,owner==api))continue;
                JSONObject incoming = polling.call("GET", "/v1/state?delta=1" + (version.isEmpty() ? "" : "&after=" + version), null, null);
                if(MobileState.pollObsolete(epoch,refreshVersion,requestedSelection,selection,owner==api))continue;
                version = String.valueOf(incoming.getLong("version"));
                JSONObject previous = latest, merged = MobileState.merge(previous, incoming);
                if(MobileState.pollObsolete(epoch,refreshVersion,requestedSelection,selection,owner==api))continue;
                if (selection == null || selection.isEmpty()) {
                    JSONArray threads = merged.optJSONArray("threads"); if (threads != null && threads.length() > 0) selection = threads.getJSONObject(0).getString("id");
                }
                merged.put("selectedThreadId", selection).put("catalog", catalogRows).put("online", true).put("computer", api.fingerprint);
                notifications.update(previous, merged); latest = merged; message = "已连接";
                if (MobileState.alerts(previous, merged).length() > 0 || System.currentTimeMillis() - savedAt > 2000) {
                    try { cache.save(merged); savedAt = System.currentTimeMillis(); }
                    catch (Exception cacheError) { message = "已连接，但本地历史保存失败"; }
                }
                // One brief success notice per sync session; polling/reconnection never reposts it.
                if (!connectionAnnounced) { connectionAnnounced = true; notifications.connected(); }
                broadcast();refreshCatalog(owner,epoch);
            } catch (Exception error) {
                if (stopped) break;
                if(MobileState.pollObsolete(epoch,refreshVersion,requestedSelection,selection,owner==api)){version="";opened="";continue;}
                try { latest = MobileState.offline(latest); notifications.disconnected(); } catch (Exception ignored) {}
                message = "连接中断，正在重连"; broadcast();
                try { Thread.sleep(2000); } catch (InterruptedException ignored) { break; }
                version = ""; opened = "";
            } finally { synchronized(pollLock){if(pollApi==polling)pollApi=null;}if(polling!=null)polling.cancel(); }
        }
    }
    @Override public void onDestroy() {
        stopped = true;cancelPoll(); if (api != null) api.cancel(); if (notifications != null) notifications.disconnected();
        try { if (latest != null) latest = MobileState.offline(latest); if (cache != null && latest != null) cache.save(latest); } catch (Exception ignored) {}
        if(catalogApi!=null)catalogApi.cancel();catalogWorker.shutdownNow();worker.shutdownNow(); stopForeground(STOP_FOREGROUND_REMOVE); message = "后台同步已停止"; broadcast(); super.onDestroy();
    }
}
