# Copyright 2026 Calin-Andrei Burloiu
#
#    Licensed under the Apache License, Version 2.0 (the "License");
#    you may not use this file except in compliance with the License.
#    You may obtain a copy of the License at
#
#        http://www.apache.org/licenses/LICENSE-2.0
#
#    Unless required by applicable law or agreed to in writing, software
#    distributed under the License is distributed on an "AS IS" BASIS,
#    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#    See the License for the specific language governing permissions and
#    limitations under the License.

"""Tests of bin/mtlist-dev-stack, run with:

    python3 -m unittest discover -s bin/tests -p "test_*.py"

Each test copies the script into a throwaway repository and puts fake `sbt` and `metals-standalone-client` commands
first on its `PATH`. The fakes act out what matters to the script:

- `sbt`, after a moment, listens on a socket recorded in `project/target/active.json`, writes `.bsp/sbt.json` and
  announces its server. Then, like `sbt --detach-stdio`, it detaches its standard streams and runs until SIGTERM.
  `FAKE_SBT_MODE` changes this:
  - `fail` makes it exit with an error before announcing its server.
  - `late-server` makes it start its server only after detaching, without announcing it, like sbt does when another sbt
    held the socket while it booted.
  - `slow-bsp-config` makes it write `.bsp/sbt.json` well after `active.json`.
  - `crash` makes it exit with an error once Metals has started.
- `metals-standalone-client` records whether `.bsp/sbt.json` exists and starts a child standing in for the Metals
  server and its BSP client. Then, after `FAKE_METALS_MCP_DELAY_S` seconds, it starts its MCP server: like Metals, it
  writes `.mcp.json` unless the file exists already, then logs that the server has started. Like the real BSP client,
  the child starts a stray sbt server as soon as the stack's sbt goes away. The child's command line is as long as the
  real Metals server's, whose classpath takes tens of kilobytes.

The fakes record their PIDs and arguments (sbt also its SIGTERM, and Metals whether it found `.bsp/sbt.json`) under
the state directory, in files named after the PID of the stack that started them.
"""

import json
import os
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "mtlist-dev-stack"

# How long a test waits for something that must happen, such as a process exiting.
EVENTUALLY_TIMEOUT_S = 10.0
# How long a test waits to make sure that something does not happen, such as a stray sbt server starting.
NEVER_TIMEOUT_S = 1.0
# How long a run of the script may take before the test fails.
SCRIPT_TIMEOUT_S = 60.0
# How long a slow fake Metals takes to start its MCP server: longer than the fake sbt takes to start its server.
SLOW_METALS_MCP_DELAY_S = 3.0

# `exec` keeps the PID and command line in the server.
FAKE_SBT = """#!/bin/bash
printf '%s\\n' "$@" > "$FAKE_STATE/sbt-$PPID.args"
echo $$ > "$FAKE_STATE/sbt-$PPID.pid"
exec "$FAKE_PYTHON" "$FAKE_BIN/fake-sbt-server.py" "$PPID" "$@"
"""

FAKE_SBT_SERVER = """
import json, os, signal, socket, sys, time

stack_pid = sys.argv[1]
state = os.environ["FAKE_STATE"]
mode = os.environ.get("FAKE_SBT_MODE", "")
active_json = os.path.join("project", "target", "active.json")
bsp_connection_file = os.path.join(".bsp", "sbt.json")
uri = f"local://{state}/sbt-{stack_pid}.sock"
# Longer than the script takes between two checks of the server, so that one of them sees the server without
# `.bsp/sbt.json`.
SLOW_BSP_CONFIG_DELAY_S = 1.5


def on_sigterm(signum, frame):
    with open(f"{state}/sbt-{stack_pid}.signal", "w") as signal_file:
        signal_file.write("TERM\\n")
    try:
        with open(active_json) as active_file:
            is_own_server = json.load(active_file).get("uri") == uri
        if is_own_server:
            os.remove(active_json)
    except (OSError, ValueError):
        pass
    sys.exit(143)


def start_server():
    server = socket.socket(socket.AF_UNIX)
    server.bind(uri[len("local://"):])
    server.listen()
    os.makedirs(os.path.dirname(active_json), exist_ok=True)
    with open(active_json, "w") as active_file:
        json.dump({"uri": uri}, active_file)
    if mode == "slow-bsp-config":
        time.sleep(SLOW_BSP_CONFIG_DELAY_S)
    os.makedirs(os.path.dirname(bsp_connection_file), exist_ok=True)
    with open(bsp_connection_file, "w") as bsp_file:
        json.dump({"name": "sbt"}, bsp_file)
    return server


def detach_stdio():
    devnull = os.open(os.devnull, os.O_RDWR)
    for fd in (0, 1, 2):
        os.dup2(devnull, fd)


signal.signal(signal.SIGTERM, on_sigterm)
if mode == "fail":
    time.sleep(1.5)
    print("[error] sbt.internal.ServerAlreadyBootingException", flush=True)
    sys.exit(1)
time.sleep(0.5)
if mode == "late-server":
    print("[warn] sbt server could not start because there's another instance of sbt running on this build.",
          flush=True)
    detach_stdio()
    time.sleep(1)
    server = start_server()
else:
    server = start_server()
    print("[info] started sbt server", flush=True)
    detach_stdio()
if mode == "crash":
    while not os.path.exists(f"{state}/metals-{stack_pid}.pid"):
        time.sleep(0.05)
    sys.exit(1)
while True:
    time.sleep(60)
"""

FAKE_METALS = """#!/bin/bash
printf '%s\\n' "$@" > "$FAKE_STATE/metals-$PPID.args"
if [ -f .bsp/sbt.json ]; then echo found; else echo missing; fi > "$FAKE_STATE/metals-$PPID.bsp-config"
echo $$ > "$FAKE_STATE/metals-$PPID.pid"
"$FAKE_BIN/fake-bsp-client" "$PPID" "$(printf '%0100000d' 0)" &
sleep "${FAKE_METALS_MCP_DELAY_S:-0.2}"
[ -f .mcp.json ] || printf '{"mcpServers": {}}\\n' > .mcp.json
echo "Metals MCP server started on port: 4242. Refresh connection if needed."
wait
"""

FAKE_BSP_CLIENT = """#!/bin/bash
stack_pid="$1"
echo $$ > "$FAKE_STATE/bsp-$stack_pid.pid"
while [ ! -s "$FAKE_STATE/sbt-$stack_pid.pid" ]; do
  sleep 0.05
done
sbt_pid="$(cat "$FAKE_STATE/sbt-$stack_pid.pid")"
while kill -0 "$sbt_pid" 2>/dev/null; do
  sleep 0.05
done
bash -c 'echo $$ > "$FAKE_STATE/stray-$1.pid"; exec sleep 60' stray "$stack_pid" &
"""

SOCKET_HOLDER = """
import socket, sys, time
server = socket.socket(socket.AF_UNIX)
server.bind(sys.argv[1])
server.listen()
time.sleep(60)
"""


def is_alive(pid: int) -> bool:
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    return True


class DevStackTest(unittest.TestCase):

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="mtlist-dev-stack-"))
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        self.addCleanup(self._kill_leftovers)

        self.repo = self.tmp / "repo"
        (self.repo / "bin").mkdir(parents=True)
        self.script = self.repo / "bin" / SCRIPT.name
        shutil.copy2(SCRIPT, self.script)

        self.state = self.tmp / "state"
        self.state.mkdir()
        fake_bin = self.tmp / "fakebin"
        fake_bin.mkdir()
        for name, content in [("sbt", FAKE_SBT), ("fake-sbt-server.py", FAKE_SBT_SERVER),
                              ("metals-standalone-client", FAKE_METALS), ("fake-bsp-client", FAKE_BSP_CLIENT)]:
            path = fake_bin / name
            path.write_text(content)
            path.chmod(0o755)

        self.env = {
            **os.environ,
            "PATH": f"{fake_bin}:/usr/bin:/bin:/usr/sbin:/sbin",
            "FAKE_STATE": str(self.state),
            "FAKE_BIN": str(fake_bin),
            "FAKE_PYTHON": sys.executable,
        }

    # --- helpers ---

    def run_script(self, *args: str, sbt_mode: str = "", metals_mcp_delay_s: str = "") -> subprocess.CompletedProcess:
        env = {**self.env, "FAKE_SBT_MODE": sbt_mode, "FAKE_METALS_MCP_DELAY_S": metals_mcp_delay_s}
        return subprocess.run([str(self.script), *args], cwd=self.repo, env=env, stdin=subprocess.DEVNULL,
                              capture_output=True, text=True, timeout=SCRIPT_TIMEOUT_S)

    def start_foreground(self, sbt_mode: str) -> subprocess.Popen:
        return subprocess.Popen([str(self.script), "start", "--foreground"], cwd=self.repo,
                                env={**self.env, "FAKE_SBT_MODE": sbt_mode}, stdin=subprocess.DEVNULL,
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)

    def start_ready_stack(self) -> int:
        result = self.run_script("start")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        return self.stack_pid()

    def wait_until_ready(self) -> None:
        deadline = time.monotonic() + EVENTUALLY_TIMEOUT_S
        while "Stack is running" not in self.run_script("status").stdout:
            self.assertLess(time.monotonic(), deadline, "the stack never became ready")
            time.sleep(0.1)

    def kill_stack(self, stack_pid: int) -> None:
        """Kills the stack with SIGKILL, so that it can't stop its processes."""
        os.kill(stack_pid, signal.SIGKILL)
        self.assert_exits(stack_pid, "the stack")

    def stack_pid(self) -> int:
        return int((self.repo / "logs" / "mtlist-dev-stack.pid").read_text().strip())

    def fake_record(self, role: str, stack_pid: int, kind: str) -> str:
        """Returns what the fake process with the given role ("sbt", "metals", "bsp", "stray") of a stack recorded in
        the file with the given extension ("pid", "args", "signal", "bsp-config"), once it has."""
        path = self.state / f"{role}-{stack_pid}.{kind}"
        deadline = time.monotonic() + EVENTUALLY_TIMEOUT_S
        while not path.exists() or not path.read_text().strip():
            self.assertLess(time.monotonic(), deadline, f"{path.name} was never written")
            time.sleep(0.05)
        return path.read_text().strip()

    def fake_pid(self, role: str, stack_pid: int) -> int:
        """Returns the PID of the fake process with the given role ("sbt", "metals", "bsp", "stray") of a stack."""
        return int(self.fake_record(role, stack_pid, "pid"))

    def assert_exits(self, pid: int, what: str) -> None:
        deadline = time.monotonic() + EVENTUALLY_TIMEOUT_S
        while is_alive(pid):
            self.assertLess(time.monotonic(), deadline, f"{what} (PID {pid}) is still running")
            time.sleep(0.05)

    def assert_no_stray_server(self, stack_pid: int) -> None:
        path = self.state / f"stray-{stack_pid}.pid"
        deadline = time.monotonic() + NEVER_TIMEOUT_S
        while time.monotonic() < deadline:
            self.assertFalse(path.exists(), "the fake BSP client started a stray sbt server")
            time.sleep(0.05)

    def hold_sbt_server_socket(self) -> int:
        """Starts a process bound to the build's sbt server socket, as `project/target/active.json` records it."""
        sock_path = self.tmp / "sbt.sock"
        holder = subprocess.Popen([sys.executable, "-c", SOCKET_HOLDER, str(sock_path)])
        # Cleanups run last in, first out: kill, then wait.
        self.addCleanup(holder.wait)
        self.addCleanup(holder.kill)
        deadline = time.monotonic() + EVENTUALLY_TIMEOUT_S
        while not sock_path.exists():
            self.assertLess(time.monotonic(), deadline, "the socket holder never bound its socket")
            time.sleep(0.05)
        active_json = self.repo / "project" / "target" / "active.json"
        active_json.parent.mkdir(parents=True, exist_ok=True)
        active_json.write_text(json.dumps({"uri": f"local://{sock_path}"}))
        return holder.pid

    def _kill_leftovers(self) -> None:
        pids = []
        pid_file = self.repo / "logs" / "mtlist-dev-stack.pid"
        if pid_file.exists() and pid_file.read_text().strip():
            pids.append(int(pid_file.read_text().strip()))
        for path in self.state.glob("*.pid"):
            if path.read_text().strip():
                pids.append(int(path.read_text().strip()))
        for pid in pids:
            try:
                os.kill(pid, signal.SIGKILL)
            except ProcessLookupError:
                pass

    # --- tests ---

    def test_foreground_stack_stops_metals_when_sbt_exits_by_itself(self):
        # Given
        stack = self.start_foreground(sbt_mode="crash")

        # When
        output, _ = stack.communicate(timeout=SCRIPT_TIMEOUT_S)

        # Then
        self.assertNotEqual(stack.returncode, 0, output)
        self.assertNotIn("unbound variable", output)
        self.assert_exits(self.fake_pid("metals", stack.pid), "metals-standalone-client")
        self.assert_exits(self.fake_pid("bsp", stack.pid), "Metals' BSP client")
        self.assert_exits(self.fake_pid("stray", stack.pid), "the sbt server started by Metals' BSP client")
        self.assertFalse((self.repo / "logs" / "mtlist-dev-stack.pid").exists())

    def test_foreground_stack_starts_and_stops_its_processes_with_its_output_closed(self):
        # Given: e.g. `start --foreground | head`, or a `| tee` that Ctrl-C kills too
        stack = self.start_foreground(sbt_mode="")
        stack.stdout.close()
        self.wait_until_ready()
        pids = [(self.fake_pid("metals", stack.pid), "metals-standalone-client"),
                (self.fake_pid("bsp", stack.pid), "Metals' BSP client"),
                (self.fake_pid("sbt", stack.pid), "sbt")]

        # When
        stack.terminate()
        stack.wait(timeout=SCRIPT_TIMEOUT_S)

        # Then
        for pid, what in pids:
            self.assert_exits(pid, what)

    def test_foreground_stack_warns_about_an_sbt_server_left_running_when_stopped(self):
        # Given
        stack = self.start_foreground(sbt_mode="")
        self.wait_until_ready()
        holder_pid = self.hold_sbt_server_socket()

        # When
        stack.terminate()
        output, _ = stack.communicate(timeout=SCRIPT_TIMEOUT_S)

        # Then
        self.assertIn(f"sbt server is still running for this project as PID {holder_pid}", output)

    def test_start_waits_until_the_sbt_server_has_started(self):
        # When
        result = self.run_script("start")

        # Then
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("started sbt server", (self.repo / "logs" / "sbt.log").read_text())
        self.assertEqual(self.run_script("status").returncode, 0)

    def test_start_waits_until_sbt_starts_its_server_even_if_its_log_does_not_tell(self):
        # When
        stack = self.start_foreground(sbt_mode="late-server")

        # Then
        self.wait_until_ready()
        stack.terminate()
        stack.communicate(timeout=SCRIPT_TIMEOUT_S)

    def test_start_launches_metals_only_once_sbt_has_written_its_bsp_connection_file(self):
        # Given: no `.bsp/sbt.json`, as in a fresh clone, for which Metals would run an sbt of its own

        # When
        result = self.run_script("start", sbt_mode="slow-bsp-config")

        # Then
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(self.fake_record("metals", self.stack_pid(), "bsp-config"), "found")

    def test_start_fails_when_sbt_exits_while_metals_starts(self):
        # When
        result = self.run_script("start", sbt_mode="crash", metals_mcp_delay_s=str(SLOW_METALS_MCP_DELAY_S))

        # Then
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertNotEqual(self.run_script("status").returncode, 0)

    def test_start_runs_sbt_as_a_server_that_builds_into_target_bsp(self):
        # When
        stack_pid = self.start_ready_stack()

        # Then
        sbt_args = self.fake_record("sbt", stack_pid, "args").splitlines()
        self.assertIn("--detach-stdio", sbt_args)
        self.assertIn("-Dmicrotonalist.build.targetSuffix=-bsp", sbt_args)

    def test_start_makes_metals_use_the_sbt_server_as_its_build_server(self):
        # When
        stack_pid = self.start_ready_stack()

        # Then: Metals builds through sbt, not Bloop
        metals_args = self.fake_record("metals", stack_pid, "args").splitlines()
        metals_jvm_args = metals_args[metals_args.index("--") + 1:]
        self.assertIn("-Dmetals.defaultBspToBuildTool=true", metals_jvm_args)

    def test_running_stack_keeps_no_fifo_for_the_input_of_sbt(self):
        # When
        self.start_ready_stack()

        # Then
        fifos = [path.name for path in (self.repo / "logs").iterdir() if path.is_fifo()]
        self.assertEqual(fifos, [])

    def test_start_waits_until_metals_mcp_server_has_started_and_keeps_an_existing_mcp_json(self):
        # Given: a .mcp.json left by an earlier stack, whose port Metals reuses
        mcp_json = '{"mcpServers": {"metals": {"type": "http", "url": "http://localhost:4242/mcp"}}}\n'
        (self.repo / ".mcp.json").write_text(mcp_json)

        # When
        result = self.run_script("start", metals_mcp_delay_s=str(SLOW_METALS_MCP_DELAY_S))

        # Then
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("Metals MCP server started", (self.repo / "logs" / "metals-standalone-client.log").read_text())
        self.assertEqual((self.repo / ".mcp.json").read_text(), mcp_json)

    def test_start_stops_the_processes_left_by_a_stack_killed_without_cleanup(self):
        # Given
        old_stack_pid = self.start_ready_stack()
        old_pids = [(self.fake_pid("sbt", old_stack_pid), "the old sbt"),
                    (self.fake_pid("metals", old_stack_pid), "the old metals-standalone-client"),
                    (self.fake_pid("bsp", old_stack_pid), "the old Metals' BSP client")]
        self.kill_stack(old_stack_pid)

        # When
        result = self.run_script("start")

        # Then
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assert_no_stray_server(old_stack_pid)
        for pid, what in old_pids:
            self.assertFalse(is_alive(pid), f"{what} (PID {pid}) is still running")
        self.assertEqual(self.run_script("status").returncode, 0)

    def test_start_refuses_while_an_sbt_server_runs_and_says_to_stop_metals_first(self):
        # Given
        holder_pid = self.hold_sbt_server_socket()

        # When
        result = self.run_script("start")

        # Then
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(f"kill {holder_pid}", result.stderr)
        self.assertIn("Stop any other Metals for this project first", result.stderr)

    def test_start_fails_when_the_stack_shuts_down_during_startup(self):
        # When
        result = self.run_script("start", sbt_mode="fail")

        # Then
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertNotEqual(self.run_script("status").returncode, 0)

    def test_stop_stops_metals_and_its_children_before_sbt(self):
        # Given
        stack_pid = self.start_ready_stack()
        sbt_pid = self.fake_pid("sbt", stack_pid)
        metals_pid = self.fake_pid("metals", stack_pid)
        bsp_pid = self.fake_pid("bsp", stack_pid)

        # When
        result = self.run_script("stop")

        # Then
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assert_no_stray_server(stack_pid)
        for pid, what in [(sbt_pid, "sbt"), (metals_pid, "metals-standalone-client"), (bsp_pid, "Metals' BSP client")]:
            self.assertFalse(is_alive(pid), f"{what} (PID {pid}) is still running")
        self.assertNotEqual(self.run_script("status").returncode, 0)

    def test_stop_stops_sbt_with_sigterm(self):
        # Given
        stack_pid = self.start_ready_stack()

        # When
        result = self.run_script("stop")

        # Then
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(self.fake_record("sbt", stack_pid, "signal"), "TERM")

    def test_stop_warns_about_an_sbt_server_left_running(self):
        # Given
        self.start_ready_stack()
        holder_pid = self.hold_sbt_server_socket()

        # When
        result = self.run_script("stop")

        # Then
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(f"sbt server is still running for this project as PID {holder_pid}", result.stderr)
        self.assertIn("Stop any other Metals for this project first", result.stderr)

    def test_stop_stops_the_processes_left_by_a_stack_killed_without_cleanup(self):
        # Given
        stack_pid = self.start_ready_stack()
        pids = [(self.fake_pid("sbt", stack_pid), "sbt"),
                (self.fake_pid("metals", stack_pid), "metals-standalone-client"),
                (self.fake_pid("bsp", stack_pid), "Metals' BSP client")]
        self.kill_stack(stack_pid)

        # When
        result = self.run_script("stop")

        # Then
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assert_no_stray_server(stack_pid)
        for pid, what in pids:
            self.assertFalse(is_alive(pid), f"{what} (PID {pid}) is still running")

    def test_restart_restarts_the_stack_without_a_stray_server(self):
        # Given
        old_stack_pid = self.start_ready_stack()
        old_metals_pid = self.fake_pid("metals", old_stack_pid)
        old_bsp_pid = self.fake_pid("bsp", old_stack_pid)

        # When
        result = self.run_script("restart")

        # Then
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assert_no_stray_server(old_stack_pid)
        self.assertFalse(is_alive(old_metals_pid), "the old metals-standalone-client is still running")
        self.assertFalse(is_alive(old_bsp_pid), "the old Metals' BSP client is still running")
        self.assertNotEqual(self.stack_pid(), old_stack_pid)
        self.assertEqual(self.run_script("status").returncode, 0)

    def test_restart_fails_when_the_new_stack_shuts_down(self):
        # Given
        self.start_ready_stack()

        # When
        result = self.run_script("restart", sbt_mode="fail")

        # Then
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertNotEqual(self.run_script("status").returncode, 0)


if __name__ == "__main__":
    unittest.main()
