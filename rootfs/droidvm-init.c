/* droidvm-init —— guest 里的 PID 1：挂文件系统、把控制台接上、循环拉起 /bin/sh。
 *
 * 为什么不能 `init=/bin/sh`：内核在 devtmpfs 就绪之前就尝试把 /dev/console
 * 复制给 init 的 0/1/2，失败时只打一句 "Warning: unable to open an initial
 * console" 就继续，于是 sh 拿到的是关闭的 stdin，立刻读到 EOF、以 0 退出；而
 * PID 1 退出等于 panic("Attempted to kill init")。这里自己开控制台，并且把
 * shell 放在一个 respawn 循环里，PID 1 永远不死。
 *
 * 日志为什么必须要三路：上面那句 "Warning" 之后内核做了 sys_dup(0) 两次，
 * 也就是 1、2 都是 fd 0 的副本。fd 0 若是关闭的，dup 失败，init 的 0/1/2
 * 全是无效 fd —— 只写 stderr 的日志会全部以 EBADF 静默丢掉，看起来像"init
 * 什么都没干"。所以每条消息都同时写 /dev/kmsg（一定能在 dmesg 里看到）。
 *
 * 策略为什么要可切换：真机上 App 用 con0=fd:0,fd:1 把 stdin/stdout 通道递进来，
 * 那种情况下直接用继承来的 0/1/2 最保真；但如果 fd 0 是关闭的（例如 run.sh
 * 里 con0=null,fd:1），就必须自己开 /dev/tty0 之类。两种都可能，因此先探测，
 * 再在"shell 秒退两次"时自动换另一套。
 *
 * 静态链接（gcc-aarch64-linux-gnu -static）：guest 是 musl 的 Alpine，静态
 * glibc 程序只走 syscall，不依赖 guest 的 libc。
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mount.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <stdarg.h>
#include <time.h>
#include <unistd.h>

static int kmsg_fd = -1;

/* 三条路都写一遍：/dev/kmsg 一定看得见，fd 1/2 视情况而定。 */
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

/* 自己开一个控制台并把 0/1/2 指过去。成功返回 0。 */
static int use_console_device(void)
{
    static const char *C[] = {
        "/dev/console", "/dev/tty0", "/dev/ttyS0", "/dev/ttyAMA0", NULL
    };
    int fd = -1;
    for (int i = 0; C[i]; i++) {
        fd = open(C[i], O_RDWR | O_NOCTTY);
        if (fd >= 0) {
            sayf("droidvm-init: 控制台 %s\n", C[i]);
            break;
        }
    }
    if (fd < 0) {
        say("droidvm-init: 没有任何可用的控制台\n");
        return -1;
    }
    setsid(); /* PID 1 本来就是会话首进程，这里失败是正常的 */
    if (ioctl(fd, TIOCSCTTY, 0) < 0)
        sayf("droidvm-init: TIOCSCTTY 失败：%s\n", strerror(errno));
    if (dup2(fd, 0) < 0 || dup2(fd, 1) < 0 || dup2(fd, 2) < 0)
        say("droidvm-init: dup2 失败，shell 可能还是没有 stdin\n");
    if (fd > 2)
        close(fd);
    struct termios t;
    if (tcgetattr(0, &t) == 0) {
        t.c_lflag |= ECHO | ICANON | ISIG;
        (void)tcsetattr(0, TCSANOW, &t);
    }
    return 0;
}

int main(void)
{
    /* 先挂 devtmpfs，才可能拿到 /dev/kmsg。 */
    mkdir("/dev", 0755);
    mount_if("devtmpfs", "/dev", "devtmpfs", 0, NULL, 0);
    kmsg_fd = open("/dev/kmsg", O_WRONLY | O_NONBLOCK);
    say("droidvm-init: 启动\n");

    mount_if("proc",     "/proc",    "proc",    0, NULL, 1);
    mount_if("sysfs",    "/sys",     "sysfs",   0, NULL, 0);
    mount_if("devpts",   "/dev/pts", "devpts",  0, "mode=0620,ptmxmode=0666", 1);
    mount_if("tmpfs",    "/tmp",     "tmpfs",   0, "mode=1777", 0);
    mount_if("tmpfs",    "/run",     "tmpfs",   0, NULL, 0);

    char t0[128], t1[128], t2[128];
    fd_target(0, t0, sizeof t0);
    fd_target(1, t1, sizeof t1);
    fd_target(2, t2, sizeof t2);
    sayf("droidvm-init: 继承 fd0=%s fd1=%s fd2=%s\n", t0, t1, t2);

    /* fd 0 有效（真通道/pty/管道）就直接用，最保真；否则自己开控制台。 */
    int console_mode = (strcmp(t0, "closed") == 0 || strcmp(t0, "EBADF") == 0);
    if (console_mode)
        say("droidvm-init: 继承的 stdin 不可用，改用控制台设备\n");
    else
        say("droidvm-init: 直接使用继承的 stdio\n");

    setenv("HOME", "/root", 1);
    setenv("PATH",
           "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin", 1);
    setenv("TERM", "linux", 1);

    int fast = 0;
    for (;;) {
        if (console_mode && use_console_device() < 0) {
            /* 开不出来就退回继承的 fd，下一轮再试 */
            console_mode = 0;
        }
        time_t t0s = time(NULL);
        pid_t p = fork();
        if (p == 0) {
            execl("/bin/sh", "sh", "-i", (char *)NULL);
            say("droidvm-init: 起不来 /bin/sh\n");
            _exit(127);
        }
        if (p < 0) {
            sayf("droidvm-init: fork 失败：%s\n", strerror(errno));
            sleep(1);
            continue;
        }
        int st = 0;
        if (waitpid(p, &st, 0) < 0)
            sayf("droidvm-init: waitpid 失败：%s\n", strerror(errno));
        long ran = (long)(time(NULL) - t0s);

        if (WIFEXITED(st))
            sayf("droidvm-init: shell 退出（%d），运行 %lds\n",
                 WEXITSTATUS(st), ran);
        else
            sayf("droidvm-init: shell 被信号 %d 终止，运行 %lds\n",
                 WTERMSIG(st), ran);

        /* 秒退且退出码 0，几乎肯定是 stdin 又是 EOF：换一套 stdio 再试。 */
        if (ran < 2 && WIFEXITED(st) && WEXITSTATUS(st) == 0 && ++fast >= 2) {
            fast = 0;
            console_mode = !console_mode;
            sayf("droidvm-init: 连续秒退，切换 stdio 策略为 %s\n",
                 console_mode ? "控制台设备" : "继承的 stdio");
        } else if (ran >= 2) {
            fast = 0;
        }
        sleep(1);
    }
}
