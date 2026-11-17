# 阶段三五字候选与按键等宽交付状态

- 构建版本：0.3.64（64）；代码提交：466e87a4fa63e18a3591a193f7221816905379ce。
- CI：https://github.com/Thanatophilia/xyu-ime/actions/runs/44027771805，completed / success，已下载并复验签名 APK。
- APK：xyu-ime-0.3.64.apk；SHA256：5bc6852389e5f8d87ca4b2f8d8b31472813c637a6985f10f204a9e18d4e80b9b。
- app JVM 31、部署 JVM 套件 1、app Android 36 24、Rime Android 36 6，共 62 passed、0 skipped、0 failures；JUnit XML 为 0 errors；Lint 0 errors、15 warnings、6 hints。签名 v2、双 ABI、ZIP/ELF 16 KB 静态对齐、下载后校验通过。
- 普通键盘符号（!@#）键和回车键同宽同高。候选页左侧拼音区宽度与符号键一致，右侧四个功能键实际表面宽度与普通回车键一致并对齐，共用 KeyboardGeometry 宽度规则。候选网格固定五个单字列，长词按 Unicode 字数跨列（最多五列），不改成固定页面。
- 0.3.62 测试对可访问高度固定47dp的额外假设不适用，保留实测两键相等验证；0.3.63 发现左下切换触控略超出侧栏，0.3.64 将点击表面保持至少48dp并留在栏内，完整通过。
- 独立悬浮编码、左下全部/单字、完整候选页覆盖七键、连续手指滑动、上下图标滚动当前85%视口、筛选原始索引与提交安全都保留且回归通过。
- 引擎/方案/资源/构建流程未改；realme 标准/抬高、跨应用、覆盖升级词频和本轮实机手感待审查。0.3.51 核心显示认可不代表新布局验收。
- README、候选页设计、任务书及手机验收清单同步归档，纯文档提交使用 [skip ci]。
