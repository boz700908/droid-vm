#!/usr/bin/env bash
# 目标内核需要、但 mainline 6.18 没有导出的符号。
#
# 这不是「让内核能加载外部模块」的通用修复，所以不进上游补丁
# （kernel/patches/0001-um-arm64-kprobes-and-module-loading.patch），
# 单独一个脚本，属于 Android ABI 兼容范围。
#
#   path_umount(const struct path *, int)
#       mainline 6.18 有这个函数，却没有 EXPORT_SYMBOL；Android GKI 导出了它，
#       而 SukiSU 的 feature/kernel_umount.c 直接调用。这里补上同样的导出。
#
# 幂等；参数是内核树目录，默认 ksrc。
set -euo pipefail

TREE=${1:-ksrc}
F="$TREE/fs/namespace.c"

if [ ! -f "$F" ]; then
	echo "找不到 $F" >&2
	exit 1
fi
if grep -q 'EXPORT_SYMBOL.*path_umount' "$F"; then
	echo "fs/namespace.c: path_umount 已导出"
	exit 0
fi

cat >> "$F" <<'EOF'

/*
 * SukiSU(LKM): Android GKI 导出 path_umount 供内核模块使用，mainline 只保留了
 * 函数本身。外部模块（kernelsu.ko）在 enforcement 关闭时要用它做 umount。
 */
EXPORT_SYMBOL_GPL(path_umount);
EOF
echo "fs/namespace.c: 已导出 path_umount"
