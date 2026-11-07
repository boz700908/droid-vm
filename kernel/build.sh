#!/usr/bin/env bash
# 用法: MODE=bionic|glibc KSRC=/path/to/kernel OUT=/path/out bash kernel/build.sh
set -euo pipefail
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
MODE=${MODE:-bionic}
: "${KSRC:?KSRC 未设置}"
OUT=${OUT:-$HERE/../out}
FRAGMENT=${FRAGMENT:-$HERE/config/um-arm64-android.config}
mkdir -p "$OUT"

export ARCH=um SUBARCH=arm64 LLVM=1
if [ "$MODE" = bionic ]; then
  : "${NDK:?bionic 模式需要 NDK}"
  export CLANG="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/clang"
  export NDK_SYSROOT="$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
  export CC="$HERE/toolchain/android-clang.sh"
fi

cd "$KSRC"
make defconfig
scripts/kconfig/merge_config.sh -m .config "$FRAGMENT" >/dev/null 2>&1 || true
make olddefconfig
make -j"$(nproc)"

cp -f linux "$OUT/linux-$MODE"
{
  echo "mode=$MODE"
  echo "commit=$(git rev-parse HEAD)"
  echo "date=$(date -u +%FT%TZ)"
  echo "size=$(stat -c%s "$OUT/linux-$MODE")"
  echo "--- 关键配置 ---"
  grep -E 'CONFIG_(ANDROID_BINDER_IPC|ANDROID_BINDERFS|OVERLAY_FS|USER_NS|MODULES|KALLSYMS|KPROBES|MEMCG)=' .config || true
} > "$OUT/build.txt"
file "$OUT/linux-$MODE" >> "$OUT/build.txt" || true
cat "$OUT/build.txt"
