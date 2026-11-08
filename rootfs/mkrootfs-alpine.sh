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
rm -f "$OUT/alpine.ext4"
mke2fs -q -t ext4 -F -d root -L umroot "$OUT/alpine.ext4" "$SIZE"
cat > "$OUT/run.sh" <<'RUN'
#!/bin/sh
# 在 Termux 里把 linux-bionic、stub_exe_bionic、alpine.ext4 放同一目录后：sh run.sh
# seccomp=on 才会走 PTRACE_SYSEMU 快路径并拿到多 vCPU；不支持时改用 seccomp=auto。
exec ./linux-bionic mem=2048M ubd0=alpine.ext4 root=/dev/ubda rw \
  init=/sbin/droidvm-init con0=null,fd:1 seccomp=on "$@"
RUN
chmod +x "$OUT/run.sh"
ls -lh "$OUT"
