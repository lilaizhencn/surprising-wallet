#!/usr/bin/env python3
"""Actual durable Java native signing/relay bridge; full withdrawal/collection business remains separate."""
import json
import os
import pathlib
import secrets
import subprocess
import sys

from monero_wallet_address_test import main as addresses
from node_test import require


def payments(cluster, ledger, signer, publication, environment):
    (signer / 'coordinator-aes.bin').write_bytes(secrets.token_bytes(32))
    classpath = environment['MPC_WALLET_TEST_CLASSPATH']

    def java(phase, pair):
        result = subprocess.run(['java', '-cp', classpath, 'com.surprising.wallet.custody.MpcMoneroPaymentNativeProbe', str(signer), phase, str(pair)],
            env=environment, timeout=180)
        require(result.returncode == 0, 'actual Java native payment bridge failed: ' + phase)

    ledger.call(ledger.funder, 'create_wallet', {'filename': 'external-payment-funder', 'password': secrets.token_hex(32), 'language': 'English'})
    recipient = ledger.call(ledger.funder, 'get_address')['address']
    for blocks in (4, 80):
        ledger.call(ledger.daemon, 'generateblocks', {'wallet_address': recipient, 'amount_of_blocks': blocks})
    ledger.call(ledger.funder, 'refresh')
    source = publication['addresses'][0]
    ledger.call(ledger.funder, 'transfer', {'destinations': [{'address': source, 'amount': 5 * 10**12}]})
    ledger.call(ledger.daemon, 'generateblocks', {'wallet_address': recipient, 'amount_of_blocks': 12})
    proofs = []
    for number, pair in enumerate(([0, 1], [0, 2], [1, 2])):
        (signer / 'payment-case-public.json').write_text(json.dumps({'source': source, 'recipient': recipient}))
        absent = next(index for index in range(3) if index not in pair)
        cluster.stop(absent)
        ledger.stop('wallet' + str(absent))
        try:
            if number == 0:
                java('admit', number)
                original = json.loads((signer / 'admitted-sign-request-public.json').read_text())
                completed = cluster.run(original, pair, timeout_seconds=90)
                require(completed[0]['signatureHex'] == completed[1]['signatureHex'], 'uncollected native results disagree')
                artifact = json.loads(bytes.fromhex(completed[0]['signatureHex']))
                for index in pair:
                    cluster.stop(index)
                    ledger.stop('wallet' + str(index))
                for index in pair:
                    ledger.restart('wallet' + str(index))
                    cluster.start(index)
                java('select', number)
                recovered = json.loads((signer / 'selected-payment-0-public.json').read_text())
                require(recovered['recoveredUncollectedResult'] and recovered['selected']['signSessionId'] == original['sessionId']
                    and recovered['selected']['transactionHash'] == artifact['transactionHash'],
                    'coordinator result recovery changed the original native signing session or transaction')
            else:
                java('sign', number)
            proof = json.loads((signer / ('selected-payment-' + str(number) + '-public.json')).read_text())
            tx_hash = proof['selected']['transactionHash']
            require(ledger.post(ledger.daemon, '/get_transactions', {'txs_hashes': [tx_hash], 'decode_as_json': False})['missed_tx'] == [tx_hash],
                'Java signing or a rejected replacement hash broadcast before explicit original relay')
            for index in pair:
                cluster.stop(index)
                ledger.stop('wallet' + str(index))
            # The first original holder is offline too; only the second survives to broadcast the completed transaction.
            survivor = pair[1]
            ledger.restart('wallet' + str(survivor))
            cluster.start(survivor)
            java('relay', number)
            ledger.restart('wallet' + str(pair[0]))
            cluster.start(pair[0])
            ledger.call(ledger.daemon, 'generateblocks', {'wallet_address': recipient, 'amount_of_blocks': 12})
            java('observe', number)
            ledger.call(ledger.funder, 'refresh')
            incoming = ledger.call(ledger.funder, 'get_transfer_by_txid', {'txid': tx_hash})['transfer']
            require(incoming['amount'] == 10**12 and incoming['confirmations'] >= 10, 'actual recipient did not receive the fixed native payment')
            cluster.stop(survivor)
            ledger.stop('wallet' + str(survivor))
            java('relay', number)
            ledger.restart('wallet' + str(survivor))
            cluster.start(survivor)
            proofs.append(proof)
            print('XMR Java payment bridge ' + str(pair) + ': durable two-party signature, coordinator/signer/private-wallet restart, one original surviving holder, same-hash relay replay and exact fees passed.', flush=True)
        finally:
            ledger.restart('wallet' + str(absent))
            cluster.start(absent)
    fees = sum(int(proof['selected']['feeAtomic']) for proof in proofs)
    destination = pathlib.Path(os.environ.get('MPC_MONERO_PAYMENT_PUBLIC_SUMMARY', '/tmp/surprising-wallet-mpc-monero-payment-public.json'))
    destination.write_text(json.dumps({'scope': 'Actual Java durable native SIGN selection and original-holder relay bridge; formal withdrawal/collection/Gas business still pending',
        'passed': True, 'quorums': ['AB', 'AC', 'BC'], 'coordinatorResultLossRecovered': True, 'fundedAtomic': str(5 * 10**12), 'paidAtomic': str(3 * 10**12),
        'feeAtomic': str(fees), 'remainingAtomic': str(2 * 10**12 - fees), 'selections': proofs}, indent=2))


if __name__ == '__main__':
    require(len(sys.argv) == 3, 'usage: monero_wallet_payment_test.py wallet-mpc-signer MONERO_BIN_DIR')
    addresses(*sys.argv[1:], after_addresses=payments)
