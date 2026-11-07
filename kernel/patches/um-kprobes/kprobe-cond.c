// SPDX-License-Identifier: GPL-2.0
/*
 * A32 条件执行谓词表（ARCH=um SUBARCH=arm64）。
 *
 * simulate-insn.c 解码到 A32 指令时要先按 NZCV 判断这条指令是否执行，父架构把
 * 这张表放在 arch/arm64/kernel/traps.c，而 UML 不编那个文件，于是 vmlinux 链接
 * 时会缺 aarch32_opcode_cond_checks。这里按 ARM ARM 的条件码定义给出等价实现，
 * 语义逐条照抄父架构（含 cc 0b1111 与 0b1110 等价于 AL 的处理）。
 */
#include <linux/kprobes.h>
#include <linux/types.h>
#include <asm/insn.h>
#include <asm/ptrace.h>

static bool __kprobes um_cond_eq(unsigned long pstate)
{
	return (pstate & PSR_Z_BIT) != 0;
}

static bool __kprobes um_cond_ne(unsigned long pstate)
{
	return (pstate & PSR_Z_BIT) == 0;
}

static bool __kprobes um_cond_cs(unsigned long pstate)
{
	return (pstate & PSR_C_BIT) != 0;
}

static bool __kprobes um_cond_cc(unsigned long pstate)
{
	return (pstate & PSR_C_BIT) == 0;
}

static bool __kprobes um_cond_mi(unsigned long pstate)
{
	return (pstate & PSR_N_BIT) != 0;
}

static bool __kprobes um_cond_pl(unsigned long pstate)
{
	return (pstate & PSR_N_BIT) == 0;
}

static bool __kprobes um_cond_vs(unsigned long pstate)
{
	return (pstate & PSR_V_BIT) != 0;
}

static bool __kprobes um_cond_vc(unsigned long pstate)
{
	return (pstate & PSR_V_BIT) == 0;
}

static bool __kprobes um_cond_hi(unsigned long pstate)
{
	pstate &= ~(pstate >> 1);	/* PSR_C_BIT &= ~PSR_Z_BIT */
	return (pstate & PSR_C_BIT) != 0;
}

static bool __kprobes um_cond_ls(unsigned long pstate)
{
	pstate &= ~(pstate >> 1);	/* PSR_C_BIT &= ~PSR_Z_BIT */
	return (pstate & PSR_C_BIT) == 0;
}

static bool __kprobes um_cond_ge(unsigned long pstate)
{
	pstate ^= (pstate << 3);	/* PSR_N_BIT ^= PSR_V_BIT */
	return (pstate & PSR_N_BIT) == 0;
}

static bool __kprobes um_cond_lt(unsigned long pstate)
{
	pstate ^= (pstate << 3);	/* PSR_N_BIT ^= PSR_V_BIT */
	return (pstate & PSR_N_BIT) != 0;
}

static bool __kprobes um_cond_gt(unsigned long pstate)
{
	/* PSR_N_BIT ^= PSR_V_BIT */
	unsigned long temp = pstate ^ (pstate << 3);

	temp |= (pstate << 1);		/* PSR_N_BIT |= PSR_Z_BIT */
	return (temp & PSR_N_BIT) == 0;
}

static bool __kprobes um_cond_le(unsigned long pstate)
{
	/* PSR_N_BIT ^= PSR_V_BIT */
	unsigned long temp = pstate ^ (pstate << 3);

	temp |= (pstate << 1);		/* PSR_N_BIT |= PSR_Z_BIT */
	return (temp & PSR_N_BIT) != 0;
}

static bool __kprobes um_cond_al(unsigned long pstate)
{
	return true;
}

pstate_check_t * const aarch32_opcode_cond_checks[16] = {
	um_cond_eq, um_cond_ne, um_cond_cs, um_cond_cc,
	um_cond_mi, um_cond_pl, um_cond_vs, um_cond_vc,
	um_cond_hi, um_cond_ls, um_cond_ge, um_cond_lt,
	um_cond_gt, um_cond_le, um_cond_al, um_cond_al
};
