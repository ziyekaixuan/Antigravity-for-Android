# Antigravity 核心模块（`modules/core`）

> Antigravity v2 核心分发的基础模块：解包、ELF/ABI AArch64 校验、glibc 环境供应、shim，以及 runtime 目录的加密构建。

---

## 1. 用途与职责范围

`modules/core` 模块是 Android 平台（ARM64 / AArch64）上 Antigravity v2 运行时的基础。它提供 language server 原始二进制文件、glibc 系统库、证书、配置，并提供用于以下工作的工具：

1. **解包与提取（`unpack/`）：** 检测归档格式（tar.gz / 原始 ELF）并提取 `language_server`，设置 POSIX 权限。
2. **二进制校验（`validate/`）：** 检查 SHA-256 校验和，校验 64 位小端 ELF 结构、目标架构 AArch64（EM=183）和格式（ET_DYN/PIE）。
3. **加密清单（`manifest/`）：** 流式计算 SHA-256（按 1 MB 分块），生成 `manifest.json` 并严格校验文件完整性。
4. **runtime 包组装（`bundle/`）：** 为消费者（`modules/runner`、`modules/builder`）组装隔离的 runtime 目录（`bin/`、`lib/`、`certs/`、`seed/`）。
5. **系统 shim（`shims/`）：** 模拟 Linux 环境工具（`xdg-open`、浏览器调用、Shizuku `rish`）。

---

## 2. 目录与文件结构

```
modules/core/
├── spec/                       # 核心的声明式规范
│   ├── version.env             # 版本变量、构建标签、加载器名称
│   ├── checksums.json          # 归档/二进制文件的基准 SHA-256 和大小
│   ├── glibc_deps.list         # 所需 glibc .so 库列表
│   └── shims.list              # 待安装的可执行 shim 列表
├── pristine/                   # 原始未修改的核心归档
│   └── language_server.tar.gz  # Pristine aarch64 language_server（解包后约 181 MB）
├── unpack/                     # 解包与权限模块
│   ├── archive_detector.py     # 格式检测（tar_gz、elf、unknown）
│   ├── extractor.py            # 从 tar.gz / 原始 ELF 中提取核心二进制文件
│   └── permissions.py          # 设置 POSIX 权限（+x / +r）
├── validate/                   # ELF 与校验和校验器
│   ├── elf_header.py           # 检查魔数 \x7fELF、EI_CLASS（64 位）、EI_DATA（LE）
│   ├── elf_arch.py             # 检查 e_machine == EM_AARCH64 (183)
│   ├── elf_compat.py           # 检查 e_type（ET_EXEC / ET_DYN）
│   ├── checksum_checker.py     # 流式比对文件 SHA-256
│   └── verify_pipeline.py      # 端到端校验 E2E 流水线（5 个阶段）
├── manifest/                   # 清单生成器与校验器
│   ├── file_hasher.py          # 流式计算 SHA-256（1MB 分块）
│   ├── manifest_builder.py     # 扫描目录并构建 manifest.json
│   └── manifest_verifier.py    # 按 manifest.json 检查目录完整性
├── bundle/                     # 最终 runtime 组装
│   ├── layout.py               # 初始化目录结构（bin、lib、certs、seed 等）
│   ├── pack_binaries.py        # 将 language_server、加载器和 shim（符号链接/委托器）打包到 bin/
│   ├── pack_libraries.py       # 将 glibc 库复制到 lib/，将网络配置复制到 etc/
│   ├── pack_certs.py           # 将 SSL 证书复制到 certs/
│   ├── pack_seed.py            # 将预填充状态复制到 seed/
│   └── assemble.py             # 完整 runtime 组装的 CLI 与 Python 编排器
├── glibc/                      # aarch64 下的 glibc 库集
│   ├── ld-linux-aarch64.so.1   # 动态链接器（ELF 解释器）
│   ├── libc.so.6, libm.so.6... # glibc 系统库
│   ├── liblse_compat.so        # 为旧 CPU 模拟 LSE 原子指令
│   └── etc/                    # 配置文件（hosts、nsswitch、resolv）
├── certs/                      # 安全证书
│   └── ca-certificates.crt     # 系统 SSL CA 证书库
├── seed/                       # 初始状态与指令
│   ├── AGENTS.md               # 系统提示词与环境规则
│   └── *.pbtxt                 # 引导初始 protobuf 状态
├── shims/                      # 系统与浏览器垫片
│   ├── browser/                # xdg-open 与浏览器委托器（chrome、open 等）
│   ├── privilege/              # rish.sh（Shizuku ADB shell 接口）
│   └── system/                 # open_helper.sh、xdg_open.sh（URL 分发器）
└── tests/                      # 单元测试
    ├── test_extractor.py       # 解包与 archive_detector 测试
    ├── test_elf_validator.py   # ELF 头与架构测试
    ├── test_manifest.py        # 清单生成与校验测试
    ├── test_bundle.py          # layout、etc 配置与 assemble.py 组装测试
    ├── test_shims.py           # xdg-open 与浏览器委托器中的 URL 解析测试
    └── run_all.sh              # 语法检查与测试运行脚本
```

---

## 3. 组件接口（API 与 CLI）

### 3.1. 解包（`unpack/`）

- **`extractor.extract_core(src_path: str, dest_path: str, binary_name: str = "language_server") -> bool`**
  - 从 `tar.gz` 中提取可执行文件 `binary_name`，或复制原始 `ELF`。
  - CLI：`python3 extractor.py <source_path> <dest_binary_path> [binary_name]`

- **`archive_detector.detect_type(file_path: str) -> str`**
  - 返回：`"tar_gz"`、`"elf"`、`"unknown"`、`"not_found"`。
  - CLI：`python3 archive_detector.py <file_path>`

- **`permissions.make_executable(file_path: str) -> None`**
  - 为文件添加 `r-xr-xr-x` 标志。
  - CLI：`python3 permissions.py [--exec|--read] <path>`

### 3.2. 校验（`validate/`）

- **`elf_header.validate_elf_header(file_path: str) -> bool`**
  - 检查签名 `\x7fELF`、64 位（`EI_CLASS=2`）、小端（`EI_DATA=1`）。
- **`elf_arch.validate_aarch64(file_path: str) -> bool`**
  - 检查 `e_machine == 183`（ARM64/AArch64）。
- **`elf_compat.validate_executable_type(file_path: str) -> str`**
  - 返回 `"ET_DYN"`（PIE/SO）或 `"ET_EXEC"`。
- **`checksum_checker.verify_checksum(file_path: str, expected_hash: str) -> bool`**
  - 流式计算 SHA-256（1 MB 缓冲）并与基准值比对。

### 3.3. 清单（`manifest/`）

- **`manifest_builder.build_manifest(runtime_dir: str, out_file: str = None) -> dict`**
  - 递归扫描 `runtime_dir`（排除 `manifest.json` 本身），计算每个文件的 SHA-256，并写入以下结构的 JSON：
    ```json
    {
      "schema": 1,
      "generated_at": "2026-10-15T20:00:00Z",
      "files": {
        "bin/liblanguage_server.so": "<sha256>",
        "lib/libc.so.6": "<sha256>"
      }
    }
    ```
  - CLI：`python3 manifest_builder.py <runtime_dir> [output_manifest_path]`

- **`manifest_verifier.verify_manifest(manifest_path: str, target_dir: str) -> bool`**
  - 检查清单中描述的所有文件的存在性与校验和是否一致。
  - CLI：`python3 manifest_verifier.py <manifest_json_path> <target_dir>`

### 3.4. 包组装（`bundle/`）

- **`assemble.assemble_runtime(core_bin_path: str, module_root: str, out_dir: str, dry_run: bool = False) -> dict`**
  - runtime 组装编排器：
    1. 初始化目录（`layout.py`），包括 `bin`、`lib`、`certs`、`seed`、`etc`。
    2. 将核心复制为 `bin/liblanguage_server.so`，加载器复制为 `bin/libloader.so` 和 `bin/ld-linux-aarch64.so.1`，以及 shim（`pack_binaries.py`）。浏览器工具通过符号链接或指向 `xdg-open` 的紧凑链接关联。
    3. 将共享库复制到 `lib/`，并将网络和 DNS 配置（`hosts`、`resolv.conf`、`nsswitch.conf`）从 `glibc/etc` 复制到 `etc/`（`pack_libraries.py`），避免重复复制加载器。
    4. 将证书复制到 `certs/`（`pack_certs.py`）。
    5. 将 seed 文件复制到 `seed/`（`pack_seed.py`）。
    6. 生成 `manifest.json`（`manifest_builder.py`）。
  - CLI:
    ```bash
    python3 modules/core/bundle/assemble.py \
      --core-bin /path/to/language_server \
      --out-dir /path/to/destination/runtime \
      [--dry-run]
    ```

---

## 4. 组装后的 Runtime 结构（`assets/runtime`）

组装完成后，模块形成如下拓扑结构的目录：

| 目录 | 内容 | 用途 |
| :--- | :--- | :--- |
| `bin/` | `liblanguage_server.so`、`libloader.so`、`ld-linux-aarch64.so.1`、`xdg-open`、`chrome`、`rish`... | 可执行二进制文件、glibc 加载器和 shell shim |
| `lib/` | `libc.so.6`、`libm.so.6`、`libdl.so.2`、`libpthread.so.0`、`liblse_compat.so`、`ld-linux-aarch64.so.1` | AArch64 下的 glibc 依赖与 LSE 原子指令库 |
| `etc/` | `hosts`、`resolv.conf`、`nsswitch.conf` | glibc 网络栈所需的网络与 DNS 配置 |
| `certs/` | `ca-certificates.crt` | Go TLS 运行时所需的根 SSL/TLS 证书 |
| `seed/` | `AGENTS.md`、`antigravity_state.pbtxt`、`jetski_state.pbtxt` | 引导预设、代理配置与系统规则 |
| `/` | `manifest.json` | 文件完整性的加密登记表 |

---

## 5. 测试与校验

### 运行全部单元测试
```bash
bash modules/core/tests/run_all.sh
```
包括：
1. Python 语法编译（`compileall`）与 shell 脚本检查（`bash -n`）。
2. 解包单元测试（`test_extractor.py`）。
3. ELF 头与架构单元测试（`test_elf_validator.py`）。
4. 清单与损坏检测单元测试（`test_manifest.py`）。
5. 包组装、layout 与 etc 配置单元测试（`test_bundle.py`）。
6. xdg-open 与浏览器 shim 中的 URL 解析单元测试（`test_shims.py`）。

### 端到端 E2E 校验流水线
```bash
python3 modules/core/validate/verify_pipeline.py
```
在隔离沙箱中执行 5 步端到端校验：比对 pristine 归档哈希 -> 提取 -> ELF/ABI 校验 -> 组装测试 runtime -> 检查生成的清单。
