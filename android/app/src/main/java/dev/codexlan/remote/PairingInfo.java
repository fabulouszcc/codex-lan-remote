package dev.codexlan.remote;

import org.json.JSONObject;

/** The QR/clipboard is input, never a URL or command to launch. */
final class PairingInfo {
    final String address,fingerprint,code;
    final String networkMode;
    final JSONObject easytier;
    final long expiresAt;
    private PairingInfo(String address,String fingerprint,String code,String mode,JSONObject easytier,long expiresAt){this.address=address;this.fingerprint=fingerprint;this.code=code;this.networkMode=mode;this.easytier=easytier;this.expiresAt=expiresAt;}
    JSONObject json() throws Exception {JSONObject value=new JSONObject().put("address",address).put("fingerprint",fingerprint).put("code",code).put("networkMode",networkMode);if(easytier!=null)value.put("easytier",new JSONObject(easytier.toString()));if(expiresAt!=Long.MAX_VALUE)value.put("expiresAt",expiresAt);return value;}
    static PairingInfo parse(String text,long now) throws Exception {
        if(text==null||text.length()>16384)throw new Exception("不是有效的电脑配对二维码");
        JSONObject value;
        try{value=new JSONObject(text);}catch(Exception e){throw new Exception("请使用电脑插件生成的配对二维码");}
        if(!(value.opt("address") instanceof String)||!(value.opt("fingerprint") instanceof String)||!(value.opt("code") instanceof String))throw new Exception("配对信息不完整");
        String address=MobileState.connectionAddress(value.getString("address"));
        String fingerprint=value.getString("fingerprint").replace(":","").replaceAll("\\s","").toLowerCase(java.util.Locale.ROOT);
        String code=value.getString("code").trim();
        if(!fingerprint.matches("[a-f0-9]{64}")||!code.matches("[a-zA-Z0-9_-]{1,128}"))throw new Exception("配对信息格式不正确");
        long expiry=Long.MAX_VALUE;
        if(value.has("expiresAt")){try{Object raw=value.get("expiresAt");expiry=raw instanceof Number?((Number)raw).longValue():java.time.Instant.parse(raw.toString()).toEpochMilli();}catch(Exception e){throw new Exception("配对有效期格式不正确");}if(expiry<=now)throw new Exception("配对码已过期，请在电脑端重新生成");}
        String mode=value.optString("networkMode", "existing");
        if(!java.util.Arrays.asList("existing","lan","easytier").contains(mode))throw new Exception("不支持的配对连接方式");
        JSONObject et=null;
        if("easytier".equals(mode)){
            JSONObject raw=value.optJSONObject("easytier");if(raw==null)throw new Exception("二维码缺少 EasyTier 入网参数");
            if(raw.has("dhcp")&&!(raw.opt("dhcp") instanceof Boolean))throw new Exception("DHCP 参数格式不正确");
            boolean dhcp=raw.optBoolean("dhcp");
            if(dhcp&&raw.has("cidr"))throw new Exception("自动分配不能同时指定手机静态地址");
            et=new JSONObject();for(String key:new String[]{"name","secret",dhcp?"networkCidr":"cidr","peers"}){if(!(raw.opt(key) instanceof String))throw new Exception("EasyTier 入网参数不完整");et.put(key,raw.getString(key));}
            if(dhcp)et.put("dhcp",true);
            EasyTierProfile profile=new EasyTierProfile(et.getString("name"),et.getString("secret"),et.getString(dhcp?"networkCidr":"cidr"),et.getString("peers"),dhcp);
            String host=new java.net.URI(address).getHost();
            if(!sameSubnet(host,profile.address,profile.prefix)||(!dhcp&&host.equals(profile.address)))throw new Exception("手机与电脑虚拟地址必须在同一子网且不能重复");
        }else if(value.has("easytier"))throw new Exception("连接方式与组网参数不一致");
        return new PairingInfo(address,fingerprint,code,mode,et,expiry);
    }
    private static boolean sameSubnet(String a,String b,int prefix){
        if(a==null||!a.matches("[0-9.]+"))return false;
        try{long left=0,right=0;String[] x=a.split("\\."),y=b.split("\\.");if(x.length!=4||y.length!=4)return false;for(int i=0;i<4;i++){int n=Integer.parseInt(x[i]);if(n<0||n>255)return false;left=left*256+n;right=right*256+Integer.parseInt(y[i]);}long mask=(0xffffffffL<<(32-prefix))&0xffffffffL;return(left&mask)==(right&mask);}catch(Exception ignored){return false;}
    }
}
