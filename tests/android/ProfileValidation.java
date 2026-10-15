import dev.codexlan.remote.EasyTierProfile;

public final class ProfileValidation {
    private static void reject(String cidr, String peer) throws Exception {
        try { new EasyTierProfile("test", "secret", cidr, peer); }
        catch (Exception expected) { return; }
        throw new AssertionError("Unsafe address/transport accepted");
    }
    public static void main(String[] args) throws Exception {
        EasyTierProfile profile = new EasyTierProfile("name\"\n[[peer]]", "secret\\\"\n", "192.168.250.14/24", "tcp://203.0.113.1:11010");
        if (!profile.subnet.equals("192.168.250.0")) throw new AssertionError("Route is not normalized");
        if (!profile.toml().contains("network_name = \"name\\\"\\u000a[[peer]]\"")) throw new AssertionError("TOML config injection");
        if (!profile.toml().contains("network_secret = \"secret\\\\\\\"\\u000a\"")) throw new AssertionError("TOML secret escaping");
        reject("192.168.250.0/24", "tcp://203.0.113.1:11010");
        reject("192.168.250.255/24", "tcp://203.0.113.1:11010");
        reject("192.168.250.14/0", "tcp://203.0.113.1:11010");
        reject("192.168.250.14/24", "tcp://192.168.250.1:11010");
        reject("999.1.1.1/24", "tcp://203.0.113.1:11010");
        reject("192.168.250.14/24", "https://203.0.113.1");
        System.out.println("PASS: normalized subnet, TOML injection/secret escaping, invalid hosts, default route and transport-loop guards");
    }
}
