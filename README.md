# 絮雨输入法 · Xyu IME

以 Jetpack Compose 绘制键盘、自行实现 Android 系统输入法前端。阶段三已接入离线 Rime/librime 简体全拼，正在完成云端回归与候选 APK 交付，用户实机验收待完成。阶段二主体已验收，句首大写修正版仍待用户复测。后续再推进布局优化与键盘自定义。

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

本阶段只有英文语言子类型，尚未包含 Rime、中文预编辑和候选词。中文引擎与词库将在英文键盘实机验收后加入。
