#!/usr/bin/env bash
# UML 下没有 __arm64_sys_* 蹦床（只有裸 sys_* 函数），arm64 模块从内核态调
# syscall 就没有可绑的符号。原因见 syscall-shim.c 文件头。
set -euo pipefail
P="$(cd "$(dirname "$0")" && pwd)"
K="${1:?用法: apply.sh <内核树>}"
[ -d "$K/arch/arm64/um" ] || { echo "找不到 $K/arch/arm64/um"; pwd; exit 1; }

install -m 0644 "$P/syscall-shim.c" "$K/arch/arm64/um/syscall-shim.c"
echo "已放入 arch/arm64/um/syscall-shim.c"

python3 - "$K" <<'PYEOF'
import pathlib, sys
K = pathlib.Path(sys.argv[1])
mk = K / 'arch/arm64/um/Makefile'
s = mk.read_text()
if 'syscall-shim.o' in s:
    print('arch/arm64/um/Makefile 已含 syscall-shim.o，跳过')
else:
    anchor = 'subarch-$(CONFIG_MODULES) +='
    assert anchor in s, 'arch/arm64/um/Makefile 锚点丢失'
    add = (
        '# UML 的 sys_call_table 里是裸 sys_* 函数，没有 __arm64_sys_* 蹦床；\n'
        '# 给从内核态调 syscall 的 arm64 模块补上（名字/签名与 arm64 一致）。\n'
        'obj-y += syscall-shim.o\n\n'
    )
    mk.write_text(s.replace(anchor, add + anchor, 1))
    print('已插入 obj-y += syscall-shim.o')
PYEOF
