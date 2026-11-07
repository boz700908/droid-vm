# M0：手机上跑到 shell、验多核与 umfb

目标：不 root，用 Termux 把 UML 内核跑成 aarch64 用户进程进 Alpine shell，确认
**`nproc > 1`**（`seccomp=on` 的多 vCPU 路径通了）与 **umfb 能写出帧**。

目标机红米 Note 13 Pro：宿主内核 5.10、Android 16 / SDK 36、`arm64-v8a`、
**PAGE_SIZE 4096**、MemTotal ≈ 15.6 GB → 产物取 **`um-gki618-4k`**（4 KB 页变体）。

## 1. 产物（缺一不可）

| 文件 | 说明 |
|---|---|
| `linux-bionic` | 内核本体，静态 aarch64，跑起来就是一个进程 |
| `stub_exe_bionic` | **必须与内核同目录** |
| `alpine.ext4` | 根盘（Alpine 3.20 aarch64 + `/bin/sh`） |
| `run.sh` | 启动脚本，已带 `seccomp=on` |
| `kernel-config.txt` | 核对 `.config` 用，不参与启动 |

## 2. 部署（Termux）

```sh
mkdir -p ~/droidvm && cd ~/droidvm
unzip -o ~/storage/downloads/um-gki618-4k.zip -d ~/droidvm
chmod +x linux-bionic stub_exe_bionic run.sh
```

`uml_dir` 默认 `$HOME/.uml`，Termux 家目录可写；报错时显式加 `uml_dir=$HOME/.uml`。

## 3. 启动

```sh
cd ~/droidvm && ./run.sh
```

`run.sh` 等价于：

```sh
exec ./linux-bionic mem=2048M ubd0=alpine.ext4 root=/dev/ubda rw \
     init=/bin/sh con0=null,fd:1 seccomp=on "$@"
```

多余参数原样追加，试参数不必改脚本：

```sh
./run.sh ncpus=4
./run.sh seccomp=off                                   # 单核排障基线
./run.sh umlfb=$HOME/umfb.bin:720x1280-32,hz=30,tile=64
```

## 4. 验证清单（进 shell 后）

```sh
nproc ; grep -c processor /proc/cpuinfo    # 期望 > 1（默认 宿主核数-1）
dmesg | tail -30                           # 无 panic
free -m ; ls /dev | head -30               # 有 ubda / console / fb0
```

- **`nproc` = 1**：走的是 ptrace 单核路径 —— 先查 `run.sh` 里 `seccomp=on` 是否还在；
  在但仍单核，说明宿主/ROM 拦了 `PTRACE_SYSEMU`（5.10 应支持，先看 `uname -r`）。
- `stub_exe_bionic: No such file` → 与内核不同目录或缺执行位。

## 5. umfb

`umfb` 只有命令行给了 `umlfb=` 才脱离休眠（否则 probe 返回 `-ENODEV`）：

```
umlfb=<path|fd:N>:<W>x<H>[-32][,hz=<n>][,tile=<n>]
```

- `<path>`：宿主可写文件，内核把帧（含 magic + 头）镜像进去；`fd:N` 直接写已打开的 fd。
- `-32` = 32bpp；`hz` 刷新率；`tile` 脏块尺寸（越大越省 CPU）。
- 验证：`ls -l /dev/fb0`、`cat /sys/class/graphics/fb0/virtual_size` 应与命令行一致；
  宿主机 `$HOME/umfb.bin` 大小 =「头 + W*H*4」，guest 写屏后 `md5sum` 会变。

`umview`（上游 `tools/um-arm64/umview`，SDL2）是桌面查看器；Android 上先只验文件，
真正的显示走 M2 的 hwcomposer。

## 6. 失败对照

| 现象 | 原因 | 处理 |
|---|---|---|
| 启动即退无输出 | 无执行位 / 架构不符 | `chmod +x`；`file linux-bionic` 应为 aarch64 |
| `ptrace: Operation not permitted` | ROM 安全策略拦 ptrace | 先用 `seccomp=off` 单核跑通 |
| `nproc`=1 | 见 §4 | 见 §4 |
| 没有 `/dev/fb0` | 命令行没给 `umlfb=` | 见 §5 |

## 7. 下一步（M1）

M0 通过后换成 redroid `13.0.0_64only` 拆层得到的 `/system` `/vendor`，
`init=/system/bin/servicemanager` 验 binderfs（`kernel-config.txt` 里
`ANDROID_BINDER_IPC`/`ANDROID_BINDERFS` 应为 `=y`）。
