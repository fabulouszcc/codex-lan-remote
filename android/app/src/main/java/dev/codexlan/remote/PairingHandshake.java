package dev.codexlan.remote;

import org.json.JSONObject;

/** Wait for a verified peer; never automatically replay a pairing POST. */
final class PairingHandshake {
    interface Transport { JSONObject call(String method,String path,JSONObject body) throws Exception; }
    interface Clock { long now(); }
    interface Pause { void sleep(long millis) throws Exception; }
    interface BeforeSubmit { void run() throws Exception; }
    static JSONObject run(Transport transport,JSONObject body,Clock clock,Pause pause,BeforeSubmit beforeSubmit) throws Exception {
        long deadline=clock.now()+30000;int attempts=0;
        while(true){
            try {
                JSONObject info=transport.call("GET","/v1/info",null);
                if(info.optInt("protocol")!=1)throw new Exception("电脑协议不兼容，请更新电脑插件");
                if(!info.optBoolean("pairingOpen"))throw new Exception("电脑配对码已过期，请生成新码后重新扫描");
                break;
            }catch(java.io.IOException error){
                if(error instanceof javax.net.ssl.SSLException)throw new Exception("电脑证书验证失败，请重新扫描电脑配对码");
                if(clock.now()>=deadline)throw new Exception("已加入组网，但暂时无法连接电脑；请确认电脑连接服务和虚拟 IP");
                pause.sleep(Math.min(1000,250L*(++attempts)));
            }
        }
        beforeSubmit.run();
        try{return transport.call("POST","/v1/pair",body);}
        catch(java.io.IOException error){throw new Exception("配对结果未确认，请重新扫描配对码；不会自动重复提交");}
    }
}
