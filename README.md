# 絮雨输入法 · Xyu IME

以 Jetpack Compose 绘制键盘，自行实现 Android 系统输入法前端。阶段三已接入离线 Rime/librime 简体全拼。0.3.51 独立悬浮编码核心显示效果已获用户实机认可并归档；最新候选版 **0.3.60** 完成候选页本地分页与回归修复，云端验证通过，待用户实机审查。阶段三完整实机验收待完成。阶段二主体已验收，句首大写修正版仍待用户复测。

## 第一阶段 · 已完成

- 验收基线：**0.1.22**，代码提交 [`590c53c`](https://github.com/Thanatophilia/xyu-ime/commit/590c53c39388b83e78d1bfaa0c0202ffec5ae660)。
- 云端构建：[Android APK #22](https://github.com/Thanatophilia/xyu-ime/actions/runs/43832857853)，编译、5 项单元测试、Android Lint、Android 16 模拟器冒烟测试及 APK 签名校验通过。
- 实机验收：2026-11-16，用户在 realme RMX3852 / Android 16 上反馈测试均无问题，确认阶段一完成。
- 保留应用内文本、密码、数字、搜索四类试打输入框，作为后续版本的复测入口。
- 符号页布局调整已记录，待基础功能完善后再处理。

### 已交付功能

- Android 8.0（API 26）及以上；compile / target SDK 36。
- QWERTY 字母区 10 / 9 / 7 错列，Shift、删除位于第三行两侧。
- 底部始终七键：`[！﹫#] [123] [，] [空格] [。] [小地球] [回车]`。
- 支持英文直输、Shift、长按 Shift 大写锁定、长按删除连删、数字页、符号页。
- 小地球：点按切换内部语言，首版仅 English 时提示并保留英文；长按打开系统输入法选择器。
- 根据编辑框类型进入数字页，按编辑器约定执行搜索、完成、发送或换行。
- Compose 生命周期由输入法服务管理；使用标准输入法窗口，不添加悬浮窗。
- 无网络权限、不记录输入内容。

## 第二阶段 · 句首大写修正版待复测

用户在初次验收后补报 0.2.30 句号后未恢复大写的问题，原验收结论对此项重新打开。修正版 **0.2.32**（代码 `840cab7`）补充句末标点识别，保留普通空格不触发句首大写的规则。[Actions #32](https://github.com/Thanatophilia/xyu-ime/actions/runs/43869246346) 通过，包含实际键盘句首大写回归；待用户复测。下方 0.2.30 信息保留为历史记录。

- 交付版本：**0.2.30**，代码提交 [`423bcff`](https://github.com/Thanatophilia/xyu-ime/commit/423bcff056a9e4d0b30c3d3a441b787d321c3fcd)。
- 云端构建：[Android APK #30](https://github.com/Thanatophilia/xyu-ime/actions/runs/43862876985)，编译、14 项单元测试、Android Lint（0 错误）、6 项 Android 16 模拟器测试和 APK 签名校验通过。
- 使用与阶段一相同的固定签名，可覆盖安装 0.1.22。
- 实机验收：2026-11-16，用户在 realme RMX3852 / Android 16 上确认阶段二所需测试均无问题、功能正常，包括此前通过的四项核心编辑测试及后续自动大写等剩余场景。阶段二以 0.2.30 为完成基线。

- 顶部“编辑”入口：光标方向、行首/行尾、选择模式、全选、复制、剪切、主动粘贴。
- 系统 ICU 完整字符边界处理组合 emoji 和组合音标删除；依照设备 Unicode 数据。
- 独立自动大写与手动 Shift 状态，保留长按大写锁定。
- 邮箱、网址、电话、小数、负数、数字密码的初始页面、标点与回车适配。
- 保留四类核心试打框，并扩展更多测试类型、emoji 样例。试打内容只在内存，进入后台清空，密码不进入保存状态。
- 密码禁止复制/剪切；剪贴板只在用户明确点击粘贴时读取，无后台监听和历史记录。
- 原符号页布局与底部七键顺序保留。本阶段尚未接入 Rime 与中文。
- [阶段二手机验收清单](docs/acceptance-phase2.md)。

## 第三阶段 · 本地候选分页已验证，实机验收待完成

- 候选交付版本：**0.3.60**（versionCode 60），代码提交 [`fe19e09`](https://github.com/Thanatophilia/xyu-ime/commit/fe19e098be7029c3a81810a9e31443d2d97b27c6)，Actions [#60](https://github.com/Thanatophilia/xyu-ime/actions/runs/43983690194) 全部门禁成功。
- 展开页左侧是首音节筛选，左下固定“全部／单字”切换，不提供“词语”模式；右栏固定“删除／上翻页／下翻页／回车”。先按音节与字类筛选，再由界面每页显示最多 9 项，切换输入修订、音节或字类重置到第一页；上、下翻页只改变界面页码，不向 Rime 发送翻页按键，也不改变编码或原生高亮。到最后一个已加载本地页继续读取候选前缀，沿用 4096 项上限。候选点击保留原始索引和候选修订校验；回车按引擎真实高亮索引提交，越界则不执行，不发送搜索／发送等编辑器动作。删除仅编辑当前组合，保留会话保护与长按停手边界。
- 独立悬浮编码、固定顶部候选栏、候选页展开替换整个下方键盘（包括原底部七键）的结构保留；本轮只改 app 模块与回归测试，引擎、方案、资源和构建流程未修改。
- APK：[xyu-ime-0.3.60.apk](https://github.com/Thanatophilia/xyu-ime/actions/runs/43983690194)；SHA256：`c17e0fb4e1cc28b413ff3e88fa0a4e29272085b7d7d774d384cea51464b7c4b9`。
- 最新实际测试报告：app JVM 29、Rime JVM 部署事务测试入口 1、app Android 36 23、Rime Android 36 6，合计 59 passed、0 skipped、0 failures；已收录的 JUnit XML 均为 0 errors。Rime JVM 计数来自 Gradle HTML 报告。Lint 0 errors、15 warnings、6 hints。测试按真实页面收集候选的索引与文字，验证音节及单字筛选包含于“全部”；拖动只要求候选页保持展开、不选词、不提交、不露普通键；候选回车不预测词典文字，验证组合结束、已提交前缀保留且编辑器动作为空。
- 签名 v2、ZIP 16 KB 对齐、双 ABI 与各原生库 ELF LOAD 16 KB 对齐均通过；下载后校验和及原生 verifier 复验通过。固定签名证书 SHA256 为 `fa62895fa256fb286acb6546a1663331af42fa08abac4c1ce3fa1645ee90d7d0`。
- 历史：0.3.51 悬浮编码核心显示效果获认可；0.3.54 建立左侧音节栏，0.3.55 交付上一版操作栏；#56—#59 未通过完整门禁，不作为本轮安装版本。#60 修复按原生高亮推算页码导致的筛选／分页不一致。
- 状态：**新候选页布局、本地翻页及阶段三完整功能与兼容性均待实机审查**。仅既有 0.3.51 的核心显示方向已获得用户认可，不扩大该反馈的验收范围。
- 原始 artifact 的 READ-ME.md 是构建前文档快照，APK、BUILD.txt、校验和对应 0.3.60；请以本页及最新[手机验收清单](docs/acceptance-phase3.md)为准。
- [候选页设计与参考](docs/candidate-page.md)。

## APK 云端构建

在 [Actions → Android APK](https://github.com/Thanatophilia/xyu-ime/actions/workflows/android.yml) 下载成功构建的 `xyu-ime-版本号` artifact。解压后安装 `.apk`。同一签名可覆盖安装。

- 主分支代码推送、手动运行：单元测试、Android Lint、编译，然后用固定私密签名生成可安装 APK。
- PR：检查并编译调试版，不读取签名密钥。
- 安装包内附 SHA256、版本与提交编号，以及手机验收清单。
- 签名密钥通过仓库 Actions Secrets 提供：`XYU_KEYSTORE_BASE64`、`XYU_KEYSTORE_PASSWORD`、`XYU_KEY_ALIAS`。不要把密钥或口令提交到仓库。

安装后打开“絮雨输入法”，先启用、再选择絮雨，使用试打区检查。

详见 [第一阶段验收](docs/acceptance-phase1.md) 和 [Android 窗口适配](docs/android-window.md)。

## 目录

```text
app/src/main/java/org/thanatophilia/xyuime/
├── MainActivity.kt           # 启用引导与四类试打框
├── ime/
│   ├── XyuImeService.kt      # 系统输入连接、窗口与生命周期
│   └── EditorPolicy.kt       # 编辑框类型、回车动作判断
└── ui/KeyboardView.kt        # Compose 布局、手势、七键底栏
```

阶段三已包含离线 Rime、中文预编辑和候选词；引擎代码位于 `rime-engine/`。系统英文语言子类型之外，键盘内部通过小地球切换中英文。
