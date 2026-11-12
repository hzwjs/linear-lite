#!/usr/bin/env python3
"""复现 Codex 命名权限对硬链接的边界；不访问真实项目或凭据。

仅适用于 macOS：创建临时 APFS 稀疏镜像，结束时卸载并删除全部 fixture。
退出码 0 表示探针复现预期现象，不表示安全隔离通过。
"""

import errno
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


def sandbox_command(project, script, paths):
    profile = (
        'permissions.llprobe.filesystem={":root"="deny",":minimal"="read",'
        + json.dumps(str(project))
        + '="read"}'
    )
    return [
        "codex", "sandbox", "-P", "llprobe", "-c", profile,
        "-c", "permissions.llprobe.network.enabled=false", "-C", str(project),
        "--", "/bin/sh", "-c", script, "fixture", *map(str, paths),
    ]


def read_results(project, paths):
    script = 'for p in "$@"; do if /bin/cat "$p" >/dev/null 2>&1; then echo allowed; else echo denied; fi; done'
    result = run(sandbox_command(project, script, paths), timeout=30)
    values = result.stdout.splitlines()
    if len(values) != len(paths) or any(value not in {"allowed", "denied"} for value in values):
        raise RuntimeError("沙箱探针返回了无法识别的结果")
    return values


def race_result(project, private_file, alias):
    script = 'echo READY; IFS= read -r signal; if /bin/cat "$1" >/dev/null 2>&1; then echo allowed; else echo denied; fi'
    proc = subprocess.Popen(
        sandbox_command(project, script, [alias]), stdin=subprocess.PIPE,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, start_new_session=True,
    )
    try:
        if not select.select([proc.stdout], [], [], 20)[0] or proc.stdout.readline().strip() != "READY":
            raise RuntimeError("沙箱子进程未准备好")
        os.link(private_file, alias)
        output, _ = proc.communicate("continue\n", timeout=20)
        if proc.returncode != 0 or output.strip() not in {"allowed", "denied"}:
            raise RuntimeError("沙箱并发探针执行失败")
        return output.strip()
    finally:
        if proc.poll() is None:
            os.killpg(proc.pid, signal.SIGKILL)
            proc.wait(timeout=10)


def main():
    # fixture 放在用户目录，避免系统临时目录被 :minimal 放行。
    root = Path(tempfile.mkdtemp(prefix=".linear-lite-144-probe-", dir=Path.home())).resolve()
    mount = root / "mount"
    mount.mkdir()
    image = root / "probe.sparseimage"
    attached = False
    cleanup_error = None
    result = {}
    try:
        project = root / "same-project"
        private = root / "same-private"
        project.mkdir()
        private.mkdir()
        (project / "plain").write_text("project fixture", encoding="utf-8")
        (private / "sentinel").write_text("private fixture", encoding="utf-8")
        os.link(private / "sentinel", project / "alias")
        keys = ["project", "private_path", "hardlink"]
        values = read_results(project, [project / "plain", private / "sentinel", project / "alias"])
        result["same_volume"] = dict(zip(keys, values))
        result["same_volume"]["hardlink_after_start"] = race_result(
            project, private / "sentinel", project / "late-alias"
        )

        run(["hdiutil", "create", "-size", "64m", "-type", "SPARSE", "-fs", "APFS",
             "-volname", "LinearLiteBoundaryProbe", "-quiet", str(image)])
        run(["hdiutil", "attach", "-nobrowse", "-noautoopen", "-mountpoint", str(mount),
             "-quiet", str(image)])
        attached = True
        volume_project = mount / "project"
        volume_private = mount / "private"
        volume_project.mkdir()
        volume_private.mkdir()
        (volume_project / "plain").write_text("project fixture", encoding="utf-8")
        (volume_private / "sentinel").write_text("volume private fixture", encoding="utf-8")
        os.symlink(private / "sentinel", volume_project / "outside-symlink")
        os.link(volume_private / "sentinel", volume_project / "same-volume-alias")
        try:
            os.link(private / "sentinel", volume_project / "cross-volume-alias")
            cross_volume = "allowed"
        except OSError as error:
            cross_volume = errno.errorcode.get(error.errno, str(error.errno))
        keys = ["project", "outside_private_path", "outside_symlink", "volume_private_path", "volume_private_hardlink"]
        values = read_results(volume_project, [volume_project / "plain", private / "sentinel",
                              volume_project / "outside-symlink", volume_private / "sentinel",
                              volume_project / "same-volume-alias"])
        result["isolated_volume"] = {**dict(zip(keys, values)), "cross_volume_hardlink": cross_volume}
    finally:
        # 即使挂载命令异常，也检查挂载点；卸载失败时保留目录以便处理。
        if attached or os.path.ismount(mount):
            try:
                run(["hdiutil", "detach", "-quiet", str(mount)], timeout=30)
            except (subprocess.SubprocessError, OSError) as error:
                cleanup_error = str(error)
        if cleanup_error is None:
            shutil.rmtree(root)
        else:
            print(json.dumps({"cleanup_failed": True, "mount": str(mount), "reason": cleanup_error}, ensure_ascii=False))

    expected_same = {"project": "allowed", "private_path": "denied", "hardlink": "allowed", "hardlink_after_start": "allowed"}
    expected_volume = {"project": "allowed", "outside_private_path": "denied", "outside_symlink": "denied",
                       "volume_private_path": "denied", "volume_private_hardlink": "allowed", "cross_volume_hardlink": "EXDEV"}
    result["fixture_cleaned"] = cleanup_error is None
    result["vulnerability_reproduced"] = result.get("same_volume") == expected_same and result.get("isolated_volume") == expected_volume
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["vulnerability_reproduced"] and result["fixture_cleaned"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
