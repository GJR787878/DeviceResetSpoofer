# DeviceResetSpoofer

> 免开 LSPosed 管理器的设备伪装模块：应用内选择目标 → 随机/自定义 → 保存即生效
> Device spoofing module that works without opening LSPosed Manager: pick targets in-app → Random / Customize → Save, done

[![Android](https://img.shields.io/badge/Android-7.0%20~%2016-green.svg)](https://www.android.com/)
[![LSPosed](https://img.shields.io/badge/LSPosed-Required-blue.svg)](https://github.com/LSPosed/LSPosed)
[![Root](https://img.shields.io/badge/Root-Required-orange.svg)]()
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

[English](#-english) | [中文](#-中文) | [Русский](#-русский)

---

## 📱 Screenshots / 界面预览

| 主界面 / Main | 设置界面 / Settings |
|:---:|:---:|
| ![主界面](images/screenshot_main_en.png) | ![设置界面](images/screenshot_config_en.png) |

---

## 🇬🇧 English

### Table of Contents
- [Introduction](#introduction)
- [Features](#-features)
- [Requirements](#-requirements)
- [Installation](#-installation)
- [Usage](#-usage)
- [How It Works](#-how-it-works)
- [Spoofed Identifiers](#-spoofed-identifiers)
- [FAQ](#-faq)
- [Warnings](#️-warnings)
- [License](#-license)

### Introduction

An LSPosed module that **spoofs a fresh device identity for the apps you pick, right from inside this app — no need to open LSPosed Manager again** after a one-time setup. Select a target app, tap Random / Customize, Save, and reopen the app: the new identity is applied.

### ✨ Features

- **No more LSPosed Manager**: tick "System Framework" once in LSPosed Manager, then everything (add/remove targets, write identities) happens in this app
- **Manual trigger**: adding a target to the list is NOT applying — tap **Random / Customize → Save** in its detail dialog to write the spoofed identity
- **Sentinel-driven real injection**: only processes whose private dir contains a readable `.identity_sentinel` get hooked; everything else is skipped with zero overhead
- **Applies without reboot**: after Save, just reopen the target app — no phone reboot, no LSPosed
- **Clear-data helper**: apps that read identity only at first launch may need **Clear Data** (offered right in the dialog) to see the new values
- **Multi-dimensional spoofing**: Android ID, Advertising ID, IMEI/MEID, IMSI/ICCID, device model, MAC address, GSF ID, serial, carrier info
- **Independent toggles**: each hook item (Android ID / Ad ID / IMEI / Model / MAC, …) can be enabled/disabled independently
- **Trilingual UI**: Chinese / English / Русский, switch anytime
- **Glass UI & filters**: frosted-glass capsule style, filter System / Installed apps, long-press to remove targets
- **Cached app list**: icon + entry caching, lazy icon loading; new installs / uninstalls are detected automatically (no manual refresh needed)

### 📋 Requirements

| Item | Requirement |
|------|-------------|
| **Android Version** | Android 7.0 ~ Android 16 (API 24 ~ 36) |
| **Root Access** | Required (writing the sentinel file) |
| **Xposed Framework** | LSPosed / LSPosed_mod (recommended) |
| **Architecture** | arm64-v8a, armeabi-v7a, x86, x86_64 |
| **Storage** | ~6MB |

> **Note**: Tested on LSPosed only. Other frameworks such as EdXposed may have compatibility issues.

### 📦 Installation

1. Download the latest APK from the [Releases](https://github.com/GJR787878/DeviceResetSpoofer/releases) page and install it
2. Open **LSPosed Manager** → **Modules** → enable **DeviceResetSpoofer**
3. Tap the module → **Scope** → **tick "System Framework"** (global injection — this is the ONLY time you need LSPosed)
4. **Reboot the phone once** (required for the module to take effect)
5. From now on you never need to open LSPosed Manager again

### 🚀 Usage

1. Open the **DeviceResetSpoofer** app → **Apps** tab
2. Tap **+ Select App** to pick target apps (filter System / Installed as needed)
3. Tap a target row to open its detail dialog → tap **Random** or enter **Customize** → **Save** (this is the manual trigger that writes the spoofed identity)
4. Just reopen the target app — the new identity is applied. **No reboot, no LSPosed**
5. If the app still reports old values (identity read at first launch), open its detail dialog again → **Clear Data** → reopen the app directly (no need to launch the app via the module)

> **Tip**: A green "已注入 ✓ / Injected ✓" status is shown once the module is enabled with global scope. The list auto-refreshes when apps are installed/uninstalled.

### 🔧 How It Works

The module hooks globally (via System Framework scope) but **costs nothing for non-targets**: it only installs hooks when the target process's private directory contains a readable `.identity_sentinel` (chmod 666, readable across UID — the root cause of earlier "hooked but not applied" issues). Identity values are stored in the module config; **Save (Random/Customize) is the manual trigger** that writes the values + sentinel. Reopening the target app then reads the spoofed values.

### 🎭 Spoofed Identifiers

- Android ID (SSAID)
- Advertising ID (AAID) / AppSet ID
- IMEI / MEID / IMSI / ICCID
- Serial number / MAC address
- GSF ID
- Device info: Brand, Model, Manufacturer, Build fingerprint
- Carrier info: Code, Name, Country

### ❓ FAQ

**Q: I added the app to the list but nothing changed?**
A: Adding a target only manages the list. You must open its detail dialog and tap **Random / Customize → Save** to actually write the spoofed identity (manual trigger).

**Q: The status still shows "not injected"?**
A: Check that the module is enabled in LSPosed Manager and "System Framework" is ticked in its scope, then reboot once. The status is database-driven — it reflects the real LSPosed state.

**Q: The app still reads the old identity after Save?**
A: Some apps cache the identity on first launch. Use **Clear Data** in the detail dialog (partial apps require clearing data to take effect), then reopen the target app directly.

**Q: Do I need to reboot every time I check a new app?**
A: No. Save → reopen the target app is all it takes. Rebooting is needed only once after the initial module enable.

**Q: New apps installed / uninstalled don't show up?**
A: The app list is cached for speed and rebuilt automatically when the installed package set changes.

**Q: App crashes when launched from LSPosed's quick-launch button?**
A: This is a conflict between LSPosed's quick-launch and hook timing. Open the app from the desktop icon instead. If it crashes on first launch, clear its data once and open it again.

### ⚠️ Warnings

- For **personal privacy protection and technical testing only**
- Some apps detect Xposed/Root traces, **account ban risk exists**
- **Clear Data wipes the target app's own data** (login, cache, etc.) — use it only when the identity doesn't refresh
- Apps reading system properties directly at the native layer cannot be intercepted by Java hooks
- Test on non-critical apps first
- Troubleshooting: LSPosed → Logs → Search "DeviceReset"; or use **Export Log** in Settings to share the log

### 📄 License

[MIT License](LICENSE)

---

## 🇨🇳 中文

### 目录
- [简介](#简介)
- [功能特性](#-功能特性)
- [系统要求](#-系统要求)
- [安装方法](#-安装方法)
- [使用方法](#-使用方法)
- [工作原理](#-工作原理)
- [伪装的识别码](#-伪装的识别码)
- [常见问题](#-常见问题)
- [注意事项](#️-注意事项)
- [许可证](#-许可证)

### 简介

一个 LSPosed 模块：**安装时在 LSPosed 里勾一次「系统框架」，之后全程免开 LSPosed**——在本应用内选择目标应用，随机/自定义生成伪装值，保存后直接打开目标应用即生效。

### ✨ 功能特性

- **免开 LSPosed 管理器**：仅在 LSPosed 中勾选「系统框架」一次（全局注入），之后增删目标、写入伪装值全部在本应用内完成
- **手动触发**：把应用加入目标列表 ≠ 生效；需在其详情弹窗中点击**随机/自定义 → 保存**才会写入伪装值
- **哨兵驱动真授权**：只有目标应用私有目录存在可读哨兵文件 `.identity_sentinel` 的进程才注入 Hook，其余进程零开销跳过
- **保存即生效，无需重启**：保存后直接重新打开目标应用即可，不用重启手机、不用开 LSPosed
- **清数据辅助**：部分应用只在首次启动读取身份，可在弹窗内一键**清空数据**后直接打开目标应用生效
- **多维度伪装**：Android ID、广告 ID、IMEI/MEID、IMSI/ICCID、设备型号、MAC 地址、GSF ID、序列号、运营商信息
- **独立开关**：各 Hook 项（Android ID / 广告 ID / IMEI / 设备型号 / MAC 等）可独立开启/关闭
- **三语界面**：中文 / English / Русский，随时切换
- **玻璃胶囊 UI**：毛玻璃胶囊风格；支持系统/安装应用筛选；长按删除目标
- **列表缓存加速**：图标与条目缓存、图标懒加载；自动检测新装/卸载应用，无需手动刷新

### 📋 系统要求

| 项目 | 要求 |
|------|------|
| **Android 版本** | Android 7.0 ~ Android 16（API 24 ~ 36） |
| **Root 权限** | 必须（用于写入哨兵文件） |
| **Xposed 框架** | LSPosed / LSPosed_mod（推荐） |
| **架构** | arm64-v8a, armeabi-v7a, x86, x86_64 |
| **存储空间** | 约 6MB |

> **注意**：本模块仅在 LSPosed 框架下测试通过，EdXposed 等其他框架可能存在兼容性问题。

### 📦 安装方法

1. 前往 [Releases](https://github.com/GJR787878/DeviceResetSpoofer/releases) 页面下载最新版 APK 并安装
2. 打开 **LSPosed 管理器** → **模块** → 启用 **DeviceResetSpoofer**
3. 点击模块进入**作用域**，**勾选「系统框架」**（全局注入——这是唯一一次需要打开 LSPosed）
4. **重启手机一次**（首次启用模块必须）
5. 此后完全不需要再打开 LSPosed 管理器

### 🚀 使用方法

1. 打开 **DeviceResetSpoofer** 应用 → **Apps** 页
2. 点击 **+ Select App** 选择目标应用（可按系统/安装应用筛选）
3. 点击目标行打开详情弹窗 → 点击**随机**或输入**自定义** → **保存**（此即手动触发，写入伪装值）
4. 直接重新打开目标应用即生效。**无需重启，无需 LSPosed**
5. 若目标应用仍读到旧值（首次启动才读取身份）：再次打开其详情弹窗 → **清空数据** → 直接打开目标应用（无需通过模块启动）

> **提示**：模块启用且全局作用域生效后显示绿色「已注入✓」状态；应用列表在系统有新装/卸载时自动更新。

### 🔧 工作原理

模块通过「系统框架」作用域全局注入，但对非目标进程**零开销**：只有目标进程私有目录存在可读哨兵文件 `.identity_sentinel`（chmod 666，跨 UID 可读——正是早期"已 Hook 却未生效"的根因）才安装 Hook。伪装值存放在模块配置中，**随机/自定义 → 保存是手动触发**，写入值并落哨兵；重新打开目标应用即读取到伪装值。

### 🎭 伪装的识别码

- Android ID（SSAID）
- 广告 ID（AAID）/ AppSet ID
- IMEI / MEID / IMSI / ICCID
- Serial 序列号 / MAC 地址
- GSF ID
- 设备型号：品牌、型号、厂商、Build 指纹
- 运营商信息：代码、名称、国家

### ❓ 常见问题

**Q：把应用加入列表了，为什么没生效？**
A：加入目标只是管理列表。必须打开其详情弹窗，点击**随机/自定义 → 保存**才会真正写入伪装值（手动触发）。

**Q：状态仍显示未注入？**
A：检查 LSPosed 管理器中模块已启用、作用域已勾选「系统框架」，并重启一次。该状态为数据库驱动，反映 LSPosed 真实状态。

**Q：保存后目标应用仍读到旧值？**
A：部分应用首次启动就缓存身份。在详情弹窗使用**清空数据**（部分应用需清空数据才能生效），然后直接打开目标应用。

**Q：每次勾选新应用都要重启吗？**
A：不用。保存 → 重新打开目标应用即可；只有首次启用模块时需要重启一次。

**Q：新装/卸载的应用没出现在列表？**
A：应用列表为加速做了缓存，系统应用集合变化时自动重建。

**Q：从 LSPosed 快速启动按钮打开应用闪退？**
A：这是 LSPosed 快速启动与 Hook 注入时序冲突导致。请从桌面图标直接打开应用；若首次打开闪退，清除一次该应用数据再打开。

### ⚠️ 注意事项

- 本模块**仅用于个人隐私保护和技术测试**，请勿用于非法用途
- 部分应用会检测 Xposed/Root 痕迹，**存在账号封禁风险**
- **清空数据会清除目标应用自身数据**（登录态、缓存等），仅在身份刷新不了时使用
- native 层直接读取系统属性的应用，Java 层 Hook 无法拦截
- 建议先在不重要的应用上测试
- 排查问题：LSPosed → 日志 → 搜索「DeviceReset」；或在设置页使用「导出日志」分享日志

### 📄 许可证

[MIT License](LICENSE)

---

## 🇷🇺 Русский

### Содержание
- [Введение](#введение)
- [Возможности](#-возможности)
- [Требования](#-требования)
- [Установка](#-установка)
- [Использование](#-использование)
- [Как это работает](#-как-это-работает)
- [Подделываемые идентификаторы](#-подделываемые-идентификаторы)
- [Часто задаваемые вопросы](#-часто-задаваемые-вопросы)
- [Предупреждения](#️-предупреждения)
- [Лицензия](#-лицензия)

### Введение

Модуль LSPosed, который **подделывает идентичность устройства для выбранных вами приложений прямо из этого приложения — больше не нужно открывать LSPosed Manager** после однократной настройки. Выберите целевое приложение, нажмите «Случайно»/«Настроить», сохраните и снова откройте приложение: новая идентичность применена.

### ✨ Возможности

- **Без LSPosed Manager**: отметьте «System Framework» один раз в LSPosed Manager — дальше всё (добавление/удаление целей, запись идентичности) делается в этом приложении
- **Ручной запуск**: добавление приложения в список ≠ применение — нажмите **«Случайно»/«Настроить» → Сохранить** в диалоге деталей, чтобы записать подделанную идентичность
- **Детекция по сторожевому файлу**: хуки ставятся только в процессы, в приватном каталоге которых есть читаемый `.identity_sentinel`; всё остальное пропускается с нулевой нагрузкой
- **Применяется без перезагрузки**: после «Сохранить» просто откройте целевое приложение — никакой перезагрузки телефона, никакого LSPosed
- **Помощник очистки данных**: приложениям, читающим идентичность только при первом запуске, может потребоваться **Очистить данные** (предлагается прямо в диалоге), чтобы увидеть новые значения
- **Многомерное подделывание**: Android ID, рекламный ID, IMEI/MEID, IMSI/ICCID, модель устройства, MAC-адрес, GSF ID, серийный номер, информация об операторе
- **Независимые переключатели**: каждый элемент хука (Android ID / Ad ID / IMEI / Model / MAC и т.д.) включается/отключается независимо
- **Трёхъязычный интерфейс**: 中文 / English / Русский, переключение в любой момент
- **Стеклянный UI и фильтры**: матово-стеклянный стиль-капсула, фильтр системных/установленных приложений, удаление целей долгим нажатием
- **Кэш списка приложений**: кэш иконок и записей, ленивая загрузка иконок; новые установки/удаления обнаруживаются автоматически

### 📋 Требования

| Пункт | Требование |
|------|-------------|
| **Версия Android** | Android 7.0 ~ Android 16 (API 24 ~ 36) |
| **Права Root** | Обязательны (для записи сторожевого файла) |
| **Фреймворк Xposed** | LSPosed / LSPosed_mod (рекомендуется) |
| **Архитектура** | arm64-v8a, armeabi-v7a, x86, x86_64 |
| **Хранилище** | ~6 МБ |

> **Примечание**: Протестировано только на LSPosed. Другие фреймворки, такие как EdXposed, могут иметь проблемы совместимости.

### 📦 Установка

1. Скачайте и установите последний APK со страницы [Releases](https://github.com/GJR787878/DeviceResetSpoofer/releases)
2. Откройте **LSPosed Manager** → **Modules** → включите **DeviceResetSpoofer**
3. Нажмите на модуль → **Scope** → **отметьте «System Framework»** (глобальная инъекция — это единственный раз, когда нужен LSPosed)
4. **Перезагрузите телефон один раз** (обязательно для активации модуля)
5. Дальше LSPosed Manager больше никогда не нужен

### 🚀 Использование

1. Откройте приложение **DeviceResetSpoofer** → вкладка **Apps**
2. Нажмите **+ Select App**, чтобы выбрать целевые приложения (при необходимости фильтр Системные/Установленные)
3. Нажмите на строку цели, чтобы открыть диалог деталей → нажмите **«Случайно»** или введите **«Настроить»** → **Сохранить** (это ручной запуск, записывающий подделанную идентичность)
4. Просто снова откройте целевое приложение — новая идентичность применена. **Без перезагрузки, без LSPosed**
5. Если приложение по-прежнему показывает старые значения (идентичность читается при первом запуске): снова откройте его диалог деталей → **Очистить данные** → откройте приложение напрямую

> **Совет**: Зелёный статус «已注入✓ / Injected✓» появляется, когда модуль включён с глобальной областью действия. Список автоматически обновляется при установке/удалении приложений.

### 🔧 Как это работает

Модуль внедряется глобально (через область System Framework), но **ничего не стоит для нецелевых процессов**: хуки ставятся только тогда, когда в приватном каталоге целевого процесса есть читаемый `.identity_sentinel` (chmod 666, читаемый между UID — корень проблемы ранних версий «хук есть, а применения нет»). Значения идентичности хранятся в конфигурации модуля; **«Случайно»/«Настроить» → «Сохранить» — это ручной запуск**, который записывает значения и сторожевой файл. При повторном открытии целевое приложение читает подделанные значения.

### 🎭 Подделываемые идентификаторы

- Android ID (SSAID)
- Рекламный ID (AAID) / AppSet ID
- IMEI / MEID / IMSI / ICCID
- Серийный номер / MAC-адрес
- GSF ID
- Информация об устройстве: бренд, модель, производитель, отпечаток Build
- Информация об операторе: код, название, страна

### ❓ Часто задаваемые вопросы

**Q: Я добавил приложение в список, но ничего не изменилось?**
A: Добавление цели только управляет списком. Нужно открыть её диалог деталей и нажать **«Случайно»/«Настроить» → «Сохранить»**, чтобы фактически записать подделанную идентичность (ручной запуск).

**Q: Статус всё ещё показывает «не внедрено»?**
A: Проверьте, что модуль включён в LSPosed Manager и в его области действия отмечен «System Framework», затем перезагрузитесь один раз. Статус управляется базой данных и отражает реальное состояние LSPosed.

**Q: После «Сохранить» приложение всё ещё читает старые значения?**
A: Некоторые приложения кэшируют идентичность при первом запуске. Используйте **«Очистить данные»** в диалоге деталей (частичным приложениям требуется очистка данных), затем снова откройте целевое приложение.

**Q: Нужно ли перезагружаться при каждом выборе нового приложения?**
A: Нет. «Сохранить» → снова открыть целевое приложение — этого достаточно. Перезагрузка нужна только один раз после первоначального включения модуля.

**Q: Новые установленные/удалённые приложения не появляются?**
A: Список приложений кэшируется для скорости и автоматически перестраивается при изменении набора установленных пакетов.

**Q: Приложение вылетает при запуске через кнопку быстрого запуска LSPosed?**
A: Это конфликт между быстрым запуском LSPosed и временем инъекции хука. Открывайте приложение с рабочего стола. Если при первом запуске происходит вылет, один раз очистите данные приложения и откройте снова.

### ⚠️ Предупреждения

- Только для **защиты личной конфиденциальности и технического тестирования**
- Некоторые приложения обнаруживают следы Xposed/Root, **существует риск блокировки аккаунта**
- **«Очистить данные» удаляет собственные данные целевого приложения** (вход, кэш и т.д.) — используйте только когда идентичность не обновляется
- Приложения, читающие системные свойства напрямую на нативном уровне, не могут быть перехвачены Java-хуками
- Сначала тестируйте на некритичных приложениях
- Устранение неполадок: LSPosed → Logs → Поиск «DeviceReset»; или «Экспорт журнала» в настройках

### 📄 Лицензия

[MIT License](LICENSE)

---

## ⭐ Support

If this project helps you, please give it a Star ⭐

For issues or suggestions, please submit an [Issue](https://github.com/GJR787878/DeviceResetSpoofer/issues).
