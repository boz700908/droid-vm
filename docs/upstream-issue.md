# 上游 issue 草稿（lakitu12/linux-um-arm64-gki）

> 待 CI 全绿后再发。附的是仓库里的单补丁
> `kernel/patches/0001-um-arm64-kprobes-and-module-loading.patch`（21 文件）。
> 正文用英文，便于上游阅读；中文备注只在本文件里。

## 标题

Title (English): **`arm64 UML: add kprobes support so external kernel modules (KSU-style LKMs) can load`**

## 正文（English）

Hi, and thanks for maintaining this tree — it is the only arm64 UML + GKI base I found
that runs a real 6.18 kernel as an aarch64 userspace process on an unrooted Android
phone, which is exactly the use case I needed.

**Problem.** `CONFIG_KPROBES` cannot be enabled on `ARCH=um SUBARCH=arm64`:

* `HAVE_KPROBES` / `HAVE_KRETPROBES` are selected only in `arch/arm64/Kconfig`, and
  `ARCH=um` reads `arch/um/Kconfig` instead, so the symbols are never offered.
* Even after adding the `select`s, `arch/arm64/kernel/probes/*.c` does not compile: the
  port has no `asm/kprobes.h`, arm64's `struct pt_regs` accessors (`regs->pc`,
  `regs->pstate`, `regs->regs[n]`) do not exist in UML's pt_regs, the kprobes BRK
  encoding lives in a header that pulls the real `asm/memory.h`, and there is no trap
  path from a host `SIGTRAP` into `kernel/kprobes.c`.
* `set_memory_ro/rw/rox/...` (used by the module loader and probe memory protection)
  have no UML implementation.

This matters because a whole class of out-of-tree modules depends on kprobes:
KernelSU / SukiSU / KPM-style LKMs, live-patching modules, and anything calling
`register_kprobe()`. Without it an LKM builds but cannot hook anything, so the tree
cannot be used as a Magisk/KSU host — the main reason people want a real kernel under
UML in the first place.

**What the patch does** (five layers, all inside `arch/`):

1. **Trap routing.** `arch/um/os-Linux/signal.c` intercepts a host `SIGTRAP` first and
   hands the register file to `arch/arm64/um/kprobe-trap.c`, which materialises a
   `struct pt_regs` + a synthetic ESR (EC = 0x3c, comment = the BRK immediate) and calls
   the existing non-static handlers `kprobe_brk_handler()` /
   `kprobe_ss_brk_handler()` / `kretprobe_brk_handler()`, then writes the (possibly
   rewritten) registers back so host resume continues from the patched state.
   Recursion is blocked per CPU.
2. **Text patching.** UML has no `stop_machine()`, so
   `aarch64_insn_patch_text{,_nosync}()` are implemented directly: host `mprotect()` the
   page writable, `WRITE_ONCE` the instruction word, `__builtin___clear_cache()`,
   `smp_mb()`.
3. **Page permissions.** `set_memory_ro/rw/rox/rw_nx/x/nx` map onto a new
   `os_mem_set_prot()` helper (`mprotect` / `-errno`).
4. **Headers and config.** `arch/um/Kconfig` selects `HAVE_KPROBES` /
   `HAVE_KRETPROBES`; `arch/arm64/um/asm/kprobes.h` and
   `arch/um/include/asm/kprobes.h` forward to the real arm64 header when
   `CONFIG_KPROBES && __aarch64__` (x86 UML keeps `asm-generic/kprobes.h`); minimal
   UML-side `traps.h`, `gcs.h`, `daifflags.h` stand-ins replace headers that only make
   sense on real arm64 hardware; `debug-monitors.h` gets the
   `BRK64_OPCODE_KPROBES{,_SS}` encodings and `DBG_HOOK_*`; the arm64-only accesses in
   `probes/kprobes.c` (`pstate`, `pc`, `regs[]`) go through the `PT_REGS_*` accessors,
   and the EL2 `__hyp_text*` blacklist is compiled out under `CONFIG_UML`.
5. **Linker and decode helpers.** `arch/um/kernel/uml.lds.S` now emits
   `ENTRY_TEXT` / `KPROBES_TEXT`, so the `__entry_text_*` and `__kprobes_text_*`
   boundaries that `kernel/kprobes.c` and `arch_populate_kprobe_blacklist()` test
   against actually exist; and `arch/arm64/um/kprobe-cond.c` supplies the
   `aarch32_opcode_cond_checks[]` table `simulate-insn.c` calls, which normally lives
   in `arch/arm64/kernel/traps.c` and is therefore not built for UML.

Kprobes uses the XOL model here (`arch_prepare_ss_slot` writes the original instruction
followed by `BRK64_OPCODE_KPROBES_SS`), so no hardware single-step is required.

**One thing that is still out of reach**, so you know before reading the patch:
`CONFIG_KPROBE_EVENTS` needs `HAVE_REGS_AND_STACK_ACCESS_API`, i.e. real arm64
`regs_get_register()` / `regs_get_kernel_stack_nth()`. UML does not build
`arch/arm64/kernel/stacktrace.c`, so kprobe events stay unavailable. Plain
`register_kprobe()` / `register_kretprobe()` work, which is what LKMs use; I did not fake
the symbol just to light up the config.

**For out-of-tree modules** (this cost me a week of CI round-trips; three gaps only appear
under `ARCH=um`, which is why no module author has hit them):

1. `-I$(srctree)/arch/um/include/shared` is passed before a module's own `-I`, and that
   directory contains a kernel `arch.h` guarded by `__ARCH_H__`. Any module with a header
   of that name silently includes the UML one instead of its own. Worth either renaming
   that header or moving it out of the global include path.
2. `arch/arm64/um/asm/syscall.h` has only the old six-argument `sys_call_ptr_t`; real
   arm64 defines `syscall_fn_t` (one `const struct pt_regs *`) in
   `arch/arm64/include/asm/syscall.h`. Anything declaring a syscall table for the
   one-argument signature needs a local `typedef`. Adding it to the UML header would be
   one line and is what I would suggest.
3. `<vdso/datapage.h>` reaches `arch/arm64/include/asm/vdso/gettimeofday.h`, which pulls
   `asm/arch_timer.h` (real `ALTERNATIVE`, `isb`, `mrs cntpct_el0`) and `vdso/processor.h`
   (`cpu_relax` redefinition). Anything touching the vdso page or clocksource needs a
   `CONFIG_UML` gate there; there is no vdso page to rewrite in UML anyway.
4. Android's GKI tree exports a few VFS internals under the
   `ANDROID_GKI_VFS_internal_...` namespace and also exports `path_umount`, which has no
   `EXPORT_SYMBOL` upstream. Modules ported from Android therefore fail modpost twice
   (missing namespace import, missing export). Both are one-line fixes on the module or
   Android side, so they are **not** in the attached patch — mentioning them only so the
   next person does not spend a week on it. If you would rather have `path_umount`
   exported here, say so and I will send that as a separate one-liner.

Happy to send the tiny `syscall_fn_t` patch separately if you want it in the tree.

**Testing.** On `ARCH=um SUBARCH=arm64` with `CONFIG_MODULES=y`, `CONFIG_KPROBES=y`,
`CONFIG_KRETPROBES=y`, `CONFIG_KALLSYMS_ALL=y`: the kernel
builds clean from a pristine checkout with clang 20 / bionic target / 4 KiB pages, and
links (`vmlinux` + `kernelsu.ko` built against it), in CI on ubuntu-24.04. Boot-time and
runtime verification (`insmod`/`rmmod`, a probe firing, a kretprobe returning through the
trampoline) is being run on the target device now — I will report the results here rather
than claim them silently.

Patch attached (`0001-um-arm64-kprobes-and-module-loading.patch`, 21 files). Happy to
split it per layer, or to move the trap entry out of `os-Linux/signal.c` if you would
rather have it in `arch/um/drivers`.

## 中文备注（发布前检查清单）

- [ ] 先确认 CI 全绿，产物 `um-gki618-4k` 的 `kernel-config.txt` 含
      `KPROBES/KRETPROBES/KPROBE_EVENTS/MODULES/MODULE_UNLOAD/KALLSYMS_ALL`。
- [ ] 手机侧按 `docs/M0-phone.md` 至少 M0 通过（`nproc > 1`、`umlfb` 出帧）再发，
      正文可补一句真机已验证。
- [ ] 补丁里不能出现 `sukisu`/`ksu`/`kpm` 字样（当前 grep 为 0）。
- [ ] 「Testing」里写的每一项都必须是真跑过的，没跑过的删掉。已删掉
      `CONFIG_KPROBE_EVENTS=y`（拿不到 `HAVE_REGS_AND_STACK_ACCESS_API`）。
- [ ] 「For out-of-tree modules」四条是**模块侧**发现，不是补丁内容；发 issue 时
      确认措辞是「建议」而不是「你们错了」。第 4 条（`path_umount` 导出 /
      `ANDROID_GKI_` 命名空间）明确写明不在补丁里：那是 Android ABI 兼容，
      对应实现是本仓库的 `kernel/patches/um-exports/apply.sh` + `um-ns-import.py`。
- [ ] 附加一句能力边界：`ksu_patch_text` / `ksu_syscall_table` / syscall hook 在 UML 上
      **明确不支持**（诚实返回 `-EOPNOTSUPP` / `-ENOSYS`），可用的是模块加载、
      kprobe、KPM/SUSFS。别让上游误以为 syscall hook 能用。
