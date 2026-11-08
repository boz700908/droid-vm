#!/usr/bin/env bash
# M0 用的 Alpine ext4 根盘（UML ubd0 直接挂）
set -euo pipefail
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
GUEST_PKGS=${GUEST_PKGS:-util-linux util-linux-misc android-tools curl strace iproute2 gcompat}
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
cat > root/etc/inittab <<'INITTAB'
# /etc/inittab —— droidvm guest
::sysinit:/bin/mount -t proc proc /proc
::sysinit:/bin/mount -t sysfs sysfs /sys
# /dev 由 devtmpfs 挂上后会把镜像里的目录整个遮住，pts 得先建再挂，
# 否则就是 mount: devpts on /dev/pts failed: No such file or directory。
::sysinit:/bin/mkdir -p /dev/pts /dev/shm
::sysinit:/bin/mount -t devpts devpts /dev/pts
::sysinit:/bin/mount -t tmpfs tmpfs /tmp
::sysinit:/bin/mount -t tmpfs tmpfs /run
::sysinit:/bin/hostname droidvm
::sysinit:/bin/echo droidvm-init: 标准-init-已接管（busybox-init+inittab）>/dev/kmsg
# App 不是真终端，不走 getty/login 那套提示；sh -l 会读 /etc/profile 拿到 PATH、PS1。
# UML console 占 TTY_MAJOR(4) minor 0，/dev/console 是 5:1。VT 关掉之后
# devtmpfs 未必替我们建这两个节点，busybox init 打不开控制终端就静默跳过，
# 表现正是「接管之后什么都没有」。已存在的 mknod 会 EEXIST，无害。
::sysinit:/bin/mknod -m 620 /dev/tty0 c 4 0
::sysinit:/bin/mknod -m 620 /dev/console c 5 1
::sysinit:/bin/ls -l /dev/tty0 /dev/console /dev/tty >/dev/kmsg 2>&1
::sysinit:/bin/cat /proc/consoles >/dev/kmsg 2>&1
# init 是先 exec /bin/sh -c "<这一行>" 的，所以 $$ 能展开、重定向也能用。
# 这行 echo 一出现，就说明子进程确实被拉起来了，问题只在 tty 本身。
tty0::respawn:/bin/sh -c 'echo droidvm: tty0-shell-已拉起 pid=$$ >/dev/kmsg; exec /bin/sh -l'
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
