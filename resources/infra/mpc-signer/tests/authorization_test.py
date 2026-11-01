#!/usr/bin/env python3
"""Every native holder must reject Java-only permission and changed/expired approval proofs."""
import json
import pathlib
import sys
import tempfile
import time
import uuid
from node_test import Cluster, rejected, require

with tempfile.TemporaryDirectory(prefix="sw-mpc-approval-") as directory:
    root = pathlib.Path(directory)
    cluster = Cluster(root, pathlib.Path(sys.argv[1]))
    try:
        key = str(uuid.uuid4())
        create = {"sessionId": str(uuid.uuid4()), "tenantId": cluster.tenant, "keyId": key, "epoch": 1,
                  "operation": "CREATE_KEY", "algorithm": "ECDSA_SECP256K1", "online": cluster.identities, "messageHex": ""}
        cluster.run(create, [0, 1, 2])
        signing = dict(create, sessionId=str(uuid.uuid4()), operation="SIGN", online=cluster.identities[:2], messageHex="12" * 32)
        proof = cluster.signing_authorization(signing)
        for i in (0, 1):
            rejected(lambda: cluster.request(i, "/v1/sessions", signing, authorize=False))
            wrong = dict(proof, signatureHex="00" * 64)
            rejected(lambda: cluster.request(i, "/v1/sessions", dict(signing, authorization=wrong), authorize=False))
            for field, value in (("sessionId", str(uuid.uuid4())), ("messageHex", "34" * 32), ("keyId", str(uuid.uuid4())),
                                 ("epoch", 2), ("online", list(reversed(signing["online"]))), ("tenantId", str(uuid.uuid4()))):
                rejected(lambda: cluster.request(i, "/v1/sessions", dict(signing, **{field: value}, authorization=proof), authorize=False))
            expired = cluster.signing_authorization(signing, issuedAt=int(time.time()) - 100, expiresAt=int(time.time()) - 1)
            rejected(lambda: cluster.request(i, "/v1/sessions", dict(signing, authorization=expired), authorize=False))
        preparation = dict(signing, sessionId=str(uuid.uuid4()), messageHex="56" * 32)
        preparation_proof = cluster.signing_authorization(preparation, purpose="ADDRESS_PREPARATION", chain="XRP")
        preparation_result = cluster.run(dict(preparation, authorization=preparation_proof), [0, 1])
        require(all(row["state"] == "COMPLETED" for row in preparation_result), "explicit XRP address preparation purpose was rejected")
        unknown = dict(signing, sessionId=str(uuid.uuid4()))
        unknown_proof = cluster.signing_authorization(unknown, purpose="ATTACKER_PURPOSE")
        for i in (0, 1):
            rejected(lambda: cluster.request(i, "/v1/sessions", dict(unknown, authorization=unknown_proof), authorize=False))
        proof = cluster.signing_authorization(signing, expiresAt=int(time.time()) + 3)
        result = cluster.run(dict(signing, authorization=proof), [0, 1])
        require(result[0]["state"] == "COMPLETED", "valid independently authorized signature did not complete")
        claims = json.loads(bytes.fromhex(proof["payloadHex"]))
        reused = dict(signing, sessionId=str(uuid.uuid4()))
        reused_proof = cluster.signing_authorization(reused, authorizationId=claims["authorizationId"])
        for i in (0, 1):
            rejected(lambda: cluster.request(i, "/v1/sessions", dict(reused, authorization=reused_proof), authorize=False))
            different_grant = cluster.signing_authorization(signing, authorizationId=str(uuid.uuid4()))
            rejected(lambda: cluster.request(i, "/v1/sessions", dict(signing, authorization=different_grant), authorize=False))
        while time.time() <= claims["expiresAt"]:
            time.sleep(0.05)
        for i in (0, 1):
            cluster.stop(i)
            cluster.start(i)
            require(cluster.request(i, "/v1/sessions", dict(signing, authorization=proof), authorize=False)["state"] == "COMPLETED",
                    "original approval/session binding did not survive restart")
        print("PASS native authorization: mandatory proof, tamper, expiry, one-time binding, original-session restart")
    finally:
        cluster.close()
