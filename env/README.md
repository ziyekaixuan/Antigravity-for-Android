# Antigravity 环境架构与审计（env/）

> [!NOTE]
> 本文档是 Antigravity（AGY v2）中根目录空间 `env/` 的官方技术规范与审计报告。
> 它描述了 rootfs 的组成、`pkg` 包管理器的原理、Android/Termux 路径动态重写系统、ARMv8.1-A+（LSE）兼容机制，以及发现的补丁、瓶颈和优化建议。

---

## 1. Rootfs 的组成与结构（`env/`）

环境 `env/` 是一个轻量、自包含的基于 ARM64（`aarch64`）的 Bionic 用户空间环境，设计为在 Android 应用沙箱内运行，无需超级用户权限（`root`）或带系统调用拦截的模拟器（`proot` / `ptrace`）。

### 目录地图

```
env/
├── README.md               # 本技术文档与审计报告
├── README                  # 旧格式的基础说明
├── build.sh                # 构建并将资源打包到 dist/env 的主脚本
├── bootstrap/              # 基础 rootfs 归档与修改工具
│   ├── bootstrap-aarch64.zip # Termux bootstrap 的原始二进制 zip 归档
│   ├── bootstrap.tar.gz      # 优化后的 tar.gz（符号链接、已精简文档）
│   └── patch-prefix.py       # (Legacy) 用于 dpkg 的 ELF 二进制补丁脚本
├── packages/               # 嵌入 bootstrap 的附加包
│   └── git.deb             # 预先准备的 Git 包，用于离线使用
├── overlay/                # 覆盖到 $PREFIX 之上的文件（etc、bin、lib）
│   ├── bin/                # AGY 系统脚本与工具
│   │   ├── agy-daemon      # 后台自主任务调度器与计划器
│   │   ├── agy-fix-paths   # Shebang 与路径动态规范化工具
│   │   ├── agy-notify      # Android 系统通知管理工具
│   │   ├── agy-upgrade     # 安全更新、备份与迁移管理器
│   │   ├── rish            # Shizuku 客户端，用于特权 Shell（uid=2000）
│   │   └── cs              # language server 的占位程序
│   ├── etc/                # 系统配置
│   │   ├── antigravity/    # AGENTS.md（智能体全局规则）
│   │   ├── pkg/repo.conf   # pkg 仓库配置（Termux 镜像）
│   │   ├── profile.d/      # Shell 初始化脚本（00-env.sh）
│   │   ├── tls/            # 证书 cert.pem 与 openssl.cnf 配置
│   │   ├── hosts           # localhost 名称解析基础表
│   │   ├── motd            # 终端欢迎消息
│   │   ├── profile         # bash 会话根配置文件
│   │   └── resolv.conf     # 公共 DNS 服务器（1.1.1.1、8.8.8.8）
│   └── lib/                # 系统共享库
│       ├── libtermux-exec.so # 用于路径转换的 execve 拦截库
│       └── daemon/         # 实现 agy-daemon 的 Python 模块
├── pkg/                    # 自主包管理器 pkg（Bash + AWK）
│   ├── pkg                 # 可执行命令行界面（CLI）
│   └── lib/                # pkg 的模块化函数库
│       ├── util.sh         # 工具、环境变量、rewrite_prefix
│       ├── deb.sh          # 不用 ar 解析 ar/.deb 归档并解包
│       ├── db.sh           # 已安装包的本地数据库（.meta、.files）
│       ├── fetch.sh        # 带 sha256 校验和进度的 HTTP(S) 下载
│       ├── index.sh        # 处理 Packages 索引并生成 TSV
│       ├── index.awk       # 将 debian stanza 解析为 TSV 格式的 AWK 解析器
│       ├── resolver.sh     # 递归依赖图与循环校验
│       ├── version.sh      # Debian 版本比较包装器
│       └── vercmp.awk      # 纯 AWK 实现的 dpkg vercmp 语义
├── liblse_compat/          # ARMv8.1-A+ 指令（LSE、SIMD）模拟模块
│   ├── liblse_compat.c     # SIGILL 拦截与软件模拟的源代码
│   ├── liblse_compat.so    # 编译后的共享库
│   └── build.sh            # 构建 liblse_compat.so 的脚本
├── repo/                   # 仓库本地缓存目录（默认为空）
├── scripts/                # 维护工程脚本
│   ├── pack_bootstrap.py   # zip -> tar.gz 转换器，清理 doc/man 并注入 extra-deb
│   └── make_manifest.py    # 生成确定性 manifest.json 以校验完整性的生成器
├── dist/                   # 用于包含在 APK 中的资源构建结果
│   └── env/                # assets/env 的镜像（bootstrap、overlay、pkg、manifest.json）
└── tests/                  # 综合测试集
    ├── fixtures/           # 测试夹具（hello.deb、Packages）
    ├── selftest            # pkg 的自主测试（vercmp、db、resolver、deb）
    ├── invariant           # 资源与符号链接 1:1 完整性检查
    └── e3                  # 在临时目录中部署 rootfs 并启动的 E2E 测试

```

### 在 Android 沙箱中的内存与存放位置

应用启动时，Android 类 `io.github.ziyekaixuan.antigravity.install.EnvInstaller` 将资源解包到应用隔离的文件空间：
- **`$HOME`**：`/data/user/<id>/<package_id>/files`（旧版 Android 上为 `/data/data/<package_id>/files`）。
- **`$PREFIX`**：`$HOME/usr` — Bionic rootfs 的根。
- **`$PREFIX/bin`**：可执行文件（`bash`、`sh`、`git`、`pkg`、`agy-daemon` 等）。
- **`$PREFIX/lib`**：Bionic 共享库（`libtermux-exec.so`）。
- **`$PREFIX/etc`**：配置文件，包括 TLS 证书（`etc/tls/cert.pem`）。
- **`$PREFIX/libexec/pkg`**：`pkg` 管理器的系统模块。
- **`$PREFIX/var/lib/pkg`**：已安装包数据库（`installed/`）、`.deb` 缓存（`cache/`）和索引（`index/`）。

---

## 2. 包管理器 `pkg` 的逻辑与架构

包管理器 `pkg` 的设计目标是彻底消除在终端工作阶段对重型工具 `dpkg`、`apt`、`ar` 和 `python` 的依赖。它完全用 POSIX 兼容的 `Bash` 和 `AWK` 编写。

### 支持的命令
| 命令 | 说明 |
| :--- | :--- |
| `pkg update` | 从 Termux 镜像下载 `Packages.gz`，解包为 `repo.N.Packages` 并编译为优化的 TSV 索引 `index.awk`。 |
| `pkg install <pkg...>` | 解析依赖图（DFS）、下载 `.deb`、校验 SHA256、提取、动态重写路径（`rewrite_prefix`）、注册到数据库并生成 `runtime/bin` 桥。 |
| `pkg upgrade` | 将已安装包的版本与仓库比较（`deb_vercmp`），更新过时的包。 |
| `pkg remove <pkg...>` | 读取 `.files`，安全删除包的文件和空父目录，删除 `.meta`。 |
| `pkg list [regex]` | 从 TSV 索引输出所有可用包，可按过滤条件筛选。 |
| `pkg installed` | 列出本地已安装的包及其版本。 |
| `pkg info <pkg>` | 包的详细元数据（大小、架构、依赖、哈希、提供者）。 |
| `pkg search <query>` | 按子串/正则表达式搜索包。 |
| `pkg why <file>` | 通过反向搜索 `.files` 确定指定文件所属的包。 |
| `pkg clean` | 清理 `$PKG_CACHE` 中下载的 `.deb` 归档。 |
| `pkg selftest` | 内置离线测试，检查 Shell、证书和仓库配置的完整性。 |

### 依赖解析机制（`resolver.sh`）
依赖解析基于拓扑排序和深度优先搜索（DFS）：
1. **防止循环：** 维护状态目录 `$work/doing/<pkg>` 和 `$work/done/<pkg>`。如果算法再次进入标记为 `doing` 的顶点，则记录循环依赖并安全中断操作，不做部分安装。
2. **虚拟包（`Provides`）：** 如果按名称找不到包，函数 `index_provider` 会扫描仓库索引中 `Provides` 列。
3. **版本约束：** `(>= 1.0)`、`(<< 2.0)`、`(= 1.2)` 形式的条件会被解析，并通过 AWK 实现的 `deb_vercmp` 校验。

### 数据库格式（`db.sh`）
存储逻辑极简且容错：
- `$PKG_DB/<package>.meta`：`key=value` 格式的关键字段（`name`、`version`、`arch`）。通过从临时文件 `.tmp` 的原子 `mv` 写入。
- `$PKG_DB/<package>.files`：属于该包的文件的相对路径排序列表。
- 与 dpkg 同步（`db_sync_dpkg`）：如果存在 `$PREFIX/var/lib/dpkg/status`（来自基础 bootstrap），`pkg` 数据库会自动导入预装包而不重复。

---

## 3. Termux/Android 路径动态转换

由于 Termux 包编译时硬编码了路径 `/data/data/com.termux/files/usr`，而 AGY 应用运行在任意上下文中（例如 `/data/user/0/io.github.ziyekaixuan.antigravity/files/usr` 或 `/data/user/10/io.github.ziyekaixuan.antigravity.dev/files/usr`），因此采用三级适配系统：

### 重写层级
```
┌─────────────────────────────────────────────────────────────┐
│ 1. 安装时层级 (Install-Time / Java)                         │
│    TermuxShebangRewriter、TermuxPrefixRewriter、            │
│    TermuxSymlinkFixer（位于 EnvInstaller.java）             │
│    -> 解包、路径规范化与 chmod 权限设置                     │
├─────────────────────────────────────────────────────────────┤
│ 2. 运行时层级 (Runtime / LD_PRELOAD)                        │
│    libtermux-exec.so 拦截 execve / execv                    │
│    -> 将 /bin/sh 调用和 Termux 路径重定向到 $PREFIX         │
├─────────────────────────────────────────────────────────────┤
│ 3. 动态包层级 (Post-Install / CLI)                          │
│    - pkg/lib/util.sh 中的 rewrite_prefix                    │
│    - 用于第三方 pip / npm 脚本的 agy-fix-paths              │
└─────────────────────────────────────────────────────────────┘
```

1. **`TermuxShebangRewriter` 与 `TermuxPrefixRewriter`（Java）：** 在应用安装或更新时扫描 `bin`、`libexec`、`etc`、`share`、`var` 目录。所有符合正则表达式 `/data/(?:data|user/[0-9]+)/[^/]+/files/usr` 的文本出现处都被替换为当前 `$PREFIX`。
2. **`TermuxSymlinkFixer`（Java）：** 指向不存在的 `com.termux` 前缀的绝对符号链接会被动态重建，指向本地 `$PREFIX`。
3. **`libtermux-exec.so`（C / Bionic Hook）：** 该库通过 `LD_PRELOAD` 注入所有 Shell 子进程（经 `00-env.sh`）。它拦截 `execve`、`execvp`、`fexecve` 函数族，将 `/bin/sh` 调用和过时的 Termux 路径替换为当前 `$PREFIX/bin` 中的可执行文件。
4. **`agy-fix-paths` 与 `pkg rewrite_prefix`（Bash）：** 通过 `pkg` 安装包或通过 `pip install` / `npm install -g` 安装全局 CLI 工具时，脚本中包含解释器路径。`agy-fix-paths` 仅安全扫描文本文件（跳过二进制文件和 ELF），并以原子方式将路径替换为当前 `$PREFIX`。

---

## 4. ARMv8.1-A+ 兼容性与 `liblse_compat` 模块

### 移动处理器上的 LSE 问题
现代二进制文件（包括 Go 运行时、Node.js、编译器和核心工具）按现代架构 `ARMv8.1-A+` 编译，带硬件原子指令 LSE（Large System Extensions）标志。
然而，一些流行的移动 SoC（例如 **Snapdragon 685**，Cortex-A53 核心 / Redmi Note 12）硬件上仅支持基础指令集 **ARMv8.0-A**。在这些核心上执行 LSE 指令时，处理器会产生 **`SIGILL`（非法指令，信号 4）** 异常，导致应用崩溃并报错：
`FATAL ERROR: This binary was compiled with lse enabled`。

### `liblse_compat` 的工作原理
`liblse_compat.so` 库在 Linux 内核层面透明地解决这个问题，无需修改二进制文件：
1. **安装内核处理器：** 最高优先级的构造函数 `__attribute__((constructor(101)))` 直接通过汇编（`svc #0`）调用内核系统调用 `rt_sigaction(134)`，绕过 libc 包装器。这保证处理器在其他库启动前就已安装。
2. **防覆盖保护（`sigaction` hook）：** `liblse_compat` 导出自己的 `sigaction` 函数，拦截客户环境（例如 Go 运行时或 JVM）覆盖 `SIGILL` 处理器的尝试。用户处理器保存在 `old_sigill_act` 中，仅在崩溃指令不属于可模拟指令时才调用。
3. **软件解码器与模拟器：**
   - **FEAT_LSE (ARMv8.1-A)：** 模拟原子内存操作：`LDADD`、`LDCLR`、`LDEOR`、`LDSET`、`SWP`，有符号和无符号最大/最小值（`LDSMAX`、`LDSMIN`、`LDUMAX`、`LDUMIN`），以及原子比较并交换（`CAS`、`CASA`、`CASL`、`CASAL`、`CASP`、`CASPA`、`CASPL`、`CASPAL`），使用 GCC/Clang 标准内置原子操作（`__atomic_fetch_add`、`__atomic_compare_exchange_n`）。
   - **FEAT_LRCPC / LRCPC2 (ARMv8.3-A / 8.4-A)：** 模拟 Load-Acquire RCpc 操作：`LDAPR`、`LDAPRB`、`LDAPRH`、`LDAPUR`、`STLUR`，带内存屏障 `__atomic_thread_fence`。
   - **FEAT_DotProd (ARMv8.2-A / 8.4-A)：** 模拟标量积的向量与元素指令 `SDOT`、`UDOT`，针对 SIMD 寄存器 `V0`–`V31`（上下文结构 `fpsimd_context`）。
   - **FEAT_RDM (ARMv8.1-A)：** 模拟带舍入和加倍的向量操作 `SQRDMLAH`、`SQRDMLSH`。
   - **FEAT_FCMA (ARMv8.3-A)：** 模拟复数向量加法与乘积累加 `FCADD`、`FCMLA`（针对 `_Float16`、`float`、`double` 类型）。
4. **PC 偏移：** 模拟成功后，程序计数器前进 4 字节（`uc->uc_mcontext.pc += 4`），控制权立即返回被中断的线程。

### 两个世界的划分：Bionic 与 Glibc（关键方面）
Antigravity 中共存两种不同的执行环境：
1. **核心语言服务器 / 核心守护进程：** 在 `runtime/` 下由 **Glibc** 管理运行。
2. **终端、bash、`pkg` 工具：** 在 `$PREFIX/` 下由 **Android Bionic libc** 管理运行。

> [!WARNING]
> 当前构建的 `env/liblse_compat/liblse_compat.so` 是针对 `libc.so.6` 和 `libdl.so.2`（GNU Glibc）编译的。
> 因此它**绝不能**加入 Bionic 终端进程的全局 `LD_PRELOAD`。如果 Bionic 链接器（`linker64`）遇到需要 `libc.so.6` 的库，任何进程（`/system/bin/sh`、`bash`、`ls`）的启动都会立即因链接错误而异常终止：
> `CANNOT LINK EXECUTABLE: library "libc.so.6" not found`。
>
> 对于 Bionic 环境，`00-env.sh` 代码会安全地检查 `$PREFIX/lib/liblse_compat.so` 是否存在——而那里并没有这个文件。`liblse_compat.so` 仅由 Glibc 加载器通过 `RuntimeManager.java` 中的 `--preload` 参数加载。

---

## 5. 审计结果：发现的补丁、开销与错误

在深入分析 `env/` 源代码的过程中，记录了以下意见：

### 1. `pkg/lib/deb.sh` 中的灾难性开销（I/O 瓶颈）
- **问题：** `ar_members` 和 `ar_member` 中的 `ar` 格式解析是通过循环调用外部进程 `dd` 实现的，块大小 `bs=1`、`count=60`，用于读取每个文件的头：
  ```bash
  hdr="$(dd if="$file" bs=1 skip="$off" count=60 2>/dev/null)"
  ```
  在解包 `install_one` 时，函数 `deb_member_name` 被多次调用，每次都通过数十次 `dd` fork 重新从磁盘扫描整个 deb 归档。
- **多次解包：** 在一次包安装过程中，`data.tar` 被解包/扫描 4 次：
  1) `deb_field` 将 `control.tar` 解包两次；
  2) `deb_validate_data` 解包 `data.tar` 以校验路径；
  3) `deb_extract_data` 将 `data.tar` 解包到文件系统；
  4) `deb_files` 再次解包 `data.tar` 以获取文件列表。
- **损失评估：** 对于大包（10 MB 以上），这会造成多达 3–7 秒的额外等待时间和数百次 `fork()/execve()` 调用。

### 2. `overlay/bin/` 脚本中的硬编码路径
- **`env/overlay/bin/agy-notify` [已修复 - 第一轮]：**
  此前包含开发者绝对路径 `SRC_LOGO="/storage/emulated/0/Documents/antigravity_release/docs/assets/logo.png"`。
  已移除本地路径；添加了按 `$AGY_LOGO` 和标准资源路径（`$PREFIX/share/antigravity/logo.png`、`$PREFIX/share/pixmaps/antigravity.png`）搜索的逻辑，文件缺失时安全回退到 Android 系统图标。
- **`env/overlay/bin/agy-upgrade` [已修复 - 第一轮]：**
  修复了 `HOME` 路径的计算：
  此前错误地使用 `export HOME="${HOME:-/data/user/0/$(basename "$(dirname "$PREFIX")")/files}"`，导致形如 `/data/user/0/files/files` 的路径。
  已修正为标准且正确的形式：`export HOME="${HOME:-$(dirname "$PREFIX")}"`。
- **Shebang 中的 `io.github.ziyekaixuan.antigravity`：**
  在文件 `env/pkg/pkg`、`env/overlay/bin/agy-fix-paths`、`env/overlay/bin/agy-notify`、`env/overlay/bin/agy-upgrade` 中，首行 Shebang 为：
  `#!/data/data/io.github.ziyekaixuan.antigravity/files/usr/bin/bash` 或 `#!/data/user/0/io.github.ziyekaixuan.antigravity/files/usr/bin/bash`。
  `TermuxShebangRewriter` 会在安装 APK 时重写它们。

### 3. `pkg/pkg` 中桥生成的开销 [已修复 - 第一轮]
- **问题：** 此前安装每个包时，`pkg` 脚本执行循环 `for bin_file in "$PREFIX/bin"/*; do`，重新扫描 `$PREFIX/bin` 中的所有二进制文件。
- **解决方案：** 循环已优化：现在仅针对当前安装包中的二进制文件（`$pkg_files` 中带 `bin/` 前缀的文件）在 `$PREFIX/../runtime/bin` 中生成桥，不再重复扫描 `$PREFIX/bin/*`。

### 4. `env/build.sh` 中 `liblse_compat` 构建链断裂
- 脚本 `env/build.sh` 构建 `bootstrap.tar.gz` 并将 `pkg` 和 `overlay` 目录复制到 `dist/env`。
- 但 `liblse_compat` 由单独的脚本 `env/liblse_compat/build.sh` 构建，未纳入 `env/build.sh all` 流程，也未复制到 `dist/`。

### 5. 路径规范化代码重复
将 Termux 路径替换为当前 `$PREFIX` 的逻辑在 4 个独立位置重复：
1. `io.github.ziyekaixuan.antigravity.install.TermuxPrefixRewriter` / `TermuxShebangRewriter`（Java）；
2. `env/pkg/lib/util.sh` -> 函数 `rewrite_prefix`（Bash/sed）；
3. `env/overlay/bin/agy-fix-paths`（Bash/sed）；
4. `env/bootstrap/patch-prefix.py`（Python）。

---

## 6. 修复与优化建议

1. **优化 `deb.sh` 解包器：**
   - 一次遍历保存 ar 成员偏移，避免多次调用 `dd`。
   - 将路径校验（`deb_validate_data`）、数据提取（`deb_extract_data`）和文件列表生成（`deb_files`）合并为**唯一一次 `tar -x -v` 解包遍历**，直接从 tar 的 `stdout`/`stderr` 读取已解包文件列表。这将使包安装速度提升 3–4 倍。
2. **修复路径：**
   - [x] 将 `env/overlay/bin/agy-upgrade` 中的 `$HOME` 计算修复为 `HOME="${HOME:-$(dirname "$PREFIX")}"`（第一轮已完成）。
   - [x] 将 `env/overlay/bin/agy-notify` 中的硬编码 Logo 路径替换为通过应用资源或 `$PREFIX/share/antigravity/logo.png` 的相对路径，文件缺失时回退到系统图标（第一轮已完成）。
   - 打包前为仓库中所有脚本使用可移植 Shebang `#!/usr/bin/env bash` 或 `#!/bin/sh`。
3. **优化 `pkg` 中的桥生成器：**
   - [x] 仅为当前安装包添加的文件（来自 `$pkg_files` 列表）生成 runtime 桥，而不是为 `$PREFIX/bin` 目录中的所有文件生成（第一轮已完成）。
4. **集成 LSE 组件：**
   - 在 `env/build.sh` 中添加 `liblse_compat` 目标。
   - 如需在 Bionic 中使用 LSE 模拟，考虑通过 Android NDK 显式构建不依赖 `libc.so.6` 的 `liblse_compat_bionic.so`。

---

## 7. 构建与测试说明

### 构建环境
```bash
# 1. 从 bootstrap-aarch64.zip 构建优化后的 bootstrap.tar.gz
./env/build.sh bootstrap

# 2. 将所有环境层打包到 dist/env（可直接包含进 APK）
./env/build.sh assets

# 3. 重新构建所有组件
./env/build.sh all
```

### 运行测试
```bash
# 1. pkg 核心的自主模块测试（vercmp、resolver、db、deb）
bash env/tests/selftest

# 2. 检查资源树的不变量与完整性
bash env/tests/invariant

# 3. 部署前缀的完整集成 E2E 测试
bash env/tests/e3
```
