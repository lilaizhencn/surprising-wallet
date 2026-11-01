#!/usr/bin/env python3
"""Formal wallet EIP-7702 batches on three actual MPC quorum pairs and isolated Prague nodes."""
import hashlib
import json
import os
import pathlib
import signal
import subprocess
import sys
import tempfile
import time
import uuid

from node_test import Cluster, require


def java_name_uuid(value):
    return str(uuid.UUID(bytes=hashlib.md5(value.encode()).digest(), version=3))


def stored_material(root, pair):
    return {str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest()
            for index in pair for path in (root / f"node{index}-store").rglob("*") if path.is_file()}


def interrupted(*_):
    raise KeyboardInterrupt()


def main():
    require(len(sys.argv) >= 3, "usage: evm7702_business_test.py /path/to/wallet-mpc-signer command [args...]")
    os.umask(0o077)
    binary = str(pathlib.Path(sys.argv[1]).resolve(strict=True))
    signal.signal(signal.SIGTERM, interrupted)
    tenants = [java_name_uuid(name) for name in ("tenant-7702-a", "tenant-7702-b")]
    proofs = []
    for label, pair in (("AB", [0, 1]), ("AC", [0, 2]), ("BC", [1, 2])):
        with tempfile.TemporaryDirectory(prefix=f"surprising-wallet-mpc-7702-business-{label.lower()}-") as temporary:
            root = pathlib.Path(temporary)
            cluster = Cluster(root, binary, "00000000-0000-0000-0000-000000000001", extra_tenants=tenants)
            child = None
            try:
                absent = next(index for index in range(3) if index not in pair)
                acknowledgement = {"pair": pair, "absent": absent}
                (root / "java-probe.json").write_text(json.dumps({"tenantId": cluster.tenant, "peers": cluster.peers, "pair": pair}))
                env = os.environ.copy()
                env["SW_TEST_MPC_DIRECTORY"] = str(root)
                child = subprocess.Popen(sys.argv[2:], env=env, start_new_session=True)
                deadline = time.monotonic() + 900
                stopped = False
                restarted = set()
                while child.poll() is None and time.monotonic() < deadline:
                    if not stopped and (root / "7702-keys-ready").is_file():
                        process = cluster.processes[absent]
                        cluster.stop(absent)
                        require(process.poll() is not None, "third MPC process did not terminate")
                        stopped = True
                        (root / "7702-third-stopped").write_text(json.dumps(acknowledgement))
                        print(f"EIP-7702 {label}: all DKG complete; third native process stopped.", flush=True)
                    for phase in ("collection", "payout"):
                        if phase not in restarted and (root / f"7702-{phase}-restart-ready").is_file():
                            require(stopped and absent not in cluster.processes, "recovery requires the third node to remain stopped")
                            before = stored_material(root, pair)
                            require(before, "original native MPC material is missing")
                            for index in pair:
                                cluster.stop(index)
                            for index in pair:
                                cluster.start(index)
                            require(stored_material(root, pair) == before, "native restart replaced original durable material")
                            restarted.add(phase)
                            (root / f"7702-{phase}-restarted").write_text(json.dumps(acknowledgement))
                            print(f"EIP-7702 {label}: both original holders restarted for {phase}; material unchanged.", flush=True)
                    require(all(process.poll() is None for process in cluster.processes.values()), "an active native MPC node exited")
                    time.sleep(0.1)
                require(child.poll() is not None, f"EIP-7702 {label} business acceptance timed out")
                require(child.returncode == 0, f"EIP-7702 {label} Maven/business acceptance failed")
                require(stopped and restarted == {"collection", "payout"}, "native stop/restart scenarios were not executed")
                proof = json.loads((root / "7702-business-public.json").read_text())
                require(proof["passed"] is True and proof["pair"] == pair, "business proof belongs to another quorum")
                require(len(proof["transactions"]) == 12 and proof["principalSettledOrders"] == 10, "business matrix is incomplete")
                require(proof["lostResponses"] == proof["holderRestarts"] == 2 and proof["disabledEntryRecovery"] is True,
                        "original unknown-broadcast recovery was not verified")
                require(all(proof.get(name) is True for name in ("internalRescanNoCredit", "ownedIncomingCreditOnce", "completedReplayUnchanged")),
                        "internal/owned transfers or completed-operation replay accounting is unverified")
                require({item["type"] for item in proof["transactions"]} == {2, 4}, "both activation and delegated reuse must reach consensus")
                require(int(proof["relayerFundedAtomic"]) == int(proof["relayerRemainingAtomic"]) + int(proof["feeAtomic"]),
                        "operator physical fee conservation failed")
                proof["quorum"] = label
                proofs.append(proof)
            finally:
                if child and child.poll() is None:
                    os.killpg(child.pid, signal.SIGTERM)
                    try:
                        child.wait(timeout=30)
                    except subprocess.TimeoutExpired:
                        os.killpg(child.pid, signal.SIGKILL)
                        child.wait(timeout=5)
                cluster.close()
    hashes = [item["transactionHash"] for proof in proofs for item in proof["transactions"]]
    require(len(set(hashes)) == len(hashes) == 36, "business transactions were duplicated across quorum fixtures")
    output = pathlib.Path(os.environ.get("MPC_EVM_7702_BUSINESS_PROOF", "/tmp/surprising-wallet-mpc-7702-business-public.json"))
    output.write_text(json.dumps({"passed": True, "quorums": ["AB", "AC", "BC"], "transactions": 36, "proofs": proofs}))
    print("EIP-7702 actual MPC formal business AB/AC/BC passed:36 transactions,30 settled orders,6 lost responses and holder restarts.", flush=True)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        raise SystemExit(130)
