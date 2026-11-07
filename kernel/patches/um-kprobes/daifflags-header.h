/* SPDX-License-Identifier: GPL-2.0-only */
#ifndef __UM_ARM64_DAIFFLAGS_H
#define __UM_ARM64_DAIFFLAGS_H

/*
 * arm64 的 <asm/daifflags.h> 直接读写 DAIF 系统寄存器，UML 上没有这个寄存器：
 * 本地中断在 UML 里是靠屏蔽宿主信号实现的（arch/um/include/asm/irqflags.h），
 * 所以把 local_daif_* 整体映射到 arch_local_*。
 *
 * 被 arch/arm64/kernel/probes/kprobes.c 与 kernel/kprobes.c 用到。
 */
#include <linux/irqflags.h>
#include <linux/types.h>

struct pt_regs;

#define DAIF_PROCCTX		0UL
#define DAIF_PROCCTX_NOIRQ	0UL
#define DAIF_ERRCTX		0UL
#define DAIF_MASK		0UL

/* UML 没有 DAIF.I 可查；调用点只用于决定是否要恢复，返回 false 最保守。 */
static inline bool irqs_are_masked(void)
{
	return false;
}

static inline void local_daif_mask(void)
{
	arch_local_irq_disable();
}

static inline void local_daif_unmask(void)
{
	arch_local_irq_enable();
}

static inline unsigned long local_daif_save_flags(void)
{
	return arch_local_save_flags();
}

static inline unsigned long local_daif_save(void)
{
	unsigned long flags = arch_local_save_flags();

	arch_local_irq_disable();
	return flags;
}

static inline void local_daif_restore(unsigned long flags)
{
	arch_local_irq_restore(flags);
}

static inline void local_daif_inherit(struct pt_regs *regs)
{
	(void)regs;
}

#endif /* __UM_ARM64_DAIFFLAGS_H */
