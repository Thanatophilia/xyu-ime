# 絮雨输入法 · Xyu IME

以 Jetpack Compose 绘制键盘、自行实现 Android 系统输入法前端。当前阶段先验证英文键盘和系统窗口适配，下一阶段再接入 Rime/librime。

## 第一阶段

- Android 8.0（API 26）及以上；compile / target SDK 36。
- QWERTY 字母区 10 / 9 / 7 错列，Shift、删除位于第三行两侧。
- 底部始终七键：`[！﹫#] [123] [，] [空格] [。] [小地球] [回车]`。
- 支持英文直输、Shift、长按 Shift 大写锁定、长按删除连删、数字页、符号页。
- 小地球：点按切换内部语言，首版仅 English 时提示并保留英文；长按打开系统输入法选择器。
- 根据编辑框类型进入数字页，按编辑器约定执行搜索、完成、发送或换行。
- Compose 生命周期由输入法服务管理；使用标准输入法窗口，不添加悬浮窗。
- 无网络权限、不记录输入内容。

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
