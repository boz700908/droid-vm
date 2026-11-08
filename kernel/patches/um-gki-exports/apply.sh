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

# 第三批：SELinux 内部符号。模块要在内核里读/改写 policydb 才能做 sepolicy 伪造，
# GKI 把这一整套都导出了（android/abi_gki_aarch64_* 里能查到），上游内核没有。
# 这确实把「任何 GPL 模块都能重写安全策略」这条路打开了 —— 和 GKI 同样的取舍。
for sym in ('avtab_alloc avtab_destroy avtab_insert_nonunique avtab_search_node '
            'avtab_search_node_next').split():
    want.append(('security/selinux/ss/avtab.c', 'EXPORT_SYMBOL_GPL(%s);' % sym))
for sym in ('ebitmap_contains ebitmap_destroy ebitmap_equal ebitmap_get_bit '
            'ebitmap_set_bit').split():
    want.append(('security/selinux/ss/ebitmap.c', 'EXPORT_SYMBOL_GPL(%s);' % sym))
for sym in ('policydb_context_isvalid policydb_destroy policydb_filenametr_search '
            'policydb_load_isids policydb_read policydb_write').split():
    want.append(('security/selinux/ss/policydb.c', 'EXPORT_SYMBOL_GPL(%s);' % sym))
for sym in ('sidtab_context_to_sid sidtab_destroy sidtab_search_entry '
            'sidtab_sid2str_get sidtab_sid2str_put').split():
    want.append(('security/selinux/ss/sidtab.c', 'EXPORT_SYMBOL_GPL(%s);' % sym))
for sym in ('mls_compute_context_len mls_context_to_sid mls_sid_to_context').split():
    want.append(('security/selinux/ss/mls.c', 'EXPORT_SYMBOL_GPL(%s);' % sym))
for sym in ('symtab_insert symtab_search').split():
    want.append(('security/selinux/ss/symtab.c', 'EXPORT_SYMBOL_GPL(%s);' % sym))
for sym in ('avc_has_perm avc_ss_reset').split():
    want.append(('security/selinux/avc.c', 'EXPORT_SYMBOL_GPL(%s);' % sym))
for sym in ('selinux_state selinux_blob_sizes').split():
    want.append(('security/selinux/hooks.c', 'EXPORT_SYMBOL_GPL(%s);' % sym))
want += [
    ('security/selinux/status.c',   'EXPORT_SYMBOL_GPL(selinux_status_update_policyload);'),
    ('security/selinux/netlink.c',  'EXPORT_SYMBOL_GPL(selnl_notify_policyload);'),
    ('security/selinux/ss/services.c', 'EXPORT_SYMBOL_GPL(security_bounded_transition);'),
    ('security/selinux/ss/hashtab.c', 'EXPORT_SYMBOL_GPL(__hashtab_insert);'),
    ('security/selinux/ss/conditional.c', 'EXPORT_SYMBOL_GPL(cond_compute_av);'),
]
# 第三批的尾巴：几个非 SELinux 的小件，同样是 GKI 导出、这里没有。
want += [
    ('kernel/pid.c',               'EXPORT_SYMBOL_GPL(change_pid);'),
    ('kernel/pid.c',               'EXPORT_SYMBOL_GPL(free_pids);'),
    ('lib/is_single_threaded.c',   'EXPORT_SYMBOL_GPL(current_is_single_threaded);'),
    ('kernel/sys.c',               'EXPORT_SYMBOL_GPL(uts_sem);'),
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
