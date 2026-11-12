#!/usr/bin/env python3
"""仅用自建 fixture 验证零权限挂载底座及沙箱强制卸载回落。"""

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


def start_sandbox(project, mount, replacement, access):
    profile = ('permissions.llprobe.filesystem={":root"="deny",":minimal"="read",'
               + json.dumps(str(project)) + '=' + json.dumps(access) + '}')
    script = (
        'value=$(/bin/cat "$1" 2>/dev/null) || value=DENIED; printf "READY:%s\\n" "$value"; '
        'IFS= read -r signal; '
        'before=$(/bin/cat "$1" 2>/dev/null) || before=DENIED; '
        'if /bin/chmod 755 "$2" >/dev/null 2>&1; then base=allowed; else base=denied; fi; '
        'if /bin/chmod 755 "$3" >/dev/null 2>&1; then project=allowed; else project=denied; fi; '
        'after=$(/bin/cat "$1" 2>/dev/null) || after=DENIED; '
        'if /bin/mv "$2" "$4" >/dev/null 2>&1; then replace=allowed; else replace=denied; fi; '
        'if (printf CHANGED > "$1") 2>/dev/null; then write=allowed; else write=denied; fi; '
        'printf "AFTER:%s:%s:%s:%s:%s:%s\\n" "$before" "$base" "$project" "$after" "$replace" "$write"'
    )
    command = ["codex", "sandbox", "-P", "llprobe", "-c", profile,
               "-c", "permissions.llprobe.network.enabled=false", "-C", str(project),
               "--", "/bin/sh", "-c", script, "fixture", str(project / "marker"),
               str(mount), str(project), str(replacement)]
    proc = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, text=True, start_new_session=True)
    if not select.select([proc.stdout], [], [], 30)[0]:
        os.killpg(proc.pid, signal.SIGKILL)
        proc.wait(timeout=10)
        raise RuntimeError("沙箱未报告准备状态")
    ready = proc.stdout.readline().strip()
    if ready != "READY:MOUNTED_FIXTURE":
        if proc.poll() is None:
            os.killpg(proc.pid, signal.SIGKILL)
            proc.wait(timeout=10)
        raise RuntimeError("沙箱准备状态错误: " + ready)
    return proc


def finish_sandbox(proc):
    output, stderr = proc.communicate("continue\n", timeout=30)
    values = output.strip().split(":")
    if proc.returncode != 0 or len(values) != 7 or values[0] != "AFTER":
        raise RuntimeError("沙箱未返回可识别结果: " + output[:300] + " " + stderr[:300])
    keys = ["before_chmod_read", "chmod_base", "chmod_project", "after_chmod_read", "replace_base", "write_marker"]
    return dict(zip(keys, values[1:]))


def main():
    root = Path(tempfile.mkdtemp(prefix=".linear-lite-zero-base-", dir=Path.home())).resolve()
    mount = root / "mount"
    mount.mkdir()
    (mount / "marker").write_text("UNDERLYING_FIXTURE", encoding="utf-8")
    image = root / "probe.sparseimage"
    replacement = root / "replacement"
    attached = False
    processes = []
    cleanup_error = None
    outcome = {"model_calls": 0, "turn_starts": 0}
    try:
        run(["hdiutil", "create", "-size", "64m", "-type", "SPARSE", "-fs", "APFS",
             "-volname", "LinearLiteZeroBaseProbe", "-quiet", str(image)])
        mount.chmod(0)
        attach = subprocess.run(["hdiutil", "attach", "-nobrowse", "-noautoopen", "-mountpoint", str(mount),
                                 "-quiet", str(image)], capture_output=True, text=True, timeout=45)
        outcome["attach_to_mode_000"] = "allowed" if attach.returncode == 0 else "denied"
        outcome["attach_error"] = attach.stderr[:300]
        if attach.returncode == 0:
            attached = True
            project = mount
            (project / "marker").write_text("MOUNTED_FIXTURE", encoding="utf-8")
            for access in ("read", "write"):
                processes.append(start_sandbox(project, mount, replacement, access))
            detached = subprocess.run(["hdiutil", "detach", "-force", "-quiet", str(mount)],
                                      capture_output=True, text=True, timeout=40)
            outcome["forced_detach"] = "allowed" if detached.returncode == 0 else "denied"
            outcome["detach_error"] = detached.stderr[:300]
            if detached.returncode == 0:
                attached = False
                for access, proc in zip(("read", "write"), processes):
                    outcome[access] = finish_sandbox(proc)
                    # 若探针成功改变底座，恢复 fixture 才能独立比较下一种 profile。
                    if not mount.exists() and replacement.exists():
                        replacement.rename(mount)
                    mount.chmod(0o700)
                    (mount / "marker").write_text("UNDERLYING_FIXTURE", encoding="utf-8")
                    mount.chmod(0)
    finally:
        for proc in processes:
            if proc.poll() is None:
                os.killpg(proc.pid, signal.SIGKILL)
                proc.wait(timeout=10)
        if attached or os.path.ismount(mount):
            try:
                run(["hdiutil", "detach", "-quiet", str(mount)], timeout=30)
            except (subprocess.SubprocessError, OSError) as error:
                cleanup_error = str(error)
        if cleanup_error is None:
            mount.chmod(0o700)
            shutil.rmtree(root)
        else:
            print(json.dumps({"cleanup_failed": True, "mount": str(mount), "reason": cleanup_error}, ensure_ascii=False))
    outcome["fixture_cleaned"] = cleanup_error is None
    print(json.dumps(outcome, ensure_ascii=False, indent=2))
    return 0 if outcome.get("read") and outcome.get("write") and outcome["fixture_cleaned"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
