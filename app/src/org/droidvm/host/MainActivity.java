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

    private Process guest;
    private OutputStream guestIn;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private static final int MAX_CHARS = 200000;

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
        memField.setText("2048");
        memField.setHint("2048");
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

    private void append(String s) {
        logToFile(s);
        ui.post(new Appender(s));
    }

    /**
     * Unpack a gzipped asset once, into the app's private files directory.
     * Data, not code -- so it may live here; it just may not be executed.
     */
    private File unpackAsset(String asset, String outName) throws IOException {
        File out = new File(getFilesDir(), outName);
        if (out.exists() && out.length() > 0)
            return out;

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
            if (rootfs != null) {
                cmd.add("ubd0=" + rootfs.getAbsolutePath());
                cmd.add("root=/dev/ubda");
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

    private void send(String s) {
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
