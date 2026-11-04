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
        pending = dict(signing, sessionId=str(uuid.uuid4()), messageHex="78" * 32)
        pending_proof = cluster.signing_authorization(pending)
        require(cluster.request(0, "/v1/sessions", dict(pending, authorization=pending_proof), authorize=False)["state"] == "RUNNING",
                "single-holder signing session did not wait for its partner")
        quarantine = {"tenantId": cluster.tenant, "keyId": key, "descriptorHash": "ab" * 32}
        rejected(lambda: cluster.request(0, "/v1/key-quarantines", quarantine,
                                         context=cluster.client_context("unregistered")))
        rejected(lambda: cluster.request(0, "/v1/key-quarantines",
                                         dict(quarantine, tenantId=str(uuid.uuid4()))))
        for i in range(3):
            receipt = cluster.request(i, "/v1/key-quarantines", quarantine)
            require(receipt["quarantined"] and receipt["descriptorHash"] == quarantine["descriptorHash"]
                    and receipt["partyId"] == cluster.identities[i], "holder quarantine receipt differs")
            require(cluster.request(i, "/v1/key-quarantines", quarantine) == receipt,
                    "original holder quarantine retry differs")
            rejected(lambda i=i: cluster.request(i, "/v1/key-quarantines",
                                                 dict(quarantine, descriptorHash="cd" * 32)))
        for i in (0, 1):
            if i == 0:
                deadline = time.monotonic() + 5
                while time.monotonic() < deadline:
                    interrupted = cluster.request(0, "/v1/results", {"sessionId": pending["sessionId"],
                                                                        "tenantId": cluster.tenant, "keyId": key})
                    if interrupted["state"] == "INDETERMINATE":
                        break
                    time.sleep(0.05)
                require(interrupted["state"] == "INDETERMINATE", "in-flight session did not stop on key quarantine")
            rejected(lambda i=i: cluster.request(i, "/v1/sessions",
                                                 dict(signing, authorization=proof), authorize=False))
            previous = cluster.request(i, "/v1/results", {"sessionId": signing["sessionId"],
                                                             "tenantId": cluster.tenant, "keyId": key})
            require(previous["state"] == "COMPLETED", "original result must remain readable for reconciliation")
            cluster.stop(i)
            cluster.start(i)
            rejected(lambda i=i: cluster.request(i, "/v1/sessions",
                                                 dict(signing, authorization=proof), authorize=False))
        replacement = dict(create, sessionId=str(uuid.uuid4()), keyId=str(uuid.uuid4()))
        cluster.run(replacement, [0, 1, 2])
        print("PASS native authorization: mandatory proof, tamper, expiry, one-time binding, original-session restart")
    finally:
        cluster.close()
