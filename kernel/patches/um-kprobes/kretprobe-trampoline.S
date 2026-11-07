/* SPDX-License-Identifier: GPL-2.0 */
/*
 * kretprobe trampoline for ARCH=um SUBARCH=arm64.
 *
 * 父架构的 kernel/probes/kprobes_trampoline.S 会 include <asm/assembler.h>，
 * 那一串头在 UML 上是 C 专用的（alternative.h / cpufeature.h / ptrace.h 都有
 * 汇编器不认识的写法），所以这里只留下真正需要的那一条 brk。
 *
 * kretprobe 把被 hook 函数的返回地址换成这个符号；执行到 brk 时宿主送来
 * SIGTRAP，arch/um/os-Linux/signal.c 交给 kprobe-trap.c，后者合成 ESR 并转给
 * kretprobe_brk_handler()，handler 把 PC 改成真实返回地址后再回到调用者。
 *
 * 立即数与父架构 asm/brk-imm.h 的 KRETPROBES_BRK_IMM 保持一致（0x007）。
 */
	.text
	.align	2
	.globl	__kretprobe_trampoline
	.type	__kretprobe_trampoline, %function
__kretprobe_trampoline:
	brk	#0x007
1:	b	1b
	.size	__kretprobe_trampoline, . - __kretprobe_trampoline
