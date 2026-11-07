
/*
 * --- um-kprobes: patching ---
 *
 * kprobes 与模块需要的文本改写入口。父架构的实现在 arch/arm64/kernel/patching.c，
 * 它靠「可写别名映射」加 stop_machine 保证一致性；UML 两样都没有：内核文本就是本
 * 进程 ELF 的 .text（RX 映射），改写前得让宿主 mprotect 临时开写，改完再收回去；
 * 也没有 stop_machine（arch/um 全树没有这个设施），跨 CPU 只做屏障，本核清 I-cache。
 */
#include <linux/smp.h>
#include <linux/string.h>
#include <asm/page.h>

#define UM_PROT_READ	1
#define UM_PROT_WRITE	2
#define UM_PROT_EXEC	4

extern int os_mem_set_prot(void *addr, unsigned long len, int prot);

int aarch64_insn_patch_text_nosync(void *addr, u32 insn)
{
	unsigned long page = (unsigned long)addr & PAGE_MASK;
	int ret;

	ret = os_mem_set_prot((void *)page, PAGE_SIZE,
			      UM_PROT_READ | UM_PROT_WRITE | UM_PROT_EXEC);
	if (ret)
		return ret;

	WRITE_ONCE(*(u32 *)addr, insn);
	__builtin___clear_cache((char *)addr, (char *)addr + sizeof(insn));

	/* 收回到 RX：这片内存除了指令没有别的用途 */
	os_mem_set_prot((void *)page, PAGE_SIZE, UM_PROT_READ | UM_PROT_EXEC);
	return 0;
}

int aarch64_insn_patch_text(void *addrs[], u32 insns[], int cnt)
{
	int i, ret;

	for (i = 0; i < cnt; i++) {
		ret = aarch64_insn_patch_text_nosync(addrs[i], insns[i]);
		if (ret)
			return ret;
	}

	smp_mb();
	return 0;
}
