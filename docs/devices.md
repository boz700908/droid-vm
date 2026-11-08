# 一台手机有的东西，我们怎么补

分三层看，别混着谈：

1. **内核侧** —— 没有真实外设，就在软件里造出设备节点（`kernel/config/devices.config`）。
2. **HAL 侧** —— Android 用户态的那层 `.so`／AIDL 服务，多半要自己写；镜像里只有 AOSP 给的默认件。
3. **数据源** —— 设备背后要有真实数据，就由手机本体经隧道回灌。

三层齐了，功能才算「有」。

## 清单

| 设备 | 内核侧 | 用户态（HAL） | 数据源 | 状态 |
|---|---|---|---|---|
| 显示屏 | 不需要 | `gralloc.redroid` + `hwcomposer.redroid` 已有，`surfaceflinger` 在 | 无 | 拉帧即可：`screencap` raw → 再上 H.264。**不走 fb0** |
| 触摸 / 按键 | `uinput` ✅ | 直接给 Android 一个 evdev 节点即可 | 手机触摸 | 待隧道 |
| 扬声器 | `snd-aloop` ✅ | **自写 tinyalsa 版 primary HAL**（镜像里那个 52KB 的 `audio.primary.default` 是空壳，一个 ALSA 符号都没有） | guest → 手机 `AudioTrack` | HAL 待写 |
| 麦克风 | `snd-aloop` 采集端 ✅ | 同上 | 手机麦克风 → 写进环回 | 同上 |
| 振动 | `input-ff-memless` ✅ | `vibrator.default.so` 在，需要桥到隧道 | guest → 手机振动器 | 待隧道 |
| 电池 / 充电 | `test_power` ✅ | Android **health HAL** 读 `/sys/class/power_supply`，需自写或用 AOSP 示例件 | 可映射手机真实电量 | 内核已备 |
| 摄像头 | `vivid` ✅ 产 `/dev/video*`（测试图） | AOSP 的 external camera HAL，需移植 | 无（测试图）；真画面可回灌手机摄像头 | 内核已备 |
| 传感器（加速度/陀螺/磁力/光/距/气压） | IIO 软设备 + `uinput` ✅ | **自写 AIDL sensors HAL**（镜像里**一个都没有**） | 手机 `SensorManager` | HAL 待写 |
| 温度 / 热管理 | `thermal` ✅ | thermal HAL 自写 | 可映射手机温度 | 内核已备 |
| WLAN | `mac80211_hwsim` ✅ 纯软件射频 | wifi AIDL HAL 自写，或 `wpa_supplicant` 直连 hwsim | 无 | 内核已备 |
| 蓝牙 | `hci_vhci` ✅ 给 `/dev/vhci` | bluetooth HAL 自写 | 无 | 内核已备 |
| GPS / 位置 | 不需要 | AOSP 默认 GNSS HAL 吃 NMEA，可喂 | 手机定位 | 待隧道 |
| 时间 | 不需要 | `local_time.default.so` 在 | 隧道同步手机时间 | 容易 |
| NFC / 指纹 / 红外 / 蜂窝 SIM | — | — | — | **不做**：没硬件、没 HAL，做了也没有意义 |
| USB / OTG / SD 卡 | — | — | — | **不做**：UML 没有 UDC；外部存储用 hostfs 目录代替 |

## 优先级

读屏能不能用，取决于前四行（显示、触摸、声音、传感器）。只有它们齐了，TalkBack 才有「看得见、摸得着、听得见」的闭环。

## 已知不确定

- `MEDIA_SUPPORT` 在现代内核里 `depends on HAS_IOMEM`，UML 不一定给；若是，`vivid` 会被 Kconfig 静默丢掉 —— 构建后查 `artifacts/kernel-config.txt` 里 `CONFIG_VIDEO_VIVID` 的实际取值即可确认，不用猜。
- 同理 `THERMAL` 在部分 UML 配置下不可用。
