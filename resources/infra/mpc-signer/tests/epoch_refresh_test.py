#!/usr/bin/env python3
"""Actual cb-mpc network refresh, durable readiness recovery, and old-epoch fencing."""
import hashlib
import json
import os
import pathlib
import sys
import tempfile
import time
import uuid
from node_test import Cluster, require, rejected, verify_signature


def record(cluster, index, prefix, key, epoch):
    return cluster.root / f"node{index}-store" / f"{prefix}{cluster.tenant}-{key}-{epoch}"


def share_hashes(cluster, key, epoch):
    return [hashlib.sha256(record(cluster, i, "share-", key, epoch).read_bytes()).hexdigest() for i in range(3)]


def signatures(cluster, algorithm, key, epoch, public):
    witnesses = []
    for pair in ([0, 1], [0, 2], [1, 2]):
        absent = next(i for i in range(3) if i not in pair)
        cluster.stop(absent)
        try:
            descriptor = cluster.descriptor(algorithm, key, "SIGN", pair, os.urandom(32).hex())
            descriptor["epoch"] = epoch
            result = cluster.run(descriptor, pair)
            require(result[0]["publicKeyHex"] == public and result[1]["publicKeyHex"] == public, "refresh changed the joint key")
            verify_signature(cluster.root, algorithm, public, descriptor["messageHex"], result[0]["signatureHex"])
            witnesses.append({"epoch": epoch, "pair": pair, "messageHex": descriptor["messageHex"], "signatureHex": result[0]["signatureHex"]})
            time.sleep(0.05)
        finally:
            cluster.start(absent)
    return witnesses


def reject_old(cluster, algorithm, key, epoch):
    for i in range(3):
        descriptor = cluster.descriptor(algorithm, key, "SIGN", [i, (i + 1) % 3], os.urandom(32).hex())
        descriptor["epoch"] = epoch
        require(cluster.request(i, "/health")["status"] == "UP", "old-epoch rejection must come from a live holder")
        rejected(lambda: cluster.request(i, "/v1/sessions", descriptor))
        require(not (cluster.root / f"node{i}-store" / ("request-" + descriptor["sessionId"])).exists(), "rejected old signature created a receipt")


def curve(cluster, algorithm):
    key = str(uuid.uuid4())
    creation = cluster.descriptor(algorithm, key, "CREATE_KEY", [0, 1, 2])
    created = cluster.run(creation, [0, 1, 2])
    public = created[0]["publicKeyHex"]
    require(all(item["publicKeyHex"] == public for item in created), "DKG public keys disagree")
    original = share_hashes(cluster, key, 1)
    witnesses = signatures(cluster, algorithm, key, 1, public)
    refresh = cluster.descriptor(algorithm, key, "REFRESH", [0, 1, 2])
    refresh["epoch"] = 2
    for changed in (dict(refresh, epoch=1), dict(refresh, epoch=2.5), dict(refresh, epoch=4294967298),
                    dict(refresh, online=refresh["online"][:2]), dict(refresh, messageHex="00")):
        rejected(lambda: cluster.request(0, "/v1/sessions", changed))
    rejected(lambda: cluster.request(0, "/v1/refresh-prepared", refresh))
    results = cluster.run(refresh, [0, 1, 2])
    require(all(item["epoch"] == 2 and item["publicKeyHex"] == public for item in results), "network refresh did not bind the target version")
    refreshed = share_hashes(cluster, key, 2)
    require(original == share_hashes(cluster, key, 1) and all(a != b for a, b in zip(original, refreshed)), "refresh overwrote or reused an old record")
    reject_old(cluster, algorithm, key, 1)

    # Explicit disk-boundary fault: retain every original request/share/metadata/fence,
    # remove readiness/completion receipts in this isolated fixture, and restart all holders.
    # Recovery may repeat the public barrier; it must never repeat random share refresh.
    for i in range(3):
        cluster.stop(i)
        record(cluster, i, "ready-", key, 2).unlink()
        (cluster.root / f"node{i}-store" / ("result-" + refresh["sessionId"])).unlink()
    for i in range(3):
        cluster.start(i)
        require(cluster.request(i, "/v1/results", refresh)["state"] == "INTERRUPTED", "missing readiness must not imply completion")
        premature = cluster.descriptor(algorithm, key, "SIGN", [i, (i + 1) % 3], os.urandom(32).hex())
        premature["epoch"] = 2
        rejected(lambda: cluster.request(i, "/v1/sessions", premature))
    recovered = cluster.run(refresh, [0, 1, 2])
    require(recovered == results, "original refresh result changed after readiness recovery")
    require(share_hashes(cluster, key, 2) == refreshed and share_hashes(cluster, key, 1) == original, "readiness recovery reran private refresh")
    witnesses.extend(signatures(cluster, algorithm, key, 2, public))

    following = cluster.descriptor(algorithm, key, "REFRESH", [0, 1, 2])
    following["epoch"] = 3
    require(all(item["publicKeyHex"] == public and item["epoch"] == 3 for item in cluster.run(following, [0, 1, 2])), "second refresh failed")
    require(share_hashes(cluster, key, 2) == refreshed, "second refresh overwrote the preceding epoch")
    reject_old(cluster, algorithm, key, 2)
    witnesses.extend(signatures(cluster, algorithm, key, 3, public))
    return {"algorithm": algorithm, "publicKeyHex": public, "witnesses": witnesses,
            "oldEpochRejected": True, "originalSharesPreserved": True, "readinessRecoveryWithoutNewShares": True}


def interrupted_protocol(cluster):
    key = str(uuid.uuid4())
    creation = cluster.descriptor("ED25519", key, "CREATE_KEY", [0, 1, 2])
    cluster.run(creation, [0, 1, 2])
    descriptor = cluster.descriptor("ED25519", key, "REFRESH", [0, 1, 2])
    descriptor["epoch"] = 2
    # The third holder never joins: the first two cannot finish random refresh.
    for i in [0, 1]:
        require(cluster.request(i, "/v1/sessions", descriptor)["state"] == "RUNNING", "partial refresh was not accepted")
    for i in [0, 1]:
        cluster.stop(i)
        require(not record(cluster, i, "key-", key, 2).exists(), "partial refresh unexpectedly completed")
        cluster.start(i)
        require(cluster.request(i, "/v1/sessions", descriptor)["state"] == "INTERRUPTED", "missing private protocol state must remain interrupted")
        old = cluster.descriptor("ED25519", key, "SIGN", [0, 1], "00")
        rejected(lambda: cluster.request(i, "/v1/sessions", old))
        conflicting = dict(descriptor, sessionId=str(uuid.uuid4()))
        rejected(lambda: cluster.request(i, "/v1/sessions", conflicting))
    return True


def main():
    require(len(sys.argv) == 2, "usage: epoch_refresh_test.py /path/to/wallet-mpc-signer")
    os.umask(0o077)
    with tempfile.TemporaryDirectory(prefix="surprising-wallet-mpc-refresh-") as temporary:
        cluster = Cluster(pathlib.Path(temporary), str(pathlib.Path(sys.argv[1]).resolve(strict=True)))
        try:
            curves = []
            for algorithm in ["ECDSA_SECP256K1", "ED25519", "SCHNORR_BIP340"]:
                curves.append(curve(cluster, algorithm))
                print(algorithm + ": two actual network refreshes, three epochs/all quorums, original readiness recovery and old-version rejection passed.", flush=True)
            require(interrupted_protocol(cluster), "interrupted protocol safety was not checked")
            output = pathlib.Path(os.environ.get("MPC_REFRESH_PUBLIC_PROOF", "/tmp/surprising-wallet-mpc-refresh-public.json"))
            output.write_text(json.dumps({"passed": True, "curves": curves, "signatures": 27, "interruptedProtocolHeld": True}))
            print("Native refresh passed:3 algorithms,6 network refreshes,27 independently verified signatures; incomplete private protocol remains fenced.", flush=True)
        finally:
            cluster.close()


if __name__ == "__main__":
    main()
