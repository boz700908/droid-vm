# droid-vm

在**未 root 的 Android 手机**上跑一个带真 Magisk / KernelSU(LKM) / KernelPatch(KPM) 的 Android 环境。

底座不是容器也不是 QEMU，而是 **arm64 上的 Linux/UML** —— 真 Linux 内核以普通 aarch64 用户进程的方式运行，不需要 root、KVM 或 hypervisor。于是 guest 里：

- 有真内核、真块设备、真 mount/overlayfs → 官方 Magisk 管理器走真机流程，模块真挂载；
- 能 `modprobe` 真 `.ko` → KernelSU 的 LKM 模式可用（模块须为本内核重编）；
- 能把 KernelPatch 编进内核 → APatch 的 KPM 路线可用；
- guest 本身就是 root，**不需要任何 root 权限模拟层**。

## 内核底座：`lakitu12/linux-um-arm64-gki`

不自建内核树，直接消费 **[lakitu12/linux-um-arm64-gki](https://github.com/lakitu12/linux-um-arm64-gki)**：Android 17 GKI（6.18.32）之上 95 个提交的 arm64 UML 移植。它已经解决了几件我们本来要自己做的大工程：

| 能力 | 状态 |
|---|---|
| **SMP** | `CONFIG_SMP=y`、`NR_CPUS=16`、`ncpus=` 默认「宿主核数−1」；实测 4 个 CPU 密集任务 3.19–3.76×。**ptrace 模式强制单核**，须 `seccomp=on` 才有多 vCPU |
| 同进程多线程 | 需 `stub_slots=N`（默认 1；app domain 仍会 RCU stall，未开） |
| 显示 | `umfb` 帧缓冲：脏块镜像到宿主文件/fd，配 `umview` 或 app 的 display sink |
| 图形加速 | virtio-gpu over virtio-uml PCI，2D 后端 `ci/vugpu2d.py` |
| 根盘 | ext4 与 **qcow2**（读写、refcount、压缩描述符） |
| Android guest | `config/android-guest.config`：binder/binderfs、PSI、netfilter 全套，逐条是实测 boot gate |
| 产物 | `artifacts/linux-bionic`（静态 aarch64，bionic API 30）+ `artifacts/stub_exe_bionic`（必须放同一目录） |

已知短板：块 I/O 完成走进程级 `SIGIO`，全落到 CPU0，I/O 密集任务（`docker build`）扩不起来。

## 本仓库做什么

只做「裁剪 + 叠加 + 出包」：

- `.github/workflows/kernel.yml` — 浅克隆该内核树，装 NDK r28c，按需合并 `config/page4k.config`，再叠加我们的 `kernel/config/magisk-lkm.config`，产出可直接放手机的产物
- `kernel/config/magisk-lkm.config` — 模块加载 / `KALLSYMS_ALL` / kprobe（KernelSU LKM 与第三方 `.ko` 的前提）
- `rootfs/mkrootfs-alpine.sh` — M0 用 Alpine ext4 根盘 + 手机端 `run.sh`

## 页大小

本机宿主是 4 KB 页。guest 默认 16 KB 在 4 KB 宿主上能跑（4 宿主页对 1 guest 页），但 **redroid-13 那一代 Android 用户态是 4 KB 的**，所以默认按 `pages4k=true` 出 4 KB 变体，与宿主 1:1。

## 里程碑

见 `docs/PLAN.md`。
