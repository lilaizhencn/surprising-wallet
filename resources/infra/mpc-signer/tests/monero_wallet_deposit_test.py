#!/usr/bin/env python3
"""Actual native XMR custody deposits/locks/Gas/reorg; existing isolated local PG18 only."""
import json
import os
import pathlib
import secrets
import subprocess
import sys

from monero_wallet_address_test import main as addresses
from node_test import require


def deposits(cluster, ledger, signer, publication, environment):
    classpath = environment['MPC_WALLET_TEST_CLASSPATH']
    proofs = []

    def java(phase, pair):
        result = subprocess.run(['java', '-cp', classpath, 'com.surprising.wallet.custody.MpcMoneroDepositNativeProbe',
            str(signer), phase, str(pair)], env=environment, timeout=180)
        require(result.returncode == 0, 'actual Java native custody deposit phase failed: ' + phase)
        proof = json.loads((signer / 'monero-deposit-public.json').read_text())
        proofs.append(proof)
        return proof

    def actual_balance(pair, expected):
        for index in pair:
            balance = ledger.call(ledger.configurations[index]['walletRpcUrl'], 'get_balance', {'all_accounts': True, 'strict': True})
            require(balance['balance'] == expected and balance['unlocked_balance'] == expected,
                'actual private wallet principal differs from Java booked funds')

    ledger.call(ledger.funder, 'create_wallet', {'filename': 'external-deposit-funder', 'password': secrets.token_hex(32), 'language': 'English'})
    funder = ledger.call(ledger.funder, 'get_address')['address']
    for count in (4, 80):
        ledger.call(ledger.daemon, 'generateblocks', {'wallet_address': funder, 'amount_of_blocks': count})
    ledger.call(ledger.funder, 'refresh')
    published = publication['addresses']
    destinations = [
        [{'address': published[0], 'amount': 10**12}],
        [{'address': published[0], 'amount': 5 * 10**11}, {'address': published[1], 'amount': 5 * 10**11}],
        [{'address': published[2], 'amount': 10**12}],
    ]
    last = None
    for number, pair in enumerate(([0, 1], [0, 2], [1, 2])):
        ledger.call(ledger.funder, 'refresh')
        transfer = ledger.call(ledger.funder, 'transfer', {'destinations': destinations[number]})
        ledger.call(ledger.daemon, 'generateblocks', {'wallet_address': funder, 'amount_of_blocks': 1})
        height = ledger.call(ledger.daemon, 'get_info')['height'] - 1
        block = ledger.call(ledger.daemon, 'get_block', {'height': height})
        require(transfer['tx_hash'] in block.get('tx_hashes', []), 'external funding was not included in the original mined block')
        case = {'index': number, 'transactionHash': transfer['tx_hash'], 'outputs': destinations[number],
            'previousFundedAtomic': str(number * 10**12), 'fundedAtomic': str((number + 1) * 10**12),
            'blockHeight': height, 'blockHash': block['block_header']['hash']}
        (signer / 'deposit-case-public.json').write_text(json.dumps(case))
        absent = next(index for index in range(3) if index not in pair)
        cluster.stop(absent)
        ledger.stop('wallet' + str(absent))
        try:
            java('locked', number)
            ledger.call(ledger.daemon, 'generateblocks', {'wallet_address': funder, 'amount_of_blocks': 12})
            java('unlock', number)
            actual_balance(pair, (number + 1) * 10**12)
            print('XMR actual native deposit pair ' + str(pair) + ': offline third, real immature output, original-position credit, suspended/disabled maintenance and physical/booked conservation passed.', flush=True)
        finally:
            ledger.restart('wallet' + str(absent))
            cluster.start(absent)
        last = case

    # Replace the final funding block and its maturity blocks only in this owned, offline daemon.
    ledger.call(ledger.funder, 'close_wallet')
    popped = ledger.post(ledger.daemon, '/pop_blocks', {'nblocks': 13})
    require(popped.get('status') == 'OK' and ledger.call(ledger.daemon, 'get_info')['height'] == last['blockHeight'],
        'owned Monero test daemon did not roll back the exact Gas funding branch')
    ledger.call(ledger.daemon, 'flush_txpool', {'txids': []})
    ledger.call(ledger.daemon, 'generateblocks', {'wallet_address': funder, 'amount_of_blocks': 14, 'starting_nonce': 1000})
    replacement = ledger.call(ledger.daemon, 'get_block_header_by_height', {'height': last['blockHeight']})['block_header']
    require(replacement['hash'] != last['blockHash'], 'actual native reorg retained the original funding block')
    absent = 2
    cluster.stop(absent)
    ledger.stop('wallet' + str(absent))
    try:
        final = java('reorg', 0)
        actual_balance([0, 1], 2 * 10**12)
    finally:
        ledger.restart('wallet' + str(absent))
        cluster.start(absent)
    destination = pathlib.Path(os.environ.get('MPC_MONERO_DEPOSIT_PUBLIC_SUMMARY', '/tmp/surprising-wallet-mpc-monero-deposit-public.json'))
    destination.write_text(json.dumps({'scope': 'Actual Java native custody deposit/Gas/lock/reorg business; native outgoing payment Java cutover remains pending',
        'passed': True, 'quorums': ['AB', 'AC', 'BC'], 'externalFundedAtomic': str(3 * 10**12),
        'canonicalRemainingAtomic': str(2 * 10**12), 'originalGasOrphanedAtomic': str(10**12), 'phases': proofs,
        'final': final}, indent=2))
    print('XMR actual native custody deposits: all three pairs, multi-recipient original output positions, Gas funding, rollback/replay and real alternative-chain reorg passed.', flush=True)


if __name__ == '__main__':
    require(len(sys.argv) == 3, 'usage: monero_wallet_deposit_test.py wallet-mpc-signer MONERO_BIN_DIR')
    addresses(*sys.argv[1:], after_addresses=deposits)
