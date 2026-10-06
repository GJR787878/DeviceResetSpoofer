package io.github.gjr787878.devicereset.ui;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import io.github.gjr787878.devicereset.Config;
import io.github.gjr787878.devicereset.GlassButtonDrawable;
import io.github.gjr787878.devicereset.LSPosedScopeHelper;
import io.github.gjr787878.devicereset.xposed.Identity;
import io.github.gjr787878.devicereset.xposed.IdentityGenerator;
import io.github.gjr787878.devicereset.xposed.SentinelDetector;
import io.github.libxposed.service.XposedService;

public class MainActivity extends AppCompatActivity {
    private static final String PREFS_LANG = "app_language";
    private static final String LANG_ZH = "zh";
    private static final String LANG_EN = "en";
    private static final String LANG_RU = "ru";
    private static final String UPDATE_REPO = "GJR787878/DeviceResetSpooferX";
    private static final String REPO_HOME_URL = "https://github.com/GJR787878/DeviceResetSpooferX";

    private static final int COLOR_WHITE = 0xFFFFFFFF;
    private static final int COLOR_BLUE = 0xFF0A84FF;
    private static final int COLOR_GRAY = 0xFFCCCCCC;
    private static final int COLOR_GREEN = 0xFF34C759;
    private static final int COLOR_DIALOG_BG = 0xFF1C1C1E;

    // ===== 选择应用列表缓存（缓存 + 包名集合对比失效）=====
    // 图标按包名缓存在内存，避免每次打开弹窗都全量解码图标（最慢环节）
    private static final int ICON_CACHE_SIZE = 600;
    private static final android.util.LruCache<String, android.graphics.drawable.Drawable> pickerIconCache =
            new android.util.LruCache<>(ICON_CACHE_SIZE);
    // 条目缓存（label/pkg/system 轻量数据）与上次扫描的包名集合；包名集合不一致=有新装/卸载 → 重建缓存
    private static java.util.List<AppEntry> pickerEntryCache = null;
    private static java.util.Set<String> pickerPkgCache = null;
    // 图标懒加载线程池（后台解码，不阻塞首屏渲染）
    private static final java.util.concurrent.ExecutorService pickerIconPool =
            java.util.concurrent.Executors.newFixedThreadPool(4);

    private String currentLang;
    private FrameLayout contentFrame;
    private Button navApps, navSettings;
    private LinearLayout appListContainer;
    private TextView appCountText;
    // 页面视图缓存：切换 Tab 不再整页重建导致黑屏重载
    private View cachedAppsPage, cachedSettingsPage;
    private GlassButtonDrawable[] hookGlass = new GlassButtonDrawable[7];
    private TextView[] hookTv = new TextView[7];
    // LSPosed 作用域缓存（libxposed getScope 实时读取），主页蓝点旁显示「作用域✓/未授权」
    private final java.util.Set<String> scopeCache = new java.util.HashSet<>();
    private volatile boolean scopeLoaded = false;
    private volatile boolean scopeConnected = false;
    // LSPosed 数据库真实状态（root 读 modules_config.db，不依赖 libxposed service）
    private volatile boolean scopeDbLoaded = false;
    private volatile boolean scopeDbEnabled = false;
    private volatile boolean scopeDbSystem = false;
    private volatile boolean scopeDbSelf = false;
    private final java.util.Set<String> scopeDbTargets = new java.util.HashSet<>();
    private TextView lspStatusTv; // 设置页 LSPosed 框架状态行

    // ===== 三语字符串 =====
    private String t(String zh, String en, String ru) {
        if (LANG_ZH.equals(currentLang)) return zh;
        if (LANG_RU.equals(currentLang)) return ru;
        return en;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 注册 LSPosed 框架 binder：新版 LSPosed 会通过 XposedProvider 注入 service，
        // 之后应用内选目标即可动态请求作用域（免开 LSPosed 管理器）
        LSPosedScopeHelper.init();
        SharedPreferences prefs = getSharedPreferences("devicereset_ui", MODE_PRIVATE);
        currentLang = prefs.getString(PREFS_LANG, LANG_EN);
        buildRootUI();
        checkUpdate(false);
    }

    /** 异步读取 LSPosed 作用域到缓存，用于主页显示「作用域✓/未授权」；
     *  同时 root 读 LSPosed 配置库真实状态（libxposed service 未连接时兜底显示真实注入状态） */
    private void refreshScopeStatus() {
        new Thread(() -> {
            scopeConnected = LSPosedScopeHelper.isConnected();
            java.util.List<String> sc = LSPosedScopeHelper.getScope();
            synchronized (scopeCache) {
                scopeCache.clear();
                if (sc != null) scopeCache.addAll(sc);
            }
            scopeLoaded = true;
            // root 读 db 兜底真实状态
            Config.ScopeDbState dbs = Config.readLSPosedDbState(this);
            if (dbs != null && dbs.dbOk) {
                scopeDbEnabled = dbs.enabled;
                scopeDbSystem = dbs.hasSystem;
                scopeDbSelf = dbs.hasSelf;
                synchronized (scopeDbTargets) {
                    scopeDbTargets.clear();
                    scopeDbTargets.addAll(dbs.targets);
                }
                scopeDbLoaded = true;
            }
            runOnUiThread(() -> {
                refreshAppList();
                if (lspStatusTv != null) {
                    if (scopeConnected) {
                        lspStatusTv.setText(t("LSPosed 框架：已连接，作用域 ",
                                "LSPosed: connected, scope ",
                                "LSPosed: подключён, область ") + scopeCache.size() + t(" 个应用",
                                " app(s)",
                                " приложений"));
                        lspStatusTv.setTextColor(COLOR_BLUE);
                    } else if (scopeDbLoaded && scopeDbEnabled && scopeDbSystem && scopeDbSelf) {
                        lspStatusTv.setText(t("LSPosed 框架：已注入（作用域已自动同步✓，重启后全部生效）",
                                "LSPosed: injected (scope auto-synced✓, reboot to apply all)",
                                "LSPosed: внедрён (область авто-синхр✓, перезагрузка для применения)"));
                        lspStatusTv.setTextColor(COLOR_GREEN);
                    } else if (scopeDbLoaded && scopeDbEnabled && scopeDbSystem) {
                        lspStatusTv.setText(t("LSPosed 框架：已注入（全局模式✓）",
                                "LSPosed: injected (global scope✓)",
                                "LSPosed: внедрён (глобальная область✓)"));
                        lspStatusTv.setTextColor(COLOR_GREEN);
                    } else {
                        lspStatusTv.setText(t("LSPosed 框架：未连接（模块未启用或框架过旧）",
                                "LSPosed: not connected (module disabled or old framework)",
                                "LSPosed: не подключён (модуль выключен или старая версия фреймворка)"));
                        lspStatusTv.setTextColor(COLOR_GRAY);
                    }
                }
            });
        }).start();
    }

    /** 目标是否在 LSPosed 作用域内（框架已连接才有意义）。
     * 系统框架(system)/Android 系统(android) 已勾 = 全局注入模式（zygote 注入所有进程，
     * MainHook 按 targets.txt 过滤），所有目标都算在作用域内。
     * 未连接 libxposed service 时用数据库真实状态兜底（root 读 scope 表）。 */
    private boolean isInScope(String pkg) {
        synchronized (scopeCache) {
            if (scopeLoaded && scopeConnected) {
                if (scopeCache.contains("system") || scopeCache.contains("android")) return true;
                return scopeCache.contains(pkg);
            }
        }
        synchronized (scopeDbTargets) {
            return scopeDbLoaded && (scopeDbSystem || scopeDbTargets.contains(pkg));
        }
    }

    /** 是否为全局注入模式（系统框架已勾选） */
    private boolean isGlobalScope() {
        synchronized (scopeCache) {
            if (scopeLoaded && scopeConnected) {
                return scopeCache.contains("system") || scopeCache.contains("android");
            }
        }
        return scopeDbLoaded && scopeDbSystem;
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 异步刷新 LSPosed 作用域状态（主页可见「作用域✓/未授权」）
        refreshScopeStatus();
        // 兜底：已连接 libxposed 框架时作用域由 requestScope/removeScope 维护，无需写库；
        // 仅老框架（无 libxposed service）回退 root 写库同步（无目标/非 LSPosed 环境自动跳过，不打扰）
        // 3.9.2: 不再 root 写 LSPosed 库（全局注入+哨兵即可，避免与手动勾选冲突）
    }

    private void buildRootUI() {
        float d = getResources().getDisplayMetrics().density;
        // §3.4 平板适配：smallestScreenWidthDp >= 600 视为平板
        boolean isTablet = getResources().getConfiguration().smallestScreenWidthDp >= 600;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        contentFrame = new FrameLayout(this);
        FrameLayout.LayoutParams contentLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);

        // §3.6.1 导航栏磨砂渐变
        GradientDrawable navBg;
        LinearLayout navBar = new LinearLayout(this);
        FrameLayout.LayoutParams navLp;

        if (isTablet) {
            // §3.4 平板：左侧竖排悬浮胶囊，垂直居中，半屏高
            navBar.setOrientation(LinearLayout.VERTICAL);
            navBar.setGravity(Gravity.CENTER_VERTICAL);
            navBg = new GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{0xF06A6A72, 0x882C2C2E});
            navBg.setCornerRadius(28 * d);
            navBg.setStroke(Math.round(1 * d), 0x55FFFFFF);
            navBar.setBackground(navBg);
            // 左侧悬浮：宽约100dp，高半屏，垂直居中
            navLp = new FrameLayout.LayoutParams(
                    Math.round(100 * d), Math.round(300 * d));
            navLp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
            navLp.leftMargin = Math.round(16 * d);
            // 内容区左侧让出导航空间
            contentLp.leftMargin = Math.round(132 * d);
            contentLp.bottomMargin = 0;
        } else {
            // 手机：底部横排导航
            navBar.setOrientation(LinearLayout.HORIZONTAL);
            navBar.setGravity(Gravity.CENTER);
            navBg = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0xF06A6A72, 0x882C2C2E});
            navBg.setCornerRadius(28 * d);
            navBg.setStroke(Math.round(1 * d), 0x55FFFFFF);
            navBar.setBackground(navBg);
            navLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.round(64 * d));
            navLp.gravity = Gravity.BOTTOM;
            navLp.leftMargin = Math.round(16 * d);
            navLp.rightMargin = Math.round(16 * d);
            navLp.bottomMargin = Math.round(16 * d);
            contentLp.bottomMargin = Math.round(80 * d);
        }

        root.addView(contentFrame, contentLp);
        root.addView(navBar, navLp);

        navApps = makeNavButton();
        navSettings = makeNavButton();

        if (isTablet) {
            // 平板：竖排按钮，宽充满，高约80dp
            LinearLayout.LayoutParams navBtnLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.round(80 * d));
            navBtnLp.topMargin = Math.round(8 * d);
            navBtnLp.bottomMargin = Math.round(8 * d);
            navBtnLp.leftMargin = Math.round(8 * d);
            navBtnLp.rightMargin = Math.round(8 * d);
            navBar.addView(navApps, navBtnLp);
            navBar.addView(navSettings, navBtnLp);
        } else {
            // 手机：横排按钮
            LinearLayout.LayoutParams navBtnLp = new LinearLayout.LayoutParams(
                    0, Math.round(48 * d), 1f);
            navBtnLp.leftMargin = Math.round(4 * d);
            navBtnLp.rightMargin = Math.round(4 * d);
            navBar.addView(navApps, navBtnLp);
            navBar.addView(navSettings, navBtnLp);
        }

        setContentView(root);
        switchTab(0);
    }

    private Button makeNavButton() {
        Button b = new Button(this);
        b.setTextSize(15);
        b.setAllCaps(false);
        b.setBackgroundColor(Color.TRANSPARENT);
        return b;
    }

    private void switchTab(int idx) {
        boolean isApps = idx == 0;
        // 只在首次进入时构建页面，之后复用缓存视图（避免黑屏重新加载）
        if (isApps && cachedAppsPage == null) cachedAppsPage = buildAppsPage();
        if (!isApps && cachedSettingsPage == null) cachedSettingsPage = buildSettingsPage();
        contentFrame.removeAllViews();
        contentFrame.addView(isApps ? cachedAppsPage : cachedSettingsPage);
        float d = getResources().getDisplayMetrics().density;
        GradientDrawable selBg = new GradientDrawable();
        selBg.setColor(0x330A84FF);
        selBg.setCornerRadius(20 * d);
        navApps.setText(t("应用", "Apps", "Приложения"));
        navSettings.setText(t("设置", "Settings", "Настройки"));
        navApps.setTextColor(isApps ? COLOR_BLUE : COLOR_WHITE);
        navSettings.setTextColor(isApps ? COLOR_WHITE : COLOR_BLUE);
        navApps.setBackground(isApps ? selBg : null);
        navSettings.setBackground(isApps ? null : selBg);
        navApps.setOnClickListener(v -> switchTab(0));
        navSettings.setOnClickListener(v -> switchTab(1));
    }

    // ===== 应用页 =====
    private View buildAppsPage() {
        float d = getResources().getDisplayMetrics().density;
        ScrollView sv = new ScrollView(this);
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(Math.round(24 * d), Math.round(48 * d), Math.round(24 * d), Math.round(32 * d));

        TextView title = new TextView(this);
        title.setText(t("选择应用", "Select App", "Выберите приложение"));
        title.setTextSize(28);
        title.setTextColor(COLOR_WHITE);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        ll.addView(title);

        // 操作行：刷新按钮 + 选择应用按钮（缩小）+ 右侧计数，同一行
        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);

        // 刷新：圆形玻璃按钮
        Button btnRefresh = new Button(this);
        btnRefresh.setText("↻");
        btnRefresh.setTextSize(15);
        btnRefresh.setTextColor(COLOR_WHITE);
        btnRefresh.setAllCaps(false);
        btnRefresh.setBackground(new GlassButtonDrawable(22 * d, 1 * d, false));
        actionRow.addView(btnRefresh, new LinearLayout.LayoutParams(
                Math.round(44 * d), Math.round(44 * d)));
        btnRefresh.setOnClickListener(v -> refreshAppList());

        // 选择应用：缩小的玻璃胶囊按钮
        Button btnAddApp = makeGlassBtn(t("＋ 选择应用", "＋ Select App", "＋ Выбрать"), 12);
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        addLp.leftMargin = Math.round(10 * d);
        actionRow.addView(btnAddApp, addLp);
        btnAddApp.setOnClickListener(v -> showAllAppsPicker());

        // 计数：右侧
        appCountText = new TextView(this);
        appCountText.setText("");
        appCountText.setTextSize(13);
        appCountText.setTextColor(COLOR_GRAY);
        appCountText.setGravity(Gravity.END);
        LinearLayout.LayoutParams countLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        countLp.leftMargin = Math.round(10 * d);
        actionRow.addView(appCountText, countLp);

        LinearLayout.LayoutParams actionRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionRowLp.topMargin = Math.round(8 * d);
        ll.addView(actionRow, actionRowLp);

        appListContainer = new LinearLayout(this);
        appListContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        listLp.topMargin = Math.round(16 * d);
        ll.addView(appListContainer, listLp);

        sv.addView(ll);
        refreshAppList();
        return sv;
    }

    private void refreshAppList() {
        appListContainer.removeAllViews();
        appCountText.setText(t("扫描中...", "Scanning...", "Сканирование..."));
        new Thread(() -> {
            final java.util.List<String> pkgs = scanPackagesWithIdentity();
            runOnUiThread(() -> {
                appCountText.setText(pkgs.size() + t(" 个目标应用", " target app(s)", " целевых приложений"));
                if (pkgs.isEmpty()) {
                    TextView empty = new TextView(this);
                    empty.setText(t("暂无目标应用。\n请点击「＋ 选择应用」添加目标，再在应用详情中点击「随机」或「自定义」生成伪装值（手动触发后生效）。",
                            "No target apps yet.\nTap \"+ Select App\" to add a target, then tap Random / Customize in its detail dialog to generate a spoofed identity (applies after manual trigger).",
                            "Нет целевых приложений.\nНажмите «+ Выбрать приложение», затем «Случайно» или «Настроить» в диалоге приложения, чтобы создать подменённую идентичность (применяется после ручного запуска)."));
                    empty.setTextSize(14);
                    empty.setTextColor(COLOR_GRAY);
                    empty.setPadding(0, 40, 0, 0);
                    appListContainer.addView(empty);
                    return;
                }
                PackageManager pm = getPackageManager();
                float d = getResources().getDisplayMetrics().density;
                for (String pkg : pkgs) {
                    try {
                        ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                        String name = pm.getApplicationLabel(ai).toString();
                        appListContainer.addView(makeAppRow(ai, name, pkg, d));
                    } catch (Throwable e) {
                        appListContainer.addView(makeAppRow(null, pkg, pkg, d));
                    }
                }
            });
        }).start();
    }

    private View makeAppRow(ApplicationInfo ai, String name, String pkg, float d) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Math.round(16 * d), Math.round(14 * d), Math.round(16 * d), Math.round(14 * d));
        row.setBackground(new GlassButtonDrawable(24 * d, 1 * d, false));

        ImageView icon = new ImageView(this);
        icon.setLayoutParams(new LinearLayout.LayoutParams(Math.round(48 * d), Math.round(48 * d)));
        try { if (ai != null) icon.setImageDrawable(getPackageManager().getApplicationIcon(ai)); } catch (Throwable ignored) {}
        row.addView(icon);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textLp.leftMargin = Math.round(14 * d);
        TextView tvName = new TextView(this);
        tvName.setText(name);
        tvName.setTextSize(16);
        tvName.setTextColor(COLOR_WHITE);
        TextView tvPkg = new TextView(this);
        tvPkg.setText(pkg);
        tvPkg.setTextSize(12);
        tvPkg.setTextColor(COLOR_GRAY);
        textCol.addView(tvName);
        textCol.addView(tvPkg);
        row.addView(textCol, textLp);

        // 蓝点 = 已手动设置伪装值（点「随机」/「保存」触发）；仅加入列表不会亮
        boolean hasIdentity = Config.isIdentityConfigured(this, pkg);
        View dot = new View(this);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setColor(COLOR_BLUE);
        dotBg.setShape(GradientDrawable.OVAL);
        dot.setBackground(dotBg);
        dot.setVisibility(hasIdentity ? View.VISIBLE : View.INVISIBLE);
        row.addView(dot, new LinearLayout.LayoutParams(Math.round(12 * d), Math.round(12 * d)));

        // 作用域状态：libxposed 未连接时用数据库真实注入状态兜底显示「已注入✓」，避免误导「框架未连接」
        if (scopeLoaded || scopeDbLoaded) {
            TextView scopeTv = new TextView(this);
            scopeTv.setTextSize(10);
            if (scopeConnected) {
                if (isInScope(pkg)) {
                    if (isGlobalScope()) {
                        scopeTv.setText(t("作用域✓全局", "Scoped✓global", "В области✓глоб"));
                    } else {
                        scopeTv.setText(t("作用域✓", "Scoped✓", "В области✓"));
                    }
                    scopeTv.setTextColor(COLOR_BLUE);
                } else {
                    scopeTv.setText(t("未授权", "Not scoped", "Не в области"));
                    scopeTv.setTextColor(COLOR_GRAY);
                }
            } else if (scopeDbLoaded && scopeDbEnabled && scopeDbSystem) {
                // 数据库实证：模块已启用 + 系统框架已勾 = 全局注入生效（真授权机制在线）
                scopeTv.setText(t("已注入✓", "Injected✓", "Внедрён✓"));
                scopeTv.setTextColor(COLOR_GREEN);
            } else if (scopeDbLoaded && scopeDbEnabled) {
                scopeTv.setText(t("模块已启用", "Module on", "Модуль вкл"));
                scopeTv.setTextColor(COLOR_GRAY);
            } else {
                scopeTv.setText(t("框架未连接", "No fw", "Нет фрейм"));
                scopeTv.setTextColor(COLOR_GRAY);
            }
            LinearLayout.LayoutParams scopeLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            scopeLp.leftMargin = Math.round(6 * d);
            row.addView(scopeTv, scopeLp);
        }

        row.setOnClickListener(v -> showAppDetailDialog(pkg, name));
        // 长按删除：统一玻璃确认弹窗，取消选择并删除身份（哨兵文件 + 模块配置，不卸载应用本身）
        row.setOnLongClickListener(v -> {
            showGlassConfirm(
                    t("删除目标应用", "Remove Target App", "Удалить приложение"),
                    t("确定删除「", "Remove \"", "Удалить «") + name
                            + t("」？将取消选择并删除其身份与哨兵文件（不会卸载应用本身）。",
                                "\"? It will be deselected and its identity & sentinel files deleted (the app itself is not uninstalled).",
                                "»? Приложение будет убрано из целей, идентичность и сторожевые файлы удалены (само приложение не удаляется)."),
                    t("删除", "Remove", "Удалить"), true, () -> {
                        Config.removeTargetPackage(this, pkg);
                        Config.removeIdentity(this, pkg);
                        deleteIdentityFiles(pkg);
                        if (LSPosedScopeHelper.isConnected()) {
                            LSPosedScopeHelper.removeScope(pkg);
                        } else {
                            // 3.9.2: 哨兵驱动，无需 root 写库
                        }
                        refreshAppList();
                        Toast.makeText(this,
                                t("已删除: ", "Removed: ", "Удалено: ") + name,
                                Toast.LENGTH_SHORT).show();
                    });
            return true;
        });
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = Math.round(10 * d);
        row.setLayoutParams(rowLp);
        return row;
    }

    // ===== 应用详情弹窗 =====
    private void showAppDetailDialog(String pkg, String appName) {
        // 身份读取：统一读取器（配置→内部哨兵→外部哨兵，与 MainHook 生效顺序一致；单点缺失自动回写对齐）
        String json = readIdentityAll(pkg);
        // 打开详情即视为选中目标应用（同步到模块配置，MainHook 只对目标生效）
        getSharedPreferences("devicereset_ui", MODE_PRIVATE).edit().putString("last_target_pkg", pkg).apply();
        Config.addTargetPackage(this, pkg);
        if (LSPosedScopeHelper.isConnected()) {
            LSPosedScopeHelper.requestScope(pkg, scopeEventListener());
        } else {
            // 3.9.2: 哨兵驱动，无需 root 写库
        }
        Identity id = (json != null && json.startsWith("{")) ? Identity.fromJson(json) : null;

        float d = getResources().getDisplayMetrics().density;
        Dialog dialog = new Dialog(this, android.R.style.Theme_Translucent_NoTitleBar);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Math.round(20 * d), Math.round(20 * d), Math.round(20 * d), Math.round(16 * d));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(COLOR_DIALOG_BG);
        bg.setCornerRadius(24 * d);
        root.setBackground(bg);

        TextView title = new TextView(this);
        title.setText(t("应用: ", "App: ", "Приложение: ") + appName + "\n" + pkg);
        title.setTextSize(16);
        title.setTextColor(COLOR_WHITE);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        root.addView(title);

        ScrollView sv = new ScrollView(this);
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        svLp.topMargin = Math.round(12 * d);
        svLp.bottomMargin = Math.round(12 * d);

        TextView content = new TextView(this);
        content.setTextSize(13);
        content.setTextColor(COLOR_GRAY);
        StringBuilder sb = new StringBuilder();
        String lAndroidId = t("Android ID: ", "Android ID: ", "Android ID: ");
        String lAdId = t("广告ID: ", "Ad ID: ", "Рекл. ID: ");
        String lAppSet = t("AppSet ID: ", "AppSet ID: ", "AppSet ID: ");
        String lImei = t("IMEI: ", "IMEI: ", "IMEI: ");
        String lMeid = t("MEID: ", "MEID: ", "MEID: ");
        String lSerial = t("序列号: ", "Serial: ", "Серийный: ");
        String lMac = t("MAC: ", "MAC: ", "MAC: ");
        String lGsf = t("GSF ID: ", "GSF ID: ", "GSF ID: ");
        String lBrand = t("品牌: ", "Brand: ", "Бренд: ");
        String lModel = t("型号: ", "Model: ", "Модель: ");
        String lMfr = t("厂商: ", "Manufacturer: ", "Производитель: ");
        String lFp = t("指纹: ", "Fingerprint: ", "Отпечаток: ");
        String lBuild = t("Build ID: ", "Build ID: ", "Build ID: ");
        String lCarrier = t("运营商: ", "Carrier: ", "Оператор: ");
        String lCarrierCode = t("运营商代码: ", "Carrier Code: ", "Код оператора: ");
        String lImsi = t("IMSI: ", "IMSI: ", "IMSI: ");
        String lIccid = t("ICCID: ", "ICCID: ", "ICCID: ");

        if (id == null) {
            sb.append(t("未设置伪装值。\n点击下方「随机」或「自定义」生成（手动触发后生效）。",
                    "No spoofed identity set.\nTap Random or Customize below to generate (applies after manual trigger).",
                    "Подменённая идентичность не установлена.\nНажмите «Случайно» или «Настроить», чтобы создать (применяется после ручного запуска)."));
        } else {
            if (id.androidId != null) sb.append(lAndroidId).append(id.androidId).append("\n");
            if (id.advertisingId != null) sb.append(lAdId).append(id.advertisingId).append("\n");
            if (id.appSetId != null) sb.append(lAppSet).append(id.appSetId).append("\n");
            if (id.imei != null) sb.append(lImei).append(id.imei).append("\n");
            if (id.meid != null) sb.append(lMeid).append(id.meid).append("\n");
            if (id.serial != null) sb.append(lSerial).append(id.serial).append("\n");
            if (id.macAddress != null) sb.append(lMac).append(id.macAddress).append("\n");
            if (id.gsfId != null) sb.append(lGsf).append(id.gsfId).append("\n");
            sb.append("\n");
            if (id.brand != null) sb.append(lBrand).append(id.brand).append("\n");
            if (id.model != null) sb.append(lModel).append(id.model).append("\n");
            if (id.manufacturer != null) sb.append(lMfr).append(id.manufacturer).append("\n");
            if (id.fingerprint != null) sb.append(lFp).append(id.fingerprint).append("\n");
            if (id.buildId != null) sb.append(lBuild).append(id.buildId).append("\n");
            sb.append("\n");
            if (id.networkOperatorName != null) sb.append(lCarrier).append(id.networkOperatorName).append("\n");
            if (id.networkOperator != null) sb.append(lCarrierCode).append(id.networkOperator).append("\n");
            if (id.imsi != null) sb.append(lImsi).append(id.imsi).append("\n");
            if (id.iccid != null) sb.append(lIccid).append(id.iccid).append("\n");
        }
        content.setText(sb.toString());
        sv.addView(content);
        root.addView(sv, svLp);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        Button btnCustom = makeGlassBtn(t("自定义", "Customize", "Настроить"), 13);
        Button btnRandom = makeGlassBtn(t("随机", "Random", "Случайно"), 13);
        Button btnBack = makeGlassBtn(t("返回", "Back", "Назад"), 13);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        btnLp.leftMargin = Math.round(3 * d);
        btnLp.rightMargin = Math.round(3 * d);
        btnRow.addView(btnCustom, btnLp);
        btnRow.addView(btnRandom, btnLp);
        btnRow.addView(btnBack, btnLp);
        root.addView(btnRow);

        dialog.setContentView(root);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setLayout(Math.round(340 * d), Math.round(520 * d));
            w.setGravity(Gravity.CENTER);
            w.setDimAmount(0.6f);
        }
        dialog.show();

        btnBack.setOnClickListener(v -> dialog.dismiss());
        btnRandom.setOnClickListener(v -> {
            Identity newId = IdentityGenerator.generateRandom();
            IdentityGenerator.fillMissing(newId);
            String rndJson = newId.toJson();
            // 统一三写（配置+内部+外部），保证 UI 显示与目标进程生效值一致
            writeIdentityAll(pkg, rndJson);
            dialog.dismiss();
            refreshAppList();
            // 保存成功后询问是否清空数据（部分应用需清空数据才能立即生效）
            showClearDataPrompt(pkg, rndJson);
        });
        btnCustom.setOnClickListener(v -> {
            dialog.dismiss();
            showCustomizeDialog(pkg, appName, id);
        });
    }

    private Button makeGlassBtn(String text, float size) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(size);
        b.setTextColor(COLOR_WHITE);
        b.setAllCaps(false);
        float d = getResources().getDisplayMetrics().density;
        b.setBackground(new GlassButtonDrawable(24 * d, 1 * d, false));
        return b;
    }

    /** 统一的玻璃风格确认弹窗：标题 + 说明 + 取消/确认 两个玻璃胶囊按钮 */
    interface ConfirmCallback { void onConfirm(); }
    private void showGlassConfirm(String titleText, String message, String positiveText,
                                  boolean emphasize, ConfirmCallback cb) {
        final float d = getResources().getDisplayMetrics().density;
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Math.round(20 * d), Math.round(20 * d), Math.round(20 * d), Math.round(18 * d));
        GradientDrawable rootBg = new GradientDrawable();
        rootBg.setColor(COLOR_DIALOG_BG);
        rootBg.setCornerRadius(24 * d);
        rootBg.setStroke(Math.round(1 * d), 0x55FFFFFF);
        root.setBackground(rootBg);

        TextView tvTitle = new TextView(this);
        tvTitle.setText(titleText);
        tvTitle.setTextSize(17);
        tvTitle.setTextColor(COLOR_WHITE);
        tvTitle.setTypeface(tvTitle.getTypeface(), android.graphics.Typeface.BOLD);
        root.addView(tvTitle);

        if (message != null && message.length() > 0) {
            TextView tvMsg = new TextView(this);
            tvMsg.setText(message);
            tvMsg.setTextSize(14);
            tvMsg.setTextColor(COLOR_GRAY);
            tvMsg.setLineSpacing(4, 1);
            LinearLayout.LayoutParams msgLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            msgLp.topMargin = Math.round(12 * d);
            root.addView(tvMsg, msgLp);
        }

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams btnRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnRowLp.topMargin = Math.round(18 * d);

        Button btnCancel = makeGlassBtn(t("取消", "Cancel", "Отмена"), 14);
        btnRow.addView(btnCancel, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        btnCancel.setOnClickListener(v -> dialog.dismiss());

        Button btnOk = new Button(this);
        btnOk.setText(positiveText);
        btnOk.setTextSize(14);
        btnOk.setTextColor(emphasize ? COLOR_BLUE : COLOR_WHITE);
        btnOk.setAllCaps(false);
        btnOk.setBackground(new GlassButtonDrawable(24 * d, emphasize ? 2 * d : 1 * d, emphasize));
        LinearLayout.LayoutParams okLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        okLp.leftMargin = Math.round(10 * d);
        btnRow.addView(btnOk, okLp);
        btnOk.setOnClickListener(v -> {
            dialog.dismiss();
            cb.onConfirm();
        });

        root.addView(btnRow, btnRowLp);
        dialog.setContentView(root);
        Window win = dialog.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x88000000));
            win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    // ===== 自定义界面 =====
    interface FieldSetter { void set(String val); }

    private void showCustomizeDialog(String pkg, String appName, Identity currentId) {
        float d = getResources().getDisplayMetrics().density;
        final Identity[] workingId = {currentId != null ? currentId : IdentityGenerator.generateRandom()};
        // 修复：IMSI/ICCID 等字段默认空的问题 —— 缺什么补什么（用随机值补齐，其余保持用户已填内容）
        IdentityGenerator.fillMissing(workingId[0]);

        Dialog dialog = new Dialog(this, android.R.style.Theme_Translucent_NoTitleBar);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF000000);
        root.setPadding(Math.round(24 * d), Math.round(40 * d), Math.round(24 * d), Math.round(24 * d));

        TextView title = new TextView(this);
        title.setText(t("自定义身份", "Customize Identity", "Настройка идентичности"));
        title.setTextSize(22);
        title.setTextColor(COLOR_WHITE);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        root.addView(title);
        TextView sub = new TextView(this);
        sub.setText(appName + " (" + pkg + ")");
        sub.setTextSize(13);
        sub.setTextColor(COLOR_GRAY);
        root.addView(sub);

        ScrollView sv = new ScrollView(this);
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        svLp.topMargin = Math.round(16 * d);
        svLp.bottomMargin = Math.round(12 * d);

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);

        addSectionLabel(form, t("设备型号", "Device Profile", "Модель устройства"), d);
        Button deviceBtn = makeGlassBtn(t("设备: ", "Device: ", "Устройство: ") + (workingId[0].model != null ? workingId[0].model : t("点击选择", "Tap to select", "Нажмите для выбора")), 14);
        form.addView(deviceBtn, makeFormLp(d));
        deviceBtn.setOnClickListener(v -> showDevicePicker(workingId[0], deviceBtn));

        addSectionLabel(form, t("运营商", "Carrier", "Оператор"), d);
        Button carrierBtn = makeGlassBtn(t("运营商: ", "Carrier: ", "Оператор: ") + (workingId[0].networkOperatorName != null ? workingId[0].networkOperatorName : t("点击选择", "Tap to select", "Нажмите для выбора")), 14);
        form.addView(carrierBtn, makeFormLp(d));
        carrierBtn.setOnClickListener(v -> showCarrierPicker(workingId[0], carrierBtn));

        addSectionLabel(form, t("标识符", "Identifiers", "Идентификаторы"), d);
        addFieldRow(form, "Android ID", workingId[0].androidId, d, val -> workingId[0].androidId = val, true);
        addFieldRow(form, t("广告ID", "Ad ID (AAID)", "Рекл. ID"), workingId[0].advertisingId, d, val -> workingId[0].advertisingId = val, true);
        addFieldRow(form, "AppSet ID", workingId[0].appSetId, d, val -> workingId[0].appSetId = val, true);
        addFieldRow(form, "GSF ID", workingId[0].gsfId, d, val -> workingId[0].gsfId = val, true);
        addFieldRow(form, "IMEI", workingId[0].imei, d, val -> workingId[0].imei = val, true);
        addFieldRow(form, "MEID", workingId[0].meid, d, val -> workingId[0].meid = val, true);
        addFieldRow(form, t("序列号", "Serial", "Серийный"), workingId[0].serial, d, val -> workingId[0].serial = val, true);
        addFieldRow(form, t("MAC地址", "MAC Address", "MAC-адрес"), workingId[0].macAddress, d, val -> workingId[0].macAddress = val, true);

        addSectionLabel(form, t("Build信息", "Build Info", "Build инфо"), d);
        addFieldRow(form, "Build ID", workingId[0].buildId, d, val -> workingId[0].buildId = val, true);
        addFieldRow(form, "Bootloader", workingId[0].bootloader, d, val -> workingId[0].bootloader = val, true);
        addFieldRow(form, t("Radio版本", "Radio Version", "Версия Radio"), workingId[0].radioVersion, d, val -> workingId[0].radioVersion = val, true);

        addSectionLabel(form, t("SIM信息", "SIM Info", "SIM инфо"), d);
        addFieldRow(form, "IMSI", workingId[0].imsi, d, val -> workingId[0].imsi = val, true);
        addFieldRow(form, "ICCID", workingId[0].iccid, d, val -> workingId[0].iccid = val, true);

        sv.addView(form);
        root.addView(sv, svLp);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        Button btnSave = makeGlassBtn(t("保存", "Save", "Сохранить"), 15);
        Button btnCancel = makeGlassBtn(t("取消", "Cancel", "Отмена"), 15);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        blp.leftMargin = Math.round(4 * d);
        blp.rightMargin = Math.round(4 * d);
        btnRow.addView(btnCancel, blp);
        btnRow.addView(btnSave, blp);
        root.addView(btnRow);

        dialog.setContentView(root);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            w.setDimAmount(0f);
        }
        dialog.show();

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnSave.setOnClickListener(v -> {
            Identity toSave = workingId[0];
            // 保存前补齐缺失字段，避免存出空值
            IdentityGenerator.fillMissing(toSave);
            String json = toSave.toJson();
            // 统一三写（配置+内部+外部），保证 UI 显示与目标进程生效值一致
            writeIdentityAll(pkg, json);
            dialog.dismiss();
            refreshAppList();
            // 保存成功后询问是否清空数据（部分应用需清空数据才能立即生效）
            showClearDataPrompt(pkg, json);
        });
    }

    private void addFieldRow(LinearLayout form, String label, String currentVal, float d, FieldSetter setter, boolean canRandom) {
        TextView lbl = new TextView(this);
        lbl.setText(label);
        lbl.setTextSize(12);
        lbl.setTextColor(COLOR_GRAY);
        form.addView(lbl);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        EditText et = new EditText(this);
        et.setText(currentVal != null ? currentVal : "");
        et.setTextSize(13);
        et.setTextColor(COLOR_WHITE);
        et.setHintTextColor(0x66FFFFFF);
        // 胶囊圆角输入框：半透明玻璃底 + 1px 亮描边 + 全圆角，与玻璃按钮风格统一
        GradientDrawable etBg = new GradientDrawable();
        etBg.setColor(0x221C1C1E);
        etBg.setCornerRadius(20 * d);
        etBg.setStroke(Math.round(1 * d), 0x55FFFFFF);
        et.setBackground(etBg);
        et.setPadding(Math.round(14 * d), Math.round(10 * d), Math.round(14 * d), Math.round(10 * d));
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(et, etLp);

        if (canRandom) {
            Button rnd = makeGlassBtn(t("随机", "Rnd", "Случ"), 11);
            LinearLayout.LayoutParams rndLp = new LinearLayout.LayoutParams(Math.round(64 * d), ViewGroup.LayoutParams.WRAP_CONTENT);
            rndLp.leftMargin = Math.round(10 * d);
            row.addView(rnd, rndLp);
            rnd.setOnClickListener(v -> {
                Identity tmp = IdentityGenerator.generateRandom();
                String newVal = null;
                if ("Android ID".equals(label)) newVal = tmp.androidId;
                else if (label.contains("Ad ID") || label.contains("广告")) newVal = tmp.advertisingId;
                else if ("AppSet ID".equals(label)) newVal = tmp.appSetId;
                else if ("GSF ID".equals(label)) newVal = tmp.gsfId;
                else if ("IMEI".equals(label)) newVal = tmp.imei;
                else if ("MEID".equals(label)) newVal = tmp.meid;
                else if (label.contains("Serial") || label.contains("序列")) newVal = tmp.serial;
                else if (label.contains("MAC")) newVal = tmp.macAddress;
                else if ("Build ID".equals(label)) newVal = tmp.buildId;
                else if ("Bootloader".equals(label)) newVal = tmp.bootloader;
                else if (label.contains("Radio")) newVal = tmp.radioVersion;
                else if ("IMSI".equals(label)) newVal = tmp.imsi;
                else if ("ICCID".equals(label)) newVal = tmp.iccid;
                // 修复：不再默认填 "?" —— 匹配不到则保持原值
                if (newVal != null) et.setText(newVal);
            });
        }
        et.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) setter.set(et.getText().toString().trim());
        });
        form.addView(row, makeFormLp(d));
    }

    private LinearLayout.LayoutParams makeFormLp(float d) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Math.round(10 * d);
        return lp;
    }

    private void addSectionLabel(LinearLayout form, String text, float d) {
        TextView lbl = new TextView(this);
        lbl.setText(text);
        lbl.setTextSize(15);
        lbl.setTextColor(COLOR_WHITE);
        lbl.setTypeface(lbl.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(16 * d);
        lp.bottomMargin = Math.round(8 * d);
        form.addView(lbl, lp);
    }

    private void showDevicePicker(Identity id, Button updateBtn) {
        try {
            java.lang.reflect.Field f = IdentityGenerator.class.getDeclaredField("DEVICE_PROFILES");
            f.setAccessible(true);
            String[][] profiles = (String[][]) f.get(null);
            String[] names = new String[profiles.length];
            for (int i = 0; i < profiles.length; i++) names[i] = profiles[i][0] + " " + profiles[i][1];
            new AlertDialog.Builder(this)
                    .setTitle(t("选择设备", "Select Device", "Выберите устройство"))
                    .setItems(names, (d, which) -> {
                        String[] p = profiles[which];
                        id.brand = p[0]; id.model = p[1]; id.manufacturer = p[2];
                        id.device = p[3]; id.product = p[4]; id.hardware = p[5]; id.fingerprint = p[6];
                        updateBtn.setText(t("设备: ", "Device: ", "Устройство: ") + p[1]);
                    })
                    .setNegativeButton(t("取消", "Cancel", "Отмена"), null)
                    .show();
        } catch (Throwable e) {
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void showCarrierPicker(Identity id, Button updateBtn) {
        try {
            java.lang.reflect.Field f = IdentityGenerator.class.getDeclaredField("CARRIER_PROFILES");
            f.setAccessible(true);
            String[][] carriers = (String[][]) f.get(null);
            String[] names = new String[carriers.length];
            for (int i = 0; i < carriers.length; i++) names[i] = carriers[i][1] + " (" + carriers[i][0] + ")";
            new AlertDialog.Builder(this)
                    .setTitle(t("选择运营商", "Select Carrier", "Выберите оператора"))
                    .setItems(names, (d, which) -> {
                        String[] c = carriers[which];
                        id.networkOperator = c[0]; id.networkOperatorName = c[1];
                        id.simOperator = c[0]; id.simOperatorName = c[1];
                        id.simCountryIso = c[2]; id.networkCountryIso = c[2];
                        Identity tmp = IdentityGenerator.generateRandom();
                        id.imsi = c[0] + tmp.imsi.substring(Math.min(c[0].length(), tmp.imsi.length()));
                        updateBtn.setText(t("运营商: ", "Carrier: ", "Оператор: ") + c[1]);
                    })
                    .setNegativeButton(t("取消", "Cancel", "Отмена"), null)
                    .show();
        } catch (Throwable e) {
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // ===== 应用内选择任意应用 =====

    /** 全部应用选择器：自定义可滚动弹窗（带搜索），列出全部已安装应用，选中即添加为目标 */
    private void showAllAppsPicker() {
        final float d = getResources().getDisplayMetrics().density;
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Math.round(20 * d), Math.round(20 * d), Math.round(20 * d), Math.round(16 * d));
        GradientDrawable rootBg = new GradientDrawable();
        rootBg.setColor(COLOR_DIALOG_BG);
        rootBg.setCornerRadius(24 * d);
        rootBg.setStroke(Math.round(1 * d), 0x55FFFFFF);
        root.setBackground(rootBg);

        // 标题行：标题 + 右侧筛选按钮（小方形玻璃按钮，弹出 系统应用/安装应用 选项）
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(t("选择目标应用", "Select Target App", "Выберите приложение"));
        title.setTextSize(18);
        title.setTextColor(COLOR_WHITE);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final Button btnFilter = new Button(this);
        btnFilter.setText(t("筛选 ▾", "Filter ▾", "Фильтр ▾"));
        btnFilter.setTextSize(12);
        btnFilter.setTextColor(COLOR_WHITE);
        btnFilter.setAllCaps(false);
        final GlassButtonDrawable filterGlass = new GlassButtonDrawable(16 * d, 1 * d, false);
        btnFilter.setBackground(filterGlass);
        titleRow.addView(btnFilter, new LinearLayout.LayoutParams(
                Math.round(76 * d), ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(titleRow);

        // 筛选模式：0=全部, 1=系统应用, 2=安装应用（记住上次选择）
        final int[] filterMode = {getSharedPreferences("devicereset_ui", MODE_PRIVATE)
                .getInt("picker_filter_mode", 0)};

        // 搜索框（胶囊样式，与输入框统一）
        final EditText search = new EditText(this);
        search.setHint(t("搜索应用...", "Search apps...", "Поиск..."));
        search.setTextSize(14);
        search.setTextColor(COLOR_WHITE);
        search.setSingleLine(true);
        GradientDrawable sBg = new GradientDrawable();
        sBg.setColor(0x221C1C1E);
        sBg.setCornerRadius(20 * d);
        sBg.setStroke(Math.round(1 * d), 0x55FFFFFF);
        search.setBackground(sBg);
        search.setPadding(Math.round(14 * d), Math.round(10 * d), Math.round(14 * d), Math.round(10 * d));
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sLp.topMargin = Math.round(12 * d);
        root.addView(search, sLp);

        // 可滚动列表（ScrollView，确保能向下滑动选到全部应用）
        ScrollView sv = new ScrollView(this);
        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        svLp.topMargin = Math.round(10 * d);
        root.addView(sv, svLp);

        final TextView emptyTv = new TextView(this);
        emptyTv.setText(t("无匹配应用", "No matching apps", "Нет приложений"));
        emptyTv.setTextColor(COLOR_GRAY);
        emptyTv.setTextSize(13);
        emptyTv.setGravity(Gravity.CENTER);
        emptyTv.setVisibility(View.GONE);
        LinearLayout.LayoutParams emptyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(emptyTv, emptyLp);

        Button btnCancel = makeGlassBtn(t("取消", "Cancel", "Отмена"), 14);
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cancelLp.topMargin = Math.round(10 * d);
        root.addView(btnCancel, cancelLp);
        btnCancel.setOnClickListener(v -> dialog.dismiss());

        dialog.setContentView(root);
        Window win = dialog.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x88000000));
            win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        dialog.show();

        // 筛选小方形弹窗（锚定筛选按钮，同位置弹出）
        btnFilter.setOnClickListener(vv -> {
            LinearLayout pop = new LinearLayout(this);
            pop.setOrientation(LinearLayout.VERTICAL);
            pop.setPadding(Math.round(6 * d), Math.round(6 * d), Math.round(6 * d), Math.round(6 * d));
            GradientDrawable popBg = new GradientDrawable();
            popBg.setColor(0xFF2C2C2E);
            popBg.setCornerRadius(16 * d);
            popBg.setStroke(Math.round(1 * d), 0x55FFFFFF);
            pop.setBackground(popBg);

            String[] optTexts = {t("系统应用", "System apps", "Системные приложения"),
                    t("安装应用", "Installed apps", "Установленные приложения")};
            final int[] optModes = {1, 2};
            final PopupWindow[] pwHolder = {null};
            for (int oi = 0; oi < 2; oi++) {
                TextView o = new TextView(this);
                o.setText(optTexts[oi] + (filterMode[0] == optModes[oi] ? "  ✓" : ""));
                o.setTextSize(14);
                o.setTextColor(filterMode[0] == optModes[oi] ? COLOR_BLUE : COLOR_WHITE);
                o.setPadding(Math.round(14 * d), Math.round(10 * d), Math.round(14 * d), Math.round(10 * d));
                final int mm = optModes[oi];
                o.setOnClickListener(vo -> {
                    // 再次点同一项则取消筛选回到全部
                    filterMode[0] = (filterMode[0] == mm) ? 0 : mm;
                    filterGlass.setGlassSelected(filterMode[0] != 0);
                    getSharedPreferences("devicereset_ui", MODE_PRIVATE)
                            .edit().putInt("picker_filter_mode", filterMode[0]).apply();
                    applyPickerFilter(list, emptyTv, search, filterMode);
                    pwHolder[0].dismiss();
                });
                pop.addView(o);
            }
            PopupWindow pw = new PopupWindow(pop,
                    Math.round(172 * d), ViewGroup.LayoutParams.WRAP_CONTENT, true);
            pw.setOutsideTouchable(true);
            pw.setElevation(8 * d);
            pwHolder[0] = pw;
            pw.showAsDropDown(btnFilter, 0, Math.round(6 * d));
        });

        // 后台加载全部已安装应用：包名集合比对缓存（秒开），不一致才重建；图标懒加载后台解码
        new Thread(() -> {
            try {
                final PackageManager pm = getPackageManager();
                // ① 快速扫描包名集合（毫秒级），用于缓存失效比对
                final java.util.List<ApplicationInfo> apps = pm.getInstalledApplications(0);
                final java.util.Set<String> currentPkgs = new java.util.HashSet<>();
                for (ApplicationInfo ai : apps) currentPkgs.add(ai.packageName);

                // ② 包名集合一致 → 直接复用条目缓存；不一致（新装/卸载）→ 重建
                final java.util.List<AppEntry> entries;
                if (pickerEntryCache != null && currentPkgs.equals(pickerPkgCache)) {
                    entries = pickerEntryCache;
                } else {
                    final java.util.List<AppEntry> rebuilt = new java.util.ArrayList<>();
                    for (ApplicationInfo ai : apps) {
                        if (ai.packageName.equals(getPackageName())
                                || ai.packageName.startsWith("io.github.gjr787878")) continue;
                        CharSequence l = pm.getApplicationLabel(ai);
                        boolean isSystem = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                        rebuilt.add(new AppEntry(l != null && l.length() > 0 ? l.toString() : ai.packageName,
                                ai.packageName, null, isSystem));
                    }
                    java.util.Collections.sort(rebuilt, (a, b) -> a.label.compareToIgnoreCase(b.label));
                    pickerEntryCache = rebuilt;
                    pickerPkgCache = currentPkgs;
                    entries = rebuilt;
                }
                final java.util.Set<String> already = Config.getTargetPackages(this);
                runOnUiThread(() -> {
                    for (final AppEntry e : entries) {
                        LinearLayout row = new LinearLayout(this);
                        row.setOrientation(LinearLayout.HORIZONTAL);
                        row.setGravity(Gravity.CENTER_VERTICAL);
                        row.setPadding(Math.round(12 * d), Math.round(10 * d), Math.round(12 * d), Math.round(10 * d));
                        row.setBackground(new GlassButtonDrawable(20 * d, 1 * d, false));
                        row.setTag(new RowTag((e.label + " " + e.pkg).toLowerCase(), e.system));

                        final ImageView ic = new ImageView(this);
                        ic.setLayoutParams(new LinearLayout.LayoutParams(Math.round(36 * d), Math.round(36 * d)));
                        // ③ 图标：缓存命中直接显示；未命中先占位，后台线程池解码后填充（不阻塞首屏）
                        android.graphics.drawable.Drawable cachedIcon = pickerIconCache.get(e.pkg);
                        if (cachedIcon != null) {
                            ic.setImageDrawable(cachedIcon);
                        } else {
                            GradientDrawable ph = new GradientDrawable();
                            ph.setColor(0x33FFFFFF);
                            ph.setCornerRadius(8 * d);
                            ic.setImageDrawable(ph);
                            final String fpkg = e.pkg;
                            try {
                                pickerIconPool.execute(() -> {
                                    try {
                                        android.graphics.drawable.Drawable d2 = pm.getApplicationIcon(fpkg);
                                        if (d2 != null) {
                                            pickerIconCache.put(fpkg, d2);
                                            runOnUiThread(() -> ic.setImageDrawable(d2));
                                        }
                                    } catch (Throwable ignored) {}
                                });
                            } catch (Throwable ignored) {}
                        }
                        row.addView(ic);

                        LinearLayout tc = new LinearLayout(this);
                        tc.setOrientation(LinearLayout.VERTICAL);
                        LinearLayout.LayoutParams tcLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                        tcLp.leftMargin = Math.round(12 * d);
                        TextView n = new TextView(this);
                        n.setText(e.label + (already.contains(e.pkg) ? "  ✓" : ""));
                        n.setTextSize(15);
                        n.setTextColor(COLOR_WHITE);
                        TextView p = new TextView(this);
                        p.setText(e.pkg);
                        p.setTextSize(11);
                        p.setTextColor(COLOR_GRAY);
                        tc.addView(n);
                        tc.addView(p);
                        row.addView(tc, tcLp);

                        row.setOnClickListener(v -> addTargetFromPicker(e.pkg, dialog));
                        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                        rLp.bottomMargin = Math.round(8 * d);
                        list.addView(row, rLp);
                    }
                    // 搜索过滤（与系统/安装筛选共用统一过滤逻辑）
                    search.addTextChangedListener(new android.text.TextWatcher() {
                        public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                        public void onTextChanged(CharSequence s, int a, int b, int c) {
                            applyPickerFilter(list, emptyTv, search, filterMode);
                        }
                        public void afterTextChanged(android.text.Editable s) {}
                    });
                    // 列表加载完成后立即应用已保存的筛选（否则“记住了但没生效”）
                    applyPickerFilter(list, emptyTv, search, filterMode);
                });
            } catch (Throwable e) {
                runOnUiThread(() -> Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    /** 选择器筛选：搜索文本 + 系统/安装类型共同决定每行可见性 */
    private void applyPickerFilter(LinearLayout list, TextView emptyTv, EditText search, int[] filterMode) {
        String q = search.getText().toString().toLowerCase();
        int shown = 0;
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            RowTag rt = (RowTag) child.getTag();
            boolean textOk = q.isEmpty() || rt.text.contains(q);
            boolean typeOk = filterMode[0] == 0 || (filterMode[0] == 1 ? rt.system : !rt.system);
            boolean ok = textOk && typeOk;
            child.setVisibility(ok ? View.VISIBLE : View.GONE);
            if (ok) shown++;
        }
        emptyTv.setVisibility(shown == 0 ? View.VISIBLE : View.GONE);
    }

    /** 选择器点选：只加入目标配置，不生成身份（伪装值必须手动触发：详情中随机/自定义） */
    private void addTargetFromPicker(String pkg, Dialog dialog) {
        getSharedPreferences("devicereset_ui", MODE_PRIVATE).edit().putString("last_target_pkg", pkg).apply();
        Config.addTargetPackage(this, pkg);
        // 新版 LSPosed：应用内弹授权窗动态加入作用域（免开管理器）；老框架回退 root 写库
        if (LSPosedScopeHelper.isConnected()) {
            LSPosedScopeHelper.requestScope(pkg, scopeEventListener());
        } else {
            // 3.9.2: 哨兵驱动，无需 root 写库
        }
        Toast.makeText(this,
                t("已添加目标应用（可在详情中点击「随机」/「自定义」生成伪装值）", "Target app added (tap Random / Customize in its detail to generate a spoofed identity)", "Приложение добавлено (нажмите «Случайно»/«Настроить» в его деталях, чтобы создать подменённую идентичность)"),
                Toast.LENGTH_SHORT).show();
        refreshAppList();
        dialog.dismiss();
    }

    /** LSPosed 作用域请求回调：授权/失败时给出轻提示（不打扰流程） */
    private XposedService.OnScopeEventListener scopeEventListener() {
        return new XposedService.OnScopeEventListener() {
            @Override
            public void onScopeRequestApproved(java.util.List<String> packageNames) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        t("已加入作用域，重新打开目标应用即可生效", "Scope granted; reopen the target app to take effect", "Добавлено в область; перезапустите приложение для применения"),
                        Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onScopeRequestFailed(String message) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        t("作用域请求失败: ", "Scope request failed: ", "Ошибка запроса области: ") + message,
                        Toast.LENGTH_SHORT).show());
            }
        };
    }

    /** 选择器列表条目 */
    private static class AppEntry {
        final String label, pkg;
        final android.graphics.drawable.Drawable icon;
        final boolean system;
        AppEntry(String label, String pkg, android.graphics.drawable.Drawable icon, boolean system) {
            this.label = label;
            this.pkg = pkg;
            this.icon = icon;
            this.system = system;
        }
    }

    /** 选择器行标签：搜索文本 + 是否系统应用 */
    private static class RowTag {
        final String text;
        final boolean system;
        RowTag(String text, boolean system) {
            this.text = text;
            this.system = system;
        }
    }

    // ===== 自动清空目标应用数据（Root）=====

    /** 设置页开关：保存/随机后是否自动清空目标应用数据并重启（默认关：保存后只提示修改成功，不自动重启） */
    private boolean isAutoClearAfterSave() {
        return getSharedPreferences("devicereset_ui", MODE_PRIVATE)
                .getBoolean("auto_clear_after_save", false);
    }

    /** 删除某应用的内部/外部身份哨兵与运行时文件（长按删除目标时调用，需 Root） */
    private void deleteIdentityFiles(String pkg) {
        try {
            Process su = Runtime.getRuntime().exec("su");
            java.io.DataOutputStream os = new java.io.DataOutputStream(su.getOutputStream());
            os.writeBytes("rm -f '/data/data/" + pkg + "/files/.identity_sentinel' "
                    + "'/data/data/" + pkg + "/files/.identity_runtime' "
                    + "'/sdcard/Android/data/" + pkg + "/files/.identity_sentinel' "
                    + "'/sdcard/Android/data/" + pkg + "/files/.identity_runtime' 2>/dev/null\n");
            os.writeBytes("exit\n");
            os.flush();
            su.waitFor();
        } catch (Throwable ignored) {}
    }

    /** 写外部存储哨兵备份（/sdcard/Android/data/<pkg>/files/.identity_sentinel），模块可读 */
    private void writeExternalIdentityFile(String packageName, String json) {
        try {
            String dir = "/sdcard/Android/data/" + packageName + "/files";
            String path = dir + "/.identity_sentinel";
            Process su = Runtime.getRuntime().exec("su");
            java.io.DataOutputStream os = new java.io.DataOutputStream(su.getOutputStream());
            os.writeBytes("mkdir -p '" + dir + "'\n");
            os.writeBytes("echo '" + json.replace("'", "'\\''") + "' > '" + path + "'\n");
            os.writeBytes("chmod 666 '" + path + "'\n");
            os.writeBytes("exit\n");
            os.flush();
            su.waitFor();
        } catch (Throwable ignored) {}
    }

    /**
     * 清空目标应用全部数据：优先用官方 `pm clear <pkg>`（等价「设置→存储→清除数据」，
     * 参考 Android 官方 adb 文档；内含强停、自动处理多用户/SDK36 数据目录），
     * 失败才回退手动删除各数据目录。
     * 身份哨兵先备份、清空后再恢复（pm clear 会清掉 files/ 下所有文件），
     * 保证清数据后伪装身份依然生效；不自动打开目标应用。
     */
    private boolean clearTargetAppData(String pkg, String identityJson) {
        try {
            // 1. 先确保身份三处一致（配置+内部哨兵+外部哨兵）
            writeIdentityAll(pkg, identityJson);
            // 2. 备份哨兵内容（pm clear 会连 files/ 一起清空）
            String backup = readIdentityFile(pkg);
            // 3. 官方接口清除全部数据；失败回退手动删除
            boolean cleared = false;
            try {
                Process su = Runtime.getRuntime().exec("su");
                java.io.DataOutputStream os = new java.io.DataOutputStream(su.getOutputStream());
                os.writeBytes("pm clear " + pkg + "\n");
                os.writeBytes("exit\n");
                os.flush();
                int rc = su.waitFor();
                java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(su.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String l;
                while ((l = br.readLine()) != null) sb.append(l);
                cleared = (rc == 0) && !sb.toString().contains("Error");
            } catch (Throwable ignored) {}
            if (!cleared) {
                // 兜底：手动清缓存/数据库/偏好/网页缓存（保留 files/ 下的身份文件）
                String dataDir = "/data/user/0/" + pkg;
                Process su2 = Runtime.getRuntime().exec("su");
                java.io.DataOutputStream os2 = new java.io.DataOutputStream(su2.getOutputStream());
                os2.writeBytes("am force-stop " + pkg + " 2>/dev/null\n");
                os2.writeBytes("rm -rf " + dataDir + "/cache " + dataDir + "/code_cache "
                        + dataDir + "/databases " + dataDir + "/shared_prefs "
                        + dataDir + "/no_backup " + dataDir + "/app_webview 2>/dev/null\n");
                os2.writeBytes("for f in " + dataDir + "/files/*; do b=$(basename \"$f\"); "
                        + "[ \"$b\" = \".identity_sentinel\" ] || [ \"$b\" = \".identity_runtime\" ] || rm -rf \"$f\"; done 2>/dev/null\n");
                os2.writeBytes("exit\n");
                os2.flush();
                su2.waitFor();
            }
            // 4. 恢复身份哨兵（内部+外部），清数据后模块依然按哨兵身份生效
            if (backup != null && backup.startsWith("{")) {
                writeIdentityFile(pkg, backup);
                writeExternalIdentityFile(pkg, backup);
            }
            // 5. 不自动打开目标应用：保持停止状态（pm clear 已强停），用户下次手动打开时生效
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 用 monkey 拉起目标应用默认入口（当前仅预留，清数据后不再自动拉起） */
    private void relaunchApp(String pkg) {
        try {
            Process su = Runtime.getRuntime().exec("su");
            java.io.DataOutputStream os = new java.io.DataOutputStream(su.getOutputStream());
            os.writeBytes("monkey -p " + pkg + " 1 2>/dev/null\n");
            os.writeBytes("exit\n");
            os.flush();
            su.waitFor();
        } catch (Throwable ignored) {}
    }

    /** 保存/随机成功后：询问是否清空目标应用数据（三语；部分应用需清空数据才能立即生效） */
    private void showClearDataPrompt(final String pkg, final String json) {
        showGlassConfirm(
                t("是否清空数据？", "Clear data now?", "Очистить данные сейчас?"),
                t("修改成功。部分应用需要清空数据并重新打开才能生效：\n将清除目标应用的缓存、数据库、偏好设置（保留伪装身份），并停止该应用。\n选择「清空所有数据」后请手动重新打开目标应用，伪装值立即生效；\n选择「取消」则保持现状，伪装值在下次重新打开目标应用时生效。",
                  "Saved. Some apps need their data cleared and to be reopened before the new identity applies:\nThis clears the target app's cache, databases and prefs (spoofed identity is kept) and stops the app.\nAfter choosing Clear All Data, reopen the target app manually and the identity applies immediately;\nchoose Cancel to keep as-is — the identity applies the next time you open the target app.",
                  "Сохранено. Некоторым приложениям требуется очистка данных и повторное открытие, чтобы применилась новая идентичность:\nБудут удалены кэш, базы данных и настройки приложения (подменённая идентичность сохраняется), приложение будет остановлено.\nПосле выбора «Очистить все данные» откройте приложение вручную — идентичность применится сразу;\nпри выборе «Отмена» идентичность применится при следующем открытии приложения."),
                t("清空所有数据", "Clear All Data", "Очистить все данные"),
                true,
                () -> {
                    new Thread(() -> {
                        final boolean ok = clearTargetAppData(pkg, json);
                        runOnUiThread(() -> Toast.makeText(this, ok
                                ? t("已清空数据: ", "Data cleared: ", "Данные очищены: ") + pkg
                                : t("清空失败，请确认已授予ROOT权限（身份已保存，下次打开应用生效）",
                                        "Clear failed, ensure ROOT access (identity saved; it applies on next open)",
                                        "Ошибка очистки, проверьте Root-права (идентичность сохранена, применится при следующем открытии)"),
                                Toast.LENGTH_LONG).show());
                    }).start();
                });
    }

    /** 手动清空某应用数据并重启（自定义玻璃弹窗） */
    private void showClearDataDialog() {
        final float d = getResources().getDisplayMetrics().density;
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Math.round(20 * d), Math.round(20 * d), Math.round(20 * d), Math.round(18 * d));
        GradientDrawable rootBg = new GradientDrawable();
        rootBg.setColor(COLOR_DIALOG_BG);
        rootBg.setCornerRadius(24 * d);
        rootBg.setStroke(Math.round(1 * d), 0x55FFFFFF);
        root.setBackground(rootBg);

        TextView tvTitle = new TextView(this);
        tvTitle.setText(t("清空目标应用数据并重启", "Clear Target App Data & Restart", "Очистить данные приложения и перезапустить"));
        tvTitle.setTextSize(17);
        tvTitle.setTextColor(COLOR_WHITE);
        tvTitle.setTypeface(tvTitle.getTypeface(), android.graphics.Typeface.BOLD);
        root.addView(tvTitle);

        TextView tvMsg = new TextView(this);
        tvMsg.setText(t("将清空目标应用的缓存、数据库、偏好设置（保留身份哨兵），并强制停止后重启，使当前身份立即生效。",
                "Clears the target app's cache, databases and prefs (identity sentinel is kept), force-stops and restarts it so the current identity takes effect immediately.",
                "Очищает кэш, базы данных и настройки целевого приложения (сторожевой файл сохраняется), принудительно останавливает и перезапускает его."));
        tvMsg.setTextSize(14);
        tvMsg.setTextColor(COLOR_GRAY);
        tvMsg.setLineSpacing(4, 1);
        LinearLayout.LayoutParams msgLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        msgLp.topMargin = Math.round(12 * d);
        root.addView(tvMsg, msgLp);

        // 包名输入框（胶囊样式）
        final EditText input = new EditText(this);
        input.setHint(t("输入应用包名，如 com.example.app", "Enter package name, e.g. com.example.app", "Введите имя пакета, например com.example.app"));
        input.setTextSize(14);
        input.setTextColor(COLOR_WHITE);
        input.setSingleLine(true);
        GradientDrawable iBg = new GradientDrawable();
        iBg.setColor(0x221C1C1E);
        iBg.setCornerRadius(20 * d);
        iBg.setStroke(Math.round(1 * d), 0x55FFFFFF);
        input.setBackground(iBg);
        input.setPadding(Math.round(14 * d), Math.round(10 * d), Math.round(14 * d), Math.round(10 * d));
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inputLp.topMargin = Math.round(14 * d);
        root.addView(input, inputLp);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams btnRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnRowLp.topMargin = Math.round(18 * d);

        Button btnCancel = makeGlassBtn(t("取消", "Cancel", "Отмена"), 14);
        btnRow.addView(btnCancel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        btnCancel.setOnClickListener(v -> dialog.dismiss());

        Button btnClear = new Button(this);
        btnClear.setText(t("清空", "Clear", "Очистить"));
        btnClear.setTextSize(14);
        btnClear.setTextColor(COLOR_BLUE);
        btnClear.setAllCaps(false);
        btnClear.setBackground(new GlassButtonDrawable(24 * d, 2 * d, true));
        LinearLayout.LayoutParams clearLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clearLp.leftMargin = Math.round(10 * d);
        btnRow.addView(btnClear, clearLp);

        btnClear.setOnClickListener(v -> {
            final String pkg = input.getText().toString().trim();
            if (pkg.isEmpty()) {
                Toast.makeText(this, t("请输入包名", "Please enter package name", "Введите имя пакета"), Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            new Thread(() -> {
                String json = readIdentityAll(pkg);
                if (json == null || !json.startsWith("{")) {
                    Identity nid = IdentityGenerator.generateRandom();
                    IdentityGenerator.fillMissing(nid);
                    json = nid.toJson();
                }
                final String fjson = json;
                final boolean ok = clearTargetAppData(pkg, fjson);
                runOnUiThread(() -> Toast.makeText(this, ok
                        ? t("已清空并重启: ", "Cleared & restarted: ", "Очищено и перезапущено: ") + pkg
                        : t("清空失败，请确认已授予ROOT权限", "Clear failed, ensure ROOT access", "Ошибка, проверьте Root-права"),
                        Toast.LENGTH_LONG).show());
            }).start();
        });

        root.addView(btnRow, btnRowLp);
        dialog.setContentView(root);
        Window win = dialog.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x88000000));
            win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    // ===== 设置页 =====
    private View buildSettingsPage() {
        float d = getResources().getDisplayMetrics().density;
        ScrollView sv = new ScrollView(this);
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(Math.round(24 * d), Math.round(48 * d), Math.round(24 * d), Math.round(32 * d));

        TextView title = new TextView(this);
        title.setText(t("设置", "Settings", "Настройки"));
        title.setTextSize(28);
        title.setTextColor(COLOR_WHITE);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        ll.addView(title);

        // 副标题
        TextView subtitle = new TextView(this);
        subtitle.setText(t("手动生成并保存伪装身份，重新打开目标应用后生效",
                "Manually generate & save a spoofed identity; it takes effect after reopening the target app",
                "Вручную создайте и сохраните подменённую идентичность; она вступит в силу после повторного открытия приложения"));
        subtitle.setTextSize(14);
        subtitle.setTextColor(COLOR_GRAY);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = Math.round(8 * d);
        ll.addView(subtitle, subLp);

        // LSPosed 框架状态（作用域同步是否就绪）
        addSettingsSection(ll, t("LSPosed 框架", "LSPosed Framework", "Фреймворк LSPosed"), d);
        lspStatusTv = new TextView(this);
        lspStatusTv.setText(t("连接中...", "Connecting...", "Подключение..."));
        lspStatusTv.setTextSize(14);
        lspStatusTv.setTextColor(COLOR_GRAY);
        if (scopeLoaded || scopeDbLoaded) {
            if (scopeConnected) {
                lspStatusTv.setText(t("LSPosed 框架：已连接，作用域 ",
                        "LSPosed: connected, scope ",
                        "LSPosed: подключён, область ") + scopeCache.size() + t(" 个应用",
                        " app(s)",
                        " приложений"));
                lspStatusTv.setTextColor(COLOR_BLUE);
            } else if (scopeDbLoaded && scopeDbEnabled && scopeDbSystem) {
                lspStatusTv.setText(t("LSPosed 框架：已注入（全局模式✓，伪装已生效）",
                        "LSPosed: injected (global scope✓, spoof active)",
                        "LSPosed: внедрён (глобальная область✓, спуфинг активен)"));
                lspStatusTv.setTextColor(COLOR_GREEN);
            } else if (scopeDbLoaded && scopeDbEnabled) {
                lspStatusTv.setText(t("LSPosed 框架：模块已启用（请勾选系统框架）",
                        "LSPosed: module enabled (select system framework)",
                        "LSPosed: модуль включён (выберите системный фреймворк)"));
                lspStatusTv.setTextColor(COLOR_GRAY);
            } else {
                lspStatusTv.setText(t("LSPosed 框架：未连接（模块未启用或框架过旧）",
                        "LSPosed: not connected (module disabled or old framework)",
                        "LSPosed: не подключён (модуль выключен или старая версия фреймворка)"));
                lspStatusTv.setTextColor(COLOR_GRAY);
            }
        }
        LinearLayout.LayoutParams lspLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lspLp.topMargin = Math.round(6 * d);
        lspLp.bottomMargin = Math.round(10 * d);
        ll.addView(lspStatusTv, lspLp);

        // 作用域模式说明：一次配置后应用内全托管
        TextView scopeTip = new TextView(this);
        scopeTip.setText(t("建议：在 LSPosed 管理器为本模块勾选「系统框架」一次，之后无需再打开 LSPosed——在本应用选择/删除目标即可生效（全局注入 + 应用内过滤）。",
                "Tip: tick \"System Framework\" for this module once in LSPosed Manager; afterwards you never need to open LSPosed again - add/remove targets right here (global injection + in-app filtering).",
                "Совет: отметьте «Системный фреймворк» для модуля в LSPosed один раз — больше не нужно открывать LSPosed: выбирайте/удаляйте цели прямо здесь (глобальное внедрение + фильтрация в приложении)."));
        scopeTip.setTextSize(12);
        scopeTip.setTextColor(COLOR_GRAY);
        LinearLayout.LayoutParams tipLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tipLp.topMargin = Math.round(2 * d);
        tipLp.bottomMargin = Math.round(12 * d);
        ll.addView(scopeTip, tipLp);

        // 语言
        addSettingsSection(ll, t("语言", "Language", "Язык"), d);
        String langName = LANG_EN.equals(currentLang) ? "English" : LANG_ZH.equals(currentLang) ? "中文" : "Русский";
        Button btnLang = makeGlassBtn(t("语言: ", "Language: ", "Язык: ") + langName, 15);
        ll.addView(btnLang, makeFormLp(d));
        btnLang.setOnClickListener(v -> showLanguageDialog());

        // 检查更新
        Button btnUpdate = makeGlassBtn(t("检查更新", "Check Update", "Проверить обновления"), 15);
        ll.addView(btnUpdate, makeFormLp(d));
        btnUpdate.setOnClickListener(v -> checkUpdate(true));

        // 伪装选项
        addSettingsSection(ll, t("伪装选项", "Spoofing Options", "Параметры подмены"), d);
        String[] hookLabels = {
                t("伪装 Android ID", "Spoof Android ID", "Подмена Android ID"),
                t("伪装 广告ID", "Spoof Ad ID (AAID)", "Подмена рекламного ID"),
                t("伪装 IMEI/MEID", "Spoof IMEI/MEID", "Подмена IMEI/MEID"),
                t("伪装 设备型号", "Spoof Device Model", "Подмена модели устройства"),
                t("伪装 MAC地址", "Spoof MAC Address", "Подмена MAC-адреса"),
                t("伪装 GSF ID", "Spoof GSF ID", "Подмена GSF ID"),
                t("伪装 运营商信息", "Spoof Carrier Info", "Подмена информации оператора")
        };
        for (int i = 0; i < 7; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Math.round(16 * d), Math.round(12 * d), Math.round(16 * d), Math.round(12 * d));
            hookGlass[i] = new GlassButtonDrawable(24 * d, 1 * d, false);
            row.setBackground(hookGlass[i]);
            hookTv[i] = new TextView(this);
            hookTv[i].setText(hookLabels[i]);
            hookTv[i].setTextSize(15);
            hookTv[i].setTextColor(COLOR_WHITE);
            row.addView(hookTv[i], new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            final int idx = i;
            row.setOnClickListener(v -> toggleHook(idx));
            ll.addView(row, makeFormLp(d));
        }
        loadHookStates();

        // 生效方式

        // 维护
        addSettingsSection(ll, t("维护", "Maintenance", "Обслуживание"), d);


        Button btnAbout = makeGlassBtn(t("关于", "About", "О программе"), 15);
        ll.addView(btnAbout, makeFormLp(d));
        btnAbout.setOnClickListener(v -> showAboutDialog());

        Button btnExportLog = makeGlassBtn(t("导出诊断日志", "Export Diagnostic Log", "Экспорт диагностического журнала"), 15);
        ll.addView(btnExportLog, makeFormLp(d));
        btnExportLog.setOnClickListener(v -> exportDiagnosticLog());

        // 说明文字（原首页内容）
        addSettingsSection(ll, t("使用方法", "Usage", "Использование"), d);
        addInfoBlock(ll, t("使用方法", "How to use", "Как использовать"),
                t("1. 首次安装：LSPosed → 模块 → 启用本模块（「系统框架」已自动预勾选）→ 重启手机一次\n" +
                  "2. 之后无需再打开 LSPosed 管理器\n" +
                  "3. 在「应用」页点击「＋ 选择应用」添加任意已安装应用为目标\n" +
                  "4. 打开目标应用详情 → 点击「随机」或「自定义」→ 保存（手动触发写入）\n" +
                  "5. 显示「修改成功」后，直接打开目标应用即按伪装身份生效，无需重启\n" +
                  "6. 若目标应用仍读到旧值，保存后选择「清空所有数据」再打开该应用",
                  "1. First install: LSPosed -> Modules -> Enable this module (\"System Framework\" is pre-checked) -> reboot once\n" +
                  "2. Afterwards, no need to open LSPosed Manager again\n" +
                  "3. Tap \"+ Select App\" on the Apps tab to add any installed app as target\n" +
                  "4. Open the target app's detail -> tap Random / Customize -> Save (manual trigger)\n" +
                  "5. You'll see \"Saved\"; just reopen the target app and the spoofed identity applies, no reboot needed\n" +
                  "6. If the target app still reads old values, choose \"Clear all data\" after saving and reopen the app",
                  "1. Первая установка: LSPosed -> Модули -> Включить модуль («Системный фреймворк» уже отмечен) -> перезагрузить один раз\n" +
                  "2. Далее открывать LSPosed Manager больше не нужно\n" +
                  "3. На вкладке «Приложения» нажмите «+ Выбрать приложение», чтобы добавить любое установленное приложение\n" +
                  "4. Откройте детали приложения -> «Случайно»/«Настроить» -> Сохранить (ручная запись)\n" +
                  "5. Появится «Сохранено»; просто откройте приложение снова — подменённая идентичность применится, перезагрузка не нужна\n" +
                  "6. Если приложение всё ещё читает старые значения, выберите «Очистить все данные» после сохранения и откройте его заново"), d);

        addInfoBlock(ll, t("工作原理", "How It Works", "Как это работает"),
                t("• 伪装身份保存在模块配置中，并在「保存/随机」时写入目标应用自己的私有目录（哨兵文件，应用可读）\n" +
                  "• 模块经「系统框架」全局注入所有进程；目标应用启动时读到自己的哨兵身份即安装 Hook，未写入的应用零开销跳过\n" +
                  "• 因此只有手动写入过伪装值的应用才生效（手动触发），无需在 LSPosed 中勾选目标应用\n" +
                  "• 清除应用数据会删除哨兵 → 该应用恢复真实值，需重新写入",
                  "• The spoofed identity is stored in the module config and written to the target app's own private dir (sentinel file, readable by the app) on Save/Random\n" +
                  "• The module injects every process via the System Framework; when a target app starts, it reads its own sentinel identity and installs hooks; apps without a sentinel are skipped with zero overhead\n" +
                  "• So only apps you manually wrote a spoofed identity for are affected (manual trigger) — no need to check target apps in LSPosed\n" +
                  "• Clearing app data deletes the sentinel -> the app returns to real values and needs a new write",
                  "• Подменённая идентичность хранится в конфигурации модуля и при «Сохранить/Случайно» записывается в собственный приватный каталог приложения (файл-sentinel, читаемый приложением)\n" +
                  "• Модуль внедряется во все процессы через «Системный фреймворк»; при запуске приложение читает свою идентичность-sentinel и устанавливает хуки; приложения без sentinel пропускаются без затрат\n" +
                  "• Поэтому действуют только приложения, для которых вы вручную записали подменённую идентичность (ручной запуск) — отмечать их в LSPosed не нужно\n" +
                  "• Очистка данных приложения удаляет sentinel -> приложение возвращается к реальным значениям, нужна новая запись"), d);

        addInfoBlock(ll, t("伪装的识别码", "Spoofed Identifiers", "Подменяемые идентификаторы"),
                t("• Android ID (SSAID)\n• 广告ID (AAID) / AppSet ID\n• IMEI / MEID / IMSI / ICCID\n• 序列号 / MAC地址\n• GSF ID\n• 设备型号：品牌、型号、厂商、Build指纹\n• 运营商信息：代码、名称、国家",
                "• Android ID (SSAID)\n• Advertising ID (AAID) / AppSet ID\n• IMEI / MEID / IMSI / ICCID\n• Serial number / MAC address\n• GSF ID\n• Device: Brand, Model, Manufacturer, Build fingerprint\n• Carrier: Code, Name, Country",
                "• Android ID (SSAID)\n• Рекламный ID (AAID) / AppSet ID\n• IMEI / MEID / IMSI / ICCID\n• Серийный номер / MAC-адрес\n• GSF ID\n• Устройство: бренд, модель, производитель, отпечаток Build\n• Оператор: код, название, страна"), d);

        addInfoBlock(ll, t("注意事项", "Warnings", "Предупреждения"),
                t("• 本模块仅用于个人隐私保护和技术测试\n• 部分应用会检测Xposed/Root痕迹，存在账号封禁风险\n• 保存后若应用仍读到旧值，请「清空所有数据」后重新打开（部分应用缓存了旧身份）\n• native层直接读取系统属性的应用，Java层Hook无法拦截\n• 建议先在不重要的应用上测试\n• 排查问题：设置页 →「导出诊断日志」→ 将压缩包发送给开发者",
                "• For personal privacy protection and technical testing only\n• Some apps detect Xposed/Root traces, account ban risk exists\n• If the app still reads old values after saving, choose \"Clear all data\" and reopen it (some apps cache the old identity)\n• Native-layer system property reads cannot be intercepted by Java hooks\n• Test on non-critical apps first\n• Troubleshooting: Settings -> \"Export Diagnostic Log\" -> send the archive to the developer",
                "• Только для защиты личной конфиденциальности и технического тестирования\n• Некоторые приложения обнаруживают следы Xposed/Root, существует риск блокировки\n• Если после сохранения приложение читает старые значения, выберите «Очистить все данные» и откройте заново (некоторые приложения кэшируют старую идентичность)\n• Нативные чтения системных свойств не могут быть перехвачены Java-хуками\n• Сначала тестируйте на некритичных приложениях\n• Устранение неполадок: Настройки -> «Экспорт диагностического журнала» -> отправьте архив разработчику"), d);

        sv.addView(ll);
        return sv;
    }

    private void addSettingsSection(LinearLayout ll, String text, float d) {
        TextView lbl = new TextView(this);
        lbl.setText(text);
        lbl.setTextSize(16);
        lbl.setTextColor(COLOR_WHITE);
        lbl.setTypeface(lbl.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(20 * d);
        lp.bottomMargin = Math.round(10 * d);
        ll.addView(lbl, lp);
    }

    private void addInfoBlock(LinearLayout ll, String title, String content, float d) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(14);
        t.setTextColor(COLOR_BLUE);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        ll.addView(t);
        TextView c = new TextView(this);
        c.setText(content);
        c.setTextSize(12);
        c.setTextColor(COLOR_GRAY);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Math.round(12 * d);
        ll.addView(c, lp);
    }

    private void toggleHook(int idx) {
        boolean current = readHookState(idx);
        boolean next = !current;
        writeHookState(idx, next);
        hookTv[idx].setTextColor(next ? COLOR_BLUE : COLOR_WHITE);
        hookGlass[idx].setGlassSelected(next);
    }

    private boolean readHookState(int idx) {
        try {
            switch (idx) {
                case 0: return Config.isHookAndroidId(this);
                case 1: return Config.isHookAdId(this);
                case 2: return Config.isHookImei(this);
                case 3: return Config.isHookBuild(this);
                case 4: return Config.isHookMac(this);
                case 5: return Config.isHookGsf(this);
                case 6: return Config.isHookCarrier(this);
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private void writeHookState(int idx, boolean val) {
        try {
            switch (idx) {
                case 0: Config.setHookAndroidId(this, val); break;
                case 1: Config.setHookAdId(this, val); break;
                case 2: Config.setHookImei(this, val); break;
                case 3: Config.setHookBuild(this, val); break;
                case 4: Config.setHookMac(this, val); break;
                case 5: Config.setHookGsf(this, val); break;
                case 6: Config.setHookCarrier(this, val); break;
            }
        } catch (Throwable ignored) {}
    }

    private void loadHookStates() {
        for (int i = 0; i < 7; i++) {
            boolean state = readHookState(i);
            hookTv[i].setTextColor(state ? COLOR_BLUE : COLOR_WHITE);
            hookGlass[i].setGlassSelected(state);
        }
    }

    // ===== Root 工具 =====
    /** 主页列表包名：目标应用 ∪ 已手动设置伪装值的应用（读取模块配置，无需 Root，不触发超级用户提示） */
    private java.util.List<String> scanPackagesWithIdentity() {
        java.util.Set<String> result = new java.util.LinkedHashSet<>();
        result.addAll(Config.getTargetPackages(this));
        result.addAll(Config.getIdentityPackages(this));
        return new java.util.ArrayList<>(result);
    }

    /**
     * 统一身份写入：模块配置（UI 显示）+ 内部哨兵 + 外部哨兵（目标进程读取），
     * 保证「显示值 == 实际生效值」。所有写入路径必须走这里。
     */
    private void writeIdentityAll(String pkg, String json) {
        Config.setIdentity(this, pkg, json);
        writeIdentityFile(pkg, json);
        writeExternalIdentityFile(pkg, json);
    }

    /**
     * 统一身份读取：模块配置 → 内部哨兵 → 外部哨兵（与 MainHook 生效顺序完全一致）。
     * 单点缺失自动回写对齐：配置有而哨兵缺失 → 补写哨兵；哨兵有而配置缺失 → 回写配置。
     */
    private String readIdentityAll(String pkg) {
        String cfg = Config.getIdentity(this, pkg);
        if (cfg != null && cfg.startsWith("{")) {
            // 配置为准；哨兵缺失则静默补写，保证目标进程也能读到同一身份
            if (readIdentityFile(pkg) == null) {
                writeIdentityFile(pkg, cfg);
                writeExternalIdentityFile(pkg, cfg);
            }
            return cfg;
        }
        String sentinel = readIdentityFile(pkg);
        if (sentinel != null && sentinel.startsWith("{")) {
            // 哨兵有而配置缺失 → 回写配置对齐（显示与生效一致）
            Config.setIdentity(this, pkg, sentinel);
            return sentinel;
        }
        return null;
    }

    private String readIdentityFile(String packageName) {
        // 先尝试内部路径，再尝试外部路径
        String[] paths = {
            "/data/data/" + packageName + "/files/.identity_sentinel",
            "/sdcard/Android/data/" + packageName + "/files/.identity_sentinel"
        };
        for (String path : paths) {
            try {
                Process su = Runtime.getRuntime().exec("su");
                java.io.DataOutputStream os = new java.io.DataOutputStream(su.getOutputStream());
                os.writeBytes("cat '" + path + "' 2>/dev/null\n");
                os.writeBytes("exit\n");
                os.flush();
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(su.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();
                int rc = su.waitFor();
                String output = sb.toString().trim();
                if (rc == 0 && !output.isEmpty() && output.startsWith("{")) return output;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private void writeIdentityFile(String packageName, String json) {
        try {
            String path = "/data/data/" + packageName + "/files/.identity_sentinel";
            Process su = Runtime.getRuntime().exec("su");
            java.io.DataOutputStream os = new java.io.DataOutputStream(su.getOutputStream());
            os.writeBytes("mkdir -p '/data/data/" + packageName + "/files'\n");
            os.writeBytes("echo '" + json.replace("'", "'\\''") + "' > '" + path + "'\n");
            // 必须 666：文件由 root 写入（owner=root），600 时目标应用自身 uid 无法读取
            os.writeBytes("chmod 666 '" + path + "'\n");
            os.writeBytes("exit\n");
            os.flush();
            su.waitFor();
        } catch (Throwable ignored) {}
    }

    // ===== 导出诊断日志 =====
    private void exportDiagnosticLog() {
        Toast.makeText(this, t("正在收集诊断信息...", "Collecting diagnostic info...", "Сбор диагностической информации..."), Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                java.io.File tmpDir = new java.io.File(getCacheDir(), "drs_log_" + System.currentTimeMillis());
                tmpDir.mkdirs();

                // 1. 设备信息
                StringBuilder devInfo = new StringBuilder();
                devInfo.append("=== Device Info ===\n");
                devInfo.append("Brand: ").append(android.os.Build.BRAND).append("\n");
                devInfo.append("Model: ").append(android.os.Build.MODEL).append("\n");
                devInfo.append("Manufacturer: ").append(android.os.Build.MANUFACTURER).append("\n");
                devInfo.append("Device: ").append(android.os.Build.DEVICE).append("\n");
                devInfo.append("Product: ").append(android.os.Build.PRODUCT).append("\n");
                devInfo.append("Hardware: ").append(android.os.Build.HARDWARE).append("\n");
                devInfo.append("Fingerprint: ").append(android.os.Build.FINGERPRINT).append("\n");
                devInfo.append("Build ID: ").append(android.os.Build.ID).append("\n");
                devInfo.append("Android Version: ").append(android.os.Build.VERSION.RELEASE).append("\n");
                devInfo.append("SDK Level: ").append(android.os.Build.VERSION.SDK_INT).append("\n");
                devInfo.append("Module Version: 3.9.6 (versionCode 68)\n");
                devInfo.append("Language: ").append(currentLang).append("\n");
                // Root 状态
                devInfo.append("\n=== Root Status ===\n");
                try {
                    Process su = Runtime.getRuntime().exec("su");
                    java.io.DataOutputStream os = new java.io.DataOutputStream(su.getOutputStream());
                    os.writeBytes("id\n");
                    os.writeBytes("exit\n");
                    os.flush();
                    java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(su.getInputStream()));
                    String l;
                    while ((l = r.readLine()) != null) devInfo.append("su output: ").append(l).append("\n");
                    r.close();
                    su.waitFor();
                    devInfo.append("Root: GRANTED\n");
                } catch (Throwable e) {
                    devInfo.append("Root: FAILED - ").append(e.getMessage()).append("\n");
                }
                // libxposed getScope 实际输出（确认系统框架在 getScope 中的表示）
                devInfo.append("--- libxposed getScope ---\n");
                devInfo.append("connected=").append(LSPosedScopeHelper.isConnected()).append("\n");
                try {
                    java.util.List<String> sc = LSPosedScopeHelper.getScope();
                    devInfo.append("scope=").append(sc == null ? "null" : sc.toString()).append("\n");
                } catch (Throwable te) {
                    devInfo.append("getScope error: ").append(te.getMessage()).append("\n");
                }
                writeFile(new java.io.File(tmpDir, "device_info.txt"), devInfo.toString());

                // 2. 模块配置
                StringBuilder cfg = new StringBuilder();
                cfg.append("=== Module Config ===\n");
                cfg.append("hook_android_id: ").append(Config.isHookAndroidId(this)).append("\n");
                cfg.append("hook_ad_id: ").append(Config.isHookAdId(this)).append("\n");
                cfg.append("hook_imei: ").append(Config.isHookImei(this)).append("\n");
                cfg.append("hook_build_info: ").append(Config.isHookBuild(this)).append("\n");
                cfg.append("hook_mac: ").append(Config.isHookMac(this)).append("\n");
                cfg.append("hook_gsf_id: ").append(Config.isHookGsf(this)).append("\n");
                cfg.append("hook_carrier: ").append(Config.isHookCarrier(this)).append("\n");
                cfg.append("auto_reset: ").append(Config.isAutoReset(this)).append("\n");
                cfg.append("\n=== Target Packages (from prefs) ===\n");
                for (String p : Config.getTargetPackages(this)) cfg.append(p).append("\n");
                writeFile(new java.io.File(tmpDir, "module_config.txt"), cfg.toString());

                // 3. 扫描所有应用的哨兵文件状态
                StringBuilder scan = new StringBuilder();
                scan.append("=== Sentinel File Scan ===\n");
                scan.append("Scanning /data/data/*/files/.identity_sentinel and /sdcard/Android/data/*/files/.identity_sentinel\n\n");
                try {
                    Process su = Runtime.getRuntime().exec("su");
                    java.io.DataOutputStream os = new java.io.DataOutputStream(su.getOutputStream());
                    os.writeBytes("echo '--- INTERNAL (/data/data) ---'\n");
                    os.writeBytes("for d in /data/data/*/; do pkg=$(basename \"$d\"); f=\"$d/files/.identity_sentinel\"; if [ -f \"$f\" ]; then echo \"FOUND: $pkg ($(wc -c < \"$f\") bytes)\"; fi; done\n");
                    os.writeBytes("echo '--- EXTERNAL (/sdcard/Android/data) ---'\n");
                    os.writeBytes("for d in /sdcard/Android/data/*/; do pkg=$(basename \"$d\"); f=\"$d/files/.identity_sentinel\"; if [ -f \"$f\" ]; then echo \"FOUND: $pkg ($(wc -c < \"$f\") bytes)\"; fi; done\n");
                    os.writeBytes("echo '--- RUNTIME FILES ---'\n");
                    os.writeBytes("for d in /sdcard/Android/data/*/; do pkg=$(basename \"$d\"); f=\"$d/files/.identity_runtime\"; if [ -f \"$f\" ]; then echo \"RUNTIME: $pkg\"; fi; done\n");
                    os.writeBytes("echo '--- HOOK LOGS (真授权证据) ---'\n");
                    os.writeBytes("for d in /sdcard/Android/data/*/; do pkg=$(basename \"$d\"); f=\"$d/files/.drs_hook.log\"; if [ -f \"$f\" ]; then echo \"HOOKED: $pkg\"; cat \"$f\"; fi; done\n");
                    os.writeBytes("echo '--- PROBE LOGS (全局注入覆盖证据) ---'\n");
                    os.writeBytes("for d in /sdcard/Android/data/*/; do pkg=$(basename \"$d\"); f=\"$d/files/.drs_probe.log\"; if [ -f \"$f\" ]; then echo \"PROBED: $pkg\"; tail -1 \"$f\"; fi; done\n");
                    os.writeBytes("echo '--- targets.txt 权限链 ---'\n");
                    os.writeBytes("ls -ld /data/data/io.github.gjr787878.devicereset 2>&1\n");
                    os.writeBytes("ls -la /data/data/io.github.gjr787878.devicereset/files/ 2>&1\n");
                    os.writeBytes("echo '--- targets.txt 内容 ---'\n");
                    os.writeBytes("cat /data/data/io.github.gjr787878.devicereset/files/targets.txt 2>&1\n");
                    os.writeBytes("exit\n");
                    os.flush();
                    java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(su.getInputStream()));
                    String l;
                    while ((l = r.readLine()) != null) scan.append(l).append("\n");
                    r.close();
                    su.waitFor();
                } catch (Throwable e) {
                    scan.append("Scan error: ").append(e.getMessage()).append("\n");
                }
                writeFile(new java.io.File(tmpDir, "scan_result.txt"), scan.toString());

                // 4. 导出所有有哨兵文件的应用的身份
                java.io.File idDir = new java.io.File(tmpDir, "identities");
                idDir.mkdirs();
                java.util.List<String> pkgs = scanPackagesWithIdentity();
                for (String pkg : pkgs) {
                    String json = readIdentityFile(pkg);
                    if (json != null) {
                        writeFile(new java.io.File(idDir, pkg + ".json"), json);
                    }
                }

                // 5. LSPosed 模块状态（尝试读取）
                StringBuilder lsp = new StringBuilder();
                lsp.append("=== LSPosed Status ===\n");
                lsp.append("Module package: io.github.gjr787878.devicereset\n");
                lsp.append("Note: If no apps found with sentinel files, the module may not be enabled in LSPosed Manager.\n");
                lsp.append("Check: LSPosed -> Modules -> DeviceResetSpooferX enabled (System Framework pre-checked) -> reboot once; then Save/Random in module UI for the target app.\n");
                lsp.append("Expected in scan_result: HOOKED lines with 'ALL hooks installed, identity source=sentinel'.\n");
                try {
                    Process su = Runtime.getRuntime().exec("su");
                    java.io.DataOutputStream os = new java.io.DataOutputStream(su.getOutputStream());
                    os.writeBytes("ls -la /data/adb/lspd/ 2>/dev/null\n");
                    os.writeBytes("cat /data/adb/lspd/config/modules_config.json 2>/dev/null | head -100\n");
                    os.writeBytes("echo '--- modules_config.db ---'\n");
                    os.writeBytes("ls -la /data/adb/lspd/config/ 2>/dev/null || echo NO_CONFIG_DIR\n");
                    os.writeBytes("file /data/adb/lspd/config/modules_config.db 2>/dev/null || echo NO_DB\n");
                    os.writeBytes("echo '--- db header (od) ---'\n");
                    os.writeBytes("od -A x -t x1z /data/adb/lspd/config/modules_config.db 2>/dev/null | head -4\n");
                    os.writeBytes("echo '--- copy db to export ---'\n");
                    os.writeBytes("mkdir -p '" + tmpDir.getAbsolutePath() + "/dbdump'\n");
                    os.writeBytes("cp /data/adb/lspd/config/modules_config.db '" + tmpDir.getAbsolutePath() + "/dbdump/' 2>&1 || echo CP_DB_FAIL\n");
                    os.writeBytes("cp /data/adb/lspd/config/modules_config.db-wal '" + tmpDir.getAbsolutePath() + "/dbdump/' 2>&1 || echo CP_WAL_FAIL\n");
                    os.writeBytes("cp /data/adb/lspd/config/modules_config.db-shm '" + tmpDir.getAbsolutePath() + "/dbdump/' 2>&1 || echo CP_SHM_FAIL\n");
                    os.writeBytes("chmod 666 '" + tmpDir.getAbsolutePath() + "/dbdump/'* 2>&1 || true\n");
                    os.writeBytes("echo '--- find lspd 配置文件 ---'\n");
                    os.writeBytes("find /data/adb/lspd -maxdepth 3 -name '*.db' -o -name '*.json' -o -name '*.xml' 2>/dev/null\n");
                    os.writeBytes("S=''\n");
                    os.writeBytes("for c in sqlite3 /system/xbin/sqlite3 /system/bin/sqlite3; do command -v \"$c\" >/dev/null 2>&1 && { S=\"$c\"; break; }; done\n");
                    os.writeBytes("[ -z \"$S\" ] && [ -x /data/adb/magisk/busybox ] && S=/data/adb/magisk/busybox\n");
                    os.writeBytes("if [ -n \"$S\" ] && [ -f /data/adb/lspd/config/modules_config.db ]; then\n");
                    os.writeBytes("  echo '--- sqlite3: OK ---'\n");
                    os.writeBytes("  echo '--- module row ---'\n");
                    os.writeBytes("  \"$S\" /data/adb/lspd/config/modules_config.db \"SELECT mid,module_pkg_name,enabled,auto_include,apk_path FROM modules WHERE module_pkg_name='io.github.gjr787878.devicereset';\" 2>&1 || echo NO_MODULE_ROW\n");
                    os.writeBytes("  echo '--- scope rows ---'\n");
                    os.writeBytes("  MID=$(\"$S\" /data/adb/lspd/config/modules_config.db \"SELECT mid FROM modules WHERE module_pkg_name='io.github.gjr787878.devicereset';\" 2>&1 | head -1)\n");
                    os.writeBytes("  if [ -n \"$MID\" ]; then \"$S\" /data/adb/lspd/config/modules_config.db \"SELECT app_pkg_name,user_id FROM scope WHERE mid=$MID;\" 2>&1; else echo NO_MID; fi\n");
                    os.writeBytes("  echo '--- all modules (mid/name/enabled) ---'\n");
                    os.writeBytes("  \"$S\" /data/adb/lspd/config/modules_config.db \"SELECT mid,module_pkg_name,enabled FROM modules;\" 2>&1\n");
                    os.writeBytes("  echo '--- real schema (tables/columns) ---'\n");
                    os.writeBytes("  \"$S\" /data/adb/lspd/config/modules_config.db \".tables\" 2>&1\n");
                    os.writeBytes("  \"$S\" /data/adb/lspd/config/modules_config.db \"SELECT count(*) FROM sqlite_master;\" 2>&1\n");
                    os.writeBytes("  \"$S\" /data/adb/lspd/config/modules_config.db \"PRAGMA table_info(modules);\" 2>&1\n");
                    os.writeBytes("  \"$S\" /data/adb/lspd/config/modules_config.db \"PRAGMA table_info(scope);\" 2>&1\n");
                    os.writeBytes("  echo '--- modules raw rows ---'\n");
                    os.writeBytes("  \"$S\" /data/adb/lspd/config/modules_config.db \"SELECT * FROM modules LIMIT 5;\" 2>&1\n");
                    os.writeBytes("else\n");
                    os.writeBytes("  echo '--- sqlite3: UNAVAILABLE (no sqlite3/busybox) ---'\n");
                    os.writeBytes("fi\n");
                    os.writeBytes("exit\n");
                    os.flush();
                    java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(su.getInputStream()));
                    String l;
                    while ((l = r.readLine()) != null) lsp.append(l).append("\n");
                    r.close();
                    su.waitFor();
                } catch (Throwable e) {
                    lsp.append("LSPosed config read error: ").append(e.getMessage()).append("\n");
                }
                writeFile(new java.io.File(tmpDir, "lsposed_status.txt"), lsp.toString());

                // 6.5 自动同步诊断（lsp_sync.log）：显示模块是否在 LSPosed 中注册/启用、作用域是否写入
                java.io.File syncLog = new java.io.File(getExternalFilesDir(null), "lsp_sync.log");
                if (syncLog.exists()) {
                    StringBuilder sl = new StringBuilder();
                    sl.append("=== LSPosed Auto-Sync Log ===\n");
                    sl.append("NO_DB=数据库不存在 | NO_SQLITE=无 sqlite3/busybox（同步未执行） | NO_MID=模块未注册 | SYNCED:n|pkgs=同步成功\n\n");
                    try {
                        java.io.BufferedReader sr = new java.io.BufferedReader(new java.io.FileReader(syncLog));
                        String l;
                        while ((l = sr.readLine()) != null) sl.append(l).append("\n");
                        sr.close();
                    } catch (Throwable ignored) {}
                    writeFile(new java.io.File(tmpDir, "lsp_sync.log"), sl.toString());
                }

                // 6. 打包成 zip —— Android 11+ 分区存储不能直接写公共 Downloads，
                // 改用 MediaStore 写入下载目录（必定成功），toast 提示文件名
                java.io.File zipSrc = java.io.File.createTempFile("drs_log_", ".zip", getCacheDir());
                zipDirectory(tmpDir, zipSrc);
                final String zipPath = exportToDownloads(zipSrc);
                if (zipPath == null) throw new Exception("MediaStore write failed");

                // 清理临时目录
                deleteRecursive(tmpDir);

                runOnUiThread(() -> Toast.makeText(this,
                        t("日志已导出: ", "Log exported: ", "Журнал экспортирован: ") + zipPath,
                        Toast.LENGTH_LONG).show());
            } catch (Throwable e) {
                final String err = e.getMessage();
                runOnUiThread(() -> Toast.makeText(this,
                        t("导出失败: ", "Export failed: ", "Ошибка экспорта: ") + err,
                        Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    /** 通过 MediaStore 把文件写入系统「下载」目录（Android 11+ 唯一可靠方式），返回可读路径 */
    private String exportToDownloads(java.io.File src) {
        try {
            android.content.ContentValues v = new android.content.ContentValues();
            v.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "log.zip");
            v.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/zip");
            v.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_DOWNLOADS);
            android.net.Uri u = getContentResolver().insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (u == null) return null;
            java.io.OutputStream os = getContentResolver().openOutputStream(u);
            if (os == null) return null;
            java.io.FileInputStream fis = new java.io.FileInputStream(src);
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) > 0) os.write(buf, 0, n);
            fis.close();
            os.close();
            return t("已保存到下载目录: ", "Saved to Downloads: ", "Сохранено в загрузки: ")
                    + "Download/log.zip (" + android.provider.MediaStore.MediaColumns.RELATIVE_PATH + ")";
        } catch (Throwable e) {
            return null;
        }
    }

    private void writeFile(java.io.File f, String content) {
        try {
            java.io.FileWriter w = new java.io.FileWriter(f);
            w.write(content);
            w.close();
        } catch (Throwable ignored) {}
    }

    private void zipDirectory(java.io.File srcDir, java.io.File zipFile) throws Exception {
        java.io.FileOutputStream fos = new java.io.FileOutputStream(zipFile);
        java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(fos);
        java.io.File[] files = srcDir.listFiles();
        if (files != null) {
            for (java.io.File f : files) {
                if (f.isDirectory()) {
                    java.io.File[] subFiles = f.listFiles();
                    if (subFiles != null) {
                        for (java.io.File sf : subFiles) {
                            addToZip(zos, sf, f.getName() + "/");
                        }
                    }
                } else {
                    addToZip(zos, f, "");
                }
            }
        }
        zos.close();
        fos.close();
    }

    private void addToZip(java.util.zip.ZipOutputStream zos, java.io.File file, String prefix) throws Exception {
        // 单文件不可读（如 root 拷贝的 600 文件）跳过，不中断整个 zip
        if (!file.canRead()) return;
        java.io.FileInputStream fis = new java.io.FileInputStream(file);
        java.util.zip.ZipEntry entry = new java.util.zip.ZipEntry(prefix + file.getName());
        zos.putNextEntry(entry);
        byte[] buffer = new byte[4096];
        int len;
        while ((len = fis.read(buffer)) > 0) zos.write(buffer, 0, len);
        fis.close();
        zos.closeEntry();
    }

    private void deleteRecursive(java.io.File f) {
        if (f.isDirectory()) {
            java.io.File[] children = f.listFiles();
            if (children != null) for (java.io.File c : children) deleteRecursive(c);
        }
        f.delete();
    }

    // ===== 对话框 =====
    private void showResetDialog() {
        final EditText input = new EditText(this);
        input.setHint(t("输入应用包名，如 com.example.app", "Enter package name, e.g. com.example.app", "Введите имя пакета, например com.example.app"));
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 24, 48, 24);
        layout.addView(input);
        new AlertDialog.Builder(this)
                .setTitle(t("手动重置身份", "Reset Identity", "Сбросить идентичность"))
                .setMessage(t("输入要重置身份的应用包名。重置后该应用不再伪装，恢复真实设备值（需要Root删除哨兵文件；如需重新伪装请再次点击「随机」/「保存」）。",
                        "Enter the package name to reset. The app will no longer be spoofed and will keep real device values (root needed to remove sentinel files; to spoof again just tap Random / Save).",
                        "Введите имя пакета для сброса. Приложение больше не будет подменяться и сохранит реальные значения устройства (для удаления файлов-sentinel нужен Root; для повторной подмены нажмите «Случайно»/«Сохранить»)."))
                .setView(layout)
                .setPositiveButton(t("重置", "Reset", "Сбросить"), (d, w) -> {
                    String pkg = input.getText().toString().trim();
                    if (pkg.isEmpty()) { Toast.makeText(this, t("请输入包名", "Please enter package name", "Введите имя пакета"), Toast.LENGTH_SHORT).show(); return; }
                    try {
                        Config.removeIdentity(this, pkg);
                        boolean ok = SentinelDetector.resetIdentity(pkg, this);
                        if (ok) {
                            // 强停目标应用，使其下次启动按「无身份」恢复真实设备值
                            try {
                                Process su = Runtime.getRuntime().exec("su");
                                java.io.DataOutputStream os = new java.io.DataOutputStream(su.getOutputStream());
                                os.writeBytes("am force-stop " + pkg + " 2>/dev/null\nexit\n");
                                os.flush();
                                su.waitFor();
                            } catch (Throwable ignored) {}
                        }
                        Toast.makeText(this, ok ? t("已重置: ", "Reset: ", "Сброшена: ") + pkg
                                : t("重置失败，请确认已授予ROOT权限（已删除身份配置，下次打开应用生效）",
                                        "Reset failed, ensure ROOT access (identity config removed; applies on next open)",
                                        "Ошибка сброса, проверьте Root-права (конфигурация удалена; применится при следующем открытии)"),
                                Toast.LENGTH_LONG).show();
                        refreshAppList();
                    } catch (Throwable t) {
                        Toast.makeText(this, t("错误: ", "Error: ", "Ошибка: ") + t.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(t("取消", "Cancel", "Отмена"), null)
                .show();
    }

    private void showAboutDialog() {
        new AlertDialog.Builder(this)
                .setTitle("DeviceResetSpooferX")
                .setMessage(t("版本：3.9.6\n\n免开 LSPosed 管理器的设备伪装模块。\n\n在「应用」页添加目标 → 打开详情 →「随机」或「自定义」→ 保存（手动触发）→ 直接打开目标应用即生效。\n支持中文 / English / Русский",
                        "Version: 3.9.6\n\nDevice spoofing module that works without opening the LSPosed Manager.\n\nAdd a target on the Apps tab -> open its detail -> Random / Customize -> Save (manual trigger) -> just reopen the app and it applies.\nSupports Chinese / English / Russian",
                        "Версия: 3.9.6\n\nМодуль подмены устройства, работающий без открытия LSPosed Manager.\n\nДобавьте приложение на вкладке «Приложения» -> откройте детали -> «Случайно»/«Настроить» -> Сохранить (ручной запуск) -> просто откройте приложение снова, и оно применится.\nПоддерживает 中文 / English / Русский"))
                .setPositiveButton(t("确定", "OK", "ОК"), null)
                .show();
    }

    // ===== 自动同步作用域到 LSPosed =====
    // 原理：LSPosed 将「启用模块 + 作用域」存于 /data/adb/lspd/config/modules_config.db（SQLite）。
    // 模块 UI 内选目标后自动把目标包名写入 scope 表（只增不减，不删除用户手动勾选），
    // 这样无需打开 LSPosed 管理器；目标应用强制停止重开后即生效（作用域变更无需重启手机）。
    // 写库前自动备份 modules_config.db.drs_backup；校验失败自动回滚备份。全程静默。
    // 关键：不同 LSPosed 版本表结构可能不同（如 Android 16 新版），
    // 先 PRAGMA table_info 探测真实列名，按实际存在的列自适应拼 SQL，避免静默失败。
    // 同步结果写入 lsp_sync.log（模块外部目录），导出日志时可见，便于诊断。
    /** 自动同步作用域：应用内选/删目标即写入 LSPosed 配置库（root + SQLiteDatabase），
     * 免开 LSPosed 管理器；内容未变化自动跳过。 */
    private void autoSyncScopeToLSPosed() {
        new Thread(() -> {
            try {
                java.util.Set<String> targets = Config.getTargetPackages(this);
                Config.syncScopeToLSPosed(this, targets == null ? new java.util.HashSet<>() : targets);
            } catch (Throwable t) {
                try {
                    java.io.File f = new java.io.File(getExternalFilesDir(null), "lsp_sync.log");
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(f, true);
                    fos.write(("[MainActivity] syncScope error: " + t.getMessage() + "\n").getBytes("UTF-8"));
                    fos.close();
                } catch (Throwable ignored) {}
            }
        }).start();
    }

    /** 语言选择：玻璃风格三选弹窗（中文 / English / Русский），点选即保存并立即全局生效 */
    private void showLanguageDialog() {
        final float d = getResources().getDisplayMetrics().density;
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Math.round(20 * d), Math.round(20 * d), Math.round(20 * d), Math.round(18 * d));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(COLOR_DIALOG_BG);
        bg.setCornerRadius(24 * d);
        bg.setStroke(Math.round(1 * d), 0x55FFFFFF);
        root.setBackground(bg);

        TextView tvTitle = new TextView(this);
        tvTitle.setText(t("选择语言", "Select Language", "Выберите язык"));
        tvTitle.setTextSize(17);
        tvTitle.setTextColor(COLOR_WHITE);
        tvTitle.setTypeface(tvTitle.getTypeface(), android.graphics.Typeface.BOLD);
        root.addView(tvTitle);

        final String[][] opts = { {"中文", LANG_ZH}, {"English", LANG_EN}, {"Русский", LANG_RU} };
        for (final String[] opt : opts) {
            final boolean sel = opt[1].equals(currentLang);
            TextView o = new TextView(this);
            o.setText(opt[0] + (sel ? "  ✓" : ""));
            o.setTextSize(15);
            o.setTextColor(sel ? COLOR_BLUE : COLOR_WHITE);
            o.setPadding(Math.round(14 * d), Math.round(12 * d), Math.round(14 * d), Math.round(12 * d));
            o.setBackground(new GlassButtonDrawable(20 * d, sel ? 2 * d : 1 * d, sel));
            LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            olp.topMargin = Math.round(10 * d);
            root.addView(o, olp);
            o.setOnClickListener(vo -> {
                if (!opt[1].equals(currentLang)) {
                    getSharedPreferences("devicereset_ui", MODE_PRIVATE)
                            .edit().putString(PREFS_LANG, opt[1]).apply();
                    currentLang = opt[1];
                }
                dialog.dismiss();
                recreate(); // 立即全局刷新界面语言
            });
        }

        dialog.setContentView(root);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x88000000));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    // ===== 更新检测（保留原项目逻辑） =====
    private void checkUpdate(boolean manual) {
        String versionName;
        try {
            versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) { versionName = "0"; }
        UpdateChecker.check(UPDATE_REPO, versionName,
                (latest, tag, hasUpdate, error) -> runOnUiThread(() -> {
                    if (hasUpdate) {
                        showUpdateDialog(latest, tag);
                    } else if (manual) {
                        Toast.makeText(this, error != null ?
                                t("检查更新失败: ", "Update check failed: ", "Не удалось проверить обновления: ") + error :
                                t("已是最新版本", "You are up to date", "У вас последняя версия"),
                                Toast.LENGTH_SHORT).show();
                    }
                }));
    }

    private void showUpdateDialog(String latest, String tag) {
        new AlertDialog.Builder(this)
                .setTitle(t("发现新版本 v", "New version v", "Доступна новая версия v") + latest)
                .setMessage(t("检测到新版本，是否下载？", "New version detected. Download?", "Обнаружена новая версия. Скачать?"))
                .setPositiveButton(t("下载", "Download", "Скачать"), (d, w) -> {
                    String asset = "DeviceResetSpooferX-v" + latest + ".apk";
                    String direct = "https://github.com/" + UPDATE_REPO + "/releases/download/" + tag + "/" + asset;
                    String[] mirrors = {"https://ghfast.top/", "https://gh-proxy.com/", "https://ghproxy.net/",
                            "https://mirror.ghproxy.com/", "https://github.moeyy.xyz/"};
                    String[] urls = new String[mirrors.length + 1];
                    for (int i = 0; i < mirrors.length; i++) urls[i] = mirrors[i] + direct;
                    urls[mirrors.length] = direct;
                    AppDownloader.start(this, urls, REPO_HOME_URL,
                            "DeviceResetSpooferX-v" + latest + ".apk", latest, currentLang);
                })
                .setNegativeButton(t("取消", "Cancel", "Отмена"), null)
                .show();
    }
}
