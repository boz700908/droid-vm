#!/usr/bin/env bash
# clang 包装器：把 UML 默认的 --target=aarch64-linux-gnu 换成 bionic 三元组，
# 并在链接步骤加 -static（Android 上没有 /lib/ld-linux-aarch64.so.1，且 App 只能 exec 自己的 native 库目录）。
set -euo pipefail
: "${CLANG:?}"
: "${NDK_SYSROOT:?}"
args=()
link=1
for a in "$@"; do
  case "$a" in
    --target=*gnu*) continue ;;
    -c) link=0; args+=("$a") ;;
    *) args+=("$a") ;;
  esac
done
if [ "$link" = 1 ]; then args+=(-static); fi
exec "$CLANG" --target=aarch64-linux-android35 --sysroot="$NDK_SYSROOT" "${args[@]}"
