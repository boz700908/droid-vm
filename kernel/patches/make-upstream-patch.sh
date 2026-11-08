#!/usr/bin/env bash
# 从一棵干净的上游克隆重算 0001-um-arm64-kprobes-and-module-loading.patch。
#
# 补丁范围 = 所有「让 ARCH=um SUBARCH=arm64 的内核能加载外部模块」的内核侧改动：
#   um-kprobes/   KPROBES 移植
#   um-strings/   bionic 交给 libc 的字符串函数补弱定义 + 导出
# 明确**不进**补丁的（Android/模块兼容性质，不是 UML 缺陷）：
#   um-exports/        path_umount 导出
#   um-gki-exports/    GKI 有、这棵树没导出的那批符号
#   um-syscall-shim/   __arm64_sys_* 蹦床
#   sukisu-um/         SukiSU 模块自身的移植
#
# 用法: make-upstream-patch.sh <干净的上游克隆目录>
# 目录必须干净（git status --short 为空）；结束时把树还原。
set -euo pipefail
P="$(cd "$(dirname "$0")" && pwd)"
K="${1:?用法: make-upstream-patch.sh <干净的上游克隆目录>}"
OUT="$P/0001-um-arm64-kprobes-and-module-loading.patch"
cd "$K"
[ -z "$(git status --porcelain)" ] || { echo "$K 不干净，先 git checkout -- . && git clean -fd"; exit 1; }

bash "$P/um-kprobes/apply.sh" "$K"
bash "$P/um-strings/apply.sh" "$K"

git add -A
git diff --cached --binary > "$OUT"
git reset -q
git checkout -- .
# 补丁新建的文件是未跟踪的，checkout 不会删；按 git status 精确清掉，
# 否则最后那步 git apply --check 会因为「工作区已有」而失败。
git status --porcelain | awk '$1=="??"{ $1=""; sub(/^ /, ""); print }' | while read -r f; do rm -rf -- "$f"; done

echo "已生成 $OUT"
echo "  文件数: $(grep -c '^diff --git' "$OUT")"
echo "  字节数: $(wc -c < "$OUT")"
echo "  md5   : $(md5sum "$OUT" | cut -d' ' -f1)"
if grep -qi "sukisu\|kernelsu\|KPM" "$OUT"; then echo "  !! 补丁里混进了 SukiSU 字样"; exit 1; fi
git apply --check "$OUT" && echo "  git apply --check: OK"
