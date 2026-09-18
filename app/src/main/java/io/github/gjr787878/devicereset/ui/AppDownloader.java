package io.github.gjr787878.devicereset.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.gjr787878.devicereset.GlassButtonDrawable;

/**
 * 应用内置 APK 下载器：
 * - 弹窗与软件其它弹窗风格一致（玻璃按钮 + 主题弹窗）
 * - 显示横向进度条 + 百分比
 * - 进度条下方一左一右两个按钮：取消 / 仓库主页
 *   - 取消：停止下载并删除已下载的临时文件
 *   - 仓库主页：取消本次下载并跳转浏览器打开仓库页面
 * - 下载完成后通过 FileProvider 拉起系统安装界面
 * - 多源下载：直连 GitHub 失败时自动尝试国内镜像代理
 */
public class AppDownloader {

    private AppDownloader() {
    }

    /**
     * 开始内置下载并展示进度弹窗。
     *
     * @param context       Activity 上下文
     * @param urls          候选下载地址（直连 + 镜像，依次尝试）
     * @param repoHomeUrl   仓库主页地址（“仓库主页”按钮跳转目标）
     * @param saveName      保存到本地的文件名（如 DeviceResetSpoofer-v2.14.apk）
     * @param version       新版本号（用于标题展示）
     * @param language      当前界面语言：zh / en / ru
     */
    public static void start(final Context context, final String[] urls,
                             final String repoHomeUrl, final String saveName,
                             final String version, final String language) {
        final Handler ui = new Handler(Looper.getMainLooper());
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final float density = context.getResources().getDisplayMetrics().density;

        // ============ 构建进度弹窗（风格与软件其它弹窗一致） ============
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * density);
        root.setPadding(pad, Math.round(4 * density), pad, 0);

        final ProgressBar progress = new ProgressBar(
                context, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        root.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        final TextView percentText = new TextView(context);
        percentText.setText("0%");
        percentText.setTextSize(13);
        percentText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams percentLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        percentLp.topMargin = Math.round(6 * density);
        root.addView(percentText, percentLp);

        // 进度条下方：一左一右两个按钮
        LinearLayout btnRow = new LinearLayout(context);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = Math.round(14 * density);
        btnRow.setLayoutParams(rowLp);

        float radiusPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 24, context.getResources().getDisplayMetrics());
        float borderPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 1, context.getResources().getDisplayMetrics());

        Button cancelBtn = new Button(context);
        cancelBtn.setText(t(language, "取消", "Cancel", "Отмена"));
        cancelBtn.setTextSize(14);
        cancelBtn.setBackground(new GlassButtonDrawable(radiusPx, borderPx, false));

        Button repoBtn = new Button(context);
        repoBtn.setText(t(language, "仓库主页", "Repo Home", "Репозиторий"));
        repoBtn.setTextSize(14);
        repoBtn.setBackground(new GlassButtonDrawable(radiusPx, borderPx, false));

        int btnMargin = Math.round(4 * density);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        btnLp.leftMargin = btnMargin;
        btnLp.rightMargin = btnMargin;
        btnRow.addView(cancelBtn, btnLp);
        btnRow.addView(repoBtn, btnLp);
        root.addView(btnRow, rowLp);

        final AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(t(language, "⬇️ 正在下载 v" + version,
                        "⬇️ Downloading v" + version,
                        "⬇️ Загрузка v" + version))
                .setView(root)
                .setCancelable(false)
                .create();
        dialog.show();

        cancelBtn.setOnClickListener(v -> {
            cancelled.set(true);
            dialog.dismiss();
        });

        repoBtn.setOnClickListener(v -> {
            cancelled.set(true);
            dialog.dismiss();
            try {
                context.startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse(repoHomeUrl))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {
            }
        });

        // ============ 后台下载线程 ============
        new Thread(() -> {
            Exception lastErr = null;
            File done = null;
            long lastPostMs = 0;

            for (String urlStr : urls) {
                if (cancelled.get()) return;
                HttpURLConnection conn = null;
                InputStream is = null;
                FileOutputStream fos = null;
                File tmp = null;
                try {
                    URL url = new URL(urlStr);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(15000);
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android)");
                    conn.setRequestProperty("Accept", "application/octet-stream");
                    int code = conn.getResponseCode();
                    if (code != 200) {
                        throw new java.io.IOException("HTTP " + code);
                    }
                    long total = conn.getContentLengthLong();
                    is = conn.getInputStream();

                    File dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    if (dir == null) {
                        dir = context.getCacheDir();
                    }
                    tmp = new File(dir, saveName);
                    fos = new FileOutputStream(tmp);

                    byte[] buf = new byte[32768];
                    long doneBytes = 0;
                    int n;
                    while ((n = is.read(buf)) > 0) {
                        if (cancelled.get()) {
                            fos.close();
                            fos = null;
                            tmp.delete();
                            return;
                        }
                        fos.write(buf, 0, n);
                        doneBytes += n;
                        long now = SystemClock.elapsedRealtime();
                        if (now - lastPostMs >= 100 || doneBytes >= total) {
                            lastPostMs = now;
                            final int pct = total > 0
                                    ? (int) (doneBytes * 100 / total) : 0;
                            ui.post(() -> {
                                progress.setProgress(pct);
                                percentText.setText(pct + "%");
                            });
                        }
                    }
                    fos.close();
                    fos = null;
                    if (total > 0 && doneBytes != total) {
                        throw new java.io.IOException("size mismatch");
                    }
                    done = tmp;
                    break;
                } catch (Exception e) {
                    lastErr = e;
                    if (tmp != null && tmp.exists()) {
                        tmp.delete();
                    }
                } finally {
                    try {
                        if (is != null) is.close();
                    } catch (Exception ignored) {
                    }
                    try {
                        if (fos != null) fos.close();
                    } catch (Exception ignored) {
                    }
                    if (conn != null) conn.disconnect();
                }
            }

            if (cancelled.get()) return;

            if (done != null && done.exists()) {
                final File apk = done;
                final String lang = language;
                ui.post(() -> {
                    dialog.dismiss();
                    installApk(context, apk, lang);
                });
            } else {
                final String msg = lastErr != null
                        ? String.valueOf(lastErr.getMessage()) : "";
                ui.post(() -> {
                    dialog.dismiss();
                    Toast.makeText(context,
                            t(language, "下载失败：", "Download failed: ",
                                    "Ошибка загрузки: ") + msg,
                            Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    /** 通过 FileProvider 拉起系统安装界面 */
    private static void installApk(Context context, File apk, String language) {
        try {
            Uri uri = FileProvider.getUriForFile(
                    context, context.getPackageName() + ".fileprovider", apk);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(context,
                    t(language, "无法打开安装程序", "Cannot open installer",
                            "Не удалось открыть установщик"),
                    Toast.LENGTH_LONG).show();
        }
    }

    /** 三语字符串 */
    private static String t(String language, String zh, String en, String ru) {
        if ("ru".equals(language)) return ru;
        if ("en".equals(language)) return en;
        return zh;
    }
}
