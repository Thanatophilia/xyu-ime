# 阶段三候选本地分页交付状态

- 构建版本：0.3.60（60）；代码提交：fe19e098be7029c3a81810a9e31443d2d97b27c6。
- CI：https://github.com/Thanatophilia/xyu-ime/actions/runs/43983690194，completed / success，已下载校验签名 APK。
- APK：xyu-ime-0.3.60.apk；SHA256：c17e0fb4e1cc28b413ff3e88fa0a4e29272085b7d7d774d384cea51464b7c4b9。
- app JVM 29、Rime JVM 部署事务测试入口 1、app Android 36 23、Rime Android 36 6，合计 59 passed、0 skipped、0 failures；已收录的 JUnit XML 均为 0 errors。Rime JVM 计数来自 Gradle HTML 报告。Lint 0 errors、15 warnings、6 hints。
- 展开页左侧是首音节筛选，左下固定“全部／单字”切换，不提供“词语”模式；右栏固定“删除／上翻页／下翻页／回车”。先按音节与字类筛选，再由界面每页显示最多 9 项，切换输入修订、音节或字类重置到第一页；上、下翻页只改变界面页码，不向 Rime 发送翻页按键，也不改变编码或原生高亮。到最后一个已加载本地页继续读取候选前缀，沿用 4096 项上限。候选点击保留原始索引和候选修订校验；回车按引擎真实高亮索引提交，越界则不执行，不发送搜索／发送等编辑器动作。删除仅编辑当前组合，保留会话保护与长按停手边界。
- 未修改引擎或构建流程；候选页布局与本地分页的用户实机审查、realme 标准／抬高键盘位置、跨应用、arm64 真机及升级词频、长期使用仍待完成。0.3.51 核心显示认可不代表新交互已通过实机验收。
- README、candidate-page 与 acceptance-phase3 已按当前交互更新；交付文档使用 [skip ci] 提交。
