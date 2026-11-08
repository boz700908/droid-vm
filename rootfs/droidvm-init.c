/* droidvm-init —— guest 里的 PID 1：挂文件系统、跑一次自检、把 shell 拉起来。
 *
 * 为什么不能 `init=/bin/sh`：内核在 devtmpfs 就绪之前尝试把 /dev/console 复制给
 * init 的 0/1/2，失败时只打一句 "Warning: unable to open an initial console" 就
 * 继续，于是 sh 拿到的是关闭的 stdin，立刻读到 EOF、以 0 退出；而 PID 1 退出等于
 * panic("Attempted to kill init")。这里自己安排 stdio，并把 shell 放在 respawn
 * 循环里，PID 1 永远不死。
 *
 * 为什么日志走 /dev/kmsg：真机上 init 的 0/1/2 **全都是关闭的**（实测
 * fd0=closed fd1=closed fd2=closed），任何写 stderr 的日志都以 EBADF 静默丢弃。
 * /dev/kmsg 是 printk，一定出现在 App 的内核输出里。而且必须把它挪到 fd >= 10：
 * 低 fd 全空时 open() 会直接返回 0，那样它就会变成 shell 的 stdin，也会被后面的
 * dup2 覆盖掉——这两个坑都踩过。
 *
 * 为什么 stdout 也指向 /dev/kmsg：实测 /dev/tty0 就是个死胡同——内核日志能出来
 * 是因为 printk 直接走 stdio console 的通道，而用户进程写 tty0 走的是 tty 行规程
 * （启动日志里 "can't register UML console driver" 就是它没注册成功）。所以交互
 * shell 的输入接控制台设备、输出接 /dev/kmsg，至少保证看得见。
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
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <time.h>
#include <unistd.h>

#define KMSG_MIN_FD 10

static int kmsg_fd = -1;

/* 所有消息都往 /dev/kmsg 写一份；fd 1/2 若有就顺带写（大多是关闭的）。 */
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

/* 找一个能当 stdin 的控制台设备。tty0 在真机上写是死的，但读（输入）可能是通的，
 * 所以优先它；顺带按 ttyS0 之类排下去。 */
static int open_console(void)
{
    static const char *C[] = {
        "/dev/console", "/dev/tty0", "/dev/ttyS0", "/dev/ttyAMA0", NULL
    };
    for (int i = 0; C[i]; i++) {
        int fd = open(C[i], O_RDWR | O_NOCTTY);
        if (fd >= 0) {
            sayf("droidvm-init: 控制台设备 %s\n", C[i]);
            return fd;
        }
    }
    say("droidvm-init: 没有任何可用的控制台设备\n");
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
              "echo droidvm-init: 自检 cmdline; cat /proc/cmdline; "
              "echo droidvm-init: 自检 dev; ls /dev | tr '\\n' ' '; echo; "
              "echo droidvm-init: 自检 mounts; head -4 /proc/mounts; "
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

    mount_if("proc",     "/proc",    "proc",    0, NULL, 1);
    mount_if("sysfs",    "/sys",     "sysfs",   0, NULL, 0);
    mount_if("devpts",   "/dev/pts", "devpts",  0, "mode=0620,ptmxmode=0666", 1);
    mount_if("tmpfs",    "/tmp",     "tmpfs",   0, "mode=1777", 0);
    mount_if("tmpfs",    "/run",     "tmpfs",   0, NULL, 0);

    char t0[128], t1[128], t2[128];
    fd_target(0, t0, sizeof t0);
    fd_target(1, t1, sizeof t1);
    fd_target(2, t2, sizeof t2);
    sayf("droidvm-init: 继承 fd0=%s fd1=%s fd2=%s（kmsg 在 fd%d）\n",
         t0, t1, t2, kmsg_fd);

    setenv("HOME", "/root", 1);
    setenv("PATH",
           "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin", 1);
    setenv("TERM", "dumb", 1);

    /* 先自检：不管后面 stdio 通不通，这一步都能证明 guest 用户态是活的。 */
    spawn_probe();

    for (;;) {
        int in = open_console();
        pid_t p = fork();
        if (p == 0) {
            if (in >= 0) {
                (void)dup2(in, 0);
                if (in > 2)
                    close(in);
                struct termios t;
                if (tcgetattr(0, &t) == 0) {
                    t.c_lflag |= ECHO | ICANON | ISIG;
                    (void)tcsetattr(0, TCSANOW, &t);
                }
            }
            stdout_to_kmsg(); /* 输出走内核日志：tty0 的写路径在真机上是死的 */
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

        sayf("droidvm-init: shell 已拉起（pid %d），输入走控制台，输出走内核日志\n",
             (int)p);

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
            if (alive % 15 == 0)
                sayf("droidvm-init: shell 存活 %lds，在等输入\n", alive);
        }
        long ran = (long)(time(NULL) - t0s);
        if (WIFEXITED(st))
            sayf("droidvm-init: shell 退出（%d），运行 %lds\n",
                 WEXITSTATUS(st), ran);
        else
            sayf("droidvm-init: shell 被信号 %d 终止，运行 %lds\n",
                 WTERMSIG(st), ran);
        sleep(1);
    }
}
