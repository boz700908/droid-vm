// SPDX-License-Identifier: GPL-2.0
/*
 * 宿主 SIGTRAP -> guest kprobes 的桥。
 *
 * 本文件按宿主头文件编译，碰不到 struct pt_regs：只把 mcontext 摊成 34 个字的
 * raw 数组交给内核侧的 um_arm64_kprobe_trap()，再把可能被改过的寄存器搬回去。
 */
#include <string.h>
#include <sys/ucontext.h>

extern int um_arm64_kprobe_trap(unsigned long *raw) __attribute__((weak));

int arch_um_kprobe_trap(mcontext_t *mc)
{
	unsigned long raw[34];
	int i;

	if (!um_arm64_kprobe_trap)
		return 0;

	for (i = 0; i < 31; i++)
		raw[i] = mc->regs[i];
	raw[31] = mc->sp;
	raw[32] = mc->pc;
	raw[33] = mc->pstate;

	if (!um_arm64_kprobe_trap(raw))
		return 0;

	for (i = 0; i < 31; i++)
		mc->regs[i] = raw[i];
	mc->sp = raw[31];
	mc->pc = raw[32];
	mc->pstate = raw[33];
	return 1;
}
