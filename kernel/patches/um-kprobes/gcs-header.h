/* SPDX-License-Identifier: GPL-2.0-only */
/*
 * ARCH=um SUBARCH=arm64 的 <asm/gcs.h>。
 *
 * GCS（Guarded Control Stack）是真 arm64 硬件特性，UML guest 没有，恒为未启用。
 * 真头里有 .inst/sys 内联汇编并 include <asm/uaccess.h>（进而 <asm/memory.h>），
 * 在 UML 上编不过；kernel/probes/simulate-insn.c 只用到下面三个入口。
 */
#ifndef __UM_ARM64_GCS_H
#define __UM_ARM64_GCS_H

struct task_struct;

static inline bool task_gcs_el0_enabled(struct task_struct *task)
{
	return false;
}

static inline void push_user_gcs(unsigned long val, int *err) { }

static inline u64 pop_user_gcs(int *err)
{
	*err = -EINVAL;
	return 0;
}

#endif /* __UM_ARM64_GCS_H */
