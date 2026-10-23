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
    static final String USAGE_UPDATE = "dev.codexlan.remote.USAGE_CHANGED";
    static volatile JSONObject usage;
    static volatile String usageComputer="";
    private final ExecutorService usageWorker=Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicBoolean usageLoading=new java.util.concurrent.atomic.AtomicBoolean();
    private volatile long usageFetchedAt;
    private volatile RemoteApi usageApi;
    static volatile JSONObject latest;
    static volatile String message = "尚未连接";
    private volatile boolean stopped, started;
    private volatile String selection = "";
    private volatile String recoveredSelectionFrom = "";
    private volatile long refreshVersion;
    private volatile RemoteApi api;
    private HistoryCache cache;
    private final ExecutorService catalogWorker=Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicBoolean catalogLoading=new java.util.concurrent.atomic.AtomicBoolean();
    private volatile JSONArray catalogRows=new JSONArray();
    private volatile long catalogFetchedAt;
    private volatile RemoteApi catalogApi;
    private volatile long lastFailover;
    private volatile long foregroundSyncedEpoch=-1;private volatile String foregroundSyncedSelection="";
    private final Object stateLock=new Object();
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
                if(!api.fingerprint.equals(usageComputer)){usage=null;usageComputer=api.fingerprint;}
                cache = new HistoryCache(this, api.fingerprint);
                try { latest = MobileState.offline(cache.load()); } catch (Exception damaged) { latest = new JSONObject(); }
                latest.put("computer", api.fingerprint); selection = latest.optString("selectedThreadId");
                notifications = new ChatNotifications(this, api.fingerprint);
                if (Build.VERSION.SDK_INT >= 34) startForeground(8, notifications.background(), ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING);
                else startForeground(8, notifications.background());
                started = true; worker.execute(this::poll);
            }
            if (intent.hasExtra("threadId") && !intent.getStringExtra("threadId").equals(selection)) { selection = intent.getStringExtra("threadId");recoveredSelectionFrom=""; refreshVersion++; }
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
    private RemoteApi rememberedLanApi;
    private void rememberConnectedLan(RemoteApi owner){
        if(rememberedLanApi==owner||!"lan".equals(getSharedPreferences("MainActivity",MODE_PRIVATE).getString("networkMode","lan")))return;
        try{SecureSession storage=new SecureSession(this);JSONObject saved=storage.load();if(saved!=null&&owner.fingerprint.equals(saved.optString("fingerprint"))&&owner.address.equals(saved.optString("address"))){storage.save(ConnectionHistory.remember(saved,owner.address,"lan"));rememberedLanApi=owner;}}catch(Exception ignored){}
    }
    private void cancelPoll(){synchronized(pollLock){if(pollApi!=null)pollApi.cancel();}}
    private boolean selectedSynchronized(){
        if(foregroundSyncedEpoch!=refreshVersion||!foregroundSyncedSelection.equals(selection))return false;
        JSONArray threads=latest==null?null:latest.optJSONArray("threads");if(threads==null||selection==null||selection.isEmpty())return false;
        for(int i=0;i<threads.length();i++){JSONObject row=threads.optJSONObject(i);if(row!=null&&selection.equals(row.optString("id")))return row.optBoolean("historyReady");}return false;
    }
    private boolean tryRememberedAddress(RemoteApi owner){
        if(!"lan".equals(getSharedPreferences("MainActivity",MODE_PRIVATE).getString("networkMode","lan"))||System.currentTimeMillis()-lastFailover<10000)return false;
        lastFailover=System.currentTimeMillis();try{SecureSession session=new SecureSession(this);JSONObject saved=session.load();if(saved==null||!owner.fingerprint.equals(saved.optString("fingerprint")))return false;
            ConnectionHistory.remember(saved,owner.address,"lan");
            for(String address:ConnectionHistory.addresses(saved)){if(address.equals(owner.address)||stopped||owner!=api)continue;RemoteApi candidate=new RemoteApi(address,owner.fingerprint,owner.token);
                try{candidate.call("GET","/v1/threads",null,null,1500,3500);if(stopped||owner!=api)return false;JSONObject current=session.load();if(current==null||!owner.fingerprint.equals(current.optString("fingerprint")))return false;
                    current.put("address",candidate.address);JSONObject profiles=current.optJSONObject("addresses");if(profiles==null)profiles=new JSONObject();profiles.put("lan",candidate.address);current.put("addresses",profiles);session.save(ConnectionHistory.remember(current,candidate.address,"lan"));api=candidate;refreshVersion++;message="已自动切换到历史局域网地址";broadcast();return true;
                }catch(Exception ignored){}finally{candidate.cancel();}
            }
        }catch(Exception ignored){}return false;
    }
    private void refreshCatalog(RemoteApi owner,long epoch){
        if(stopped||System.currentTimeMillis()-catalogFetchedAt<30000||!catalogLoading.compareAndSet(false,true))return;
        catalogWorker.execute(()->{RemoteApi channel=owner.readChannel();catalogApi=channel;try{
            JSONArray rows=channel.call("GET","/v1/threads",null,null).getJSONArray("threads");
            if(!stopped&&owner==api){
                catalogRows=rows;
                synchronized(stateLock){latest=new JSONObject(latest.toString()).put("catalog",rows);}
                if((selection==null||selection.isEmpty())&&rows.length()>0){selection=rows.getJSONObject(0).getString("id");refreshVersion++;cancelPoll();}
                broadcast();
                for(int i=0;i<rows.length()&&!stopped&&owner==api&&epoch==refreshVersion&&selectedSynchronized();i++){
                    JSONObject row=rows.getJSONObject(i);String id=row.getString("id");JSONObject cached=null;JSONArray chats=latest.optJSONArray("threads");
                    if(chats!=null)for(int j=0;j<chats.length();j++)if(id.equals(chats.getJSONObject(j).optString("id"))){cached=chats.getJSONObject(j);break;}
                    if(cached!=null&&(id.equals(selection)&&cached.optBoolean("ready")||cached.optBoolean("historyReady")&&cached.optLong("historyUpdatedAt",-1)==row.optLong("updatedAt")))continue;
                    try{
                        JSONObject history=channel.call("GET","/v1/history?threadId="+java.net.URLEncoder.encode(id,"UTF-8"),null,null).getJSONObject("thread");
                        if(stopped||owner!=api||epoch!=refreshVersion)break;
                        synchronized(stateLock){latest=MobileState.mergeHistory(latest,history).put("catalog",rows);}
                        if(i%5==0){cache.save(latest);broadcast();}
                    }catch(Exception unavailable){if(unavailable.getMessage()!=null&&unavailable.getMessage().contains("不支持"))break;}
                }
                if(!stopped&&owner==api){cache.save(latest);broadcast();}
            }
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
                    try{polling.call("POST", "/v1/select", new JSONObject().put("threadId", desired), null);opened=desired;}
                    catch(RemoteApi.ApiException error){
                        if(error.status!=404)throw error;
                        JSONArray available=polling.call("GET","/v1/threads",null,null).getJSONArray("threads");
                        if(MobileState.pollObsolete(epoch,refreshVersion,requestedSelection,selection,owner==api))continue;
                        String replacement=MobileState.replacementSelection(available,desired);catalogRows=available;recoveredSelectionFrom=desired;selection=replacement;
                        refreshVersion++;synchronized(stateLock){latest=new JSONObject(latest.toString()).put("catalog",available).put("selectedThreadId",replacement).put("recoveredSelectionFrom",desired).put("online",true);}cache.save(latest);broadcast();version="";opened="";continue;
                    }
                    version = "";
                }
                if(MobileState.pollObsolete(epoch,refreshVersion,requestedSelection,selection,owner==api))continue;
                JSONObject incoming = polling.call("GET", "/v1/state?delta=1" + (version.isEmpty() ? "" : "&after=" + version), null, null);
                if(MobileState.pollObsolete(epoch,refreshVersion,requestedSelection,selection,owner==api))continue;
                version = String.valueOf(incoming.getLong("version"));
                JSONObject previous,merged;
                synchronized(stateLock){if(MobileState.pollObsolete(epoch,refreshVersion,requestedSelection,selection,owner==api))continue;previous=latest;merged=MobileState.merge(previous,incoming);
                    if((selection==null||selection.isEmpty())&&recoveredSelectionFrom.isEmpty()){JSONArray threads=merged.optJSONArray("threads");if(threads!=null&&threads.length()>0)selection=threads.getJSONObject(0).getString("id");}
                    merged.put("selectedThreadId",selection).put("recoveredSelectionFrom",recoveredSelectionFrom).put("catalog",catalogRows).put("online",true).put("computer",api.fingerprint);latest=merged;}
                JSONArray arrived=incoming.optJSONArray("threads");if(arrived!=null)for(int i=0;i<arrived.length();i++){JSONObject row=arrived.getJSONObject(i);if(selection.equals(row.optString("id"))&&row.optBoolean("historyReady")){if(foregroundSyncedEpoch!=epoch||!selection.equals(foregroundSyncedSelection))catalogFetchedAt=0;foregroundSyncedEpoch=epoch;foregroundSyncedSelection=selection;break;}}
                notifications.update(previous, merged); message = "已连接";rememberConnectedLan(owner);
                if (MobileState.alerts(previous, merged).length() > 0 || System.currentTimeMillis() - savedAt > 2000) {
                    try { cache.save(latest); savedAt = System.currentTimeMillis(); }
                    catch (Exception cacheError) { message = "已连接，但本地历史保存失败"; }
                }
                // One brief success notice per sync session; polling/reconnection never reposts it.
                if (!connectionAnnounced) { connectionAnnounced = true; notifications.connected(); }
                broadcast();refreshCatalog(owner,epoch);refreshUsage(owner);
            } catch (Exception error) {
                if (stopped) break;
                if(MobileState.pollObsolete(epoch,refreshVersion,requestedSelection,selection,owner==api)){version="";opened="";continue;}
                if(tryRememberedAddress(owner)){version="";opened="";continue;}
                try { synchronized(stateLock){latest=MobileState.offline(latest);}notifications.disconnected(); } catch (Exception ignored) {}
                message = "连接中断，正在重连"; broadcast();
                try { Thread.sleep(2000); } catch (InterruptedException ignored) { break; }
                version = ""; opened = "";
            } finally { synchronized(pollLock){if(pollApi==polling)pollApi=null;}if(polling!=null)polling.cancel(); }
        }
    }
    private void refreshUsage(RemoteApi owner){
        if(stopped||System.currentTimeMillis()-usageFetchedAt<60000||!usageLoading.compareAndSet(false,true))return;
        usageWorker.execute(()->{RemoteApi channel=owner.readChannel();usageApi=channel;try{
            JSONObject value=channel.call("GET","/v1/usage",null,null);
            if(!stopped&&owner==api){usage=value;usageComputer=owner.fingerprint;sendBroadcast(new Intent(USAGE_UPDATE).setPackage(getPackageName()));}
        }catch(Exception ignored){if(!stopped&&owner==api){if(usage!=null)try{usage=new JSONObject(usage.toString()).put("stale",true);}catch(Exception unused){}sendBroadcast(new Intent(USAGE_UPDATE).setPackage(getPackageName()));} }
        finally{if(owner==api)usageFetchedAt=System.currentTimeMillis();channel.cancel();usageApi=null;usageLoading.set(false);}});
    }
    @Override public void onDestroy() {
        stopped = true;cancelPoll(); if (api != null) api.cancel(); if (notifications != null) notifications.disconnected();
        try { if (latest != null) latest = MobileState.offline(latest); if (cache != null && latest != null) cache.save(latest); } catch (Exception ignored) {}
        if(usageApi!=null)usageApi.cancel();usageWorker.shutdownNow();if(catalogApi!=null)catalogApi.cancel();catalogWorker.shutdownNow();worker.shutdownNow(); stopForeground(STOP_FOREGROUND_REMOVE); message = "后台同步已停止"; broadcast(); super.onDestroy();
    }
}
