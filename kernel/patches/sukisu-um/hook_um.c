// SPDX-License-Identifier: GPL-2.0-only
/*
 * KSU_UM_COMPAT: ARCH=um（SUBARCH=arm64）的钩子层实现。
 *
 * UML 的用户态系统调用由 UML 自己的架构层分派，没有 arm64 那种可以直接覆盖的
 * sys_call_table；内核文本也不是能随便写的内存（UML 内核跑在宿主进程的地址空间
 * 里，改写只会炸掉自己）。所以这两个能力在 UML 上是明确不支持的：
 *
 *   - ksu_patch_text() 返回 -EOPNOTSUPP，调用方按失败处理（mount_hide /
 *     selinux_hide 会在日志里如实报出来，不会假装成功）；
 *   - ksu_syscall_table 指向一张全 -ENOSYS 的占位表，任何被误走到的地方只会
 *     拿到错误码，不会空指针崩内核；
 *   - ksu_syscall_hook_init() 只打一条 pr_info。
 *
 * 也就是说：UML 上 KPM / SUSFS / 模块挂载可用，「系统调用级 hook」不可用。
 * 这是能力边界，不是静默降级。
 */
#include "../patch_memory.h"
#include "../syscall_hook.h"
#include "klog.h"

#include <linux/errno.h>
#include <linux/kernel.h>
#include <linux/types.h>

#define KSU_UM_SYSCALL_SLOTS 1024

static long ksu_um_sys_ni(const struct pt_regs *regs)
{
	(void)regs;
	return -ENOSYS;
}

static syscall_fn_t ksu_um_syscall_table[KSU_UM_SYSCALL_SLOTS] = {
	[0 ... KSU_UM_SYSCALL_SLOTS - 1] = ksu_um_sys_ni,
};

syscall_fn_t *ksu_syscall_table = ksu_um_syscall_table;
int ksu_dispatcher_nr = -1;

unsigned long phys_from_virt(unsigned long addr, int *err)
{
	(void)addr;
	if (err)
		*err = -EOPNOTSUPP;
	return 0;
}

int ksu_patch_text(void *dst, void *src, size_t len, int flags)
{
	(void)dst;
	(void)src;
	(void)len;
	(void)flags;
	return -EOPNOTSUPP;
}

void *scan_call_to(void *start, size_t size, void *target)
{
	(void)start;
	(void)size;
	(void)target;
	return NULL;
}

void ksu_syscall_hook_init(void)
{
	pr_info("ksu: 系统调用 hook 在 UML 上不可用，已跳过\n");
}

void ksu_syscall_hook_exit(void)
{
}

int ksu_register_syscall_hook(int nr, ksu_syscall_hook_fn fn)
{
	(void)nr;
	(void)fn;
	return -EOPNOTSUPP;
}

void ksu_unregister_syscall_hook(int nr)
{
	(void)nr;
}

bool ksu_has_syscall_hook(int nr)
{
	(void)nr;
	return false;
}

void ksu_syscall_table_hook(int nr, syscall_fn_t fn, syscall_fn_t *old)
{
	(void)nr;
	(void)fn;
	if (old)
		*old = NULL;
}

void ksu_syscall_table_unhook(int nr)
{
	(void)nr;
}
