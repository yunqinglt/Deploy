# Deploy 当前设计与进度

更新：2026-10-10。Deploy 与 Fixture 代码仓库：`E:/deploy`，分支 `main`。
来源：PosEdMCP `deploy-scaffold` 分支的 `7212643`；PosEdMCP 继续在 `E:/posedmcp` 维护。
最初的共享 UID 草案已归档为 `DESIGN-initial-shared-uid.md`；以下采用用户确认后的 AIDL 方案。

## 已确认的架构

- Deploy 独立 APK，包名 `dev.deploy`，独立 UID，无 root，不声明 sharedUserId。
- Android Java 服务 + JNI：LIEF 负责 ELF 分析，Capstone 负责反汇编，Ghidra 负责 AArch64 伪代码，Keystone 负责汇编。
- 三个入口共用 DeployCore：PosEdMCP 的 AIDL 代理、Deploy 自身 MCP（127.0.0.1:4177）、未来独立前端。
- 临时 Activity 仅作服务看板；本阶段保留页面内文案，不要求先提取 i18n XML。
- Deploy 直接分析可读取的 `/sdcard/` 文件；受保护目录的访问由 PosEdMCP 明确的权限路径负责。
- PosEdMCP 的独立 MCP 监听 127.0.0.1:8765，保留其既有 root 和模块能力。
- Deploy 仓库维护 `deploy/`、`native-fixture/` 及 `deploy-api/`；PosEdMCP 保留 API 契约副本，
  分别构建、运行时经 AIDL 连接。同步规则见 `deploy-api/README.md`。

## 已实现里程碑（0.4.0）

1. `deploy_analyze(op=disasm)`：Capstone 指令，按符号或 ELF 虚拟地址定位，输出字节、文件偏移、助记符、操作数和停止原因；支持 AArch64、ARM/Thumb、x86/x64。
2. `op=decompile`：原生 Ghidra 11.3.2，支持小端 AArch64 的符号/ELF 地址入口，输出 C 风格伪代码。在私有工作进程执行，指令预算和 30 秒截止控制。
3. `deploy_load_native`：仅在 PosEdMCP 提供。Deploy 用 LIEF 校验并快照 ELF，AIDL 返回只读 FD；PosEdMCP 校验 SHA-256、经原有 ConfirmationGate 确认，传入指定作用域进程；目标再校验字节并以 memfd + android_dlopen_ext 加载。
4. 运行时加载首版限定 arm64、8 MiB、普通作用域应用、精确 peer；可调用可选 `int64_t function(void)` 入口。构造函数会执行；不调用 JNI_OnLoad。非持久加载，进程退出即清，无自动 root 回退。
5. 独立测试 APK `dev.deploy.fixture` 与测试库用于行为验收，Activity 不自行加载库、不伪造框架桥。

6. `deploy_assemble` / `deploy_analyze_bytes`：五种小端指令模式的生成/解码，AIDL v2 与直接 MCP 一致。
7. PosEdMCP `deploy_disasm_memory`：目标内有限内存读取 → 私有只读 FD → AIDL → Deploy 解码。
8. PosEdMCP `deploy_patch_memory`：确认后对普通作用域进程的单条 ARM64 指令执行旧字节比较替换，刷新缓存并恢复页权限。
9. PosEdMCP 的 ColorOS / HyperOS“帮助Deploy”特殊钩子与原保护脚本独立登记和启停。

## 已有验收

- 先前两种隔离状态已实测：缺 Deploy 时 PosEdMCP 正常降级；缺 PosEdMCP 时 Deploy 可独立分析且无提权工具。
- 当前真机：小米 24129PN74C，Android 15，ADB 3972b041。
- 本轮直接 MCP 与 AIDL 两条分析路径通过符号/地址、长度边界、strlen 和无效输入检查。
- 首次真实运行时加载：构造函数记录 PID 4416，入口返回 4416，与目标 peer 一致；UID 10289，普通应用身份。
- 详细接口与复现步骤：[docs/DEPLOY-NATIVE.md](docs/DEPLOY-NATIVE.md)；
  独立构建见 [README.md](README.md)，Agent 提示词见 [docs/MCP_PROMPT.md](docs/MCP_PROMPT.md)。
- 原始分析与加载结果保存在本目录 `smoke-*.json`；不包含 bearer token。

## 后续方向

- 更多 Ghidra 语言、类型/导入原型和重定位语义恢复。
- 持久 native hook 及撤销生命周期、inline/PLT hook。
- LIEF 离线 ELF 改写、部署与回滚是独立工作流，本轮未纳入。
- 独立分析前端；将伪代码已有的工作进程隔离扩展至其他原生引擎。

历史 0.3.0 回归见 MILESTONE-REPORT.md。当前 0.4.0 结果见 MILESTONE-0.4.md：
80 项引擎检查、10 项运行时检查、39 项隔离回归通过；Fixture 返回值 7→9→7，
五种汇编/缓冲区解码模式已在 arm64 手机验收。ROM 特殊钩子有行为测试，
尚未在新版 system_server 中启用验证，也未在 ColorOS 实机验证。
