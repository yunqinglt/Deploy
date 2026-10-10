# Deploy 0.3.0 里程碑验收

日期：2026-10-10。仓库：E:/posedmcp，分支 deploy-scaffold。

> 本文保留迁移前的 0.3.0 验收事实与提交来源。同日整理后，Deploy、Fixture 及契约维护源
> 已迁至独立仓库 `E:/deploy`；后续代码和提示词以新仓库为准。本次整理不新增运行时功能，
> 不将此前未验证的架构标记为通过。原始 JSON/日志仅保留在本机，不纳入源码提交。

## 交付

- LIEF + Capstone：deploy_analyze 的 disasm 操作，支持符号或 ELF 虚拟地址。
- 两个 MCP 入口共用参数契约；decompile 返回明确的 DECOMPILER_UNAVAILABLE。
- PosEdMCP 独有 deploy_load_native：LIEF 校验快照 → AIDL 只读 FD → 原确认闸门 → 精确作用域进程 → memfd + android_dlopen_ext。
- 8 MiB 限制、SHA-256 两端校验、进程级加载去重；无 root 回退，无持久 native hook。
- 原有独立部署边界保留，Deploy 无加载/提权工具。
- 修复 Binder 的描述符所有权：直接返回 PFD，避免放入 Bundle 丢失 PARCELABLE_WRITE_RETURN_VALUE。

## 真机结果

小米 24129PN74C / Android 15 / arm64 / ADB 3972b041。

- 最终代码的加载结果：PID 8518、UID 10289；构造函数记录的 PID 由入口返回 8518，与目标一致。
- 复用同一库和句柄，另一个入口返回 42；缺失入口明确报告库已经加载；离线 peer 在执行前拒绝。
- 直接 MCP 与 AIDL 的 28 项分析检查通过：符号与地址反汇编、窗口/数量边界、strlen、错误输入及伪代码接口。
- 最后一次隔离测试共 36 个记录全部通过：仅 PosEdMCP 时 companion 缺失不影响原工具；仅 Deploy 时可反汇编且不提供提权工具；恢复后两个服务及 AIDL 正常。
- 原始记录：smoke-final.json（最终 FD 契约的真实加载）、smoke-isolation-final.json（完整隔离回归）。前者也记录首次隔离尝试的前半段；完整隔离结果以后一文件为准。
- 设备启用状态已恢复。测试应用及库保留供复测，未修改其他作用域应用的运行行为。

## 尚未验证的范围

ARM/Thumb/x86/x64 解码器已编译，补充样本也已生成，但设备补测首次超时；后续命令被自动审批拒绝，即使用户重新授权仍返回 blocked by policy。未将它们标为真机通过。

本轮验证的是 native 库加载与入口执行。未实现 C 伪代码、持久 native hook、inline/PLT hook、LIEF 离线改写部署，且不调用 JNI_OnLoad。

## 复现与文档

迁移后见本仓库 [docs/DEPLOY-NATIVE.md](docs/DEPLOY-NATIVE.md) 和
[tools/deploy-smoke.py](tools/deploy-smoke.py)。
当前设计：E:/deploy/DESIGN.md；原始共享 UID 草案：DESIGN-initial-shared-uid.md。

构建与检查：三个 debug APK 均成功构建；Deploy 和 PosEdMCP 的 lintDebug 任务完成；Deploy 无 Error/Fatal，PosEdMCP 报告两个既有错误：local.properties 的 Windows 冒号转义，以及原有 app_name 缺少 zh 翻译。新增类没有 lint 报告。临时看板文案按用户要求未提取 i18n。Lint 依赖首次直连 TLS 失败，使用已授权的 127.0.0.1:10808 代理后完成。git diff --check 通过。

本地提交：7212643（deploy-scaffold，未推送）。

## 同日仓库整理验收

- Deploy、Fixture 与 API 契约维护源迁入 `E:/deploy`，形成独立 Gradle 工程和 Git 仓库。
  提取时 20 个模块源码/构建文件与原仓库逐字节一致，无应用逻辑变更。
- PosEdMCP 仅保留 `app` 与契约副本；确认副本一致，两个工程不相互引用源码路径。
- 新仓库 `:deploy:assembleDebug :native-fixture:assembleDebug` 成功（2 分 29 秒）；
  PosEdMCP `:app:assembleDebug` 成功。使用既有 Gradle 依赖缓存，新工程的源码与
  LIEF/Capstone 均从新目录读取。此项是构建验证，没有安装 APK 或执行新的实机测试。
- 分别整理两款服务器的 MCP 提示词，核对当前工具、参数、地址含义、错误与隔离行为；
  PosEdMCP 文档覆盖当前 33 个工具，Deploy 文档只列自身两个工具。
- 文档相对链接和 JSON 示例已检查，迁入的集成脚本通过 Python 语法检查。
