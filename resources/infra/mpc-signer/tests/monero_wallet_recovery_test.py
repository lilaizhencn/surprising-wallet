#!/usr/bin/env python3
"""Actual original native SIGN journal recovery and safe holds; no synthetic transaction signatures."""
import copy
import hashlib
import http.client
import http.server
import json
import os
import pathlib
import secrets
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse

from monero_wallet_address_test import main as addresses
from monero_native_test import snapshot
from node_test import free_port, require


class WalletGate:
    """Transparent loopback transport to the official wallet; holds one actual RPC response in memory."""
    def __init__(self, upstream, pause=None):
        self.upstream = urllib.parse.urlparse(upstream)
        require(self.upstream.hostname == '127.0.0.1' and self.upstream.scheme == 'http', 'isolated native wallet required')
        self.pause, self.counts, self.lock = pause, {}, threading.Lock()
        self.paused, self.release = threading.Event(), threading.Event()
        self.transaction_hash = None
        gate = self

        class Handler(http.server.BaseHTTPRequestHandler):
            protocol_version = 'HTTP/1.1'

            def setup(self):
                super().setup()
                # Wallet Digest challenges belong to the upstream connection; preserve it across the challenge/retry.
                self.upstream_connection = http.client.HTTPConnection(gate.upstream.hostname, gate.upstream.port, timeout=15)

            def finish(self):
                try:
                    super().finish()
                finally:
                    self.upstream_connection.close()

            def log_message(self, *_):
                pass

            def do_POST(self):
                length = int(self.headers.get('Content-Length', '0'))
                require(self.path == '/json_rpc' and 0 <= length <= 2 * 1024 * 1024, 'bounded private RPC forwarding required')
                body = self.rfile.read(length)
                method = json.loads(body)['method'] if body else None
                connection = self.upstream_connection
                try:
                    headers = {'Content-Type': 'application/json'}
                    if self.headers.get('Authorization'):
                        headers['Authorization'] = self.headers['Authorization']
                    connection.request('POST', self.path, body, headers)
                    answer = connection.getresponse()
                    payload, status = answer.read(), answer.status
                    values = json.loads(payload) if status == 200 else {}
                    successful = status == 200 and 'result' in values and not values.get('error')
                    pause = False
                    if successful and method is not None:
                        with gate.lock:
                            gate.counts[method] = gate.counts.get(method, 0) + 1
                            pause = gate.pause == method or gate.pause == 'final-describe' and method == 'describe_transfer' and gate.counts.get('sign_multisig', 0) == 1
                        if pause and not gate.paused.is_set():
                            hashes = values['result'].get('tx_hash_list', [])
                            gate.transaction_hash = hashes[0] if hashes else None
                            gate.paused.set()
                            require(gate.release.wait(45), 'controlled native RPC response was not released')
                    self.send_response(status)
                    for key, value in answer.getheaders():
                        if key.lower() in ('content-type', 'www-authenticate'):
                            self.send_header(key, value)
                    self.send_header('Content-Length', str(len(payload)))
                    if answer.will_close:
                        self.close_connection = True
                    self.send_header('Connection', 'close' if self.close_connection else 'keep-alive')
                    self.end_headers()
                    self.wfile.write(payload)
                except (BrokenPipeError, ConnectionResetError):
                    self.close_connection = True

        self.server = http.server.ThreadingHTTPServer(('127.0.0.1', free_port()), Handler)
        self.server.daemon_threads = True
        self.endpoint = 'http://127.0.0.1:' + str(self.server.server_port)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def signature_calls(self):
        with self.lock:
            return {key: self.counts.get(key, 0) for key in ('transfer', 'sign_multisig', 'export_multisig_info', 'import_multisig_info')}

    def close(self):
        self.release.set()
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)


def recoveries(cluster, ledger, signer, publication, environment):
    classpath = environment['MPC_WALLET_TEST_CLASSPATH']
    (signer / 'coordinator-aes.bin').write_bytes(secrets.token_bytes(32))
    proofs, holds = [], []

    def java(phase, pair):
        result = subprocess.run(['java', '-cp', classpath, 'com.surprising.wallet.custody.MpcMoneroPaymentNativeProbe', str(signer), phase, str(pair)], env=environment, timeout=180)
        if result.returncode:
            for index in range(3):
                if index in cluster.logs:
                    cluster.logs[index].flush()
                print((cluster.root / ('node' + str(index) + '.log')).read_text()[-1500:], file=sys.stderr)
        require(result.returncode == 0, 'actual Java native original recovery failed: ' + phase)

    def wait(check, message, timeout=35):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            value = check()
            if value:
                return value
            time.sleep(.02)
        raise AssertionError(message)

    def journal(index, kind, session):
        return signer / ('node' + str(index) + '-store') / (kind + '-' + session)

    def configure(index, gate=None, timeout=120):
        cluster.stop(index)
        file = signer / ('node' + str(index) + '.json')
        config = json.loads(file.read_text())
        config['monero']['walletRpcUrl'] = gate.endpoint if gate else ledger.configurations[index]['walletRpcUrl']
        config['sessionTimeoutSeconds'] = timeout
        file.write_text(json.dumps(config))
        if gate:
            ledger.auth[gate.endpoint] = ledger.auth[ledger.configurations[index]['walletRpcUrl']]
            ledger.call(gate.endpoint, 'get_version')
        cluster.start(index)

    def rejected(index, request, identity):
        try:
            cluster.request(index, '/v1/monero-recovery-material', request, cluster.client_context(identity))
        except urllib.error.HTTPError as error:
            require(error.code in (400, 403), 'native peer material rejection had an unrelated HTTP status')
            return
        raise AssertionError('unauthorized native original material was exposed')

    ledger.call(ledger.funder, 'create_wallet', {'filename': 'external-original-recovery-funder', 'password': secrets.token_hex(32), 'language': 'English'})
    recipient = ledger.call(ledger.funder, 'get_address')['address']
    for count in (4, 80):
        ledger.call(ledger.daemon, 'generateblocks', {'wallet_address': recipient, 'amount_of_blocks': count})
    ledger.call(ledger.funder, 'refresh')
    published = publication['addresses']
    ledger.call(ledger.funder, 'transfer', {'destinations': [{'address': published[0], 'amount': 5 * 10**12},
        {'address': published[1], 'amount': 2 * 10**12}, {'address': published[3], 'amount': 2 * 10**12}]})
    ledger.call(ledger.daemon, 'generateblocks', {'wallet_address': recipient, 'amount_of_blocks': 13})

    def scenario(mode, number, source, reference=None):
        pair = ([0, 1], [0, 2], [1, 2])[number]
        proposer, cosigner = pair
        absent = next(index for index in range(3) if index not in pair)
        first = WalletGate(ledger.configurations[proposer]['walletRpcUrl'], 'transfer' if mode == 'before-review' else None)
        second = WalletGate(ledger.configurations[cosigner]['walletRpcUrl'], 'sign_multisig' if mode in ('cosigned', 'lost-cosign-reply') else 'final-describe')
        gates = {proposer: first, cosigner: second}
        original, saved_result = None, None
        try:
            configure(proposer, first, 30 if mode == 'cosigned' else 120)
            configure(cosigner, second, 30 if mode == 'cosigned' else 120)
            cluster.stop(absent)
            ledger.stop('wallet' + str(absent))
            case = {'source': source, 'recipient': recipient}
            if reference:
                case['reference'] = reference
            (signer / 'payment-case-public.json').write_text(json.dumps(case))
            java('admit', number)
            original = json.loads((signer / 'admitted-sign-request-public.json').read_text())
            session = original['sessionId']
            gate = first if mode == 'before-review' else second
            require(gate.paused.wait(35), 'actual native RPC did not reach controlled interruption point')
            if mode == 'before-review':
                require(not journal(proposer, 'monero-reviewed', session).exists(), 'before-review fault missed its native boundary')
                for index in pair:
                    cluster.stop(index)
                gate.release.set()
            elif mode == 'lost-cosign-reply':
                require(all(journal(index, 'monero-reviewed', session).exists() for index in pair), 'co-signer was not approved by both original holders')
                for index in pair:
                    cluster.stop(index)
                gate.release.set()
                require(not journal(cosigner, 'monero-cosigned', session).exists(), 'lost RPC response was already persisted')
            elif mode == 'cosigned':
                cluster.stop(proposer)
                gate.release.set()
                wait(lambda: journal(cosigner, 'monero-cosigned', session).exists(), 'original native co-signed journal missing')
                wait(lambda: cluster.request(cosigner, '/v1/results', original)['state'] == 'INDETERMINATE', 'original failed SIGN was not retained', 60)
                saved_result = journal(cosigner, 'result', session).read_bytes()
                require(not journal(cosigner, 'monero-artifact', session).exists(), 'co-signed crash fixture accidentally completed its artifact')
                cluster.stop(cosigner)
            else:
                wait(lambda: journal(proposer, 'monero-artifact', session).exists(), 'original proposer artifact missing')
                if mode == 'artifact':
                    cluster.stop(proposer)
                    gate.release.set()
                    wait(lambda: journal(cosigner, 'monero-artifact', session).exists(), 'original co-signer artifact missing')
                    cluster.stop(cosigner)
                else:
                    # Fail only the final immutable result write, after the approved artifact is durable.
                    directory = journal(proposer, 'monero-artifact', session).parent
                    directory.chmod(0o500)
                    gate.release.set()
                    try:
                        wait(lambda: cluster.request(cosigner, '/v1/results', original)['state'] == 'COMPLETED', 'original peer did not finish its SIGN')
                        wait(lambda: cluster.request(proposer, '/v1/results', original)['state'] == 'INTERRUPTED', 'original result write failure was not fenced')
                    finally:
                        directory.chmod(0o700)
                    saved_result = journal(cosigner, 'result', session).read_bytes()
                    for index in pair:
                        cluster.stop(index)
            before = {index: gate.signature_calls() for index, gate in gates.items()}
            for index in pair:
                ledger.restart('wallet' + str(index))
                cluster.start(index)
            if mode in ('before-review', 'lost-cosign-reply'):
                java('hold', number)
                held = json.loads((signer / 'held-payment-public.json').read_text())
                require(held['state'] == 'INDETERMINATE' and held['attemptId'] == session and held['attemptCount'] == 1, 'unknown native request was released or replaced')
                require(all(not journal(index, 'recovered-result', session).exists() for index in pair), 'missing native signed material was manufactured')
                if gate.transaction_hash:
                    require(ledger.post(ledger.daemon, '/get_transactions', {'txs_hashes': [gate.transaction_hash], 'decode_as_json': False})['missed_tx'] == [gate.transaction_hash], 'unbound unknown native signature was broadcast')
                holds.append({'mode': mode, 'original': held, 'walletCalls': before})
            else:
                java('recover-select', number)
                selected = json.loads((signer / ('selected-payment-' + str(number) + '-public.json')).read_text())
                require(selected['selected']['signSessionId'] == session, 'recovery changed original native SIGN session')
                if saved_result is not None:
                    require(journal(cosigner, 'result', session).read_bytes() == saved_result, 'recovery overwrote original immutable result')
                require(journal(proposer, 'recovered-result', session).exists(), 'proposer did not persist its original recovered receipt')
                if mode != 'lost-result':
                    require(journal(cosigner, 'recovered-result', session).exists(), 'co-signer did not recover its own original RPC fact')
                rejected(cosigner, original, 'client')
                rejected(cosigner, original, 'node' + str(absent))
                rejected(proposer, original, 'node' + str(cosigner))
                changed = copy.deepcopy(original)
                changed['messageHex'] = '00'
                rejected(cosigner, changed, 'node' + str(proposer))
                peer = cluster.request(cosigner, '/v1/monero-recovery-material', original, cluster.client_context('node' + str(proposer)))
                require(peer['material'] is not None, 'original proposer cannot read its co-signer original material')
                for index, wallet in gates.items():
                    require(wallet.signature_calls() == before[index], 'native recovery exported new nonce material or called transfer/sign again')
                cluster.stop(proposer)
                ledger.stop('wallet' + str(proposer))
                java('relay', number)
                ledger.restart('wallet' + str(proposer))
                cluster.start(proposer)
                ledger.call(ledger.daemon, 'generateblocks', {'wallet_address': recipient, 'amount_of_blocks': 13})
                java('observe', number)
                cluster.stop(cosigner)
                ledger.stop('wallet' + str(cosigner))
                java('relay', number)
                ledger.restart('wallet' + str(cosigner))
                cluster.start(cosigner)
                proofs.append({'mode': mode, 'selected': selected, 'walletCallsBeforeRecovery': before,
                    'immutableOriginalResultSha256': hashlib.sha256(saved_result).hexdigest() if saved_result else None})
            for index, wallet in gates.items():
                if mode in ('before-review', 'lost-cosign-reply'):
                    require(wallet.signature_calls() == before[index], 'unknown native request retried transfer/sign or exported nonces')
            print('XMR actual interrupted native SIGN ' + mode + ' pair=' + str(pair) + ': original material only, unchanged attempt and zero new signing calls passed.', flush=True)
        except BaseException:
            print('Native wallet gate successful RPC counts: ' + json.dumps({str(index): wallet.counts for index, wallet in gates.items()}), file=sys.stderr)
            for index in range(3):
                if index in cluster.logs:
                    cluster.logs[index].flush()
                print((cluster.root / ('node' + str(index) + '.log')).read_text()[-1800:], file=sys.stderr)
            raise
        finally:
            first.release.set()
            second.release.set()
            for index in pair:
                cluster.stop(index)
                ledger.restart('wallet' + str(index))
                configure(index)
            ledger.restart('wallet' + str(absent))
            cluster.start(absent)
            first.close()
            second.close()

    for number, mode in enumerate(('cosigned', 'artifact', 'lost-result')):
        scenario(mode, number, published[0])
    scenario('before-review', 0, published[1], 'monero-original-held-before-review')
    scenario('lost-cosign-reply', 0, published[3], 'monero-original-held-wallet-response')
    fees = sum(int(proof['selected']['selected']['feeAtomic']) for proof in proofs)
    remaining = 9 * 10**12 - 3 * 10**12 - fees
    # Reopen through the native trust boundary; the test never reads a wallet password or reconstructs a key.
    current = json.loads((signer / 'admitted-sign-request-public.json').read_text())
    observations = [snapshot(cluster, ledger, current['keyId'], published[3], pair) for pair in ([0, 1], [1, 2])]
    for index in range(3):
        ledger.call(ledger.configurations[index]['walletRpcUrl'], 'refresh')
        actual = ledger.call(ledger.configurations[index]['walletRpcUrl'], 'get_balance', {'all_accounts': True, 'strict': True})
        require(actual['balance'] == remaining, 'interrupted native signatures changed original physical principal')
    destination = pathlib.Path(os.environ.get('MPC_MONERO_RECOVERY_PUBLIC_SUMMARY', '/tmp/surprising-wallet-mpc-monero-recovery-public.json'))
    destination.write_text(json.dumps({'scope': 'Actual Java/native original interrupted SIGN recovery from co-signed/artifact/completed-peer journals, and safe holds when original RPC material is missing; not whole all-chain completion.',
        'passed': True, 'quorums': ['AB', 'AC', 'BC'], 'recoveries': proofs, 'safeHolds': holds, 'finalObservations': observations,
        'fundedAtomic': str(9 * 10**12), 'paidAtomic': str(3 * 10**12), 'feeAtomic': str(fees), 'remainingAtomic': str(remaining)}, indent=2))


if __name__ == '__main__':
    require(len(sys.argv) == 3, 'usage: monero_wallet_recovery_test.py wallet-mpc-signer MONERO_BIN_DIR')
    addresses(*sys.argv[1:], after_addresses=recoveries)
