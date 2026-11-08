# redroid 官方文档里能用的东西

来源：`remote-android/redroid-doc`（master），README / deploy/README / deploy/debian /
android-builder-docker。

## 1. 硬性内核要求（逐条核过）

| 要求 | 我们 | 说明 |
|---|---|---|
| `binderfs` | ✅ `ANDROID_BINDERFS=y` | 不需要再 `modprobe binder_linux` |
| `binder,hwbinder,vndbinder` | ✅ `ANDROID_BINDER_DEVICES` | 与官方一致 |
| `ashmem` 或 `memfd` | ⚠️ ashmem 没有（GKI 已删） | **必须传 `androidboot.use_memfd=1`**，否则一堆服务初始化失败 |
| `IPv6` | ✅ `CONFIG_IPV6=y` | 名副其实的硬要求，缺了 netd 起不来 |
| `ION` / `DMA-BUF Heaps` | ⚠️ 已补 `DMABUF_HEAPS_SYSTEM=y`、`DMABUF_HEAPS_CMA=y` | 只开 `DMABUF_HEAPS` 框架不够，没有 heap 就没有 `/dev/dma_heap/*` |
| 4KB 页 | ✅ `pages4k=true` | 我们的构建默认就是 4K 变体 |
| SELinux 关掉 | ✅ 已 `CONFIG_LSM` 首位 selinux + 启动参数 permissive | 官方建议 `setenforce 0`；userdebug 镜像本来就偏 permissive |

## 2. 启动参数（`redroid-up` 要用的全部）

官方参数表里对我们有用的：

```
androidboot.hardware=redroid
qemu=1
androidboot.use_memfd=1                 # 替代 ashmem，必须
androidboot.redroid_gpu_mode=guest      # 纯软件渲染（镜像里带 vulkan.lvp / swiftshader）
androidboot.redroid_width=1080
androidboot.redroid_height=2400
androidboot.redroid_dpi=420
androidboot.redroid_fps=30
androidboot.use_redroid_overlayfs=1     # /data-base 共享、/data-diff 私有
ro.secure=0
ro.adb.secure=0                         # 免配对直接 adb root，自测方便
```

两个要点：

- **`use_redroid_overlayfs` 正好解决「一切可写」**：基础镜像保持只读不变，所有写入落进
  `/data-diff`，重启可选保留或清空。比「直接往镜像里写」安全得多。
- `ro.xxx` 覆盖是官方留的调试口，`ro.secure=0` 之后 adb 默认 root。

## 3. 显示与投屏

官方路线就是 **scrcpy over adb**（`adb connect localhost:5555` + `scrcpy`）。没有 fb0 这条路，
印证了我们从镜像里看到的事实：`gralloc.redroid` + `hwcomposer.redroid` 合成后由 SurfaceFlinger
出帧，靠抓帧取画面。

## 4. 虚拟 HAL 的出处（这是「补齐手机所有东西」的关键线索）

官方 WebRTC 一节写着：*"Plan to port WebRTC solutions from cuttlefish, including frontend,
backend and many virtual HALs."* —— 也就是说 redroid 自己还没有这些 HAL，**但 cuttlefish 有**：
AOSP 的 `device/google/cuttlefish` 里带一整套「虚拟硬件」HAL（sensors、camera、GNSS、
wifi、bluetooth、audio、vibrator），本来就是为「没有真实硬件的 Android」写的。
我们要补的那些 HAL，优先级最高的参考实现就在那里，不必从零写。

## 5. 自建镜像

`android-builder-docker` 讲的是完整 AOSP 构建（`repo init` + `repo sync` + lunched
`redroid_arm64_only-userdebug`）。量级是几十 GB 源码与数小时构建，**不适合我们现在这条路**，
留作「必须往镜像里塞 TalkBack/TTS」时的最后手段。
