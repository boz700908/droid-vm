#!/usr/bin/env bash
# 补上 Android GKI 树导出、这棵 linux-um-arm64-gki 没导出的符号。
# 外部 .ko（SukiSU 一类）默认这些符号在内核侧可用，缺一个就 modpost undefined。
# 每个都是「文件末尾追加一行导出」，可重复执行。
set -euo pipefail
K="${1:?用法: apply.sh <内核树>}"
[ -d "$K" ] || { echo "找不到内核树 $K"; pwd; exit 1; }

python3 - "$K" <<'PYEOF'
import pathlib, sys
K = pathlib.Path(sys.argv[1])

# (相对路径, 追加的导出行)
want = [
    ('fs/namespace.c',             'EXPORT_SYMBOL_GPL(mntns_operations);'),
    ('fs/nsfs.c',                  'EXPORT_SYMBOL_GPL(ns_get_path);'),
    # __arm64_sys_close 不是「导出」能解决的：UML 根本没这个函数，
    # 由 um-syscall-shim/ 补一个真蹦床。
    ('kernel/kallsyms.c',          'EXPORT_SYMBOL_GPL(kallsyms_lookup_size_offset);'),
    ('mm/maccess.c',               'EXPORT_SYMBOL_GPL(strncpy_from_user_nofault);'),
    # sys_enter 事件本身在，只是没人导出过；模块靠它注册 syscall 钩子。
    ('kernel/trace/trace_syscalls.c', 'EXPORT_TRACEPOINT_SYMBOL_GPL(sys_enter);'),
    # 第二批：SukiSU 在 arm64/GKI 上直接用的内部函数，本树有定义但没导出。
    ('fs/namespace.c',             'EXPORT_SYMBOL_GPL(path_mount);'),
    ('fs/fs_struct.c',             'EXPORT_SYMBOL_GPL(set_fs_pwd);'),
    ('fs/open.c',                  'EXPORT_SYMBOL_GPL(dentry_open_nonotify);'),
    ('kernel/kallsyms.c',          'EXPORT_SYMBOL_GPL(kallsyms_lookup_name);'),
    ('kernel/kallsyms.c',          'EXPORT_SYMBOL_GPL(kallsyms_lookup);'),
    ('kernel/kallsyms.c',          'EXPORT_SYMBOL_GPL(kallsyms_on_each_symbol);'),
    ('kernel/user.c',              'EXPORT_SYMBOL_GPL(alloc_uid);'),
    ('kernel/cred.c',              'EXPORT_SYMBOL_GPL(set_cred_ucounts);'),
    ('kernel/seccomp.c',           'EXPORT_SYMBOL_GPL(seccomp_filter_release);'),
    ('fs/ext4/sysfs.c',            'EXPORT_SYMBOL_GPL(ext4_unregister_sysfs);'),
]

for rel, line in want:
    f = K / rel
    assert f.is_file(), '缺文件 %s' % rel
    s = f.read_text()
    if line in s:
        print('已存在 %-30s %s' % (rel, line))
        continue
    if not s.endswith('\n'):
        s += '\n'
    f.write_text(s + '\n' + line + '\n')
    print('已追加 %-30s %s' % (rel, line))
PYEOF
