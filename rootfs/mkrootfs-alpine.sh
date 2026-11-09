#!/usr/bin/env bash
# M0 用的 Alpine ext4 根盘（UML ubd0 直接挂）
set -euo pipefail
# 这个脚本下面会 cd 进 work/，所以源码目录必须现在（还在仓库根）就算成
# 绝对路径；之后再 dirname "$0" 只会指向 work/rootfs。
SRC=$(cd "$(dirname "$0")" && pwd)
OUT=${KOUT:-$PWD/artifacts}
SIZE=${SIZE:-1024M}
VER=${ALPINE_VER:-3.20}
mkdir -p "$OUT" work
cd work
[ -f rootfs.tar.gz ] || curl -sSLo rootfs.tar.gz \
  "https://dl-cdn.alpinelinux.org/alpine/v${VER}/releases/aarch64/alpine-minirootfs-${VER}.0-aarch64.tar.gz"
rm -rf root && mkdir -p root
tar -xzf rootfs.tar.gz -C root
mkdir -p root/dev/pts root/proc root/sys root/data root/run root/tmp
chmod 1777 root/tmp
[ -e root/etc/resolv.conf ] || echo "nameserver 1.1.1.1" > root/etc/resolv.conf

# guest 的 PID 1。`init=/bin/sh` 会 panic——内核在 devtmpfs 就绪前把
# /dev/console 复制给 init 失败，sh 拿到关闭的 stdin 立刻 EOF 退出，而 PID 1
# 退出等于 Attempted to kill init。droidvm-init 自己开控制台并循环拉起 shell。
# 它的源码是 rootfs/droidvm-init.c，CI 用 aarch64-linux-gnu-gcc -static 编。
if [ -n "${INIT_BIN:-}" ] && [ -f "${INIT_BIN}" ]; then
	install -m 0755 "$INIT_BIN" root/sbin/droidvm-init
else
	echo "mkrootfs: 警告：没有 droidvm-init（INIT_BIN 未给），根盘只能退回 init=/bin/sh" >&2
fi
# minirootfs 只带 apk 和 busybox（520 个文件），做 redroid 那步要的
# unshare/nsenter、adb、python3 都没有。这里用静态 apk 直接往 aarch64 根盘里
# 解包：x86 的 runner 上也能跑，因为 --no-scripts 跳过了安装脚本，不需要
# binfmt/qemu。包名与用途：
#   util-linux(-misc)  unshare/nsenter/setsid/findmnt/lsns（redroid 启动器靠它）
#   android-tools      adb 客户端，连 guest 内 redroid 的 adbd
# python3 实测 42MB（/usr/lib/python3.12），会把根盘从 8MB 撑到 100MB+，
# APK 跟着涨到 70MB 上下。它只是顺手，不是必需：真要在 guest 里用就在
# guest 里 apk add python3 一次。所以不进镜像。
#   curl strace iproute2
#   gcompat            glibc 兜底，万一要跑官方预编译二进制
# 故意不装 openrc：M0/M1 用不到服务管理，需要时再加，并把 inittab 里的
# openrc sysinit/boot/default 三行恢复。
GUEST_PKGS=${GUEST_PKGS:-util-linux util-linux-misc android-tools curl python3}
if [ -n "$GUEST_PKGS" ]; then
  # 仓库版本跟着根盘走，免得 ALPINE_VER 改了而这里还用老版本号。
  VER=$(cut -d. -f1,2 root/etc/alpine-release)
  echo "mkrootfs: 补包仓库 v$VER"
  mkdir -p apkstatic
  if [ ! -x apkstatic/sbin/apk.static ]; then
    aname=$(curl -s "https://dl-cdn.alpinelinux.org/alpine/v${VER}/main/x86_64/" \
      | grep -o 'apk-tools-static-[0-9][0-9.]*-r[0-9]*\.apk' | head -1)
    [ -n "$aname" ] || { echo "mkrootfs: 找不到 apk-tools-static" >&2; exit 1; }
    curl -sSLo apkstatic/apk.apk "https://dl-cdn.alpinelinux.org/alpine/v${VER}/main/x86_64/$aname"
    tar xzf apkstatic/apk.apk -C apkstatic
  fi
  apkstatic/sbin/apk.static \
    --root "$PWD/root" --arch aarch64 --no-scripts --keys-dir "$PWD/root/etc/apk/keys" \
    --repository "https://dl-cdn.alpinelinux.org/alpine/v${VER}/main" \
    --repository "https://dl-cdn.alpinelinux.org/alpine/v${VER}/community" \
    -U add $GUEST_PKGS
  # 缺包要当场失败：根盘静默少个 unshare，问题会拖到手机上才炸。
  for f in unshare nsenter adb python3 curl; do
    find root -maxdepth 3 -name "$f" | grep -q . || {
      echo "mkrootfs: 补包后仍缺 $f" >&2; exit 1; }
  done
fi

# 标准 init：Alpine 的 /sbin/init 就是 busybox init，它照 /etc/inittab 干活。
# minirootfs 自带的那份会调 /sbin/openrc（这个包里没有 openrc），而且只给
# tty1..tty6 挂 getty —— 我们的交互通道是 tty0，也就是 UML console，所以整份
# 换掉：sysinit 自己挂文件系统，tty0 上 respawn 一个登录 shell。
# 以后要用服务管理（sshd、redroid 之类），apk add openrc 后把原版那三行
# openrc sysinit/boot/default 加回来即可。
# 隧道对端（Python 由上面的 GUEST_PKGS 显式装进根盘）。装进 /usr/local/bin，
# 由 inittab 在共享目录挂上之后拉起来；挂不上就什么都不做。
[ -f "$SRC/guest-agent.py" ] || {
  echo "mkrootfs: 找不到 $SRC/guest-agent.py" >&2; exit 1; }
mkdir -p root/usr/local/bin
install -m 0755 "$SRC/guest-agent.py" root/usr/local/bin/guest-agent.py

# redroid 的拉起脚本。参数不是猜的：全部来自官方文档的 androidboot.* 那一套
# （见 docs/redroid-notes.md）。ashmem 在 GKI 里已经没有了，所以必须 use_memfd；
# UML 里没有 GPU，所以 gpu_mode 固定 guest（纯软件渲染）。
cat > root/sbin/redroid-up <<'REDROIDUP'
#!/bin/sh
# /sbin/redroid-up —— 在 guest 里把 redroid（Android 16）拉起来。
# 用法：redroid-up [块设备]   默认 /dev/ubdb，也就是 App 用 ubd1= 传进来的 android.ext4
set -eu
log() { echo "redroid-up: $*" >/dev/kmsg 2>/dev/null || echo "redroid-up: $*"; }
die() { log "$*"; exit 1; }

IMG=${1:-/dev/ubdb}
MNT=${REDROID_MNT:-/android}

[ -b "$IMG" ] || die "$IMG 不是块设备：App 侧没有 android.ext4，或内核没挂上 ubd1"
mkdir -p "$MNT"
mountpoint -q "$MNT" || mount -t ext4 -o rw "$IMG" "$MNT" || die "挂 $IMG 到 $MNT 失败"
# Android 的 init 必须是 PID 1，/dev /proc /sys 也要看到宿主的设备与内核，
# 所以整条拉起链放进 unshare 出来的 mount+pid 命名空间：挂载动作全在命名空间里
# 做，不会污染 guest 自己的 /。参数既当 init 的 argv 传，也盖到 /proc/cmdline 上，
# 两种读法都照顾到（docker 那套就是把 androidboot.* 追加成 argv）。
mkdir -p "$MNT/data-base" "$MNT/data-diff"

# 镜像里没有任何地方设 service.adb.tcp.port（vendor/etc/init/init.redroid.rc 是
# 0 字节的空文件），adbd 于是不会监听 TCP，guest 里就 adb connect 不上。这里补一份
# vendor rc：init 会加载 /vendor/etc/init/*.rc，permissive 下不需要额外标签。
if [ ! -f "$MNT/vendor/etc/init/init.droidvm.rc" ]; then
  mkdir -p "$MNT/vendor/etc/init"
  cat > "$MNT/vendor/etc/init/init.droidvm.rc" <<'RC'
on init
    setprop service.adb.tcp.port 5555
    setprop persist.adb.tcp.port 5555
    start adbd

# 没有 USB 的情况下 init 阶段拉 adbd 可能还没就绪，boot 完再补一次
on boot
    start adbd
RC
  log "已补 /vendor/etc/init/init.droidvm.rc（adb 监听 tcp 5555）"
fi

# Android 的 init 自己会 ifup lo，但那条 rc 万一失败就一点网络都没有，
# 这里先起一遍，多做一次没有副作用。
ip link set lo up 2>/dev/null || ifconfig lo up 2>/dev/null || true
ARGS="qemu=1 androidboot.hardware=redroid androidboot.use_memfd=1 \
androidboot.redroid_gpu_mode=guest \
androidboot.redroid_width=${REDROID_W:-1080} \
androidboot.redroid_height=${REDROID_H:-2400} \
androidboot.redroid_dpi=${REDROID_DPI:-420} \
androidboot.redroid_fps=${REDROID_FPS:-30} \
androidboot.use_redroid_overlayfs=1 androidboot.selinux=permissive \
ro.secure=0 ro.adb.secure=0"

# 用 pivot_root 而不是 chroot：在 UML 上 chroot 之后再 unshare(CLONE_NEWNS) 并
# setns 回原命名空间，fs->root 会失效（nsprobe 实测：回切后 /android 下全部 ENOENT）。
# 而 Android init 的 NeedsTwoMountNamespaces() 恒为 true，必然走这条 unshare→setns
# 的路，于是 /bootstrap-apex → /apex 的 bind 全部 ENOENT、APEX 挂不上。pivot_root
# 让新根成为本命名空间真正的 root，fs->root 不再悬空（实测 PIVOT OK 后 init 日志
# 从 45 行涨到 293 行、APEX 的四个 loop 全部以 ro 挂上）。
exec unshare --mount --pid --fork --wait --propagation private /bin/sh -c '
  set -eu
  mount --make-rprivate /
  for d in dev sys; do
    mkdir -p '"$MNT"'/$d
    mount --rbind /$d '"$MNT"'/$d
  done
  mkdir -p '"$MNT"'/proc
  mount -t proc proc '"$MNT"'/proc
  # echo 会把多个词用一个空格连成一行，正好是内核命令行要的格式
  echo '"$ARGS"' > /tmp/redroid-cmdline
  mount --bind /tmp/redroid-cmdline '"$MNT"'/proc/cmdline
  # 开着 CONFIG_BOOT_CONFIG 的内核里，init 先读 /proc/bootconfig，空了才回落 cmdline，
  # 所以两份都给：bootconfig 一行一个键，正好由空格换行得到。
  if [ -e '"$MNT"'/proc/bootconfig ]; then
    echo '"$ARGS"' | tr " " "\n" > /tmp/redroid-bootconfig
    mount --bind /tmp/redroid-bootconfig '"$MNT"'/proc/bootconfig
  fi
  # pivot_root 的 put_old 必须事先存在，否则只会拿到 ENOENT（不是内核拒绝）
  mkdir -p '"$MNT"'/.oldroot
  if ! pivot_root '"$MNT"' '"$MNT"'/.oldroot 2>/tmp/pivot.err; then
    echo "redroid-up: pivot_root 失败: $(cat /tmp/pivot.err 2>/dev/null) —— 回落 chroot" >/dev/kmsg 2>/dev/null || true
    exec chroot '"$MNT"' /init '"$ARGS"'
  fi
  cd /
  umount -l /.oldroot 2>/dev/null || true
  exec /init '"$ARGS"'
'

REDROIDUP
chmod 755 root/sbin/redroid-up
mkdir -p root/var/log

cat > root/sbin/droidvm-boot <<'DROIDVMBOOT'
#!/bin/sh
# /sbin/droidvm-boot —— tty0 的唯一前台所有者。
# 有 android.ext4（App 用 ubd1= 传进来的 /dev/ubdb）就把控制台整个交给 Android；
# 没有就给一个交互 shell 当逃生通道。两条路都是阻塞/exec，不存在「后台还有个
# ash 在读同一个 tty」——那正是之前 getprop 被按字符啃碎的根源。
set -u
if [ -b /dev/ubdb ]; then
  /sbin/redroid-up /dev/ubdb
  echo "droidvm-boot: redroid-up 返回 $?，回落到交互 shell" >/dev/kmsg 2>/dev/null || true
fi
exec /bin/sh -l
DROIDVMBOOT
chmod 755 root/sbin/droidvm-boot

cat > root/etc/inittab <<'INITTAB'
# /etc/inittab —— droidvm guest
::sysinit:/bin/mount -t proc proc /proc
::sysinit:/bin/mount -t sysfs sysfs /sys
# /dev 由 devtmpfs 挂上后会把镜像里的目录整个遮住，pts 得先建再挂，
# 否则就是 mount: devpts on /dev/pts failed: No such file or directory。
::sysinit:/bin/sh -c 'mount -t devtmpfs devtmpfs /dev 2>/dev/null || true'
::sysinit:/bin/mkdir -p /dev/pts /dev/shm /dev/mqueue
::sysinit:/bin/mount -t devpts devpts /dev/pts -o gid=5,mode=620
::sysinit:/bin/mount -t tmpfs tmpfs /dev/shm -o mode=1777,size=256M
::sysinit:/bin/busybox ln -sf pts/ptmx /dev/ptmx
::sysinit:/bin/mount -t tmpfs tmpfs /run -o mode=755
::sysinit:/bin/mount -t tmpfs tmpfs /tmp -o mode=1777,size=512M
::sysinit:/bin/mkdir -p /sys/fs/cgroup
::sysinit:/bin/sh -c 'mount -t cgroup2 none /sys/fs/cgroup 2>/dev/null || true; echo "+cpu +memory +io +pids" > /sys/fs/cgroup/cgroup.subtree_control 2>/dev/null || true; echo "droidvm: cgroup2 $(mountpoint -q /sys/fs/cgroup && echo ok || echo NO) 控制器=[$(cat /sys/fs/cgroup/cgroup.controllers 2>/dev/null)]" >/dev/kmsg'
::sysinit:/bin/hostname droidvm
::sysinit:/bin/sh -c 'echo 1 4 1 7 > /proc/sys/kernel/printk; echo 0 > /proc/sys/kernel/printk_ratelimit 2>/dev/null || true'
::sysinit:/bin/mkdir -p /mnt/host
::sysinit:/bin/sh -c 'S=$(sed -n "s/.*droidvm_share=\([^ ]*\).*/\1/p" /proc/cmdline); if [ -n "$S" ]; then { mount -t hostfs hostfs /mnt/host -o "hostfs=$S" || mount -t hostfs hostfs /mnt/host -o "$S"; } && echo "droidvm: 共享目录 $S 已挂到 /mnt/host" >/dev/kmsg || echo "droidvm: 共享目录 $S 挂载失败" >/dev/kmsg; else echo "droidvm: 命令行没有 droidvm_share，跳过共享目录" >/dev/kmsg; fi'
::sysinit:/bin/sh -c 'S=$(sed -n "s/.*droidvm_share=\([^ ]*\).*/\1/p" /proc/cmdline); if [ -n "$S" ]; then T=$(stat -c %y "$S" 2>/dev/null | cut -c1-19); [ -n "$T" ] && date -s "$T" >/dev/null 2>&1; fi; echo "droidvm: 时钟 [$(date +%Y-%m-%dT%H:%M:%S)]" >/dev/kmsg'
::sysinit:/bin/echo droidvm-init: 标准-init-已接管（busybox-init+inittab）>/dev/kmsg
::sysinit:/bin/sh -c 'mountpoint -q /mnt/host || exit 0; command -v python3 >/dev/null 2>&1 || { echo "droidvm: 根盘没有 python3，隧道不可用" >/dev/kmsg; exit 0; }; setsid python3 /usr/local/bin/guest-agent.py </dev/null >/var/log/guest-agent.log 2>&1 & sleep 1; if pgrep -f guest-agent.py >/dev/null 2>&1; then echo "droidvm: 隧道对端已拉起" >/dev/kmsg; else echo "droidvm: 隧道对端启动失败，见 /var/log/guest-agent.log" >/dev/kmsg; fi'
::sysinit:/bin/mknod -m 620 /dev/tty0 c 4 0
::sysinit:/bin/mknod -m 620 /dev/console c 5 1
::sysinit:/bin/ls -l /dev/tty0 /dev/console /dev/tty >/dev/kmsg 2>&1
::sysinit:/bin/cat /proc/consoles >/dev/kmsg 2>&1
::sysinit:/bin/sh -c 'for m in /proc /sys /dev /dev/pts /dev/shm /run /tmp /sys/fs/cgroup /mnt/host; do printf "droidvm: mount %-16s %s\n" "$m" "$(mountpoint -q "$m" && echo ok || echo NO)"; done >/dev/kmsg'
tty0::respawn:/sbin/droidvm-boot
# 备用：tty0 这条还不通就换成 console 这个 id 再试。
#console::respawn:/bin/sh -l
::ctrlaltdel:/bin/reboot
::shutdown:/bin/umount -a -r
INITTAB

rm -f "$OUT/alpine.ext4"
mke2fs -q -t ext4 -F -d root -L umroot "$OUT/alpine.ext4" "$SIZE"
cat > "$OUT/run.sh" <<'RUN'
#!/bin/sh
# 在 Termux 里把 linux-bionic、stub_exe_bionic、alpine.ext4 放同一目录后：sh run.sh
# seccomp=on 才会走 PTRACE_SYSEMU 快路径并拿到多 vCPU；不支持时改用 seccomp=auto。
exec ./linux-bionic mem=2048M ubd0=alpine.ext4 root=/dev/ubda rw \
  init=/sbin/init con0=null,fd:1 seccomp=on "$@"
RUN
chmod +x "$OUT/run.sh"
ls -lh "$OUT"
