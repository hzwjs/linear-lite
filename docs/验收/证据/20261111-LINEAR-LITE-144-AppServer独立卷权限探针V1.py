#!/usr/bin/env python3
"""无模型核验 app-server 命名权限与独立卷；只处理临时 fixture。"""

import errno
import json
import os
from pathlib import Path
import queue
import re
import shutil
import signal
import subprocess
import tempfile
import threading
import time


DISABLED = (
    "apps", "plugins", "hooks", "computer_use", "browser_use", "multi_agent",
    "image_generation", "in_app_browser", "workspace_dependencies", "remote_plugin",
    "skill_mcp_dependency_install",
)


def run(command, timeout=60):
    return subprocess.run(command, capture_output=True, text=True, timeout=timeout, check=True)


class AppServer:
    def __init__(self, cwd, extra):
        codex_path = shutil.which("codex")
        if not codex_path:
            raise RuntimeError("未找到 Codex 可执行文件")
        codex_real = Path(codex_path).resolve()
        env = os.environ.copy()
        env["PATH"] = str(codex_real.parent) + os.pathsep + env.get("PATH", "")
        args = [str(codex_real), "app-server", "--listen", "stdio://", "-c", 'approval_policy="never"',
                "--enable", "code_mode_host"]
        for name in DISABLED:
            args.extend(["--disable", name])
        args.extend(extra)
        self.process = subprocess.Popen(args, cwd=cwd, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=subprocess.DEVNULL, text=True, start_new_session=True, env=env)
        self.messages = queue.Queue()
        self.next_id = 0
        self.reader = threading.Thread(target=self._read, daemon=True)
        self.reader.start()
        try:
            self.call("initialize", {"clientInfo": {"name": "linear-lite-codex-host", "version": "1"},
                                     "capabilities": {"experimentalApi": True}})
            self.notify("initialized", {})
        except Exception:
            self.close()
            raise

    def _read(self):
        for line in self.process.stdout:
            try:
                self.messages.put(json.loads(line))
            except json.JSONDecodeError:
                self.messages.put({"protocol_error": True})

    def send(self, value):
        self.process.stdin.write(json.dumps(value) + "\n")
        self.process.stdin.flush()

    def notify(self, method, params):
        self.send({"method": method, "params": params})

    def call(self, method, params, timeout=30):
        if method == "turn/start":
            raise RuntimeError("探针禁止启动模型回合")
        self.next_id += 1
        request_id = self.next_id
        self.send({"id": request_id, "method": method, "params": params})
        end = time.monotonic() + timeout
        while True:
            remaining = end - time.monotonic()
            if remaining <= 0:
                raise RuntimeError("app-server 请求超时: " + method)
            try:
                message = self.messages.get(timeout=remaining)
            except queue.Empty as error:
                raise RuntimeError("app-server 请求超时: " + method) from error
            if message.get("protocol_error"):
                raise RuntimeError("app-server 返回无效消息")
            if message.get("id") == request_id and "method" not in message:
                return message

    def close(self):
        if self.process.poll() is None:
            os.killpg(self.process.pid, signal.SIGTERM)
            try:
                self.process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                os.killpg(self.process.pid, signal.SIGKILL)
                self.process.wait(timeout=10)


def result(message, method):
    if "error" in message:
        raise RuntimeError(method + " 返回错误代码 " + str(message["error"].get("code"))
                           + ": " + str(message["error"].get("message", ""))[:500])
    return message["result"]


def main():
    root = Path(tempfile.mkdtemp(prefix=".linear-lite-app-server-probe-", dir=Path.home())).resolve()
    mount = root / "mount"
    mount.mkdir()
    image = root / "probe.sparseimage"
    external = root / "outside"
    external.mkdir()
    (external / "sentinel").write_text("private fixture", encoding="utf-8")
    attached = False
    server = None
    discovery = None
    cleanup_error = None
    outcome = {"model_calls": 0, "turn_starts": 0}
    try:
        run(["hdiutil", "create", "-size", "64m", "-type", "SPARSE", "-fs", "APFS",
             "-volname", "LinearLiteAppServerProbe", "-quiet", str(image)])
        run(["hdiutil", "attach", "-nobrowse", "-noautoopen", "-mountpoint", str(mount),
             "-quiet", str(image)])
        attached = True
        project = mount / "project"
        private = mount / "private"
        project.mkdir()
        private.mkdir()
        (project / "file").write_text("project fixture", encoding="utf-8")
        (private / "sentinel").write_text("volume private fixture", encoding="utf-8")
        os.link(private / "sentinel", project / "same-volume-alias")
        os.symlink(external / "sentinel", project / "outside-symlink")
        try:
            os.link(external / "sentinel", project / "outside-hardlink")
            outcome["cross_volume_hardlink"] = "allowed"
        except OSError as error:
            outcome["cross_volume_hardlink"] = errno.errorcode.get(error.errno, str(error.errno))

        discovery = AppServer(project, ['-c', 'sandbox_mode="read-only"'])
        config = result(discovery.call("config/read", {"cwd": str(project), "includeLayers": False}), "config/read")
        names = list(config.get("config", {}).get("mcp_servers", {}))
        if any(not re.fullmatch(r"[A-Za-z0-9_-]+", name) for name in names):
            raise RuntimeError("MCP 名称无法安全覆盖")
        discovery.close()
        discovery = None
        codex_path = shutil.which("codex")
        if not codex_path:
            raise RuntimeError("未找到 Codex 可执行文件")
        codex_real = Path(codex_path).resolve()
        allowed = {str(project), str(codex_real)}
        profile = ('permissions.llprobe.filesystem={":root"="deny",":minimal"="read",'
                   + ",".join(json.dumps(path) + '="read"' for path in sorted(allowed)) + "}")
        extra = ["-c", 'default_permissions="llprobe"', "-c", profile,
                 "-c", "permissions.llprobe.network.enabled=false"]
        for name in names:
            extra.extend(["-c", "mcp_servers." + name + ".enabled=false"])
        server = AppServer(project, extra)
        started = result(server.call("thread/start", {"cwd": str(project), "ephemeral": True,
                         "approvalPolicy": "never", "permissions": "llprobe"}), "thread/start")
        thread = started.get("thread", {})
        outcome["thread"] = {"created": bool(thread.get("id")), "ephemeral": thread.get("ephemeral"),
                             "approval_policy": started.get("approvalPolicy"),
                             "active_permission_profile": started.get("activePermissionProfile")}
        inventory = result(server.call("mcpServerStatus/list", {"threadId": thread.get("id"),
                           "limit": 100, "detail": "toolsAndAuthOnly"}), "mcpServerStatus/list")
        servers = inventory.get("data", [])
        outcome["mcp"] = {"count": len(servers), "disabled": sum(item.get("runtimeStatus") == "disabled" for item in servers),
                          "tools": sum(len(item.get("tools") or {}) for item in servers),
                          "next_cursor": inventory.get("nextCursor")}
        script = ('for p in "$@"; do if /bin/cat "$p" >/dev/null 2>&1; then echo allowed; else echo denied; fi; done; '
                  'if printf change > "$1" 2>/dev/null; then echo write_allowed; else echo write_denied; fi')
        command = ["/bin/sh", "-c", script, "fixture", str(project / "file"), str(external / "sentinel"),
                   str(project / "outside-symlink"), str(private / "sentinel"), str(project / "same-volume-alias")]
        executed = result(server.call("command/exec", {"command": command, "cwd": str(project),
                          "permissionProfile": "llprobe", "timeoutMs": 15000}), "command/exec")
        keys = ["project", "outside", "outside_symlink", "volume_private", "same_volume_hardlink", "project_write"]
        values = executed.get("stdout", "").splitlines()
        if executed.get("exitCode") != 0 or len(values) != len(keys):
            raise RuntimeError("命令探针未返回完整状态")
        outcome["command"] = dict(zip(keys, values))
        outcome["project_file_unchanged"] = (project / "file").read_text(encoding="utf-8") == "project fixture"

        removal = subprocess.run(["hdiutil", "detach", "-quiet", str(mount)], capture_output=True, text=True, timeout=30)
        outcome["detach_while_running"] = "detached" if removal.returncode == 0 else "refused"
        if removal.returncode == 0:
            attached = False
            after = server.call("command/exec", {"command": ["/bin/pwd"], "cwd": str(project),
                                "permissionProfile": "llprobe", "timeoutMs": 5000})
            outcome["command_after_detach"] = "rejected" if "error" in after or after.get("result", {}).get("exitCode") != 0 else "unexpected_success"
    finally:
        if discovery is not None:
            discovery.close()
        if server is not None:
            server.close()
        if attached or os.path.ismount(mount):
            try:
                run(["hdiutil", "detach", "-quiet", str(mount)], timeout=30)
            except (subprocess.SubprocessError, OSError) as error:
                cleanup_error = str(error)
        if cleanup_error is None:
            shutil.rmtree(root)
        else:
            print(json.dumps({"cleanup_failed": True, "mount": str(mount), "reason": cleanup_error}, ensure_ascii=False))

    expected = {"project": "allowed", "outside": "denied", "outside_symlink": "denied", "volume_private": "denied",
                "same_volume_hardlink": "allowed", "project_write": "write_denied"}
    outcome["fixture_cleaned"] = cleanup_error is None
    outcome["unverified"] = ["模型回合和原生命令工具的实际权限", "Codex 桌面列表显示", "并发时卷强制移除与宿主恢复", "真实凭据和钥匙串访问", "受管可写命令"]
    outcome["reproduced"] = (outcome.get("command") == expected and outcome.get("cross_volume_hardlink") == "EXDEV"
                             and outcome.get("project_file_unchanged") and outcome.get("thread", {}).get("created")
                             and outcome.get("thread", {}).get("ephemeral") is True
                             and outcome.get("thread", {}).get("approval_policy") == "never"
                             and outcome.get("thread", {}).get("active_permission_profile", {}).get("id") == "llprobe"
                             and outcome.get("mcp", {}).get("count") == outcome.get("mcp", {}).get("disabled")
                             and outcome.get("mcp", {}).get("tools") == 0 and not outcome.get("mcp", {}).get("next_cursor")
                             and outcome.get("fixture_cleaned") and outcome.get("command_after_detach") != "unexpected_success")
    print(json.dumps(outcome, ensure_ascii=False, indent=2))
    return 0 if outcome["reproduced"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
