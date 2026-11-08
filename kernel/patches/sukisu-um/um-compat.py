#!/usr/bin/env python3
"""修 ARCH=um 下 SukiSU 撞内核同名头 / 缺 vdso 头的问题。

三处都是「真 arm64 上不成立、只在 UML 上暴露」的：

1. include/arch.h → include/ksu-arch.h（连同 22 个文件的 include 一起改）
   ARCH=um 的 KBUILD_CPPFLAGS 里有 -I$(srctree)/arch/um/include/shared，那里躺着
   内核自己的 arch.h（守卫 __ARCH_H__，声明 arch_show_cpuinfo 之类）。SukiSU 的
   #include "arch.h" 于是全部命中它，PT_REGS_* / REBOOT_SYMBOL / SYSCALL_PARM1
   一个都拿不到，clang 一律报「call to undeclared function」。真 arm64 上没有这条
   -I 路径，所以上游一直没发现。改名是唯一不依赖 -I 顺序的解法。

2. hook/syscall_hook.h 补 syscall_fn_t
   arm64 的 syscall_fn_t 定义在 arch/arm64/include/asm/syscall.h；UML 走的是
   arch/arm64/um/asm/syscall.h，里面只有老式 6 参数 sys_call_ptr_t。

3. extension/cpu_spoof.c 在 UML 下跳过 vdso 页改写
   <vdso/datapage.h> 会拉进 arch/arm64/include/asm/vdso/gettimeofday.h →
   asm/arch_timer.h，里面的 ALTERNATIVE / isb / cntpct_el0 在 UML 上既编不出，
   UML 也没有可改写的 vdso 页。跳过并如实记账（++missing），不谎报成功。
"""
import pathlib
import re
import sys

MARK = 'KSU_UM_COMPAT'


def patch_arch_include(root):
    src = root / 'include' / 'arch.h'
    dst = root / 'include' / 'ksu-arch.h'
    if dst.exists():
        print('include/arch.h: 已改名为 ksu-arch.h')
    elif src.exists():
        src.rename(dst)
        print('include/arch.h: → ksu-arch.h')
    else:
        raise SystemExit('include/arch.h 不见了，SukiSU 的目录结构变了')

    files = sorted(list(root.rglob('*.c')) + list(root.rglob('*.h')))
    n = 0
    for p in files:
        s = p.read_text()
        if '#include "arch.h"' not in s:
            continue
        p.write_text(s.replace('#include "arch.h"', '#include "ksu-arch.h"'))
        n += 1
    left = [str(p.relative_to(root)) for p in files if '#include "arch.h"' in p.read_text()]
    assert not left, '还有文件引用 arch.h: %s' % left
    print('include/arch.h: %d 个文件改成 #include "ksu-arch.h"' % n)


def patch_arch_symbols(root):
    """REBOOT_SYMBOL / SYS_*_SYMBOL 与寄存器布局无关，UML 分支里也要有。

    ptregs-um.py 把整个 __aarch64__ 块包成「CONFIG_UML 分支 / #else 原块」，
    而 #else 里除了必须关掉的字段式宏，还塞着这几个符号名——supercall.c 用它就
    报 undeclared。这里把 #else 里的 *_SYMBOL 定义原样复制一份到 UML 分支，
    上游以后再加一个符号名也自动跟上。
    """
    p = root / 'include' / 'ksu-arch.h'
    s = p.read_text()
    if MARK + '符号名' in s:
        print('ksu-arch.h: 符号名已复制')
        return
    anchor = '#else\n#define __PT_PARM1_REG regs[0]\n'
    assert anchor in s, 'ksu-arch.h 的 #else 分支结构变了'
    head, tail = s.split(anchor, 1)
    body = tail.split('#endif /* CONFIG_UML */', 1)[0]
    syms = [ln for ln in body.splitlines() if re.match(r'#define [A-Z0-9_]+_SYMBOL ', ln)]
    assert syms, '#else 分支里没有 *_SYMBOL 定义'
    add = ('/* %s符号名：与 pt_regs 布局无关，UML 分支也要有。 */\n' % MARK
           + '\n'.join(syms) + '\n\n' + anchor)
    p.write_text(head + add + tail)
    print('ksu-arch.h: 复制了 %d 个 *_SYMBOL 到 UML 分支' % len(syms))


def patch_syscall_hook_h(root):
    p = root / 'hook' / 'syscall_hook.h'
    s = p.read_text()
    if MARK in s:
        print('syscall_hook.h: 已打过')
        return
    anchor = ('#elif defined(__riscv)\n'
              'typedef long (*syscall_fn_t)(const struct pt_regs *);\n'
              '#endif\n')
    assert anchor in s, 'syscall_hook.h 的 typedef 块结构变了'
    add = ('#elif defined(__riscv)\n'
           'typedef long (*syscall_fn_t)(const struct pt_regs *);\n'
           '#elif defined(__aarch64__) && defined(CONFIG_UML)\n'
           '/* %s: arm64 的 syscall_fn_t 在 arch/arm64/include/asm/syscall.h；\n'
           ' * UML 走 arch/arm64/um/asm/syscall.h，那里只有老式 6 参数 sys_call_ptr_t。\n'
           ' */\n'
           'typedef long (*syscall_fn_t)(const struct pt_regs *);\n'
           '#endif\n') % MARK
    p.write_text(s.replace(anchor, add, 1))
    print('syscall_hook.h: 补了 UML 版 syscall_fn_t')


def patch_cpu_spoof(root):
    p = root / 'extension' / 'cpu_spoof.c'
    s = p.read_text()
    if MARK in s:
        print('cpu_spoof.c: 已打过')
        return
    inc = '#include <vdso/datapage.h>\n#include <vdso/clocksource.h>\n'
    assert inc in s, 'cpu_spoof.c 的 vdso 头不再是连着两行'
    s = s.replace(inc,
                  '/* %s: UML 没有 arm64 的 vdso 页；这两个头会拉进 arch_timer 的\n'
                  ' * ALTERNATIVE/isb 内联汇编，UML 编不出来。 */\n'
                  '#if !defined(CONFIG_UML)\n' % MARK + inc + '#endif\n', 1)
    vdso = '#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 18, 0)\n'
    assert s.count(vdso) == 1, 'vdso 写回块的位置变了'
    s = s.replace(vdso,
                  '#if defined(CONFIG_UML)\n'
                  '                /* UML 没有可改写的 vdso 页：跳过，但如实记账。 */\n'
                  '                pr_warn("ksu: cpu spoof 跳过 vdso 时钟改写（UML）\\n");\n'
                  '                ++missing;\n'
                  '#elif LINUX_VERSION_CODE >= KERNEL_VERSION(6, 18, 0)\n', 1)
    p.write_text(s)
    print('cpu_spoof.c: UML 下跳过 vdso 改写')


if __name__ == '__main__':
    root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else 'kernel')
    patch_arch_include(root)
    patch_arch_symbols(root)
    patch_syscall_hook_h(root)
    patch_cpu_spoof(root)
