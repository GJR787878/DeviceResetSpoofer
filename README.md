# DeviceResetSpoofer

[中文](#中文) | [English](#english)

---

## 中文

### 简介

一个 LSPosed 模块：对选中的应用，在**清除应用数据后自动生成全新的设备识别码**。

### 功能特性

- ✅ 清除目标应用数据后，下次冷启动自动获得全新设备身份
- ✅ 支持伪装：Android ID、广告ID(AAID)、IMEI/MEID、设备型号(Build)、MAC地址、GSF ID、运营商信息
- ✅ 按应用隔离，每个应用独立身份
- ✅ 手动重置某个应用的身份
- ✅ 应用搜索、系统应用过滤
- ✅ 详细的使用说明和常见问题解答

### 工作原理（哨兵文件检测法）

Android 系统没有"应用数据被清除"的广播，但清除数据的本质是删除 `/data/data/<package>/` 整个目录。

模块在目标应用私有目录放置一个隐藏哨兵文件 `.identity_sentinel`，内容为当前身份的 JSON。

每次应用冷启动时：
- 哨兵文件**存在** → 数据没被清 → 沿用旧身份
- 哨兵文件**不存在** → 数据被清了 → 生成全新身份并写入新哨兵

### 伪装的识别码清单

| 类别 | 具体项 |
|------|--------|
| 核心ID | Android ID(SSAID)、广告ID(AAID)、AppSet ID、GSF ID |
| 电话 | IMEI、MEID、IMSI、ICCID、SIM序列号 |
| 硬件 | Serial序列号、MAC地址 |
| 设备信息 | 品牌、型号、厂商、设备名、Build指纹、Bootloader、Radio版本 |
| 运营商 | 运营商代码、名称、国家ISO |

每次生成的身份**整套自洽**（品牌/型号/指纹匹配，IMEI通过Luhn校验）。

### 使用方法

1. 手机已 root，安装 Magisk + LSPosed（或 Zygisk + LSPosed）
2. 安装本模块 APK
3. 打开 LSPosed 管理器 → 模块 → 启用 DeviceResetSpoofer
4. 点击模块 → **作用域** → 勾选目标应用
5. **重启手机**（必须重启，LSPosed 注入需要重启）
6. 打开模块配置界面 → 勾选需要保护的应用
7. 去系统设置 → 应用 → 目标应用 → 存储 → 清除数据
8. 重新打开目标应用，它将获得全新的设备识别码

> 菜单中还有「手动重置身份」和「使用说明」。

### 常见问题

**Q: 清除数据后身份没变？**
A: 检查：1) LSPosed作用域是否勾选 2) 模块配置界面是否勾选 3) 是否重启手机 4) 清除数据后是否完全杀掉进程再打开

**Q: 从LSPosed右下角启动按钮打开应用闪退？**
A: 这是LSPosed快速启动功能与Hook注入时序冲突导致的。解决方法：直接从桌面图标打开应用，不要用LSPosed的启动按钮。

**Q: 加壳应用不生效？**
A: 在LSPosed作用域设置中，对该应用勾选「排除资源钩子」选项。

**Q: 如何查看模块是否正常工作？**
A: 打开LSPosed管理器 → 日志 → 搜索「DeviceReset」，可以看到模块的加载状态和身份生成信息。

### 注意事项

- 本模块仅用于个人隐私保护和技术测试
- 部分应用会检测 Xposed/Root 痕迹，存在账号封禁风险
- native 层直接读取系统属性的应用，Java 层 Hook 无法拦截
- 建议先在不重要的应用上测试，确认无误后再用于重要应用

### 构建

#### GitHub Actions（推荐）
Push 代码到 main 分支，Actions 会自动编译，在 Artifacts 中下载 APK。

#### 本地构建
```bash
./gradlew assembleDebug
# APK 输出在 app/build/outputs/apk/debug/
```

### 项目结构

```
app/src/main/java/com/devicereset/
├── xposed/
│   ├── MainHook.java           # LSPosed 入口
│   ├── SentinelDetector.java   # 哨兵文件检测器（核心）
│   ├── IdentityGenerator.java  # 随机身份生成器
│   └── Identity.java           # 身份数据模型
├── hooks/
│   ├── AndroidIdHook.java      # Android ID
│   ├── AdvertisingIdHook.java  # 广告ID / AppSet ID
│   ├── TelephonyHook.java      # IMEI / MEID / 运营商
│   ├── BuildInfoHook.java      # 设备型号信息
│   ├── WifiMacHook.java        # MAC 地址
│   └── GsfIdHook.java          # GSF ID
├── ui/
│   ├── MainActivity.java       # 配置界面
│   ├── HelpActivity.java       # 使用说明页面
│   ├── AppListAdapter.java     # 应用列表
│   └── AppInfo.java            # 应用信息
└── Config.java                  # 配置管理
```

### License

MIT

---

## English

### Introduction

An LSPosed module that **automatically generates a brand new device identity after clearing app data** for selected applications.

### Features

- ✅ Automatically obtain a new device identity on next cold start after clearing target app data
- ✅ Spoofing support: Android ID, Advertising ID (AAID), IMEI/MEID, Build info, MAC address, GSF ID, carrier info
- ✅ Per-app isolation, each app has independent identity
- ✅ Manually reset identity for any app
- ✅ App search and system app filtering
- ✅ Comprehensive usage guide and FAQ

### How It Works (Sentinel File Detection)

Android has no system broadcast for "app data cleared", but clearing data essentially deletes the entire `/data/data/<package>/` directory.

The module places a hidden sentinel file `.identity_sentinel` in the target app's private directory, containing the current identity as JSON.

On each cold start:
- Sentinel file **exists** → data not cleared → reuse existing identity
- Sentinel file **not found** → data was cleared → generate new identity and write new sentinel

### Spoofed Identifiers

| Category | Items |
|----------|-------|
| Core IDs | Android ID (SSAID), Advertising ID (AAID), AppSet ID, GSF ID |
| Telephony | IMEI, MEID, IMSI, ICCID, SIM serial |
| Hardware | Serial number, MAC address |
| Device Info | Brand, model, manufacturer, device name, Build fingerprint, Bootloader, Radio version |
| Carrier | Carrier code, name, country ISO |

Each generated identity is **internally consistent** (brand/model/fingerprint match, IMEI passes Luhn check).

### Usage

1. Rooted phone with Magisk + LSPosed (or Zygisk + LSPosed)
2. Install the module APK
3. Open LSPosed Manager → Modules → Enable DeviceResetSpoofer
4. Tap the module → **Scope** → check target apps
5. **Reboot phone** (required for LSPosed injection)
6. Open the module config app → check apps to protect
7. Go to System Settings → Apps → Target app → Storage → Clear data
8. Reopen the target app, it will get a brand new device identity

> The menu also has "Reset Identity" and "Usage Guide".

### FAQ

**Q: Identity didn't change after clearing data?**
A: Check: 1) LSPosed scope is checked 2) Module config has the app checked 3) Phone was rebooted 4) App process was fully killed before reopening

**Q: App crashes when launched from LSPosed's play button?**
A: This is caused by a timing conflict between LSPosed's quick launch and hook injection. Solution: Launch the app from the desktop icon instead of LSPosed's button.

**Q: Packed/protected apps don't work?**
A: In LSPosed scope settings, enable "Exclude resource hooks" for that app.

**Q: How to verify the module is working?**
A: Open LSPosed Manager → Logs → search for "DeviceReset" to see module load status and identity generation info.

### Notes

- This module is for personal privacy protection and technical testing only
- Some apps detect Xposed/Root traces, account ban risk exists
- Apps reading system properties directly at native layer cannot be intercepted by Java-level hooks
- Test on non-critical apps first before using with important accounts

### Build

#### GitHub Actions (recommended)
Push code to main branch, Actions will auto-build. Download APK from Artifacts.

#### Local build
```bash
./gradlew assembleDebug
# APK output: app/build/outputs/apk/debug/
```

### Project Structure

```
app/src/main/java/com/devicereset/
├── xposed/
│   ├── MainHook.java           # LSPosed entry
│   ├── SentinelDetector.java   # Sentinel file detector (core)
│   ├── IdentityGenerator.java  # Random identity generator
│   └── Identity.java           # Identity data model
├── hooks/
│   ├── AndroidIdHook.java      # Android ID
│   ├── AdvertisingIdHook.java  # Advertising ID / AppSet ID
│   ├── TelephonyHook.java      # IMEI / MEID / Carrier
│   ├── BuildInfoHook.java      # Device model info
│   ├── WifiMacHook.java        # MAC address
│   └── GsfIdHook.java          # GSF ID
├── ui/
│   ├── MainActivity.java       # Config UI
│   ├── HelpActivity.java       # Help / usage guide
│   ├── AppListAdapter.java     # App list
│   └── AppInfo.java            # App info
└── Config.java                  # Config manager
```

### License

MIT
