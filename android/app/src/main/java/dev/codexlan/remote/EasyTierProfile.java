package dev.codexlan.remote;

import java.net.URI;

/** Configuration and narrow virtual-subnet routing, independent of Android UI. */
public final class EasyTierProfile {
    public static final String INSTANCE = "codex-lan-android";
    public final String name, secret, address, subnet, peers;
    public final int prefix;
    public final boolean dhcp;
    public EasyTierProfile(String name, String secret, String cidr, String peers) throws Exception {
        this(name, secret, cidr, peers, false);
    }
    // In DHCP mode cidr describes the expected network, never a requested phone IP.
    public EasyTierProfile(String name, String secret, String cidr, String peers, boolean dhcp) throws Exception {
        this.dhcp = dhcp;
        this.name = name.trim(); this.secret = secret; this.peers = peers.trim();
        if (this.name.isEmpty() || this.name.length() > 128 || secret.length() > 512) throw new Exception("网络名称不能为空，且名称/密钥长度不能过长");
        String[] parts = cidr.trim().split("/", -1);
        if (parts.length != 2 || !parts[1].matches("[0-9]{1,2}")) throw new Exception("请填写手机虚拟 IPv4/前缀，例如 10.90.0.2/24");
        prefix = Integer.parseInt(parts[1]);
        if (prefix < 8 || prefix > 30) throw new Exception("预览版支持 /8 至 /30 的虚拟子网");
        address = parts[0]; long ip = ipv4(address);
        long mask = (0xffffffffL << (32 - prefix)) & 0xffffffffL;
        long network = ip & mask;
        if (ip == network || ip == (network | (~mask & 0xffffffffL)) || ip >>> 24 == 0 || ip >>> 24 == 127 || ip >>> 24 >= 224) throw new Exception("请输入可分配给手机的虚拟主机地址");
        subnet = ((network >>> 24) & 255) + "." + ((network >>> 16) & 255) + "." + ((network >>> 8) & 255) + "." + (network & 255);
        if (this.peers.isEmpty()) throw new Exception("请至少填写一个可达的组网节点地址");
        String[] entries = this.peers.split("\\r?\\n");
        if (entries.length > 16) throw new Exception("最多支持 16 个节点地址");
        for (String value : entries) {
            if (value.trim().isEmpty()) continue;
            URI peer = new URI(value.trim());
            if (!java.util.Arrays.asList("tcp", "udp", "ws", "wss", "quic", "wg").contains(peer.getScheme()) || peer.getHost() == null || peer.getRawUserInfo() != null) throw new Exception("节点格式应为 tcp://主机:端口 或其他 EasyTier 协议地址");
            // Routing transport packets back into their own TUN would create a loop.
            if (peer.getHost().matches("[0-9.]+") && (ipv4(peer.getHost()) & mask) == network) throw new Exception("组网节点应填底层网络地址，不能填本虚拟子网内的地址");
        }
    }
    private static long ipv4(String value) throws Exception {
        String[] octets = value.split("\\.", -1); if (octets.length != 4) throw new Exception("IPv4 地址格式不正确");
        long ip = 0;
        for (String octet : octets) {
            if (!octet.matches("0|[1-9][0-9]{0,2}")) throw new Exception("IPv4 地址格式不正确");
            int number = Integer.parseInt(octet); if (number > 255) throw new Exception("IPv4 地址超出范围"); ip = (ip << 8) | number;
        }
        return ip;
    }
    private static String quote(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            if (c == '\\' || c == '"') result.append('\\').append(c);
            else if (c < 32 || c == 127) result.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
            else result.append(c);
        }
        return result.append('"').toString();
    }
    public String toml() {
        StringBuilder config = new StringBuilder("instance_name = ").append(quote(INSTANCE))
            .append("\nhostname = \"codex-lan-android\"\ndhcp = ").append(dhcp);
        if (!dhcp) config.append("\nipv4 = ").append(quote(address + "/" + prefix));
        config.append("\nlisteners = []\nrpc_portal = \"127.0.0.1:0\"\n[network_identity]\nnetwork_name = ").append(quote(name))
            .append("\nnetwork_secret = ").append(quote(secret)).append('\n');
        for (String peer : peers.split("\\r?\\n")) if (!peer.trim().isEmpty()) config.append("\n[[peer]]\nuri = ").append(quote(peer.trim())).append('\n');
        return config.append("\n[flags]\nenable_encryption = true\nenable_ipv6 = false\n").toString();
    }
    public EasyTierProfile assigned(org.json.JSONObject value) throws Exception {
        org.json.JSONObject raw = value.getJSONObject("address");
        Object number = raw.get("addr"), length = value.get("network_length");
        if (!(number instanceof Number) || !(length instanceof Number)) throw new Exception("虚拟地址格式无效");
        long ip = ((Number)number).longValue(); int bits = ((Number)length).intValue();
        if (ip < 0 || ip > 0xffffffffL || ((Number)number).doubleValue() != ip || ((Number)length).doubleValue() != bits) throw new Exception("虚拟地址格式无效");
        String host = ((ip >>> 24)&255)+"."+((ip >>> 16)&255)+"."+((ip >>> 8)&255)+"."+(ip&255);
        EasyTierProfile assigned = new EasyTierProfile(name,secret,host+"/"+bits,peers);
        if (assigned.prefix != prefix || !assigned.subnet.equals(subnet)) throw new Exception("分配地址不在电脑虚拟子网中");
        return assigned;
    }
}
