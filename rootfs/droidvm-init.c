/* droidvm-init —— guest 里的 PID 1：挂文件系统、跑一次自检、把 shell 拉起来。
 *
 * 为什么不能 `init=/bin/sh`：内核把 /dev/console 复制给 init 失败时只打一句
 * "Warning: unable to open an initial console" 就继续，于是 sh 拿到关闭的 stdin，
 * 读到 EOF 立刻以 0 退出；而 PID 1 退出等于 panic("Attempted to kill init")。
 * 这里自己安排 stdio，并把 shell 放在 respawn 循环里，PID 1 永远不死。
 *
 * 为什么日志同时写 /dev/kmsg：踩过一次整盘皆盲——CONFIG_VT 抢了 TTY_MAJOR(4)，
 * UML stdio console 注册失败，/dev/tty0 变成 dummycon，写进去毫无反应，连 shell
 * 的提示符都看不见。/dev/kmsg 走 printk，一定出现在 App 的内核输出里。kmsg 必须
 * 待在 fd >= 10：0/1/2 全空时 open() 会直接返回 0，那样它就成了 shell 的 stdin
 * （读到 EOF 秒退），也会被后面的 dup2 覆盖掉。这两个坑都踩过。
 *
 * 交互 shell 的 stdio：VT 关掉后 /dev/tty0 是真正的 UML console tty，输入输出
 * 和作业控制都正常，所以默认直接用它。只有当它在 tty 上连续秒退两次（说明写路
 * 又断了，例如又有人把 VT 打开）才退回到「输入 tty + 输出 kmsg」，同时开始打
 * 存活心跳——那时候唯一的观测手段就是内核日志。
 *
 * 静态链接（gcc-aarch64-linux-gnu -static）：guest 是 musl 的 Alpine，静态 glibc
 * 程序只走 syscall，不依赖 guest 的 libc。
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mount.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <time.h>
#include <unistd.h>

#define KMSG_MIN_FD 10

static int kmsg_fd = -1;

/* 输出走 tty 还是退回内核日志；连续秒退会翻转它。 */
static int out_via_kmsg = 0;

static void say(const char *s)
{
    size_t n = strlen(s);
    if (kmsg_fd >= 0)
        (void)!write(kmsg_fd, s, n);
    (void)!write(1, s, n);
    (void)!write(2, s, n);
}

static void sayf(const char *fmt, ...)
{
    char m[256];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(m, sizeof m, fmt, ap);
    va_end(ap);
    say(m);
}

static void fd_target(int fd, char *out, size_t n)
{
    char link[64];
    snprintf(link, sizeof link, "/proc/self/fd/%d", fd);
    ssize_t r = readlink(link, out, n - 1);
    if (r < 0)
        snprintf(out, n, "closed");
    else
        out[r] = '\0';
}

/* 挂不上不算错：内核可能已经挂过（devtmpfs），或者这不是必需的。 */
static void mount_if(const char *src, const char *tgt, const char *fstype,
                     unsigned long flags, const char *data, int fuss)
{
    mkdir(tgt, 0755);
    if (mount(src, tgt, fstype, flags, data) == 0 || errno == EBUSY)
        return;
    if (fuss)
        sayf("droidvm-init: mount %s 失败：%s\n", tgt, strerror(errno));
}

/* 打开内核日志，并把它赶到高 fd 上：留在 0/1/2 会被后面的 dup2 覆盖。 */
static void kmsg_open(void)
{
    int fd = open("/dev/kmsg", O_WRONLY | O_NONBLOCK);
    if (fd < 0)
        return;
    int hi = fcntl(fd, F_DUPFD, KMSG_MIN_FD);
    if (hi >= 0) {
        close(fd);
        kmsg_fd = hi;
    } else {
        kmsg_fd = fd; /* 挪不动就将就用，至少比没有强 */
    }
    /* printk 限速会把 shell 的输出整行吞掉（实测丢了 7 行），interactive 的
     * 场景里那是不可接受的。interval 置 0 等于关掉限速。 */
    int f = open("/proc/sys/kernel/printk_ratelimit", O_WRONLY);
    if (f >= 0) {
        (void)!write(f, "0", 1);
        close(f);
    }
}

/* 子进程：输出全部改成内核日志。 */
static void stdout_to_kmsg(void)
{
    int k = open("/dev/kmsg", O_WRONLY | O_NONBLOCK);
    if (k < 0)
        return;
    (void)dup2(k, 1);
    (void)dup2(k, 2);
    if (k > 2)
        close(k);
}

/* 找控制台设备。VT 关掉时 /dev/tty0 是 UML 自己的 console tty，可读可写；
 * /dev/console 是同一个 tty 的别名；两个都不行才说明内核的 console 没注册上。 */
static int open_console(void)
{
    static const char *C[] = {
        "/dev/tty0", "/dev/console", "/dev/tty", "/dev/ttyS0", NULL
    };
    for (int i = 0; C[i]; i++) {
        int fd = open(C[i], O_RDWR | O_NOCTTY);
        if (fd < 0)
            continue;
        if (!isatty(fd)) {
            close(fd);
            continue;
        }
        return fd;
    }
    return -1;
}

static void spawn_probe(void)
{
    say("droidvm-init: 启动自检开始\n");
    pid_t p = fork();
    if (p == 0) {
        int fd = open_console();
        if (fd >= 0) {
            (void)dup2(fd, 0);
            if (fd > 2)
                close(fd);
        }
        stdout_to_kmsg();
        execl("/bin/sh", "sh", "-c",
              "echo droidvm-init: 自检 uname; uname -a; "
              "echo droidvm-init: 自检 cpu= $(nproc); "
              "echo droidvm-init: 自检 mem; free -m; "
              "echo droidvm-init: 自检 tty; cat /proc/tty/drivers | head -8; "
              "echo droidvm-init: 自检 完成",
              (char *)NULL);
        say("droidvm-init: 自检起不来 /bin/sh\n");
        _exit(127);
    }
    if (p > 0) {
        int st = 0;
        waitpid(p, &st, 0);
        sayf("droidvm-init: 启动自检结束（status=%d）\n", st);
    }
}

int main(void)
{
    /* 先挂 devtmpfs，才可能拿到 /dev/kmsg。 */
    mkdir("/dev", 0755);
    mount_if("devtmpfs", "/dev", "devtmpfs", 0, NULL, 0);
    kmsg_open();
    say("droidvm-init: 启动\n");

    mount_if("proc",   "/proc",    "proc",   0, NULL, 1);
    mount_if("sysfs",  "/sys",     "sysfs",  0, NULL, 0);
    mount_if("devpts", "/dev/pts", "devpts", 0, "mode=0620,ptmxmode=0666", 1);
    mount_if("tmpfs",  "/tmp",     "tmpfs",  0, "mode=1777", 0);
    mount_if("tmpfs",  "/run",     "tmpfs",  0, NULL, 0);

    /* devtmpfs 不管 /dev/tty（5:0）这个别名，得自己造；没有它 sh 会抱怨
     * "can't access tty; job control turned off"。 */
    mknod("/dev/tty", S_IFCHR | 0666, makedev(5, 0));
    mknod("/dev/tty0", S_IFCHR | 0666, makedev(4, 0));

    char t[3][128];
    fd_target(0, t[0], sizeof t[0]);
    fd_target(1, t[1], sizeof t[1]);
    fd_target(2, t[2], sizeof t[2]);
    sayf("droidvm-init: 继承 fd0=%s fd1=%s fd2=%s（kmsg 在 fd%d）\n",
         t[0], t[1], t[2], kmsg_fd);

    setenv("HOME", "/root", 1);
    setenv("PATH",
           "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin", 1);
    setenv("TERM", "dumb", 1); /* App 那边不是真终端，别让它发光标查询 */
    setenv("PS1", "/ # ", 1);

    spawn_probe();

    int quick = 0;
    for (;;) {
        int in = open_console();
        if (in < 0 && !out_via_kmsg) {
            say("droidvm-init: 没有可用的控制台 tty，退回内核日志通道\n");
            out_via_kmsg = 1;
        }

        pid_t p = fork();
        if (p == 0) {
            if (in >= 0) {
                (void)dup2(in, 0);
                (void)setsid();
                (void)ioctl(0, TIOCSCTTY, 0);
                struct termios ts;
                if (tcgetattr(0, &ts) == 0) {
                    ts.c_lflag |= ECHO | ICANON | ISIG;
                    (void)tcsetattr(0, TCSANOW, &ts);
                }
            }
            if (out_via_kmsg) {
                stdout_to_kmsg();
            } else if (in >= 0) {
                (void)dup2(in, 1);
                (void)dup2(in, 2);
            }
            if (in > 2)
                close(in);
            execl("/bin/sh", "sh", "-i", (char *)NULL);
            say("droidvm-init: 起不来 /bin/sh\n");
            _exit(127);
        }
        if (in >= 2)
            close(in);
        if (p < 0) {
            sayf("droidvm-init: fork 失败：%s\n", strerror(errno));
            sleep(2);
            continue;
        }

        sayf("droidvm-init: shell 已拉起（pid %d，输出=%s）\n", (int)p,
             out_via_kmsg ? "内核日志" : "控制台 tty");

        time_t t0s = time(NULL);
        long alive = 0;
        int st = 0;
        for (;;) {
            pid_t r = waitpid(p, &st, WNOHANG);
            if (r == p)
                break;
            if (r < 0 && errno != EINTR) {
                sayf("droidvm-init: waitpid 失败：%s\n", strerror(errno));
                break;
            }
            sleep(5);
            alive += 5;
            /* 只有退回内核日志时才需要心跳：tty 正常时它只会污染屏幕。 */
            if (out_via_kmsg && alive % 30 == 0)
                sayf("droidvm-init: shell 存活 %lds，在等输入\n", alive);
        }
        long ran = (long)(time(NULL) - t0s);
        if (WIFEXITED(st))
            sayf("droidvm-init: shell 退出（%d），运行 %lds\n",
                 WEXITSTATUS(st), ran);
        else
            sayf("droidvm-init: shell 被信号 %d 终止，运行 %lds\n",
                 WTERMSIG(st), ran);

        if (ran < 2 && WIFEXITED(st) && WEXITSTATUS(st) == 0) {
            if (++quick >= 2) {
                quick = 0;
                out_via_kmsg = !out_via_kmsg;
                sayf("droidvm-init: 连续秒退，输出策略切换为 %s\n",
                     out_via_kmsg ? "内核日志" : "控制台 tty");
            }
        } else {
            quick = 0;
        }
        sleep(1);
    }
}
