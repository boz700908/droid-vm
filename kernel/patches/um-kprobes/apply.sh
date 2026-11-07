#!/bin/bash
# 把 kprobes 移植打进 ksrc 树。可重复执行。
set -e
K=${1:?用法: apply.sh <内核源码目录>}
P=$(cd "$(dirname "$0")" && pwd)

cp "$P/kprobe-trap.c"   "$K/arch/arm64/um/kprobe-trap.c"
cp "$P/probe-mem.c"     "$K/arch/arm64/um/probe-mem.c"
cp "$P/kprobe-signal.c" "$K/arch/arm64/um/os-Linux/kprobe-signal.c"
cp "$P/mem-host.c"      "$K/arch/arm64/um/os-Linux/mem-host.c"

# <asm/kprobes.h> 的桩默认只给 __kprobes 标注，开了 KPROBES 必须换回父架构真头
cp "$P/kprobes-header.h" "$K/arch/arm64/um/asm/kprobes.h"
cp "$P/kprobes-header-um.h" "$K/arch/um/include/asm/kprobes.h"
cp "$P/daifflags-header.h" "$K/arch/arm64/um/asm/daifflags.h"
echo "um-kprobes: <asm/kprobes.h> 两处已铺"
md5sum "$K/arch/arm64/um/asm/kprobes.h" "$K/arch/um/include/asm/kprobes.h"

if ! grep -q 'um-kprobes: patching' "$K/arch/arm64/um/patching.c"; then
	cat "$P/patching-append.c" >> "$K/arch/arm64/um/patching.c"
fi

if ! grep -q 'um-kprobes: Makefile' "$K/arch/arm64/um/Makefile"; then
	cat "$P/Makefile.append" >> "$K/arch/arm64/um/Makefile"
fi

python3 - "$K" <<'PY'
import sys, pathlib
K = pathlib.Path(sys.argv[1])

# 1) arch/um/Kconfig: HAVE_KPROBES / HAVE_KRETPROBES
kc = K / 'arch/um/Kconfig'
s = kc.read_text()
if 'HAVE_KPROBES' not in s:
    anchor = '\tselect HAVE_SYSCALL_TRACEPOINTS\n'
    if anchor not in s:
        anchor = '\tselect HAVE_ARCH_TRACEHOOK\n'
    assert anchor in s, 'arch/um/Kconfig 锚点丢失'
    s = s.replace(anchor, anchor + '\tselect HAVE_KPROBES\n\tselect HAVE_KRETPROBES\n', 1)
    kc.write_text(s)

# 2) os-Linux/Makefile: 两个宿主侧文件
mk = K / 'arch/arm64/um/os-Linux/Makefile'
s = mk.read_text()
if 'mem-host.o' not in s:
    old = 'obj-y = registers.o mcontext.o'
    assert old in s, 'os-Linux/Makefile 锚点丢失'
    s = s.replace(old, 'obj-y = registers.o mcontext.o mem-host.o kprobe-signal.o', 1)
    mk.write_text(s)

# 3) 补 patching 的两个原型（um 版头文件里可能只有 nosync）
th = K / 'arch/arm64/um/asm/text-patching.h'
s = th.read_text()
if 'aarch64_insn_patch_text(void' not in s:
    s += ('\n/* kprobes 需要的一次性改多处文本入口（um-kprobes） */\n'
          'int aarch64_insn_patch_text(void *addrs[], u32 insns[], int cnt);\n')
    th.write_text(s)

# 4) 共享 signal.c: SIGTRAP 先问 kprobes
sc = K / 'arch/um/os-Linux/signal.c'
s = sc.read_text()
if 'arch_um_kprobe_trap' not in s:
    old_inc = '#include "internal.h"'
    assert old_inc in s, 'signal.c include 锚点丢失'
    s = s.replace(old_inc,
                  old_inc + '\n\n/* ARCH=um SUBARCH=arm64 的 kprobes 桥；'
                  '没有实现（如 x86）时是空指针 */\n'
                  'extern int arch_um_kprobe_trap(mcontext_t *mc) __attribute__((weak));',
                  1)
    old = '\t(*sig_info[sig])(sig, si, &r, mc);'
    assert old in s, 'signal.c 分派锚点丢失'
    s = s.replace(old,
                  '\t/* BRK 型陷入：先交给 kprobes，没人认领才当普通信号转发给 guest */\n'
                  '\tif (sig == SIGTRAP && arch_um_kprobe_trap && arch_um_kprobe_trap(mc))\n'
                  '\t\treturn;\n\n' + old, 1)
    sc.write_text(s)

print('um-kprobes: 补丁已应用')

# 5) arch/arm64/um/asm/ptrace.h: kprobes 需要 instruction_pointer[_set]
pt = K / 'arch/arm64/um/asm/ptrace.h'
s = pt.read_text()
if 'instruction_pointer_set' not in s:
    snippet = """
/*
 * UML 的 ptrace 头原本没有这两条；generic kernel/kprobes.c（UP 分支）与
 * arch/arm64/kernel/probes/kprobes.c（XOL 单步改写 PC）都要用。
 */
static inline unsigned long instruction_pointer(struct pt_regs *regs)
{
	return PT_REGS_IP(regs);
}

static inline void instruction_pointer_set(struct pt_regs *regs, unsigned long val)
{
	PT_REGS_IP(regs) = val;
}

"""
    i = s.rstrip().rfind('#endif')
    assert i > 0, 'ptrace.h 里找不到结尾 #endif'
    pt.write_text(s[:i] + snippet + s[i:])
PY
