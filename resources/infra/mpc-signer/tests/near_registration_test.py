#!/usr/bin/env python3
"""Real formal NEP-145 registration/refund and following payments with each absent third MPC holder."""
import hashlib
import json
import os
import pathlib
import signal
import subprocess
import sys
import tempfile
import time
from node_test import Cluster, require


def material(root, pair):
    return {str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest()
            for index in pair for path in (root / f"node{index}-store").rglob("*") if path.is_file()}


def interrupted(*_):
    raise KeyboardInterrupt()


def main():
    require(len(sys.argv) == 2, "usage: near_registration_test.py /path/to/wallet-mpc-signer")
    os.umask(0o077)
    binary = str(pathlib.Path(sys.argv[1]).resolve(strict=True))
    classpath = os.environ.get("MPC_WALLET_TEST_CLASSPATH")
    require(classpath and os.environ.get("MPC_NEAR_TEST_FIXTURE"), "fresh Java classpath and protected NEAR fixture are required")
    signal.signal(signal.SIGTERM, interrupted)
    proofs = []
    for label, pair in (("AB", [0, 1]), ("AC", [0, 2]), ("BC", [1, 2])):
        with tempfile.TemporaryDirectory(prefix=f"surprising-wallet-mpc-near-storage-{label.lower()}-") as temporary:
            root = pathlib.Path(temporary)
            cluster = Cluster(root, binary, "00000000-0000-0000-0000-000000000001")
            child = None
            try:
                absent = next(index for index in range(3) if index not in pair)
                acknowledgement = {"pair": pair, "absent": absent}
                (root / "java-probe.json").write_text(json.dumps({"tenantId": cluster.tenant, "peers": cluster.peers, "pair": pair}))
                env = os.environ.copy()
                env["SW_TEST_MPC_DIRECTORY"] = str(root)
                child = subprocess.Popen(["java", "-cp", classpath, "com.surprising.wallet.custody.MpcNearRegistrationNativeProbe", str(root)], env=env)
                deadline = time.monotonic() + 360
                stopped = restarted = False
                while child.poll() is None and time.monotonic() < deadline:
                    if not stopped and (root / "near-storage-keys-ready").is_file():
                        process = cluster.processes[absent]
                        cluster.stop(absent)
                        require(process.poll() is not None, "third native holder remains alive")
                        stopped = True
                        (root / "near-storage-third-stopped").write_text(json.dumps(acknowledgement))
                        print(f"NEAR storage {label}: real DKG complete; third holder stopped.", flush=True)
                    if not restarted and (root / "near-storage-restart-ready").is_file():
                        require(stopped and absent not in cluster.processes, "recovery requires the third holder to remain absent")
                        before = material(root, pair)
                        require(before, "original native material is absent")
                        for index in pair:
                            cluster.stop(index)
                        for index in pair:
                            cluster.start(index)
                        require(material(root, pair) == before, "native restart changed original material")
                        restarted = True
                        (root / "near-storage-restarted").write_text(json.dumps(acknowledgement))
                        print(f"NEAR storage {label}: original holders restarted with unchanged material.", flush=True)
                    require(all(process.poll() is None for process in cluster.processes.values()), "an active native holder exited")
                    time.sleep(0.1)
                require(child.poll() is not None, "NEAR storage formal acceptance timed out")
                require(child.returncode == 0, f"NEAR storage {label} formal acceptance failed")
                require(stopped and restarted, "actual absent-third/restart scenarios did not execute")
                proof = json.loads((root / "near-storage-public.json").read_text())
                require(proof["passed"] is True and proof["pair"] == pair, "NEAR storage proof belongs to another quorum")
                require(len(proof["transactions"]) == 7 and len(proof["registrationProofs"]) == 4, "registration/payment matrix is incomplete")
                require(sum(item["kind"] == "REGISTRATION" for item in proof["transactions"]) == 4, "registration count differs")
                require(sum(item["status"] == "FAILED" for item in proof["transactions"]) == 1, "actual failed registration refund is absent")
                require(all(proof.get(name) is True for name in ("lostAcceptedReplyRecovered", "disabledRecovery", "principalRefundOnce", "gasLedgerEqualsPhysical", "internalRescanNoCredit")),
                        "original recovery or exact money verification is missing")
                proof["quorum"] = label
                proofs.append(proof)
                print(f"NEAR storage {label}: four actual registrations and three following Token payments passed.", flush=True)
            finally:
                if child and child.poll() is None:
                    child.terminate()
                    try:
                        child.wait(timeout=20)
                    except subprocess.TimeoutExpired:
                        child.kill()
                        child.wait(timeout=5)
                cluster.close()
    hashes = [item["hash"] for proof in proofs for item in proof["transactions"]]
    require(len(hashes) == len(set(hashes)) == 21, "NEAR storage matrix reused an unrelated transaction")
    output = pathlib.Path(os.environ.get("MPC_NEAR_STORAGE_PUBLIC_PROOF", "/tmp/surprising-wallet-mpc-near-storage-public.json"))
    output.write_text(json.dumps({"passed": True, "quorums": ["AB", "AC", "BC"], "transactions": 21, "proofs": proofs}))
    print("NEAR actual MPC storage AB/AC/BC passed:12 registrations,9 Token payments,actual refunds,original recovery and exact Gas/principal accounting.", flush=True)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        raise SystemExit(130)
