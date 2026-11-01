#!/usr/bin/env python3
"""Actual native Monero regtest payments through independent mTLS nodes; no wallet keys enter Java."""
import hashlib
import json
import os
import pathlib
import secrets
import subprocess
import sys
import tempfile
import time
import uuid

from node_test import Cluster, free_port, require


def execute(cluster, descriptor, nodes, timeout_seconds=60):
    classpath = os.environ.get('MPC_JAVA_TEST_CLASSPATH')
    if not classpath:
        return cluster.run(descriptor, nodes, timeout_seconds=timeout_seconds)
    path = cluster.root / ('java-monero-' + descriptor['sessionId'] + '.json')
    path.write_text(json.dumps({'request': descriptor, 'nodes': nodes, 'peers': cluster.peers}))
    result = subprocess.run(['java', '-cp', classpath, 'com.surprising.wallet.common.signing.MoneroMpcClientNativeProbe',
        str(cluster.root), str(path)], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=110)
    require(result.returncode == 0, 'actual Java native Monero client failed: ' + result.stderr.decode()[-3500:])
    values = json.loads(result.stdout)
    require(len(values) == len(nodes), 'actual Java native client returned a different quorum')
    print('XMR actual Java client: ' + descriptor['operation'] + ', authenticated participants ' + str(len(nodes)) + ' passed.', flush=True)
    return values


class NativeLedger:
    def __init__(self, root, binaries):
        self.root, self.binaries = root, binaries
        self.processes, self.logs, self.auth, self.commands = {}, {}, {}, {}
        self.daemon = 'http://127.0.0.1:' + str(free_port())
        self.configurations = []

    def post(self, endpoint, path, payload, timeout_seconds=10):
        request = json.dumps(payload).encode()
        command = ['curl', '--silent', '--show-error', '--fail', '--max-time', str(timeout_seconds), '--noproxy', '*',
            '--header', 'Content-Type: application/json', '--data-binary', '@-']
        if endpoint in self.auth:
            command.extend(['--digest', '--config', str(self.auth[endpoint])])
        response = subprocess.run(command + [endpoint + path], input=request, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        require(response.returncode == 0, 'isolated native RPC HTTP call failed; curl code ' + str(response.returncode))
        return json.loads(response.stdout)

    def call(self, endpoint, method, parameters=None):
        body = self.post(endpoint, '/json_rpc', {'jsonrpc': '2.0', 'id': 1, 'method': method, 'params': parameters or {}},
            timeout_seconds=60 if method == 'generateblocks' else 10)
        error = body.get('error') or {}
        diagnostic = ': ' + str(error.get('message', '')) if method in ('refresh', 'generateblocks') else ''
        require(not error and 'result' in body, 'isolated native RPC rejected ' + method
            + '; code ' + str(error.get('code')) + diagnostic)
        return body['result']

    def start(self, name, command, endpoint, readiness):
        self.commands[name] = (command, endpoint, readiness)
        log = (self.root / (name + '.log')).open('ab')
        self.logs[name] = log
        process = subprocess.Popen(command, stdout=log, stderr=log)
        self.processes[name] = process
        deadline = time.monotonic() + 25
        last_error = None
        while time.monotonic() < deadline:
            require(process.poll() is None, 'isolated Monero process exited: ' + name)
            try:
                self.call(endpoint, readiness)
                return
            except (OSError, ValueError, AssertionError) as error:
                last_error = error
                time.sleep(.1)
        raise AssertionError('isolated Monero readiness failed: ' + name + ': ' + str(last_error))

    def __enter__(self):
        try:
            for name in ('monerod', 'monero-wallet-rpc'):
                version = subprocess.run([self.binaries / name, '--version'], check=True,
                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True).stdout
                require('v0.18.5.4' in version, 'native Monero acceptance requires official 0.18.5.4')
            daemon_user, daemon_password = 'isolated-native-daemon', secrets.token_hex(24)
            daemon_config = self.root / 'daemon-auth.conf'
            daemon_config.write_text('rpc-login=' + daemon_user + ':' + daemon_password + '\n')
            daemon_curl = self.root / 'daemon-curl.conf'
            daemon_curl.write_text('user="' + daemon_user + ':' + daemon_password + '"\n')
            self.auth[self.daemon] = daemon_curl
            daemon_credentials = self.root / 'daemon-credentials.json'
            daemon_credentials.write_text(json.dumps({'username': daemon_user, 'password': daemon_password}))
            self.start('daemon', [str(self.binaries / 'monerod'), '--config-file', str(daemon_config), '--regtest', '--keep-fakechain', '--offline', '--fixed-difficulty', '1',
                '--disable-dns-checkpoints', '--check-updates', 'disabled', '--no-zmq', '--no-igd', '--non-interactive',
                '--data-dir', str(self.root / 'daemon'), '--rpc-bind-ip', '127.0.0.1', '--rpc-bind-port', self.daemon.rsplit(':', 1)[1],
                '--p2p-bind-ip', '127.0.0.1', '--p2p-bind-port', str(free_port()), '--rpc-ssl', 'disabled',
                '--log-file', str(self.root / 'daemon-private.log'), '--max-concurrency', '2'], self.daemon, 'get_info')
            for index in range(4):
                folder = self.root / ('wallet' + str(index))
                folder.mkdir(mode=0o700)
                endpoint = 'http://127.0.0.1:' + str(free_port())
                username, password = 'isolated-native-node', secrets.token_hex(24)
                curl_credential = folder / 'curl-auth.conf'
                curl_credential.write_text('user="' + username + ':' + password + '"\n')
                self.auth[endpoint] = curl_credential
                config = folder / 'rpc.conf'
                config.write_text('rpc-login=' + username + ':' + password + '\n'
                    + 'daemon-login=' + daemon_user + ':' + daemon_password + '\n')
                credential = folder / 'native-credential.json'
                credential.write_text(json.dumps({'username': username, 'password': password}))
                self.start('wallet' + str(index), [str(self.binaries / 'monero-wallet-rpc'), '--config-file', str(config),
                    '--wallet-dir', str(folder), '--shared-ringdb-dir', str(folder / 'ringdb'), '--daemon-address', self.daemon,
                    '--daemon-ssl', 'disabled', '--allow-mismatched-daemon-version', '--trusted-daemon', '--rpc-bind-ip', '127.0.0.1',
                    '--rpc-bind-port', endpoint.rsplit(':', 1)[1], '--rpc-ssl', 'disabled', '--non-interactive', '--max-concurrency', '1',
                    '--log-file', str(folder / 'wallet-private.log')], endpoint, 'get_version')
                if index < 3:
                    self.configurations.append({'network': 'regtest', 'walletRpcUrl': endpoint,
                        'walletCredentialFile': str(credential), 'daemonRpcUrl': self.daemon,
                        'daemonCredentialFile': str(daemon_credentials)})
                else:
                    self.funder = endpoint
            info = self.call(self.daemon, 'get_info')
            print('XMR isolated daemon network:', info.get('nettype'), 'offline:', info.get('offline'), flush=True)
            self.genesis = self.call(self.daemon, 'get_block_header_by_height', {'height': 0})['block_header']['hash']
            return self
        except BaseException:
            self.close()
            raise

    def stop(self, name):
        process = self.processes.pop(name, None)
        if process is not None and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
        log = self.logs.pop(name, None)
        if log:
            log.close()

    def restart(self, name):
        self.stop(name)
        self.start(name, *self.commands[name])

    def close(self):
        for name in list(self.processes)[::-1]:
            self.stop(name)

    def __exit__(self, *_):
        self.close()


def creation(cluster, ledger, key, kind, parent=None):
    policy = {'kind': kind, 'network': 'regtest', 'genesisHash': ledger.genesis}
    if parent:
        policy['rootKeyId'] = parent
    return cluster.descriptor('MONERO_NATIVE_2_OF_3', key, 'CREATE_KEY', [0, 1, 2],
        json.dumps(policy, sort_keys=True, separators=(',', ':')).encode().hex())


def verify_result(cluster, descriptor, results, vectors):
    digest = hashlib.sha256(json.dumps(descriptor, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
    addresses = []
    for result in results:
        require(result['tenantId'] == cluster.tenant and result['keyId'] == descriptor['keyId']
            and result['intentHash'] == digest, 'native Monero result is not bound to its immutable authenticated request')
        address = result['publicAddress']
        require(len(address) == 95, 'native Monero public address encoding differs')
        alphabet = '123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz'
        decoded = bytearray()
        for start in range(0, len(address), 11):
            block = address[start:start + 11]
            value = 0
            for character in block:
                value = value * 58 + alphabet.index(character)
            decoded.extend(value.to_bytes(8 if len(block) == 11 else 5, 'big'))
        require(len(decoded) == 69 and decoded[1:65].hex() == result['publicKeyHex'],
            'native Monero public spend/view keys do not match its published address')
        require('signatureHex' not in result, 'native Monero key ceremony leaked an unexpected artifact')
        addresses.append(address)
    require(len(set(addresses)) == 1, 'native Monero nodes published different public addresses')
    policy = json.loads(bytes.fromhex(descriptor['messageHex']))
    vectors.append({'kind': policy['kind'], 'network': policy['network'], 'address': addresses[0],
        'publicKeyHex': results[0]['publicKeyHex'], 'paymentIdHex': ''})
    return addresses[0]


def expect_failed_creation(cluster, descriptor):
    for index in range(3):
        require(cluster.request(index, '/v1/sessions', descriptor)['state'] == 'RUNNING', 'negative native ceremony was not admitted')
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        values = [cluster.request(index, '/v1/results', descriptor) for index in range(3)]
        if all(value['state'] != 'RUNNING' for value in values):
            require(all(value['state'] == 'FAILED' and 'publicKeyHex' not in value for value in values),
                'rejected native ceremony published a public address')
            return
        time.sleep(.05)
    raise AssertionError('negative native ceremony did not fail closed')


def snapshot(cluster, ledger, key, source, pair, tx_hash=None, wrong_anchor=False, depth=10):
    height = ledger.call(ledger.daemon, 'get_info')['height'] - depth
    anchor = ledger.call(ledger.daemon, 'get_block_header_by_height', {'height': height})['block_header']
    policy = {'kind': 'SNAPSHOT', 'network': 'regtest', 'genesisHash': ledger.genesis, 'sender': source,
        'height': height, 'blockHash': '00' * 32 if wrong_anchor else anchor['hash'], 'fromHeight': 0}
    if tx_hash:
        policy['transactionHash'] = tx_hash
    descriptor = cluster.descriptor('MONERO_NATIVE_2_OF_3', key, 'MONERO_OBSERVE', pair,
        json.dumps(policy, sort_keys=True, separators=(',', ':')).encode().hex())
    if wrong_anchor:
        for index in pair:
            cluster.request(index, '/v1/sessions', descriptor)
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            results = [cluster.request(index, '/v1/results', descriptor) for index in pair]
            if all(item['state'] != 'RUNNING' for item in results):
                require(all(item['state'] == 'FAILED' and 'monero' not in item for item in results),
                    'wrong canonical observation anchor published a snapshot')
                return
            time.sleep(.05)
        raise AssertionError('wrong-anchor observation did not terminate')
    results = execute(cluster, descriptor, pair)
    require(results[0]['monero'] == results[1]['monero'], 'independent native account snapshots disagree')
    observed = results[0]['monero']
    require(observed['address'] == source and observed['anchorHeight'] == height and observed['anchorHash'] == anchor['hash'],
        'native observation is not bound to its public account and canonical anchor')
    if tx_hash:
        transaction = observed['transaction']
        require(transaction['transactionHash'] == tx_hash and transaction['known'] and not transaction['inPool']
            and transaction['confirmations'] >= depth and len(transaction['blockHash']) == 64,
            'native observation does not prove original canonical transaction inclusion')
    return observed


def maturity(cluster, ledger, key, source):
    recipient = ledger.call(ledger.funder, 'get_address')['address']
    transfer = ledger.call(ledger.funder, 'transfer', {'destinations': [{'address': source, 'amount': 10**12}]})
    ledger.call(ledger.daemon, 'generateblocks', {'wallet_address': recipient, 'amount_of_blocks': 1})
    height = ledger.call(ledger.daemon, 'get_info')['height'] - 1
    block = ledger.call(ledger.daemon, 'get_block', {'height': height})
    miner = block['miner_tx_hash']
    require(miner not in block.get('tx_hashes', []), 'miner test must exercise the distinct coinbase inclusion field')
    immature = snapshot(cluster, ledger, key, source, [0, 1], miner, depth=1)
    require(immature['transaction']['coinbase'] and immature['transaction']['unlockTime'] > height,
        'native observation lost the original miner transaction maturity')
    outputs = immature['outputs']
    require(len(outputs) == 1 and outputs[0]['transactionHash'] == transfer['tx_hash']
        and outputs[0]['confirmations'] == 1 and not outputs[0]['unlocked'] and not outputs[0]['coinbase']
        and not outputs[0]['spent'] and not outputs[0]['frozen'] and outputs[0]['unlockTime'] == 0,
        'native observation dropped the immature owned output or claimed it spendable')
    original = ledger.post(ledger.daemon, '/get_transactions', {'txs_hashes': [transfer['tx_hash']], 'decode_as_json': True})['txs'][0]
    transaction = json.loads(original['as_json'])
    output = outputs[0]
    target = transaction['vout'][output['outputIndex']]['target']
    output_key = target.get('tagged_key', target).get('key')
    require(output_key == output['outputKey'] and original['output_indices'][output['outputIndex']] == output['globalIndex'],
        'native owned output does not preserve its original public transaction position')
    ledger.call(ledger.daemon, 'generateblocks', {'wallet_address': recipient, 'amount_of_blocks': 12})
    mature = snapshot(cluster, ledger, key, source, [0, 1], depth=1)
    require(len(mature['outputs']) == 1 and mature['outputs'][0]['unlocked']
        and mature['outputs'][0]['outputKey'] == output['outputKey'] and mature['outputs'][0]['outputIndex'] == output['outputIndex']
        and mature['outputs'][0]['confirmations'] == 13 and mature['unlockedBalanceAtomic'] == str(10**12),
        'native maturity refresh regenerated an output or failed to observe its actual unlock')
    print('XMR native observation: immature output retained, exact original position/global index, later unlock and canonical miner inclusion passed.', flush=True)


def relay(cluster, ledger, key, source, signing, tx_hash, index, corrupt=False):
    digest = hashlib.sha256(json.dumps(signing, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
    policy = {'kind': 'RELAY', 'network': 'regtest', 'genesisHash': ledger.genesis, 'sender': source,
        'signSessionId': signing['sessionId'], 'signIntentHash': digest,
        'transactionHash': '00' * 32 if corrupt else tx_hash}
    descriptor = cluster.descriptor('MONERO_NATIVE_2_OF_3', key, 'MONERO_RELAY', [index],
        json.dumps(policy, sort_keys=True, separators=(',', ':')).encode().hex())
    if corrupt:
        cluster.request(index, '/v1/sessions', descriptor)
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            result = cluster.request(index, '/v1/results', descriptor)
            if result['state'] != 'RUNNING':
                require(result['state'] == 'INDETERMINATE' and 'monero' not in result,
                    'altered relay hash was accepted or claimed as a proven payment failure')
                return
            time.sleep(.05)
        raise AssertionError('altered relay hash was not rejected')
    result = execute(cluster, descriptor, [index])[0]
    require(result['monero'] == {'kind': 'RELAY', 'transactionHash': tx_hash,
        'signSessionId': signing['sessionId'], 'signIntentHash': digest}, 'native relay changed the selected original signing receipt')
    cluster.stop(index)
    cluster.start(index)
    require(cluster.request(index, '/v1/results', descriptor) == result
        and cluster.request(index, '/v1/sessions', descriptor) == result,
        'native relay receipt did not survive restart and exact replay')


def payments(cluster, ledger, key, source):
    ledger.call(ledger.funder, 'create_wallet', {'filename': 'external-funder',
        'password': secrets.token_hex(32), 'language': 'English'})
    recipient = ledger.call(ledger.funder, 'get_address')['address']
    for blocks in (4, 80):
        ledger.call(ledger.daemon, 'generateblocks', {'wallet_address': recipient, 'amount_of_blocks': blocks})
    ledger.call(ledger.funder, 'refresh')
    ledger.call(ledger.funder, 'transfer', {'destinations': [{'address': source, 'amount': 25 * 10**12}]})
    ledger.call(ledger.daemon, 'generateblocks', {'wallet_address': recipient, 'amount_of_blocks': 12})
    fees, hashes = 0, []
    for pair in ([0, 1], [0, 2], [1, 2]):
        absent = next(index for index in range(3) if index not in pair)
        cluster.stop(absent)
        ledger.stop('wallet' + str(absent))
        try:
            if not hashes:
                observed = snapshot(cluster, ledger, key, source, pair)
                require(observed['balanceAtomic'] == str(25 * 10**12)
                    and observed['unlockedBalanceAtomic'] == str(25 * 10**12) and len(observed['outputs']) == 1,
                    'native observation did not report actual external owned funding')
                snapshot(cluster, ledger, key, source, pair, wrong_anchor=True)
            policy = {'kind': 'PAYMENT', 'network': 'regtest', 'genesisHash': ledger.genesis,
                'sender': source, 'recipient': recipient, 'amountAtomic': str(10**12), 'maximumFeeAtomic': str(10**10)}
            descriptor = cluster.descriptor('MONERO_NATIVE_2_OF_3', key, 'SIGN', pair,
                json.dumps(policy, sort_keys=True, separators=(',', ':')).encode().hex())
            try:
                results = execute(cluster, descriptor, pair)
            except BaseException:
                for index in pair:
                    cluster.logs[index].flush()
                    # Native node logs contain only controlled diagnostics and public session identities.
                    print((cluster.root / ('node' + str(index) + '.log')).read_text()[-1500:], file=sys.stderr)
                raise
            require(results[0]['signatureHex'] == results[1]['signatureHex'], 'native payment requires two identical authenticated artifacts')
            artifact = json.loads(bytes.fromhex(results[0]['signatureHex']))
            digest = hashlib.sha256(json.dumps(descriptor, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
            require(artifact['intentHash'] == digest and artifact['policy'] == policy
                and artifact['online'] == descriptor['online'] and artifact['tenantId'] == cluster.tenant
                and artifact['keyId'] == key and artifact['sessionId'] == descriptor['sessionId'],
                'native payment artifact lost its exact intent binding')
            require(artifact['description']['change_address'] == source and int(artifact['feeAtomic']) > 0,
                'native payment escaped its original change scope')
            tx_hash = artifact['transactionHash']
            require(len(tx_hash) == 64 and tx_hash not in hashes, 'native payment hash is invalid or reused')
            before = ledger.post(ledger.daemon, '/get_transactions', {'txs_hashes': [tx_hash], 'decode_as_json': False})
            require(before['missed_tx'] == [tx_hash], 'native signing broadcast before the durable business boundary')
            for index, result in zip(pair, results):
                cluster.stop(index)
                cluster.start(index)
                require(cluster.request(index, '/v1/results', descriptor) == result,
                    'signed native artifact was not recovered after node restart')
                require(cluster.request(index, '/v1/sessions', descriptor) == result,
                    'native signing replay created a different artifact')
            relay(cluster, ledger, key, source, descriptor, tx_hash, pair[0], corrupt=True)
            require(ledger.post(ledger.daemon, '/get_transactions', {'txs_hashes': [tx_hash], 'decode_as_json': False})['missed_tx'] == [tx_hash],
                'altered relay request broadcast a payment')
            # A complete joint signature can be broadcast by one surviving native holder.
            cluster.stop(pair[1])
            ledger.stop('wallet' + str(pair[1]))
            try:
                relay(cluster, ledger, key, source, descriptor, tx_hash, pair[0])
            finally:
                ledger.restart('wallet' + str(pair[1]))
                cluster.start(pair[1])
            ledger.call(ledger.daemon, 'generateblocks', {'wallet_address': recipient, 'amount_of_blocks': 12})
            ledger.call(ledger.funder, 'refresh')
            observed = ledger.call(ledger.funder, 'get_transfer_by_txid', {'txid': tx_hash})['transfer']
            require(observed['amount'] == 10**12 and observed['confirmations'] >= 10,
                'native recipient did not confirm the exact authorized amount')
            fees += int(artifact['feeAtomic'])
            hashes.append(tx_hash)
            observed = snapshot(cluster, ledger, key, source, pair, tx_hash)
            expected = 25 * 10**12 - len(hashes) * 10**12 - fees
            require(observed['balanceAtomic'] == str(expected) and observed['unlockedBalanceAtomic'] == str(expected),
                'native authenticated account state does not conserve actual principal and fee')
            change = [output for output in observed['outputs'] if output['transactionHash'] == tx_hash]
            require(change and all(output['change'] and output['sourceAddress'] == source for output in change),
                'native original-source change was not distinguished from an external deposit')
            # A new replay session on the other original holder must observe the same confirmed hash.
            relay(cluster, ledger, key, source, descriptor, tx_hash, pair[1])
            print('XMR native nodes: quorum ' + '/'.join(str(index) for index in pair)
                + ', offline third party, immutable single-holder relay, original-block confirmation and authenticated balance passed.', flush=True)
        finally:
            ledger.restart('wallet' + str(absent))
            cluster.start(absent)
    expected = 25 * 10**12 - 3 * 10**12 - fees
    print('XMR native nodes: AB/AC/BC confirmed, exact source conservation and original-account unlocked change passed.', flush=True)
    output = os.environ.get('MPC_MONERO_PUBLIC_PAYMENT_OUTPUT')
    if output:
        pathlib.Path(output).write_text(json.dumps({'protocol': 'MONERO_NATIVE_2_OF_3', 'network': 'isolated-regtest',
            'scope': 'native key/sign/relay/observation components; Java business pending', 'quorums': ['AB', 'AC', 'BC'],
            'fundingAtomic': str(25 * 10**12), 'paidAtomic': str(3 * 10**12), 'feeAtomic': str(fees),
            'remainingAtomic': str(expected), 'transactionHashes': hashes}, indent=2) + '\n')


def main(binary, directory):
    os.umask(0o077)
    with tempfile.TemporaryDirectory(prefix='surprising-wallet-mpc-xmr-native-') as temporary:
        root = pathlib.Path(temporary)
        with NativeLedger(root, pathlib.Path(directory).resolve()) as ledger:
            signer = root / 'signers'
            signer.mkdir(mode=0o700)
            cluster = Cluster(signer, binary, monero_configs=ledger.configurations)
            try:
                root_key = str(uuid.uuid4())
                vectors = []
                descriptor = creation(cluster, ledger, root_key, 'ROOT')
                root_address = verify_result(cluster, descriptor, execute(cluster, descriptor, [0, 1, 2]), vectors)
                integrated = ledger.call(ledger.configurations[0]['walletRpcUrl'], 'make_integrated_address',
                    {'standard_address': root_address, 'payment_id': '0123456789abcdef'})
                vectors.append({'kind': 'INTEGRATED', 'network': 'regtest', 'address': integrated['integrated_address'],
                    'publicKeyHex': vectors[0]['publicKeyHex'], 'paymentIdHex': '0123456789abcdef'})
                public = {root_address}
                print('XMR independent native nodes: authenticated root ceremony passed; private keys stayed in local wallet RPC.', flush=True)
                children = []
                for number in range(1, 4):
                    if number == 3:
                        for index in range(3):
                            cluster.stop(index)
                            cluster.start(index)
                    key = str(uuid.uuid4())
                    descriptor = creation(cluster, ledger, key, 'ADDRESS', root_key)
                    address = verify_result(cluster, descriptor, execute(cluster, descriptor, [0, 1, 2]), vectors)
                    require(address not in public, 'native Monero child account address was reused')
                    public.add(address)
                    children.append((key, address))
                    for configuration in ledger.configurations:
                        accounts = ledger.call(configuration['walletRpcUrl'], 'get_accounts')['subaddress_accounts']
                        require(len(accounts) == number + 1 and accounts[number]['base_address'] == address,
                            'native Monero account allocation differs from the three actual private wallets')
                print('XMR independent native nodes: three distinct account addresses, encrypted root-password reuse and signer restart passed.', flush=True)
                payments(cluster, ledger, *children[0])
                maturity(cluster, ledger, *children[1])
                descriptor = creation(cluster, ledger, str(uuid.uuid4()), 'ROOT')
                policy = json.loads(bytes.fromhex(descriptor['messageHex']))
                policy['genesisHash'] = '00' * 32
                descriptor['messageHex'] = json.dumps(policy, sort_keys=True, separators=(',', ':')).encode().hex()
                expect_failed_creation(cluster, descriptor)
                for index in range(3):
                    cluster.stop(index)
                    path = signer / ('node' + str(index) + '.json')
                    configuration = json.loads(path.read_text())
                    configuration['monero']['network'] = 'mainnet'
                    path.write_text(json.dumps(configuration))
                    cluster.start(index)
                try:
                    descriptor = creation(cluster, ledger, str(uuid.uuid4()), 'ROOT')
                    policy = json.loads(bytes.fromhex(descriptor['messageHex']))
                    policy['network'] = 'mainnet'
                    descriptor['messageHex'] = json.dumps(policy, sort_keys=True, separators=(',', ':')).encode().hex()
                    expect_failed_creation(cluster, descriptor)
                finally:
                    for index in range(3):
                        cluster.stop(index)
                        path = signer / ('node' + str(index) + '.json')
                        configuration = json.loads(path.read_text())
                        configuration['monero']['network'] = 'regtest'
                        path.write_text(json.dumps(configuration))
                        cluster.start(index)
                for configuration in ledger.configurations:
                    os.chmod(configuration['walletCredentialFile'], 0o644)
                try:
                    expect_failed_creation(cluster, creation(cluster, ledger, str(uuid.uuid4()), 'ADDRESS', root_key))
                finally:
                    for configuration in ledger.configurations:
                        os.chmod(configuration['walletCredentialFile'], 0o600)
                # Simulate one node's abandoned, unpublished account allocation. The next ceremony must align all three first.
                # Node 1 participated in the last payment, so its private wallet is open.
                # Node 0 was restarted and must only reopen via the native adapter.
                abandoned = ledger.configurations[1]['walletRpcUrl']
                ledger.call(abandoned, 'create_account', {'label': 'abandoned-unpublished'})
                ledger.call(abandoned, 'store')
                descriptor = creation(cluster, ledger, str(uuid.uuid4()), 'ADDRESS', root_key)
                address = verify_result(cluster, descriptor, execute(cluster, descriptor, [0, 1, 2]), vectors)
                for configuration in ledger.configurations:
                    accounts = ledger.call(configuration['walletRpcUrl'], 'get_accounts')['subaddress_accounts']
                    require(len(accounts) == 6 and accounts[5]['base_address'] == address,
                        'native account progress was not reconciled before publication')
                print('XMR independent native nodes: wrong genesis/private credential permissions rejected; interrupted account progress reconciled.', flush=True)
                # A registered root whose private file is missing must never be regenerated.
                endpoint = ledger.configurations[0]['walletRpcUrl']
                ledger.call(endpoint, 'close_wallet')
                keys = root / 'wallet0' / ('mpc-' + root_key + '.keys')
                saved = keys.with_suffix('.keys-preserved')
                keys.rename(saved)
                try:
                    descriptor = creation(cluster, ledger, str(uuid.uuid4()), 'ADDRESS', root_key)
                    for index in range(3):
                        cluster.request(index, '/v1/sessions', descriptor)
                    deadline = time.monotonic() + 20
                    while time.monotonic() < deadline:
                        result = cluster.request(0, '/v1/results', descriptor)
                        if result['state'] == 'FAILED':
                            break
                        time.sleep(.05)
                    require(result['state'] == 'FAILED' and not keys.exists(), 'native node regenerated a missing registered root')
                finally:
                    for index in range(3):
                        cluster.stop(index)
                    saved.rename(keys)
                print('XMR independent native nodes: missing registered root file failed closed without regenerating keys.', flush=True)
                output = os.environ.get('MPC_MONERO_PUBLIC_REFERENCE_OUTPUT')
                if output:
                    destination = pathlib.Path(output)
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    destination.write_text(json.dumps({'source': 'Official Monero 0.18.5.4 native root/account ceremonies through three independent authenticated signing nodes; public keys/addresses only, no funded wallet or secret material',
                        'vectors': vectors}, indent=2) + '\n')
            finally:
                cluster.close()


if __name__ == '__main__':
    if len(sys.argv) != 3:
        raise SystemExit('usage: monero_native_test.py wallet-mpc-signer MONERO_BIN_DIR')
    main(sys.argv[1], sys.argv[2])
