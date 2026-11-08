#!/usr/bin/env python3
"""把 SukiSU 内核模块的 arm64 寄存器访问改到 UML 也能编的写法。

为什么不能只改宏：SukiSU 的字段式写法把 `->` 和变量名钉死在调用点上，
`p->__PT_PARM2_REG` 能替换的只有右边，展开永远是 `p-><字段>`。而 UML 的
struct pt_regs 里 `regs` 是一个结构体快照（struct uml_pt_regs），不是 arm64
的 unsigned long regs[]，于是 `p->regs[1]` 报 "subscripted value is not an
array, pointer, or vector"，`p->pc` / `p->sp` / `p->pstate` 直接不存在。

所以分两步，而且两步缺一不可：
  1. include/arch.h：给 __aarch64__ 加一个 CONFIG_UML 分支，用内核自己的
     UPT_* 访问器定义同名的函数式宏；原来的字段式宏块在 UML 下整体跳过。
  2. 源码里 `p->__PT_X_REG` 改成 `PT_REGS_X(p)`（包括赋值——UPT_X 是可赋值的
     左值，sucompat 那几行要写回寄存器）。

这个补丁只保证「编得出来、寄存器读写落在 host 快照上」。sucompat 的
execveat 替换依赖改内核 syscall 表，UML 没有那条路径，所以那段逻辑即使编过
也不会生效——那是功能问题，不是编译问题。
"""
import pathlib
import re
import sys

MARK = 'KSU_PTREGS_IS_UML'

UML_BLOCK = '''#if defined(CONFIG_UML)

/*
 * UML 的 struct pt_regs 是一层 host 寄存器快照：寄存器在 pt_regs.regs
 * （struct uml_pt_regs）里，没有 arm64 的 regs[] 数组，也没有 pc / sp。
 * 这里把 SukiSU 的访问器全部换成内核自己的 UPT_* 宏。
 *
 * 不在这里定义 PT_REGS_IP / PT_REGS_ORIG_SYSCALL：UML 的 asm/ptrace.h
 * 已经有它们，重复定义反而会盖掉正确的实现。
 */
#define %s 1
#define PT_REGS_PARM1(x)         PT_REGS_X((x), 0)
#define PT_REGS_SYSCALL_PARM1(x) PT_REGS_X((x), 0)
#define PT_REGS_PARM2(x)         PT_REGS_X((x), 1)
#define PT_REGS_PARM3(x)         PT_REGS_X((x), 2)
#define PT_REGS_SYSCALL_PARM4(x) PT_REGS_X((x), 3)
#define PT_REGS_CCALL_PARM4(x)   PT_REGS_X((x), 3)
#define PT_REGS_PARM5(x)         PT_REGS_X((x), 4)
#define PT_REGS_PARM6(x)         PT_REGS_X((x), 5)
#define PT_REGS_RET(x)           PT_REGS_X30(x)
#define PT_REGS_FP(x)            PT_REGS_X29(x)
#define PT_REGS_RC(x)            PT_REGS_X0(x)
/* UML 的 sp 访问器带参数，形如 PT_REGS_SP_REG(r) */
#define PT_REGS_SP(x)            PT_REGS_SP_REG(x)

#else
''' % MARK

FIELD_RE = re.compile(r'([A-Za-z_][A-Za-z0-9_]*)->__PT_([A-Z0-9_]+)_REG\b')
FIELD_NAMES = {
    'PARM1': 'PARM1', 'PARM2': 'PARM2', 'PARM3': 'PARM3', 'PARM4': 'SYSCALL_PARM4',
    'PARM5': 'PARM5', 'PARM6': 'PARM6', 'SYSCALL_PARM4': 'SYSCALL_PARM4',
    'CCALL_PARM4': 'CCALL_PARM4', 'SYSCALL_PARM1': 'SYSCALL_PARM1',
    'RET': 'RET', 'FP': 'FP', 'RC': 'RC', 'SP': 'SP', 'IP': 'IP',
    'ORIG_SYSCALL': 'ORIG_SYSCALL',
}


def patch_arch_h(root):
    p = root / 'include' / 'arch.h'
    s = p.read_text()
    if MARK in s:
        print('arch.h: 已打过')
        return
    head = '#if defined(__aarch64__)\n\n'
    assert head in s, 'arch.h 结构与预期不符（找不到 __aarch64__ 分支）'
    s = s.replace(head, head + UML_BLOCK, 1)
    tail = '\n#elif defined(__x86_64__)\n'
    assert tail in s
    s = s.replace(tail, '\n#endif /* CONFIG_UML */\n' + tail, 1)

    # 原来那整块字段式访问器在 UML 下必须整体跳过，否则会盖掉上面的定义。
    first = '#define PT_REGS_PARM1(x) (__PT_REGS_CAST(x)->__PT_PARM1_REG)\n'
    last = '#define PT_REGS_ORIG_SYSCALL(x) (__PT_REGS_CAST(x)->__PT_ORIG_SYSCALL_REG)\n'
    assert first in s and last in s
    s = s.replace(first, '#if !defined(%s)\n%s' % (MARK, first), 1)
    s = s.replace(last, last + '#endif /* !%s */\n' % MARK, 1)
    p.write_text(s)
    print('arch.h: 已加 CONFIG_UML 分支')


def patch_sources(root):
    total = 0
    for p in sorted(root.rglob('*.c')):
        s = p.read_text()
        if '->__PT_' not in s:
            continue
        def sub(m):
            var, name = m.group(1), FIELD_NAMES.get(m.group(2))
            if name is None:
                raise SystemExit('未知寄存器宏 __PT_%s_REG in %s' % (m.group(2), p))
            return 'PT_REGS_%s(%s)' % (name, var)
        new, n = FIELD_RE.subn(sub, s)
        if n:
            p.write_text(new)
            print('%-46s %d 处' % (p.relative_to(root), n))
            total += n
    print('源码: 共改 %d 处' % total)


def patch_syscall_hook_manager(root):
    """把 arm64 专用的 syscall 重定向从 UML 里摘出去。

    ksu_sys_enter_handler 的 __aarch64__ 分支要用 is_compat_task()（arm64 的
    compat 概念，UML 没有）和 pt_regs->syscallno（arm64 字段，UML 没有）。
    这条路径本来就要靠改 el0_svc 生效，UML 里没有 el0_svc 可改：照原样编只会
    绕一大圈得到「编不过」，不如明确摘掉并写清楚为什么。
    """
    p = root / 'hook' / 'syscall_hook_manager.c'
    s = p.read_text()
    if 'CONFIG_UML' in s:
        print('syscall_hook_manager.c: 已打过'); return
    n = s.count('#elif defined(__aarch64__)')
    assert n == 2, n
    s = s.replace('#elif defined(__aarch64__)',
        '/* UML: 没有 compat 任务，也没有 pt_regs->syscallno 与 el0_svc */\n'
        '#elif defined(__aarch64__) && !defined(CONFIG_UML)')
    p.write_text(s)
    print('syscall_hook_manager.c: 摘掉 %d 处 arm64 分支' % n)


if __name__ == '__main__':
    root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else 'kernel')
    patch_arch_h(root)
    patch_sources(root)
    patch_syscall_hook_manager(root)
