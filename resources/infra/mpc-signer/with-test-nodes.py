#!/usr/bin/env python3
"""Run a local chain acceptance command with three isolated, actual MPC processes."""
import json
import os
import pathlib
import signal
import subprocess
import sys
import tempfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent / "tests"))
from node_test import Cluster


def interrupted(*_):
    raise KeyboardInterrupt()


def main():
    if len(sys.argv) < 3:
        raise SystemExit("usage: with-test-nodes.py /path/to/wallet-mpc-signer command [args...]")
    os.umask(0o077)
    binary = str(pathlib.Path(sys.argv[1]).resolve(strict=True))
    signal.signal(signal.SIGTERM, interrupted)
    with tempfile.TemporaryDirectory(prefix="surprising-wallet-mpc-chain-test-") as temporary:
        root = pathlib.Path(temporary)
        cluster = Cluster(root, binary, "00000000-0000-0000-0000-000000000001")
        child = None
        try:
            (root / "java-probe.json").write_text(json.dumps({"tenantId": cluster.tenant, "peers": cluster.peers}))
            env = os.environ.copy()
            env["SW_TEST_MPC_DIRECTORY"] = str(root)
            child = subprocess.Popen(sys.argv[2:], env=env)
            return child.wait()
        finally:
            if child and child.poll() is None:
                child.terminate()
                try:
                    child.wait(timeout=30)
                except subprocess.TimeoutExpired:
                    child.kill()
                    child.wait(timeout=5)
            cluster.close()


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        raise SystemExit(130)
