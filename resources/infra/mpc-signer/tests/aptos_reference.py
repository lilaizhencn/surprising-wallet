#!/usr/bin/env python3
"""Recompute public golden vectors with official aptos-sdk==0.11.0; consumes no private key."""
import hashlib
import importlib.metadata
import json
import pathlib
import sys
from aptos_sdk.ed25519 import PublicKey,Signature
from aptos_sdk.account_address import AccountAddress
from aptos_sdk.transactions import EntryFunction,TransactionArgument,TransactionPayload,RawTransaction,SignedTransaction
from aptos_sdk.type_tag import StructTag,TypeTag
from aptos_sdk.bcs import Serializer
from aptos_sdk.authenticator import Authenticator,Ed25519Authenticator

if importlib.metadata.version('aptos-sdk') != '0.11.0':
    raise SystemExit('Golden vectors require official aptos-sdk==0.11.0')
source=pathlib.Path(sys.argv[1]) if len(sys.argv)>1 else pathlib.Path(__file__).resolve().parents[4]/'resources/test-fixtures/mpc/aptos-codec-reference.json'
fixture=json.loads(source.read_text())
vectors=[]
for v in fixture['vectors']:
    sender=AccountAddress.from_str_relaxed(v['sender']);recipient=AccountAddress.from_str_relaxed(v['recipient']);metadata=v['metadata']
    types=[];args=[];module='0x1::aptos_account'
    if metadata:
        module='0x1::primary_fungible_store';types=[TypeTag(StructTag.from_str('0x1::fungible_asset::Metadata'))]
        args.append(TransactionArgument(AccountAddress.from_str_relaxed(metadata),Serializer.struct))
    args.extend([TransactionArgument(recipient,Serializer.struct),TransactionArgument(v['amountAtomic'],Serializer.u64)])
    raw=RawTransaction(sender,v['sequence'],TransactionPayload(EntryFunction.natural(module,'transfer',types,args)),v['maxGasAmount'],v['gasUnitPrice'],v['expiration'],v['chainId'])
    serializer=Serializer();raw.serialize(serializer)
    public=PublicKey.from_str(v['publicKeyHex']);signature=Signature.from_str(v['signatureHex'])
    assert AccountAddress.from_key(public)==sender
    signed=SignedTransaction(raw,Authenticator(Ed25519Authenticator(public,signature)));assert signed.verify()
    digest=hashlib.sha3_256(hashlib.sha3_256(b'APTOS::Transaction').digest()+b'\x00'+signed.bytes()).hexdigest()
    vectors.append({**v,'rawHex':serializer.output().hex(),'signingHex':raw.keyed().hex(),'signedHex':signed.bytes().hex(),'txId':'0x'+digest})
result={'reference':'official aptos-sdk==0.11.0','vectors':vectors,
        'primaryStore':'0x'+hashlib.sha3_256(bytes.fromhex(vectors[0]['sender'][2:])+bytes.fromhex('cafe'.zfill(64))+b'\xfc').hexdigest()}
json.dump(result,sys.stdout,indent=2);sys.stdout.write('\n')
