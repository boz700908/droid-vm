// SPDX-License-Identifier: GPL-2.0
/*
 * kprobes 的 UML 陷入路由（ARCH=um SUBARCH=arm64）。
 *
 * 真机上 BRK 走 do_debug_exception() -> call_break_hook()，由 debug-monitors.c
 * 按 ESR 里的 comment 把活儿分给父架构的三个 handler。UML 里内核文本就是本进程
 * 可执行的代码，执行 BRK 只会得到宿主投来的 SIGTRAP，于是
 * arch/um/os-Linux/signal.c 把该信号截住、整理成 x0..x30/sp/pc/pstate 交到这里：
 * 本文件把 raw 数组翻译成 struct pt_regs + ESR，调用同一个 handler，再把 handler
 * 改过的寄存器写回 raw，让宿主恢复执行时用的就是改过的状态。
 */
#include <linux/kallsyms.h>
#include <linux/kprobes.h>
#include <linux/sched.h>
#include <linux/smp.h>
#include <linux/string.h>
#include <asm/brk-imm.h>
#include <asm/debug-monitors.h>
#include <asm/kprobes.h>
#include <asm/ptrace.h>

/*
 * 父架构 <asm/esr.h> 会经 <asm/memory.h> 拉入真 arm64 的地址空间常量
 * （CONFIG_ARM64_VA_BITS、virt_to_phys 等），UML 侧编不过。这里只用到两个
 * ESR 编码常量，按 ARM ARM 的定义直接写。
 */
#define ESR_ELx_EC_SHIFT	26
#define ESR_ELx_EC_BRK64	0x3c

/* raw[] 的布局，必须与 arch/arm64/um/os-Linux/kprobe-signal.c 一致 */
enum {
	UM_RAW_X0,
	UM_RAW_SP = 31,
	UM_RAW_PC,
	UM_RAW_PSTATE,
	UM_RAW_WORDS,
};

/* 每核防递归：kprobe 的 handler 自己再触发 BRK 时不再进入 */
static int um_kprobe_busy[NR_CPUS];

int um_arm64_kprobe_trap(unsigned long *raw)
{
	struct pt_regs regs;
	unsigned long pc = raw[UM_RAW_PC];
	unsigned long esr;
	unsigned int insn;
	int cpu, imm, ret, i;
	bool ours;

	cpu = raw_smp_processor_id();
	if (um_kprobe_busy[cpu])
		return 0;

	if (!kernel_text_address(pc))
		return 0;

	insn = READ_ONCE(*(unsigned int *)pc);
	if ((insn & 0xffe0001f) != AARCH64_BREAK_MON)
		return 0;

	imm = (insn >> 5) & 0xffff;
	ours = (imm == KPROBES_BRK_IMM) || (imm == KPROBES_BRK_SS_IMM);
#ifdef CONFIG_KRETPROBES
	ours = ours || (imm == KRETPROBES_BRK_IMM);
#endif
	if (!ours)
		return 0;

	memset(&regs, 0, sizeof(regs));
	for (i = 0; i < 31; i++)
		regs.regs[i] = raw[UM_RAW_X0 + i];
	regs.sp = raw[UM_RAW_SP];
	regs.pc = pc;
	regs.syscallno = NO_SYSCALL;
	/*
	 * 这是内核态 BRK：把模式位换成 EL1 再交出去，否则 kprobes 内部的
	 * user_mode(regs) 会把一次内核陷入当成用户态陷入。宿主线程真正的
	 * EL/DAIF 由信号帧自己保存，所以这个改动不回写。
	 */
	regs.pstate = (raw[UM_RAW_PSTATE] & ~PSR_MODE_MASK) | PSR_MODE_EL1h;

	esr = ((unsigned long)ESR_ELx_EC_BRK64 << ESR_ELx_EC_SHIFT) | imm;

	um_kprobe_busy[cpu] = 1;
	switch (imm) {
	case KPROBES_BRK_IMM:
		ret = kprobe_brk_handler(&regs, esr);
		break;
	case KPROBES_BRK_SS_IMM:
		ret = kprobe_ss_brk_handler(&regs, esr);
		break;
#ifdef CONFIG_KRETPROBES
	case KRETPROBES_BRK_IMM:
		ret = kretprobe_brk_handler(&regs, esr);
		break;
#endif
	default:
		ret = 0;
		break;
	}
	um_kprobe_busy[cpu] = 0;

	if (!ret)
		return 0;

	for (i = 0; i < 31; i++)
		raw[UM_RAW_X0 + i] = regs.regs[i];
	raw[UM_RAW_SP] = regs.sp;
	raw[UM_RAW_PC] = regs.pc;
	return 1;
}
NOKPROBE_SYMBOL(um_arm64_kprobe_trap);

/*
 * 父架构在 arch/arm64/mm/fault.c 里实现，UML 的缺页路径不经过 kprobes，
 * 返回 0 表示「不由 kprobe 处理」，与父架构的语义一致。
 */
int kprobe_fault_handler(struct pt_regs *regs, unsigned int fsr)
{
	(void)regs;
	(void)fsr;
	return 0;
}
