package org.droidvm.host;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.Uri;
import java.io.BufferedInputStream;
import java.io.FileDescriptor;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import org.json.JSONObject;
import java.io.FileInputStream;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A Linux kernel, running inside an ordinary Android app.
 *
 * This exists to move the port out of the adb shell and into the app sandbox,
 * which is a materially harder environment and the one that actually matters
 * for shipping. The differences that shape this class:
 *
 *   - An app runs in the untrusted_app SELinux domain under a W^X policy. It
 *     may not execute anything it wrote itself, so every binary -- the kernel,
 *     the stub, passt, umnet, umusb -- ships as a lib*.so inside the APK and
 *     is run from getApplicationInfo().nativeLibraryDir, the one directory an
 *     app is allowed to execute from.
 *   - UML normally execs its stub from an anonymous memfd. That is refused
 *     here, so the kernel is passed stub_exe=<nativeLibraryDir>/libstub.so.
 *   - There is no /tmp. Guest RAM is backed from the app's cache directory.
 *
 * The UI is deliberately plain: a scrolling log, a line-input box wired to the
 * guest's stdin, and the few settings worth changing while debugging. It is a
 * console, not a terminal emulator -- there is no ANSI or cursor handling,
 * because the point is to see what the kernel says, not to run vim.
 */
public class MainActivity extends Activity {

    private TextView status;
    private TextView log;
    private ScrollView scroll;
    private EditText input;
    private EditText memField;
    private CheckBox seccompBox;
    private CheckBox netBox;
    private CheckBox usbBox;
    private CheckBox initBox;
    private Button startStop;
    private CheckBox tunnelBox;
    /* 隧道：走 hostfs 共享目录里的 rpc/in、rpc/out 中转，不碰 tty0。 */
    private File shareDir;
    private final AtomicLong rpcSeq = new AtomicLong();

    private Process guest;
    private OutputStream guestIn;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private static final int MAX_CHARS = 200000;

    /* ------------------------------------------------------------------
     * 系统盘（redroid 的 Android 根盘 android.ext4）
     *
     * 4 GB 的镜像塞不进 APK，只能落在 filesDir；而 filesDir 是 App 私有
     * 目录，用户从浏览器下到手机上的文件进不来，于是给两条路：
     *   1. 直接从站点下载并就地解压（一步到位，推荐）；
     *   2. 用系统文件选择器挑一个本地 .ext4 或 .ext4.gz 导入。
     * 两条路都先写 .part 再改名，中途失败不会留下半个镜像让 UML 去挂。
     * ------------------------------------------------------------------ */

    private static final String DISK_URL =
            "https://zqrj1.top/dl/e76aa893ab7d07e7/android.ext4.gz";
    private static final int REQ_DISK = 0x5100;

    private TextView diskLabel;
    private Button diskDownload;
    private Button diskPick;
    private volatile boolean diskBusy;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);

        /* ---------------------------------------------------------------
         * 无障碍优先的布局约定
         *
         * 上游是一行横排 + HorizontalScrollView：读屏靠焦点遍历还能摸到被
         * 挤出屏幕的控件，低视力用户摸不到，也没法安全地横向滚动。这里一律
         * 竖排、一行一个控件、占满宽度、最小高度 48dp，焦点顺序就是阅读顺序。
         *
         * 状态变化只用 live region 播报（见下面的 setStatus），不会抢焦点。
         * --------------------------------------------------------------- */

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        root.setPadding(pad, pad, pad, pad);

        /* --- 状态行：读屏在它变化时自己念，用户不用去找 --- */
        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        status.setText("状态：未启动");
        status.setAccessibilityHeading(true);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(status, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        /* --- 内存：标签与输入框用 setLabelFor 显式绑定，
         *     点标签会把焦点送进输入框，读屏也会把两者念成一项。 --- */
        TextView memLabel = new TextView(this);
        memLabel.setText("内存（MB）");
        memLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);

        memField = new EditText(this);
        memField.setId(View.generateViewId());
        memLabel.setLabelFor(memField.getId());
        memField.setInputType(InputType.TYPE_CLASS_NUMBER);
        /* 实测：4G 撑不到 zygote 稳定，Android 侧会 fdsan + SIGABRT 直接死。
         * 8G 在 15.6G 的机器上验证过可用，所以默认就写 8192。 */
        memField.setText("8192");
        memField.setHint("8192");
        memField.setContentDescription("内存大小，单位兆字节");
        memField.setMinHeight(dp(48));

        root.addView(memLabel, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        root.addView(memField, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        /* --- 开关：文字说明后果，不用缩写。「seccomp」三个字对一个
         *     靠读屏的人来说不是线索。 --- */
        seccompBox = new CheckBox(this);
        seccompBox.setText("seccomp 系统调用过滤（建议开启）");
        seccompBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        seccompBox.setChecked(true);
        seccompBox.setMinHeight(dp(48));
        root.addView(seccompBox, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        netBox = new CheckBox(this);
        netBox.setText("给 guest 联网（passt）");
        netBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        netBox.setChecked(true);
        netBox.setMinHeight(dp(48));
        root.addView(netBox, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        /*
         * Off by default: most runs have nothing plugged into the phone, and
         * the USB path costs a permission dialog before the guest starts.
         */
        usbBox = new CheckBox(this);
        usbBox.setText("USB 直通（需要先插好适配器）");
        usbBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        usbBox.setChecked(false);
        usbBox.setMinHeight(dp(48));
        root.addView(usbBox, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        /*
         * 默认走 Alpine 自带的 /sbin/init（busybox init + /etc/inittab），
         * 也就是标准的 Linux 启动流程。勾上才换成 droidvm-init —— 那是
         * console tty 还坏着的时候自己写的兜底 PID 1，现在只留作诊断：
         * 标准 init 读不到 inittab、或者起来就退，勾这个能立刻拿到
         * 自带自检和 kmsg 直写日志的那一套。
         */
        initBox = new CheckBox(this);
        initBox.setText("诊断 init（droidvm-init，标准 init 起不来时用）");
        initBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        initBox.setChecked(false);
        initBox.setMinHeight(dp(48));
        root.addView(initBox, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        /* --- 启动 / 停止：文案和 contentDescription 都随状态变，
         *     读屏说的永远和按下去会发生的一致。 --- */
        startStop = new Button(this);
        startStop.setText("启动");
        startStop.setContentDescription("启动内核");
        startStop.setMinHeight(dp(48));
        startStop.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (guest == null) boot(); else stop();
            }
        });
        root.addView(startStop, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        /*
         * The seccomp filter zygote installs on this app is inherited by every
         * process it spawns, including the kernel -- which is why the guest
         * dies with SIGSYS here but not under `run-as`. This button runs a
         * probe under that same filter and prints which syscalls it forbids,
         * because "something was denied" is not something you can act on.
         */
        Button probe = new Button(this);
        probe.setText("自检（看 seccomp 拦了什么）");
        probe.setMinHeight(dp(48));
        probe.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { runProbe(); }
        });
        root.addView(probe, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        /* --- 系统盘：redroid 的 Android 根盘必须落在 App 私有目录里，
         *     浏览器下到手机上的文件进不来，所以这里给两条导入路径。 --- */
        TextView diskTitle = new TextView(this);
        diskTitle.setText("Android 系统盘");
        diskTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        diskTitle.setAccessibilityHeading(true);
        root.addView(diskTitle, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        diskLabel = new TextView(this);
        diskLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        diskLabel.setMinHeight(dp(48));
        diskLabel.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_NONE);
        root.addView(diskLabel, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        diskDownload = new Button(this);
        diskDownload.setText("下载系统盘（约 720 MB，解压后 4 GB）");
        diskDownload.setContentDescription(
                "从站点下载 Android 系统盘，下载约 720 兆，解压后占用 4 GB");
        diskDownload.setMinHeight(dp(48));
        diskDownload.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { downloadDisk(); }
        });
        root.addView(diskDownload, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        diskPick = new Button(this);
        diskPick.setText("从手机里选文件导入系统盘");
        diskPick.setContentDescription(
                "用文件选择器挑一个系统盘镜像导入，支持 ext4 和压缩过的 ext4.gz");
        diskPick.setMinHeight(dp(48));
        diskPick.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickDisk(); }
        });
        root.addView(diskPick, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        /* --- 内核输出 --- */
        TextView logTitle = new TextView(this);
        logTitle.setText("内核输出");
        logTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        logTitle.setAccessibilityHeading(true);
        root.addView(logTitle, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        log = new TextView(this);
        log.setTypeface(Typeface.MONOSPACE);
        /* 14sp 而不是上游的 10sp：低视力用户要能放大后仍读得下。 */
        log.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        log.setTextIsSelectable(true);
        log.setFocusable(true);
        log.setAccessibilityPaneTitle("内核输出");
        /*
         * 不设 live region：一次启动就是几十行，逐行播报会把读屏彻底淹掉，
         * 而且每行都是「对按键的回答」之外的内容。要听就按下面那颗按钮，
         * 那是一次明确的请求，可以打断之前的朗读。
         */
        log.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_NONE);
        log.setBackgroundColor(Color.BLACK);
        log.setTextColor(Color.GREEN);

        scroll = new ScrollView(this);
        scroll.addView(log);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, 0, 1.0f));

        /*
         * 三颗按钮竖排：字号放大到 2 倍时横排会把「读更早三行」这类文字截断。
         * 「读更早」和「读最新」必须成对出现 —— 只给一个「最后三行」的话，
         * 一次启动就是几十行输出，用户没有任何办法往上游翻。
         */
        LinearLayout logActions = new LinearLayout(this);
        logActions.setOrientation(LinearLayout.VERTICAL);

        Button older = new Button(this);
        older.setText("读更早三行");
        older.setMinHeight(dp(48));
        older.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { readOlder(); }
        });
        logActions.addView(older, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        Button newest = new Button(this);
        newest.setText("读最新三行");
        newest.setMinHeight(dp(48));
        newest.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { readNewest(); }
        });
        logActions.addView(newest, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        Button copy = new Button(this);
        copy.setText("复制全部输出");
        copy.setMinHeight(dp(48));
        copy.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { copyAll(); }
        });
        logActions.addView(copy, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        root.addView(logActions, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        /* --- 输入行，接到 guest 的 stdin --- */
        /* --- 隧道开关 ---
         * 内核命令行是 con=null con0=fd:0,fd:1，底部输入框写的就是 tty0 的同一条
         * stdin；Android 一起来就把控制台接过去，这条通道就哑了。勾上这个框之后
         * 命令不再走 tty0，而是经共享目录交给 guest 里常驻的隧道进程，它活在
         * Alpine 的挂载命名空间里（pivot_root 只影响 redroid-up 自己那个），
         * 所以 Android 占着控制台时照样能用。默认不勾，老路径完全不变。 */
        tunnelBox = new CheckBox(this);
        tunnelBox.setText("经隧道发给 Alpine（Android 起来后也能用）");
        tunnelBox.setContentDescription("经隧道发给 Alpine");
        tunnelBox.setMinHeight(dp(48));
        tunnelBox.setChecked(false);
        root.addView(tunnelBox, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        input = new EditText(this);
        input.setHint("输入命令，按软键盘的发送键发出");
        input.setContentDescription("发给 guest 的命令");
        input.setSingleLine(true);
        input.setMinHeight(dp(48));
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int id, android.view.KeyEvent e) {
                send(input.getText().toString());
                input.setText("");
                return true;
            }
        });
        root.addView(input, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        setContentView(root);
        refreshDisk();

        append("DroidVM -- Linux/UML on aarch64, inside an Android app\n");
        append("uid " + android.os.Process.myUid() + "\n");
        append("libdir " + getApplicationInfo().nativeLibraryDir + "\n\n");

        usbAttachNotice(getIntent());
    }

    /*
     * singleTop in the manifest, so plugging the adapter in while the guest is
     * running delivers the attach intent here instead of stacking a second
     * Activity on top of a boot in progress.
     */
    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        usbAttachNotice(i);
    }

    /*
     * Deliberately one level of anonymous class, not two: a Runnable posting
     * another Runnable produces a MainActivity$N$1 class file that d8 8.2
     * rejects with an internal NullPointerException, which reads like a
     * compiler bug rather than anything to do with this code.
     */
    private java.io.FileOutputStream logFile;
    private LocalServerSocket usbServer;

    private final class Appender implements Runnable {
        private final String s;

        Appender(String s) { this.s = s; }

        @Override public void run() {
            log.append(s);
            /* Keep the buffer bounded: a kernel log will happily grow until
             * the TextView becomes unusable. */
            if (log.length() > MAX_CHARS) {
                CharSequence c = log.getText();
                log.setText(c.subSequence(c.length() - MAX_CHARS / 2, c.length()));
            }
            scroll.fullScroll(View.FOCUS_DOWN);
        }
    }

    /**
     * Everything shown in the terminal is also written to files/boot.log.
     *
     * The on-screen view is a bounded TextView and the interesting part of a
     * failed boot is usually the part that has already scrolled away, which
     * leaves screenshots as the only record -- unreadable past a few hundred
     * lines, and impossible to grep. The file is the app's own private
     * storage, so `adb shell run-as org.droidvm.host cat files/boot.log`
     * retrieves it without root.
     */
    private void logToFile(String s) {
        try {
            if (logFile == null) {
                logFile = new java.io.FileOutputStream(
                        new java.io.File(getFilesDir(), "boot.log"), true);
            }
            logFile.write(s.getBytes("UTF-8"));
            logFile.flush();
        } catch (Exception e) {
            /* Never let logging break the boot it is meant to record. */
        }
    }

    /**
     * The guest's busybox line editor asks the terminal for the cursor
     * position (ESC[6n) on every prompt, and neither the TextView nor
     * boot.log is a terminal, so the query -- and whatever colour or cursor
     * escape a command emits -- would otherwise be shown as litter. We are
     * not a terminal and cannot answer it; drop the sequences.
     */
    private static String stripEscapes(String s) {
        if (s.indexOf('\u001b') < 0)
            return s;
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\u001b') {
                b.append(c);
                continue;
            }
            if (i + 1 >= s.length())
                break;
            char kind = s.charAt(++i);
            if (kind == '[') {
                while (i + 1 < s.length()) {
                    char d = s.charAt(++i);
                    if (d >= '@' && d <= '~')
                        break;
                }
            } else if (kind == ']') {
                while (i + 1 < s.length()) {
                    char d = s.charAt(++i);
                    if (d == '\u0007')
                        break;
                    if (d == '\u001b' && i + 1 < s.length()
                            && s.charAt(i + 1) == '\\') {
                        i++;
                        break;
                    }
                }
            }
        }
        return b.toString();
    }

    private void append(String s) {
        s = stripEscapes(s);
        logToFile(s);
        ui.post(new Appender(s));
    }

    /**
     * Unpack a gzipped asset once, into the app's private files directory.
     * Data, not code -- so it may live here; it just may not be executed.
     */
    private File unpackAsset(String asset, String outName) throws IOException {
        File out = new File(getFilesDir(), outName);
        /* 装了新版本就要重解一遍：根盘的改动（比如 inittab 里 hostfs 的挂载
         * 写法）只随 APK 走，而「文件在就跳过」会让手机一直跑旧镜像，改一百
         * 遍都没用。版本戳用包的 lastUpdateTime；根盘只放系统内容，用户数据
         * 在另一块盘 android.ext4（ubd1）里，重解碰不到它。 */
        long stamp = 0;
        try {
            stamp = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).lastUpdateTime;
        } catch (Exception e) {
            /* 拿不到戳就退回老行为：有文件就用文件。 */
        }
        File mark = new File(getFilesDir(), outName + ".stamp");
        String have = "";
        try {
            if (mark.exists())
                have = new String(java.nio.file.Files.readAllBytes(mark.toPath()),
                        "UTF-8").trim();
        } catch (Exception e) {
            have = "";
        }
        if (out.exists() && out.length() > 0
                && (stamp == 0 || have.equals(Long.toString(stamp))))
            return out;
        if (out.exists())
            append("根盘随新版本重建（" + outName + "）\n");

        append("unpacking " + asset + " ...\n");
        InputStream in = getAssets().open(asset);
        if (asset.endsWith(".gz"))
            in = new GZIPInputStream(in, 65536);
        FileOutputStream fos = new FileOutputStream(out);
        byte[] buf = new byte[1 << 16];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            fos.write(buf, 0, n);
            total += n;
        }
        fos.close();
        in.close();
        append("unpacked " + (total >> 20) + " MB\n");
        try {
            java.nio.file.Files.write(mark.toPath(),
                    Long.toString(stamp).getBytes("UTF-8"));
        } catch (Exception e) {
            /* 写不上就下次再解一遍，不影响启动。 */
        }
        return out;
    }

    private void runProbe() {
        new Thread(new ProbeRunner()).start();
    }

    private final class ProbeRunner implements Runnable {
        @Override public void run() {
            try {
                File p = new File(getApplicationInfo().nativeLibraryDir, "libappseccomp.so");
                if (!p.exists()) {
                    append("probe not in this build\n");
                    return;
                }
                /* The probe is a tracer: run the kernel under it, inside the
                 * app's own seccomp filter, so a kill that leaves no output
                 * still yields a signal and a PC. */
                File k = new File(getApplicationInfo().nativeLibraryDir, "libuml.so");
                File probe = new File(getApplicationInfo().nativeLibraryDir,
                        "libprobe.so");
                ProcessBuilder pb = probe.exists()
                        ? new ProcessBuilder(probe.getAbsolutePath())
                        : new ProcessBuilder(p.getAbsolutePath(),
                                k.getAbsolutePath(), "--version");
                pb.redirectErrorStream(true);
                Process pr = pb.start();
                BufferedReader r = new BufferedReader(
                        new InputStreamReader(pr.getInputStream()), 8192);
                String line;
                while ((line = r.readLine()) != null)
                    append(line + "\n");
                append("[probe exited " + pr.waitFor() + "]\n");
            } catch (Exception e) {
                append("probe error: " + e + "\n");
            }
        }
    }

    /* ------------------------------------------------------------------
     * USB passthrough.
     *
     * An app may not open /dev/bus/usb -- the untrusted_app domain has no
     * access to the device nodes at all. What it may do is ask the user for
     * permission to one device and be handed an already-open usbfs descriptor
     * for it, and USBDEVFS_* ioctls work on that descriptor. libusb reaches
     * Android hardware the same way (libusb_wrap_sys_device); umusb uses it to
     * serve USB/IP to the guest's vhci-hcd.
     * ------------------------------------------------------------------ */

    /*
     * The Realtek RTL8811AU that the guest's in-tree rtw88 8821au driver
     * binds. res/xml/device_filter.xml carries the same pair in decimal,
     * 9047/287, and the two have to be changed together.
     */
    private static final int USB_VENDOR = 0x2357;
    private static final int USB_PRODUCT = 0x011f;

    /* Our own action: the permission answer arrives as a broadcast. */
    private static final String ACTION_USB_PERMISSION =
            "org.droidvm.host.USB_PERMISSION";

    /* Written by the boot thread, read by the receiver on the main one. */
    private volatile CountDownLatch usbGrant;
    private ParcelFileDescriptor usbPfd;

    private final class UsbPermission extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            CountDownLatch l = usbGrant;

            if (l != null)
                l.countDown();
        }
    }

    private static String usbId(int vendor, int product) {
        return String.format(Locale.US, "%04x:%04x", vendor, product);
    }

    private static String usbId(UsbDevice d) {
        return usbId(d.getVendorId(), d.getProductId());
    }

    /**
     * Acquire the adapter and return a descriptor umusb can use, or -1.
     *
     * Called from the boot thread and blocks there while the permission dialog
     * is up: the answer is a broadcast, which is delivered on the main thread,
     * so waiting for it on the main thread would deadlock.
     *
     * Every exit prints why. A USB failure that says nothing is
     * indistinguishable from a kernel that never booted.
     */
    private int usbOpen() {
        usbRelease();

        UsbManager um = (UsbManager)getSystemService(Context.USB_SERVICE);
        if (um == null) {
            append("usb: no UsbManager -- this phone has no USB host mode\n");
            return -1;
        }

        UsbDevice dev = null;
        HashMap<String, UsbDevice> devs = um.getDeviceList();
        for (UsbDevice d : devs.values()) {
            append("usb: sees " + usbId(d) + " " + d.getDeviceName() + "\n");
            if (d.getVendorId() == USB_VENDOR && d.getProductId() == USB_PRODUCT)
                dev = d;
        }
        if (dev == null) {
            append("usb: no " + usbId(USB_VENDOR, USB_PRODUCT) + " adapter"
                    + " among " + devs.size() + " attached device(s);"
                    + " booting without USB\n");
            return -1;
        }

        /*
         * Already granted if the adapter is what launched us: the platform
         * gives an app permission to the device that matched its
         * USB_DEVICE_ATTACHED filter, and keeps it until the device is
         * unplugged.
         */
        if (!um.hasPermission(dev)) {
            append("usb: asking for permission to " + usbId(dev) + " ...\n");
            usbGrant = new CountDownLatch(1);

            UsbPermission recv = new UsbPermission();
            IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);

            try {
                /*
                 * targetSdk 34 refuses an unflagged registerReceiver for an
                 * action the system does not own. The grant is sent through
                 * our own PendingIntent, so it arrives under our uid and
                 * NOT_EXPORTED still receives it -- while EXPORTED would let
                 * any other app on the phone forge one.
                 */
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    registerReceiver(recv, filter,
                            Context.RECEIVER_NOT_EXPORTED);
                else
                    registerReceiver(recv, filter);

                try {
                    Intent i = new Intent(ACTION_USB_PERMISSION)
                            .setPackage(getPackageName());
                    /*
                     * From API 31 getBroadcast() throws unless it is told
                     * IMMUTABLE or MUTABLE. Immutable is the safe one, and the
                     * price is that the system cannot fill
                     * EXTRA_PERMISSION_GRANTED into the intent it sends back:
                     * an immutable PendingIntent ignores the fill-in intent.
                     * So the broadcast is only a wake-up and hasPermission()
                     * below is the answer.
                     */
                    int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                            ? PendingIntent.FLAG_IMMUTABLE : 0;

                    um.requestPermission(dev, PendingIntent.getBroadcast(
                            this, 0, i, flags));
                    if (!usbGrant.await(60, TimeUnit.SECONDS))
                        append("usb: the permission dialog went unanswered"
                                + " for 60s\n");
                } finally {
                    unregisterReceiver(recv);
                }
            } catch (Exception e) {
                append("usb: permission request failed: " + e + "\n");
                return -1;
            } finally {
                usbGrant = null;
            }

            if (!um.hasPermission(dev)) {
                append("usb: permission denied; booting without USB\n");
                return -1;
            }
            append("usb: permission granted\n");
        }

        UsbDeviceConnection conn = um.openDevice(dev);
        if (conn == null) {
            append("usb: openDevice(" + usbId(dev) + ") failed"
                    + " -- unplugged since the scan?\n");
            return -1;
        }
        try {
            /*
             * UsbDeviceConnection owns the descriptor and closes it from its
             * finalize(), which would pull it out from under umusb as soon as
             * the object became garbage. Dup it instead: the copy names the
             * same open usbfs file -- same claimed interfaces, same URB queue
             * -- and nothing but this class can close it.
             */
            ParcelFileDescriptor theirs =
                    ParcelFileDescriptor.adoptFd(conn.getFileDescriptor());
            try {
                usbPfd = ParcelFileDescriptor.dup(theirs.getFileDescriptor());
            } finally {
                /* adoptFd() was only a handle to dup from; the descriptor
                 * itself still belongs to the connection. */
                theirs.detachFd();
            }
            /*
             * Descriptors that arrive over binder come with O_CLOEXEC, and
             * ParcelFileDescriptor.dup() puts it back on the copy. Left set,
             * the number on umusb's command line would name a descriptor that
             * exec had already closed.
             */
            Os.fcntlInt(usbPfd.getFileDescriptor(), OsConstants.F_SETFD, 0);
        } catch (Exception e) {
            append("usb: cannot take over the descriptor: " + e + "\n");
            usbRelease();
            conn.close();
            return -1;
        }
        /* The dup holds the usbfs file open; this only drops the framework's
         * own reference to it. */
        conn.close();

        int fd = usbPfd.getFd();

        append("usb: " + usbId(dev) + " " + dev.getDeviceName()
                + " opened, fd " + fd + "\n");
        return fd;
    }

    /*
     * Hand the usbfs descriptor to umusb over an abstract unix socket.
     *
     * Not on the command line: measured on this device, a descriptor this app
     * opens does not survive the spawn -- "a child's /proc/self/fd has no
     * /dev/bus/usb entry" -- because ART's process spawn closes everything
     * above stderr in the child before exec, as OpenJDK's childproc.c does.
     * FD_CLOEXEC is irrelevant to that; the descriptor has to be sent, not
     * named. SCM_RIGHTS installs a fresh one in umusb's table.
     *
     * Abstract namespace: a socket file under the app's own directory is not
     * something an exec'd child of the app is allowed to connect to, and there
     * is nothing to clean up afterwards.
     */
    private void usbServe(final String name) {
        try {
            usbServer = new LocalServerSocket(name);
        } catch (IOException e) {
            append("usb: cannot listen on @" + name + ": " + e + "\n");
            return;
        }
        new Thread(new UsbServer(name)).start();
    }

    private final class UsbServer implements Runnable {
        private final String name;

        UsbServer(String name) { this.name = name; }

        @Override public void run() {
            try {
                LocalSocket s = usbServer.accept();
                FileDescriptor[] fds = { usbPfd.getFileDescriptor() };

                s.setFileDescriptorsForSend(fds);
                s.getOutputStream().write(0);
                s.getOutputStream().flush();
                s.close();
                append("usb: descriptor sent to umusb\n");
            } catch (Exception e) {
                append("usb: handing over the descriptor failed: " + e + "\n");
            }
        }
    }

    private void usbRelease() {
        if (usbPfd == null)
            return;
        try {
            usbPfd.close();
        } catch (IOException e) {
            /* Deliberately dropping this descriptor; a failed close is not
             * something the caller can act on. */
        }
        usbPfd = null;
    }

    /**
     * Ask a child process what it inherited, and say so.
     *
     * ProcessBuilder is not execve(): OpenJDK's child closes every descriptor
     * above stderr before it execs, and libcore's process code descends from
     * it. Measured on this project's build machine (JDK 21): a descriptor with
     * FD_CLOEXEC clear is listed in the JVM's own /proc/self/fd and absent
     * from the child's. Whether ART does the same is a question only the phone
     * can answer -- and if it does, umusb gets EBADF on --usbfd and every URB
     * fails with nothing in the log pointing at the cause. So one child, once,
     * to make the answer part of the boot log.
     */
    /*
     * Does a descriptor we opened reach a child across exec?
     *
     * Clearing FD_CLOEXEC is necessary and may not be sufficient: OpenJDK's
     * childproc.c, which ART's ProcessImpl descends from, walks /proc/self/fd
     * in the child and closes everything above stderr before exec'ing. Whether
     * this particular Android does that is a question about the device, so ask
     * the device: spawn a child the same way the kernel is spawned and see
     * whether the usbfs descriptor is in its table.
     */
    private boolean usbCheckInherit(int fd) {
        try {
            Process p = new ProcessBuilder("/system/bin/ls", "-l",
                    "/proc/self/fd").redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream()), 4096);
            String seen = null;
            String line;

            while ((line = r.readLine()) != null)
                if (line.contains("/dev/bus/usb"))
                    seen = line.trim();
            p.waitFor();

            if (seen != null) {
                append("usb: descriptor survives exec (" + seen + ")\n");
                return true;
            }
            append("usb: a child's /proc/self/fd has no /dev/bus/usb entry --"
                    + " fd " + fd + " would reach umusb closed\n");
            return false;
        } catch (Exception e) {
            append("usb: inheritance check did not run: " + e + "\n");
            return false;
        }
    }

    /*
     * USB_DEVICE_ATTACHED started or resumed us. Worth a line: it is the one
     * route to the adapter that needs no permission dialog.
     */
    private void usbAttachNotice(Intent i) {
        if (i == null ||
            !UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(i.getAction()))
            return;

        UsbDevice d = (UsbDevice)i.getParcelableExtra(UsbManager.EXTRA_DEVICE);

        append("usb: " + (d != null ? usbId(d) : "a device")
                + " attached -- tick 'usb' and Boot\n");
    }

    private void boot() {
        setRunning(true);
        setStatus("正在启动…");
        new Thread(new Runnable() { @Override public void run() { bootThread(); } }).start();
    }

    private void bootThread() {
        try {
            String libdir = getApplicationInfo().nativeLibraryDir;
            File kernel = new File(libdir, "libuml.so");
            File stub = new File(libdir, "libstub.so");
            File umnet = new File(libdir, "libumnet.so");
            File passt = new File(libdir, "libpasst.so");
            File umusb = new File(libdir, "libumusb.so");

            if (!kernel.exists()) {
                append("ERROR: " + kernel + " missing from the APK\n");
                return;
            }

            /*
             * 我们的产物只有 Alpine 根盘，没有 initramfs。两者任取其一，
             * 根盘优先；都没有就说清楚，别让用户对着「启动失败」猜。
             */
            File rootfs = null;
            try {
                rootfs = unpackAsset("rootfs.ext4.gz", "rootfs.ext4");
            } catch (IOException e) {
                append("no rootfs.ext4.gz in the APK, falling back to initramfs\n");
            }

            File initrd = null;
            if (rootfs == null) {
                try {
                    initrd = unpackAsset("initramfs.cpio.gz", "initramfs.cpio.gz");
                } catch (IOException e) {
                    append("ERROR: 这个 APK 里既没有 rootfs.ext4.gz 也没有"
                            + " initramfs.cpio.gz\n");
                    setStatus("启动失败：产物里没有根盘");
                    return;
                }
            }

            int usbfd = -1;
            if (usbBox.isChecked()) {
                if (!umusb.exists())
                    append("usb: libumusb.so is not in this build;"
                            + " booting without USB\n");
                else
                    usbfd = usbOpen();
            }

            List<String> cmd = new ArrayList<String>();
            boolean net = netBox.isChecked() && umnet.exists() && passt.exists();
            if (net) {
                cmd.add(umnet.getAbsolutePath());
                cmd.add("--passt");
                cmd.add(passt.getAbsolutePath());
                cmd.add("--");
            }
            /*
             * umusb nests inside umnet: each wrapper execs whatever follows
             * its own --, so with both on, the kernel runs under both and ends
             * up with the vec0 argument umnet appends as well as whatever
             * umusb appends. The descriptor is passed by number, which is why
             * usbOpen() had to clear FD_CLOEXEC on it.
             */
            if (usbfd >= 0) {
                String sock = "umarm-usb-" + android.os.Process.myPid();

                usbServe(sock);
                cmd.add(umusb.getAbsolutePath());
                cmd.add("--usbsock");
                cmd.add(sock);
                /*
                 * The guest cannot attach to a socket of ours: vhci's
                 * attach_store() resolves the number it is given in the fd
                 * table of the process writing to sysfs, which is a guest
                 * process. So umusb listens instead, and the guest connects
                 * out through passt -- which is why usb needs net.
                 */
                cmd.add("--listen");
                cmd.add("127.0.0.1:3240");
                cmd.add("--");
            }
            cmd.add(kernel.getAbsolutePath());
            cmd.add("mem=" + memField.getText().toString() + "M");
            cmd.add("panic=-1");
            cmd.add("con=null");
            cmd.add("con0=fd:0,fd:1");
            /* The whole reason this app can work at all: exec the stub from
             * the APK's library directory rather than from a memfd. */
            cmd.add("stub_exe=" + stub.getAbsolutePath());
            cmd.add("seccomp=" + (seccompBox.isChecked() ? "auto" : "off"));
            /*
             * 共享目录：guest 用 hostfs 把它挂到 /mnt/host，App 与 guest 看到
             * 同一份文件。隧道（控制、文件读写、音频环）和文件注入都落在这里，
             * 所以先建好再传路径；建不出来就明说，不要静默假装有。
             */
            File share = new File(getFilesDir(), "share");
            if (share.isDirectory() || share.mkdirs()) {
                cmd.add("droidvm_share=" + share.getAbsolutePath());
            } else {
                append("（共享目录建不出来，隧道与文件注入这次用不了）\n");
            }
            if (rootfs != null) {
                cmd.add("ubd0=" + rootfs.getAbsolutePath());
                cmd.add("root=/dev/ubda");
                /*
                 * 第二块盘：Android 根盘（redroid 的那份 ext4）。有就挂成 ubd1，
                 * guest 里是 /dev/ubdb，由 /sbin/redroid-up 挂到 /android 再 chroot。
                 */
                File androidImg = new File(getFilesDir(), "android.ext4");
                if (androidImg.isFile()) {
                    cmd.add("ubd1=" + androidImg.getAbsolutePath());
                }
                cmd.add("rw");
                /*
                 * 标准启动流程：kernel_init 打开 /dev/console（VT 关掉后这就
                 * 是 UML console 的 tty），dup 给 0/1/2，再 exec Alpine 的
                 * /sbin/init —— busybox init 照 /etc/inittab 挂文件系统，然后
                 * 在 tty0 上 respawn 一个登录 shell。根盘里的 inittab 是
                 * rootfs/mkrootfs-alpine.sh 写的，专门给 tty0 补了一行。
                 *
                 * 不要退回 init=/bin/sh：sh 读到 EOF 就退出，PID 1 退出等于
                 * panic。真要兜底就勾「诊断 init」，那条路是 droidvm-init。
                 */
                cmd.add(initBox.isChecked() ? "init=/sbin/droidvm-init"
                                            : "init=/sbin/init");
            } else {
                cmd.add("initrd=" + initrd.getAbsolutePath());
                cmd.add("init=/init");
            }

            append("$ " + join(cmd) + "\n\n");

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.directory(getFilesDir());
            /* No /tmp in an app sandbox; guest RAM is backed from the cache
             * directory instead. HOME is where UML puts its umid. */
            pb.environment().put("TMPDIR", getCacheDir().getAbsolutePath());
            pb.environment().put("HOME", getFilesDir().getAbsolutePath());

            guest = pb.start();
            guestIn = guest.getOutputStream();

            BufferedReader r = new BufferedReader(
                    new InputStreamReader(guest.getInputStream()), 8192);
            String line;
            while ((line = r.readLine()) != null)
                append(line + "\n");

            int rc = guest.waitFor();
            append("\n[guest exited, status " + rc + "]\n");
            /* 退出码必须说出来：盲用户看不到那行输出，而「退出了」和
             * 「退出码 139」是两件完全不同的事。 */
            setStatus("内核已退出，退出码 " + rc);
        } catch (Exception e) {
            append("\nERROR: " + e + "\n");
            setStatus("启动失败，原因见输出");
        } finally {
            guest = null;
            guestIn = null;
            /* Hand the adapter back. Interfaces claimed through this usbfs
             * file stay claimed for as long as any descriptor on it is open,
             * so holding the dup after the guest is gone would leave the
             * device claimed by nobody in particular. */
            usbRelease();
            setRunning(false);
        }
    }

    /**
     * 一次隧道往返：把 req 写进 <share>/rpc/in，等 guest 把同名结果写到 rpc/out。
     * 请求先落到 rpc/tmp 再改名，guest 那边永远不会读到写了一半的文件。
     */
    private JSONObject rpc(JSONObject req, int timeoutMs) throws Exception {
        /* 与内核命令行里 droidvm_share= 指同一个目录（getFilesDir()/share）。 */
        File share = shareDir != null ? shareDir : new File(getFilesDir(), "share");
        shareDir = share;
        File in = new File(share, "rpc/in");
        File out = new File(share, "rpc/out");
        File tmp = new File(share, "rpc/tmp");
        for (File d : new File[]{in, out, tmp}) {
            if (!d.isDirectory() && !d.mkdirs() && !d.isDirectory())
                throw new IOException("建不出目录 " + d);
        }
        String name = String.format(Locale.US, "a%010d-%04d.req",
                rpcSeq.incrementAndGet(), (int) (System.nanoTime() % 10000));
        File part = new File(tmp, name + ".part");
        FileOutputStream fo = new FileOutputStream(part);
        try {
            fo.write(req.toString().getBytes("UTF-8"));
            fo.flush();
        } finally {
            fo.close();
        }
        if (!part.renameTo(new File(in, name)))
            throw new IOException("请求发不出去（改名到 rpc/in 失败）");
        File resp = new File(out, name);
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (resp.isFile()) {
                StringBuilder sb = new StringBuilder();
                BufferedReader r = new BufferedReader(
                        new InputStreamReader(new FileInputStream(resp), "UTF-8"));
                try {
                    String line;
                    while ((line = r.readLine()) != null)
                        sb.append(line).append('\n');
                } finally {
                    r.close();
                }
                resp.delete();
                return new JSONObject(sb.toString().trim());
            }
            Thread.sleep(20);
        }
        throw new IOException("guest 没有应答（" + (timeoutMs / 1000) + " 秒超时）");
    }

    /** 经隧道执行一条命令；App 的版本输出走日志区，不进 tty0。 */
    private void sendTunnel(final String cmd) {
        final int timeoutS = 30;
        append("$ [隧道] " + cmd + "\n");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject req = new JSONObject();
                    req.put("op", "exec");
                    req.put("cmd", cmd);
                    req.put("timeout", timeoutS);
                    JSONObject r = rpc(req, (timeoutS + 15) * 1000);
                    String out = r.optString("out", "");
                    if (out.isEmpty())
                        append("（无输出）\n");
                    else
                        append(out.endsWith("\n") ? out : out + "\n");
                    if (r.optBoolean("ok", false))
                        append("[退出码 " + r.optInt("rc", 0) + "]\n");
                    else
                        append("[隧道报错：" + r.optString("err", "未知") + "]\n");
                } catch (Exception e) {
                    append("[隧道不可用：" + e.getMessage() + "]\n");
                }
            }
        }, "droidvm-tunnel").start();
    }

    private void send(String s) {
        /* 勾了隧道就走中转；没勾就还是原来那条 tty0 的 stdin。 */
        if (tunnelBox != null && tunnelBox.isChecked()) {
            String t = s.trim();
            if (!t.isEmpty())
                sendTunnel(t);
            return;
        }
        if (guestIn == null) {
            append("[not running]\n");
            announce("内核未运行，先按启动");
            return;
        }
        try {
            guestIn.write((s + "\n").getBytes("UTF-8"));
            guestIn.flush();
        } catch (IOException e) {
            append("[write failed: " + e + "]\n");
        }
    }

    private void stop() {
        if (guest != null) {
            setStatus("正在停止…");
            guest.destroy();
        }
    }

    /* ------------------------------------------------------------------
     * 系统盘：下载与导入
     *
     * 下载量按压缩包算（约 720 MB），进度按 10% 一报 —— 每 1% 都改一次
     * live region，读屏会变成噪音。导入按每 0.1 GB 一报。
     * ------------------------------------------------------------------ */

    private File diskFile() {
        return new File(getFilesDir(), "android.ext4");
    }

    /** 刷新系统盘那一行的文案与按钮可用性；任何线程都能调。 */
    private void refreshDisk() {
        final File f = diskFile();
        final boolean ready = f.isFile() && f.length() > (1L << 20);
        final boolean idle = !diskBusy;
        ui.post(new Runnable() {
            @Override public void run() {
                String s = ready
                        ? String.format(Locale.US, "系统盘：已就绪，%.2f GB",
                                        f.length() / 1073741824.0)
                        : "系统盘：未就绪（没有 android.ext4，redroid 起不来）";
                diskLabel.setText(s);
                diskLabel.setContentDescription(s);
                diskDownload.setEnabled(idle);
                diskPick.setEnabled(idle);
            }
        });
    }

    private void downloadDisk() {
        if (diskBusy)
            return;
        diskBusy = true;
        refreshDisk();
        setStatus("正在下载系统盘…");
        new Thread(new Runnable() {
            @Override public void run() { downloadDiskThread(); }
        }).start();
    }

    private void downloadDiskThread() {
        final File part = new File(getFilesDir(), "android.ext4.part");
        java.net.HttpURLConnection c = null;
        InputStream in = null;
        FileOutputStream out = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL(DISK_URL).openConnection();
            c.setConnectTimeout(20000);
            c.setReadTimeout(60000);
            c.connect();
            int code = c.getResponseCode();
            if (code != 200)
                throw new IOException("HTTP " + code);
            long total = c.getContentLength();
            in = new BufferedInputStream(c.getInputStream(), 1 << 16);
            in.mark(2);
            int b0 = in.read();
            int b1 = in.read();
            in.reset();
            if (b0 == 0x1f && b1 == 0x8b)
                in = new GZIPInputStream(in, 1 << 16);
            out = new FileOutputStream(part);
            byte[] buf = new byte[1 << 20];
            long done = 0;
            long last = -10;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                long pct = total > 0 ? done * 100 / total : done / 10737418L;
                if (pct - last >= 10) {
                    last = pct;
                    setStatus("正在下载系统盘，" + pct + "%");
                }
            }
            out.flush();
            out.close();
            out = null;
            in.close();
            in = null;
            File dst = diskFile();
            if (dst.exists() && !dst.delete())
                throw new IOException("删不掉旧的 android.ext4");
            if (!part.renameTo(dst))
                throw new IOException("改名失败");
            append("系统盘已下载：" + dst.length() + " 字节\n");
            setStatus("系统盘已就绪");
            announce("系统盘下载完成，可以启动");
        } catch (Exception e) {
            part.delete();
            append("系统盘下载失败：" + e + "\n");
            setStatus("系统盘下载失败");
        } finally {
            try { if (out != null) out.close(); } catch (Exception ignored) { }
            try { if (in != null) in.close(); } catch (Exception ignored) { }
            if (c != null)
                c.disconnect();
            diskBusy = false;
            refreshDisk();
        }
    }

    private void pickDisk() {
        if (diskBusy)
            return;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_DISK);
        } catch (Exception e) {
            append("打不开文件选择器：" + e + "\n");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_DISK || res != RESULT_OK || data == null || data.getData() == null)
            return;
        final Uri uri = data.getData();
        diskBusy = true;
        refreshDisk();
        setStatus("正在导入系统盘…");
        new Thread(new Runnable() {
            @Override public void run() { importDiskThread(uri); }
        }).start();
    }

    private void importDiskThread(Uri uri) {
        final File part = new File(getFilesDir(), "android.ext4.part");
        InputStream in = null;
        FileOutputStream out = null;
        try {
            in = new BufferedInputStream(
                    getContentResolver().openInputStream(uri), 1 << 16);
            in.mark(2);
            int b0 = in.read();
            int b1 = in.read();
            in.reset();
            if (b0 == 0x1f && b1 == 0x8b)
                in = new GZIPInputStream(in, 1 << 16);
            out = new FileOutputStream(part);
            byte[] buf = new byte[1 << 20];
            long done = 0;
            long last = -1;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                long step = done / 107374182L;
                if (step != last) {
                    last = step;
                    setStatus(String.format(Locale.US, "正在导入系统盘，%.1f GB",
                                            done / 1073741824.0));
                }
            }
            out.flush();
            out.close();
            out = null;
            in.close();
            in = null;
            File dst = diskFile();
            if (dst.exists() && !dst.delete())
                throw new IOException("删不掉旧的 android.ext4");
            if (!part.renameTo(dst))
                throw new IOException("改名失败");
            append("系统盘已导入：" + dst.length() + " 字节\n");
            setStatus("系统盘已就绪");
            announce("系统盘导入完成，可以启动");
        } catch (Exception e) {
            part.delete();
            append("系统盘导入失败：" + e + "\n");
            setStatus("系统盘导入失败");
        } finally {
            try { if (out != null) out.close(); } catch (Exception ignored) { }
            try { if (in != null) in.close(); } catch (Exception ignored) { }
            diskBusy = false;
            refreshDisk();
        }
    }

    /* ------------------------------------------------------------------
     * 无障碍层
     *
     * 三条规矩，和别的无障碍实现一致：
     *   1. 状态变化挂在 live region 上，由读屏自己念，不抢焦点；
     *   2. 内核输出绝不逐行播报，只在用户明确按了按钮时念最后几行 ——
     *      那是对按键的回答，按规则可以打断正在进行的朗读；
     *   3. 不自己起 TTS。走 announceForAccessibility，用户关掉读屏就不出声。
     *      这是「无障碍优先」，不是「无论如何都出声」。
     * ------------------------------------------------------------------ */

    /** 改状态行。live region 会让读屏在它变化时自己念出来。 */
    private void setStatus(final String s) {
        ui.post(new Runnable() {
            @Override public void run() { status.setText("状态：" + s); }
        });
    }

    /** 只改按钮的文案与描述，不动状态行。 */
    private void setRunning(final boolean running) {
        ui.post(new Runnable() {
            @Override public void run() {
                startStop.setText(running ? "停止" : "启动");
                startStop.setContentDescription(running ? "停止内核" : "启动内核");
            }
        });
    }

    /** 只说一句，不改状态行；用于「已复制」这类一次性回答。 */
    private void announce(final String s) {
        ui.post(new Runnable() {
            @Override public void run() { status.announceForAccessibility(s); }
        });
    }

    /* 上一次念到的片段起点；-1 表示还没念过。以「非空行」为单位计数，
     * 于是之后追加的新输出不会打乱已经建立的位置。 */
    private int readAnchor = -1;

    /** 日志的非空行。空行会让读屏停顿却没内容，先剔掉。 */
    private List<String> logLines() {
        List<String> out = new ArrayList<>();
        CharSequence c = log.getText();
        if (c != null) {
            for (String s : c.toString().split("\n")) {
                String t = s.trim();
                if (t.length() > 0)
                    out.add(t);
            }
        }
        return out;
    }

    /** 念最新三行。 */
    private void readNewest() {
        List<String> all = logLines();
        if (all.isEmpty()) {
            announce("还没有输出");
            return;
        }
        int end = all.size();
        int start = Math.max(0, end - 3);
        readAnchor = start;
        announceChunk(all, start, end);
    }

    /** 往上翻三行；还没念过时等同于念最新。 */
    private void readOlder() {
        List<String> all = logLines();
        if (all.isEmpty()) {
            announce("还没有输出");
            return;
        }
        int end = (readAnchor < 0) ? all.size() : readAnchor;
        if (end <= 0) {
            announce("已经到最早一行了，共 " + all.size() + " 行");
            return;
        }
        int start = Math.max(0, end - 3);
        readAnchor = start;
        announceChunk(all, start, end);
    }

    /** 一段一次说完，并且报出位置——用户得知道自己翻到哪了。 */
    private void announceChunk(List<String> all, int start, int end) {
        StringBuilder b = new StringBuilder();
        for (int i = start; i < end; i++)
            b.append(all.get(i)).append('\n');
        announce("第 " + (start + 1) + " 到 " + end + " 行，共 " + all.size() + " 行\n"
                + b.toString().trim());
    }

    private void copyAll() {
        ClipboardManager cm =
                (ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) {
            announce("剪贴板不可用");
            return;
        }
        cm.setPrimaryClip(ClipData.newPlainText("DroidVM 内核输出", log.getText()));
        announce("已复制全部输出，共 " + log.length() + " 个字符");
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static String join(List<String> l) {
        StringBuilder b = new StringBuilder();
        for (String s : l) { b.append(s); b.append(' '); }
        return b.toString().trim();
    }
}
