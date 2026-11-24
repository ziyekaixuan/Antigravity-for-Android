# APK 构建器模块：`modules/builder`

> 无需 Gradle 和 Android Studio 的自主 Android APK 构建流水线。
> 针对在 Android 环境（ARM64 / Linux）中原生直接运行进行了优化。

---

## 1. 架构概览

Antigravity APK 的构建完全独立于重量级构建系统（Gradle、AGP、Groovy/Kotlin daemon）。编排器 [`build_apk.sh`](build_apk.sh) 调用一串原子脚本，直接操控 Android SDK 的底层二进制工具（`aapt`、`javac`、`d8`/`r8`、`zipalign`、`apksigner`）以及专用的 Python 工具。

### 流水线示意图 (Pipeline Diagram)

```mermaid
flowchart TD
    Env[config/app.env] --> P01[01_gen_config.py]
    Src[app/src/main/...] --> P01
    
    subgraph Config & Resources
        P01 -->|BuildConfig.java| GenDir[build/gen]
        P01 -->|gen.xml| BuildRes[build/res/values]
        P01 -->|Dev-aware Manifest| TargetManifest[build/AndroidManifest.xml]
        TargetManifest & ResDir[app/src/main/res] & BuildRes --> P02[02_r_java.sh: aapt overlay]
        P02 -->|R.java| GenDir
    end

    subgraph Bytecode & Dexing
        GenDir & Src --> JavaArg[@build/java_sources.txt]
        JavaArg --> P03[03_compile_java.sh: javac]
        P03 -->|*.class| ObjDir[build/obj]
        ObjDir --> ClassArg[@build/class_files.txt]
        ClassArg --> P04[04_dex.sh: D8 via r8.jar]
        P04 -->|classes.dex| DexDir[build/dex]
    end

    subgraph Assets & Packaging
        Runtime[modules/runner/prepare_runtime] --> P05[05_stage_assets.py]
        Web[web/] & EnvDist[env/dist] --> P05
        P05 -->|staged assets| StagingDir[build/staging/assets]
        
        TargetManifest & ResDir & BuildRes & StagingDir & DexDir --> P06[06_package.sh: aapt + zip]
        P06 -->|raw APK| Unaligned[build/unaligned.apk]
    end

    subgraph Align, Sign & Verify
        Unaligned --> P07[align.py: zipalign / python]
        P07 -->|4-byte aligned APK| Aligned[build/aligned.apk]
        Aligned --> P08[08_sign.sh: apksigner]
        P08 -->|Signed APK (v1+v2+v3)| Dist[dist/antigravity.apk / antigravity-dev.apk]
        Dist --> P09[09_verify.sh: apksigner verify]
    end
```

---

## 2. 构建流水线阶段 (Build Stages)

| 阶段 | 脚本 | 主要工具 | 输入 | 输出 | 用途 |
|---|---|---|---|---|---|
| **01** | [`01_gen_config.py`](01_gen_config.py) | Python 3 | `config/app.env`、`AndroidManifest.xml` | `build/gen/**/BuildConfig.java`、`build/res/values/gen.xml`、`build/AndroidManifest.xml` | 生成常量、端口和 dev 清单到构建树（不污染 git） |
| **02** | [`02_r_java.sh`](02_r_java.sh) | `aapt` | `AndroidManifest.xml`、`res/`、`build/res/`、`android.jar` | `build/gen/**/R.java` | 编译资源标识符，带 overlay，发布版跳过重复执行 |
| **03** | [`03_compile_java.sh`](03_compile_java.sh) | `javac` | `@build/java_sources.txt`、`android.jar` | `build/obj/**/*.class` | 通过参数文件编译 Java 源码（防止 `ARG_MAX`） |
| **04** | [`04_dex.sh`](04_dex.sh) | `D8`（`tools/r8.jar`） | `@build/class_files.txt`、`android.jar` | `build/dex/classes.dex` | 通过 D8 参数文件将 Java 字节码转换为 DEX，避免 CLI 溢出 |
| **05** | [`05_stage_assets.py`](05_stage_assets.py) | Python 3 + `modules/runner` | 运行时核心、`web/`、`env/dist/env` | `build/staging/assets/` | 收集并校验资源（runtime、web、env） |
| **06** | [`06_package.sh`](06_package.sh) | `aapt` + `zipfile` | 资源 + `build/res` overlay、清单、`classes.dex`、`staging/assets/` | `build/unaligned.apk` | 创建基础 APK（带 overlay），注入 dex 和资源 |
| **07** | [`align.py`](align.py) | `zipalign` / Python | `build/unaligned.apk` | `build/aligned.apk` | 对 ZIP 归档做 4 字节对齐以便 `mmap` |
| **08** | [`08_sign.sh`](08_sign.sh) | `apksigner` | `build/aligned.apk`、`tools/debug.keystore` | `dist/antigravity.apk` 或 `dist/antigravity-dev.apk` | 使用 v1、v2 和 v3 方案签名 |
| **09** | [`09_verify.sh`](09_verify.sh) | `apksigner` | `dist/*.apk` | stdout 中的校验结果 | 完整校验有效性与签名方案 |

---

## 3. 各步骤详解

### 步骤 01：生成配置 (`01_gen_config.py`)
- **输入**：`config/app.env` 中的变量和 `DEV_MODE`（0 或 1）。
- **输出**：
  - `build/gen/com/agy/BuildConfig.java`：静态字段 `PORT`、`GATEWAY_PORT`、`CORE_VERSION`、`APP_VERSION_NAME`、`DEBUG`。
  - `build/res/values/gen.xml`：供 Android framework 使用的字符串和整数 XML 资源（生成到构建树中，避免污染仓库和 `git status`）。
  - `build/AndroidManifest.xml`：替换 `${applicationId}`，dev 模式下将包名改为 `io.github.ziyekaixuan.antigravity.dev`，设置 `android:debuggable="true"`，并隔离 `permission.INTERNAL_BROADCAST`。

### 步骤 02：生成 R.java (`02_r_java.sh`)
- 运行 `aapt package -m -J <gen_dir>` 解析资源并生成数字 ID，使用 overlay `-S "$BUILD_DIR/res" -S "$RES_DIR" --auto-add-overlay`。
- 检查目标清单中的包名：如果包名等于基础包名 `io.github.ziyekaixuan.antigravity`，则跳过第二次重复的 `aapt` 运行。如果包名不同（例如 `io.github.ziyekaixuan.antigravity.dev`），则通过 `--custom-package io.github.ziyekaixuan.antigravity` 再生成一个兼容旧调用的回退 `io.github.ziyekaixuan.antigravity.R`。

### 步骤 03：编译 Java (`03_compile_java.sh`)
- 将应用源码和生成类（`BuildConfig`、`R`）的列表收集到参数文件 `@$BUILD_DIR/java_sources.txt` 中，彻底消除 `ARG_MAX`（`Argument list too long`）错误的风险。
- 使用 `javac` 编译代码：`-encoding UTF-8 -source 1.8 -target 1.8 -cp tools/android.jar @$BUILD_DIR/java_sources.txt`。

### 步骤 04：D8 dex 化 (`04_dex.sh`)
- 扫描类目录 `$OBJ_DIR` 并生成参数文件 `@$BUILD_DIR/class_files.txt`，消除命令行长度限制。
- 通过独立的 D8（`tools/r8.jar` 中的 `com.android.tools.r8.D8`）将编译好的 `.class` 文件转换为 `classes.dex`。
- 参数：`--output $DEX_DIR`、`--lib tools/android.jar`、`--min-api 24`、`@$BUILD_DIR/class_files.txt`。

### 步骤 05：资源暂存 (`05_stage_assets.py`)
- 将三组资源收集到 `build/staging/assets/`：
  1. `runtime/`：二进制核心 `language_server`（经 `prepare_runtime.py` 打补丁并加密校验）。
  2. `web/`：前端界面（`index.html`、`compiled_tailwind.css`、`generated/main.js` 等）。复制前自动调用 bundle 构建 `bash web/build.sh`，保证 `web/src/` 中的最新 Web 资源进入 APK。通过 `web_ignore` 过滤器排除辅助文件（`main.js`、`src/`、`tests/`、`build.sh`、`patch_main.py` 以及 `src.manifest`）。
  3. `env/`：环境 overlay、`libtermux-exec.so` 和 `manifest.json`。

### 步骤 06：打包 APK (`06_package.sh`)
- `aapt package -f` 生成 APK 骨架，支持资源 overlay（`-S "$BUILD_DIR/res" -S "$RES_DIR" --auto-add-overlay`）、二进制 `AndroidManifest.xml` 和已编译资源（`resources.arsc`）。
- 流式 Python ZIP 注入器添加 `classes.dex` 和 `assets/` 树。
- 已使用 gzip/png/jpeg/zip 算法压缩过的文件不重复压缩（`ZIP_STORED`），其余文件使用 `ZIP_DEFLATED` 压缩。

### 步骤 07：ZipAlign 对齐 (`align.py`)
- 将未编译数据的头部对齐到 4 字节边界（`.so` 库为 4096 字节），使 Android 系统能通过 `mmap()` 直接访问文件，无需解压到 RAM。
- **双层策略**：
  1. 系统二进制 `zipalign`（如果已安装在 `$PATH` 中）。
  2. 内置跨平台纯 Python 算法，无外部依赖。

### 步骤 08 和 09：签名与校验 (`08_sign.sh` 和 `09_verify.sh`)
- `apksigner sign` 使用 `tools/debug.keystore` 中的密钥签名 APK。
- 支持的方案：
  - **v1**：JAR 签名（兼容性）。
  - **v2**：APK Signature Scheme v2（Android 7.0+）。
  - **v3**：APK Signature Scheme v3（Android 9.0+）。
- `apksigner verify --verbose` 校验签名和清单摘要。

---

## 4. 依赖与工具

| 工具 | 最低版本 | 用途 | 位置 |
|---|---|---|---|
| **Python** | 3.8+ | 配置、暂存、打包、ZipAlign | 系统 `$PATH` |
| **aapt** | v0.2+ (v1) | 解析 XML、编译资源、APK 骨架 | 系统 `$PATH` |
| **javac** | OpenJDK 8 / 11 / 17 | 编译 `.java` -> `.class` | 系统 `$PATH` |
| **java** | JRE 8+ | 运行 R8/D8 | 系统 `$PATH` |
| **r8.jar** | Android R8/D8 | Dex 转换器（bytecode -> DEX） | `tools/r8.jar` |
| **android.jar** | API 24+（当前为 API 28） | Android Framework 存根 | `tools/android.jar` |
| **zipalign** | 任意（可选） | 快速原生对齐 | 系统 `$PATH` |
| **apksigner** | 0.9+ | APK 签名与校验 | 系统 `$PATH` |
| **debug.keystore**| JKS/PKCS12 | 测试签名证书 | `tools/debug.keystore` |

---

## 5. 使用方法

```bash
# 标准发布版 APK 构建（dist/antigravity.apk，端口 45167）：
bash modules/builder/build_apk.sh

# 带地区绕过的发布版构建（dist/antigravity-region-bypass.apk，端口 45167）：
bash modules/builder/build_apk.sh --region-bypass

# 运行 builder 模块测试：
bash modules/builder/tests/run_all.sh
```

---

## 6. 审计结果：Bug、临时方案与优化

### 1. `align.py` 中的严重 Bug（已修复）
- **问题**：纯 Python 实现 `align_with_python` 对每个条目调用 `zin.read()`。该方法会解压 `ZIP_DEFLATED` 压缩流。随后这些解压后的字节连同原始头部 `compress_type=8` 和 `compress_size` 被写入输出文件。结果是，在没有 `zipalign` 二进制文件的情况下，任何构建出的 APK 都会完全损坏（`zlib.error: invalid stored block lengths`）。
- **修复**：改写为按本地头部偏移直接读取原始压缩字节流（`raw payload`），以 64 KB 分块缓冲。数据损坏已消除，RAM 占用从 O(N) 降至 O(1)。

### 2. 源码污染（`gen.xml`）（已修复）
- **问题**：`01_gen_config.py` 将 `gen.xml` 直接写入仓库目录 `app/src/main/res/values/gen.xml`，导致 `git status` 被污染，并有误提交 dev 配置的风险。
- **修复**：将生成位置移到构建树 `$BUILD_DIR/res/values/gen.xml`。在 `02_r_java.sh` 和 `06_package.sh` 中添加了资源 overlay 支持 `-S "$BUILD_DIR/res" -S "$RES_DIR" --auto-add-overlay`，使生成值动态代入，Git 工作树保持干净。

### 3. `02_r_java.sh` 中的双重 `aapt` 运行（已修复）
- **问题**：发布模式下 `aapt` 以相同参数（`io.github.ziyekaixuan.antigravity`）运行两次，造成多余的 CPU 负载。
- **修复**：在 `02_r_java.sh` 中添加了清单检查（`PKG=$(sed -n -E 's/.*package="([^"]+)".*/\1/p' "$MANIFEST" | head -n 1)`）。如果目标包已等于 `io.github.ziyekaixuan.antigravity`，则跳过重复运行。如果包名不同（例如 `io.github.ziyekaixuan.antigravity.dev`），则生成两个包（`io.github.ziyekaixuan.antigravity.dev.R` 和兼容回退 `io.github.ziyekaixuan.antigravity.R`）。

### 4. 命令行溢出风险（`ARG_MAX`）（已修复）
- **问题**：在 `03_compile_java.sh` 和 `04_dex.sh` 中，所有源文件和类都通过 bash 数组 `${JAVA_SOURCES[@]}` 和 `${CLASS_FILES[@]}` 代入。随着代码库扩大，这会触发 Linux 内核系统错误 `Argument list too long`（`E2BIG`）。
- **修复**：
  - 对于 `03_compile_java.sh` 中的 `javac`：生成参数文件 `@$BUILD_DIR/java_sources.txt`，将所有路径转义后写入。
  - 对于 `04_dex.sh` 中的 `D8`：扫描 `$OBJ_DIR` 并生成参数文件 `@$BUILD_DIR/class_files.txt`，传给 `com.android.tools.r8.D8`（消除命令长度限制，并绕过 D8 不支持传入原始目录的问题）。

### 5. 多次 JVM 启动（JVM Overhead）
- **问题**：流水线连续启动 Java 虚拟机 4 次（`javac`、`d8`、`apksigner sign`、`apksigner verify`）。在移动 CPU 上每次 JVM 启动耗时 0.8–1.5 秒。
- **解决方案**：合并签名与校验阶段，使用合并脚本或编译守护进程。

### 6. 失效的 `--fast` 标志
- **问题**：`--fast` 标志在 `build_apk.sh` 中被解析，但未传递给子脚本。每次运行时 `build/` 目录都会被完全删除（`rm -rf`），重新复制并重新打包约 240 MB 资源。
- **解决方案**：将 `FAST_BUILD` 标志透传到 `05_stage_assets.py` 和 `06_package.sh`，实现增量构建并跳过未变更的核心资源。

### 7. 闪存上过多的磁盘 I/O
- **问题**：APK 完整写入磁盘连续进行 3 次：`unaligned.apk`（约 103 MB）-> `aligned.apk`（约 103 MB）-> `dist/antigravity.apk`（约 103 MB）。总写入量超过 300 MB，会磨损 Android 设备的闪存。
- **解决方案**：让 `align.py` 直接输出到目标文件 `$DIST_DIR/antigravity.apk` 并就地签名（`in-place signing`）。

### 8. Web 构建器集成与防止清单泄露（已修复）
- **问题**：在 `05_stage_assets.py` 中，Web 资源直接从 `web/` 原样复制，未预先运行构建，导致 `web/src/` 中的改动可能进不了 APK。此外，`web_ignore` 过滤器中缺少 `src.manifest`，导致源码清单进入了 APK 资源。
- **修复**：在 `05_stage_assets.py` 中加入了暂存资源前自动运行 `bash web/build.sh`，并将 `src.manifest` 加入 `web_ignore` 忽略列表。
