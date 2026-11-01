#!/usr/bin/env python3
"""Real native MPC authority/operation/relayer signatures and isolated Prague consensus; no business claim."""
import json
import os
import pathlib
import subprocess
import sys
import tempfile
import uuid

from node_test import Cluster, require, start_evm


def main():
    require(len(sys.argv) == 2, 'usage: evm7702_codec_test.py /path/to/wallet-mpc-signer')
    os.umask(0o077)
    classpath = os.environ.get('MPC_WALLET_TEST_CLASSPATH')
    require(classpath, 'set MPC_WALLET_TEST_CLASSPATH from the fresh successful wallet Maven verification')
    repository = pathlib.Path(__file__).resolve().parents[4]
    evm_root = repository / 'resources/infra/evm-fork'
    hardhat = evm_root / 'node_modules/.bin/hardhat'
    require(hardhat.is_file(), 'install the pinned evm-fork npm dependencies before running this acceptance')
    subprocess.run([str(hardhat), 'compile'], cwd=evm_root, check=True, timeout=120)
    with tempfile.TemporaryDirectory(prefix='surprising-wallet-mpc-7702-codec-') as temporary:
        root = pathlib.Path(temporary)
        cluster = None
        evm = None
        evm_log = None
        try:
            cluster = Cluster(root, str(pathlib.Path(sys.argv[1]).resolve(strict=True)))
            keys = []
            for _ in range(4):
                key_id = str(uuid.uuid4())
                descriptor = cluster.descriptor('ECDSA_SECP256K1', key_id, 'CREATE_KEY', [0, 1, 2])
                generated = cluster.run(descriptor, [0, 1, 2])
                public = generated[0]['publicKeyHex']
                require(all(result['publicKeyHex'] == public for result in generated), 'native DKG public identities differ')
                keys.append({'keyId': key_id, 'publicKeyHex': public})
            evm, evm_log, endpoint = start_evm(root)
            (root / '7702-fixture-public.json').write_text(json.dumps({'tenantId': cluster.tenant,
                'peers': cluster.peers, 'evmRpcUrl': endpoint, 'authorities': keys[:3], 'relayer': keys[3],
                'artifacts': str(evm_root / 'artifacts/contracts')}))
            proofs = []
            for number, pair in enumerate(([0, 1], [0, 2], [1, 2])):
                absent = next(index for index in range(3) if index not in pair)
                cluster.stop(absent)
                try:
                    result = subprocess.run(['java', '-cp', classpath, 'com.surprising.wallet.custody.MpcEvm7702NativeProbe',
                        str(root), str(number)], cwd=repository, timeout=180)
                    require(result.returncode == 0, 'actual EIP-7702 native codec failed for ' + str(pair))
                    proof = json.loads((root / ('7702-component-' + str(number) + '-public.json')).read_text())
                    require(proof['passed'] is True and proof['pair'] == pair and len(proof['transactions']) == 4,
                        'actual EIP-7702 native codec did not cover all requested steps')
                    require([item['type'] for item in proof['transactions']] == [4, 2, 4, 2], 'activation/reuse envelope coverage differs')
                    proofs.append(proof)
                finally:
                    cluster.start(absent)
            hashes = [item['transactionHash'] for proof in proofs for item in proof['transactions']]
            require(len(hashes) == len(set(hashes)) == 12, 'EIP-7702 native component reused an unrelated transaction')
            summary = {'scope': 'Actual MPC codec/Prague consensus, not formal wallet batch or principal accounting',
                'passed': True, 'quorums': ['AB', 'AC', 'BC'], 'transactions': 12, 'proofs': proofs}
            output = pathlib.Path(os.environ.get('MPC_EVM_7702_PUBLIC_PROOF', '/tmp/surprising-wallet-mpc-7702-codec-public.json'))
            output.write_text(json.dumps(summary))
            print('EIP-7702 native codec AB/AC/BC with stopped third: 12 actual type4/type2 collection/payout transactions passed.', flush=True)
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


if __name__ == '__main__':
    main()
