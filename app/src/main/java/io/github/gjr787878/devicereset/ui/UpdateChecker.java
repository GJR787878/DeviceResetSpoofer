package io.github.gjr787878.devicereset.ui;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 自动更新检测：查询 GitHub 仓库最新 Release，与本地版本比较。
 *
 * 双通道检测（提高成功率）：
 * 1. API：https://api.github.com/repos/<repo>/releases/latest（标准方式）
 * 2. 页面：https://github.com/<repo>/releases/latest 的 302 重定向提取 tag
 *    （api.github.com 在部分网络（如国内 DNS）解析失败时使用）
 * 3. CDN/代理：jsDelivr 多节点 + GitHub raw 国内代理，读仓库根 latest_version.txt
 *    （GitHub 域名整体不可达时使用；多源取最大版本，避免旧缓存误判）
 *
 * 自动重试：DNS 波动时域名解析可能瞬时失败，最多重试 3 轮。
 * 4. IP 直连：DNS 污染 + IP 封锁时，用 GitHub 已知 Anycast IP 直连（raw + API）。
 * 错误按通道汇总，便于定位具体哪个源失败。
 */
public class UpdateChecker {

    public interface Callback {
        /**
         * @param latestVersion 最新版本号（如 2.5），解析失败时为 null
         * @param latestTag     最新 Release 的 tag（如 v2.5）
         * @param hasUpdate     是否存在新版本
         * @param error         错误信息（成功时为 null）
         */
        void onResult(String latestVersion, String latestTag, boolean hasUpdate, String error);
    }

    /**
     * @param repo           如 "GJR787878/DeviceResetSpoofer"
     * @param currentVersion 本地版本号，如 versionName
     * @param callback       结果回调（主线程）
     */
    public static void check(final String repo, final String currentVersion,
                             final Callback callback) {
        new Thread(() -> {
            String latest = null;
            String tag = null;
            boolean hasUpdate = false;
            String error = null;

            /*
             * 自动重试：国内 DNS 波动时域名解析可能瞬时失败，
             * 重试 3 轮（每轮尝试全部通道），大幅提升成功率。
             */
            final int MAX_ATTEMPTS = 3;

            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {

                latest = null;
                tag = null;
                StringBuilder errs = new StringBuilder();

                // 通道一：GitHub API
                try {
                    tag = fetchLatestTagFromApi(repo);
                } catch (Exception e) {
                    errs.append("[API:").append(e.getMessage()).append("]");
                }

                // 通道一失败 → 通道二：github.com 页面 302 重定向
                if (tag == null || tag.isEmpty()) {
                    try {
                        tag = fetchLatestTagFromPage(repo);
                    } catch (Exception e2) {
                        errs.append("[页面:").append(e2.getMessage()).append("]");
                    }
                }

                // 通道二失败 → 通道三：国内可直连的 CDN / GitHub 代理（多源取最大版本）
                if (tag == null || tag.isEmpty()) {
                    String[] urls = {
                            "https://cdn.jsdelivr.net/gh/" + repo + "@main/latest_version.txt",
                            "https://fastly.jsdelivr.net/gh/" + repo + "@main/latest_version.txt",
                            "https://gcore.jsdelivr.net/gh/" + repo + "@main/latest_version.txt",
                            "https://ghfast.top/https://raw.githubusercontent.com/" + repo + "/main/latest_version.txt",
                            "https://gh-proxy.com/https://raw.githubusercontent.com/" + repo + "/main/latest_version.txt",
                            "https://ghproxy.net/https://raw.githubusercontent.com/" + repo + "/main/latest_version.txt",
                            "https://github.moeyy.xyz/https://raw.githubusercontent.com/" + repo + "/main/latest_version.txt",
                            "https://ghproxy.cc/https://raw.githubusercontent.com/" + repo + "/main/latest_version.txt",
                            "https://gh.llkk.cc/https://raw.githubusercontent.com/" + repo + "/main/latest_version.txt",
                            "https://mirror.ghproxy.com/https://raw.githubusercontent.com/" + repo + "/main/latest_version.txt"
                    };
                    String best = null;
                    StringBuilder cdnErr = new StringBuilder();
                    for (String u : urls) {
                        try {
                            String v = fetchLatestVersionFromUrl(u);
                            if (v != null && (best == null || compareVersions(v, best) > 0)) {
                                best = v;
                            }
                        } catch (Exception ex) {
                            if (cdnErr.length() < 140) {
                                if (cdnErr.length() > 0) cdnErr.append("; ");
                                cdnErr.append(hostOf(u)).append(":").append(ex.getMessage());
                            }
                        }
                    }
                    if (best != null) {
                        latest = best;
                        tag = "v" + best;
                    } else if (cdnErr.length() > 0) {
                        errs.append("[代理:").append(cdnErr).append("]");
                    }
                }

                // 通道四：IP 直连兜底（绕过 DNS 污染，用已知 IP + Host 头 + TLS SNI）
                if (latest == null) {
                    StringBuilder ipErr = new StringBuilder();
                    try {
                        String v = fetchViaIpFallback("raw.githubusercontent.com",
                                RAW_IPS, "/" + repo + "/main/latest_version.txt", null);
                        if (v != null) {
                            latest = v;
                            tag = "v" + v;
                        }
                    } catch (Exception ex) {
                        ipErr.append("raw:").append(ex.getMessage());
                    }
                    if (latest == null) {
                        try {
                            String t = fetchViaIpFallback("api.github.com",
                                    API_IPS, "/repos/" + repo + "/releases/latest", "tag_name");
                            if (t != null && !t.isEmpty()) {
                                tag = t;
                            }
                        } catch (Exception ex) {
                            if (ipErr.length() > 0) ipErr.append("; ");
                            ipErr.append("api:").append(ex.getMessage());
                        }
                    }
                    if (latest == null && tag != null && !tag.isEmpty()) {
                        latest = extractVersion(tag);
                    }
                    if (latest == null && ipErr.length() > 0) {
                        errs.append("[IP直连:").append(ipErr).append("]");
                    }
                }

                if (latest == null && tag != null && !tag.isEmpty()) {
                    latest = extractVersion(tag);
                }

                if (latest != null) {
                    error = null;
                    break;
                }

                error = errs.toString();
                if (error.length() > 300) {
                    error = error.substring(0, 300) + "...";
                }

                if (attempt < MAX_ATTEMPTS) {
                    try {
                        Thread.sleep(1500);
                    } catch (InterruptedException ignored) {
                    }
                }
            }

            if (latest == null && error != null) {
                error = "网络异常（已重试 " + MAX_ATTEMPTS + " 次）: " + error;
            }

            if (latest != null && currentVersion != null) {
                hasUpdate = compareVersions(latest, currentVersion) > 0;
            }

            final String fLatest = latest;
            final String fTag = tag;
            final boolean fHas = hasUpdate;
            final String fError = error;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(
                    () -> callback.onResult(fLatest, fTag, fHas, fError));
        }).start();
    }
    /** 通道一：GitHub API 获取最新 tag */
    private static String fetchLatestTagFromApi(String repo) throws Exception {
        URL url = new URL("https://api.github.com/repos/" + repo + "/releases/latest");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setRequestProperty("User-Agent", "GJR787878-UpdateChecker");
        int code = conn.getResponseCode();
        if (code == 200) {
            InputStream is = conn.getInputStream();
            BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line);
            }
            r.close();
            JSONObject json = new JSONObject(sb.toString());
            conn.disconnect();
            return json.optString("tag_name", "");
        }
        conn.disconnect();
        throw new Exception("HTTP " + code);
    }

    /** 通道二：github.com releases/latest 的 302 重定向，从 Location 提取 tag */
    private static String fetchLatestTagFromPage(String repo) throws Exception {
        URL url = new URL("https://github.com/" + repo + "/releases/latest");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setInstanceFollowRedirects(false);  // 手动读 Location
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android)");
        int code = conn.getResponseCode();
        String loc = conn.getHeaderField("Location");
        conn.disconnect();
        if ((code == 301 || code == 302 || code == 303 || code == 307 || code == 308)
                && loc != null && loc.contains("/releases/tag/")) {
            String tag = loc.substring(loc.indexOf("/releases/tag/") + "/releases/tag/".length());
            int q = tag.indexOf('?');
            if (q >= 0) tag = tag.substring(0, q);
            while (tag.endsWith("/")) {
                tag = tag.substring(0, tag.length() - 1);
            }
            return tag;
        }
        throw new Exception("HTTP " + code);
    }

    /** 通道三：从任意 URL 读取纯版本号（如 2.6），失败抛异常 */
    private static String fetchLatestVersionFromUrl(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android)");
        int code = conn.getResponseCode();
        if (code == 200) {
            InputStream is = conn.getInputStream();
            BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            String v = r.readLine();
            r.close();
            conn.disconnect();
            if (v != null) {
                v = v.trim();
                if (v.matches("\\d+(\\.\\d+)*")) {
                    return v;
                }
            }
        }
        conn.disconnect();
        throw new Exception("HTTP " + code);
    }

    /** 通道四：IP 直连绕过 DNS 污染。用已知 IP 连接 + Host 头 + TLS SNI。 */
    private static final String[] RAW_IPS = {
            "185.199.108.133",
            "185.199.109.133",
            "185.199.110.133",
            "185.199.111.133"
    };
    private static final String[] API_IPS = {
            "140.82.112.6",
            "140.82.113.6",
            "140.82.114.6",
            "140.82.116.6"
    };

    /**
     * IP 直连：jsonKey 为 null 时读首行纯版本号；
     * 非 null 时解析 JSON 并返回该字段（如 tag_name）。
     */
    private static String fetchViaIpFallback(String host, String[] ips,
                                             String path, String jsonKey)
            throws Exception {
        Exception last = null;
        for (String ip : ips) {
            try {
                URL url = new URL("https://" + ip + path);
                HttpsURLConnection conn =
                        (HttpsURLConnection) url.openConnection();
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                conn.setRequestProperty("Host", host);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android)");

                // 信任所有证书（连的是 IP，无法按域名校验）
                TrustManager[] trustAll = {new X509TrustManager() {
                    public void checkClientTrusted(X509Certificate[] c, String a) {
                    }
                    public void checkServerTrusted(X509Certificate[] c, String a) {
                    }
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }};
                SSLContext sc = SSLContext.getInstance("TLS");
                sc.init(null, trustAll, new SecureRandom());
                final SSLSocketFactory base = sc.getSocketFactory();
                final String sniHost = host;

                conn.setSSLSocketFactory(new SSLSocketFactory() {
                    private void applySni(Socket socket) {
                        if (socket instanceof SSLSocket) {
                            SSLSocket ssl = (SSLSocket) socket;
                            SSLParameters p = ssl.getSSLParameters();
                            p.setServerNames(Collections.singletonList(
                                    new SNIHostName(sniHost)));
                            ssl.setSSLParameters(p);
                        }
                    }
                    public Socket createSocket() throws java.io.IOException {
                        SSLSocket s = (SSLSocket) base.createSocket();
                        applySni(s);
                        return s;
                    }
                    public Socket createSocket(Socket s, String h, int p,
                                               boolean a) throws java.io.IOException {
                        SSLSocket s2 = (SSLSocket) base.createSocket(s, h, p, a);
                        applySni(s2);
                        return s2;
                    }
                    public Socket createSocket(String h, int p)
                            throws java.io.IOException {
                        SSLSocket s = (SSLSocket) base.createSocket(h, p);
                        applySni(s);
                        return s;
                    }
                    public Socket createSocket(String h, int p,
                                               InetAddress lh, int lp)
                            throws java.io.IOException {
                        SSLSocket s = (SSLSocket) base.createSocket(h, p, lh, lp);
                        applySni(s);
                        return s;
                    }
                    public Socket createSocket(InetAddress h, int p)
                            throws java.io.IOException {
                        SSLSocket s = (SSLSocket) base.createSocket(h, p);
                        applySni(s);
                        return s;
                    }
                    public Socket createSocket(InetAddress h, int p,
                                               InetAddress lh, int lp)
                            throws java.io.IOException {
                        SSLSocket s = (SSLSocket) base.createSocket(h, p, lh, lp);
                        applySni(s);
                        return s;
                    }
                    public String[] getDefaultCipherSuites() {
                        return base.getDefaultCipherSuites();
                    }
                    public String[] getSupportedCipherSuites() {
                        return base.getSupportedCipherSuites();
                    }
                });

                conn.setHostnameVerifier((h, s) -> true);

                int code = conn.getResponseCode();
                if (code == 200) {
                    InputStream is = conn.getInputStream();
                    BufferedReader r = new BufferedReader(
                            new InputStreamReader(is, "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = r.readLine()) != null) {
                        sb.append(line);
                    }
                    r.close();
                    conn.disconnect();
                    if (jsonKey == null) {
                        String v = sb.toString().trim();
                        if (v.matches("\\d+(\\.\\d+)*")) {
                            return v;
                        }
                    } else {
                        JSONObject json = new JSONObject(sb.toString());
                        String t = json.optString(jsonKey, "");
                        if (!t.isEmpty()) {
                            return t;
                        }
                    }
                }
                conn.disconnect();
                throw new Exception("HTTP " + code);
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new Exception("IP fallback failed");
    }

    /** 从 URL 提取域名（用于错误提示） */
    private static String hostOf(String urlStr) {
        try {
            return new URL(urlStr).getHost();
        } catch (Exception e) {
            return urlStr;
        }
    }
    /** 从 tag（如 v2.5 / 25-v2.4 / 1.3.9）提取版本号，失败返回 null */
    public static String extractVersion(String tag) {
        if (tag == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d+)(\\.\\d+)*")
                .matcher(tag);
        return m.find() ? m.group() : null;
    }

    /** 版本比较：a > b 返回正数，a < b 返回负数，相等返回 0 */
    public static int compareVersions(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int va = i < pa.length ? Integer.parseInt(pa[i]) : 0;
            int vb = i < pb.length ? Integer.parseInt(pb[i]) : 0;
            if (va != vb) return va - vb;
        }
        return 0;
    }
}
