#!/usr/bin/env bash
# 让 ARCH=um SUBARCH=arm64 的 bionic 构建也导出 memcpy/strcmp 等字符串函数。
# 原因见 kernel-strings.c 文件头：string.h 把这些交给 libc，lib/string.c 就不导出，
# 外部内核模块没有 libc 就全部 modpost undefined。
set -euo pipefail
P="$(cd "$(dirname "$0")" && pwd)"
K="${1:?用法: apply.sh <内核树>}"
[ -d "$K/arch/arm64/um" ] || { echo "找不到 $K/arch/arm64/um"; pwd; exit 1; }

install -m 0644 "$P/kernel-strings.c" "$K/arch/arm64/um/kernel-strings.c"
echo "已放入 arch/arm64/um/kernel-strings.c"

python3 - "$K" <<'PYEOF'
import pathlib, sys
K = pathlib.Path(sys.argv[1])
mk = K / 'arch/arm64/um/Makefile'
s = mk.read_text()
if 'kernel-strings.o' in s:
    print('arch/arm64/um/Makefile 已含 kernel-strings.o，跳过')
else:
    anchor = 'subarch-$(CONFIG_MODULES) +='
    assert anchor in s, 'arch/arm64/um/Makefile 锚点丢失'
    add = (
        '# bionic 构建把 memcpy/strcmp 等交给 libc，内核因此不导出它们；\n'
        '# 外部模块没有 libc，这里补弱定义 + EXPORT_SYMBOL。\n'
        'obj-y += kernel-strings.o\n'
        'CFLAGS_kernel-strings.o := -fno-builtin\n\n'
    )
    mk.write_text(s.replace(anchor, add + anchor, 1))
    print('已插入 obj-y += kernel-strings.o')
PYEOF
