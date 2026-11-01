#!/usr/bin/env python3
"""Resume existing funded testnet identities; never generate replacement keys or remove their stores."""
import json
import os
import pathlib
import re
import subprocess
import sys

from node_test import Cluster, require


def main(binary, directory, recipient):
    os.umask(0o077)
    root = pathlib.Path(directory).resolve(strict=True)
    meta = json.loads((root / 'acceptance-public.json').read_text())
    require(meta['network'] == 'hyperliquid-testnet' and meta['endpoint'] == 'https://api.hyperliquid-testnet.xyz',
        'funded acceptance must use the official testnet')
    require(set(meta['keys']) == {'source', 'collector'}, 'existing funded source and collector are required')
    classpath = os.environ.get('MPC_WALLET_TEST_CLASSPATH')
    phase = os.environ.get('MPC_HYPERCORE_FUNDED_PHASE', 'baseline')
    require(re.fullmatch(r'[a-z0-9][a-z0-9-]{0,47}', os.environ.get('MPC_HYPERCORE_FUNDED_RUN_ID', '')),
        'explicit stable funded test run ID required; retries must retain the same ID')
    require(os.environ.get('MPC_HYPERCORE_FUNDED_PAYER_ROLE') in {'source', 'collector'},
        'select one of the two original funded owners')
    require(phase in {'baseline', 'recovery', 'collection', 'owned-withdrawal', 'procurement', 'hype-withdrawal', 'hype-collection', 'hype-owned-withdrawal'}, 'unknown funded acceptance phase')
    require(classpath and os.environ.get('SW_TEST_CUSTODY_DB_URL'), 'fresh Java test classpath and existing local PG18 test database are required')
    configurations = [json.loads((root / ('node' + str(index) + '.json')).read_text()) for index in range(3)]
    require(all(configuration['peers'] == meta['peers'] for configuration in configurations), 'persistent peer identities changed')
    cluster = Cluster.__new__(Cluster)
    cluster.root, cluster.binary = root, str(pathlib.Path(binary).resolve(strict=True))
    cluster.processes, cluster.logs = {}, {}
    cluster.peers = meta['peers']
    cluster.identities = [peer['identity'] for peer in cluster.peers]
    cluster.ports = [peer['port'] for peer in cluster.peers]
    cluster.tenant = meta['tenantId']
    cluster.client_id = next(iter(configurations[0]['clients']))
    cluster.context = cluster.client_context('client')
    try:
        for round_index, online in enumerate(([0, 1], [0, 2], [1, 2])):
            if phase == 'procurement' and round_index:
                break
            for index in list(cluster.processes):
                if index not in online:
                    cluster.stop(index)
            for index in online:
                if index not in cluster.processes:
                    cluster.start(index)
            if phase == 'procurement':
                from hypercore_test_funding import buy_hype
                buy_hype(cluster, meta)
            result = subprocess.run(['java', '-cp', classpath,
                'com.surprising.wallet.custody.MpcHyperCoreFundedNativeProbe', str(root), recipient, str(round_index)])
            require(result.returncode == 0, 'funded testnet business phase failed; preserve its database and original native stores for recovery')
        if phase == 'procurement':
            print('HyperCore test HYPE procurement and original fill accounting passed with the existing AB quorum; persistent identities retained.', flush=True)
        else:
            asset = 'HYPE' if phase.startswith('hype-') else 'USDC'
            print(f'HyperCore funded {asset} {phase} AB/AC/BC business phases passed with the third native signer offline; persistent identities retained.', flush=True)
    finally:
        cluster.close()


if __name__ == '__main__':
    require(len(sys.argv) == 4, 'usage: hypercore_funded_test.py native-binary existing-acceptance-directory activated-testnet-recipient')
    main(*sys.argv[1:])
