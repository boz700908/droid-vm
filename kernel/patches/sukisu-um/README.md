# SukiSU Ultra 内核模块的 UML 兼容补丁

`ptregs-um.py` 是**脚本**而不是 `.patch`：SukiSU 更新快，`.patch` 几乎每次上游改动
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
```

脚本幂等：已经改过会打印「已打过」。如果上游改了 `arch.h` 的结构或寄存器访问方式，
`assert` 会失败并指出期望什么——那时按提示更新脚本，别去手改 CI 里的克隆产物。
