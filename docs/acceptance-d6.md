# D6 · 覆盖升级与恢复：分批执行记录

更新：2026-11-21。**D6 分批执行中，尚未整体验收**。当前第二批候选 **0.3.149（149）**，代码 `42a230e4d41d787612e4c64bd5be75d5ad9f81cd`，[Actions #149](https://github.com/Thanatophilia/xyu-ime/actions/runs/44865969396) 整体成功；第二批详情见下节。首批 0.3.147 / `bcfcc96` 的 [Actions #147](https://github.com/Thanatophilia/xyu-ime/actions/runs/44784776028) 和历史证据继续保留。

**2026-11-21 用户确认 0.3.147“验证没问题，继续”**，据此记录首批普通实机回归通过。冻结的 D6 升级对照源仍为 **0.3.145**，不能将这次普通验证扩展为学习词跨进程、破坏性恢复或 release A/B 升级已通过。首批包没有改 UI、候选、符号、底栏、惯性或禁回弹。

## D6 前置门禁（本地实现，待 GitHub CI 复核）

针对 release A/B 前置风险，新增两类本地检查并接入工作流：

- `scripts/d6-release-preflight.py`：从 APK 读取包名、versionCode/versionName、v2 证书摘要、debuggable/testOnly 标志、双 ABI、三项 Rime asset SHA-256、DATA_VERSION 和 debug 探针缺席；与 `d6-baseline.json` 中冻结的 0.3.145 APK 哈希比对，拒绝包名/证书改变、降级、资源字节变化或不符合预期的 DATA_VERSION。9 项测试覆盖 A/B 元数据规则与拒绝情况。
- `scripts/d6-emulator-preflight.sh`：任何设备数据操作之前，要求手动 workflow、main 分支、显式 disposable 标记、单个 emulator serial、Android 36/x86_64/qemu 标志与 adb root；条件不符即退出。6 项假 adb 测试验证不安全条件会提前失败。
- `.github/workflows/d6-release-preflight.yml` 只下载并检查原始基线/候选 APK，**不安装 APK、不处理用户目录**；`.github/workflows/d6-emulator-preflight.yml` 只探测 CI 临时模拟器是否满足门禁，尚不执行数据实验。主工作流运行这 15 项纯逻辑测试。
- 直接对本地 0.3.145 与 0.3.149 APK 核对，三项 Rime assets 的 SHA-256 完全一致；两个 APK 自身 SHA256 分别与冻结清单中的 0.3.145 和验收记录中的 0.3.149 匹配。这只是文件身份/资源比较，不是 release 覆盖升级证据。

本地验证：15 项 Python 测试通过、`actionlint` 检查三个工作流通过、Python 编译检查、Shell 语法检查与 `git diff --check` 通过。第一次推送的主工作流 #150 在启动后 1 分 19 秒失败，公开摘要没有展示失败步骤的具体日志，故暂不把原因断言为已定位；已移除设备门禁脚本对 `rg` 的依赖，并将两个 Python 测试拆成独立 CI 步骤以便定位，推送后继续复核。当前没有可用 Android SDK `aapt`/`apksigner` 或能启动的 Java 运行时，尚未针对真实 APK 执行预检器；也未验证 CI 模拟器实际开放 `adb root`。因此以上均标记为**前置实现，等待云端复核**，不提升 P0/P3 进度结论。本次没有安装/覆盖 APK、读取 release 用户目录、执行 force-stop/清数据、构建或发布新 APK，也没有运行真实 emulator A/B。

---

## 第二批：真实原生学习记录、关闭重开与冷进程恢复（0.3.149）

**实机反馈：2026-11-21，用户确认 0.3.149“还行，没问题”**，记录为第二批普通实机回归通过。这不是数据库打开时强杀、实际 IME 服务重建、release 覆盖升级 A/B 或整个 D6 的验收结论；下方云端取证范围与未完成门槛不变。冻结升级对照源仍为 0.3.145。

本批只增加 debug/test 验证与发布包防泄漏检查，没有修改生产 `RimeEngine` 的请求、reset/close、shutdown 或词库版本逻辑，也没有改键盘/符号手感。

### 实际通过

- `RimeNativePersistenceTest` 在独立测试包 `org.thanatophilia.xyuime.rime.test` 的专用 files 目录执行，不访问 IME 的真实 `files/rime/`。
- 人工词 `synthetic-pinyin-01` 已确认不是内置固定词条；新的 user DB 中导出计数 **0**，选词学习三次后为 **3**。这是 librime levers 安全导出解析得到的实际 user DB 记录，不单靠候选排名或句子自动合成判断。
- 销毁 session 后调用真实 `NativeRime.shutdown()`，探针确认 ready=false；同进程 native 重开记录仍为 **3**。
- 脚本显式启动四个新的 OS 测试进程；核对 PID+进程启动时间均不同。第二进程打开前的关闭态用户文件摘要与首进程完全一致，打开后记录为 **3**，候选及 reading 正确。
- 第三进程先关闭 native，再快照真实 user DB；新学习使记录 **3→5**，关闭 native 后留下 pending。第四进程执行两次 recover，打开数据库前文件集/摘要均精确回到快照；冷启动后记录恢复为 **3**，候选及 reading 正确。
- 安全探针 `RimeTestProbe` 只存在 debug 源集；C++ 仅在 Debug 宏下编译。release 的两 ABI 原生库与所有 DEX 都检查不含探针。完整词库导出留在测试私有目录并由 RAII 删除，工件只存计数、进程身份、文件名和 SHA256。
- [可复核证据摘要](d6-native-evidence-149.json)；完整四阶段 JSON 与 instrumentation 输出在 Actions 的 `emulator-review/d6-native/`。

### 不能扩大的结论

- 这是使用真实 bundled assets、JNI 和 LevelDB 的 **debug 隔离测试**，不是 release 包覆盖安装或实际 IME 服务生命周期测试。
- 测试会主动销毁 session、调用安全导出和 finalize；虽然没有调用 `sync_user_data`，这些动作可能促成落盘。它只证明这种受控正常关闭路径，不证明未做上述操作、DB 打开时强杀仍无损。
- `am force-stop` 仅发生于 native 已 finalize 的阶段之间；不能写成“打开数据库时任意进程崩溃已验证”。
- 数据库打开后，LevelDB 的 CURRENT/MANIFEST/log 文件及时间元数据会合法变化。**字节摘要相等只在关闭后、重开前的比较窗口，以及恢复快照后一致性上断言**；跨正常重新打开用词条计数、reading 和 sentinel 验证逻辑保留，不能要求整个 user 目录永久逐字节相同。
- #148 首阶段 seed 已通过，但脚本依赖任务机未装的 rg 导致提前停止。#149 改用 Python 解析四阶段真实结果后全部重跑通过，未拿 #148 冒充完整验证。

### 云端与安装包

- 普通套件 app **80** + Rime **11** 项，零失败/错误/跳过；另外四次独立 instrumentation 均 `OK (1 test)`，不混进上述套件数量。
- 原 JVM 部署 12 场景及启动恢复 5 项继续通过；Lint **0 errors，22 warnings，9 hints**。
- APK `xyu-ime-0.3.149.apk`，提交 `42a230e`；SHA256 `7ef1f6d27298cd3d7951bf52672cb11780579e8489d740bfce308a8bd671f2d6`。
- 固定签名 v2/同证书、双 ABI、ZIP/ELF 16KB 静态检查通过；不等于 16KB 内核运行实测。
- 新包只供本轮普通覆盖回归，不要求用户主力机做强杀、清数据、降级等实验。

## 首批实际实现（0.3.147 历史记录）

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

P2 **部分完成**：0.3.149 已证明人工用户词实际写入、受控 finalize/reinitialize 和新的 OS 进程后可读，并补真实关闭态 DB 快照恢复；未完成 DB 打开时不主动导出/关闭的强杀、实际 IME 服务重建和 release 升级。

P3—P7 **尚未完成**：release 覆盖安装 A/B、完整 R01—R12 故障矩阵、未受控强杀/服务恢复和最终 release 测试。下一批按任务书补未受控进程终止与 release A/B 安装夹具，不把此次受控测试当作这些门槛已经通过。`RimeEngine.reset()/close()` 仍只销毁 session，不是全局 native shutdown。

## 普通实机回归已通过，以下保留为回归清单（0.3.149）

如安装 0.3.149，只做同签名覆盖，不卸载不清数据；抽查离线中文输入、重开键盘、原有常用学习词是否仍可选、符号最近记录与禁回弹手感。生产输入/关闭逻辑未改，本轮主要新增自动化证据，不要求用户重复破坏性测试。force-stop 数据实验、降级安装和清数据只在后续指定的可清空测试设备完成，不要求主力机配合。
