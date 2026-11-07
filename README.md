# droid-vm

在**未 root 的 Android 手机**上跑一个带真 Magisk / KernelSU(LKM) / KernelPatch(KPM) 的 Android 环境。

底座不是容器，也不是 QEMU：用 **[Linux/UML on arm64](https://github.com/zalexdev/linux-um-arm64)** —— 一个真 Linux 内核以普通 aarch64 用户进程的方式运行，不需要 root、KVM 或 hypervisor。因此 guest 里：

- 有**真内核**、真块设备、真 mount/overlayfs → 官方 Magisk 管理器走真机流程，模块真挂载；
- 能 `modprobe` 真内核模块（LKM）→ KernelSU 可用（模块需为本内核重编）；
- 能把 KernelPatch 编进内核（KPM）→ APatch 路线可用；
- guest 是真 root，**不需要任何 root 权限模拟**。

## 当前阶段：M0

编译出可在 App/Termux 里 exec 的 **bionic 静态 UML 内核**，在红米 Note 13 Pro（Android 16 / 4KB 页 / 16GB）上 boot 到 shell。

里程碑见 `docs/PLAN.md`。

## 构建

全程 GitHub Actions（本机不编内核）：

- `kernel/build.sh` — 内核构建，`MODE=bionic|glibc`
- `kernel/config/um-arm64-android.config` — 打开 binder/binderfs、overlayfs、namespaces、cgroup、模块加载等
- `rootfs/mkrootfs-alpine.sh` — 生成 Alpine ext4 根盘（供 M0 验证 boot）
- `.github/workflows/kernel.yml` — 流水线，产物为 `linux-bionic` + `alpine.ext4`
