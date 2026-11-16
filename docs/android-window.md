# Android 输入法窗口适配

## 已确认的设备设置

realme RMX3852，Android 16 / API 36，当前逻辑 1080×2376 px / 480 dpi（约 360dp 宽），手势导航。

系统“输入法→键盘位置”包含标准、抬高。抬高页面写明：竖屏选择抬高，可开启右侧“快捷切换输入法”。本机诊断确认私有 secure 键 `keyboard_position` 的标准值为 0、抬高为 1。这只是设备调查结果，应用代码不读取、不修改该值。

## 实现原则

- 继承 InputMethodService，并通过 onCreateInputView 返回 ComposeView。
- 由标准 Theme.InputMethod、框架容器与导航控制器处理窗口落点、底部系统栏和 OEM 抬高区域。
- 输入视图宽度 MATCH_PARENT、高度 WRAP_CONTENT。Compose 限制首选高度并服从实际测量约束；不把输入视图设为铺满屏幕。
- 键盘子树不调用 imePadding，也不重复累加导航栏高度，不手动设置窗口 y 偏移或硬编码抬高值。
- 0.3.47 起竖屏内容首选约 286dp，横屏约 191dp；候选栏常驻预留 46dp / 34dp，键盘尺寸不因编码出现而变化。这些尺寸不含系统管理的底栏，高度不足时遵守宿主约束。
- 编码与候选栏分离：编码通过输入法自己的 PopupWindow 子窗口（TYPE_APPLICATION_SUB_PANEL）在键盘物理左上方悬浮显示，Compose 绘制。定位依据实际键盘边界，使用父 IME 窗口 token；不申请 SYSTEM_ALERT_WINDOW，也不更改主窗口大小或 onComputeInsets。
- 编码子窗口不获取焦点、不接收触摸、不会替用户消耗 Back。编码长度变化限制在屏幕安全宽度内；输入会话结束或隐藏键盘时在暂停 Compose 生命周期前同步关闭。候选栏仅保留候选词与展开/收回入口。
- Compose 的 LocalConfiguration/测量约束响应旋转与尺寸变化；不替换 Decor 的 WindowInsets 监听器。在键盘内容外侧用 windowInsetsPadding 消费一次剩余 systemBars 与 displayCutout 的水平/底部并集。该并集包含 Android 16 的 IME caption 底栏，使用 max 合并不做相加，已经由祖先消费的边距不会重复添加。
- 明确提供 LifecycleOwner、SavedStateRegistryOwner、ViewModelStoreOwner，处理显示/隐藏与销毁。编辑框切换或键盘隐藏会取消旧手势。
- onEvaluateFullscreenMode 返回 false，横屏保持普通键盘。
- 保留默认 onComputeInsets，向系统报告输入区覆盖关系。该方法是报告输出，不是“抬高请求”的输入回调。

Android 没有通用的“标准/抬高”模式查询回调。厂商如何调整框架容器需要实际 APK 在设备上验收，不能把框架默认行为等同于已完成所有 ROM 验证。若验收发现重叠，再基于未被框架消耗的真实 WindowInsets 修正，不依赖 OEM 私有键。

## 系统接入

- BIND_INPUT_METHOD 是服务的保护权限，由系统绑定；用户无需为键盘授予无障碍、悬浮窗或 Shizuku 权限。
- 英文在 input_method.xml 声明固定 subtypeId 与 isAsciiCapable。
- 短按小地球是内部语言动作；本阶段只有英文，提示而不改变大小写。长按通过 InputMethodManager.showInputMethodPicker 打开系统选择器。
- EditorInfo 决定数字页、密码直输和搜索/发送/下一项等回车动作。InputConnection 执行编辑，不在应用间缓存旧连接。

## 官方依据

- https://developer.android.com/reference/android/inputmethodservice/InputMethodService
- https://developer.android.com/reference/android/inputmethodservice/InputMethodService.Insets
- https://developer.android.com/reference/android/view/WindowInsets
- https://developer.android.com/reference/android/view/inputmethod/InputMethodSubtype
- https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/core/res/res/layout/input_method.xml
