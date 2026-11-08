/* droidvm-init —— guest 里的 PID 1，只做三件事：把文件系统挂好、把控制台接到
 * stdio 上、循环拉起 /bin/sh。
 *
 * 为什么不能 `init=/bin/sh`：内核在 devtmpfs 就绪之前就尝试把 /dev/console
 * 复制给 init 的 0/1/2，失败时只打一句 "Warning: unable to open an initial
 * console" 就继续，于是 sh 拿到的是关闭的 stdin，立刻读到 EOF、以 0 退出；而
 * PID 1 退出等于 panic("Attempted to kill init")。这里自己开控制台，并且把
 * shell 放在一个 respawn 循环里，PID 1 永远不死。
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
#include <unistd.h>

static const char *CONSOLES[] = {
    "/dev/console", "/dev/tty0", "/dev/ttyS0", "/dev/ttyAMA0", NULL
};

static void say(const char *s)
{
    size_t n = strlen(s);
    if (write(2, s, n) < 0) { /* 没有控制台时连这句话都写不出去，只能放弃 */ }
}

/* 挂不上不算错：内核可能已经挂过（devtmpfs），或者这不是必需的。
 * 只有 /proc 和 devpts 值得抱怨——缺了它们 ls /dev 与作业控制都别扭。 */
static void mount_if(const char *src, const char *tgt, const char *fstype,
                     unsigned long flags, const char *data, int fuss)
{
    mkdir(tgt, 0755);
    if (mount(src, tgt, fstype, flags, data) == 0 || errno == EBUSY)
        return;
    if (fuss) {
        char m[160];
        snprintf(m, sizeof m, "droidvm-init: mount %s 失败：%s\n", tgt,
                 strerror(errno));
        say(m);
    }
}

int main(void)
{
    mount_if("proc",     "/proc",    "proc",    0, NULL, 1);
    mount_if("sysfs",    "/sys",     "sysfs",   0, NULL, 0);
    mount_if("devtmpfs", "/dev",     "devtmpfs",0, NULL, 0);
    mount_if("devpts",   "/dev/pts", "devpts",  0, "mode=0620,ptmxmode=0666", 1);
    mount_if("tmpfs",    "/tmp",     "tmpfs",   0, "mode=1777", 0);
    mount_if("tmpfs",    "/run",     "tmpfs",   0, NULL, 0);

    int fd = -1;
    for (int i = 0; CONSOLES[i]; i++) {
        fd = open(CONSOLES[i], O_RDWR | O_NOCTTY);
        if (fd >= 0) {
            char m[128];
            snprintf(m, sizeof m, "droidvm-init: 控制台 %s\n", CONSOLES[i]);
            say(m);
            break;
        }
    }

    if (fd >= 0) {
        setsid();
        ioctl(fd, TIOCSCTTY, 0);
        if (dup2(fd, 0) < 0 || dup2(fd, 1) < 0 || dup2(fd, 2) < 0)
            say("droidvm-init: dup2 失败，shell 可能还是没有 stdin\n");
        if (fd > 2)
            close(fd);
        struct termios t;
        if (tcgetattr(0, &t) == 0) {
            t.c_lflag |= ECHO | ICANON | ISIG;
            tcsetattr(0, TCSANOW, &t);
        }
    } else {
        say("droidvm-init: 没有可用的控制台，shell 只能靠内核日志输出\n");
    }

    /* 交互 shell 需要的环境。用不着读 /etc/profile，Alpine 的 sh 会自己读
     * /etc/profile，这里只保证 HOME 与 PATH 不缺。 */
    setenv("HOME", "/root", 1);
    setenv("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin", 1);
    setenv("TERM", "linux", 1);

    for (;;) {
        pid_t p = fork();
        if (p == 0) {
            execl("/bin/sh", "sh", "-i", (char *)NULL);
            say("droidvm-init: 起不来 /bin/sh\n");
            _exit(127);
        }
        if (p < 0) {
            say("droidvm-init: fork 失败\n");
            sleep(1);
            continue;
        }
        int st = 0;
        waitpid(p, &st, 0);
        char m[128];
        if (WIFEXITED(st))
            snprintf(m, sizeof m,
                     "\ndroidvm-init: shell 退出（%d），1 秒后重新拉起\n",
                     WEXITSTATUS(st));
        else
            snprintf(m, sizeof m,
                     "\ndroidvm-init: shell 被信号 %d 终止，1 秒后重新拉起\n",
                     WTERMSIG(st));
        say(m);
        sleep(1);
    }
}
