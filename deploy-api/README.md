# Deploy 接口契约

维护源在 Deploy 仓库的 `deploy-api/`。PosEdMCP 保留同目录的源码副本，
使两个项目均可独立构建，无需固定的相邻目录、Git 子模块或另一个项目的构建输出。
当前契约对应 Deploy 0.3.0，提取自 PosEdMCP `7212643`。

需要同步的文件：

- `build.gradle.kts`
- `src/main/aidl/dev/deploy/api/IDeploy.aidl`
- `src/main/java/dev/deploy/api/DeployContract.java`

先在此仓库修改，再将这三个文件同步至 PosEdMCP，并分别构建 Deploy 和 PosEdMCP。
在两边的提交说明中记录契约变更。不要只修改其中一端的分析参数 schema 或 AIDL 方法。
Binder 方法顺序、参数方向及返回类型都属于兼容性边界；当前没有独立的契约版本协商，
破坏性修改需要同时升级两款 APK。

本地核对（在 `E:/deploy` 执行，差异会让 Git 返回非零退出码）：

```powershell
git diff --no-index -- deploy-api/build.gradle.kts ../posedmcp/deploy-api/build.gradle.kts
git diff --no-index -- deploy-api/src ../posedmcp/deploy-api/src
```

`prepareLibrary` 必须直接返回 `ParcelFileDescriptor`，以保留 Binder 的返回值关闭语义；
元数据通过 `out Bundle` 返回。不要将描述符改放到 Bundle 中。此接口只准备只读快照，
不执行 native 代码；加载及确认闸门属于 PosEdMCP。
