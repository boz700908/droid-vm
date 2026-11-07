// SPDX-License-Identifier: GPL-2.0
/*
 * UML 侧的 set_memory_*()。
 *
 * 真机上是页表操作，UML 里只能让宿主 mprotect。两处依赖它：
 *   - alloc_insn_page() 要把 kprobe 指令槽改成只读可执行；
 *   - UML 的 vmalloc/execmem 内存默认不可执行，模块与指令槽最终要执行。
 */
#include <linux/kernel.h>
#include <linux/mm.h>
#include <asm/page.h>

#define UM_PROT_READ	1
#define UM_PROT_WRITE	2
#define UM_PROT_EXEC	4

extern int os_mem_set_prot(void *addr, unsigned long len, int prot);

static int um_set_mem(unsigned long addr, int numpages, int prot)
{
	unsigned long start = addr & PAGE_MASK;
	unsigned long end = PAGE_ALIGN(addr + (unsigned long)numpages * PAGE_SIZE);

	if (end <= start)
		return -EINVAL;

	return os_mem_set_prot((void *)start, end - start, prot);
}

int set_memory_ro(unsigned long addr, int numpages)
{
	return um_set_mem(addr, numpages, UM_PROT_READ);
}

int set_memory_rw(unsigned long addr, int numpages)
{
	return um_set_mem(addr, numpages, UM_PROT_READ | UM_PROT_WRITE);
}

int set_memory_rox(unsigned long addr, int numpages)
{
	return um_set_mem(addr, numpages, UM_PROT_READ | UM_PROT_EXEC);
}

int set_memory_rw_nx(unsigned long addr, int numpages)
{
	return um_set_mem(addr, numpages, UM_PROT_READ | UM_PROT_WRITE);
}

int set_memory_x(unsigned long addr, int numpages)
{
	return um_set_mem(addr, numpages,
			  UM_PROT_READ | UM_PROT_WRITE | UM_PROT_EXEC);
}

int set_memory_nx(unsigned long addr, int numpages)
{
	return um_set_mem(addr, numpages, UM_PROT_READ | UM_PROT_WRITE);
}
