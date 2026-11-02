#!/usr/bin/env python3
"""Isolated native nodes; optional Java wallet probe uses the existing local PG18 test database."""
import concurrent.futures
import hashlib
import json
import os
import pathlib
import shutil
import socket
import ssl
import subprocess
import sys
import tempfile
import time
import threading
import urllib.error
import urllib.request
import uuid


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def openssl(*arguments):
    return subprocess.run(["openssl", *map(str, arguments)], check=True,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout


def issue_certificate(root, name):
    openssl("req", "-new", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:P-256",
            "-nodes", "-subj", "/CN=" + name, "-keyout", root / (name + ".key"),
            "-out", root / (name + ".csr"))
    extensions = root / (name + ".extensions")
    extensions.write_text("basicConstraints=critical,CA:FALSE\n"
                          "keyUsage=critical,digitalSignature\n"
                          "extendedKeyUsage=serverAuth,clientAuth\n"
                          "subjectKeyIdentifier=hash\n"
                          "authorityKeyIdentifier=keyid,issuer\n"
                          "subjectAltName=DNS:localhost,IP:127.0.0.1\n")
    openssl("x509", "-req", "-in", root / (name + ".csr"), "-CA", root / "ca.pem",
            "-CAkey", root / "ca.key", "-CAcreateserial", "-days", "1",
            "-extfile", extensions, "-out", root / (name + ".pem"))
    public_pem = openssl("x509", "-in", root / (name + ".pem"), "-pubkey", "-noout")
    public_der = subprocess.run(["openssl", "pkey", "-pubin", "-outform", "DER"],
                                input=public_pem, check=True, stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE).stdout
    return hashlib.sha256(public_der).hexdigest()


def free_port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


class Cluster:
    def __init__(self, root, binary, tenant=None, monero_configs=None, extra_tenants=()):
        self.root = root
        binary = pathlib.Path(binary)
        fixture = binary.with_name("wallet_mpc_fixture_node")
        override = os.environ.get("WALLET_MPC_TEST_NODE_BINARY")
        self.binary = pathlib.Path(override) if override else (fixture if binary.name == "wallet-mpc-signer" and fixture.is_file() else binary)
        self.processes, self.logs = {}, {}
        self.authorization_cache = {}
        self.authorization_lock = threading.RLock()
        # Isolated test authority only. Production nodes never hold this private key.
        openssl("genpkey", "-algorithm", "ED25519", "-out", root / "approval-authority.key")
        public = openssl("pkey", "-in", root / "approval-authority.key", "-pubout", "-outform", "DER")
        self.authorization_public_key = public[-32:].hex()
        openssl("req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
                "-subj", "/CN=isolated-wallet-mpc-test-ca", "-keyout", root / "ca.key",
                "-addext", "basicConstraints=critical,CA:TRUE",
                "-addext", "keyUsage=critical,keyCertSign,cRLSign",
                "-addext", "subjectKeyIdentifier=hash",
                "-out", root / "ca.pem")
        self.identities = [issue_certificate(root, "node" + str(i)) for i in range(3)]
        self.client_id = issue_certificate(root, "client")
        issue_certificate(root, "unregistered")
        self.tenant = str(uuid.UUID(tenant)) if tenant else str(uuid.uuid4())
        tenants = list(dict.fromkeys([self.tenant, *(str(uuid.UUID(value)) for value in extra_tenants)]))
        self.ports = [free_port() for _ in range(3)]
        self.peers = [{"identity": identity, "host": "localhost", "port": port}
                      for identity, port in zip(self.identities, self.ports)]
        for i in range(3):
            config = {"bindHost": "127.0.0.1", "port": self.ports[i],
                      "certificateFile": str(root / f"node{i}.pem"),
                      "privateKeyFile": str(root / f"node{i}.key"),
                      "caFile": str(root / "ca.pem"),
                      "testStorageKeyHex": os.urandom(32).hex(),
                      "storeDirectory": str(root / f"node{i}-store"),
                      "sessionTimeoutSeconds": 15, "peers": self.peers,
                      "authorizationPublicKeys": [self.authorization_public_key],
                      "clients": {self.client_id: {"tenants": tenants,
                                   "operations": ["CREATE_KEY", "REFRESH", "SIGN", "READ_RESULT"]}}}
            if monero_configs is not None:
                require(len(monero_configs) == 3, "Monero fixture requires three independent native wallets")
                config["monero"] = monero_configs[i]
                config["sessionTimeoutSeconds"] = 120
                config["clients"][self.client_id]["operations"].extend(["MONERO_RELAY", "MONERO_OBSERVE"])
            (root / f"node{i}.json").write_text(json.dumps(config))
        self.context = self.client_context("client")
        try:
            for i in range(3):
                self.start(i)
        except BaseException:
            self.close()
            raise

    def client_context(self, name):
        context = ssl.create_default_context(cafile=str(self.root / "ca.pem"))
        context.minimum_version = ssl.TLSVersion.TLSv1_3
        if name:
            context.load_cert_chain(self.root / (name + ".pem"), self.root / (name + ".key"))
        return context

    def signing_authorization(self, descriptor, **changes):
        with self.authorization_lock:
            return self._signing_authorization(descriptor, **changes)

    def _signing_authorization(self, descriptor, **changes):
        canonical = json.dumps(descriptor, sort_keys=True, separators=(",", ":"))
        if not changes and canonical in self.authorization_cache:
            return self.authorization_cache[canonical]
        claims = {"v": 1, "authorizationId": str(uuid.uuid4()), "requestId": str(uuid.uuid4()),
                  "tenantId": descriptor["tenantId"], "keyId": descriptor["keyId"], "epoch": descriptor["epoch"],
                  "sessionId": descriptor["sessionId"], "operation": descriptor["operation"],
                  "algorithm": descriptor["algorithm"], "online": descriptor["online"],
                  "messageHash": hashlib.sha256(bytes.fromhex(descriptor["messageHex"])).hexdigest(),
                  "chain": "XMR" if descriptor["algorithm"] == "MONERO_NATIVE_2_OF_3" else "ETH",
                  "network": "regtest", "businessHash": hashlib.sha256(canonical.encode()).hexdigest(),
                  "purpose": "WITHDRAWAL", "issuedAt": int(time.time()), "expiresAt": int(time.time()) + 300}
        claims.update(changes)
        payload = json.dumps(claims, sort_keys=True, separators=(",", ":")).encode()
        # OpenSSL raw Ed25519 needs a regular input file; the test fixture directory is private.
        source = self.root / ("approval-message-" + str(uuid.uuid4()))
        try:
            source.write_bytes(b"SW-MPC-AUTHORIZATION-V1\n" + payload)
            signature = openssl("pkeyutl", "-sign", "-rawin", "-inkey", self.root / "approval-authority.key", "-in", source)
        finally:
            source.unlink(missing_ok=True)
        proof = {"payloadHex": payload.hex(), "signatureHex": signature.hex()}
        if not changes:
            self.authorization_cache[canonical] = proof
        return proof

    def request(self, i, path, body=None, context=None, authorize=True):
        if body is not None and path == "/v1/sessions" and body.get("operation") in ("SIGN", "MONERO_RELAY") and authorize and "authorization" not in body:
            body = dict(body, authorization=self.signing_authorization(body))
        data = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(f"https://localhost:{self.ports[i]}" + path,
                                         data=data, headers={"Content-Type": "application/json"})
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),
                                             urllib.request.HTTPSHandler(context=context or self.context))
        with opener.open(request, timeout=5) as response:
            return json.load(response)

    def start(self, i):
        self.logs[i] = open(self.root / f"node{i}.log", "ab")
        self.processes[i] = subprocess.Popen([self.binary, self.root / f"node{i}.json"],
                                             stdout=self.logs[i], stderr=self.logs[i])
        deadline = time.monotonic() + 15
        last_error = None
        while time.monotonic() < deadline:
            if self.processes[i].poll() is not None:
                # The server emits fixed diagnostics, never secret buffers.
                self.logs[i].flush()
                raise AssertionError(f"native node {i} exited during startup: " +
                                     (self.root / f"node{i}.log").read_text()[-2000:])
            try:
                health = self.request(i, "/health")
                require(health["partyId"] == self.identities[i], "native SPKI identity differs")
                return
            except (OSError, urllib.error.URLError) as error:
                last_error = error
                time.sleep(0.05)
        raise AssertionError(f"native node {i} did not become ready: {last_error!r}")

    def stop(self, i):
        process = self.processes.pop(i, None)
        if process:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
        log = self.logs.pop(i, None)
        if log:
            log.close()

    def close(self):
        for i in list(self.processes):
            self.stop(i)

    def descriptor(self, algorithm, key, operation, online, message=""):
        return {"sessionId": str(uuid.uuid4()), "tenantId": self.tenant, "keyId": key,
                "epoch": 1, "operation": operation, "algorithm": algorithm,
                "online": [self.identities[i] for i in online], "messageHex": message}

    def run(self, descriptor, nodes, timeout_seconds=25):
        with concurrent.futures.ThreadPoolExecutor(max_workers=len(nodes)) as executor:
            submitted = list(executor.map(lambda i: self.request(i, "/v1/sessions", descriptor), nodes))
        require(all(result["state"] in ("RUNNING", "COMPLETED") for result in submitted), "session was not accepted")
        deadline = time.monotonic() + timeout_seconds
        while time.monotonic() < deadline:
            results = [self.request(i, "/v1/results", descriptor) for i in nodes]
            if all(result["state"] != "RUNNING" for result in results):
                require(all(result["state"] == "COMPLETED" for result in results),
                        "native session failed: " + json.dumps(results))
                return results
            time.sleep(0.05)
        raise AssertionError("native MPC session timed out")


def rejected(operation):
    try:
        operation()
    except (urllib.error.URLError, ssl.SSLError, OSError):
        return
    raise AssertionError("unauthorized or conflicting operation was accepted")


def verify_signature(root, algorithm, public_hex, message_hex, signature_hex):
    public = bytes.fromhex(public_hex)
    if algorithm == "ECDSA_SECP256K1":
        # RFC 5480 SubjectPublicKeyInfo for a compressed secp256k1 key.
        spki = bytes.fromhex("3036301006072a8648ce3d020106052b8104000a032200") + public
    elif algorithm == "ED25519":
        spki = bytes.fromhex("302a300506032b6570032100") + public
    else:
        require(verify_bip340(public, bytes.fromhex(message_hex), bytes.fromhex(signature_hex)),
                "network BIP340 signature failed independent verification")
        return
    (root / "verify-public.der").write_bytes(spki)
    (root / "verify-message.bin").write_bytes(bytes.fromhex(message_hex))
    (root / "verify-signature.bin").write_bytes(bytes.fromhex(signature_hex))
    arguments = ["pkeyutl", "-verify", "-pubin", "-inkey", root / "verify-public.der", "-keyform", "DER",
                 "-in", root / "verify-message.bin", "-sigfile", root / "verify-signature.bin"]
    if algorithm == "ED25519":
        arguments.append("-rawin")
    openssl(*arguments)


def verify_bip340(public, message, signature):
    # Independent Python implementation of the public BIP340 verification
    # equation only. No private keys or signing arithmetic are implemented.
    if len(public) != 32 or len(message) != 32 or len(signature) != 64:
        return False
    prime = 2**256 - 2**32 - 977
    order = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141
    generator = (0x79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798,
                 0x483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8)

    def add(left, right):
        if left is None:
            return right
        if right is None:
            return left
        x1, y1 = left
        x2, y2 = right
        if x1 == x2:
            if (y1 + y2) % prime == 0:
                return None
            slope = (3 * x1 * x1) * pow(2 * y1, -1, prime) % prime
        else:
            slope = (y2 - y1) * pow(x2 - x1, -1, prime) % prime
        x3 = (slope * slope - x1 - x2) % prime
        return x3, (slope * (x1 - x3) - y1) % prime

    def multiply(scalar, point):
        result = None
        while scalar:
            if scalar & 1:
                result = add(result, point)
            point = add(point, point)
            scalar >>= 1
        return result

    x = int.from_bytes(public, "big")
    if x >= prime:
        return False
    y = pow((x**3 + 7) % prime, (prime + 1) // 4, prime)
    if (y * y - x**3 - 7) % prime:
        return False
    if y & 1:
        y = prime - y
    r, s = int.from_bytes(signature[:32], "big"), int.from_bytes(signature[32:], "big")
    if r >= prime or s >= order:
        return False
    tag = hashlib.sha256(b"BIP0340/challenge").digest()
    challenge = int.from_bytes(hashlib.sha256(tag + tag + signature[:32] + public + message).digest(), "big") % order
    reconstructed = add(multiply(s, generator), multiply((order - challenge) % order, (x, y)))
    return reconstructed is not None and reconstructed[0] == r and reconstructed[1] % 2 == 0


def start_evm(root):
    require(shutil.which("anvil"), "Anvil is required by the wallet native transaction probe")
    port = free_port()
    log = open(root / "anvil.log", "ab")
    process = subprocess.Popen(["anvil", "--silent", "--host", "127.0.0.1", "--port", str(port),
                                "--chain-id", "31337", "--hardfork", "prague"], stdout=log, stderr=log)
    endpoint = f"http://127.0.0.1:{port}"
    try:
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            require(process.poll() is None, "Anvil exited before the isolated transaction probe")
            try:
                request = urllib.request.Request(endpoint, data=b'{"jsonrpc":"2.0","id":1,"method":"eth_chainId","params":[]}',
                                                 headers={"Content-Type": "application/json"})
                with opener.open(request, timeout=1) as response:
                    require(json.load(response).get("result") == hex(31337), "Isolated EVM chain ID differs")
                return process, log, endpoint
            except OSError:
                time.sleep(0.05)
        raise AssertionError("Anvil did not become ready")
    except BaseException:
        process.terminate()
        process.wait(timeout=5)
        log.close()
        raise


def exercise(cluster):
    for algorithm in ("ECDSA_SECP256K1", "ED25519", "SCHNORR_BIP340"):
        key = str(uuid.uuid4())
        creation = cluster.descriptor(algorithm, key, "CREATE_KEY", [0, 1, 2])
        created = cluster.run(creation, [0, 1, 2])
        public = created[0]["publicKeyHex"]
        require(all(result["publicKeyHex"] == public for result in created), "independent nodes disagree on public key")
        message = os.urandom(73 if algorithm == "ED25519" else 32).hex()
        for pair in ([0, 1], [0, 2], [1, 2]):
            signing = cluster.descriptor(algorithm, key, "SIGN", pair, message)
            signed = cluster.run(signing, pair)
            require(signed[0]["signatureHex"] and not signed[1]["signatureHex"], "signature receiver differs")
            verify_signature(cluster.root, algorithm, public, message, signed[0]["signatureHex"])
            repeated = cluster.request(pair[0], "/v1/sessions", signing)
            require(repeated == signed[0], "idempotent submission changed the signature")
            conflicting = dict(signing, messageHex=os.urandom(len(bytes.fromhex(message))).hex())
            rejected(lambda: cluster.request(pair[0], "/v1/sessions", conflicting))
        cluster.stop(0)
        cluster.start(0)
        require(cluster.request(0, "/v1/results", creation) == created[0], "DKG result did not survive restart")
        restored = cluster.descriptor(algorithm, key, "SIGN", [0, 1], message)
        signed = cluster.run(restored, [0, 1])
        verify_signature(cluster.root, algorithm, public, message, signed[0]["signatureHex"])
        print(algorithm + ": three independent processes, AB/AC/BC, idempotency and restored-share signing passed", flush=True)

    forbidden = cluster.descriptor("ED25519", str(uuid.uuid4()), "CREATE_KEY", [0, 1, 2])
    rejected(lambda: cluster.request(0, "/v1/sessions", forbidden, cluster.client_context("unregistered")))
    rejected(lambda: cluster.request(0, "/v1/sessions", forbidden, cluster.client_context(None)))
    rejected(lambda: cluster.request(0, "/v1/sessions", dict(forbidden, tenantId=str(uuid.uuid4()))))
    rejected(lambda: cluster.request(0, "/v1/messages", {
        "sessionId": forbidden["sessionId"], "intentHash": "0" * 64, "sequence": 0, "payloadHex": "00"}))

    # Start only one participant, then kill it before the protocol can finish.
    interrupted = cluster.descriptor("ED25519", str(uuid.uuid4()), "CREATE_KEY", [0, 1, 2])
    require(cluster.request(0, "/v1/sessions", interrupted)["state"] == "RUNNING", "interrupted test was not accepted")
    busy = cluster.descriptor("ED25519", str(uuid.uuid4()), "CREATE_KEY", [0, 1, 2])
    require(cluster.request(0, "/v1/sessions", busy)["state"] == "BUSY", "occupied node did not reject before acceptance")
    require(cluster.request(0, "/v1/results", busy)["state"] == "ABSENT", "busy capacity rejection created a receipt")
    digest = hashlib.sha256(json.dumps(interrupted, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
    peer_context = cluster.client_context("node1")
    rejected(lambda: cluster.request(0, "/v1/messages", {
        "sessionId": interrupted["sessionId"], "intentHash": digest,
        "sequence": 1, "payloadHex": "00"}, peer_context))
    rejected(lambda: cluster.request(0, "/v1/messages", {
        "sessionId": interrupted["sessionId"], "intentHash": "0" * 64,
        "sequence": 0, "payloadHex": "00"}, peer_context))
    cluster.stop(0)
    cluster.start(0)
    require(cluster.request(0, "/v1/results", interrupted)["state"] == "INTERRUPTED", "lost session was not fenced")
    require(cluster.request(0, "/v1/sessions", interrupted)["state"] == "INTERRUPTED", "lost execution was silently restarted")
    require(cluster.request(0, "/v1/results", busy)["state"] == "ABSENT", "capacity rejection persisted across restart")
    print("mTLS identity, tenant authorization, protocol role and interrupted-session fencing passed", flush=True)


def main():
    require(len(sys.argv) == 2, "usage: node_test.py /path/to/wallet-mpc-signer")
    os.umask(0o077)
    with tempfile.TemporaryDirectory(prefix="surprising-wallet-mpc-node-test-") as temporary:
        cluster = None
        evm = None
        evm_log = None
        try:
            cluster = Cluster(pathlib.Path(temporary), str(pathlib.Path(sys.argv[1]).resolve()))
            exercise(cluster)
            java_classpath = os.environ.get("MPC_JAVA_TEST_CLASSPATH")
            wallet_classpath = os.environ.get("MPC_WALLET_TEST_CLASSPATH")
            descriptor = {"tenantId": cluster.tenant, "peers": cluster.peers}
            for chain in ("BTC", "LTC", "DOGE", "BCH"):
                prefix = "MPC_" + chain + "_TEST_"
                if os.environ.get(prefix + "RPC_URL"):
                    require(wallet_classpath, chain + " probe requires the wallet Service classpath")
                    for suffix in ("RPC_USER", "RPC_PASSWORD", "WALLET"):
                        require(os.environ.get(prefix + suffix), chain + " probe requires " + prefix + suffix)
                    field = chain.lower()
                    descriptor.update({field + "RpcUrl": os.environ[prefix + "RPC_URL"],
                                       field + "RpcUser": os.environ[prefix + "RPC_USER"],
                                       field + "RpcPassword": os.environ[prefix + "RPC_PASSWORD"],
                                       field + "Wallet": os.environ[prefix + "WALLET"]})
            if os.environ.get("MPC_TRON_TEST_HTTP_URL"):
                require(wallet_classpath, "TRON probe requires the wallet Service classpath")
                for variable in ("MPC_TRON_TEST_FULL_NODE", "MPC_TRON_TEST_SOLIDITY_NODE", "MPC_TRON_TEST_TOKEN_ARTIFACT"):
                    require(os.environ.get(variable), "TRON probe requires " + variable)
                descriptor.update(tronHttpUrl=os.environ["MPC_TRON_TEST_HTTP_URL"],
                                  tronFullNode=os.environ["MPC_TRON_TEST_FULL_NODE"],
                                  tronSolidityNode=os.environ["MPC_TRON_TEST_SOLIDITY_NODE"],
                                  tronTokenArtifact=os.environ["MPC_TRON_TEST_TOKEN_ARTIFACT"])
            if os.environ.get("MPC_NEAR_TEST_RPC_URL"):
                require(wallet_classpath and os.environ.get("MPC_NEAR_TEST_FIXTURE"), "NEAR component requires wallet classpath and isolated funding fixture")
                descriptor["nearRpcUrl"] = os.environ["MPC_NEAR_TEST_RPC_URL"]
                descriptor["nearFixture"] = os.environ["MPC_NEAR_TEST_FIXTURE"]
            if os.environ.get("MPC_DOT_TEST_FIXTURE"):
                require(wallet_classpath, "DOT component/formal business require compiled wallet classpath")
                descriptor["dotFixture"] = os.environ["MPC_DOT_TEST_FIXTURE"]
            if os.environ.get("MPC_CARDANO_TEST_FIXTURE"):
                require(wallet_classpath, "Cardano component requires compiled wallet classpath")
                descriptor["cardanoFixture"] = os.environ["MPC_CARDANO_TEST_FIXTURE"]
            if os.environ.get("MPC_SUI_TEST_RPC_URL"):
                require(wallet_classpath and os.environ.get("MPC_SUI_TEST_FAUCET_URL"), "Sui component requires wallet classpath and isolated faucet")
                descriptor["suiRpcUrl"] = os.environ["MPC_SUI_TEST_RPC_URL"]
                descriptor["suiFaucetUrl"] = os.environ["MPC_SUI_TEST_FAUCET_URL"]
                if os.environ.get("MPC_SUI_TEST_COIN_FIXTURE"):
                    descriptor["suiCoinFixture"] = os.environ["MPC_SUI_TEST_COIN_FIXTURE"]
            if os.environ.get("MPC_TON_TEST_RPC_URL"):
                require(wallet_classpath and os.environ.get("MPC_TON_TEST_FUNDER_KEY_FILE"), "TON component requires wallet classpath and protected local genesis funding fixture")
                descriptor["tonRpcUrl"] = os.environ["MPC_TON_TEST_RPC_URL"]
                descriptor["tonFunderKeyFile"] = os.environ["MPC_TON_TEST_FUNDER_KEY_FILE"]
            if os.environ.get("MPC_APTOS_TEST_RPC_URL"):
                require(wallet_classpath and os.environ.get("MPC_APTOS_TEST_FAUCET_URL"), "Aptos component requires wallet classpath and isolated faucet")
                descriptor["aptosRpcUrl"] = os.environ["MPC_APTOS_TEST_RPC_URL"]
                descriptor["aptosFaucetUrl"] = os.environ["MPC_APTOS_TEST_FAUCET_URL"]
                if os.environ.get("MPC_APTOS_TEST_FA_FIXTURE"):
                    descriptor["aptosFaFixture"] = os.environ["MPC_APTOS_TEST_FA_FIXTURE"]
            if os.environ.get("MPC_SOLANA_TEST_RPC_URL"):
                require(wallet_classpath, "Solana codec probe requires the wallet Service classpath")
                descriptor["solanaRpcUrl"] = os.environ["MPC_SOLANA_TEST_RPC_URL"]
            if os.environ.get("MPC_HYPERCORE_TESTNET_URL"):
                require(wallet_classpath, "HyperCore auth probe requires the wallet Service classpath")
                descriptor["hypercoreTestnetUrl"] = os.environ["MPC_HYPERCORE_TESTNET_URL"]
            if os.environ.get("MPC_XRP_TEST_RPC_URL"):
                require(wallet_classpath, "XRP probe requires the wallet Service classpath")
                descriptor["xrpRpcUrl"] = os.environ["MPC_XRP_TEST_RPC_URL"]
            if wallet_classpath:
                evm, evm_log, descriptor["evmRpcUrl"] = start_evm(cluster.root)
            if java_classpath or wallet_classpath:
                (cluster.root / "java-probe.json").write_text(json.dumps(descriptor))
            if java_classpath:
                probe = subprocess.run(["java", "-cp", java_classpath,
                        "com.surprising.wallet.common.signing.MpcClientNativeProbe", str(cluster.root)],
                        stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=90)
                require(probe.returncode == 0, "Java client probe failed: " + probe.stderr.decode(errors="replace")[-12000:])
                results = json.loads(probe.stdout)
                require(len(results) == 9, "Java client did not execute every algorithm and quorum")
                for result in results:
                    verify_signature(cluster.root, result["algorithm"], result["publicKeyHex"],
                                     result["messageHex"], result["signatureHex"])
                print("Java client: real MPC signatures for all algorithms/quorums, intent binding and pre-submit SPKI pinning passed", flush=True)
            if wallet_classpath:
                probe = subprocess.run(["java", "-cp", wallet_classpath,
                        "com.surprising.wallet.custody.MpcSigningNativeProbe", str(cluster.root)],
                        cwd=pathlib.Path(__file__).resolve().parents[4],
                        stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                        timeout=600 if descriptor.get("tonRpcUrl") or descriptor.get("suiCoinFixture") or descriptor.get("nearFixture") or descriptor.get("dotFixture") or descriptor.get("cardanoFixture") else 150)
                require(probe.returncode == 0, "Wallet service probe failed: " + probe.stderr.decode(errors="replace")[-12000:])
                results = json.loads(probe.stdout)
                require(len(results) == 7, "Wallet service must execute both chain algorithms, every quorum and interrupted-quorum replay")
                for result in results:
                    verify_signature(cluster.root, result["algorithm"], result["publicKeyHex"],
                                     result["messageHex"], result["signatureHex"])
                print("Wallet service: PG18 + three native nodes, real custody-address DKG for 27 chains, atomic Gas publication, pending-DKG resume, legacy/1559 business withdrawals, lost-response recovery, principal settlement and signing recovery passed", flush=True)
                if descriptor.get("tronHttpUrl"):
                    print("TRON: actual native AB/AC/BC signatures, real TRE TRX/USDT/USDC withdrawals, committed raw/hash before RPC, lost-response recovery, exact recipient deltas and one-time frozen-principal settlement passed", flush=True)
                if descriptor.get("tonRpcUrl"):
                    require((cluster.root / "ton-codec-passed").is_file(), "Compiled probe did not execute requested TON component acceptance")
                    require((cluster.root / "ton-jetton-codec-passed").is_file(), "Compiled probe did not execute requested TON Jetton component acceptance")
                    require((cluster.root / "ton-business-passed").is_file(), "Compiled probe did not execute requested TON formal business acceptance")
                    print("TON business: actual AB/AC/BC TON/USDT/USDC, real deposits/replay, committed raw/hash, lost replies and recovery after gates closed, collection, skipped-send/source-abort refunds and exact net expense reconciliation passed.", flush=True)
                if descriptor.get("nearRpcUrl"):
                    require((cluster.root / "near-codec-passed").is_file(), "Compiled probe did not execute requested NEAR component acceptance")
                    require((cluster.root / "near-business-passed").is_file(), "Compiled probe did not execute requested NEAR formal business acceptance")
                if descriptor.get("dotFixture"):
                    require((cluster.root / "dot-codec-passed").is_file(), "Compiled probe did not execute requested DOT component acceptance")
                    require((cluster.root / "dot-business-passed").is_file(), "Compiled probe did not execute requested DOT formal business acceptance")
                    print("DOT: requested public codec and formal Asset Hub business acceptance passed.", flush=True)
                if descriptor.get("suiRpcUrl"):
                    require((cluster.root / "sui-codec-passed").is_file(), "Compiled probe did not execute requested Sui component acceptance")
                    print("Sui component: actual AB/AC/BC native SUI, fixed owned objects and PTB, exact principal and signed net Gas/storage rebate, same-wire replay.", flush=True)
                if descriptor.get("cardanoFixture"):
                    require((cluster.root / "cardano-codec-passed").is_file(), "Compiled probe did not execute requested Cardano component acceptance")
                    require((cluster.root / "cardano-business-passed").is_file(), "Compiled probe did not execute requested Cardano business acceptance")
                    print("Cardano: requested native-node MPC codec and formal custody business acceptance passed.", flush=True)
                if descriptor.get("suiCoinFixture"):
                    require((cluster.root / "sui-business-passed").is_file(), "Compiled probe did not execute requested Sui formal business acceptance")
                    print("Sui business: actual AB/AC/BC SUI/USDT/USDC; real deposits/replay; fixed Coin admission and raw/digest before RPC; lost replies and recovery after gates closed; exact principal; collection; committed out-of-gas refund; signed net storage expense passed.", flush=True)
                if descriptor.get("aptosRpcUrl"):
                    require((cluster.root / "aptos-codec-passed").is_file(), "Compiled probe did not execute requested Aptos component acceptance")
                    print("Aptos component: actual AB/AC/BC MPC APT, localnet accepted selected BCS hash, exact recipient/fee, identical-byte replay. Business and FA are NOT covered.", flush=True)
                if descriptor.get("aptosFaFixture"):
                    require((cluster.root / "aptos-business-passed").is_file(), "Compiled probe did not execute requested Aptos FA business acceptance")
                    print("Aptos business: actual AB/AC/BC APT/USDT/USDC; real deposits/replay; selected raw/hash before RPC; lost replies and disabled-switch recovery; principal settled once; collection; committed Move failure refund; exact actual net fee reconciliation passed.", flush=True)
                if descriptor.get("solanaRpcUrl"):
                    require((cluster.root / "solana-codec-passed").is_file(), "Compiled probe did not execute requested Agave codec acceptance")
                    require((cluster.root / "solana-business-passed").is_file(), "Compiled probe did not execute requested Agave business acceptance")
                    print("Solana: actual AB/AC/BC MPC SOL/USDT/USDC business withdrawals; joint Gas signatures; real deposits/replay; committed raw/hash before RPC; lost replies; exact principal settlement; collection; confirmed failure refund and actual ATA rent/fee spending passed.", flush=True)
                if descriptor.get("hypercoreTestnetUrl"):
                    require((cluster.root / "hypercore-auth-passed").is_file(), "Compiled probe did not execute requested HyperCore testnet authentication")
                    print("HyperCore: official testnet recovered joint owner from actual AB/AC/BC MPC signatures; unfunded action intent/raw persisted and principal retained. Funded transfers are NOT covered.", flush=True)
                if descriptor.get("xrpRpcUrl"):
                    require((cluster.root / "xrp-business-passed").is_file(), "Compiled wallet probe did not execute requested XRP business acceptance")
                    print("XRP: native AB/AC/BC signatures, official standalone xrpld XRP/USDT/USDC payments, tenant Gas activation/TrustSet, durable sequence/raw/hash, lost-response recovery and one-time settlement passed", flush=True)
                for chain in ("BTC", "LTC", "DOGE", "BCH"):
                    if descriptor.get(chain.lower() + "RpcUrl"):
                        print(chain + ": actual native AB/AC/BC, consensus-accepted recipient/change, formal PGMQ business signing, committed raw/hash before RPC, lost-response identical replay and one-time principal settlement passed", flush=True)
        finally:
            if cluster:
                cluster.close()
            if evm:
                evm.terminate()
                try:
                    evm.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    evm.kill()
                    evm.wait(timeout=5)
            if evm_log:
                evm_log.close()


if __name__ == "__main__":
    main()
