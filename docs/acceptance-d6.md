# D6 · 覆盖升级与恢复：分批执行记录

更新：2026-11-21。**D6 已启动但远未完成**；首批候选 **0.3.147（147）**，代码 `bcfcc96c0b06c526ee930a9e0b1b0d2de017640f`。[Actions #147](https://github.com/Thanatophilia/xyu-ime/actions/runs/44784776028) 整体成功。

已实机验收的安全基线仍是 **0.3.145**；首批包不新增 UI，不更改候选、符号、底栏、键盘几何、默认惯性或禁回弹设置。

## 本批实际实现

1. `RimeDataVersion` 统一校验资源版本；在资源安装、事务启动、恢复读取和提交写入时应用。非法字符串在文件和 native 回调前失败，生产数据版本保持不变。
2. 保留原有 `initialize() → recover() → begin()` 顺序，不跳过启动恢复。新增独立用例确保 pending 在下一次启动前回滚、committed 快照只清理不覆盖新用户文件、preparing/version.tmp 清理幂等。
3. 资源安装器保留原公共调用，内部引入输入流 seam。测试可注入损坏内容/读取异常，生产路径继续读取 APK assets 并核对原 SHA-256；没有故障按钮、可调用的外部测试入口或用户数据库导出。

## 通过的证据及其边界

| 层次 | 实际结果 | 能证明 / 不能证明 |
|---|---|---|
| P0 静态基线 | [基线清单](d6-baseline.json) 记录旧包 SHA、签名证书、包名、版本和来源提交 | 固定比较来源；不代表在测试设备实际安装过两个 release 包 |
| JVM 原部署套件 | 1 个 JUnit 包装用例，内部 12 个普通文件场景通过 | 事务、首次目录、模拟资源升级、合成用户文件保留；不是 LevelDB 学习词落盘 |
| JVM 启动恢复 | `RimeDeploymentStartupTest` 5 项通过 | pending/committed/preparing 入口恢复、非法目标及提交拒绝；不是 Android 进程强杀 |
| Android 资源安装 | `RimeResourceInstallTest` 新增 5 项全部通过 | 真实 APK assets、真实 Android 文件系统中的独立缓存目录：同版复用、checksum 失败、读取中断、重试、版本目录隔离和非法版本拒绝 |
| Android 既有 Rime | 6 项通过，Rime 总计 **11 项** | 中文引擎既有闭环回归；原学习句测试仍只证明同引擎内复用 |
| Android app | **80 项**通过 | 现有输入/编辑/候选/符号/无障碍回归不退化 |

全部 Android 用例 0 failures / 0 errors / 0 skipped；Lint 0 errors（22 warnings / 9 hints）。JVM 报告在 Actions 工件 `rime-engine/build/reports/tests/testDebugUnitTest/`，资源测试报告在 `rime-engine/build/outputs/androidTest-results/`。

## 首轮遗漏与纠正，不掩盖历史

- #146（`fe80154`）虽构建成功，静态复查发现增加版本校验时误删了 `initialize()` 内原有 `recover()`。因此 **0.3.146 不作为交付候选，不建议安装**。当时单测没有覆盖直接从启动入口恢复 pending；全绿不能证明该场景正确。
- #147 已恢复该调用，并新增入口级回归。如果再次去掉 `recover()`，pending/committed/preparing 启动用例会失败。已与 0.3.145 基线逐项复核生产差异。
- 本地 JVM 因 Android 沙箱可执行内存限制未能启动，未将它计作测试通过；以上通过结论均来自 CI 报告。

## APK 校验

- 候选：`xyu-ime-0.3.147.apk`，versionCode 147；packageName `org.thanatophilia.xyuime`（构建配置）。
- SHA256：`0b1afb8949796a065df917d505b14568d0e4487b8b5d9be11b597132171bd0b3`。
- 固定签名证书 SHA256：`fa62895fa256fb286acb6546a1663331af42fa08abac4c1ce3fa1645ee90d7d0`，与 0.3.145 一致，APK Signature Scheme v2 通过。
- arm64-v8a / x86_64 和资源 native verifier 通过；ZIP/ELF 16 KB 静态检查通过。没有据此宣称 16 KB 内核运行已通过。
- 未在本轮对用户手机进行安装、清数据、降级或强杀。APK 只供本批普通回归，不是 D6 最终发布验收。

## 尚未通过的门槛

P0 **部分完成**：静态身份锁定；仍缺指定可清空设备的旧 release 包重复安装输入、升级 fixture 和完整环境证据。

P1 **部分完成**：文件系统与资源失败路径已补证；仍缺 F01/F02/F04/F05 完整真实部署生命周期、中文失败时英文降级/主动重试、冷进程二次启动证据。

P2—P7 **尚未完成**：原生学习词可区分的持久化证据、未主动 sync 的强杀与受控正常关闭分别验证、release 覆盖安装 A/B、完整 R01—R12 故障矩阵、真实进程/服务恢复和最终 release 测试。

下一批先完成隔离引擎的冷初始化/关闭证据与 P1 生命周期测试，再进入学习词 DB 查询。不得将 `reset()`、`close()` 当前仅销毁 session 的行为当作关闭整个 native 引擎，也不在数据库打开时直接复制/散列 LevelDB。

## 本批人工抽查（可选，不等于 D6 完成）

如安装本候选，只做同签名覆盖安装，不卸载不清数据；抽查离线中文输入、重开键盘、符号最近记录与禁回弹手感。破坏性故障注入、force-stop 数据实验和降级安装都应在指定的可清空测试设备完成，不要求主力机配合。
