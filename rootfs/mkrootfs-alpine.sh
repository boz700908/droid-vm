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
::sysinit:/bin/mount -t devpts devpts /dev/pts
::sysinit:/bin/mount -t tmpfs tmpfs /tmp
::sysinit:/bin/mount -t tmpfs tmpfs /run
::sysinit:/bin/hostname droidvm
::sysinit:/bin/echo droidvm-init: 标准-init-已接管（busybox-init+inittab）>/dev/kmsg
# App 不是真终端，不走 getty/login 那套提示；sh -l 会读 /etc/profile 拿到 PATH、PS1。
tty0::respawn:/bin/sh -l
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
