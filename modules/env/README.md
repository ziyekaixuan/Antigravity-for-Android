# Antigravity v2 - 环境、诊断与系统网关子系统 (`modules/env`)

> **模块 `env`** — 为 Google Antigravity v2 提供系统环境管理、Android/Linux 路径动态解析、硬件与网络诊断（preflight/port checker）、结构化遥测日志、命令执行审计，以及与 Android System Gateway 的进程间通信的基础子系统。

---

## 1. 架构概览

模块 `modules/env` 消除运行时组件对 Android 文件系统固定路径的依赖，并提供标准化的运行环境：

```
 +-----------------------------------------------------------------------------------+
 |                             SPECIFICATION LAYER                                   |
 |          [spec/paths.json]                     [spec/env_vars.json]               |
 |    (Android 基础路径模板)                 (静态变量、PATH、LD_LIBRARY_PATH)       |
 +--------------------------+---------------------------------+----------------------+
                            |                                 |
                            v                                 v
 +-----------------------------------------------------------------------------------+
 |                              CONTEXT & RESOLVER                                   |
 |         [path_resolver.py]                          [env_builder.py]              |
 |   动态计算 package_id、                   构建进程环境：HOME、PREFIX、PATH、      |
 |   app_root、runtime、prefix、cache        TMPDIR、GODEBUG、GIT_CONFIG_NOSYSTEM    |
 +--------------------------+---------------------------------+----------------------+
                            |                                 |
       +--------------------+----------------+----------------+----------------+
       |                                     |                                 |
       v                                     v                                 v
+--------------------------+  +--------------------------+  +--------------------------+
|  DIAGNOSTICS & PREFLIGHT |  |   LOGGING & CRASH DUMP   |  |     COMMAND RUNNER       |
|      [preflight.py]      |  |         [log.py]         |  |       [runner.py]        |
|  - 架构 (ARM64)          |  |  - ANSI Logger / JSONL   |  |  - 执行 $ cmd            |
|  - 剩余空间 (>250)       |  |   [flight_recorder.py]   |  |  - 指标：duration_ms     |
|  - 写权限 W_OK           |  |  - 内存与磁盘状态转储    |  |  - 审计 commands.jsonl   |
|    [port_checker.py]     |  |                          |  |  - Fallback & list       |
|  - TCP Loopback 测试     |  +--------------------------+  +--------------------------+
+--------------------------+                 |                                 |
                                             v                                 v
 +-----------------------------------------------------------------------------------+
 |                             IPC & SYSTEM GATEWAY                                  |
 |           [bridge/client.py]                           [bridge/commands.py]       |
 |    自动检测端口 gateway.port                        高级类型化                    |
 |    传输：POST http://127.0.0.1:port/gateway         命令：notify、open、wakelock  |
 +-----------------------------------------------------------------------------------+
                                             |
                                             v
 +-----------------------------------------------------------------------------------+
 |                                CLI UTILITIES                                      |
 |          [cli/agy_system.py]                           [cli/agy_logs.py]          |
 |    管理 Android 通知、                               检查核心日志 core.log、      |
 |    Wakelock、Intent URL、剪贴板                      崩溃转储与 commands.jsonl    |
 +-----------------------------------------------------------------------------------+
```

---

## 2. 模块目录结构

```
modules/env/
├── spec/
│   ├── paths.json               # 应用文件结构的 JSON 规范
│   └── env_vars.json            # 基础环境变量的 JSON 规范
├── context/
│   ├── path_resolver.py         # 无硬编码的路径解析器，package_id 检测器
│   └── env_builder.py           # 隔离进程环境的构造器
├── diagnostics/
│   ├── preflight.py             # 架构（ARM64）、权限与内存预检
│   └── port_checker.py          # 本地 TCP 端口可用性检查
├── logging/
│   ├── log.py                   # 轻量彩色 ANSI + JSONL 日志器
│   └── flight_recorder.py       # 崩溃 JSON 快照（crash dump）生成器
├── bridge/
│   ├── client.py                # 带自动 fallback 的 Android Gateway IPC 客户端
│   └── commands.py              # 面向 Android OS 的类型化系统调用
├── cmd/
│   └── runner.py                # 带遥测的命令执行引擎
├── cli/
│   ├── agy_system.py            # Android Gateway CLI 控制器（notify、open、wakelock）
│   └── agy_logs.py              # 日志、命令历史与崩溃的 CLI 检查器
└── tests/
    ├── run_all.sh               # 语法与模块检查主脚本
    ├── test_context.py          # 路径解析器与 env 构建测试
    ├── test_diagnostics.py      # 预检与端口检查器测试
    ├── test_logging.py          # 日志器与 flight recorder 测试
    ├── test_bridge.py           # 网关客户端与命令类型化测试
    └── test_cli.py              # agy_system 与 agy_logs CLI 工具测试
```

---

## 3. 环境与路径规范 (`spec/`)

### 3.1. 文件系统映射 (`spec/paths.json`)
路径模板使用占位符 `{package_name}`（或 `{app_id}`），在运行时动态替换：
```json
{
  "app_root": "/data/user/0/{package_name}/files",
  "prefix": "/data/user/0/{package_name}/files/usr",
  "runtime": "/data/user/0/{package_name}/files/runtime",
  "cache": "/data/user/0/{package_name}/cache",
  "gemini_home": "/data/user/0/{package_name}/files/.gemini",
  "shared_storage": "/storage/emulated/0",
  "default_projects": "/storage/emulated/0/Documents"
}
```

### 3.2. 环境变量规范 (`spec/env_vars.json`)
定义基础变量、`PATH` 搜索顺序和库路径：
```json
{
  "static": {
    "LANG": "C.UTF-8",
    "LC_ALL": "C.UTF-8",
    "GODEBUG": "netdns=go",
    "ANTIGRAVITY_VSCODE_HOST": "1"
  },
  "relative_to_app": {
    "HOME": ".",
    "TMPDIR": "../cache"
  },
  "path_order": [
    "{runtime}/bin",
    "{prefix}/bin",
    "/system/bin"
  ],
  "ld_library_paths": [
    "{runtime}/lib",
    "{prefix}/lib"
  ]
}
```

---

## 4. 上下文与动态路径解析器 (`context/`)

### 4.1. `path_resolver.py`
消除对包名的硬编码绑定（`com.qin.agy` vs `com.qin.agy.dev` vs 自定义构建）：
- `get_application_id() -> str`：按以下优先级确定包标识符：
  1. 环境变量 `AGY_APPLICATION_ID`
  2. 环境变量 `APPLICATION_ID`
  3. 标志 `DEV_MODE == "1"`（返回 `com.qin.agy.dev`）
  4. 默认：`com.qin.agy`
- `load_paths(custom_spec=None, app_id=None) -> Dict[str, str]`：从 `paths.json` 加载并插值路径，并通过 `os.path.abspath(os.path.expanduser(...))` 保证得到绝对路径。
- `resolve_subpath(key: str, rel_path: str, custom_spec=None, app_id=None) -> str`：安全拼接子路径。为防止传入前导斜杠（`/bin/tool`）时重置根目录，会调用 `rel_path.lstrip("/")`。

### 4.2. `env_builder.py`
- `build_process_env(custom_spec_dir=None, extra_env=None, app_id=None) -> Dict[str, str]`：
  为派生进程构建干净、确定性的环境变量字典：
  - `HOME`：绑定到 `paths["app_root"]`
  - `PREFIX`：Linux 前缀 `paths["prefix"]`（`/data/user/0/{package}/files/usr`）
  - `TMPDIR`：缓存目录 `paths["cache"]`
  - `GIT_CONFIG_NOSYSTEM`：值为 `"1"`（屏蔽缺失 Termux 系统 etc 导致的错误）
  - `AGY_APPLICATION_ID`：当前包的标识符
  - `PATH`：由 `{runtime}/bin:{prefix}/bin:/system/bin` 拼接而成
  - `LD_LIBRARY_PATH`：`{runtime}/lib` 与 `{prefix}/lib` 目录（如果目录存在）
  - `extra_env`：额外的用户自定义变量

---

## 5. 系统诊断 (`diagnostics/`)

### 5.1. 预检器 (`diagnostics/preflight.py`)
在部署或启动核心之前检查 Android 主机的就绪状态：
```python
def run_preflight(app_root: Optional[str] = None, min_mb_free: int = 250) -> Dict[str, Any]
```
- **处理器架构**：检查 `platform.machine()`。仅允许 `aarch64` / `arm64`。
- **剩余磁盘空间**：通过 `shutil.disk_usage()` 检查 `app_root` 所在分区的可用空间。默认阈值——不少于 250 MB。
- **写权限**：检查 `os.access(app_root, os.W_OK)`。
- **动态 fallback**：如果未指定 `app_root`，先通过 `path_resolver` 解析，再通过 `$HOME` 解析。

### 5.2. 端口校验器 (`diagnostics/port_checker.py`)
```python
def check_port_available(port: int, host: str = "127.0.0.1") -> Dict[str, Any]
```
- 检查能否在 loopback 接口上绑定 TCP 套接字（`socket.bind`），超时 0.5 秒。
- 返回 `{"port": port, "available": True/False, "error": None/str}`。

---

## 6. 日志记录与崩溃事后分析 (`logging/`)

### 6.1. 结构化日志器 (`logging/log.py`)
```python
def log(level: str, tag: str, message: str, meta: Dict[str, Any] = None, jsonl_file: str = None) -> None
```
- **ANSI 颜色**：`DEBUG`（灰色）、`INFO`（蓝色）、`OK`（绿色）、`WARN`（黄色）、`ERROR`（红色）。
- **`NO_COLOR` 支持**：设置环境变量 `NO_COLOR` 或 `TERM=dumb` 时，自动禁用转义字符。
- **流分离**：`ERROR` 级别日志输出到 `sys.stderr`，其余级别输出到 `sys.stdout`。
- **JSONL 流式输出**：指定 `jsonl_file` 时，以原子方式按 JSON Lines 格式写入记录，带有毫秒级时间戳（`ts`）。相关操作受 `try-except` 块保护。

### 6.2. 崩溃记录器 (`logging/flight_recorder.py`)
```python
def record_crash_snapshot(error_msg: str, dump_path: str, extra_context: Dict[str, Any] = None) -> str
```
- 创建详细的事故事后分析快照：
  - 精确时间（epoch 毫秒与 ISO 8601 UTC）
  - 错误消息与上下文（`extra_context`）
  - 平台数据、架构、Python 版本、PID
  - 目标目录的文件系统状态（`shutil.disk_usage`）
- 将格式化后的 JSON 保存到 `dump_path`。

---

## 7. 系统网关与 IPC 桥接 (`bridge/`)

通过 `CoreServerService`（Android System Gateway）实现 Python 脚本、后台任务与 Android OS 系统服务之间的进程间通信。

### 7.1. IPC 客户端 (`bridge/client.py`)
- `resolve_gateway_url() -> str`：
  1. 检查环境变量 `AGY_GATEWAY_URL`。
  2. 在 `$HOME/logs/gateway.port`、`app_root/logs/gateway.port` 以及 `com.qin.agy.dev` / `com.qin.agy` 的标准目录中查找动态端口文件 `gateway.port`。
  3. 如果文件不存在，则选择默认端口：`DEV_MODE=1` / `com.qin.agy.dev` 时为 `45171`，生产版为 `45170`。
- `send_command(command: str, payload: dict = None, gateway_url: str = None) -> dict`：
  - 向 `/gateway` 端点发送带有 JSON 信封 `{command, payload}` 的 HTTP POST 请求。
  - 自动 failover：主端口不可用时，尝试重连到备用端口（`45170` <-> `45171`）。
  - 无法连接网关时返回 `{ok: False, fallback: True, error: ...}`，不抛出未处理异常。

### 7.2. 类型化命令门面 (`bridge/commands.py`)
- `notify(title, message, progress=None, tag="agy_status", channel="agy_tasks", ongoing=False) -> dict`：向状态栏发送 Android 通知，支持进度条（0-100）与常驻。
- `cancel_notify(tag="agy_status", notification_id=1001) -> dict`：撤销活动通知。
- `open_url(url: str) -> dict`：通过 Android Intent（`ACTION_VIEW`）打开网页链接或 deep link。
- `wakelock(acquire=True, tag="agy_task") -> dict`：持有/释放处理器 `PARTIAL_WAKE_LOCK`。
- `clipboard_set(text: str) -> dict`：复制文本到 Android 系统剪贴板。
- `clipboard_get() -> dict`：从 Android 系统剪贴板读取文本。
- `system_info() -> dict`：查询电池、网络、可用内存与磁盘状态。

---

## 8. 命令审计与执行引擎 (`cmd/runner.py`)

用于透明执行 shell 命令的工具与库，带有扩展的性能分析与审计：

```bash
# 带遥测执行命令：
python3 modules/env/cmd/runner.py run "echo 'Hello Antigravity'"

# 直接调用：
python3 modules/env/cmd/runner.py pkg list

# 查看最近执行的命令：
python3 modules/env/cmd/runner.py list 20
```

- 计算精确的执行时长（毫秒，`duration_ms`）。
- 将每条命令写入应用日志目录（`$HOME/logs/commands.jsonl`）中的 append-only 审计日志 `commands.jsonl`。
- 输出带有返回码、时长和输出前几行的格式化树形结构。

---

## 9. CLI 接口 (`cli/`)

### 9.1. 系统控制器 (`cli/agy_system.py`)

管理 Android 功能的统一终端接口：

```bash
# 1. 系统通知：
python3 modules/env/cli/agy_system.py notify -t "构建完成" -m "APK 已编译成功" --tag "build"
python3 modules/env/cli/agy_system.py notify -t "下载中" --progress 75 --ongoing
python3 modules/env/cli/agy_system.py notify cancel --tag "build"

# 2. 在系统浏览器 / Custom Tabs 中打开 URL：
python3 modules/env/cli/agy_system.py open "https://github.com"

# 3. 后台模式管理（Wakelock）：
python3 modules/env/cli/agy_system.py wakelock on --tag "long_job"
python3 modules/env/cli/agy_system.py wakelock off --tag "long_job"

# 4. Android 主机诊断：
python3 modules/env/cli/agy_system.py info
python3 modules/env/cli/agy_system.py --json info

# 5. 剪贴板：
echo "秘密令牌" | python3 modules/env/cli/agy_system.py clipboard set
python3 modules/env/cli/agy_system.py clipboard get
```

### 9.2. 遥测与日志检查器 (`cli/agy_logs.py`)

分析 Antigravity 核心运行时状态的工具：

```bash
# 1. 查看通用日志的最后几行：
python3 modules/env/cli/agy_logs.py -n 50

# 2. 按子系统与重要级别过滤：
python3 modules/env/cli/agy_logs.py --channel core --level ERROR

# 3. 审计已执行的命令：
python3 modules/env/cli/agy_logs.py --commands -n 25

# 4. 详细解析某条命令（STDOUT、STDERR、时间）：
python3 modules/env/cli/agy_logs.py --cmd-id "cmd_1792097231_1ec8"

# 5. 检查最新的崩溃报告（Crash Report）：
python3 modules/env/cli/agy_logs.py --crash
python3 modules/env/cli/agy_logs.py --crash --json
```

---

## 10. 模块测试 (`tests/`)

测试套件覆盖 `env` 子系统的 100% 组件。

### 运行测试
```bash
bash modules/env/tests/run_all.sh
```

### 测试套件组成
| 测试文件 | 组件 | 验证场景 |
| :--- | :--- | :--- |
| `test_context.py` | `path_resolver`、`env_builder` | 9 个测试：路径解析、带前导斜杠的 subpath、`HOME`、`PREFIX`、`TMPDIR`、`GIT_CONFIG_NOSYSTEM` 变量、动态 `AGY_APPLICATION_ID`、`DEV_MODE` 与 `app_id` 覆盖。 |
| `test_diagnostics.py` | `preflight`、`port_checker` | 3 个测试：当前文件系统检查、无参数的默认预检、空闲 TCP loopback 端口校验。 |
| `test_logging.py` | `log`、`flight_recorder` | 2 个测试：结构化 JSONL 流写入、带平台与磁盘元数据收集的崩溃快照生成。 |
| `test_bridge.py` | `client`、`commands` | 5 个测试：离线模式下网关自动 fallback、URL 格式化、wakelock、`typing.get_type_hints()` 内省与全部命令方法校验。 |
| `test_cli.py` | `agy_system`、`agy_logs` | 6 个测试：CLI 帮助调用、JSON 格式系统信息解析、崩溃报告检查、JSON 格式剪贴板读取、通过 `--commands` 的命令审计。 |

---

## 11. 可靠性审计、发现的 Bug 与修复

在对 `modules/env` 子系统源代码的深度审计中，发现并消除了关键架构缺陷、Android 路径错误与类型问题：

### 11.1. `preflight.py` 中的关键路径硬编码
- **问题**：`run_preflight` 函数签名中的默认参数被硬编码：`app_root: str = "/data/user/0/com.qin.agy/files"`。
- **后果**：在调试构建（`com.qin.agy.dev`）或任何自定义包中，无参调用 `run_preflight()` 会检查不存在的生产包 `com.qin.agy` 目录。结果 `os.path.exists()` 返回 `False`，`free_mb = 0`，预检器无条件报告错误 `ok: False`，阻塞核心启动。
- **修复**：参数改为 `app_root: Optional[str] = None`。增加了通过 `path_resolver.load_paths()["app_root"]` -> `$HOME` -> fallback 的级联动态目录解析。

### 11.2. `bridge/commands.py` 中的类型错误 `NameError: Optional`
- **问题**：函数签名中使用了类型注解 `Optional[int]`、`Dict[str, Any]`，但缺少从 `typing` 模块的导入。
- **后果**：在进行类型内省时（例如通过 `typing.get_type_hints(commands.notify)` 或 Pydantic/FastAPI 等校验库），Python 3.10+ 会抛出异常 `NameError: name 'Optional' is not defined`。
- **修复**：增加了显式导入 `from typing import Dict, Any, Optional`，并实现了缺失的方法 `clipboard_get()`、`cancel_notify()` 与 `system_info()`。

### 11.3. `cmd/runner.py` 中的日志抑制与硬编码
- **问题**：`LOG_DIR` 路径被硬编码为 `/data/user/0/com.qin.agy/files/logs`。
- **后果**：在 `com.qin.agy.dev` 环境中工作时，创建目录或写入 `commands.jsonl` 会因拒绝访问（`PermissionDenied`）失败，而该错误被 `except Exception: pass` 块静默吞掉。通过 `runner.py` 启动的所有命令都会丢失审计历史。
- **修复**：实现了 `_resolve_log_dir()` 函数，优先使用 `$HOME/logs` 与 `path_resolver`。

### 11.4. `cli/agy_logs.py` 中日志与崩溃转储不可用
- **问题**：`commands.jsonl`、`logs/core.log` 文件与 `logs/crashes` 目录的路径被硬编码绑定到 `com.qin.agy`。同时 `read_file_fallback` 检查的路径是 `~/.gemini/logs/core.log`，而实际文件创建在 `$HOME/logs/core.log`。
- **后果**：如果 Android Gateway 离线，`agy_logs.py` 命令无法发现任何日志文件，只输出空白。
- **修复**：引入 `_find_commands_log()` 函数，路径与动态解析器合并，并在 `core.log` 的候选列表中增加了 `$HOME/logs/core.log`。

### 11.5. Gateway 端口查找逻辑重复
- **问题**：`bridge/client.py` 中的 `resolve_gateway_url()` 与 `cli/agy_logs.py` 中的 `get_gateway_base()` 包含完全相同的重复代码，且两个函数都只检查 `gateway.port` 的静态路径。
- **修复**：`agy_logs.py` 现在导入 `bridge.client` 中的集中式方法，而端口解析器本身会检查 `$HOME/logs/gateway.port` 与环境标志 `AGY_APPLICATION_ID == "com.qin.agy.dev"`。

### 11.6. `env_builder.py` 中缺少 `$PREFIX` 与 `$GIT_CONFIG_NOSYSTEM`
- **问题**：变量 `PREFIX`（`/data/user/0/{package}/files/usr`）是 Android userland（Termux / AGY）中的关键环境变量。尽管 `paths.json` 中存在 `"prefix"` 键，`env_builder.py` 却没有设置 `PREFIX`。同时也缺少标志 `GIT_CONFIG_NOSYSTEM=1`。
- **修复**：增加了显式设置 `env["PREFIX"] = paths["prefix"]` 与 `env["GIT_CONFIG_NOSYSTEM"] = "1"`。

### 11.7. `path_resolver.resolve_subpath` 中的路径重置 Bug
- **问题**：在 Python 标准库中，`os.path.join("/a/b", "/c")` 返回 `"/c"`。传入带前导斜杠的相对路径（例如 `resolve_subpath("runtime", "/bin/sh")`）会导致基础目录被丢弃。
- **修复**：增加了规范化处理 `rel_path.lstrip("/")`。

### 11.8. `cli/agy_system.py` 中忽略 `--json` 标志与静默错误
- **问题**：`clipboard` 子命令忽略 `--json` 参数，且在与网关通信出错时不向用户报告任何信息。
- **修复**：实现了带 `--json` 标志时的原始 JSON 输出，以及向 `stderr` 输出错误消息并返回非零退出码。
