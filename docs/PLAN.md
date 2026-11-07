# 里程碑

- **M0**：bionic 静态 UML 内核 → 手机 Termux 里 boot 到 shell，实测基线（syscall/openat/计算）。
- **M1**：guest 换 Android rootfs（redroid `13.0.0_64only` 拆层得 /system /vendor），内核起 binderfs，先拉 `servicemanager` 验证 binder 通。
- **M2**：zygote + surfaceflinger（SwiftShader）+ 自写 hwcomposer，把帧送出 guest。
- **M3**：显示/输入接入 App（bionic 二进制 + 帧共享内存 + 输入注入）。
- **M4**：官方 Magisk 真装真用（选 boot 分区 → patch → 重启生效）；KernelSU LKM 与 KernelPatch KPM 用 UML 重编模块跑通。

## 已知硬伤（上游未解决，我们要自己扛）

1. UML 是 uniprocessor，无 SMP / 无 cpuset → UI 会卡。
2. guest vDSO 时间未合并 → 跑几十分钟后时间回绕。
3. 无 32 位兼容（`CONFIG_COMPAT` off）。
4. 作者明确不维护、上游不会合 → 我们长期维护 fork。
5. 图形栈（gralloc/hwcomposer）无人做过，是最大风险。
