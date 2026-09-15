package io.github.gjr787878.devicereset.ui;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 自动更新检测：查询 GitHub 仓库最新 Release，与本地版本比较。
 *
 * 双通道检测（提高成功率）：
 * 1. API：https://api.github.com/repos/<repo>/releases/latest（标准方式）
 * 2. 页面：https://github.com/<repo>/releases/latest 的 302 重定向提取 tag
 *    （api.github.com 在部分网络（如国内 DNS）解析失败时使用）
 * 3. CDN：jsDelivr 读取仓库根 latest_version.txt（GitHub 域名整体不可达时使用）
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

            // 通道一：GitHub API
            try {
                tag = fetchLatestTagFromApi(repo);
            } catch (Exception e) {
                error = e.getMessage();
            }

            // 通道一失败 → 通道二：github.com 页面 302 重定向
            if (tag == null || tag.isEmpty()) {
                try {
                    tag = fetchLatestTagFromPage(repo);
                    error = null;
                } catch (Exception e2) {
                    error = e2.getMessage();
                }
            }

            // 通道二失败 → 通道三：jsDelivr CDN（国内可直连，读仓库根 latest_version.txt）
            if (tag == null || tag.isEmpty()) {
                String[] mirrors = {
                        "https://cdn.jsdelivr.net/gh/",
                        "https://fastly.jsdelivr.net/gh/",
                        "https://gcore.jsdelivr.net/gh/"
                };
                for (String base : mirrors) {
                    try {
                        String v = fetchLatestVersionFromJsDelivr(
                                base + repo + "@main/latest_version.txt");
                        if (v != null) {
                            latest = v;
                            tag = "v" + v;
                            error = null;
                            break;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }

            if (latest == null && tag != null && !tag.isEmpty()) {
                latest = extractVersion(tag);
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

    /** 通道三：jsDelivr CDN 读取仓库根 latest_version.txt（纯版本号，如 2.6） */
    private static String fetchLatestVersionFromJsDelivr(String urlStr) throws Exception {
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
