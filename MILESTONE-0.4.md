# Deploy 0.4.0 — 2026-10-10

本轮在 `E:/deploy` 实现 Ghidra 伪代码、Keystone 汇编及缓冲区反汇编，
在 `E:/posedmcp` 接入有限内存快照、单条 ARM64 指令修改及两个“帮助Deploy”特殊钩子。
两端保持独立 UID，AIDL v2 源码同步，Deploy 不获得 root 或目标进程读写权限。

## 交付与边界

- Ghidra 11.3.2：小端 AArch64 ELF，按函数名/ELF VA 生成 C 风格伪代码。
  1–20000 指令预算，默认 4096，私有工作进程单次 30 秒截止，输出最多 256 KiB。
  类型/原型为推断；其他架构、完整重定位及跨库原型恢复留待后续。
- Keystone 0.9.2：ARM64、ARM、Thumb、x86、x86_64；给定地址生成机器码，
  限指令/简单标签，最多 16384 字符、512 语句、4096 输出字节，不执行。
- Capstone：给定字节或文件反汇编。PosEdMCP 可在目标内读取 1–4096 字节，
  经私有只读 FD / AIDL 交给 Deploy。明确区分 ELF VA、buffer VA 和 runtime VA。
- PosEdMCP 单条补丁：精确普通作用域 peer、4 字节对齐、private RX 映射、
  expected 旧字节、插件确认、原子比较替换、指令缓存刷新、页权限恢复。
  不暂停线程，不锁定映射生命周期，无多指令补丁、trampoline、自动回滚或 root 回退。
- ColorOS/HyperOS 特殊钩子：各自名为“帮助Deploy”，保护 dev.deploy 及其冒号子进程；
  HyperOS 仅匹配既有已知 cleaner reason，手动强停保持可用。默认关闭。
  独立所有者身份避免和原保护脚本互相覆盖，普通 hook_clear 不移除特殊钩子。

## 已完成验证

设备：小米 24129PN74C，Android 15 / arm64，ADB `3972b041`。
Deploy、PosEdMCP、Fixture debug APK 均编译并安装。

| 验证 | 结果与证据 |
| --- | --- |
| 引擎/基础集成 | 80 项通过，`smoke-engines.json` |
| 五种指令模式 | 每种均经直接 MCP 与 AIDL 汇编/解码，含非零基址标签分支 |
| 真实 Ghidra 输出 | answer 函数返回 `0x2a`；calculate 函数恢复条件/算术；按名称与地址均通过 |
| 非法输入/预算 | 指示、隐藏指示、无效指令、错位/溢出、语句上限和反编译预算错误均被拒绝 |
| 运行时 | 10 项通过，`smoke-runtime.json`；PID 12455 / UID 10289 |
| 快照 | 运行时地址 `0x734a1927f8` 解出 `mov x0,#7; ret`，AIDL 返回时间/PID/UID 元数据 |
| 补丁及恢复 | `e00080d2` → `200180d2` → `e00080d2`，入口实测 7 → 9 → 7，页权限恢复成功 |
| 失败边界 | 旧字节不匹配未写入；无效内存及离线 peer 被拒绝 |
| 隔离 | 39 项通过，`smoke-engines-isolation.json`，结束后两款应用已恢复启用 |
| 仅 Deploy | 文件反汇编、伪代码、汇编、字节反汇编均可用，无提权工具 |
| 仅 PosEdMCP | 缺辅助应用明确报错；device_info、HTTP 服务及原有工具列表正常 |
| ROM 脚本/登记器 | PosEdMCP JVM harness：84 组包名/原因组合，以及共存、重装、嵌套记录、独立暂停、普通清除均通过 |
| 最终安装 | 重装最终 APK 后 5 项状态/打包检查通过，`smoke-final-state.json`；PosEdMCP 37 个工具、Deploy 4 个工具，Ghidra 资产可用 |

静态及隔离报告有重叠的基础检查，以上计数不能相加视作独立测试用例数。
所有原始报告只保存在本地并由 Git 忽略；token 只在测试进程内存读取，没有写入报告。
隔离使用临时禁用/恢复应用模拟缺失能力，不是真正卸载；本轮没有额外执行 root 命令。

## 调试结论与未验收范围

最初在每次反编译后杀工作进程，会与下一次 bind 竞争并触发系统重启退避。
现在正常结束后解除绑定，允许系统缓存进程；只有超时才终止工作进程。
连续名称/地址反编译和错误后续请求通过。尚未用特制重输入触发真实 30 秒超时或
native 崩溃，因此不把这些保护路径标为已真机触发验证。

首次运行时请求发生桥接超时，未标为成功。检查目标后重启测试进程并保持其前台，
完整重新验收通过。运行时请求沿用现有 ConfirmationGate；没有修改确认偏好。
成功写入已恢复并验证入口返回 7，未留下本次测试补丁。

没有重启手机，也没有自动开启新的系统钩子。新版 system_server 的实际拦截仍待
用户重启后启用检查，ColorOS 设备也尚未实测。现有回归证明脚本条件和共存管理，
不证明所有 ROM 版本的方法签名或所有后台回收路径。
跨架构编码/解码在 arm64 手机上完成，不代表其他架构 CPU 的执行测试。

## 复现与文档

- `tools/deploy-engines-smoke.py --output smoke-engines.json`
- `tools/deploy-engines-smoke.py --runtime-only --output smoke-runtime.json`
- `tools/deploy-smoke.py --isolation --output smoke-engines-isolation.json`
- PosEdMCP：`tests/run-hooks.ps1`

构建依赖固定提交、主机 SLEIGH 资产生成命令和许可证说明位于
`third_party/README.md`；原生许可文本随 APK 放在 `assets/licenses/`。
两端 `docs/MCP_PROMPT.md`、集成说明与 AIDL v2 契约文档已更新。
之前 0.3.0 的验收保留在 `MILESTONE-REPORT.md`，不覆盖历史记录。
