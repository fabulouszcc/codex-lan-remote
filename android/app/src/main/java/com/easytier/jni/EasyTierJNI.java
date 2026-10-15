package com.easytier.jni;

/** JNI ABI matching the pinned EasyTier libraries. No Moonlight application code. */
public final class EasyTierJNI {
    public static final EasyTierJNI INSTANCE = new EasyTierJNI();
    static {
        System.loadLibrary("easytier_ffi");
        System.loadLibrary("easytier_android_jni");
    }
    private EasyTierJNI() {}
    public native int parseConfig(String config);
    public native int runNetworkInstance(String config);
    public native int setTunFd(String instanceName, int fd);
    public native int retainNetworkInstance(String[] instanceNames);
    public native String collectNetworkInfos();
    public native String getLastError();
}
