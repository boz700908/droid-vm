# 里程碑

- **M0**：出 `linux-bionic` + `stub_exe_bionic` + Alpine 根盘，手机上 boot 到 shell，确认 `nproc > 1`（`seccomp=on` 下）与 `umfb` 可写。
- **M1**：guest 换 Android rootfs（redroid `13.0.0_64only` 拆层得 `/system`、`/vendor`），拉 `servicemanager` 验证 binderfs 通。
- **M2**：zygote + surfaceflinger + 自写 hwcomposer，帧经 `umfb` 送出 guest。
- **M3**：App 化（bionic 二进制 + 帧共享内存 + 输入注入）。
- **M4**：官方 Magisk 真装真用；KernelSU LKM 与 KernelPatch KPM 用 UML 重编模块跑通。

## 我们自己要扛的风险

1. **图形栈**：`umfb` 是平面 framebuffer，Android 要的是 gralloc + hwcomposer；virtio-gpu 的 2D 后端能否顶住 SurfaceFlinger 未验证。这是最大未知。
2. **同进程多线程不并行**：`stub_slots` 在 app domain 还 RCU stall，Android 单进程多线程性能受限（多进程仍可并行）。
3. **guest vDSO 时间**：时间回绕问题要确认这棵树是否已修。
4. **模块生态**：KSU/KPM 模块必须为 UML 重编，现成 GKI `.ko`/`.kpm` 不能直接用。
5. 上游是个人项目，需长期跟 rebase（它有 `docs/gki-notes.md` 说明换 GKI drop 的坑）。
