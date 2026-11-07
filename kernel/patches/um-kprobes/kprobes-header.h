/* SPDX-License-Identifier: GPL-2.0-only */
/*
 * ARCH=um SUBARCH=arm64 的 <asm/kprobes.h>。
 *
 * 默认（CONFIG_KPROBES=n）只需要 __kprobes 这个段标注可用：arch/arm64/lib/insn.c
 * 被复用来做指令编解码，里面的函数标了 __kprobes，那只是段标注，不依赖 kprobes
 * 机制本身。而 arm64 的真头会经 asm/probes.h 牵出 cpufeature 一整套，白白拖进
 * cpucap-defs.h，所以默认保持 asm-generic 的精简版。
 *
 * CONFIG_KPROBES=y 时反过来必须用父架构的完整定义：kprobe_opcode_t、
 * MAX_INSN_SIZE、struct arch_specific_insn、struct kprobe_ctlblk，以及
 * kprobe_brk_handler()/kprobe_ss_brk_handler()/kretprobe_brk_handler() 的原型
 * 都在 arch/arm64/include/asm/kprobes.h 里。UML 换的只是陷入入口，其余照用。
 */
#ifndef __UM_ARM64_KPROBES_H
#define __UM_ARM64_KPROBES_H

#if defined(CONFIG_KPROBES) && defined(__aarch64__)
#include "../../include/asm/kprobes.h"
#else
#include <asm-generic/kprobes.h>
#endif

#endif /* __UM_ARM64_KPROBES_H */
