"""Optional test-only HYPE procurement; capped mock USDC, original MPC order and real fill proof.

Requires the official hyperliquid-python-sdk 0.24.0 in the acceptance interpreter.
This module never reconstructs a private key or changes production exchange capabilities.
"""
import json
import os
import re
import time
from decimal import Decimal, ROUND_CEILING

from node_test import require


def buy_hype(cluster, meta):
    import requests
    from eth_account.messages import encode_typed_data
    from eth_keys import keys
    from eth_utils import keccak
    from hyperliquid.utils.signing import action_hash, construct_phantom_agent, l1_payload

    endpoint = 'https://api.hyperliquid-testnet.xyz'
    require(meta['endpoint'] == endpoint and meta['network'] == 'hyperliquid-testnet', 'test-only funding endpoint required')
    owner = meta['keys']['collector']['address'].lower()
    public = meta['keys']['collector']['publicKeyHex']
    budget, size = Decimal('15'), Decimal('0.07')

    def save(name, value):
        target = cluster.root / name
        temporary = target.with_suffix('.tmp')
        with open(temporary, 'w', encoding='utf-8') as output:
            json.dump(value, output, separators=(',', ':'))
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, target)

    def post(path, body):
        result = requests.post(endpoint + path, json=body, timeout=30)
        result.raise_for_status()
        return result.json()

    plan_file = cluster.root / 'test-hype-order-plan-public.json'
    spot = post('/info', {'type': 'spotMeta'})
    hype = [token for token in spot['tokens'] if token['name'] == 'HYPE']
    usdc = [token for token in spot['tokens'] if token['name'] == 'USDC']
    require(len(hype) == len(usdc) == 1 and hype[0]['tokenId'] == '0x7317beb7cceed72ef0b346074cc8e7ab', 'unexpected test HYPE identity')
    pairs = [pair for pair in spot['universe'] if pair['tokens'] == [hype[0]['index'], usdc[0]['index']]]
    require(len(pairs) == 1, 'test HYPE/USDC pair is ambiguous')
    pair = pairs[0]
    if plan_file.exists():
        plan = json.loads(plan_file.read_text())
    else:
        book = post('/info', {'type': 'l2Book', 'coin': pair['name']})
        ask = Decimal(book['levels'][1][0]['px'])
        limit = (ask * Decimal('1.02')).quantize(Decimal('0.01'), rounding=ROUND_CEILING)
        require(Decimal('10') <= ask * size and limit * size < budget, 'test procurement exceeds the mock USDC budget or order minimum')
        state = post('/info', {'type': 'spotClearinghouseState', 'user': owner})
        available = {balance['coin']: Decimal(balance['total']) - Decimal(balance['hold']) for balance in state['balances']}
        require(available.get('USDC', Decimal(0)) >= budget and available.get('HYPE', Decimal(0)) == 0, 'unexpected original test funding balances')
        cloid = '0x' + keccak(text='surprising-wallet:test-hype-procurement:v1:' + owner).hex()[:32]
        action = {'type': 'order', 'orders': [{'a': 10000 + pair['index'], 'b': True, 'p': str(limit), 's': str(size),
                  'r': False, 't': {'limit': {'tif': 'Ioc'}}, 'c': cloid}], 'grouping': 'na'}
        plan = {'network': meta['network'], 'endpoint': endpoint, 'owner': owner, 'keyId': meta['keys']['collector']['keyId'],
                'pair': pair['name'], 'cloid': cloid, 'nonce': int(time.time() * 1000), 'action': action, 'maxMockUsdc': str(budget)}
        save(plan_file.name, plan)
    require(plan['network'] == meta['network'] and plan['endpoint'] == endpoint and plan['owner'] == owner
            and plan['keyId'] == meta['keys']['collector']['keyId'] and plan['maxMockUsdc'] == '15', 'original test funding plan changed')
    action, nonce = plan['action'], plan['nonce']
    order = action['orders'][0]
    require(action['type'] == 'order' and len(action['orders']) == 1 and action['grouping'] == 'na'
            and plan['pair'] == pair['name'] and order['a'] == 10000 + pair['index']
            and order['b'] is True and order['r'] is False and order['t'] == {'limit': {'tif': 'Ioc'}}
            and order['s'] == str(size) and order['c'] == plan['cloid'] and Decimal(order['p']) * size < budget,
            'original test order exceeds its fixed scope')
    typed = encode_typed_data(full_message=l1_payload(construct_phantom_agent(action_hash(action, None, nonce, None), False)))
    digest = keccak(b'\x19' + typed.version + typed.header + typed.body)
    session_file = cluster.root / 'test-hype-order-native-session.json'
    if session_file.exists():
        session = json.loads(session_file.read_text())
    else:
        session = cluster.descriptor('ECDSA_SECP256K1', plan['keyId'], 'SIGN', [0, 1], digest.hex())
        save(session_file.name, session)
    require(session['keyId'] == plan['keyId'] and session['messageHex'] == digest.hex()
            and session['tenantId'] == meta['tenantId'] and session['online'] == cluster.identities[:2], 'original native funding session changed')
    signed = cluster.run(session, [0, 1])
    signature = bytes.fromhex(signed[0]['signatureHex'])
    require(signature[0] == 0x30 and signature[1] == len(signature) - 2 and signature[2] == 2, 'native DER signature differs')
    r_length = signature[3]
    r = int.from_bytes(signature[4:4 + r_length], 'big')
    offset = 4 + r_length
    require(signature[offset] == 2 and offset + 2 + signature[offset + 1] == len(signature), 'invalid native DER scalar layout')
    s = int.from_bytes(signature[offset + 2:], 'big')
    curve_order = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141
    s = min(s, curve_order - s)
    expected = keys.PublicKey.from_compressed_bytes(bytes.fromhex(public))
    candidates = [keys.Signature(vrs=(v, r, s)) for v in (0, 1)]
    matching = [value for value in candidates if value.recover_public_key_from_msg_hash(digest) == expected]
    require(len(matching) == 1 and expected.to_checksum_address().lower() == owner, 'MPC funding signature recovered another owner')
    body = {'action': action, 'nonce': nonce, 'signature': {'r': '0x' + format(r, '064x'), 's': '0x' + format(s, '064x'), 'v': matching[0].v + 27}}
    save('test-hype-order-selected-body-public.json', body)
    status = post('/info', {'type': 'orderStatus', 'user': owner, 'oid': plan['cloid']})
    if status.get('status') == 'unknownOid':
        try:
            response = post('/exchange', body)
            save('test-hype-order-response-public.json', response)
        except requests.RequestException as error:
            save('test-hype-order-unknown-public.json', {'exception': type(error).__name__})
        status = post('/info', {'type': 'orderStatus', 'user': owner, 'oid': plan['cloid']})
    save('test-hype-order-status-public.json', status)
    require(status.get('status') == 'order', 'original test order remains unknown; preserve its nonce and session')
    observed = status['order']['order']
    require(observed['cloid'] == plan['cloid'] and observed['coin'] == plan['pair'] and observed['side'] == 'B'
            and Decimal(observed['origSz']) == size and Decimal(observed['limitPx']) == Decimal(order['p']), 'real test order differs from original signed order')
    require(status['order']['status'] in {'filled', 'canceled', 'iocCancelRejected'}, 'test IOC order is not terminal')
    fills = post('/info', {'type': 'userFillsByTime', 'user': owner, 'startTime': max(0, nonce - 60000), 'aggregateByTime': False})
    fills = [fill for fill in fills if fill['oid'] == observed['oid']]
    save('test-hype-order-fills-public.json', fills)
    require(fills and len({fill['tid'] for fill in fills}) == len(fills), 'original test order has no unique fill proof')
    quantity = quote = base_fee = quote_fee = Decimal(0)
    for fill in fills:
        price, amount, fee = Decimal(fill['px']), Decimal(fill['sz']), Decimal(fill['fee'])
        require(fill['coin'] == plan['pair'] and fill['side'] == 'B' and Decimal(0) < price <= Decimal(order['p'])
                and amount > 0 and fee >= 0 and fill['feeToken'] in {'HYPE', 'USDC'}
                and re.fullmatch('0x[0-9a-f]{64}', fill['hash']), 'real fill asset, amount, price, fee or identity differs')
        quantity += amount
        quote += price * amount
        if fill['feeToken'] == 'HYPE':
            base_fee += fee
        else:
            quote_fee += fee
    require(0 < quantity <= size and quote + quote_fee <= budget and quantity > base_fee, 'real funding fill exceeds its cap')
    proof = dict(plan=plan, orderStatus=status, fills=fills, mockUsdcSpent=str(quote + quote_fee), hypeAcquired=str(quantity - base_fee))
    save('test-hype-procurement-public.json', proof)
    return proof
