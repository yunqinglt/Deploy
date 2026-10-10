# Deploy — 设计大纲（v0）

> **历史草案，已废弃。** 共享 UID 与纯 C++ MCP 方案未采用；当前实现为独立 UID、
> Java 服务 + JNI，并通过 AIDL 与 PosEdMCP 协作。现行设计见 [DESIGN.md](DESIGN.md)。

> PosEdMCP 的辅助软件：一个跑在 Android 设备上的 **C++ 原生 MCP 服务器**，用 LIEF 提供
> ELF（尤其 Android native 库）的二进制符号分析。非 root、只读 `/sdcard`、经 HTTP+Bearer
> 暴露给手机上的 Agent，与 PosEdMCP **共享 UID** 分发。

---

## 1. 定位

- **是什么**：ELF 静态分析引擎，吃一个 `.so` 文件，产出结构/符号/字符串/反汇编/定位信息。
- **不是什么**：不做运行时 hook、不注入、不 root、不改目标进程——运行时那一侧归 PosEdMCP。
- **为什么是「辅助」**：PosEdMCP 的 probe 已经能列出目标应用加载了哪些库（`/proc/pid/maps` +
  sdkHints 表），但那是「文件名级」的浅分析；Deploy 负责「打开文件、读 ELF 内部」的深分析。

## 2. 架构总览

```
                 ┌────────────── 手机（Android 15 / 16，arm64）──────────────┐
                 │                                                          │
  Agent ──HTTP──►│  PosEdMCP    uid: dev.posedmcp                            │
                 │    root + 无障碍常驻（现有，不动）                         │
                 │      │ 同 UID：可互相拉起、直连、共享权限与私有数据        │
                 │      │                                                   │
  Agent ──HTTP──►│  Deploy      uid: dev.posedmcp（共享，非 root）           │
                 │    C++ MCP server：LIEF + capstone + cpp-httplib         │
                 │    只读 /sdcard/.../staging 里的 .so                      │
                 └──────────────────────────────────────────────────────────┘
```

三点接线关系（**混合**，三者都要）：

1. **Agent 直调**：Agent 同时注册 PosEdMCP 和 Deploy 两个 MCP 服务器，自己编排链路。
2. **PosEdMCP 内嵌后端**：PosEdMCP 也能把 Deploy 当后端调（同 UID，无跨应用 IPC 障碍）。
3. **回喂 native 注入**：Deploy 产出的符号→地址/偏移，作为 PosEdMCP 未来 native 注入的定位锚点。

## 3. 分发与打包（共享 UID 增量模组）

- Deploy 是**独立 APK**，与 PosEdMCP **同一把签名** + `android:sharedUserId="dev.posedmcp"`。
- 内容 = 最小 Android 壳（一个 `Service`）+ `jniLibs/arm64-v8a/libdeploy.so`
  （LIEF + capstone 静态链入，单文件交付）。
- **共享 UID 带来的白拿福利**：
  - 同 SELinux 域 → 记忆里「抽象 socket / ContentProvider / bindService 全被封」的跨应用墙直接消失。
  - 同权限集 → PosEdMCP 已有的外部存储权限 Deploy 自动继承（读 `/sdcard` 不用另开）。
  - 同私有目录 → PosEdMCP 可直接读 Deploy 的 token / 缓存。

### ⚠ 前置条件（需你确认）
- **PosEdMCP 的 manifest 现在有没有声明 `sharedUserId`？** 共享 UID 要求**两边都**声明同一个值 +
  同一把签名；单边声明等于没共享。若 PosEdMCP 没声明，需要给它补上，并且**改 UID 通常要卸载重装**
  （重装会破坏无障碍绑定，需重开，代价你已知悉）。
- **`sharedUserId` 已被 Google 弃用多年**（API 29 起）。在 Android 15/16 的侧载环境大概率仍生效，
  但需真机验证；**备选机制**：若 sharedUserId 失效，改用「动态功能模块 / split APK」（同包名，天然同 UID），
  或退化为「Deploy 由 PosEdMCP 以子进程形式拉起（继承 UID）」。

## 4. 进程与生命周期

- `Service` 拉起 C++ HTTP 服务器。
  - **拟定**：C++ 编成**可执行**放 assets，Service 解压后 `exec` 成独立进程——理由：LIEF 解析的是
    **不可信 ELF**，崩溃面大，独立进程隔离 crash，不拖垮壳进程。
  - 备选：JNI `System.loadLibrary` + 原生线程（打包更简，但一个 malformed ELF 崩掉整进程）。
- **Deploy 会被 ROM 杀**（非 root 无无障碍，`o-stop` / `OneKeyClean` 见记忆）。设计原则：
  - **无状态**：每次分析请求自成一体；进程死了重拉即可，无脏状态。
  - **可重拉**：PosEdMCP（同 UID、无障碍常驻）按需 `startService` 把它拉起来。
  - **结果可落盘缓存**：大库解析结果缓存到私有目录，进程重拉后读缓存，避免重解析。
- 端口：`127.0.0.1` 固定端口（或动态端口 + 注册给 PosEdMCP 中转给 Agent）。

## 5. MCP 工具面（初稿）

工具按四层分析能力划分，命名在 Deploy 自己的命名空间下（Agent 侧两个服务器天然区隔）：

| 工具 | 作用 | 关键参数 / 返回 |
|---|---|---|
| `elf_overview` | 单库总览 | header（class/machine/type）、entry、SONAME、NEEDED、build-id、段/节统计、加固特征（PIE/RELRO/NX/canary） |
| `elf_symbols` | 符号表 | dynsym+symtab；`kind=exported\|imported\|all`、`name_filter`、`demangle`；含 name/type/bind/visibility/value/size |
| `elf_relocations` | 重定位 + PLT/GOT | 导入符号的 reloc 类型与地址 |
| `elf_strings` | 字符串表 | `min_len`、`filter` |
| `elf_metadata` | 元数据与启发式 | build-id、note、.comment、Android 版本、UPX/OLLVM/壳痕迹 |
| `elf_disasm` | 反汇编（capstone，arm64） | `symbol\|address` + `count`；符号级或地址级 |
| `elf_resolve` | **hook 定位** | 给一个符号 → `{name, va, file_offset, size, type, bind}`，供 native 注入 |
| `list_libs` | 枚举 staging 目录 | `dir` + `pattern`，与 probe 库列表衔接 |
| `analyze` | 一键综合报告 | overview+symbols+strings+metadata 摘要，**省 tokens**（对应 PosEdMCP「给模型省 tokens」原则） |

## 6. 与 PosEdMCP 的三种配合

1. **紧耦合工作流**（定位→拉库→解析）：
   `probe_snapshot` 给库列表 → Agent 请 PosEdMCP 用 root 把目标 `/data/app/*/lib/*.so`
   拷到 staging → Deploy `analyze` staging 里的文件。
   **staging 约定**（实现时定）：`/sdcard/Android/media/dev.deploy/staging/<pkg>/`
   （posix 可读、世界可读；具体路径与 scoped-storage 读权限在实现时敲定）。
2. **独立按路径分析**：Agent 直接给 `/sdcard/.../foo.so`，Deploy 就地解析，不依赖现场信息。
3. **回喂 native 注入**：`elf_resolve` 输出**稳定 JSON schema**（`name → {va, offset, size, ...}`），
   等 PosEdMCP 的 native 运行时注入落地后直接当定位锚点。

## 7. 技术选型

| 组件 | 选型 | 说明 |
|---|---|---|
| ELF 解析 | **LIEF**（C++，NDK 交叉编译，静态链） | 需从源码用 NDK 编译，非 pip 包；工程量集中在这一步 |
| 反汇编 | **capstone**（C，交叉编译，静态链） | LIEF 本身不反汇编 |
| HTTP | **cpp-httplib**（header-only） | 轻量、易交叉编译 |
| JSON | **nlohmann/json**（header-only） | MCP JSON-RPC 2.0 |
| 构建 | **CMake + NDK toolchain** | 产出单 `libdeploy.so`（或 assets 里的可执行） |

## 8. 安全 / 权限

- **非 root**：只读 `/sdcard`（含继承 PosEdMCP 的存储权限）；需要读私有目录（`/data/app`、`/system/lib`）
  时**不做**，交给 PosEdMCP 的 root 弹窗链路先 staging。
- **Bearer token**：Deploy 自持 token（存私有目录），**经 PosEdMCP 的 Status 页 / `instructions`
  统一发给 Agent**（同 UID，PosEdMCP 能直接读到）——Agent 一处拿两个 token。
- **解析不可信 ELF 的健壮性**：LIEF 抛异常/崩溃要隔离（见 §4 的 exec 独立进程），坏文件返回明确错误，
  不给「看起来像空表」的静默失败（这条在 PosEdMCP 上反复咬过人）。

## 9. 里程碑（建议开发顺序）

1. **骨架**：能交叉编译、能在设备上跑起来的 C++ MCP 骨架（HTTP + `tools/list` + 一个 echo 工具）。
2. **LIEF 接入**：`elf_overview` / `elf_symbols` / `elf_relocations` / `elf_strings` / `elf_metadata`。
3. **capstone + 定位**：`elf_disasm` + `elf_resolve`。
4. **打包 + 联动**：shared-UID APK、保活、staging 约定、PosEdMCP 端联动与回喂接口。
5. **端到端验证**：两台机器（PLR110 / 小米），三种配合各验一遍。

## 10. 待确认清单

- [ ] PosEdMCP manifest 是否已声明 `sharedUserId`；是否接受「补声明 + 卸载重装」的代价。
- [ ] 共享 UID 机制选型：`sharedUserId`（既定）vs split/动态功能模块 vs 子进程继承 UID（备选）。
- [ ] 原生形态：assets 可执行 `exec`（拟定）vs JNI 进程内。
- [ ] 保活方案：PosEdMCP 按需拉起（拟定）是否够，还是要 Deploy 自己前台服务 + 常驻通知。
- [ ] 首版是否就上 capstone 反汇编，还是先交付结构/符号/字符串、反汇编放第二里程碑。
