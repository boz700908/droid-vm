#!/usr/bin/env python3
"""给 SukiSU 补 ANDROID_GKI_ 前缀的 VFS 命名空间导入（幂等）。

目标内核是 GKI 风格：filp_open / path_get / dentry_open / ihold /
mnt_want_write_file 这些 VFS 内部符号用 ANDROID_GKI_ 前缀的命名空间导出。
上游的 core/init.c 只 import 了不带前缀的名字，modpost 于是直接报

  module kernelsu uses symbol filp_open from namespace ANDROID_GKI_VFS_...,
  but does not import it

两个都留着：换内核时不用再改这里。

用法：python3 um-ns-import.py <sukisu-kernel-目录>
"""
import pathlib
import sys

NS = 'ANDROID_GKI_VFS_internal_I_am_really_a_filesystem_and_am_NOT_a_driver'


def main():
    root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else 'kernel')
    p = root / 'core' / 'init.c'
    s = p.read_text()
    if NS in s:
        print('core/init.c: 命名空间导入已打过')
        return
    anchor = ('MODULE_IMPORT_NS(VFS_internal_I_am_really_a_filesystem_and_am_NOT_a_driver);\n'
              '#endif\n')
    assert s.count(anchor) == 1, 'core/init.c 的 MODULE_IMPORT_NS 结尾块变了'
    add = anchor + (
        '/* KSU_UM_COMPAT: 目标内核是 GKI 风格，VFS 内部符号的命名空间带\n'
        ' * ANDROID_GKI_ 前缀；上游只 import 了不带前缀的名字。\n'
        ' */\n'
        'MODULE_IMPORT_NS("%s");\n' % NS)
    p.write_text(s.replace(anchor, add, 1))
    print('core/init.c: 补上 ANDROID_GKI_ 命名空间导入')


if __name__ == '__main__':
    main()
