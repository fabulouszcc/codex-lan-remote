package dev.codexlan.remote;

import org.json.JSONObject;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

final class RemoteApi {
    final String address;
    final String fingerprint;
    volatile String token;
    private final SSLContext ssl;
    private final Set<HttpsURLConnection> connections = ConcurrentHashMap.newKeySet();
    RemoteApi(String address, String fingerprint, String token) throws Exception {
        URI uri = new URI(address.trim());
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) throw new Exception("请输入 https://电脑IP:端口");
        this.address = address.trim().replaceAll("/+$", "");
        this.fingerprint = fingerprint.replace(":", "").replaceAll("\\s", "").toLowerCase(java.util.Locale.ROOT);
        if (!this.fingerprint.matches("[a-f0-9]{64}")) throw new Exception("证书指纹应为 64 位十六进制字符");
        this.token = token;
        ssl = SSLContext.getInstance("TLS");
        ssl.init(null, new TrustManager[] { new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] chain, String auth) throws java.security.cert.CertificateException { throw new java.security.cert.CertificateException("仅支持服务端证书"); }
            public void checkServerTrusted(X509Certificate[] chain, String auth) throws java.security.cert.CertificateException {
                try {
                    if (chain == null || chain.length == 0) throw new Exception();
                    chain[0].checkValidity();
                    if (!matches(chain[0])) throw new Exception();
                } catch (Exception error) { throw new java.security.cert.CertificateException("电脑证书与配对指纹不符或已过期"); }
            }
        } }, new java.security.SecureRandom());
    }
    private boolean matches(java.security.cert.Certificate cert) throws Exception {
        byte[] expected = new byte[32];
        for (int i = 0; i < 32; i++) expected[i] = (byte) Integer.parseInt(fingerprint.substring(i * 2, i * 2 + 2), 16);
        return MessageDigest.isEqual(expected, MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
    }
    JSONObject call(String method, String path, JSONObject body, String operationId) throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(address + path).openConnection();
        connections.add(connection);
        try {
            connection.setSSLSocketFactory(ssl.getSocketFactory());
            // Identity is the explicitly pinned certificate; IP addresses may change across networks.
            connection.setHostnameVerifier((host, session) -> { try { return matches(session.getPeerCertificates()[0]); } catch (Exception e) { return false; } });
            connection.setConnectTimeout(8000); connection.setReadTimeout(30000); connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod(method); connection.setRequestProperty("Accept", "application/json");
            if (token != null && !token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
            if (operationId != null) connection.setRequestProperty("Idempotency-Key", operationId);
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json"); connection.setFixedLengthStreamingMode(bytes.length);
                try (java.io.OutputStream out = connection.getOutputStream()) { out.write(bytes); }
            }
            int status = connection.getResponseCode();
            InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (input == null) throw new Exception("电脑未返回数据（" + status + "）");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (input) {
                byte[] buffer = new byte[8192]; int read;
                while ((read = input.read(buffer)) != -1) { if (bytes.size() + read > 8 * 1024 * 1024) throw new Exception("返回内容过大"); bytes.write(buffer, 0, read); }
            }
            JSONObject result = new JSONObject(bytes.toString("UTF-8"));
            if (status != 200) throw new Exception(result.optString("error", "请求失败（" + status + "）"));
            return result;
        } finally { connections.remove(connection); connection.disconnect(); }
    }
    void cancel() { for (HttpsURLConnection connection : connections) connection.disconnect(); }
}
