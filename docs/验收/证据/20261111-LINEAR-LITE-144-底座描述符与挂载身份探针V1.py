#!/usr/bin/env python3
"""验证零权限底座描述符与自建 APFS 测试卷的挂载生命周期。"""

import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import tempfile


def identity(attributes):
    return {"device": attributes.st_dev, "inode": attributes.st_ino,
            "mode": format(stat.S_IMODE(attributes.st_mode), "04o")}


def main():
    root = Path(tempfile.mkdtemp(prefix=".linear-lite-fd-mount-", dir=Path.home())).resolve()
    mount = root / "mount"
    mount.mkdir()
    image = root / "probe.sparseimage"
    fd = None
    attached = False
    cleanup_error = None
    outcome = {"model_calls": 0, "turn_starts": 0}
    try:
        create = subprocess.run(["hdiutil", "create", "-size", "64m", "-type", "SPARSE", "-fs", "APFS",
                                 "-volname", "LinearLiteFdProbe", "-quiet", str(image)],
                                capture_output=True, text=True, timeout=60, check=True)
        outcome["image_create_exit"] = create.returncode
        fd = os.open(mount, os.O_RDONLY | os.O_DIRECTORY)
        outcome["base_before_chmod"] = identity(os.fstat(fd))
        os.fchmod(fd, 0)
        outcome["base_before_attach"] = identity(os.fstat(fd))
        attach = subprocess.run(["hdiutil", "attach", "-owners", "on", "-nobrowse", "-noautoopen",
                                 "-mountpoint", str(mount), "-quiet", str(image)],
                                capture_output=True, text=True, timeout=60)
        outcome["attach_exit"] = attach.returncode
        outcome["attach_error"] = attach.stderr[:300]
        if attach.returncode == 0:
            attached = True
            outcome["base_fd_after_attach"] = identity(os.fstat(fd))
            outcome["visible_mount_after_attach"] = identity(os.stat(mount))
            outcome["base_fd_stable"] = outcome["base_before_attach"] == outcome["base_fd_after_attach"]
            outcome["visible_is_distinct"] = (outcome["visible_mount_after_attach"]["device"],
                                                outcome["visible_mount_after_attach"]["inode"]) != (
                                                    outcome["base_fd_after_attach"]["device"],
                                                    outcome["base_fd_after_attach"]["inode"])
            detach = subprocess.run(["hdiutil", "detach", "-quiet", str(mount)],
                                    capture_output=True, text=True, timeout=30)
            outcome["detach_exit"] = detach.returncode
            outcome["detach_error"] = detach.stderr[:300]
            if detach.returncode == 0:
                attached = False
                outcome["base_fd_after_detach"] = identity(os.fstat(fd))
                outcome["visible_base_after_detach"] = identity(os.stat(mount))
    finally:
        if attached or os.path.ismount(mount):
            try:
                subprocess.run(["hdiutil", "detach", "-quiet", str(mount)],
                               capture_output=True, text=True, timeout=30, check=True)
            except (subprocess.SubprocessError, OSError) as error:
                cleanup_error = str(error)
        if fd is not None:
            try:
                os.fchmod(fd, 0o700)
            finally:
                os.close(fd)
        if cleanup_error is None:
            shutil.rmtree(root)
        else:
            print(json.dumps({"cleanup_failed": True, "mount": str(mount), "reason": cleanup_error}, ensure_ascii=False))

    outcome["fixture_cleaned"] = cleanup_error is None
    outcome["not_verified"] = ["其他进程替换底座的并发风险", "宿主重启时 FD 恢复", "模型、协作批次及生产挂载"]
    outcome["verified"] = (outcome.get("attach_exit") == 0 and outcome.get("detach_exit") == 0
                           and outcome.get("base_fd_stable") is True and outcome.get("visible_is_distinct") is True
                           and outcome.get("base_before_attach", {}).get("mode") == "0000"
                           and outcome.get("base_before_attach") == outcome.get("base_fd_after_detach")
                           and outcome.get("base_fd_after_detach") == outcome.get("visible_base_after_detach")
                           and outcome["fixture_cleaned"])
    print(json.dumps(outcome, ensure_ascii=False, indent=2))
    return 0 if outcome["verified"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
