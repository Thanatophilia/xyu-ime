# 阶段三连续候选滑动与图标滚屏交付状态

- 构建版本：0.3.61（61）；代码提交：70b90bbe7a6149617847c54f04ae9a6e8cc50f0b。
- CI：https://github.com/Thanatophilia/xyu-ime/actions/runs/43994680018，completed / success，已下载并复验签名 APK。
- APK：xyu-ime-0.3.61.apk；SHA256：15f88df9dd881066373c52ba7ff5393f2172c544c6251a4e3de0a3ec5bdfc871。
- app JVM 29、部署 JVM 套件 1、app Android 36 23、Rime Android 36 6，共 59 passed、0 skipped、0 failures；JUnit XML 为 0 errors；Lint 0 errors、15 warnings、6 hints。签名 v2、双 ABI、ZIP/ELF 16 KB 静态对齐、下载后校验通过。
- 用户纠正“上下翻页”只是功能表达，视觉须用图标，且必须保留原有手动划页。0.3.60 固定九项分页已撤销：完整筛选列表恢复连续 LazyVerticalGrid；上下宽口箭头滚动当前视口约 85%，保留少量重叠；回车也用折返图标，无可见文字。手势与按钮共享状态，手指拖动取消待完成按钮滚动，末端按需加载（4096 前缀上限）。
- 左侧音节、左下全部/单字、独立悬浮编码、候选页覆盖整个键盘保持。回车/删除会话校验和不发送/不删正文的规则不变。测试重新严格验证真实手动滑动必须移动候选，按钮从该位置继续滚动，之后仍可手动滑动，并验证筛选原始编号与窗口稳定性。
- 引擎/方案/资源/构建流程未改；realme 标准/抬高、跨应用、覆盖升级词频、长期使用和本轮实机手感仍待审查。0.3.51 显示效果认可不代表新交互已验收。
- README、候选页设计、任务书和手机验收清单均已同步纠正，纯文档归档使用 [skip ci]。
