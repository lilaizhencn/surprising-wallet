#!/usr/bin/env python3
"""Actual formal native XMR withdrawal/collection/Gas preparation and original-request recovery."""
import decimal
import json
import os
import pathlib
import secrets
import subprocess
import sys

from monero_wallet_address_test import main as addresses
from node_test import require


def business(cluster, ledger, signer, publication, environment):
    (signer / 'coordinator-aes.bin').write_bytes(secrets.token_bytes(32))
    classpath = environment['MPC_WALLET_TEST_CLASSPATH']
    proofs = []

    def java(phase, pair):
        result = subprocess.run(['java', '-cp', classpath, 'com.surprising.wallet.custody.MpcMoneroBusinessNativeProbe',
            str(signer), phase, str(pair)], env=environment, timeout=240)
        if result.returncode != 0:
            for index in range(3):
                if index in cluster.logs:
                    cluster.logs[index].flush()
                # Node logs contain fixed diagnostics and public session IDs, never RPC bodies or private material.
                print((cluster.root / ('node' + str(index) + '.log')).read_text()[-2400:], file=sys.stderr)
        require(result.returncode == 0, 'actual formal native XMR business failed: ' + phase)
        proof = json.loads((signer / 'monero-business-public.json').read_text())
        proofs.append(proof)
        return proof

    ledger.call(ledger.funder, 'create_wallet', {'filename': 'external-business-funder', 'password': secrets.token_hex(32), 'language': 'English'})
    recipient = ledger.call(ledger.funder, 'get_address')['address']
    for blocks in (4, 80):
        ledger.call(ledger.daemon, 'generateblocks', {'wallet_address': recipient, 'amount_of_blocks': blocks})
    ledger.call(ledger.funder, 'refresh')
    source, collector, gas = publication['addresses'][:3]
    ledger.call(ledger.funder, 'transfer', {'destinations': [{'address': source, 'amount': 5 * 10**12}, {'address': gas, 'amount': 5 * 10**12}]})
    ledger.call(ledger.daemon, 'generateblocks', {'wallet_address': recipient, 'amount_of_blocks': 13})
    case = {'reference': 'xmr-business-baseline', 'source': source, 'recipient': recipient, 'kind': 'WITHDRAWAL'}
    (signer / 'business-case-public.json').write_text(json.dumps(case))
    java('baseline', 0)
    external = 0
    expected_deposits = 2
    selected = []

    def payment(mode, number, amount, target):
        nonlocal external, expected_deposits
        pair = ([0, 1], [0, 2], [1, 2])[number]
        absent = next(index for index in range(3) if index not in pair)
        kind = 'COLLECTION' if mode == 'collection' else 'WITHDRAWAL'
        if mode == 'owned':
            expected_deposits += 1
        fixed = {'reference': 'xmr-business-' + mode + '-' + str(number), 'source': source, 'recipient': target,
            'kind': kind, 'mode': mode, 'amount': format(decimal.Decimal(amount) / 10**12, 'f'), 'expectedDeposits': expected_deposits}
        (signer / 'business-case-public.json').write_text(json.dumps(fixed))
        cluster.stop(absent)
        ledger.stop('wallet' + str(absent))
        try:
            admitted = java('start', number)
            if mode == 'funded':
                require(admitted['selected']['txHash'] is None, 'main native payment signed before original fee funding was unlocked')
                ledger.call(ledger.daemon, 'generateblocks', {'wallet_address': recipient, 'amount_of_blocks': 13})
                admitted = java('funded-resume', number)
            original = admitted['selected']
            require(original['txHash'] and original['selectedAttemptId'], 'accepted native business has no durable original selection')
            # A new coordinator JVM, both original holders and private wallets all restart while the third stays stopped.
            for index in pair:
                cluster.stop(index)
                ledger.stop('wallet' + str(index))
            for index in pair:
                ledger.restart('wallet' + str(index))
                cluster.start(index)
            recovered = java('recover', number)
            require(recovered['selected']['txHash'] == original['txHash']
                and recovered['selected']['selectedAttemptId'] == original['selectedAttemptId'], 'native recovery replaced the original business selection')
            ledger.call(ledger.daemon, 'generateblocks', {'wallet_address': recipient, 'amount_of_blocks': 13})
            confirmed = java('confirm', number)
            require(confirmed['selected']['status'] == 'CONFIRMED' and confirmed['pending'] == 0, 'native payment or its original funding did not settle')
            selected.append({'case': fixed, 'selected': confirmed['selected']})
            if target == recipient:
                external += amount
                ledger.call(ledger.funder, 'refresh')
                incoming = ledger.call(ledger.funder, 'get_transfer_by_txid', {'txid': original['txHash']})['transfer']
                require(incoming['amount'] == amount and incoming['confirmations'] >= 10, 'actual external recipient did not receive original native principal')
            physical = sum(int(item['result']['monero']['balanceAtomic']) for item in confirmed['snapshots'])
            fees = int(confirmed['confirmedFeeAtomic'])
            require(physical == 10 * 10**12 - external - fees, 'original native payments and all actual fees do not conserve physical funds')
            for index in pair:
                actual = ledger.call(ledger.configurations[index]['walletRpcUrl'], 'get_balance', {'all_accounts': True, 'strict': True})
                require(actual['balance'] == physical and actual['unlocked_balance'] == physical, 'independent original native wallet balance differs from Java observation')
            print('XMR formal native ' + mode + ' ' + str(pair) + ': third offline, accepted relay reply lost, coordinator/holders/private wallets restarted, disabled-chain recovery, principal once and exact fees passed.', flush=True)
            return confirmed
        finally:
            ledger.restart('wallet' + str(absent))
            cluster.start(absent)

    funding_only = os.environ.get('MPC_MONERO_BUSINESS_FUNDING_ONLY') == '1'
    if not funding_only:
        for number in range(3):
            payment('withdrawal', number, 10**12, recipient)
            payment('collection', number, 10**11, collector)
        payment('owned', 0, 10**11, collector)
    current = proofs[-1]
    remaining = next(int(item['result']['monero']['balanceAtomic']) for item in current['snapshots'] if item['result']['publicAddress'] == source)
    final = payment('funded', 0, remaining, recipient)
    destination = pathlib.Path(os.environ.get('MPC_MONERO_BUSINESS_PUBLIC_SUMMARY', '/tmp/surprising-wallet-mpc-monero-business-public.json'))
    scope = 'Actual formal native XMR Gas preparation and its original parent withdrawal only.' if funding_only else 'Actual formal native XMR withdrawal/collection/Gas preparation; principal, canonical proof, owned-recipient/internal classification and original-request recovery. Arbitrary mid-native SIGN recovery remains separate.'
    destination.write_text(json.dumps({'scope': scope,
        'passed': True, 'quorums': ['AB'] if funding_only else ['AB', 'AC', 'BC'], 'fundingOnly': funding_only,
        'externalFundedAtomic': str(10 * 10**12), 'externalPaidAtomic': str(external),
        'feeAtomic': final['confirmedFeeAtomic'], 'selected': selected, 'phases': proofs, 'final': final}, indent=2))


if __name__ == '__main__':
    require(len(sys.argv) == 3, 'usage: monero_wallet_business_test.py wallet-mpc-signer MONERO_BIN_DIR')
    addresses(*sys.argv[1:], after_addresses=business)
