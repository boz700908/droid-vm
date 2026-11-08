// SPDX-License-Identifier: GPL-2.0
/*
 * arm64 syscall entry trampolines for out-of-tree modules on ARCH=um.
 *
 * The real arm64 port wraps every syscall in __arm64_sys_<name>(struct
 * pt_regs *) (arch/arm64/include/asm/syscall_wrapper.h) and GKI kernels export
 * those trampolines, because the entry path passes registers in a pt_regs. UML
 * does not: handle_syscall() unpacks the six arguments itself and the table in
 * arch/arm64/um/sys_call_table.c holds the plain sys_<name>(...) functions. So
 * CONFIG_ARCH_HAS_SYSCALL_WRAPPER is off and no __arm64_sys_* symbol exists —
 * see the comment at the top of sys_call_table.c, which says exactly this.
 *
 * A module written for arm64 that calls a syscall from kernel space therefore
 * has nothing to bind to, and modpost reports "__arm64_sys_close undefined".
 * The three trampolines below have the same name and signature as arm64 and
 * read the arguments out of the caller's pt_regs the same way the arm64 wrapper
 * does: SYSCALL_PARM1 = x0, PARM2 = x1, and so on.
 *
 * Only the three syscalls SukiSU calls that way are here (close, setns,
 * unshare). Add more in the same shape; PT_REGS_X() from
 * arch/arm64/um/asm/ptrace.h is the accessor.
 *
 * Guarded by !CONFIG_ARCH_HAS_SYSCALL_WRAPPER: on a real arm64 build the
 * trampolines already exist and defining them again would clash.
 */
#include <linux/export.h>
#include <linux/linkage.h>
#include <linux/ptrace.h>

#ifndef CONFIG_ARCH_HAS_SYSCALL_WRAPPER

/*
 * Declared the way arch/arm64/um/sys_call_table.c declares them: the real
 * functions take typed arguments, but a syscall table entry is the six-register
 * form, and extra arguments cost nothing in the calling convention.
 */
extern asmlinkage long sys_close(unsigned long, unsigned long, unsigned long,
				 unsigned long, unsigned long, unsigned long);
extern asmlinkage long sys_setns(unsigned long, unsigned long, unsigned long,
				 unsigned long, unsigned long, unsigned long);
extern asmlinkage long sys_unshare(unsigned long, unsigned long, unsigned long,
				   unsigned long, unsigned long, unsigned long);

/* Prototypes first: the kernel builds with -Wmissing-prototypes. */
asmlinkage long __arm64_sys_close(const struct pt_regs *regs);
asmlinkage long __arm64_sys_setns(const struct pt_regs *regs);
asmlinkage long __arm64_sys_unshare(const struct pt_regs *regs);

asmlinkage long __arm64_sys_close(const struct pt_regs *regs)
{
	return sys_close(PT_REGS_X(regs, 0), 0, 0, 0, 0, 0);
}
EXPORT_SYMBOL_GPL(__arm64_sys_close);

asmlinkage long __arm64_sys_setns(const struct pt_regs *regs)
{
	return sys_setns(PT_REGS_X(regs, 0), PT_REGS_X(regs, 1), 0, 0, 0, 0);
}
EXPORT_SYMBOL_GPL(__arm64_sys_setns);

asmlinkage long __arm64_sys_unshare(const struct pt_regs *regs)
{
	return sys_unshare(PT_REGS_X(regs, 0), 0, 0, 0, 0, 0);
}
EXPORT_SYMBOL_GPL(__arm64_sys_unshare);

#endif /* !CONFIG_ARCH_HAS_SYSCALL_WRAPPER */
