"""Owned fresh Core containers and a transparent read-only RPC boundary; no database containers."""
import base64
import decimal
import http.server
import json
import os
import pathlib
import subprocess
import threading
import time
import urllib.error
import urllib.request
import uuid

IMAGES = {
    "BTC": ("surprising-wallet/mpc-bitcoin-core:29.0", "BTC", 18446),
    "LTC": ("surprising-wallet/mpc-litecoin-core:0.21.4", "LTC", 19445),
    "DOGE": ("surprising-wallet/mpc-dogecoin-core:1.14.9", "DOGE", 22556),
    "BCH": ("surprising-wallet/mpc-bchn:29.0.0", "BCH", 18445),
}


class Core:
    def __init__(self, directory, context, chain):
        self.directory = pathlib.Path(directory); self.directory.mkdir(mode=0o700)
        self.context = context; self.chain = chain; self.container = None; self.server = None
        image, prefix, port = IMAGES[chain]
        self.user = "funder-" + uuid.uuid4().hex; self.password = uuid.uuid4().hex
        self.read_user = "readonly-" + uuid.uuid4().hex; self.read_password = uuid.uuid4().hex
        self.read_calls = []
        self.auth_file = self.directory / "funder.credentials"
        self.auth_file.write_text(self.user + ":" + self.password); self.auth_file.chmod(0o600)
        self.read_file = self.directory / "readonly.credentials"
        self.read_file.write_text(self.read_user + ":" + self.read_password); self.read_file.chmod(0o600)
        env = self.directory / "node.env"
        env.write_text(prefix + "_REGTEST_RPC_USER=" + self.user + "\n" + prefix + "_REGTEST_RPC_PASSWORD=" + self.password + "\n")
        env.chmod(0o600)
        try:
            self.image_id = self.docker("image", "inspect", image, "--format", "{{.Id}}")
            self.container = self.docker("create", "--name", "sw-approval-utxo-" + chain.lower() + "-" + uuid.uuid4().hex[:12],
                                         "--label", "sw.approval.fixture=true", "--env-file", str(env), "-p", f"127.0.0.1::{port}", self.image_id)
            self.docker("start", self.container)
            mapped = self.docker("port", self.container, f"{port}/tcp")
            assert mapped.startswith("127.0.0.1:") and "\n" not in mapped, "Core port must be bound only to loopback"
            self.root = "http://" + mapped
            deadline = time.monotonic() + 45
            while True:
                try:
                    assert self.call("getblockchaininfo")["chain"] == "regtest"; break
                except (urllib.error.URLError, TimeoutError, ConnectionError, RuntimeError):
                    if time.monotonic() >= deadline: raise
                    time.sleep(0.1)
            self.wallet = "approval-funder"
            if chain != "DOGE": self.call("createwallet", [self.wallet])
            self.wallet_url = self.root if chain == "DOGE" else self.root + "/wallet/" + self.wallet
            self.miner = self.call("getnewaddress", wallet=True)
            self.mine(150)
            self.genesis = self.call("getblockhash", [0])
            self._readonly_proxy()
        except BaseException:
            self.close(); raise

    def docker(self, *args):
        result = subprocess.run(["docker", "--context", self.context, *args], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=60)
        if result.returncode != 0: raise RuntimeError("isolated Core container operation failed: " + args[0] + ": " + result.stderr[-800:])
        return result.stdout.strip()

    def raw(self, body, endpoint=None):
        auth = base64.b64encode((self.user + ":" + self.password).encode()).decode()
        request = urllib.request.Request(endpoint or self.root, data=body, headers={"Content-Type": "application/json", "Authorization": "Basic " + auth})
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with opener.open(request, timeout=10) as response: return response.read(2 * 1024 * 1024 + 1)

    def call(self, method, params=None, wallet=False):
        body = json.dumps({"jsonrpc": "1.0", "id": uuid.uuid4().hex, "method": method, "params": [] if params is None else params}).encode()
        result = json.loads(self.raw(body, self.wallet_url if wallet else self.root), parse_float=decimal.Decimal)
        if result.get("error") is not None: raise RuntimeError(f"isolated Core RPC {method} failed ({result['error'].get('code')})")
        return result["result"]

    def mine(self, count=1): return self.call("generatetoaddress", [count, self.miner])

    def _readonly_proxy(self):
        owner = self
        expected = "Basic " + base64.b64encode((self.read_user + ":" + self.read_password).encode()).decode()

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_POST(self):
                if self.headers.get("Authorization") != expected:
                    self.send_error(401); return
                length = int(self.headers.get("Content-Length", "0"))
                if not 0 < length <= 65536:
                    self.send_error(413); return
                body = self.rfile.read(length); request = json.loads(body)
                if request.get("method") not in {"getblockhash", "getblockchaininfo", "gettxout"}:
                    self.send_error(403); return
                owner.read_calls.append(request["method"])
                try:
                    result = owner.raw(body)  # Forward exact Core bytes, including decimal amounts and null/spent facts.
                    self.send_response(200); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(result)))
                    self.end_headers(); self.wfile.write(result)
                except (OSError, urllib.error.URLError): self.send_error(502)
            def log_message(self, *args): pass

        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.server.daemon_threads = True
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True); self.thread.start()
        self.read_url = f"http://127.0.0.1:{self.server.server_address[1]}"

    def profile(self):
        return {"chain": self.chain, "network": "regtest", "rpcChainName": "regtest", "genesisHash": self.genesis,
                "endpoint": self.read_url, "basicAuthFile": str(self.read_file), "minimumConfirmations": 1}

    def fund(self, address, script):
        points = []
        for amount in (2, 3):
            txid = self.call("sendtoaddress", [address, amount], wallet=True)
            raw = self.call("getrawtransaction", [txid, True])
            matches = [v for v in raw["vout"] if v["scriptPubKey"]["hex"] == script and v["value"] == amount]
            assert len(matches) == 1
            points.append({"txId": txid, "outputIndex": matches[0]["n"], "valueAtomic": amount * 100_000_000})
        self.mine()
        return points

    def close(self):
        if self.server is not None:
            self.server.shutdown(); self.server.server_close(); self.thread.join(timeout=2); self.server = None
        if self.container:
            subprocess.run(["docker", "--context", self.context, "rm", "-f", "-v", self.container], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
            self.container = None
