package io.github.gjr787878.devicereset.ui;

import android.graphics.drawable.Drawable;

/**
 * 已安装应用信息。
 */
public class AppInfo {
    public String packageName;
    public String appName;
    public Drawable icon;
    public boolean isSystemApp;
    public boolean isSelected;

    public AppInfo(String packageName, String appName, Drawable icon,
                   boolean isSystemApp, boolean isSelected) {
        this.packageName = packageName;
        this.appName = appName;
        this.icon = icon;
        this.isSystemApp = isSystemApp;
        this.isSelected = isSelected;
    }
}
