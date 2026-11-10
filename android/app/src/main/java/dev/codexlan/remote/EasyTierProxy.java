package dev.codexlan.remote;

import java.net.InetSocketAddress;
import java.net.Proxy;

/** Only the configured virtual subnet uses the app-owned loopback proxy. */
final class EasyTierProxy {
    private static final class Route {
        final EasyTierProfile profile;final Proxy proxy;
        Route(EasyTierProfile profile,int port){this.profile=profile;proxy=new Proxy(Proxy.Type.SOCKS,new InetSocketAddress("127.0.0.1",port));}
    }
    private static volatile Route route;
    static void configure(EasyTierProfile profile,int port){
        if(port<1024||port>65535)throw new IllegalArgumentException("本地代理端口无效");route=new Route(profile,port);
    }
    static Proxy forHost(String host){Route active=route;return active!=null&&active.profile.contains(host)?active.proxy:Proxy.NO_PROXY;}
    static void clear(){route=null;}
}
