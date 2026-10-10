# Deploy

Deploy 是 Android 上的 ELF 符号分析软件及 MCP 服务器。LIEF 负责解析，Capstone
负责指令级反汇编。当前版本为 0.3.0，应用运行于独立 UID，不需要 root。
当前 Activity 是临时服务看板；独立分析前端与 C 伪代码引擎留待后续开发。

## 仓库布局

| 路径 | 职责 |
| --- | --- |
| `deploy/` | `dev.deploy` APK：DeployCore、JNI、独立 MCP 与 AIDL 服务 |
| `deploy-api/` | AIDL 与 MCP 分析参数契约的维护源 |
| `native-fixture/` | `dev.deploy.fixture` 测试 APK 及测试库 |
| `docs/MCP_PROMPT.md` | 给连接 Deploy 的 Agent 使用的提示词 |
| `docs/DEPLOY-NATIVE.md` | 跨应用分析、native 加载契约与真机验收步骤 |
| `tools/deploy-smoke.py` | 需要两款应用的集成测试，包含可选加载和隔离测试 |
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
[third_party/README.md](third_party/README.md) 准备固定版本的 LIEF 与 Capstone。
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

Fixture 的构造函数记录 PID，`deploy_fixture_probe()` 返回该 PID，
`deploy_fixture_answer()` 返回 42。Activity 不自行加载库或模拟桥接。
真实加载测试需要在 LSPosed/Vector 的 PosEdMCP 作用域中选择 Fixture；
详见 [集成测试步骤](docs/DEPLOY-NATIVE.md#device-verification)。

## 进度与历史记录

[DESIGN.md](DESIGN.md) 是当前架构与后续方向，
[MILESTONE-REPORT.md](MILESTONE-REPORT.md) 记录迁移前的真机验收。
`DESIGN-initial-shared-uid.md` 仅为已废弃方案的历史存档。
原始 `smoke-*.json`、日志、测试 `.so` 和生成样本留在本地并由 Git 忽略。
当前只验证了 AArch64 真机路径，其他已编译解码架构仍待实机验证。

自用项目，尚未选择项目许可证。第三方库分别遵循其上游许可证。
