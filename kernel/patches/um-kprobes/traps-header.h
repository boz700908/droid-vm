/* SPDX-License-Identifier: GPL-2.0-only */
/*
 * ARCH=um SUBARCH=arm64 的 <asm/traps.h>。
 *
 * 父架构的这份头只被 kernel/probes/{kprobes,simulate-insn}.c 包含，而这两个文件
 * 并不使用它里面的任何东西。真头却会经 <asm/esr.h> 把 <asm/memory.h> 拉进来，
 * 那份头依赖 CONFIG_ARM64_VA_BITS、virt_to_phys、__ro_after_init 等只对真 arm64
 * 成立的前提，在 UML 上必然编不过。所以这里给一个空壳。
 */
#ifndef __UM_ARM64_TRAPS_H
#define __UM_ARM64_TRAPS_H

#include <asm/ptrace.h>

#endif /* __UM_ARM64_TRAPS_H */
