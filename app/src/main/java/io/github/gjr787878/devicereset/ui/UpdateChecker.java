package io.github.gjr787878.devicereset.ui;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 自动更新检测：查询 GitHub 仓库最新 Release，与本地版本比较。
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
     * @param currentVersion 本地版本号，如 BuildConfig.VERSION_NAME
     * @param callback       结果回调（主线程）
     */
    public static void check(final String repo, final String currentVersion,
                             final Callback callback) {
        new Thread(() -> {
            String latest = null;
            String tag = null;
            boolean hasUpdate = false;
            String error = null;
            try {
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
                    tag = json.optString("tag_name", "");
                    latest = extractVersion(tag);
                    if (latest != null && currentVersion != null) {
                        hasUpdate = compareVersions(latest, currentVersion) > 0;
                    }
                } else {
                    error = "HTTP " + code;
                }
                conn.disconnect();
            } catch (Exception e) {
                error = e.getMessage();
            }
            final String fLatest = latest;
            final String fTag = tag;
            final boolean fHas = hasUpdate;
            final String fError = error;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(
                    () -> callback.onResult(fLatest, fTag, fHas, fError));
        }).start();
    }

    /** 从 tag（如 v2.5 / 24-v2.4 / 1.3.9）提取版本号，失败返回 null */
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
