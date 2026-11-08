# SukiSU Ultra 内核模块的 UML 兼容补丁

这里有两个脚本，都是照「只在 `ARCH=um` 上暴露」的差异写的：

| 脚本 | 解决什么 |
|---|---|
| `ptregs-um.py` | UML 的 `struct pt_regs` 没有 `regs[]` / `pc` / `sp` / `pstate` / `syscallno` |
| `um-compat.py` | `include/arch.h` 被内核同名头劫持、缺 `syscall_fn_t`、`vdso` 头在 UML 编不出 |

两个都是**脚本**而不是 `.patch`：SukiSU 更新快，`.patch` 几乎每次上游改动
都要重新对一遍行号，而脚本按语义定位（找 `#if defined(__aarch64__)`、找
`->__PT_XXX_REG`），上游怎么挪代码都不影响；万一结构真的变了，脚本里的 `assert`
会立刻大声失败，而不是悄悄改错地方。

CI 在克隆 SukiSU 之后、编译之前执行它（`.github/workflows/kernel.yml` 的
「给 SukiSU 打 UML 兼容补丁」），并自检「一个字段式寄存器访问都不剩」。

## 为什么需要它

SukiSU 的 `kernel/include/arch.h` 按 arm64 的真实布局定义了一族字段式宏：

```c
#define __PT_PARM2_REG regs[1]
```

调用点写成 `p->__PT_PARM2_REG`，展开就是 `p->regs[1]`。这在 arm64 上成立，因为
`struct pt_regs` 里有 `unsigned long regs[31]`。

UML 的 `struct pt_regs` 完全不同：它是 host 寄存器的快照，寄存器在
`pt_regs.regs`（`struct uml_pt_regs`）里，没有 `regs[]` 数组，也没有 `pc` / `sp` /
`pstate` / `syscallno`。于是：

- `p->regs[1]` → `subscripted value is not an array, pointer, or vector`
- `p->pc`、`p->sp`、`p->pstate`、`p->syscallno` → 成员不存在

**只改宏救不了**：`p->__PT_PARM2_REG` 里变量名和 `->` 都钉在调用点上，宏只能替换
箭头右边，展开结果永远是 `p-><某个字段>`。所以必须两步一起做。

## 脚本做了什么

1. `include/arch.h`：在 `__aarch64__` 分支里加 `#if defined(CONFIG_UML)` 子分支，
   用内核自己的 `PT_REGS_X()` / `PT_REGS_X30()` / `PT_REGS_SP_REG()` 定义同名函数式
   宏；原来的字段式宏块在 UML 下整体跳过（`KSU_PTREGS_IS_UML` 守卫）。
   `PT_REGS_IP` / `PT_REGS_ORIG_SYSCALL` 故意不重定义——UML 的 `asm/ptrace.h`
   已经有正确的实现，盖掉反而会错。
2. 源码里 `p->__PT_XXX_REG` 全部改成函数式 `PT_REGS_XXX(p)`，共 12 处，都在
   `feature/sucompat.c`。赋值语句也照改：`PT_REGS_X()` 展开成 `UPT_X(...)`，是可
   赋值的左值，`sucompat` 要写回寄存器的那几行不受影响。
3. `hook/syscall_hook_manager.c`：两处 `#elif defined(__aarch64__)` 加上
   `&& !defined(CONFIG_UML)`。这段要用 `is_compat_task()` 和 `pt_regs->syscallno`
   （UML 都没有），而且它靠改 `el0_svc` 生效，UML 里没有 `el0_svc` 可改——摘掉是
   如实表达「这条路径在 UML 上不成立」，不是把问题藏起来。

## `um-compat.py`：三个纯 UML 的编译障碍

这三处在真 arm64 上一条都不会触发，所以上游从来没发现；`ARCH=um` 一编就全冒出来。

### 1. `include/arch.h` 被内核自己的同名头劫持

`ARCH=um` 的编译命令里有 `-I$(srctree)/arch/um/include/shared`，那里躺着一个内核的
`arch.h`（守卫 `__ARCH_H__`，声明 arch 侧的辅助函数）。它的位置排在 SukiSU 自己的
`include/` **之前**，于是模块里 22 个文件的 `#include "arch.h"` 全部命中它：

```
./hook/syscall_hook.h: ... PT_REGS_PARM2 undeclared
runtime/ksud_integration.c: call to undeclared function 'PT_REGS_PARM2'
supercall/supercall.c: use of undeclared identifier 'REBOOT_SYMBOL'
```

症状看起来像「`arch.h` 里的宏没写」，其实是**根本没 include 到 `arch.h`**。真 arm64 上
`arch/um` 那条 `-I` 不存在，所以永远不复现。

修法不是调 `-I` 顺序（那是跟内核的构建系统较劲，换个内核版本又变），而是**改名**：
`include/arch.h` → `include/ksu-arch.h`，连带 22 处 `#include` 一起改。名字唯一，顺序
就无关了。**任何在 `ARCH=um` 下外编的模块，只要有个叫 `arch.h` 的头都会踩这个坑。**

### 2. `syscall_fn_t` 在 UML 下不存在

`hook/syscall_hook.h` 里，`syscall_fn_t` 靠内核头提供：arm64 的定义在
`arch/arm64/include/asm/syscall.h`，而 UML 走的是 `arch/arm64/um/asm/syscall.h`，那里
只有老式 6 参数 `sys_call_ptr_t`。于是：

```
./hook/syscall_hook.h:11:8: error: unknown type name 'syscall_fn_t'
feature/sucompat.c: too few arguments to function call, expected 6, have 1
```

后一条是前一条的连锁反应：`ksu_syscall_table` 退化成了 6 参数的函数指针类型，调用点
只传 1 个 `regs` 就报参数不够。补一行 `typedef` 两条一起消失。

### 3. `cpu_spoof.c` 的 vdso 页在 UML 不存在

`extension/cpu_spoof.c` 要改写 vdso 页里的时钟源，于是 include
`<vdso/datapage.h>` / `<vdso/clocksource.h>`。这条链在 arm64 上是：

```
vdso/datapage.h → asm/vdso/gettimeofday.h → asm/arch_timer.h
```

`arch_timer.h` 里是 `ALTERNATIVE(...)`、`isb`、`mrs cntpct_el0` 这类真硬件内联汇编，
UML 上既编不出（`expected string literal in 'asm'`、`cpu_relax` 重定义），也没有「vdso
页」这种东西可改。所以 UML 下整体跳过，并**如实记账**：

```c
#if defined(CONFIG_UML)
        pr_warn("ksu: cpu spoof 跳过 vdso 时钟改写（UML）\n");
        ++missing;   /* 走「部分符号没法改」的失败路径，不谎报成功 */
#elif LINUX_VERSION_CODE >= KERNEL_VERSION(6, 18, 0)
```

CPU 伪装本来就是给真机看的（改 `/proc/cpuinfo` 与 hwcaps），UML 里没有意义；关键是
别让它返回 0 让人以为生效了。

## 编得过 ≠ 功能可用

补丁只负责让模块在 `ARCH=um` 下编出来、加载进去。下面这些在 UML 上编得过但不会生效，
排障时不要当成 bug 去找：

- `sucompat` 的 execveat 重定向：依赖改内核 syscall 表（`ksu_syscall_table[...]`），
  UML 没有那条路径。
- syscall hook / dispatcher：同上，没有 `el0_svc` 可 patch。
- 一切按 arm64 符号名解析的东西（`__arm64_sys_*`）：UML 内核里没有这些符号，
  解析会失败并走「找不到就退化」的分支。

真正要在 UML 上用的是 **KPM**（`CONFIG_KPM=y`）、SUSFS 和模块加载本身。

## 上游升级时怎么维护

```bash
git -C sukisu pull            # 或者 CI 里改 SUKISU 的 ref
python3 kernel/patches/sukisu-um/ptregs-um.py sukisu/kernel
python3 kernel/patches/sukisu-um/um-compat.py sukisu/kernel
```

脚本幂等：已经改过会打印「已打过」。如果上游改了 `arch.h` 的结构或寄存器访问方式，
`assert` 会失败并指出期望什么——那时按提示更新脚本，别去手改 CI 里的克隆产物。
