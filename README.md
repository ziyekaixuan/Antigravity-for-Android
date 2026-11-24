# OwnAgy

**把 Antigravity 装进口袋，随时随地开工。**

Antigravity Android 客户端，搭载官方核心 2.34.0，全界面简体中文。

![GitHub Release](https://img.shields.io/github/v/release/ziyekaixuan/Antigravity-for-Android?display_name=tag&style=flat-square&color=20BFD0) ![GitHub Stars](https://img.shields.io/github/stars/ziyekaixuan/Antigravity-for-Android?style=flat-square&color=526D9A)

[GitHub 仓库](https://github.com/ziyekaixuan/Antigravity-for-Android) · [下载 Releases](https://github.com/ziyekaixuan/Antigravity-for-Android/releases)

---

## 项目简介

**OwnAgy** 是 Antigravity Android 客户端，搭载官方核心 2.34.0，把完整的 AI 编程环境装进手机：对话、子代理、终端、文件操作、代码执行、图片生成，在 Android 上全部可用。包名 `com.qin.agy`，可与原版共存安装，互不影响。

### 项目入口

| 资源 | 地址 |
| :--- | :--- |
| GitHub 仓库 | [ziyekaixuan/Antigravity-for-Android](https://github.com/ziyekaixuan/Antigravity-for-Android) |
| Releases 下载 | [GitHub Releases](https://github.com/ziyekaixuan/Antigravity-for-Android/releases) |
| 问题反馈 | [GitHub Issues](https://github.com/ziyekaixuan/Antigravity-for-Android/issues) |

## 核心功能展示

| 功能 | 说明 |
| :--- | :--- |
| **全界面简体中文** | 运行时汉化层覆盖全部菜单与设置；代码、终端输出、文件名、模型名保持原文。 |
| **灵动岛实时通知** | 直接读取核心的会话状态驱动通知，任务开始、进行、结束与真实状态一致，支持 Android 16 实时活动样式；子任务、后台任务运行期间不会提前熄火。 |
| **后台保活** | 任务运行期间持有网络锁，核心心跳监测每 5 秒对账；连接中断会如实提示，而不是假装运行。 |
| **专属端口段** | 核心与网关使用独立端口，与原版及其他分支同时运行不冲突。 |
| **旧版数据自动导入** | 首次启动时一次性导入旧版的整个 `.gemini` 数据（会话、自动化、自定义规则）。 |
| **顺手的细节** | Desktop Mode 开关、语音输入、诊断日志一键导出（长按图标 → 导出诊断日志）。 |

## 下载与安装

请优先通过 **[GitHub Releases 发布页](https://github.com/ziyekaixuan/Antigravity-for-Android/releases)** 下载。发布页会列出当前版本说明、安装包和校验文件。

当前发布提供两个版本（均为 arm64，适配大多数现代 Android 手机）：

| 版本 | 文件 | 说明 |
| :--- | :--- | :--- |
| **绕区版** | `OwnAgy-<版本>-region-bypass.apk` | 一般选这个 |
| **标准版** | `OwnAgy-<版本>-standard.apk` | 保留官方全部校验 |
| 校验和 | `SHA256SUMS.txt` | 核对下载文件完整性 |

### 绕区版和标准版有什么区别

两个版本的功能、界面、核心版本**完全一致**，差别只有一处：登录时官方对账号的**地区 / 使用资格校验**。

| | 绕区版（region-bypass） | 标准版（standard） |
| :--- | :--- | :--- |
| 账号资格校验 | **跳过**官方的地区 / 资格校验环节 | 完整保留官方校验 |
| 适合谁 | 登录时被提示地区不支持、无使用资格的账号 | 账号本身符合官方资格要求的用户 |
| 其他功能 | 与标准版完全相同 | 与绕区版完全相同 |
| 签名 | 相同签名，可与标准版互相覆盖安装 | 相同签名，可与绕区版互相覆盖安装 |

简单说：**不确定选哪个就选绕区版**；用标准版登录被资格校验拦下来，再换绕区版即可，数据不受影响。

### 安装步骤

1. 打开 [Releases 最新发布页](https://github.com/ziyekaixuan/Antigravity-for-Android/releases/latest)。
2. 阅读版本说明，下载 APK。
3. 按 Android 系统提示完成安装；如系统要求，允许当前安装来源安装应用。
4. 已装旧版本可直接覆盖安装，数据不丢。

## 使用与问题反馈

如遇到可复现的异常或功能问题，请通过 [GitHub Issues](https://github.com/ziyekaixuan/Antigravity-for-Android/issues) 提交。建议附上：

- OwnAgy 版本号。
- Android 版本、设备型号。
- 具体复现步骤、预期结果、实际结果。
- 必要时附诊断日志（长按图标 → 导出诊断日志）。

**请勿在公开 Issue 中粘贴账号密码、访问令牌、私钥或其他敏感信息。**

## 安全与免责声明

1. **下载来源：** 请优先从本仓库的 [GitHub Releases](https://github.com/ziyekaixuan/Antigravity-for-Android/releases) 获取安装包，并核对发布页提供的 SHA-256 校验信息。不要安装来源不明的二次打包版本。
2. **网络与账号风险：** 连接速度、服务可用性及第三方平台账号状态会受到设备、网络、服务端策略、地区规则和账号状态等因素影响。
3. **合规使用：** 用户应遵守所在地区适用法律法规、目标网站/平台服务条款及第三方服务规则，并自行承担配置、连接和交易行为所产生的责任。

## 构建（开发者）

本地构建需要 JDK 17 与 Android build-tools 在 PATH 中：

```bash
bash build.sh                  # 标准版 → dist/antigravity.apk
bash build.sh --region-bypass  # 绕区版 → dist/antigravity-region-bypass.apk
```

也可以用 GitHub Actions 自动打包（`.github/workflows/build.yml`）：在 Actions 页手动运行，或推送 `v*` 标签，成品会签名后发布到 Releases。发布签名钥匙存放在仓库的 Secrets 中（`KS_B64_1`~`KS_B64_8` 分片、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`），不进入代码仓库。

版本号位于 `config/app.env`（`APP_VERSION_NAME` / `APP_VERSION_CODE`），发版时递增。

---

**OwnAgy · 把 Antigravity 装进口袋。**

如果 OwnAgy 对你有帮助，欢迎点亮 Star、提交反馈。
