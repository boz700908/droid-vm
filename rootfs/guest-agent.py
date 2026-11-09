#!/usr/bin/env python3
"""guest 侧的隧道对端。

读 /mnt/host/rpc/in/*.req，执行，把结果写到 rpc/out/ 里的同名文件。一行一个 JSON，
协议见 docs/tunnel.md。路径一律是 guest 内的绝对路径，本程序以 guest root 运行 ——
用户要的就是「镜像里的文件都能读能写」，所以这里不做路径白名单；能不能进来由 App
那头决定，它才是暴露在外面的那半边。

写文件默认 append：大文件是分块发过来的，每块一次 write，不能用覆盖模式。
"""
import base64
import json
import os
import shutil
import subprocess
import time

RPC = os.environ.get("DROIDVM_RPC", "/mnt/host/rpc")
IN = os.path.join(RPC, "in")
OUT = os.path.join(RPC, "out")
TMP = os.path.join(RPC, "tmp")
# 一次最多搬 4MB：再大就分块，免得把 guest 的内存吃穿。
MAX = 4 << 20


def _abs(req, key):
    v = req.get(key)
    if not isinstance(v, str) or not v.startswith("/"):
        raise ValueError("%s 必须是 guest 内的绝对路径" % key)
    return v


def handle_request(req):
    op = req.get("op")
    if op == "ping":
        return {"ok": True, "pid": os.getpid(), "rpc": RPC}
    if op == "stat":
        p = _abs(req, "path")
        st = os.lstat(p)
        if os.path.islink(p):
            kind = "link"
        elif os.path.isdir(p):
            kind = "dir"
        else:
            kind = "file"
        return {"ok": True, "type": kind, "size": st.st_size,
                "mode": oct(st.st_mode & 0o7777), "uid": st.st_uid,
                "gid": st.st_gid, "mtime": int(st.st_mtime)}
    if op == "readdir":
        p = _abs(req, "path")
        out = []
        with os.scandir(p) as it:
            for e in it:
                try:
                    st = e.stat(follow_symlinks=False)
                except OSError:
                    continue
                out.append({"name": e.name,
                            "dir": e.is_dir(follow_symlinks=False),
                            "size": st.st_size})
        out.sort(key=lambda x: x["name"])
        return {"ok": True, "entries": out}
    if op == "read":
        p = _abs(req, "path")
        off = int(req.get("offset") or 0)
        n = min(int(req.get("length") or MAX), MAX)
        with open(p, "rb") as f:
            f.seek(off)
            data = f.read(n)
        return {"ok": True, "offset": off, "eof": len(data) < n,
                "data": base64.b64encode(data).decode()}
    if op == "write":
        p = _abs(req, "path")
        data = base64.b64decode(req.get("data") or "")
        mode = "wb" if req.get("truncate") else "ab"
        with open(p, mode) as f:
            f.write(data)
        return {"ok": True, "written": len(data)}
    if op == "mkdir":
        os.makedirs(_abs(req, "path"), exist_ok=True)
        return {"ok": True}
    if op == "unlink":
        p = _abs(req, "path")
        if os.path.isdir(p) and not os.path.islink(p):
            shutil.rmtree(p)
        else:
            os.unlink(p)
        return {"ok": True}
    if op == "rename":
        os.replace(_abs(req, "from"), _abs(req, "to"))
        return {"ok": True}
    if op == "exec":
        # 逃生通道的核心：Android 占着 tty0 的时候，App 只能从这里进来。
        # 本进程活在 Alpine 的挂载命名空间里（redroid-up 的 pivot_root 只影响
        # 它自己 unshare 出来的那个命名空间），所以 Android 起来了照样能在这里
        # 跑命令：看 dmesg、umount、kill，都是 Alpine 侧的视角。
        cmd = req.get("cmd")
        if not isinstance(cmd, str) or not cmd.strip():
            raise ValueError("cmd 不能为空")
        t = int(req.get("timeout") or 20)
        t = max(1, min(t, 120))
        try:
            p = subprocess.run(["/bin/sh", "-c", cmd], stdin=subprocess.DEVNULL,
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               timeout=t)
        except subprocess.TimeoutExpired as e:
            partial = (e.stdout or b"").decode("utf-8", "replace")
            return {"ok": False, "err": "超时（%d 秒）" % t, "rc": -1,
                    "out": partial[:200000]}
        out = p.stdout.decode("utf-8", "replace")
        if len(out) > 200000:
            out = out[:200000] + "\n…（输出超过 200000 字节，已截断）"
        return {"ok": True, "rc": p.returncode, "out": out}
    raise ValueError("不认识的 op: %r" % (op,))


def answer(name):
    path = os.path.join(IN, name)
    try:
        with open(path, "r") as f:
            req = json.loads(f.read() or "{}")
    except Exception as e:                                # 半截文件、坏 JSON
        resp = {"ok": False, "err": "请求读不出来: %s" % e}
    else:
        try:
            resp = handle_request(req)
        except Exception as e:
            resp = {"ok": False, "err": "%s: %s" % (type(e).__name__, e)}
    part = os.path.join(TMP, name + ".part")
    with open(part, "w") as f:
        f.write(json.dumps(resp, ensure_ascii=False) + "\n")
    # 只经 rename 发布：对端永远不会读到半个响应。
    os.replace(part, os.path.join(OUT, name))
    os.unlink(path)


def main():
    for d in (IN, OUT, TMP):
        os.makedirs(d, exist_ok=True)
    while True:
        try:
            names = sorted(n for n in os.listdir(IN) if n.endswith(".req"))
        except OSError:
            names = []
        for n in names:
            try:
                answer(n)
            except Exception:
                pass
        time.sleep(0.1 if names else 0.2)


if __name__ == "__main__":
    main()
