# Deploy

Deploy 是 Android 上的 ELF 符号分析软件及 MCP 服务器。LIEF 负责解析，Capstone
负责指令级反汇编，Ghidra 11.3.2 生成 AArch64 C 伪代码，Keystone 0.9.2 生成机器码。
当前版本为 0.4.0，应用运行于独立 UID，不需要 root。
当前 Activity 是临时服务看板；独立分析前端留待后续开发。

## 仓库布局

| 路径 | 职责 |
| --- | --- |
| `deploy/` | `dev.deploy` APK：DeployCore、JNI、独立 MCP 与 AIDL 服务 |
| `deploy-api/` | AIDL 与 MCP 分析参数契约的维护源 |
| `native-fixture/` | `dev.deploy.fixture` 测试 APK 及测试库 |
| `docs/MCP_PROMPT.md` | 给连接 Deploy 的 Agent 使用的提示词 |
| `docs/DEPLOY-NATIVE.md` | 跨应用分析、native 加载契约与真机验收步骤 |
| `tools/deploy-smoke.py` | 需要两款应用的集成测试，包含可选加载和隔离测试 |
| `tools/deploy-engines-smoke.py` | 引擎往返、伪代码、内存快照及补丁恢复验收 |
| `tools/prepare-ghidra.py` | 构建主机 SLEIGH 编译器并生成 Android 语言资产 |
| `third_party/` | 依赖版本、补丁及本地依赖目录 |

本仓库于 2026-10-10 从 PosEdMCP 的 `deploy-scaffold` 分支、提交
`7212643ce52ea313cb8bcba43804405f4ca976ef` 提取。工作目录为 `E:/deploy`；
PosEdMCP 在 `E:/posedmcp` 独立维护其 root、框架桥与加载器。本仓库的构建不需要
PosEdMCP 源码。原始代码历史仍在 PosEdMCP 仓库，新仓库从本次整理开始记账。

PosEdMCP 保留 `deploy-api/` 的契约副本，两个项目分别构建自己的 APK，运行时经 Binder
连接。修改契约时按 [deploy-api/README.md](deploy-api/README.md) 同步并验证两端。

## 构建

需要 JDK 17 或更高（本地使用 22）、Gradle 8.14.3、Android SDK 36、
NDK 28.2.13676358、CMake 3.22.1。先按
[third_party/README.md](third_party/README.md) 准备固定版本的依赖，
并在主机 C++ 编译环境中运行 `python tools/prepare-ghidra.py` 生成 Ghidra 语言资产。
APK 当前只打包 `arm64-v8a`；Capstone 可分析的 ELF 架构与 APK 自身 ABI 是两回事。

在仓库根目录创建不纳入 Git 的 `local.properties`，例如：

```properties
sdk.dir=E\:/SDK
```

Git Bash 下可沿用项目自带的 Gradle 下载器和启动器：

```bash
bash tools/bootstrap-gradle.sh
bash tools/gradle.sh :deploy:assembleDebug :native-fixture:assembleDebug
```

PowerShell 下，准备好同一 Gradle 发行包后运行：

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-22'
$env:GRADLE_USER_HOME = "$PWD/.gradle-home"
& ./.tools/gradle-8.14.3/bin/gradle.bat :deploy:assembleDebug :native-fixture:assembleDebug
```

Gradle 发行包可从 `https://services.gradle.org/distributions/gradle-8.14.3-bin.zip`
下载，解压到 `.tools/`。Windows 上建议将工程和 Gradle 缓存放在 ASCII 路径。
代理按本机环境配置，不将凭据或机器配置提交到 Git。

输出分别是 `deploy/build/outputs/apk/debug/deploy-debug.apk` 和
`native-fixture/build/outputs/apk/debug/native-fixture-debug.apk`。
Fixture 只用于测试，无需随 Deploy 安装。

## 使用与协作

打开 Deploy，授予分析 `/sdcard/` 文件所需的“所有文件访问”权限并启动服务。
独立 MCP 为 `http://127.0.0.1:4177/mcp`，使用 Deploy 看板提供的 Bearer token；
`/health` 只用于探活。Agent 提示词见 [docs/MCP_PROMPT.md](docs/MCP_PROMPT.md)。

- 仅 Deploy：可直接分析可读 ELF，无 root 或进程加载工具。
- 仅 PosEdMCP：原有特权服务保持可用，Deploy 工具报告辅助应用缺失或不可达。
- 两者共存：PosEdMCP 的分析工具经 AIDL 调用 Deploy；`deploy_load_native` 经
  PosEdMCP 确认策略，将已校验库送入一个精确指定的作用域应用进程。
- Deploy 的 `deploy_assemble` / `deploy_analyze_bytes` 支持小端 ARM64、ARM、Thumb、
  x86 和 x86_64，只生成/解码字节。Ghidra 反编译在私有工作进程执行，单次上限 30 秒。
- PosEdMCP 的 `deploy_disasm_memory` 读取目标进程 1–4096 字节并以只读 FD 经 AIDL 解码；
  `deploy_patch_memory` 经确认和旧字节校验替换一条 ARM64 指令，并恢复页权限。
- PosEdMCP 特殊设置提供 ColorOS / HyperOS 的“帮助Deploy”开关，针对已知后台清理路径。
  默认关闭；更新后的 system_server 模块通常需重启设备才能加载。

Fixture 的构造函数记录 PID，`deploy_fixture_probe()` 返回该 PID，
`deploy_fixture_answer()` 返回 42，`deploy_fixture_calculate(x)` 包含条件/算术，
`deploy_fixture_patchpoint()` 提供可恢复的 `mov x0,#7; ret` 测试入口。
Activity 不自行加载库或模拟桥接。
真实加载测试需要在 LSPosed/Vector 的 PosEdMCP 作用域中选择 Fixture；
详见 [集成测试步骤](docs/DEPLOY-NATIVE.md#device-verification)。

## 进度与历史记录

[DESIGN.md](DESIGN.md) 是当前架构与后续方向，
[MILESTONE-REPORT.md](MILESTONE-REPORT.md) 记录迁移前的真机验收。
`DESIGN-initial-shared-uid.md` 仅为已废弃方案的历史存档。
原始 `smoke-*.json`、日志、测试 `.so` 和生成样本留在本地并由 Git 忽略。
0.4.0 的验证记录见 [MILESTONE-0.4.md](MILESTONE-0.4.md)。五种汇编/缓冲区解码模式
均在 arm64 手机通过往返；这不代表其他架构设备上的执行验证。
一加 Android 16 的 Hans 保活修复、RikkaHub 连接和隔离回归见
[MILESTONE-COLOROS.md](MILESTONE-COLOROS.md)。

自用项目，尚未选择项目许可证。第三方库分别遵循其上游许可证。

Deploy 的独立前端以项目为工作单位：用户新建项目并导入 `.so` 私有副本，项目页包含
反编译、相对地址汇编、项目信息与文件列表三个 Tab。MCP 服务器配置位于 Settings，
而不是分析首页；`.so` 文件通过 `application/x-sharedlib` MIME 可由文件管理器交给 Deploy。
