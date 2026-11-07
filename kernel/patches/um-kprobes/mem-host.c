// SPDX-License-Identifier: GPL-2.0
/*
 * 宿主侧页权限辅助。
 *
 * 内核文本是本进程 ELF 的 .text（RX 映射），要写入必须先让宿主把它改成可写；
 * 模块与 kprobe 指令槽是 vmalloc 来的普通内存，要执行又必须先改成可执行。
 * 这两件事只有宿主能做，所以放在 os-Linux。
 */
#include <errno.h>
#include <sys/mman.h>
#include <sys/types.h>

int os_mem_set_prot(void *addr, unsigned long len, int prot)
{
	if (mprotect(addr, (size_t)len, prot) == 0)
		return 0;

	return -errno;
}
