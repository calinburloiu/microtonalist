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

- `sbt` announces its server after a moment, then reads commands until `exit`. `FAKE_SBT_MODE=fail` makes it exit with
  an error instead, after the stack has sent it the warm-up `compile`, like sbt does when another server holds the
  build's socket. `FAKE_SBT_MODE=close-input` makes it stop reading its input before it announces its server, then
  exit.
- `metals-standalone-client` starts a child standing in for the Metals server and its BSP client, then writes
  `.mcp.json`. Like the real BSP client, the child starts a stray sbt server as soon as the stack's sbt goes away.

The fakes record their PIDs under the state directory, in files named after the PID of the stack that started them.
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

FAKE_SBT = """#!/bin/bash
echo $$ > "$FAKE_STATE/sbt-$PPID.pid"
case "${FAKE_SBT_MODE:-}" in
  fail)
    sleep 1.5
    echo "[error] sbt.internal.ServerAlreadyBootingException"
    exit 1
    ;;
  close-input)
    exec 0<&-
    echo "[info] started sbt server"
    sleep 1
    exit 1
    ;;
esac
sleep 0.5
echo "[info] started sbt server"
while read -r command; do
  if [ "$command" = exit ]; then
    exit 0
  fi
done
"""

FAKE_METALS = """#!/bin/bash
echo $$ > "$FAKE_STATE/metals-$PPID.pid"
"$FAKE_BIN/fake-bsp-client" "$PPID" &
sleep 0.2
printf '{"mcpServers": {}}\\n' > .mcp.json
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
        for name, content in [("sbt", FAKE_SBT), ("metals-standalone-client", FAKE_METALS),
                              ("fake-bsp-client", FAKE_BSP_CLIENT)]:
            path = fake_bin / name
            path.write_text(content)
            path.chmod(0o755)

        self.env = {
            **os.environ,
            "PATH": f"{fake_bin}:/usr/bin:/bin:/usr/sbin:/sbin",
            "FAKE_STATE": str(self.state),
            "FAKE_BIN": str(fake_bin),
        }

    # --- helpers ---

    def run_script(self, *args: str, sbt_mode: str = "") -> subprocess.CompletedProcess:
        return subprocess.run([str(self.script), *args], cwd=self.repo, env={**self.env, "FAKE_SBT_MODE": sbt_mode},
                              stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=SCRIPT_TIMEOUT_S)

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

    def stack_pid(self) -> int:
        return int((self.repo / "logs" / "mtlist-dev-stack.pid").read_text().strip())

    def fake_pid(self, role: str, stack_pid: int) -> int:
        """Returns the PID of the fake process with the given role ("sbt", "metals", "bsp", "stray") of a stack."""
        path = self.state / f"{role}-{stack_pid}.pid"
        deadline = time.monotonic() + EVENTUALLY_TIMEOUT_S
        while not path.exists() or not path.read_text().strip():
            self.assertLess(time.monotonic(), deadline, f"{path.name} was never written")
            time.sleep(0.05)
        return int(path.read_text().strip())

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
        active_json.parent.mkdir(parents=True)
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
        stack = self.start_foreground(sbt_mode="fail")

        # When
        output, _ = stack.communicate(timeout=SCRIPT_TIMEOUT_S)

        # Then
        self.assertNotEqual(stack.returncode, 0, output)
        self.assertNotIn("unbound variable", output)
        self.assert_exits(self.fake_pid("metals", stack.pid), "metals-standalone-client")
        self.assert_exits(self.fake_pid("bsp", stack.pid), "Metals' BSP client")
        self.assert_exits(self.fake_pid("stray", stack.pid), "the sbt server started by Metals' BSP client")
        self.assertFalse((self.repo / "logs" / "mtlist-dev-stack.pid").exists())
        self.assertFalse((self.repo / "logs" / ".sbt-stdin.fifo").exists())

    def test_foreground_stack_stops_metals_when_sbt_stops_reading_its_input(self):
        # Given
        stack = self.start_foreground(sbt_mode="close-input")

        # When
        output, _ = stack.communicate(timeout=SCRIPT_TIMEOUT_S)

        # Then
        self.assertNotEqual(stack.returncode, 0, output)
        self.assert_exits(self.fake_pid("metals", stack.pid), "metals-standalone-client")
        self.assert_exits(self.fake_pid("bsp", stack.pid), "Metals' BSP client")
        self.assertFalse((self.repo / "logs" / "mtlist-dev-stack.pid").exists())

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

    def test_stop_warns_about_an_sbt_server_left_running(self):
        # Given
        self.start_ready_stack()
        holder_pid = self.hold_sbt_server_socket()

        # When
        result = self.run_script("stop")

        # Then
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(f"sbt server is still running for this project as PID {holder_pid}", result.stderr)

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
