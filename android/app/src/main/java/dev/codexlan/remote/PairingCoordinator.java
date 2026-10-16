package dev.codexlan.remote;

import android.content.Context;
import android.content.Intent;
import org.json.JSONObject;

/** Pairing survives Activity recreation; approved requests are encrypted locally. */
final class PairingCoordinator {
    static final String UPDATE="dev.codexlan.remote.PAIRING_CHANGED";
    private static final java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final java.util.concurrent.atomic.AtomicBoolean busy=new java.util.concurrent.atomic.AtomicBoolean();
    private static volatile String message="";
    private static volatile boolean failed;
    static boolean busy(){return busy.get();}
    static JSONObject pending(Context context) throws Exception {return new SecureSession(context,"pending-pairing").load();}
    static String message(){return message;}
    static boolean failed(){return failed;}
    static void prepare(Context context,PairingInfo info) throws Exception {
        if(busy())throw new Exception("正在完成配对，请稍候");
        new SecureSession(context,"pending-pairing").save(new JSONObject().put("request",info.json()).put("phase","permission"));failed=false;
    }
    static void discard(Context context){if(!busy()){new SecureSession(context,"pending-pairing").clear();message="";failed=false;}}
    private static void announce(Context context,String value){message=value;context.sendBroadcast(new Intent(UPDATE).setPackage(context.getPackageName()));}
    static void permissionDenied(Context context){failed=true;announce(context,"未授予 VPN 权限，点击继续配对可重试；组网配置尚未导入");}
    static void start(Context activity){
        Context context=activity.getApplicationContext();if(!busy.compareAndSet(false,true))return;
        try {
            JSONObject pending=pending(context);if(pending==null)throw new Exception("请重新扫描电脑配对码");
            if("submitting".equals(pending.optString("phase")))throw new Exception("上次配对结果未确认，请重新扫码；不会自动重复提交");
            PairingInfo info=PairingInfo.parse(pending.getJSONObject("request").toString(),System.currentTimeMillis());
            if(info.easytier==null)throw new Exception("二维码缺少组网参数，请重新扫码");
            SecureSession settings=new SecureSession(context,"easytier");JSONObject prior=settings.load();boolean same=prior!=null;
            for(String key:new String[]{"name","secret","cidr","networkCidr","peers"})same &= prior!=null&&prior.optString(key).equals(info.easytier.optString(key));
            same &= prior!=null&&prior.optBoolean("dhcp")==info.easytier.optBoolean("dhcp");
            if(EasyTierVpnService.running&&!same)throw new Exception("请先断开当前 EasyTier，再导入新网络");
            if(!EasyTierVpnService.running){settings.save(info.easytier);activity.startForegroundService(new Intent(activity,EasyTierVpnService.class));}
            pending.put("phase","waiting");new SecureSession(context,"pending-pairing").save(pending);
            failed=false;announce(context,"正在加入 EasyTier 网络…");
            worker.execute(()->{
                try {
                    long until=android.os.SystemClock.uptimeMillis()+65000;
                    while(!(EasyTierVpnService.running&&EasyTierVpnService.state.startsWith("虚拟接口"))&&android.os.SystemClock.uptimeMillis()<until)Thread.sleep(200);
                    if(!(EasyTierVpnService.running&&EasyTierVpnService.state.startsWith("虚拟接口")))throw new Exception("组网未就绪，请在设置中检查节点和网络参数");
                    announce(context,"已建立组网，正在等待电脑连接…");
                    RemoteApi candidate=new RemoteApi(info.address,info.fingerprint,null);
                    JSONObject result=PairingHandshake.run((method,path,body)->candidate.call(method,path,body,null,"GET".equals(method)?2500:8000,"GET".equals(method)?2500:30000),
                        new JSONObject().put("code",info.code).put("name",android.os.Build.MODEL),android.os.SystemClock::uptimeMillis,Thread::sleep,()->{
                            pending.put("phase","submitting");new SecureSession(context,"pending-pairing").save(pending);announce(context,"正在验证电脑并完成配对…");
                        });
                    String token=result.getString("token");if(token.isEmpty())throw new Exception("电脑未返回有效配对凭据");
                    new SecureSession(context).save(new JSONObject().put("address",info.address).put("fingerprint",info.fingerprint).put("token",token).put("addresses",new JSONObject().put("easytier",info.address)));
                    context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).edit().putString("networkMode","easytier").apply();
                    new SecureSession(context,"pending-pairing").clear();failed=false;announce(context,"配对成功，正在进入对话…");
                }catch(Exception error){failed=true;announce(context,error.getMessage()==null?"配对未完成，请重试或重新扫码":error.getMessage());}
                finally{busy.set(false);context.sendBroadcast(new Intent(UPDATE).setPackage(context.getPackageName()));}
            });
        }catch(Exception error){busy.set(false);failed=true;announce(context,error.getMessage()==null?"配对未完成，请重新扫码":error.getMessage());}
    }
}
