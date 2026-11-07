# 上游 issue 草稿（lakitu12/linux-um-arm64-gki）

> 待 CI 全绿后再发。附的是仓库里的单补丁
> `kernel/patches/0001-um-arm64-kprobes-and-module-loading.patch`（17 文件）。
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

**What the patch does** (four layers, all inside `arch/`):

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

Kprobes uses the XOL model here (`arch_prepare_ss_slot` writes the original instruction
followed by `BRK64_OPCODE_KPROBES_SS`), so no hardware single-step is required.

**Testing.** On `ARCH=um SUBARCH=arm64` with `CONFIG_MODULES=y`, `CONFIG_KPROBES=y`,
`CONFIG_KRETPROBES=y`, `CONFIG_KPROBE_EVENTS=y`: the kernel builds clean with clang 20
(bionic target, 4 KiB pages) and boots under UML; `insmod`/`rmmod` of a trivial module
works; a test module registering a `kprobe` on a kernel function fires, and a
`kretprobe` returns through the trampoline.

Patch attached (`0001-um-arm64-kprobes-and-module-loading.patch`, 17 files). Happy to
split it per layer, or to move the trap entry out of `os-Linux/signal.c` if you would
rather have it in `arch/um/drivers`.

## 中文备注（发布前检查清单）

- [ ] 先确认 CI 全绿，产物 `um-gki618-4k` 的 `kernel-config.txt` 含
      `KPROBES/KRETPROBES/KPROBE_EVENTS/MODULES/MODULE_UNLOAD/KALLSYMS_ALL`。
- [ ] 手机侧按 `docs/M0-phone.md` 至少 M0 通过（`nproc > 1`、`umlfb` 出帧）再发，
      正文可补一句真机已验证。
- [ ] 补丁里不能出现 `sukisu`/`ksu`/`kpm` 字样（当前 grep 为 0）。
- [ ] 「Testing」里写的每一项都必须是真跑过的，没跑过的删掉。
