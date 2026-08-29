# DeviceResetSpoofer

> 清除应用数据后自动生成全新设备识别码的 LSPosed 模块
> An LSPosed module that automatically generates a brand-new device identity after clearing app data

[![Android](https://img.shields.io/badge/Android-7.0%20~%2016-green.svg)](https://www.android.com/)
[![LSPosed](https://img.shields.io/badge/LSPosed-Required-blue.svg)](https://github.com/LSPosed/LSPosed)
[![Root](https://img.shields.io/badge/Root-Required-orange.svg)]()
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

[中文](#-中文) | [English](#-english) | [Русский](#-русский)

---

## 📱 界面预览 / Screenshots

| 主界面 / Main | 配置界面 / Config |
|:---:|:---:|
| ![主界面](images/screenshot_main_en.png) | ![配置界面](images/screenshot_config_en.png) |

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

一个 LSPosed 模块：对选中的应用，在**清除应用数据后自动生成全新的设备识别码**。

### ✨ 功能特性

- **自动换身份**：对选中的应用，清除应用数据后自动生成全新设备身份
- **多维度伪装**：Android ID、广告 ID、IMEI/MEID、设备型号、MAC 地址、GSF ID、运营商信息
- **中英文双语**：一键切换中英文界面
- **手动重置**：可随时手动重置某个应用的身份
- **独立开关**：各 Hook 项可独立开启/关闭
- **哨兵检测**：基于私有目录哨兵文件检测数据清除，无需监听系统广播

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

1. 前往 [Releases](https://github.com/GJR787878/DeviceResetSpoofer/releases) 页面下载最新版 APK
2. 安装 APK
3. 打开 **LSPosed 管理器** → **模块** → 找到 **DeviceResetSpoofer** → 启用模块
4. 点击模块进入 **作用域** 设置，勾选需要保护的目标应用
5. **重启手机**（必须重启，否则模块不生效）

### 🚀 使用方法

1. 打开 **DeviceResetSpoofer** 应用
2. 点击 **「运行日志」** 按钮进入配置界面
3. 在应用列表中**勾选**需要保护的目标应用
4. （可选）在下方开关中调整需要伪装的识别码类型
5. 前往 **系统设置** → **应用** → 目标应用 → **存储** → **清除数据**
6. 重新打开目标应用，即获得全新的设备身份

> **提示**：也可以在配置界面右上角菜单中选择「手动重置身份」，无需清除数据即可换身份。

### 🔧 工作原理

模块在目标应用私有目录放置隐藏哨兵文件 `.identity_sentinel`。清除应用数据会删除整个私有目录，哨兵文件也被删除。下次应用启动时检测到哨兵不存在，即生成全新设备身份并写入新哨兵。

### 🎭 伪装的识别码

- Android ID（SSAID）
- 广告 ID（AAID）/ AppSet ID
- IMEI / MEID / IMSI / ICCID
- Serial 序列号 / MAC 地址
- GSF ID
- 设备型号：品牌、型号、厂商、Build 指纹
- 运营商信息：代码、名称、国家

### ❓ 常见问题

**Q：清除数据后身份没变？**
A：检查以下几点：
1. LSPosed 作用域是否勾选了目标应用
2. 模块配置界面是否勾选了目标应用
3. 是否重启了手机
4. 清除数据后是否完全杀掉进程再重新打开

**Q：从 LSPosed 右下角启动按钮打开应用闪退？**
A：这是 LSPosed 的快速启动功能与 Hook 注入时序冲突导致的。
解决方法：不要用 LSPosed 的启动按钮，直接从桌面图标打开应用。
如果首次打开闪退，先清除一次应用数据再打开即可。

**Q：加壳应用不生效？**
A：在 LSPosed 作用域设置中，对该应用勾选「排除资源钩子」选项。
部分加固应用可能需要额外处理。

**Q：如何手动重置身份（不清除数据）？**
A：在模块配置界面，点击右上角菜单 → 手动重置身份 → 选择应用。
重置后该应用下次启动将获得全新身份。

**Q：每次启动都变身份？**
A：正常情况下，只有清除数据后才会换身份。
如果每次启动都变，说明哨兵文件写入失败（可能是权限问题），检查应用是否有存储权限，或尝试重新安装模块。

### ⚠️ 注意事项

- 本模块**仅用于个人隐私保护和技术测试**，请勿用于非法用途
- 部分应用会检测 Xposed/Root 痕迹，**存在账号封禁风险**
- 加壳应用请在 LSPosed 作用域设置中勾选「排除资源钩子」
- native 层直接读取系统属性的应用，Java 层 Hook 无法拦截
- 建议先在不重要的应用上测试
- 排查问题：LSPosed → 日志 → 搜索「DeviceReset」

### 📄 许可证

[MIT License](LICENSE)

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

An LSPosed module that **automatically generates a brand-new device identity after clearing app data** for selected applications.

### ✨ Features

- **Auto identity reset**: Generate a new device identity automatically after clearing app data
- **Multi-dimensional spoofing**: Android ID, Advertising ID, IMEI/MEID, Device model, MAC address, GSF ID, Carrier info
- **Bilingual UI**: One-click switch between Chinese and English
- **Manual reset**: Manually reset identity for any app at any time
- **Independent toggles**: Each hook item can be enabled/disabled independently
- **Sentinel detection**: Detect data clearing via a sentinel file in the private directory

### 📋 Requirements

| Item | Requirement |
|------|-------------|
| **Android Version** | Android 7.0 ~ Android 16 (API 24 ~ 36) |
| **Root Access** | Required (for writing the sentinel file) |
| **Xposed Framework** | LSPosed / LSPosed_mod (recommended) |
| **Architecture** | arm64-v8a, armeabi-v7a, x86, x86_64 |
| **Storage** | ~6MB |

> **Note**: This module is tested on LSPosed only. Other frameworks like EdXposed may have compatibility issues.

### 📦 Installation

1. Download the latest APK from the [Releases](https://github.com/GJR787878/DeviceResetSpoofer/releases) page
2. Install the APK
3. Open **LSPosed Manager** → **Modules** → Find **DeviceResetSpoofer** → Enable the module
4. Tap the module → **Scope** → Check target applications
5. **Reboot your phone** (required, otherwise the module won't work)

### 🚀 Usage

1. Open the **DeviceResetSpoofer** app
2. Tap the **「Run Log」** button to enter the config interface
3. **Check** target applications in the list
4. (Optional) Adjust spoofing toggles at the bottom
5. Go to **System Settings** → **Apps** → Target app → **Storage** → **Clear data**
6. Reopen the target app to get a brand-new device identity

> **Tip**: You can also manually reset identity from the config app's top-right menu without clearing data.

### 🔧 How It Works

The module places a hidden sentinel file `.identity_sentinel` in the target app's private directory. Clearing app data deletes the entire private directory, including the sentinel file. On the next launch, the module detects the missing sentinel and generates a new device identity, writing a new sentinel file.

### 🎭 Spoofed Identifiers

- Android ID (SSAID)
- Advertising ID (AAID) / AppSet ID
- IMEI / MEID / IMSI / ICCID
- Serial number / MAC address
- GSF ID
- Device info: Brand, Model, Manufacturer, Build fingerprint
- Carrier info: Code, Name, Country

### ❓ FAQ

**Q: Identity didn't change after clearing data?**
A: Check the following:
1. Is the target app checked in LSPosed scope?
2. Is the target app checked in the module config interface?
3. Did you reboot your phone?
4. Did you fully kill the process before reopening after clearing data?

**Q: App crashes when launched from LSPosed's quick-launch button?**
A: This is caused by a conflict between LSPosed's quick-launch feature and hook injection timing.
Solution: Don't use LSPosed's launch button — open the app directly from the desktop icon.
If it crashes on first launch, clear the app data once and then open it.

**Q: Packed/protected apps don't work?**
A: In LSPosed scope settings, enable "Exclude resource hooks" for that app.
Some hardened apps may require additional handling.

**Q: How to manually reset identity (without clearing data)?**
A: In the module config interface, tap the top-right menu → Manual identity reset → Select the app.
The app will get a new identity on its next launch.

**Q: Identity changes on every launch?**
A: Normally, identity only changes after clearing data.
If it changes on every launch, the sentinel file write failed (possibly a permission issue). Check if the app has storage permission, or try reinstalling the module.

### ⚠️ Warnings

- For **personal privacy protection and technical testing only**
- Some apps detect Xposed/Root traces, **account ban risk exists**
- For packed/protected apps, enable "Exclude resource hooks" in LSPosed scope settings
- Apps reading system properties directly at the native layer cannot be intercepted by Java hooks
- Test on non-critical apps first
- Troubleshooting: LSPosed → Logs → Search "DeviceReset"

### 📄 License

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

Модуль LSPosed, который **автоматически генерирует совершенно новую идентичность устройства после очистки данных приложения** для выбранных приложений.

### ✨ Возможности

- **Автоматический сброс идентичности**: автоматическая генерация новой идентичности устройства после очистки данных приложения
- **Многомерное подделывание**: Android ID, рекламный ID, IMEI/MEID, модель устройства, MAC-адрес, GSF ID, информация об операторе
- **Двуязычный интерфейс**: переключение между китайским и английским одним нажатием
- **Ручной сброс**: ручной сброс идентичности для любого приложения в любое время
- **Независимые переключатели**: каждый элемент хука можно включать/отключать независимо
- **Детекция по сторожевому файлу**: определение очистки данных через сторожевой файл в приватном каталоге

### 📋 Требования

| Пункт | Требование |
|------|-------------|
| **Версия Android** | Android 7.0 ~ Android 16 (API 24 ~ 36) |
| **Права Root** | Обязательны (для записи сторожевого файла) |
| **Фреймворк Xposed** | LSPosed / LSPosed_mod (рекомендуется) |
| **Архитектура** | arm64-v8a, armeabi-v7a, x86, x86_64 |
| **Хранилище** | ~6 МБ |

> **Примечание**: Этот модуль протестирован только на LSPosed. Другие фреймворки, такие как EdXposed, могут иметь проблемы совместимости.

### 📦 Установка

1. Скачайте последний APK со страницы [Releases](https://github.com/GJR787878/DeviceResetSpoofer/releases)
2. Установите APK
3. Откройте **LSPosed Manager** → **Modules** → Найдите **DeviceResetSpoofer** → Включите модуль
4. Нажмите на модуль → **Scope** → Отметьте целевые приложения
5. **Перезагрузите телефон** (обязательно, иначе модуль не заработает)

### 🚀 Использование

1. Откройте приложение **DeviceResetSpoofer**
2. Нажмите кнопку **「Run Log」**, чтобы войти в интерфейс конфигурации
3. **Отметьте** целевые приложения в списке
4. (Необязательно) Настройте переключатели подделывания внизу
5. Перейдите в **System Settings** → **Apps** → Целевое приложение → **Storage** → **Clear data**
6. Снова откройте целевое приложение, чтобы получить совершенно новую идентичность устройства

> **Совет**: Вы также можете вручную сбросить идентичность из меню в правом верхнем углу приложения конфигурации без очистки данных.

### 🔧 Как это работает

Модуль размещает скрытый сторожевой файл `.identity_sentinel` в приватном каталоге целевого приложения. Очистка данных приложения удаляет весь приватный каталог, включая сторожевой файл. При следующем запуске модуль обнаруживает отсутствие сторожевого файла и генерирует новую идентичность устройства, записывая новый сторожевой файл.

### 🎭 Подделываемые идентификаторы

- Android ID (SSAID)
- Рекламный ID (AAID) / AppSet ID
- IMEI / MEID / IMSI / ICCID
- Серийный номер / MAC-адрес
- GSF ID
- Информация об устройстве: бренд, модель, производитель, отпечаток Build
- Информация об операторе: код, название, страна

### ❓ Часто задаваемые вопросы

**Q: Идентичность не изменилась после очистки данных?**
A: Проверьте следующее:
1. Отмечено ли целевое приложение в области действия LSPosed?
2. Отмечено ли целевое приложение в интерфейсе конфигурации модуля?
3. Перезагружали ли вы телефон?
4. Полностью ли вы завершили процесс перед повторным открытием после очистки данных?

**Q: Приложение вылетает при запуске через кнопку быстрого запуска LSPosed?**
A: Это вызвано конфликтом между функцией быстрого запуска LSPosed и временем инъекции хука.
Решение: не используйте кнопку запуска LSPosed — открывайте приложение напрямую с значка на рабочем столе.
Если при первом запуске происходит вылет, один раз очистите данные приложения, а затем откройте его.

**Q: Защищённые/упакованные приложения не работают?**
A: В настройках области действия LSPosed включите «Exclude resource hooks» для этого приложения.
Некоторые защищённые приложения могут потребовать дополнительной обработки.

**Q: Как вручную сбросить идентичность (без очистки данных)?**
A: В интерфейсе конфигурации модуля нажмите меню в правом верхнем углу → Ручной сброс идентичности → Выберите приложение.
Приложение получит новую идентичность при следующем запуске.

**Q: Идентичность меняется при каждом запуске?**
A: Обычно идентичность меняется только после очистки данных.
Если она меняется при каждом запуске, запись сторожевого файла не удалась (возможно, проблема с правами). Проверьте, есть ли у приложения права на хранилище, или попробуйте переустановить модуль.

### ⚠️ Предупреждения

- Только для **защиты личной конфиденциальности и технического тестирования**
- Некоторые приложения обнаруживают следы Xposed/Root, **существует риск блокировки аккаунта**
- Для защищённых/упакованных приложений включите «Exclude resource hooks» в настройках области действия LSPosed
- Приложения, читающие системные свойства напрямую на нативном уровне, не могут быть перехвачены Java-хуками
- Сначала тестируйте на некритичных приложениях
- Устранение неполадок: LSPosed → Logs → Поиск «DeviceReset»

### 📄 Лицензия

[MIT License](LICENSE)

---

## ⭐ Support

If this project helps you, please give it a Star ⭐

For issues or suggestions, please submit an [Issue](https://github.com/GJR787878/DeviceResetSpoofer/issues).
