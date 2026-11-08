#!/usr/bin/env python3
"""把 UML 版钩子层装进 SukiSU 模块树（幂等）。

ARCH=um 下 hook/arm64/{patch_memory,syscall_hook}.c 不编译，kernelsu.ko 链接期
缺 ksu_patch_text / ksu_syscall_table / ksu_syscall_hook_init。这里把仓库里的
hook_um.c 拷进模块树，并在 Kbuild 里给它一个 UML 分支。

用法：python3 um-hook-stub.py <sukisu-kernel-目录>
"""
import pathlib
import shutil
import sys

HERE = pathlib.Path(__file__).resolve().parent
SRC = HERE / 'hook_um.c'

KBUILD_OLD = "ifeq ($(CONFIG_ARM64),y)\nkernelsu-objs += hook/arm64/patch_memory.o\n"
KBUILD_NEW = (
    "ifeq ($(CONFIG_UML),y)\n"
    "# UML 没有可覆盖的 sys_call_table，也没有可改写的内核文本；\n"
    "# 钩子层用一套如实报「不支持」的实现顶上，见 hook/um/hook_um.c。\n"
    "kernelsu-objs += hook/um/hook_um.o\n"
    "else " + KBUILD_OLD
)


def main():
    root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else 'kernel')
    assert SRC.is_file(), 'hook_um.c 不见了：%s' % SRC

    d = root / 'hook' / 'um'
    d.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(SRC, d / 'hook_um.c')
    print('hook/um/hook_um.c: 已写入')

    k = root / 'Kbuild'
    s = k.read_text()
    if 'hook/um/hook_um.o' in s:
        print('Kbuild: 已打过')
        return
    assert s.count(KBUILD_OLD) == 1, 'Kbuild 的 arch 钩子分支变了'
    k.write_text(s.replace(KBUILD_OLD, KBUILD_NEW, 1))
    print('Kbuild: 增加 UML 钩子分支')


if __name__ == '__main__':
    main()
