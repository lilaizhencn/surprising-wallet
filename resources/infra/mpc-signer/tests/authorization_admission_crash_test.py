#!/usr/bin/env python3
"""Actual process crash after durable admission must never masquerade as no accepted authorization."""
import concurrent.futures
import os
import pathlib
import sys
import tempfile
import time
import uuid
from node_test import Cluster, rejected, require

os.umask(0o077)
with tempfile.TemporaryDirectory(prefix="sw-approval-admission-crash-") as directory:
    os.environ["WALLET_MPC_TEST_EXIT_AFTER_ADMISSION"] = "1"
    cluster = Cluster(pathlib.Path(directory), pathlib.Path(sys.argv[2]))
    try:
        create = cluster.descriptor("ECDSA_SECP256K1", str(uuid.uuid4()), "CREATE_KEY", [0, 1, 2])
        cluster.run(create, [0, 1, 2])
        signing = dict(create, sessionId=str(uuid.uuid4()), operation="SIGN", online=cluster.identities[:2], messageHex="69" * 32)
        expires = int(time.time()) + 5
        proof = cluster.signing_authorization(signing, expiresAt=expires)
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
            futures = [executor.submit(lambda i=i: rejected(lambda: cluster.request(i, "/v1/sessions", dict(signing, authorization=proof)))) for i in (0, 1)]
            for future in futures: future.result()
        for i in (0, 1):
            require(cluster.processes[i].wait(timeout=5) == 86, "test did not crash at the exact admission persistence boundary")
        os.environ.pop("WALLET_MPC_TEST_EXIT_AFTER_ADMISSION", None)
        cluster.binary = pathlib.Path(sys.argv[1]).with_name("wallet_mpc_fixture_node")
        while time.time() <= expires: time.sleep(0.05)
        for i in (0, 1):
            cluster.stop(i); cluster.start(i)
            require(cluster.request(i, "/v1/results", signing)["state"] == "INTERRUPTED", "durable admission was incorrectly reported ABSENT after crash")
        result = cluster.run(dict(signing, authorization=proof), [0, 1])
        require(all(row["state"] == "COMPLETED" for row in result), "the exact admitted original session could not resume after proof expiry")
        print("PASS crash between admission/grant/request persistence: never ABSENT; exact original expired proof resumes once")
    finally:
        os.environ.pop("WALLET_MPC_TEST_EXIT_AFTER_ADMISSION", None)
        cluster.close()
