// SPDX-License-Identifier: GPL-2.0
//
// 最小 kprobe/kretprobe 自测模块：验证 ARCH=um SUBARCH=arm64 上
// register_kprobe() / register_kretprobe() 真的能把 BRK 打进函数入口、
// 陷入后能回到原流程。只依赖 kprobes API 本身。
//
// 用法（guest 里）：
//   insmod kprobe-test.ko
//   cat /proc/version > /dev/null     # 制造一次 vfs_read，触发探针
//   rmmod kprobe-test
// 探针命中会 pr_info 到 console（UML 的 console 就是宿主 stdout）。

#include <linux/kernel.h>
#include <linux/module.h>
#include <linux/kprobes.h>

static char *probe_sym = "vfs_read";
module_param(probe_sym, charp, 0444);
MODULE_PARM_DESC(probe_sym, "symbol to probe (default: vfs_read)");

static unsigned long n_pre, n_post, n_entry, n_ret;

static int tst_pre(struct kprobe *p, struct pt_regs *regs)
{
	n_pre++;
	if (n_pre <= 3)
		pr_info("kprobe-test: pre  %s pc=%pS\n", p->symbol_name, (void *)instruction_pointer(regs));
	return 0;
}

static void tst_post(struct kprobe *p, struct pt_regs *regs, unsigned long flags)
{
	n_post++;
	if (n_post <= 3)
		pr_info("kprobe-test: post %s\n", p->symbol_name);
}

// 6.18 的 kretprobe_instance 已经没有 rp 成员（rethook 化），直接打参数名
static int tst_entry(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	n_entry++;
	if (n_entry <= 3)
		pr_info("kprobe-test: krprobe entry %s\n", probe_sym);
	return 0;
}

static int tst_ret(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	n_ret++;
	if (n_ret <= 3)
		pr_info("kprobe-test: krprobe return %s\n", probe_sym);
	return 0;
}

static struct kprobe kp = {
	.pre_handler = tst_pre,
	.post_handler = tst_post,
};

static struct kretprobe krp = {
	.entry_handler = tst_entry,
	.handler = tst_ret,
	.maxactive = 16,
};

static int __init tst_init(void)
{
	int rc;

	kp.symbol_name = probe_sym;
	krp.kp.symbol_name = probe_sym;

	rc = register_kprobe(&kp);
	if (rc) {
		pr_err("kprobe-test: register_kprobe(%s) = %d\n", probe_sym, rc);
		return rc;
	}

	rc = register_kretprobe(&krp);
	if (rc) {
		pr_err("kprobe-test: register_kretprobe(%s) = %d\n", probe_sym, rc);
		unregister_kprobe(&kp);
		return rc;
	}

	pr_info("kprobe-test: armed on %s (kprobe + kretprobe)\n", probe_sym);
	return 0;
}

static void __exit tst_exit(void)
{
	unregister_kretprobe(&krp);
	unregister_kprobe(&kp);
	pr_info("kprobe-test: done pre=%lu post=%lu entry=%lu ret=%lu\n",
		n_pre, n_post, n_entry, n_ret);
	if (!n_pre)
		pr_warn("kprobe-test: 探针一次没命中——要么没调用 %s，要么 BRK 陷入没回来\n",
			probe_sym);
}

module_init(tst_init);
module_exit(tst_exit);
MODULE_LICENSE("GPL");
MODULE_DESCRIPTION("kprobe/kretprobe smoke test for UML arm64");
