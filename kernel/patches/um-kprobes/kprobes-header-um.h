/* SPDX-License-Identifier: GPL-2.0-only */
/*
 * <asm/kprobes.h> 在 ARCH=um 下的解析入口。
 *
 * arch/um/include 在 include 搜索顺序里比 arch/arm64/um 更靠前，所以只改
 * arch/arm64/um/asm/kprobes.h 不一定生效；两个位置写成同一份语义：
 * arm64 子架构且开了 KPROBES 时转发到父架构的真头，其余情况保持 asm-generic
 * 的精简版（只提供 __kprobes 段标注）。
 */
#ifndef __UM_ASM_KPROBES_H
#define __UM_ASM_KPROBES_H

#if defined(CONFIG_KPROBES) && defined(__aarch64__)
#include "../../../arm64/include/asm/kprobes.h"
#else
#include <asm-generic/kprobes.h>
#endif

#endif /* __UM_ASM_KPROBES_H */
