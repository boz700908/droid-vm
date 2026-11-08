# 宿主 App 的无障碍契约

宿主 App 的目标用户里有人用读屏、有人只用键盘/手势、有人低视力。这个文件
写的是**代码必须遵守的规则**，不是「我们很重视无障碍」这种话。

## 为什么是上游 `dist/umapp` 上加一层

上游已经有宿主端的正确骨架，而且它的结论全是踩出来的、重写一遍只会重新踩：

- App 跑在 `untrusted_app` 域，W^X 禁止执行自己写出来的文件 → 内核和 stub
  只能以 `lib*.so` 装进 APK，从 `nativeLibraryDir` 跑；
- UML 默认从匿名 memfd exec stub，这里被拒 → 必须 `stub_exe=<libdir>/libstub.so`；
- 沙箱里没有 `/tmp` → `TMPDIR` 指 cache 目录，`HOME` 指 files 目录；
- App 进程继承 zygote 装的 seccomp 过滤器 → 内核可能 SIGSYS 死掉，所以要能自检；
- 不用 Gradle：`aapt2` + `javac` + `d8` + `apksigner`，十来秒出包，也不用在
  这台 2 核机器上跑 Gradle。

我们只改**界面与播报**，不动上面这些。

## 界面规则

| 规则 | 原因 |
|---|---|
| 一切竖排、一行一个控件、占满宽度 | 上游是横排 + `HorizontalScrollView`，读屏靠焦点遍历还能摸到被挤出屏幕的控件，低视力用户摸不到，也没法安全地横向滚动 |
| 每个可点控件的 `minHeight` ≥ 48dp | 触摸目标下限；也是「手指点在哪个」和「焦点在哪」能对上的前提 |
| 文字不用缩写，说清后果 | `seccomp` / `net` / `usb` 三个字对读屏用户不是线索；改成「seccomp 系统调用过滤（建议开启）」「给 guest 联网（passt）」「USB 直通（需要先插好适配器）」 |
| 标签与输入框用 `setLabelFor` 绑定 | 读屏会把两者念成一项，点标签能把焦点送进输入框 |
| 标题用 `setAccessibilityHeading` | 读屏的「按标题导航」才有东西可跳 |
| 正文 ≥ 16sp，内核输出 ≥ 14sp | 低视力用户放大字号后仍要读得下（上游是 10sp） |
| 状态不只靠颜色 | 状态行的文字本身就是状态；颜色只是附加信息 |

## 播报规则（和 `tts-interrupt-rules` 一致）

1. **状态变化挂 live region（polite）**，由读屏自己念，不抢焦点、不打断别的朗读。
   状态行只有一句：`状态：正在启动…` / `状态：内核已退出，退出码 139`。
2. **内核输出绝不逐行播报**。一次启动几十行，逐行念等于把人淹掉。输出的
   live region 设成 `NONE`，只在用户明确按键时才念——那是对按键的回答，
   按规则可以替换正在进行的朗读。
   读输出由两颗按钮成对承担：**「读最新三行」** 与 **「读更早三行」**。
   只给一个「最后三行」是不够的：几十行输出里想听中间那段，用户会彻底卡住。
   每按一次念三行、并且先报位置（`第 7 到 9 行，共 41 行`），否则用户不知道
   自己在哪，翻两次就迷路。位置以「非空行」计数，之后新增的输出不会打乱它。
3. **不自己起 TTS**。走 `announceForAccessibility`：用户关掉读屏就不出声。
   这是「无障碍优先」，不是「无论如何都出声」。
4. 一次性回答（「已复制全部输出，共 N 个字符」「内核未运行，先按启动」）只说一句，
   不改状态行，免得状态被临时消息覆盖。
5. 退出码必须念出来：「退出了」和「退出码 139」是两件完全不同的事，
   而后者只存在于盲用户看不见的那行输出里。

## 代码落点

- `app/src/org/droidvm/host/MainActivity.java` —— 界面与全部播报；
  无障碍层集中在文件末尾的 `setStatus` / `setRunning` / `announce` /
  `logLines` / `readNewest` / `readOlder` / `announceChunk` / `copyAll` / `dp`。
- `app/res/xml/device_filter.xml` —— USB 直通的 VID/PID（十进制），
  与 `USB_VENDOR` / `USB_PRODUCT` 必须一起改。
- `app/build-apk.sh` —— 无 Gradle 打包。
- `.github/workflows/apk.yml` —— 取最近一次成功的内核产物装进 APK。

## 还没做（下一步）

- 启动过程的**分步播报**（解包根盘 → 起内核 → 出现 shell）：现在只有
  「正在启动…」，中间的「解包 1024 MB」只在输出里。需要把 `unpackAsset`
  的进度按 10% 一档挂 live region，不是每 MB 一次。
- 命令历史的上下键：`EditText` 在 TalkBack 下拿不到方向键，需要一个
  「上一条命令」按钮做等价操作。
- 崩溃/ANR 时的自述：`Thread.setDefaultUncaughtExceptionHandler` 里把最后几行
  输出念出来，否则读屏用户拿不到任何线索。
