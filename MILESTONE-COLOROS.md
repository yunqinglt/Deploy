# 一加 ColorOS 回归 — 2026-10-10

设备：OnePlus PLR110，Android 16 / arm64；RikkaHub 2.5.6。
本轮更新并安装 PosEdMCP、Deploy 和 Fixture，用户启用系统钩子后重启设备。
后续修复通过更新 APK、刷新已启用的脚本生效，没有绕过 ConfirmationGate。

## 发现与修复

Deploy 进入后台约 4 秒后，Hans 仍会对其执行 Middle/Frozen 状态切换。
日志明确出现 `proxyService ... DeployAidlService`、网络/Binder 代理及
`freeze ... dev.deploy ... StrictMode-3|LcdOn`。仅拦截 Athena 的强停入口不足以保活；
此时 HTTP 不响应，AIDL 也可能报告辅助应用不可达。

ColorOS 的“帮助Deploy”现在保留 Athena 钩子，并增加
`OplusHansImportance.isImportantPkg(OplusHansPackage,int,boolean,String)`：
只对 `dev.deploy` 及冒号子进程返回重要应用，阻止进入冻结/代理状态。
该签名及重载委托关系由此设备的 `oplus-services.jar` 核实。
没有拦截低层 freezer 写入，以免留下已经进入的 Binder/网络代理状态。

同一特殊开关现在拥有两条方法钩子，因此关闭时按特殊所有者清除全部方法。
原 `athena` 与 `deploy_athena` 仍独立共存；普通 hook_clear 不清除它们。
JVM 回归覆盖多方法所有者的独立清除、84 组包名/原因和 6 组 Hans 包名条件。
ROM 签名不兼容时报告失败；如果第一条已安装、第二条失败，不得视为完整保护，
关闭该开关可统一清除已安装部分。

另外修复 Deploy 临时看板的异步启停状态：页面可见时每 500 ms 刷新，离开即停止刷新，
避免按钮和 running 字段一直保留调用 start/stop 前的状态。仍使用内联文字。
Android 16 拒绝 adb 直接启动私有服务，测试脚本改为看板启动并等待健康检查，未扩大导出范围。

## 实测结果

| 项目 | 结果与本地证据 |
| --- | --- |
| 引擎及 AIDL | 最终 APK 80 项通过，`smoke-oneplus-final-engines.json`；五种指令模式、标签基址、Ghidra 名称/地址分析及错误恢复 |
| Fixture 运行时 | 10 项通过，`smoke-oneplus-runtime.json`；PID 31714 / UID 10412，加载、8 字节快照、入口 7→9→7、旧值不匹配拒绝、无效地址及离线 peer |
| 两种隔离 | 39 项通过，`smoke-oneplus-isolation.json`；临时禁用模拟缺失，分阶段完成，最终两包恢复原 enabled=0 状态、HTTP 和 AIDL 可用 |
| 仅 PosEdMCP | 辅助应用缺失明确报错，HTTP、device_info 和原工具列表正常；未为此额外执行 root 命令 |
| 仅 Deploy | `/sdcard/` ELF 反汇编、伪代码、汇编和字节反汇编可用，无提权工具 |
| Hans 实际拦截 | 最终记录含 23 次 Deploy 判定改为 true，`smoke-oneplus-final-OplusHansImportance.json` |
| Athena 共存 | 实际登记包含原保护与 Deploy 两个所有者，`smoke-oneplus-final-OplusAthenaAmManager.json`；本轮未取得针对 Deploy 的真实 Athena 清理命中 |
| 亮屏后台 | 桌面/RikkaHub 共 90 秒、10 次全部成功，8–54 ms，`smoke-oneplus-home-liveness.json` |
| 熄屏状态采样 | 0–82.5 秒的 9 次采样均为 Dozing；90 秒时已 Awake。10 次请求全部成功，17–55 ms，同一 PID，`smoke-oneplus-screenoff-observed.json` |
| 认证 | 有效 Bearer 及大小写变体返回 200；缺失/错误 token 返回 401，`smoke-oneplus-http.json` |
| RikkaHub | 观察到四个工具；配置中的 token 与 Deploy token 在内存中比较一致。用户重新连接后确认无报错，`smoke-oneplus-rikkahub.json` |

隔离恢复期间一次采样超时，同时发生诊断命令延迟和 PosEdMCP 重连请求失败，
保留在 `smoke-oneplus-final-liveness.json`；未确定此次瞬时延迟的根因，不计作稳定通过。
随后两端健康检查及 AIDL 已恢复。恢复完成后再测 90 秒，10 次全部成功，12–63 ms，
同一 PID 23787；记录为 `smoke-oneplus-restored-liveness.json`。

测试计数含重叠基础项，不能相加。原始报告、ROM 副本和日志留在本地并由 Git 忽略；
报告不包含 Bearer token。Fixture 写入经过手机确认，已恢复并实测返回 7。
后续安装和界面切换遇到自动审批拒绝，最终服务启动及 RikkaHub 重连由用户手动完成。

## RikkaHub 结论与边界

标准配置为 Streamable HTTP、`http://127.0.0.1:4177/mcp`、请求头
`Authorization: Bearer <Deploy token>`。未修改认证逻辑，未放宽 token 校验。
已证实后台冻结导致 HTTP/AIDL 不可达，未发现请求头值不匹配；
没有捕获客户端最初错误对应的 HTTP 状态码，不能将笼统的“访问被拒绝”直接归为 401。
当前重连无报错由用户确认，未通过 RikkaHub 发送聊天或执行完整模型工具调用。

保活脚本不会开机启动 MCP；重启后需打开 Deploy 并点击 Start MCP service。
存在 Deploy 进程或 AIDL 可用也不等于 HTTP 已启动。应用禁用/恢复可能让 Android 解除
无障碍服务绑定，应在 PosEdMCP 看板检查；本轮没有写系统设置强行恢复该授权。
最终只读检查确认两端 `/health` 均为 ok，PosEdMCP 无障碍已启用，手机处于 Awake；
记录见 `smoke-oneplus-final-state.json`。

短时 USB 连接、定期请求采样不证明整夜待机、无请求空闲、省电模式和所有 ROM 版本的表现。
本轮未专门触发最近任务划卡清理，也未实测用户手动强停路径；这两条不能标为真机验收完成。
Ghidra 工作进程可被系统缓存，不承诺每个子进程永久免冻；已验证后台反编译请求可完成。

## 复现入口

- `tools/deploy-engines-smoke.py --serial <serial> --output smoke-engines.json`
- `tools/deploy-engines-smoke.py --serial <serial> --runtime-only --output smoke-runtime.json`
- `tools/deploy-smoke.py --serial <serial> --isolation --output smoke-isolation.json`
- `tools/deploy-liveness.py --serial <serial> --seconds 90 --output smoke-liveness.json`
- PosEdMCP：`tests/run-hooks.ps1`

构建：两端离线 debug 构建成功，APK 已安装；两端 MCP 提示词已补充 Hans、启动条件和认证排查。
本轮工作仍在两个仓库的工作区，未额外创建提交。
