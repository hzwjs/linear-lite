#!/usr/bin/env python3
"""用虚构 fixture 检查沙箱子进程运行时卸载项目卷后的路径行为。"""

import json
import os
from pathlib import Path
import select
import shutil
import signal
import subprocess
import tempfile


def run(command, timeout=60):
    return subprocess.run(command, capture_output=True, text=True, timeout=timeout, check=True)


def main():
    root = Path(tempfile.mkdtemp(prefix=".linear-lite-unmount-probe-", dir=Path.home())).resolve()
    mount = root / "mount"
    mount.mkdir()
    # 挂载前在挂载点下放入测试文件，用来识别卸载后同一路径指向的底层内容。
    (mount / "project").mkdir()
    (mount / "project" / "marker").write_text("UNDERLYING_FIXTURE", encoding="utf-8")
    image = root / "probe.sparseimage"
    attached = False
    proc = None
    cleanup_error = None
    outcome = {"model_calls": 0, "turn_starts": 0}
    try:
        run(["hdiutil", "create", "-size", "64m", "-type", "SPARSE", "-fs", "APFS",
             "-volname", "LinearLiteUnmountProbe", "-quiet", str(image)])
        run(["hdiutil", "attach", "-nobrowse", "-noautoopen", "-mountpoint", str(mount),
             "-quiet", str(image)])
        attached = True
        project = mount / "project"
        project.mkdir()
        marker = project / "marker"
        marker.write_text("MOUNTED_FIXTURE", encoding="utf-8")
        profile = ('permissions.llprobe.filesystem={":root"="deny",":minimal"="read",'
                   + json.dumps(str(project)) + '="read"}')
        script = ('read_marker() { value=$(/bin/cat "$1" 2>/dev/null) && printf "%s" "$value" || printf DENIED; }; '
                  'read_cwd() { /bin/pwd -P 2>/dev/null || printf CWD_ERROR; }; '
                  'printf "BEFORE:%s:%s\\n" "$(read_marker "$1")" "$(read_cwd)"; '
                  'IFS= read -r signal; '
                  'printf "AFTER:%s:%s\\n" "$(read_marker "$1")" "$(read_cwd)"')
        command = ["codex", "sandbox", "-P", "llprobe", "-c", profile,
                   "-c", "permissions.llprobe.network.enabled=false", "-C", str(project),
                   "--", "/bin/sh", "-c", script, "fixture", str(marker)]
        proc = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, text=True, start_new_session=True)
        if not select.select([proc.stdout], [], [], 30)[0]:
            raise RuntimeError("沙箱子进程未返回卸载前状态")
        before = proc.stdout.readline().strip()
        if not before.startswith("BEFORE:MOUNTED_FIXTURE:"):
            raise RuntimeError("挂载卷读取前置条件不成立: " + before)
        outcome["before"] = {"marker": "MOUNTED_FIXTURE", "cwd": before.split(":", 2)[2]}
        detached = subprocess.run(["hdiutil", "detach", "-force", "-quiet", str(mount)],
                                  capture_output=True, text=True, timeout=40)
        outcome["forced_detach_exit"] = detached.returncode
        outcome["forced_detach_error"] = detached.stderr[:300]
        if detached.returncode == 0:
            attached = False
        output, stderr = proc.communicate("continue\n", timeout=30)
        outcome["sandbox_exit"] = proc.returncode
        outcome["sandbox_stderr"] = stderr[:300]
        line = output.strip()
        if not line.startswith("AFTER:"):
            raise RuntimeError("卸载后沙箱未返回可识别状态: " + line)
        _, after_marker, cwd = line.split(":", 2)
        outcome["after"] = {"marker": after_marker, "cwd": cwd}
    finally:
        if proc is not None and proc.poll() is None:
            os.killpg(proc.pid, signal.SIGKILL)
            proc.wait(timeout=10)
        if attached or os.path.ismount(mount):
            try:
                run(["hdiutil", "detach", "-quiet", str(mount)], timeout=30)
            except (subprocess.SubprocessError, OSError) as error:
                cleanup_error = str(error)
        if cleanup_error is None:
            shutil.rmtree(root)
        else:
            print(json.dumps({"cleanup_failed": True, "mount": str(mount), "reason": cleanup_error}, ensure_ascii=False))

    outcome["fixture_cleaned"] = cleanup_error is None
    outcome["not_verified"] = ["app-server 模型回合的挂载丢失处理", "进程退出与任务恢复", "强制卸载与实际项目目录绑定"]
    print(json.dumps(outcome, ensure_ascii=False, indent=2))
    return 0 if outcome["fixture_cleaned"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
