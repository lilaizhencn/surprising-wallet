#!/usr/bin/env python3
"""Verify the actual Java authorization bytes and Ed25519 proof at independent native holders."""
import json
import os
import pathlib
import subprocess
import sys
import tempfile
import uuid
from node_test import Cluster, rejected, require

os.umask(0o077)
classpath = os.environ["MPC_AUTHORIZATION_JAVA_CP"]
with tempfile.TemporaryDirectory(prefix="sw-approval-java-native-") as directory:
    root = pathlib.Path(directory)
    cluster = Cluster(root, pathlib.Path(sys.argv[1]))
    try:
        create = cluster.descriptor("ECDSA_SECP256K1", str(uuid.uuid4()), "CREATE_KEY", [0, 1, 2], "")
        cluster.run(create, [0, 1, 2])
        signing = dict(create, sessionId=str(uuid.uuid4()), operation="SIGN", online=cluster.identities[1:], messageHex="42" * 32)
        request_file = root / "request.json"; request_file.write_text(json.dumps(signing))
        result = subprocess.run(["java", "-cp", classpath, "com.surprising.wallet.common.approval.SigningAuthorizationTest", str(request_file)],
                                check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        authority = json.loads(result.stdout)
        for i in range(3):
            cluster.stop(i)
            config_file = root / f"node{i}.json"
            config = json.loads(config_file.read_text()); config["authorizationPublicKeys"] = [authority["publicKeyHex"]]
            config_file.write_text(json.dumps(config)); cluster.start(i)
        results = cluster.run(dict(signing, authorization=authority["authorization"]), [1, 2])
        require(all(result["state"] == "COMPLETED" for result in results), "Java proof was not accepted by both native holders")
        rejected(lambda: cluster.request(1, "/v1/sessions", signing))  # Fixture OpenSSL issuer is no longer trusted.
        print("PASS Java authorization canonical bytes and Ed25519 proof accepted by independent native holders B+C")
    finally:
        cluster.close()
