# App ↔ guest 的那条通道

音频回传、文件读写、传感器回灌、画面控制，四件事都需要一条 App 与 guest 之间的通道。
先把它定下来，否则每件事都会自己发明一套。

## 为什么不用 fd、不用网络

- **宿主 fd 直通**（UML 的 `ssl`/`con` 绑宿主 fd）：需要 App 给子进程额外的 fd。
  Android 的 `ProcessBuilder` 只保证 0/1/2，多一个 fd 就得靠 `fork` 自己做重定向，
  我们现在的启动方式拿不到。留作后手。
- **UML 网络**（`eth0=slirp`）：要在手机上额外带一个 slirp 二进制，且首次启动要配
  网络栈。收益只有「能用 socket」，不值得。
- **hostfs 上的 unix socket**：hostfs 不支持 socket 文件，直接排除。

## 结论：走 hostfs 共享目录

`CONFIG_HOSTFS=y` 已经在内核里。App 把自己 `filesDir` 下的一个目录路径通过内核命令行
传给 guest（`droidvm_share=/data/.../share`），guest 的 init 把它挂到 `/mnt/host`：

```sh
mount -t hostfs hostfs /mnt/host -o "$(sed -n 's/.*droidvm_share=\([^ ]*\).*/\1/p' /proc/cmdline)"
```

于是同一个目录两边都能读写 —— 这同时也是用户要的「MT 式注入文件提供器」的落点：
App 侧一个 `DocumentsProvider` 挂它，用户在系统文件管理器里就能直接拖文件进出。

## 现在就能用的最土通道

App 的 stdin 直接连到 guest 的 tty0 shell —— 也就是说**控制指令今天就能发**：
往子进程的 stdin 写一行命令，从 stdout 读回显。它只适合发指令、收文本，不适合二进制，
也不适合并发；但作为「隧道还没写完时怎么自测」的兜底，够用。真正的隧道按下面两节建。

## 两类流量，两种走法

| 流量 | 走法 | 理由 |
|---|---|---|
| 控制、文件系统调用 | `/mnt/host/rpc/` 下的请求/响应文件，一次一文件 | 简单，丢了能重试，天然可审计 |
| 音频、传感器（持续流） | `/mnt/host/ring/` 下的定长环形文件 + 递增序号 | 不做系统调用风暴，一次传一块 |

### 请求 / 响应（控制与文件）

- App 写 `rpc/tmp/<seq>`，写完 `rename` 成 `rpc/in/<seq>.req` —— rename 在同目录树内是原子的，
  不会出现「读到半个请求」。
- guest 的 agent 以 100ms 间隔扫 `rpc/in/`（hostfs 上 inotify 不可靠，别指望它），
  执行完写 `rpc/tmp/<seq>.resp.part` 再 rename 成 `rpc/out/<seq>.resp`。
- 载荷一行一个 JSON，字段少、可读，性能对控制类足够：

```json
{"op":"open","path":"/system/build.prop","flags":"r"}
{"op":"write","path":"/data/local/tmp/x","data":"<base64>"}
{"op":"readdir","path":"/vendor/etc"}
{"op":"stat","path":"/system"}
```

- `path` 一律是 **guest 内**的路径，agent 以 guest root 身份操作 —— 这样「镜像内部的所有文件
  都可写」是真的可写，而且写的是运行中的那份文件系统，不存在「离线用 debugfs 改、在线又在挂载，
  两套缓存互相拆台」的问题。
- guest 没运行时，App 用内置的静态 `debugfs` 离线读写同一份 `android.ext4`。

### 环形流（音频、传感器）

定长文件 `ring/<name>`，头部 32 字节放 `{write_seq, read_seq}`，后面是 N 个 4KB 槽。
写方填槽、推进 `write_seq`；读方按 `read_seq` 取，落后太多就丢帧（宁可断音也不要拖慢）。
48kHz/双声道/16bit、每 20ms 一帧只有 3840 字节 —— 一秒 50 次 read/write，hostfs 完全吃得下。

## 必须遵守的约定

- **不做 mmap**：hostfs 两边各自的缓存看不到对方的写入，只能靠 `read`/`write` 走宿主系统调用。
- **只经 rename 发布**，不写「同一个文件一半新一半旧」。
- App 侧所有路径都要校验在共享目录内，别让一个 `..` 把用户的数据带出去。
