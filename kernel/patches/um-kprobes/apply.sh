#!/bin/bash
# 把 kprobes 移植打进 ksrc 树。可重复执行。
set -e
K=${1:?用法: apply.sh <内核源码目录>}
P=$(cd "$(dirname "$0")" && pwd)

cp "$P/kprobe-trap.c"   "$K/arch/arm64/um/kprobe-trap.c"
cp "$P/probe-mem.c"     "$K/arch/arm64/um/probe-mem.c"
cp "$P/kprobe-signal.c" "$K/arch/arm64/um/os-Linux/kprobe-signal.c"
cp "$P/kretprobe-trampoline.S" "$K/arch/arm64/um/kretprobe-trampoline.S"
cp "$P/mem-host.c"      "$K/arch/arm64/um/os-Linux/mem-host.c"

# <asm/kprobes.h> 的桩默认只给 __kprobes 标注，开了 KPROBES 必须换回父架构真头
cp "$P/kprobes-header.h" "$K/arch/arm64/um/asm/kprobes.h"
cp "$P/kprobes-header-um.h" "$K/arch/um/include/asm/kprobes.h"
cp "$P/daifflags-header.h" "$K/arch/arm64/um/asm/daifflags.h"
cp "$P/traps-header.h"     "$K/arch/arm64/um/asm/traps.h"
cp "$P/gcs-header.h"       "$K/arch/arm64/um/asm/gcs.h"
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

# 5) arch/arm64/um/asm/ptrace.h: kprobes 需要 instruction_pointer_set
#    （instruction_pointer 已经是 ptrace-generic.h 里的宏，不能再定义同名函数）
pt = K / 'arch/arm64/um/asm/ptrace.h'
s = pt.read_text()
if 'instruction_pointer_set' not in s:
    snippet = """
/*
 * generic kernel/kprobes.c（UP 分支）与 arch/arm64/kernel/probes/kprobes.c
 * 都要把 PC 写回 pt_regs；UML 的 ptrace-generic.h 只提供了读宏。
 */
#ifndef instruction_pointer_set
#define instruction_pointer_set(regs, val) (PT_REGS_IP(regs) = (val))
#endif

#ifndef __ASSEMBLY__
/*
 * 父架构的 kernel/probes/simulate-insn.c 按「架构寄存器号」读写 pt_regs，其中
 * 31 表示 XZR 而不是 SP；UML 侧 x0..x30 与 HOST_Xn 一一对应且连续。
 */
static inline unsigned long pt_regs_read_reg(struct pt_regs *regs, int r)
{
	return (r == 31) ? 0 : PT_REGS_X(regs, r);
}

static inline void pt_regs_write_reg(struct pt_regs *regs, int r,
				     unsigned long val)
{
	if (r != 31)
		PT_REGS_X(regs, r) = val;
}

static inline void procedure_link_pointer_set(struct pt_regs *regs,
					      unsigned long val)
{
	PT_REGS_X30(regs) = val;
}
#endif /* !__ASSEMBLY__ */

"""
    i = s.rstrip().rfind('#endif')
    assert i > 0, 'ptrace.h 里找不到结尾 #endif'
    pt.write_text(s[:i] + snippet + s[i:])
PY

# 6) arch/arm64/um/asm/debug-monitors.h：父架构 probes/kprobes.c 要用 BRK 立即数
#    编码（装点 / 软单步）与 DBG_HOOK_*（BRK 是否已被消费）。
python3 - "$K" <<'PY'
import sys, pathlib
K = pathlib.Path(sys.argv[1])
p = K / 'arch/arm64/um/asm/debug-monitors.h'
s = p.read_text()
if 'BRK64_OPCODE_KPROBES' not in s:
    add = """
#include <asm/brk-imm.h>
#include <asm/insn-def.h>

/* 装 kprobe 时把目标指令改写成 BRK；ESR 的 comment 字段区分是哪一种。 */
#define BRK64_OPCODE_KPROBES\t(AARCH64_BREAK_MON | (KPROBES_BRK_IMM << 5))
#define BRK64_OPCODE_KPROBES_SS\t(AARCH64_BREAK_MON | (KPROBES_BRK_SS_IMM << 5))

/* 父架构 probes/kprobes.c 用这两个返回值表示 BRK 是否已被吃掉。 */
#define DBG_HOOK_HANDLED\t0
#define DBG_HOOK_ERROR\t\t1

"""
    i = s.rstrip().rfind('#endif')
    assert i > 0, 'debug-monitors.h 里找不到结尾 #endif'
    p.write_text(s[:i] + add + s[i:])
print('debug-monitors.h: BRK64_OPCODE_* / DBG_HOOK_* 已补')
PY

# 7) 父架构 probes 代码直接把 pstate 当 struct pt_regs 的成员读写；UML 的 pt_regs
#    把它收在 regs 里，只能走 PT_REGS_PSTATE()。
python3 - "$K" <<'PY'
import sys, pathlib
K = pathlib.Path(sys.argv[1])
for rel in ('arch/arm64/kernel/probes/kprobes.c',
            'arch/arm64/kernel/probes/simulate-insn.c'):
    p = K / rel
    s = p.read_text()
    if 'regs->pstate' not in s:
        continue
    p.write_text(s.replace('regs->pstate', 'PT_REGS_PSTATE(regs)'))
    print('%s: regs->pstate 改走 PT_REGS_PSTATE()' % rel)
PY

# 8) 父架构 probes/kprobes.c 剩下的真 arm64 专有写法：regs->pc、regs->regs[n] 在
#    UML 的 pt_regs 里不存在（宿主寄存器收在 regs 里，走 PT_REGS_* 宏）；EL2 的
#    hyp / hyp_idmap 文本段在 UML 上根本没有。
python3 - "$K" <<'PY'
import sys, pathlib
K = pathlib.Path(sys.argv[1])
p = K / 'arch/arm64/kernel/probes/kprobes.c'
s = p.read_text()
s = s.replace('regs->regs[29]', 'PT_REGS_X29(regs)')
s = s.replace('regs->regs[30]', 'PT_REGS_X30(regs)')
s = s.replace('regs->pc', 'PT_REGS_IP(regs)')
hyp = """\tret = kprobe_add_area_blacklist((unsigned long)__hyp_text_start,
\t\t\t\t\t(unsigned long)__hyp_text_end);
\tif (ret || is_kernel_in_hyp_mode())
\t\treturn ret;
\tret = kprobe_add_area_blacklist((unsigned long)__hyp_idmap_text_start,
\t\t\t\t\t(unsigned long)__hyp_idmap_text_end);
"""
if hyp in s and 'UML 没有 EL2' not in s:
    s = s.replace(hyp,
        '#ifndef CONFIG_UML\n/* UML 没有 EL2，不存在 hyp / hyp_idmap 文本段。 */\n'
        + hyp + '#endif\n')
p.write_text(s)
print('probes/kprobes.c: pc/regs[] 走宏，hyp 段按 CONFIG_UML 屏蔽')
PY
