#!/usr/bin/env python3
"""Real executable JAR, actual HTTPS browser/service identities, existing PG18 temporary test DB."""
import contextlib
import http.cookies
import http.server
import threading
import hashlib
import json
import os
import pathlib
import socket
import ssl
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid
import zipfile

root = pathlib.Path(sys.argv[1]).resolve()
sys.path.insert(0, str(root / "resources/infra/mpc-signer/tests"))
from node_test import openssl, issue_certificate, Cluster, rejected, verify_signature
from utxo_acceptance import Core

os.umask(0o077)
jar = root / "wallet-approval/target/wallet-approval-1.0.0-SNAPSHOT.jar"
assert jar.is_file(), "package wallet-approval before this acceptance"
with tempfile.TemporaryDirectory(prefix="sw-approval-https-") as directory, contextlib.ExitStack() as cleanup:
    fixture = pathlib.Path(directory)
    # Use the already-packaged runtime dependencies. No unrelated repository downloads during acceptance.
    runtime_libs = fixture / "runtime-libs"; runtime_libs.mkdir(mode=0o700)
    def check_shared(name, content, checked):
        basename = pathlib.PurePosixPath(name).name
        for module in ("common", "chain-sdks"):
            if basename.startswith(module + "-"):
                expected = root / module / "target" / basename
                assert hashlib.sha256(content).digest() == hashlib.sha256(expected.read_bytes()).digest(), "executable contains stale " + module
                assert module not in checked, "duplicate shared runtime artifact: " + module
                checked.add(module)
    authority_shared = set()
    with zipfile.ZipFile(jar) as archive:
        for name in archive.namelist():
            if name.startswith("BOOT-INF/lib/") and name.endswith(".jar"):
                content = archive.read(name)
                check_shared(name, content, authority_shared)
                (runtime_libs / pathlib.PurePosixPath(name).name).write_bytes(content)
    assert authority_shared == {"common", "chain-sdks"}
    wallet_shared = set()
    with zipfile.ZipFile(root / "wallet-api/target/wallet-api-1.0.0-SNAPSHOT.jar") as wallet_jar:
        for name in wallet_jar.namelist():
            if name.startswith("BOOT-INF/lib/") and name.endswith(".jar"):
                check_shared(name, wallet_jar.read(name), wallet_shared)
    assert wallet_shared == {"common", "chain-sdks"}
    test_java_cp = os.pathsep.join([str(root / "wallet-approval/target/test-classes"), str(root / "wallet-approval/target/classes"), str(runtime_libs / "*")])
    openssl("req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1", "-subj", "/CN=ApprovalAcceptanceCA",
            "-keyout", fixture / "ca.key", "-out", fixture / "ca.pem")
    authority_spki = issue_certificate(fixture, "approval")
    client_id = issue_certificate(fixture, "wallet-client")
    issue_certificate(fixture, "other-client")
    password = uuid.uuid4().hex
    (fixture / "store-password").write_text(password)
    openssl("pkcs12", "-export", "-inkey", fixture / "approval.key", "-in", fixture / "approval.pem", "-certfile", fixture / "ca.pem",
            "-out", fixture / "server.p12", "-passout", "file:" + str(fixture / "store-password"))
    env = dict(os.environ, APPROVAL_TLS_KEYSTORE_PASSWORD=password, APPROVAL_TLS_TRUSTSTORE_PASSWORD=password)
    env.pop("APPROVAL_WALLET_DB_PASSWORD", None)
    subprocess.run(["keytool", "-importcert", "-noprompt", "-alias", "approval-ca", "-file", str(fixture / "ca.pem"),
                    "-keystore", str(fixture / "trust.p12"), "-storetype", "PKCS12", "-storepass:env", "APPROVAL_TLS_TRUSTSTORE_PASSWORD"],
                   env=env, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    tenant = str(uuid.uuid4())
    members = [(str(uuid.uuid4()), "admin@example.test", "TENANT_ADMIN"),
               (str(uuid.uuid4()), "reviewer1@example.test", "APPROVER"), (str(uuid.uuid4()), "reviewer2@example.test", "APPROVER")]
    for member, email, role in members:
        enrollment = fixture / "enroll.json"
        enrollment.write_text(json.dumps({"tenantId": tenant, "tenantSlug": "https-approval", "memberId": member,
                                         "email": email, "displayName": role, "role": role, "password": password}))
        enrollment.chmod(0o600)
        result = subprocess.run(["java", "-jar", str(jar), "enroll", str(enrollment)], env=env, check=True,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        assert b"Independent approval member enrolled" in result.stdout
        enrollment.unlink()
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0)); port = listener.getsockname()[1]
    origin = f"https://localhost:{port}"
    native_binary = pathlib.Path(os.environ.get("MPC_APPROVAL_SIGNER_BINARY", "/tmp/surprising-wallet-approval-native-build/wallet-mpc-signer"))
    assert native_binary.is_file(), "set MPC_APPROVAL_SIGNER_BINARY to a freshly built native signer"
    native_root = fixture / "holders"; native_root.mkdir(mode=0o700)
    native_cluster = Cluster(native_root, native_binary, tenant=tenant)
    cleanup.callback(native_cluster.close)
    issuer_der = fixture / "issuer.der"
    issuer_der.write_bytes(openssl("pkey", "-in", native_root / "approval-authority.key", "-outform", "DER"))
    issuer_der.chmod(0o600)
    registry_identity = issue_certificate(native_root, "approval-registry")
    for i in range(3):
        native_cluster.stop(i)
        config_path = native_root / f"node{i}.json"
        config = json.loads(config_path.read_text())
        config["clients"][registry_identity] = {"tenants": [tenant], "operations": ["READ_RESULT"]}
        config_path.write_text(json.dumps(config)); native_cluster.start(i)
    ceremony = native_cluster.descriptor("ECDSA_SECP256K1", str(uuid.uuid4()), "CREATE_KEY", [0, 1, 2])
    native_public = native_cluster.run(ceremony, [0, 1, 2])[0]["publicKeyHex"]
    native_profile = {"ca-file": str(native_root / "ca.pem"), "certificate-file": str(native_root / "approval-registry.pem"),
                      "private-key-file": str(native_root / "approval-registry.key"),
                      "nodes": [{"partyId": peer["identity"], "endpoint": f'https://localhost:{peer["port"]}'} for peer in native_cluster.peers]}
    wallet_native_profile = {"caFile": str(native_root / "ca.pem"), "certificateFile": str(native_root / "client.pem"),
                             "privateKeyFile": str(native_root / "client.key"), "nodes": native_profile["nodes"]}
    class FeeNode(http.server.BaseHTTPRequestHandler):
        def log_message(self, *_): pass
        def do_POST(self):
            call = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            assert call["method"] in ("eth_chainId", "eth_getBlockByNumber"), "unexpected independent fee read"
            result = "0xaa36a7" if call["method"] == "eth_chainId" else {"hash": "0x" + "a" * 64, "number": "0x42"}
            body = json.dumps({"jsonrpc": "2.0", "id": call["id"], "result": result}).encode()
            self.send_response(200); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)
    fee_node = http.server.ThreadingHTTPServer(("127.0.0.1", 0), FeeNode)
    threading.Thread(target=fee_node.serve_forever, daemon=True).start()
    cleanup.callback(fee_node.server_close); cleanup.callback(fee_node.shutdown)
    cores = {}
    for chain in ("BTC", "LTC", "DOGE", "BCH"):
        core = Core(fixture / ("core-" + chain.lower()), os.environ.get("APPROVAL_DOCKER_CONTEXT", "colima-wallet-mpc-test"), chain)
        cleanup.callback(core.close); cores[chain] = core
    env.update(APPROVAL_PUBLIC_ORIGIN=origin, APPROVAL_PORT=str(port), APPROVAL_TLS_KEYSTORE=str(fixture / "server.p12"),
               APPROVAL_TLS_TRUSTSTORE=str(fixture / "trust.p12"),
               SPRING_APPLICATION_JSON=json.dumps({"approval": {"service-clients": {client_id: [tenant]}, "native-nodes": native_profile,
                    "chain-rpc": {"profiles": [{"chain": "ETH", "network": "eip155:11155111", "role": "DEFAULT", "networkIdentity": "eip155:11155111",
                        "endpoint": f"http://127.0.0.1:{fee_node.server_port}", "parameters": {"feeModel": "standard", "maximumSignedTransactionBytes": "4096"}}]},
                    "signing": {"private-key-file": str(issuer_der), "public-key-hex": native_cluster.authorization_public_key},
                    "fees": {"budgets": [{"tenantId": tenant, "chain": "ETH", "network": "eip155:11155111", "networkIdentity": "eip155:11155111", "nativeSymbol": "ETH", "nativeDecimals": 18, "rolling24hLimit": "0.0001"},
                         {"tenantId": tenant, "chain": "APTOS", "network": "testnet", "networkIdentity": "aptos:2", "nativeSymbol": "APT", "nativeDecimals": 8, "rolling24hLimit": "0.1"}]},
                    "assets": {"entries": [{"chain": "ETH", "network": "eip155:11155111", "symbol": "USDC",
                    "nativeSymbol": "ETH", "networkIdentity": "eip155:11155111", "tokenIdentifier": "0x3333333333333333333333333333333333333333",
                    "decimals": 6, "nativeDecimals": 18, "networkFeeLimit": "0.02"},
                         {"chain": "APTOS", "network": "testnet", "symbol": "APT", "nativeSymbol": "APT", "networkIdentity": "aptos:2", "tokenIdentifier": None,
                          "decimals": 8, "nativeDecimals": 8, "networkFeeLimit": "0.02"}]}}}))
    installed = json.loads(env["SPRING_APPLICATION_JSON"])
    installed["approval"]["utxo-rpc"] = {"profiles": [core.profile() for core in cores.values()]}
    for chain, core in cores.items():
        identity = "utxo:" + core.genesis
        installed["approval"]["assets"]["entries"].append({"chain": chain, "network": "regtest", "symbol": chain, "nativeSymbol": chain,
            "networkIdentity": identity, "tokenIdentifier": None, "decimals": 8, "nativeDecimals": 8, "networkFeeLimit": "0.02"})
        installed["approval"]["fees"]["budgets"].append({"tenantId": tenant, "chain": chain, "network": "regtest", "networkIdentity": identity,
            "nativeSymbol": chain, "nativeDecimals": 8, "rolling24hLimit": "0.1"})
    env["SPRING_APPLICATION_JSON"] = json.dumps(installed)
    browser = ssl.create_default_context(cafile=str(fixture / "ca.pem"))
    client = ssl.create_default_context(cafile=str(fixture / "ca.pem")); client.load_cert_chain(fixture / "wallet-client.pem", fixture / "wallet-client.key")
    other = ssl.create_default_context(cafile=str(fixture / "ca.pem")); other.load_cert_chain(fixture / "other-client.pem", fixture / "other-client.key")
    def request(path, method="GET", body=None, context=browser, cookie=None, csrf=None, origin_header=None, expected=200):
        headers = {}
        if body is not None: headers["Content-Type"] = "application/json"
        if cookie: headers["Cookie"] = cookie
        if csrf: headers["X-Approval-CSRF"] = csrf
        if origin_header: headers["Origin"] = origin_header
        req = urllib.request.Request(origin + path, method=method, headers=headers, data=None if body is None else json.dumps(body).encode())
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPSHandler(context=context))
        try:
            with opener.open(req, timeout=5) as response:
                status = response.status; output = json.load(response); response_headers = response.headers
        except urllib.error.HTTPError as failure:
            status = failure.code; output = json.load(failure); response_headers = failure.headers
        assert status == expected, (path, status, expected, output)
        return output, response_headers
    def login(email):
        principal, headers = request("/approval/v1/auth/login", "POST", {"email": email, "password": password}, origin_header=origin)
        cookies = http.cookies.SimpleCookie(); cookies.load(headers["Set-Cookie"])
        cookie = cookies["__Host-sw_approval"]
        assert cookie["secure"] and cookie["httponly"] and cookie["samesite"] == "Strict" and cookie["path"] == "/" and not cookie["domain"]
        return principal, f"__Host-sw_approval={cookie.value}"
    def wallet_call(action, expected_success=True, **values):
        profile = {"origin": origin, "serverSpki": authority_spki, "verificationKeyHex": native_cluster.authorization_public_key,
                   "caFile": str(fixture / "ca.pem"), "certificateFile": str(fixture / "wallet-client.pem"),
                   "privateKeyFile": str(fixture / "wallet-client.key"), "timeout": "PT5S"}
        if not expected_success: profile["serverSpki"] = "0" * 64
        source = fixture / "wallet-client-input.json"
        source.write_text(json.dumps(dict(action=action, profile=profile, **values)))
        cp = os.pathsep.join([str(root / "wallet-api/target/test-classes"), str(root / "wallet-api/target/classes"), str(runtime_libs / "*")])
        result = subprocess.run(["java", "-cp", cp, "com.surprising.wallet.custody.WithdrawalApprovalClientHttpsProbe", str(source)],
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=120,
                                env=dict({key: value for key, value in os.environ.items() if key != "APPROVAL_AUTH_DB_PASSWORD"},
                                    APPROVAL_DB_USERNAME=os.environ["APPROVAL_WALLET_RUNTIME"], APPROVAL_DB_PASSWORD=os.environ["APPROVAL_WALLET_DB_PASSWORD"]))
        if not expected_success:
            assert result.returncode != 0, "wrong authority SPKI was accepted by the actual Wallet client"
            return
        assert result.returncode == 0, "actual Wallet client failed: " + result.stderr[-2000:]
        return json.loads(result.stdout)
    log = open(fixture / "authority.log", "wb")
    process = subprocess.Popen(["java", "-jar", str(jar)], env=env, stdout=log, stderr=log)
    try:
        deadline = time.monotonic() + 45
        while True:
            if process.poll() is not None: raise AssertionError("authority exited: " + (fixture / "authority.log").read_text()[-7000:])
            try: request("/approval/v1/auth/session", expected=401); break
            except (urllib.error.URLError, TimeoutError, ConnectionError):
                if time.monotonic() > deadline: raise
                time.sleep(0.1)
        request("/approval/v1/auth/login", "POST", {"email": members[0][1], "password": password}, origin_header="https://wallet.example", expected=401)
        registered, _ = request("/approval/service/v1/keys", "POST", {"keyGroup": "EVM", "ceremony": ceremony}, context=client)
        assert registered["publicKeyHex"] == native_public and registered["epoch"] == 1 and registered["tenantId"] == tenant
        wallet_created = wallet_call("OBSERVE_KEY", ceremony=ceremony, publicKeyHex=native_public, nativeProfile=wallet_native_profile)
        assert wallet_created["state"] == "ACTIVE" and wallet_created["epoch"] == 1 and wallet_created["independentlyRegistered"]
        request("/approval/service/v1/keys", "POST", {"keyGroup": "EVM", "ceremony": dict(ceremony, sessionId=str(uuid.uuid4()))}, context=client, expected=400)
        missing_holder = native_cluster.descriptor("ECDSA_SECP256K1", str(uuid.uuid4()), "CREATE_KEY", [0, 1, 2])
        native_cluster.run(missing_holder, [0, 1, 2])
        native_cluster.stop(2)
        request("/approval/service/v1/keys", "POST", {"keyGroup": "EVM", "ceremony": missing_holder}, context=client, expected=500)
        native_cluster.start(2)
        request("/approval/service/v1/keys", "POST", {"keyGroup": "EVM", "ceremony": missing_holder}, context=client)
        refresh = dict(ceremony, sessionId=str(uuid.uuid4()), epoch=2, operation="REFRESH")
        native_cluster.run(refresh, [0, 1, 2], timeout_seconds=60)
        refreshed, _ = request("/approval/service/v1/keys", "POST", {"keyGroup": "EVM", "ceremony": refresh}, context=client)
        assert refreshed["epoch"] == 2 and refreshed["publicKeyHex"] == native_public
        wallet_refreshed = wallet_call("OBSERVE_REFRESH", ceremony=ceremony, refresh=refresh, publicKeyHex=native_public, nativeProfile=wallet_native_profile)
        assert wallet_refreshed["state"] == "ACTIVE" and wallet_refreshed["epoch"] == 2 and wallet_refreshed["independentlyRegistered"]
        admin, admin_cookie = login(members[0][1])
        policy = {"chain": "ETH", "network": "eip155:11155111", "assetSymbol": "USDC", "expectedVersion": 0, "singleAmount": "100", "rolling24hAmount": "1000",
                  "allWithdrawals": False, "newRecipient": True, "consoleWithdrawal": True, "requiredReviewers": 2, "expirySeconds": 3600}
        request("/approval/v1/policies", "PUT", policy, cookie=admin_cookie, origin_header=origin, expected=401)
        saved, _ = request("/approval/v1/policies", "PUT", policy, cookie=admin_cookie, csrf=admin["csrfToken"], origin_header=origin)
        assert saved["singleAmount"] == "100" and saved["version"] == 1
        request("/approval/v1/policies", "PUT", policy, cookie=admin_cookie, csrf=admin["csrfToken"], origin_header=origin, expected=400)
        intent = {"id": str(uuid.uuid4()), "tenantId": tenant, "businessReference": "CW-HTTPS-1", "chain": "ETH", "network": "eip155:11155111",
                  "assetSymbol": "USDC", "recipient": "0x1111111111111111111111111111111111111111", "amount": "9007199254740993.123456",
                  "maximumNetworkFee": "0.01", "source": "API", "initiatorId": str(uuid.uuid4()), "subject": "customer-42", "externalReference": "merchant-order-42", "fundingSource": "ADDRESS"}
        request("/approval/service/v1/intents", "POST", intent, expected=401)
        request("/approval/service/v1/intents", "POST", intent, context=other, expected=401)
        request("/approval/service/v1/intents", "POST", dict(intent, assetSnapshot={"tokenIdentifier": "attacker-controlled"}), context=client, expected=400)
        admitted, _ = request("/approval/service/v1/intents", "POST", intent, context=client)
        assert admitted["assetSnapshot"]["tokenIdentifier"] == "0x3333333333333333333333333333333333333333"
        assert admitted["assetSnapshot"]["networkIdentity"] == "eip155:11155111"
        assert admitted["status"] == "PENDING" and admitted["intent"]["amount"] == intent["amount"]
        wallet_call("SUBMIT", expected_success=False, intent=intent)
        wallet_admitted = wallet_call("SUBMIT", intent=intent)
        assert wallet_admitted["intentHash"] == admitted["intentHash"] and wallet_admitted["status"] == "PENDING"
        request("/approval/service/v1/intents", "POST", dict(intent, tenantId=str(uuid.uuid4())), context=client, expected=401)
        # Use the project's pinned wire codec, not a parallel Python transaction implementation.
        candidate_input = fixture / "candidate-input.json"
        candidate_input.write_text(json.dumps({"intent": intent, "keyId": ceremony["keyId"], "epoch": 2,
                                               "online": [native_cluster.identities[0], native_cluster.identities[2]]}))
        wire = subprocess.run(["java", "-cp", test_java_cp, "com.surprising.wallet.approval.EvmWithdrawalVerifierTest", str(candidate_input)],
                              check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        candidate = json.loads(wire.stdout)
        request("/approval/service/v1/signing-grants", "POST", candidate, expected=401)
        waiting, _ = request("/approval/service/v1/signing-grants", "POST", candidate, context=client)
        assert waiting["status"] == "PENDING" and waiting["grant"] is None and waiting.get("automaticOperation") is None
        rejected(lambda: native_cluster.request(0, "/v1/sessions", candidate["execution"], authorize=False))
        for i in (1, 2):
            reviewer, cookie = login(members[i][1])
            queue, _ = request("/approval/v1/requests?status=PENDING", cookie=cookie)
            assert queue["total"] == 1 and queue["items"][0]["intentHash"] == admitted["intentHash"]
            decision = {"decision": "APPROVE", "reason": "Independent recipient and amount verification", "intentHash": admitted["intentHash"]}
            decided, _ = request(f'/approval/v1/requests/{intent["id"]}/decisions', "POST", decision, cookie=cookie, csrf=reviewer["csrfToken"], origin_header=origin)
            assert decided["status"] == ("PENDING" if i == 1 else "APPROVED")
            request("/approval/v1/policies", "PUT", dict(policy, expectedVersion=1), cookie=cookie, csrf=reviewer["csrfToken"], origin_header=origin, expected=401)
        final, _ = request(f'/approval/service/v1/intents/{intent["id"]}?tenantId={tenant}', context=client)
        assert final["status"] == "APPROVED" and not final["signingStarted"]
        native_cluster.stop(1)  # Registered key authorization and actual 0+2 signing must remain available.
        authorized = wallet_call("AUTHORIZE", candidate=candidate)
        assert authorized["status"] == "AUTHORIZED" and authorized["grant"]["sessionId"] == candidate["execution"]["sessionId"]
        retry, _ = request("/approval/service/v1/signing-grants", "POST", candidate, context=client)
        assert retry == authorized
        changed = dict(candidate, execution=dict(candidate["execution"], sessionId=str(uuid.uuid4())))
        request("/approval/service/v1/signing-grants", "POST", changed, context=client, expected=400)
        signed = wallet_call("SIGN_WITH_STORED_GRANT", candidate=candidate, approvalReceipt=final, ceremony=ceremony, refresh=refresh,
                             publicKeyHex=native_public, nativeProfile=wallet_native_profile)
        assert signed["status"] == "SIGNED" and signed["proofPersisted"] and signed["authorizationAudits"] == 1 and signed["principalFrozen"]
        verify_signature(native_root, "ECDSA_SECP256K1", native_public, candidate["execution"]["messageHex"], signed["signatureHex"])
        replay = dict(candidate, execution=dict(candidate["execution"], sessionId=str(uuid.uuid4())), replayActor="acceptance-operator",
                      replayReason="recover exactly the same EVM transaction and original account nonce")
        recovered, _ = request("/approval/service/v1/signing-grants", "POST", replay, context=client)
        assert recovered["grant"]["id"] != authorized["grant"]["id"]
        original_claims = json.loads(bytes.fromhex(authorized["grant"]["proof"]["payloadHex"]))
        recovery_claims = json.loads(bytes.fromhex(recovered["grant"]["proof"]["payloadHex"]))
        assert original_claims["businessHash"] == recovery_claims["businessHash"] and original_claims["requestId"] == recovery_claims["requestId"]
        recovery_signature = native_cluster.run(dict(replay["execution"], authorization=recovered["grant"]["proof"]), [0, 2])
        verify_signature(native_root, "ECDSA_SECP256K1", native_public, replay["execution"]["messageHex"], recovery_signature[0]["signatureHex"])
        original_again, _ = request("/approval/service/v1/signing-grants", "POST", candidate, context=client)
        assert original_again == authorized
        final, _ = request(f'/approval/service/v1/intents/{intent["id"]}?tenantId={tenant}', context=client)
        assert final["signingStarted"]
        wallet_final = wallet_call("STATUS", tenantId=tenant, requestId=intent["id"])
        assert wallet_final["signingStarted"] and wallet_final["intentHash"] == admitted["intentHash"]
        optional = dict(intent, id=str(uuid.uuid4()), businessReference="CW-HTTPS-NO-SUBJECT")
        optional.pop("subject")
        request("/approval/service/v1/intents", "POST", optional, context=client)
        roundtrip, _ = request(f'/approval/service/v1/intents/{optional["id"]}?tenantId={tenant}', context=client)
        assert roundtrip["intent"]["subject"] == ""
        native_cluster.start(1)
        aptos_ceremony = native_cluster.descriptor("ED25519", str(uuid.uuid4()), "CREATE_KEY", [0, 1, 2])
        aptos_public = native_cluster.run(aptos_ceremony, [0, 1, 2])[0]["publicKeyHex"]
        aptos_key = wallet_call("OBSERVE_KEY", chain="APTOS", ceremony=aptos_ceremony, publicKeyHex=aptos_public, nativeProfile=wallet_native_profile)
        assert aptos_key["independentlyRegistered"] and aptos_key["epoch"] == 1
        aptos_policy = dict(policy, chain="APTOS", network="testnet", assetSymbol="APT", singleAmount=None, rolling24hAmount=None,
                            allWithdrawals=True, expectedVersion=0)
        request("/approval/v1/policies", "PUT", aptos_policy, cookie=admin_cookie, csrf=admin["csrfToken"], origin_header=origin)
        aptos_intent = dict(intent, id=str(uuid.uuid4()), businessReference="CW-APTOS-HTTPS-1", chain="APTOS", network="testnet", assetSymbol="APT",
                            recipient="0x2", amount="1", externalReference="merchant-aptos")
        aptos_review = wallet_call("SUBMIT", intent=aptos_intent)
        assert aptos_review["status"] == "PENDING" and aptos_review["assetSnapshot"]["networkIdentity"] == "aptos:2"
        for index in (1, 2):
            reviewer, cookie = login(members[index][1])
            aptos_review, _ = request(f'/approval/v1/requests/{aptos_intent["id"]}/decisions', "POST",
                {"decision": "APPROVE", "reason": "Verified canonical Aptos recipient, native amount and fee", "intentHash": aptos_review["intentHash"]},
                cookie=cookie, csrf=reviewer["csrfToken"], origin_header=origin)
        assert aptos_review["status"] == "APPROVED"
        aptos_input = fixture / "aptos-candidate-input.json"
        aptos_input.write_text(json.dumps({"intent": aptos_intent, "keyId": aptos_ceremony["keyId"], "publicKeyHex": aptos_public,
                                          "online": [native_cluster.identities[0], native_cluster.identities[2]]}))
        aptos_wire = subprocess.run(["java", "-cp", test_java_cp, "com.surprising.wallet.approval.AptosWithdrawalVerifierTest", str(aptos_input)],
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=True)
        aptos_candidate = json.loads(aptos_wire.stdout)
        native_cluster.stop(1)
        aptos_signed = wallet_call("SIGN_WITH_STORED_GRANT", candidate=aptos_candidate, approvalReceipt=aptos_review, ceremony=aptos_ceremony,
                                  publicKeyHex=aptos_public, nativeProfile=wallet_native_profile)
        assert aptos_signed["proofPersisted"] and aptos_signed["principalFrozen"] and aptos_signed["authorizationAudits"] == 1
        verify_signature(native_root, "ED25519", aptos_public, aptos_candidate["execution"]["messageHex"], aptos_signed["signatureHex"])
        invitation_body = {"memberId": str(uuid.uuid4()), "email": "invited-reviewer@example.test", "displayName": "Invited reviewer", "reason": "Independent reviewer identity verified", "acknowledgedWalletRevocation": 0}
        request("/approval/v1/members", "POST", invitation_body, context=client, expected=401)
        request("/approval/v1/members", "POST", invitation_body, cookie=cookie, csrf=reviewer["csrfToken"], origin_header=origin, expected=401)
        invitation, _ = request("/approval/v1/members", "POST", invitation_body, cookie=admin_cookie, csrf=admin["csrfToken"], origin_header=origin)
        invite_password = uuid.uuid4().hex
        activation_body = {"token": invitation["token"], "password": invite_password}
        request("/approval/v1/auth/activate", "POST", activation_body, origin_header="https://wallet.example", expected=401)
        _, activation_headers = request("/approval/v1/auth/activate", "POST", activation_body, origin_header=origin)
        assert activation_headers.get("Set-Cookie") is None
        request("/approval/v1/auth/activate", "POST", activation_body, origin_header=origin, expected=400)
        invited_principal, invited_headers = request("/approval/v1/auth/login", "POST", {"email": invitation_body["email"], "password": invite_password}, origin_header=origin)
        invited_cookie = invited_headers["Set-Cookie"].split(";", 1)[0]
        assert invited_principal["memberId"] == invitation_body["memberId"] and invited_principal["role"] == "APPROVER"
        reset, _ = request(f'/approval/v1/members/{invitation_body["memberId"]}/activation', "POST", {"reason": "Credential reset independently verified", "acknowledgedWalletRevocation": 0}, cookie=admin_cookie, csrf=admin["csrfToken"], origin_header=origin)
        request("/approval/v1/auth/session", cookie=invited_cookie, expected=401)
        request("/approval/v1/auth/login", "POST", {"email": invitation_body["email"], "password": invite_password}, origin_header=origin, expected=401)
        request(f'/approval/v1/members/{invitation_body["memberId"]}/disable', "POST", {"reason": "Reviewer access no longer required"}, cookie=admin_cookie, csrf=admin["csrfToken"], origin_header=origin)
        request("/approval/v1/auth/activate", "POST", {"token": reset["token"], "password": invite_password}, origin_header=origin, expected=400)
        public_members, _ = request("/approval/v1/members", cookie=admin_cookie)
        assert any(member["id"] == invitation_body["memberId"] and member["status"] == "DISABLED" for member in public_members["items"])
        member_audit, _ = request("/approval/v1/audits?action=MEMBER.DISABLE", cookie=admin_cookie)
        assert member_audit["total"] == 1 and member_audit["items"][0]["entityId"] == invitation_body["memberId"]
        assert invitation["token"] not in json.dumps(public_members) and reset["token"] not in json.dumps(member_audit)
        request("/approval/v1/audits", cookie=invited_cookie, expected=401)
        # A Wallet mTLS identity may revoke, but only the separate administrator may acknowledge and restore.
        restored, _ = request(f'/approval/v1/members/{invitation_body["memberId"]}/activation', "POST",
            {"reason": "Independently restore reviewer for revocation acceptance", "acknowledgedWalletRevocation": 0}, cookie=admin_cookie, csrf=admin["csrfToken"], origin_header=origin)
        request("/approval/v1/auth/activate", "POST", {"token": restored["token"], "password": invite_password}, origin_header=origin)
        active, active_headers = request("/approval/v1/auth/login", "POST", {"email": invitation_body["email"], "password": invite_password}, origin_header=origin)
        active_cookie = active_headers["Set-Cookie"].split(";", 1)[0]
        revocation = {"tenantId": tenant, "memberId": invitation_body["memberId"], "sequence": 1, "actorId": members[0][0], "revokedAt": "2026-11-02T00:00:00Z"}
        request("/approval/service/v1/member-revocations", "POST", revocation, cookie=admin_cookie, csrf=admin["csrfToken"], origin_header=origin, expected=401)
        fenced, _ = request("/approval/service/v1/member-revocations", "POST", revocation, context=client)
        assert fenced["sequence"] == 1 and fenced["memberId"] == invitation_body["memberId"]
        request("/approval/v1/auth/session", cookie=active_cookie, expected=401)
        request("/approval/v1/auth/login", "POST", {"email": invitation_body["email"], "password": invite_password}, origin_header=origin, expected=401)
        request(f'/approval/v1/members/{invitation_body["memberId"]}/activation', "POST",
            {"reason": "Stale acknowledgement is forbidden", "acknowledgedWalletRevocation": 0}, cookie=admin_cookie, csrf=admin["csrfToken"], origin_header=origin, expected=400)
        restored, _ = request(f'/approval/v1/members/{invitation_body["memberId"]}/activation', "POST",
            {"reason": "Independently verified current Wallet revocation generation", "acknowledgedWalletRevocation": 1}, cookie=admin_cookie, csrf=admin["csrfToken"], origin_header=origin)
        request("/approval/v1/auth/activate", "POST", {"token": restored["token"], "password": uuid.uuid4().hex}, origin_header=origin)
        repeated, _ = request("/approval/service/v1/member-revocations", "POST", revocation, context=client)
        assert repeated == fenced
        restored_members, _ = request("/approval/v1/members", cookie=admin_cookie)
        assert any(member["id"] == invitation_body["memberId"] and member["status"] == "ACTIVE" for member in restored_members["items"])
        utxo_results = []
        for chain, core in cores.items():
            native_cluster.start(1)
            core_ceremony = native_cluster.descriptor("ECDSA_SECP256K1", str(uuid.uuid4()), "CREATE_KEY", [0, 1, 2])
            core_public = native_cluster.run(core_ceremony, [0, 1, 2])[0]["publicKeyHex"]
            observed = wallet_call("OBSERVE_KEY", chain=chain, ceremony=core_ceremony, publicKeyHex=core_public, nativeProfile=wallet_native_profile)
            points = core.fund(observed["address"], observed["scriptPubKeyHex"])
            core_policy = dict(policy, chain=chain, network="regtest", assetSymbol=chain, singleAmount=None, rolling24hAmount=None,
                allWithdrawals=True, expectedVersion=0)
            request("/approval/v1/policies", "PUT", core_policy, cookie=admin_cookie, csrf=admin["csrfToken"], origin_header=origin)
            receipts = []
            for index, amount in enumerate(("0.1", "0.2")):
                item = dict(intent, id=str(uuid.uuid4()), businessReference="CW-"+chain+"-"+str(index), chain=chain, network="regtest", assetSymbol=chain,
                    recipient=core.call("getnewaddress", wallet=True), amount=amount, externalReference="merchant-"+chain+"-"+str(index))
                review = wallet_call("SUBMIT", intent=item)
                for reviewer_index in (1, 2):
                    reviewer, cookie = login(members[reviewer_index][1])
                    review, _ = request(f'/approval/v1/requests/{item["id"]}/decisions', "POST",
                        {"decision": "APPROVE", "reason": "Verified exact native batch recipient, principal and expense", "intentHash": review["intentHash"]},
                        cookie=cookie, csrf=reviewer["csrfToken"], origin_header=origin)
                assert review["status"] == "APPROVED"; receipts.append(review)
            native_cluster.stop(1)
            result = wallet_call("UTXO_BATCH", chain=chain, keyId=core_ceremony["keyId"], publicKeyHex=core_public,
                approvalReceipts=receipts, outpoints=points,
                nativeProfile=dict(wallet_native_profile, nodes=[wallet_native_profile["nodes"][i] for i in (0, 2, 1)]), minerAddress=core.miner,
                chainRpc={"endpoint": core.root, "basicAuthFile": str(core.auth_file)})
            assert result["status"] == "PASS" and result["proofsPersisted"] == 2 and result["unknownRetrySameBytes"] and result["principalSettledOnce"]
            assert {"getblockhash", "getblockchaininfo", "gettxout"}.issubset(core.read_calls)
            utxo_results.append(result)
        print(json.dumps({"status": "PASS", "checks": ["executable offline enrollment", "real HTTPS secure browser session", "Origin/CSRF rejection",
              "actual mTLS client SPKI/tenant restriction", "exact decimal strings", "two distinct independent reviewers", "versioned policy", "service reconciliation", "optional subject roundtrip", "actual three-holder DKG registration", "offline holder blocks registration", "same-identity monotonic refresh", "frozen independent asset identity", "caller asset spoof rejected",
              "pending approval cannot issue grant", "unsigned native request rejected", "actual authority final-wire grant", "unchanged idempotent proof", "unaudited changed session rejected", "real two-holder signature using committed authority proof", "actual Wallet Java client submit/status/grant", "Wallet client rejects wrong authority SPKI", "actual Wallet persists original proof and audit before native dispatch", "principal stays frozen before chain confirmation", "Wallet automatically registers actual DKG and refresh", "registered key grant and actual signature work with one holder offline", "explicit EVM native recovery signs unchanged wire and nonce with the same business hash"],
              "memberLifecycle": "actual HTTPS invitation/activation/reset/disable/audit; Wallet mTLS revokes credentials and sessions, stale acknowledgment rejected, independent current-generation restore survives delayed duplicate revocation",
              "aptos": "actual two-reviewer admission, automatic three-holder Ed25519 registration, Wallet proof/audit persistence, actual two-holder canonical-BCS signature with holder1 offline",
              "utxo": utxo_results, "scope": "ordinary EVM/Aptos approval/native signing plus BTC/LTC/DOGE/BCH actual two-input batch broadcast, lost reply retry, canonical mining and principal settlement; other chain authority/broadcast acceptance remains"}))
    finally:
        process.terminate()
        try: process.wait(timeout=15)
        except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=5)
        log.close()
