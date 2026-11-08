#!/bin/bash
# 不用 Gradle 打 APK。
#
# 上游 dist/umapp 的做法原样保留：Gradle 会拖进整个 Android 工程布局和它自己
# 那套工具链的下载，而这里只需要四个工具、十来秒：
#
#   aapt2 / aapt  打包资源和 manifest
#   javac         对着 android.jar 编那一个 Activity
#   d8            .class → classes.dex
#   apksigner     签名（不签名的 APK 装不上）
#
# 二进制才是最麻烦的地方。App 跑在 untrusted_app 域，W^X 策略禁止执行它自己
# 写出来的任何文件——所以内核、stub 不能解到数据目录里再跑。唯一的例外是平台
# 从 APK 里解出来的 nativeLibraryDir，于是每个二进制都以 lib*.so 的名字装在
# lib/arm64-v8a/ 下，从 nativeLibraryDir 执行。它们是普通可执行文件，不是
# 共享库；管用的只是名字。
#
# -e 和 -uo pipefail 一个都不能少。少了 -e，某一步失败后脚本还接着往下走到
# "签名"，打出来的还是上一次那个 APK，而 adb install 会报 Success——一个坏的
# manifest 和一个好的 manifest 看起来一模一样。
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=${ROOT:-$(cd "$HERE/.." && pwd)}
# 内核产物目录：CI 里是 $GITHUB_WORKSPACE/artifacts，本机是 /root/out/um4k。
A=${ARTIFACTS:-$ROOT/artifacts}
BT=${BT:-$(ls -d "${ANDROID_HOME:-$HOME/Android/Sdk}"/build-tools/* 2>/dev/null | sort -V | tail -1)}
ANDROID_JAR=${ANDROID_JAR:-$(ls "${ANDROID_HOME:-$HOME/Android/Sdk}"/platforms/*/android.jar 2>/dev/null | sort -V | tail -1)}
OUT=${OUT:-$HERE/build}
APK=${APK:-$HERE/droidvm.apk}

# 每一项是 src:libname。缺了就警告并在 App 里优雅降级，不中断打包。
BINARIES=(
	"$A/linux-bionic:libuml.so"
	"$A/stub_exe_bionic:libstub.so"
	"$A/umnet:libumnet.so"
	"$A/passt:libpasst.so"
	"$A/appseccomp:libappseccomp.so"
	"$A/umusb:libumusb.so"
)

# ROOTFS 可能是空的（verify_only 或本机没准备根盘），而后面无条件引用
# $ROOTFS_GZ；-u 会在那里直接退出，所以这里给它一个确定的空值。
ROOTFS_GZ=${ROOTFS_GZ:-}
INITRAMFS=${INITRAMFS:-$A/initramfs-alpine.cpio.gz}

for t in "$BT/aapt2" "$BT/d8" "$BT/apksigner"; do
	[ -x "$t" ] || { echo "build-apk: missing $t" >&2; exit 2; }
done
[ -f "$ANDROID_JAR" ] || { echo "build-apk: no android.jar (set ANDROID_JAR)" >&2; exit 2; }
command -v javac >/dev/null || { echo "build-apk: no javac" >&2; exit 2; }

rm -rf "$OUT"
mkdir -p "$OUT/res" "$OUT/classes" "$OUT/lib/arm64-v8a" "$OUT/assets"

# 根盘：优先用预压缩的，其次把 alpine.ext4 现压一份。
# 这段必须在 `rm -rf "$OUT"` 之后——压到 $OUT 里，之前放在前面会被自己清掉，
# 于是打包时以为没有根盘，静默打出一个只能靠 initramfs 的包。
if [ -z "$ROOTFS_GZ" ] && [ -n "${ROOTFS:-}" ] && [ -f "${ROOTFS}" ]; then
	ROOTFS_GZ=$OUT/rootfs.ext4.gz
	echo "== gzip $ROOTFS =="
	gzip -1 -c "$ROOTFS" > "$ROOTFS_GZ"
fi

# aapt2 编译的是目录树，而 $OUT 每次都被清空，所以签入的 res/ 要拷进去。
# res/xml/device_filter.xml 是 manifest 里 @xml/device_filter 的落点；少这份
# 拷贝，aapt2 link 会报未解析引用直接失败。
if [ -d "$HERE/res" ]; then
	cp -a "$HERE/res/." "$OUT/res/"
fi

echo "== staging native binaries =="
for b in "${BINARIES[@]}"; do
	src=${b%%:*}; dst=${b##*:}
	if [ -f "$src" ]; then
		cp -f "$src" "$OUT/lib/arm64-v8a/$dst"
		printf '   %-14s %s\n' "$dst" "$(du -h "$src" | cut -f1)"
	else
		echo "   WARNING: $src missing, $dst will be absent"
	fi
done
# 没有内核二进制时默认直接失败（别发出一份跑不起来的 APK）。
# ALLOW_NO_NATIVE=1 时只告警，用于「没有内核产物也要验证 Java 侧能编过」的场合。
if [ ! -f "$OUT/lib/arm64-v8a/libuml.so" ]; then
	if [ "${ALLOW_NO_NATIVE:-0}" = "1" ]; then
		echo "   WARNING: 没有内核二进制（ALLOW_NO_NATIVE=1）→ 只做编译验证，包不可运行" >&2
	else
		echo "build-apk: 没有内核二进制，打出来的 APK 跑不起来" >&2
		exit 2
	fi
fi

echo "== staging assets =="
if [ -n "$ROOTFS_GZ" ] && [ -f "$ROOTFS_GZ" ]; then
	cp -f "$ROOTFS_GZ" "$OUT/assets/rootfs.ext4.gz"
	printf '   %-14s %s\n' "rootfs" "$(du -h "$ROOTFS_GZ" | cut -f1)"
else
	echo "   WARNING: 没有根盘，只能靠 initramfs"
fi
if [ -f "$INITRAMFS" ]; then
	cp -f "$INITRAMFS" "$OUT/assets/initramfs.cpio.gz"
	printf '   %-14s %s\n' "initramfs" "$(du -h "$INITRAMFS" | cut -f1)"
fi

echo "== compiling =="
"$BT/aapt2" compile --dir "$OUT/res" -o "$OUT/res.zip" 2>/dev/null

# 每一步各自判状态，不串成管道：pipefail 下 `cmd | grep -v x | head` 报不出错，
# 而且 grep -v 一条都没匹配时退出码是 1，会把一次干净的 javac 判成失败。
if ! "$BT/aapt2" link \
	-o "$OUT/base.apk" \
	-I "$ANDROID_JAR" \
	--manifest "$HERE/AndroidManifest.xml" \
	--java "$OUT/classes" \
	"$OUT/res.zip" > "$OUT/aapt2.log" 2>&1; then
	echo "aapt2 link failed:" >&2
	head -20 "$OUT/aapt2.log" >&2
	exit 2
fi

if ! javac -source 8 -target 8 -nowarn \
	-bootclasspath "$ANDROID_JAR" \
	-classpath "$ANDROID_JAR" \
	-d "$OUT/classes" \
	$(find "$HERE/src" "$OUT/classes" -name '*.java') > "$OUT/javac.log" 2>&1; then
	echo "javac failed:" >&2
	grep -v "^Note:" "$OUT/javac.log" | head -30 >&2
	exit 2
fi

# --lib 不是可选的：d8 的 classpath 上没有 framework 时，遇到第一个嵌套匿名类
# 就抛内部 NullPointerException，看着像编译器 bug，其实是漏了参数。
"$BT/d8" --min-api 29 --lib "$ANDROID_JAR" --output "$OUT" \
	$(find "$OUT/classes" -name '*.class') 2>&1 | head -5 || exit 2

echo "== packaging =="
cd "$OUT"
cp base.apk staged.apk
zip -q -0 staged.apk classes.dex
zip -q -0 -r staged.apk lib
zip -q -9 -r staged.apk assets

echo "== signing =="
# 签名密钥必须是长期固定的这一把。APK 换了签名，手机上就只能先卸载再装，
# 而 filesDir 里的 android.ext4（4G 红药丸系统盘）会跟着一起没 —— 上一版的
# 「没有就 keytool 现生成」正是每次 CI 换一把新密钥的根，别再改回去。
# 仓库是私有的，密钥随仓库走（口令 droidvmdroidvm）；要改用别的密钥，指
# DROIDVM_KS=/path/to/xxx.jks。
KS=${DROIDVM_KS:-$HERE/keystore.jks}
if [ ! -f "$KS" ]; then
	echo "build-apk: 没有签名密钥 $KS —— 不临时生成，先把它补回来" >&2
	exit 2
fi

"$BT/zipalign" -f -p 4 staged.apk aligned.apk >/dev/null 2>&1 || cp staged.apk aligned.apk
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:droidvmdroidvm --key-pass pass:droidvmdroidvm \
	--min-sdk-version 29 --out "$APK" aligned.apk || exit 2

# 把签名证书指纹打进日志：相邻两次构建的这一行相同，才说明「覆盖安装」
# 还能保住 App 的数据目录。
"$BT/apksigner" verify --print-certs "$APK" 2>/dev/null \
	| sed -n 's/^Signer #1 certificate SHA-256 digest: /签名证书 SHA-256: /p'

echo
echo "APK: $APK  ($(du -h "$APK" | cut -f1))"
echo "install with:  adb install -r -t $APK"
