# Antigravity v2 - I/O 与事件总线子系统 (`modules/io`)

> **I/O 模块** — 为 Google Antigravity v2 在 Android/Linux 环境中提供进程间通信（IPC）、系统流（stdout/stderr）拦截与事件异步路由的中心子系统。

---

## 1. 架构概览

模块 `modules/io` 采用 **发布/订阅事件总线（Publish/Subscribe Event Bus）** 架构模式，支持流拦截器（Stream Interceptors）与异构传输方式（FIFO、UNIX Domain Socket、Android Intent）。

```
 +-------------------------------------------------------------------------------+
 |                               INPUT SOURCES                                   |
 |  [broadcast_receiver.sh]       [fifo_input.py]       [unix_socket_input.py]   |
 |   (Android am Intents)          (Named FIFO)           (AF_UNIX Stream)       |
 +-----------------------+---------------+-----------------------+---------------+
                         |               |                       |
                         +---------------+-----------------------+
                                         |
                                         v  JSON Event Envelopes
 +-------------------------------------------------------------------------------+
 |                             STREAM INTERCEPTORS                               |
 |   Core Server stdout/stderr ---> [stream_splitter.py]                         |
 |                                     |                                         |
 |             +-----------------------+-----------------------+                 |
 |             v                       v                       v                 |
 |     [filter_open_url.py]  [filter_task_events.py]  [filter_error_alerts.py]   |
 +-------------------------------------+-----------------------------------------+
                                       |
                                       v  envelope.create_event()
 +-------------------------------------------------------------------------------+
 |                               CORE EVENT BUS                                  |
 |                            [event_broker.py]                                  |
 |                                     |                                         |
 |                 +-------------------+-------------------+                     |
 |                 |                                       |                     |
 |                 v                                       v                     |
 |    In-Memory Handlers (Python)            [subscriber_router.py]              |
 |    (broker.register_handler)             (spec/default_routes.json)           |
 +---------------------------------------------------------+---------------------+
                                                           |
                                                           v  Process Dispatch
 +-------------------------------------------------------------------------------+
 |                               OUTPUT HANDLERS                                 |
 |  [notify_tray.sh]      [broadcast_emitter.sh]    [file_sink.py]  [socket_out] |
 |   Android Tray via         ACTION_OPEN_URL       events.jsonl    AF_UNIX IPC  |
 |   agy-notify / am          to Android Activity     Append Log     Forwarding  |
 +-------------------------------------------------------------------------------+
```

---

## 2. 事件规范（Event Envelope）

总线中流转的所有事件均由 JSON Schema `spec/event_schema.json`（Draft-07）严格标准化。

### 2.1. 信封格式（Envelope）
```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "AntigravityEventEnvelope",
  "type": "object",
  "required": ["event", "timestamp", "source", "data"],
  "properties": {
    "event": {
      "type": "string",
      "description": "点分事件名，例如 core.url.open、agent.task.started"
    },
    "timestamp": {
      "type": "integer",
      "description": "毫秒级 Epoch 时间戳"
    },
    "source": {
      "type": "string",
      "description": "事件来源，例如 language_server:stdout、input:socket"
    },
    "data": {
      "type": "object",
      "description": "任意事件 JSON 载荷"
    }
  }
}
```

### 2.2. 序列化与校验 (`core_bus/envelope.py`)
- `create_event(event_type, source, data)`：创建带有当前毫秒时间戳 `int(time.time() * 1000)` 的事件字典。
- `serialize_event(envelope)`：将事件序列化为 UTF-8 JSON 字符串。
- `deserialize_event(raw_text)`：解析 JSON 并校验必填字段是否存在。
- `validate_event(envelope)`：检查 `event`、`timestamp`、`source`、`data` 字段是否存在、`event` 为非空字符串且 `data` 为字典类型。

---

## 3. 总线核心 (`core_bus/`)

### 3.1. 路由器 (`subscriber_router.py`)
- 从 JSON 文件（`spec/default_routes.json`）加载路由表。
- 通过 `fnmatch.fnmatch` 支持通配符（例如 `agent.task.*`、`core.*`、`*`）。
- 方法 `get_destinations(event_type)` 返回排序去重后的输出脚本处理器列表。

#### 默认路由表 (`spec/default_routes.json`)
| 事件模式 (`pattern`) | 处理器 (`outputs`) | 用途 |
| :--- | :--- | :--- |
| `core.url.open` | `broadcast_emitter.sh`、`file_sink.py` | 在 Android 系统浏览器中打开授权/OAuth URL |
| `agent.task.*` | `notify_tray.sh`、`file_sink.py` | 在状态栏通知 Agent 任务生命周期 |
| `system.error` | `notify_tray.sh`、`file_sink.py` | 通知核心严重故障与 panic |
| `external.*` | `file_sink.py` | 外部集成事件 |

### 3.2. 事件代理 (`event_broker.py`)
- 中央事件分发器 `EventBroker`。
- 支持两级订阅者：
  1. **In-Memory Handlers**：Python 回调（`register_handler(pattern, callable)`），在进程内同步执行。
  2. **External Script Handlers**：调用 `outputs/` 目录中的外部 shell 与 Python 脚本。
- 外部处理器的调用通过 `subprocess.Popen` 进行，JSON 信封经 `stdin` 传递，执行超时可配置（默认 5 秒）。
- **僵尸进程与 FD 泄漏防护**：发生 `subprocess.TimeoutExpired` 时，保证调用 `proc.kill()`、`proc.wait()` 以从操作系统进程表中回收进程，并关闭 `stdin`/`stderr` 流描述符。
- 考虑 Android 限制：在 `/storage/emulated/0`（`noexec` 挂载）上，`.sh` 文件会显式指定解释器启动（`/system/bin/sh` 或 `bash`）。

---

## 4. 输入通道 (`inputs/`)

1. **FIFO 管道监听器 (`inputs/fifo_input.py`)**
   - 监听命名管道（`mkfifo`），反序列化按行的 JSON 事件并发布到 `EventBroker`。
   - 启动：`python3 fifo_input.py <path_to_fifo>`。
   - **FUSE 安全**：函数 `get_safe_pipe_path` 会自动将 FUSE 存储（`/storage/emulated/0`、`/sdcard`）上的路径——在这些路径上 `mkfifo` 会以 `PermissionError` 失败——重定向到安全的内部运行时目录（`/data/user/0/.../runtime` 或 `$TMPDIR`）。
   - **非阻塞写入**：函数 `write_fifo_nonblocking(fifo_path, data)` 通过 `O_NONBLOCK` 标志写入，防止在没有活动读取器时挂起。

2. **UNIX Domain Socket 监听器 (`inputs/unix_socket_input.py`)**
   - 流式 UNIX 套接字服务器（`AF_UNIX`、`SOCK_STREAM`）。
   - 接受外部 IPC 连接，按 `\n` 行分隔符收集输入缓冲区，并将事件发送到代理。
   - 启动：`python3 unix_socket_input.py <path_to_socket>`。

3. **Android Broadcast Receiver 桥接 (`inputs/broadcast_receiver.sh`)**
   - 将 Android Intent（例如来自 `am broadcast` 或插件）转换为总线 JSON 信封的工具：
     `broadcast_receiver.sh <action> <payload_json> [destination_fifo]`
   - 生成形如 `android.intent.<ACTION>` 的事件，写入总线管道或输出到 `stdout`。
   - 支持 FUSE 路径的安全解析与向 FIFO 的非阻塞发送（通过 Python `O_NONBLOCK` 或 `timeout` 工具）。

4. **自定义源模板 (`inputs/templates/template_input.sh`)**
   - 用于创建自定义事件生成器（传感器、钩子、webhook）的现成模板，内置 FIFO 防阻塞保护。

---

## 5. 流拦截器 (`interceptors/`)

拦截器在服务器核心启动时使用（`modules/runner/start_core.sh`），核心的 `stdout` 与 `stderr` 被重定向到 `stream_splitter.py` 管道。

### 5.1. 流拆分器 (`stream_splitter.py`)
- 从 `sys.stdin` 读取行。
- 无条件将它们复制到 `sys.stdout`（保留进程的直通日志）。
- **异步事件队列（Queue Worker）**：拦截到的事件的发布被移入 `queue.Queue` 队列，由独立的后台守护线程处理。因此事件分发与外部脚本处理器不会阻塞服务器核心 `stdout`/`stderr` 的读取。
- 并行地将每一行送入过滤器链 `FILTERS`。
- 如果过滤器识别出事件标记，则构建信封 `envelope.create_event` 并放入待发布队列。

### 5.2. 过滤器模块 (`interceptors/filters/`)
- `filter_open_url.py`：拦截前缀 `AGY_OPEN_URL=`、`ANTIGRAVITY_OPEN_URL:` 与 OAuth 链接（`accounts.google.com`、`/auth/callback`），生成事件 `core.url.open`。
- `filter_task_events.py`：跟踪 Agent 任务阶段（`[task:start]`、`[task:done]`、`tool_call:`），生成 `agent.task.started`、`agent.task.completed`、`agent.tool.executed`。
- `filter_error_alerts.py`：跟踪致命错误（`panic:`、`fatal error:`、`sigsegv`、`sigill`），生成 `system.error`。

---

## 6. 输出处理器 (`outputs/`)

1. **Android 托盘通知器 (`outputs/notify_tray.sh`)**
   - 从 `stdin` 接收 JSON 事件。
   - 提取事件名与消息文本。
   - 通过 `agy-notify` 工具或 `am broadcast -a com.qin.agy.ACTION_NOTIFY` 发送 Android 系统通知。

2. **Android 浏览器 Intent 发射器 (`outputs/broadcast_emitter.sh`)**
   - 接收事件 `core.url.open`。
   - 提取 `url` 字段并发送 Android Broadcast `com.qin.agy.ACTION_OPEN_URL`，以在外部浏览器或 Custom Tabs 中打开。

3. **持久化 JSONL 日志器 (`outputs/file_sink.py`)**
   - 将每个传入事件写入 append-only 日志文件（默认 `~/.gemini/events.jsonl` 或变量 `AGY_EVENT_LOG`）。

4. **UNIX Socket 发射器 (`outputs/unix_socket_emitter.py`)**
   - 通过到 UNIX 套接字的客户端连接，将事件转发给外部后台进程或守护进程。

---

## 7. 模块测试 (`tests/`)

测试套件覆盖总线的关键组件，验证序列化与路由的正确性。

### 7.1. 运行全部测试
```bash
bash modules/io/tests/run_all.sh
```

### 7.2. 测试组成
| 测试文件 | 被测组件 | 验证场景 |
| :--- | :--- | :--- |
| `test_envelope.py` | `envelope.py` | 类型校验、信封创建、序列化/反序列化 roundtrip、无效 schema 拦截 |
| `test_event_broker.py` | `SubscriberRouter`、`EventBroker` | 路由通配符过滤（`*`、`core.*`）、in-memory 分发、外部脚本超时时调用 `proc.kill()`/`proc.wait()` 并关闭 FD（`test_03_timeout_cleanup`） |
| `test_fifo_io.py` | `fifo_input.py`、`EventBroker` | 集成测试：创建 FIFO、后台监听线程、事件发布与接收、FUSE 路径重定向、无读取器时的非阻塞写入 |
| `test_stream_splitter.py` | `stream_splitter.py`、过滤器 | 通过 `io.StringIO` 测试：保留原始流 + 生成 `core.url.open` 与 `system.error`，通过 `queue.Queue` 的异步事件队列不延迟核心流 |
| `run_all.sh` | 全部组件 | Python 语法校验（`compileall`）与 Bash 语法校验（`bash -n`）+ 依次运行模块测试 |

---

## 8. 可靠性审计与技术债

审计中发现并消除了关键的架构与平台问题：

### 8.1. Shell 脚本中的 JSON 解析 [已解决]
- **问题**：`notify_tray.sh`、`broadcast_emitter.sh` 与 `template_output.sh` 中使用朴素的 `grep -o '"url":"[^"]*'` 解析 JSON。标准 Python `json.dumps()` 会在冒号后插入空格（`"url": "..."`），导致字段提取失败。
- **解决**：正则表达式已更新为支持任意空白字符 `[[:space:]]*:[[:space:]]*`（`"url"[[:space:]]*:[[:space:]]*"[^"]*"`）。已验证与 Android 上 `/system/bin/sh`（Toybox）和 Bash 的兼容性。

### 8.2. 阻塞调用与核心挂起风险（Head-of-Line Blocking）[已解决]
- **问题**：`stream_splitter.py` 直接接入主服务器核心的 `stdout`。检测到 URL 或错误时，同步调用 `broker.publish()` 会导致 Linux 管道缓冲区（64 KB）填满并阻塞核心。
- **解决**：`stream_splitter.py` 中的事件发布改为异步模型，使用 `queue.Queue` 与后台守护线程。`stdout`/`stderr` 流被即时读取与刷新。

### 8.3. `subprocess.Popen` 超时时的文件描述符泄漏与僵尸进程 [已解决]
- **问题**：`event_broker.py` 中进程超时（`subprocess.TimeoutExpired`）时，子进程继续在后台挂起，`stdin`/`stderr` 描述符未关闭。
- **解决**：增加了专门的 `except subprocess.TimeoutExpired` 代码块，保证调用 `proc.kill()`、`proc.wait()`（消除操作系统进程表中的僵尸进程）并关闭已打开的 `stdin` 与 `stderr` 文件描述符。

### 8.4. 无读取器时写入 FIFO 阻塞 [已解决]
- **问题**：`broadcast_receiver.sh` 与模板中直接写入 `echo "$JSON" > "$FIFO"`，按 POSIX 规范会一直阻塞到读取器连接。
- **解决**：`broadcast_receiver.sh` 与 `template_input.sh` 中增加了通过 Python `os.O_NONBLOCK` 或 `timeout 1s` 工具的非阻塞发送。`fifo_input.py` 中增加了 `write_fifo_nonblocking` 函数。

### 8.5. FIFO 与 UNIX 套接字与 Android FUSE 不兼容 [已解决]
- **问题**：尝试在 `/storage/emulated/0/...` 上创建命名管道（`mkfifo`）会引发 `PermissionError: [Errno 13] Permission denied`。
- **解决**：在 `get_safe_pipe_path`（`fifo_input.py`）与 `broadcast_receiver.sh` 中实现了 FUSE 前缀（`/storage/`、`/sdcard/`、`/mnt/`）的自动识别，并将管道透明重定向到应用内部存储（`/data/user/0/.../runtime` 或 `$TMPDIR`）。

### 8.6. 与 Java 层的代码重复
- **状态**：架构待办。`filter_open_url.py` 中的链接与前缀识别逻辑与 `OpenUrlWatcher.java` 重复。建议在后续迭代中通过 `broadcast_receiver.sh` 使用统一总线。
