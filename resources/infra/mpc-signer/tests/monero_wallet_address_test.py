#!/usr/bin/env python3
"""Actual Java custody/Gas native address publication; only uses the existing local PG18 test instance."""
import json
import os
import pathlib
import subprocess
import sys
import tempfile

from monero_native_test import NativeLedger
from node_test import Cluster, require


def main(binary, directory, after_addresses=None):
    os.umask(0o077)
    classpath = os.environ.get('MPC_WALLET_TEST_CLASSPATH')
    require(classpath and os.environ.get('SW_TEST_CUSTODY_DB_URL'), 'fresh wallet Java classpath and isolated local PG18 test database required')
    with tempfile.TemporaryDirectory(prefix='surprising-wallet-mpc-monero-address-') as temporary:
        root = pathlib.Path(temporary)
        with NativeLedger(root, pathlib.Path(directory).resolve()) as ledger:
            signer = root / 'signers'
            signer.mkdir(mode=0o700)
            cluster = Cluster(signer, binary, monero_configs=ledger.configurations)
            try:
                (signer / 'java-probe.json').write_text(json.dumps({'tenantId': cluster.tenant, 'peers': cluster.peers,
                    'daemonRpcUrl': ledger.daemon, 'daemonCredentialFile': ledger.configurations[0]['daemonCredentialFile'], 'genesisHash': ledger.genesis}))
                environment = dict(os.environ, SW_TEST_MPC_DIRECTORY=str(signer))
                previous = None
                for phase in ['first', 'restart']:
                    if phase == 'restart':
                        for index in range(3):
                            cluster.stop(index)
                            ledger.stop('wallet' + str(index))
                        for index in range(3):
                            ledger.restart('wallet' + str(index))
                            cluster.start(index)
                    result = subprocess.run(['java', '-cp', classpath, 'com.surprising.wallet.custody.MpcMoneroAddressNativeProbe', str(signer), phase], env=environment)
                    require(result.returncode == 0, 'actual Java native Monero address publication failed')
                    proof = json.loads((signer / 'monero-address-public.json').read_text())
                    observation = proof['observation']
                    require(observation['request']['operation'] == 'MONERO_OBSERVE'
                        and len(observation['request']['online']) == 2
                        and observation['result']['monero']['address'] == proof['addresses'][0]
                        and observation['result']['monero']['balanceAtomic'] == '0'
                        and observation['result']['monero']['outputs'] == [],
                        'actual Java wallet observation lost its two-holder original account snapshot')
                    if previous:
                        require(proof['rootKeyId'] == previous['rootKeyId'] and proof['addresses'][:3] == previous['addresses'], 'native restart regenerated published identities')
                    for configuration in ledger.configurations:
                        accounts = ledger.call(configuration['walletRpcUrl'], 'get_accounts')['subaddress_accounts']
                        require(len(accounts) == len(proof['addresses']) + 1 and set(proof['addresses']).issubset({account['base_address'] for account in accounts}),
                            'three actual wallets disagree on the registered native custody accounts')
                    previous = proof
                destination = pathlib.Path(os.environ.get('MPC_MONERO_ADDRESS_PUBLIC_SUMMARY', '/tmp/surprising-wallet-mpc-monero-address-public.json'))
                destination.write_text(json.dumps({'scope': 'Actual native Java XMR address/Gas publication and wallet service two-holder snapshot; full outgoing payment/collection business still pending',
                    'passed': True, 'restartedNativeSigners': 3, 'restartedPrivateWallets': 3, 'publicProof': previous}, indent=2))
                print('XMR Java custody native addresses: actual three holders, authenticated daemon, rollback, Gas, wallet two-holder observation and private-wallet/signer restart passed.', flush=True)
                if after_addresses:
                    after_addresses(cluster, ledger, signer, previous, environment)
            finally:
                cluster.close()


if __name__ == '__main__':
    require(len(sys.argv) == 3, 'usage: monero_wallet_address_test.py wallet-mpc-signer MONERO_BIN_DIR')
    main(*sys.argv[1:])
