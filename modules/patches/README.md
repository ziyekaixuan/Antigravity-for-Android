# Antigravity 核心二进制补丁器 (`modules/patches`)

针对 **Android ARM64 (aarch64)** 架构的 Antigravity 核心（`language_server`）定点二进制补丁模块。用于绕过平台限制（Android seccomp、Bionic libc、ARMv8.0-A 上缺少 LSE atomics）并与 runtime 环境集成。

---

## 1. 架构

```
modules/patches/
├── pipeline.py              # CLI 编排器与管道启动 API
├── spec/
│   └── patch_manifest.json  # 预期补丁与策略的规范
├── engine/                  # 底层分析与修改引擎
│   ├── elf_scanner.py       # ELF64/AArch64 解析器与 .text 段定位器
│   ├── pclntab_scanner.py   # Go pclntab 函数表解析器与 MOVZ 编码器
│   ├── byte_replacer.py     # 字节模式搜索与 in-place 替换
│   ├── reporter.py          # 已执行补丁的 JSON 报告生成
│   └── patch_context.py     # 执行上下文模型
├── groups/                  # 功能补丁组
│   ├── gates/               # 绕过 CLI gate 与 Enterprise Auth
│   ├── syscalls/            # 替换被 seccomp 拦截的 faccessat2/fchmodat2
│   ├── sigill/              # SIGILL 处理器保护（用于 liblse_compat）
│   ├── resolv/              # 将 /etc/resolv.conf 重定向为相对路径
│   └── failfast/            # 抑制 ARMv8.0-A CPU 检查与 preinit atomic
└── tests/                   # 模块与集成测试
    ├── test_byte_replacer.py
    ├── test_elf_scanner.py
    ├── test_pclntab_scanner.py
    ├── test_full_pipeline.py
    └── run_all.sh
```

---

## 2. 补丁组

| 组 | 模块 | 目标与签名 | 动作 |
|---|---|---|---|
| **gates** | `cli_gate` | CLI gate 检查中的 `ldrb w1, [x0,#8]` | 替换为 `mov w1, #1`（`21 00 80 52`） |
| **gates** | `enterprise_auth` | `EnterpriseAuthValidator.Validate` | 强制设置有效标志 |
| **syscalls** | `faccessat` | `syscall.faccessat2`（sys_439：`0xd28036e0`） | 重定向到 `faccessat`（sys_48：`0xd2800600`） |
| **syscalls** | `fchmodat` | `syscall.fchmodat2`（sys_452：`0xd2803880`） | 重定向到 `fchmodat`（sys_53：`0xd28006a0`） |
| **sigill** | `sigill_handler` | Go 运行时中的 `rt_sigaction` 调用 | 忽略信号 `SIGILL`（4），以保留 `liblse_compat.so` 的处理器 |
| **resolv** | `resolv_redirect` | 字符串 `/etc/resolv.conf`（16 字节） | 替换为 `etc//resolv.conf`，长度不变 |
| **failfast** | `crash_suppressor` | ARMv8.0-A CPU 特性检查（`0x7701180`）与 preinit atomic（`0x7765c60`） | 用 `b +0x64` 分支绕过 LSE/CRC/AES 检查，并将 `ldaddal` 替换为 `ldr`/`str`，实现与 ARMv8.0-A 处理器（Snapdragon 660 等，无硬件 LSE）的完全兼容 |

---

## 3. 分析引擎优化（第一轮）

1. **`elf_scanner.py`**：
   - 在偏移 0 处直接校验 `e_ident` 头，支持搜索内嵌 ELF 容器。
   - 支持 stripped 二进制（`e_shnum == 0`）：自动 fallback 到 Program Headers（带 `PF_X` 标志的 `PT_LOAD`）。
   - 对 `.text`、`.shstrtab` 段与头表的偏移与大小进行严格边界检查。
2. **`pclntab_scanner.py`**：
   - 优化的 `find_function`：直接在字符串表 `funcnametab` 中搜索函数名位置。
   - 在 pclntab 上下文中内部缓存函数搜索结果（`_cache`），避免每次 syscall 都循环解码数万个 latin-1 字符串。
3. **补丁组中的 Zero-Copy**：
   - 彻底消除扫描循环中 `bytes(data)` 的中间分配；直接按缓冲区协议操作（`bytearray` / `memoryview`）。
   - 消除 `crash_suppressor.py` 中的校验绕过：严格遵守 fail-closed 策略。

---

## 4. CLI 与 Python API

### CLI Runner
```bash
# 对二进制应用全部补丁：
python3 modules/patches/pipeline.py /path/to/language_server

# 将报告保存到 manifest：
python3 modules/patches/pipeline.py /path/to/language_server --manifest report.json

# 检查补丁是否存在（exit code 0 / 1）：
python3 modules/patches/pipeline.py /path/to/language_server --verify

# 不写入更改的试运行：
python3 modules/patches/pipeline.py /path/to/language_server --dry-run

# 容忍缺失的签名：
python3 modules/patches/pipeline.py /path/to/language_server --allow-missing
```

### Python API
```python
import pipeline

ok = pipeline.run_pipeline(
    target_path="/path/to/language_server",
    verify=False,        # 仅验证
    dry_run=False,       # 试运行，不写入磁盘
    allow_missing=False, # 缺失签名时不失败
    manifest_path=None   # 保存 JSON manifest 的路径
)
```

---

## 5. 测试

运行完整测试套件：
```bash
bash modules/patches/tests/run_all.sh
```

- `test_byte_replacer.py`：字节匹配与定点替换的单元测试。
- `test_elf_scanner.py`：ELF64 校验、stripped 二进制的 Program Headers fallback 与边界检查。
- `test_pclntab_scanner.py`：AArch64 操作码 `MOVZ X0, #imm` 编码校验、`funcnametab` 直接搜索与缓存。
- `test_full_pipeline.py`：在真实 pristine 二进制 `language_server` 上的 E2E 测试（dry-run、apply、sha256 check、verify、idempotence、fail-closed validation）。
