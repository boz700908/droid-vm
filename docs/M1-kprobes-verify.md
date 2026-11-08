# M1：在 guest 里验证 kprobes 与模块加载

M0 只证明「能起来、有 shell」。M1 要证明的是本仓库的**核心诉求**：
外部内核模块能加载，kprobe 能打断点、能回来。没有这两条，
SukiSU 的 LKM 形态（kprobes 挂钩）就是空中楼阁。

产物里为此专门放了两个 `.ko`：

| 文件 | 用途 |
|---|---|
| `kprobe-test.ko` | 自测模块，先加载它验机制，再用 `kernelsu.ko` |
| `kernelsu.ko` | SukiSU Ultra 的内核模块，对着本次内核外编（`CONFIG_KSU=m CONFIG_KPM=y`） |

两者都与 `linux-bionic` 同一次构建、同一个 `--target` 编出，vermagic 必须一致；
不一致时 `insmod` 会直接报 `invalid module format`，这不是 bug 而是校验生效。

## 1. 部署

把 `linux-bionic`、`stub_exe_bionic`、`alpine.ext4`、`run.sh`、两个 `.ko`
一起推到设备同一目录（`docs/M0-phone.md` 里的 Termux 步骤照旧）。
`.ko` 既可以直接留在宿主目录（guest 里 `insmod` 绝对路径够不到），
也可以先拷进根盘——推荐后者：

```sh
# 宿主（Termux）
./run.sh                     # 起 guest
# guest 里先挂不上宿主，所以在宿主侧准备：
# 用 debugfs 把 .ko 写进 alpine.ext4 最省事，或在 guest 里联网 wget
```

更省事的做法：起 guest 前把 `.ko` 放在 `run.sh` 同目录，guest 起来后用
`ubd` 之外的通道拿不到——所以实际做法是**改 `run.sh` 多加一块盘**：

```sh
# 宿主侧造一个只有两个 .ko 的小 ext4，作为 ubdb 挂进 guest
dd if=/dev/zero of=mods.ext4 bs=1M count=8
mke2fs -t ext4 -d mods/ mods.ext4 2>/dev/null || true   # mods/ 里放两个 .ko
```
然后 `run.sh` 追加 `ubdb=mods.ext4`（`run.sh` 是 `exec ./linux-bionic ... "$@"`，
直接 `./run.sh ubdb=mods.ext4` 即可，参数会透传）。

## 2. 先在 guest 里确认内核真的报了这些能力

```sh
uname -a                  # 6.18.x ... aarch64（UML 下 uname 的 machine 是 aarch64）
grep -E " (register_kprobe|register_kretprobe|unregister_kprobe)$" /proc/kallsyms
ls /sys/module | head     # 模块子系统活着
cat /proc/sys/kernel/kptr_restrict   # 需要 0 才能看到 kallsyms 地址
```

`register_kprobe` 在 kallsyms 里**必须有**。没有就说明 `CONFIG_KPROBES` 没生效，
不必往下走。

## 3. 自测模块

```sh
mkdir -p /mnt/mods && mount -t ext4 /dev/ubdb /mnt/mods
insmod /mnt/mods/kprobe-test.ko
# 命中会打在 console 上：kprobe-test: armed on vfs_read (kprobe + kretprobe)
cat /proc/version > /dev/null      # 制造一次 vfs_read
cat /proc/version > /dev/null
ls / > /dev/null
rmmod kprobe-test
```

判据：

- `insmod` 成功，且 console 出现 `armed on vfs_read`；
- 制造几次读操作后，console 至少出现一次
  `kprobe-test: pre  vfs_read pc=...`——**这说明 BRK 真的进了
  `um_arm64_kprobe_trap()` 又回到了原流程**，是整个移植最关键的一条；
- `rmmod` 后出现 `done pre=N post=N entry=N ret=N`，`pre` 必须 > 0；
- 全过程没有 `Unable to handle kernel paging request`、没有 oops。

若 `pre=0` 且模块打印了那句 warn：要么 `vfs_read` 没被调到（换 `probe_sym`，
例如 `insmod kprobe-test.ko probe_sym=ksys_read`），要么 BRK 陷入没回来
（那就是移植的问题，回到 3 条判据之外去看 dmesg）。

## 4. kernelsu.ko

```sh
insmod /mnt/mods/kernelsu.ko
dmesg | tail -20
ls /data/adb/ksu 2>/dev/null    # 首次加载可能只建目录
```

它注册的是 kprobes 钩子，`/proc/kallsyms` 里能查到 `kernelsu` 相关符号、
`/sys/module/kernelsu` 存在，就说明 LKM 形态成立。
后续 `ksud late-load` 与管理器安装流程属于 M2+M3，不在本步。

## 5. 一次失败的定位顺序

1. `insmod` 报 `invalid module format` → vermagic 不一致，确认 `.ko` 与
   `linux-bionic` 出自同一次 CI run（看 `ksrc-sha.txt` / `sukisu-sha.txt`）。
2. `insmod` 报 `Unknown symbol ... (err -2)` → 内核侧缺 `CONFIG_*`；
   用产物里的 `kernel-config.txt` 对。
3. `register_kprobe` 返回 `-EILSEQ`/`-ENOENT` → 目标符号不在 kallsyms
   （`CONFIG_KALLSYMS_ALL=n` 或符号被 LTO 掉了）。
4. `insmod` 成功但 `pre=0` → 看第 3 节末尾。
5. 一切正常但 `cat /proc/version` 直接卡住 → BRK 陷入没有回写寄存器，
   即 `kprobe-trap.c` 的 `PT_REGS_*` 回写路径有问题（这是历史踩过的地方）。
