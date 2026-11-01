'use strict';
// Public-only reference. Install @ton/core 0.63.1 and @ton/ton 16.2.2 outside the repository.
const fs = require('node:fs');
const crypto = require('node:crypto');
const {Address, beginCell, internal, external, storeMessage, storeMessageRelaxed} = require('@ton/core');
const {WalletContractV4} = require('@ton/ton');

function encode(u, signatureHex) {
    const walletId = ({mainnet:698983191, testnet:2846466839, regtest:1772725015})[u.network];
    if (walletId === undefined) throw Error('Unsupported network');
    const publicKey = Buffer.from(u.publicKeyHex, 'hex');
    const wallet = WalletContractV4.create({workchain:0, publicKey, walletId});
    if (!wallet.address.equals(Address.parse(u.sender))) throw Error('Sender differs from public StateInit');
    let payload = beginCell().storeUint(0,32).storeStringTail(u.memo).endCell();
    const jetton = u.jettonMaster !== null;
    if (jetton) payload = beginCell().storeUint(0xf8a7ea5,32).storeUint(BigInt(u.queryId),64)
        .storeCoins(BigInt(u.amountAtomic)).storeAddress(Address.parse(u.recipient))
        .storeAddress(wallet.address).storeBit(false).storeCoins(10000000n)
        .storeBit(true).storeRef(payload).endCell();
    const outgoing = internal({to:jetton?u.sourceJettonWallet:u.recipient,
        value:jetton?70000000n:BigInt(u.amountAtomic),bounce:jetton,body:payload});
    const body = beginCell().storeUint(walletId,32).storeUint(u.validUntil,32).storeUint(u.sequence,32)
        .storeUint(0,8).storeUint(3,8).storeRef(beginCell().store(storeMessageRelaxed(outgoing))).endCell();
    const result = {...u, amountAtomic:String(u.amountAtomic),walletId,
        bodyHashHex:body.hash().toString('hex'),bodyBocBase64:body.toBoc({idx:false,crc32:false}).toString('base64')};
    if (signatureHex) {
        const signature = Buffer.from(signatureHex,'hex');
        const key = crypto.createPublicKey({format:'der',type:'spki',key:Buffer.concat([Buffer.from('302a300506032b6570032100','hex'),publicKey])});
        if (!crypto.verify(null,body.hash(),key,signature)) throw Error('Invalid public Ed25519 witness');
        const message = external({to:wallet.address,init:u.sequence===0?wallet.init:undefined,
            body:beginCell().storeBuffer(signature).storeSlice(body.beginParse()).endCell()});
        const cell = beginCell().store(storeMessage(message)).endCell();
        Object.assign(result,{signatureHex,bocBase64:cell.toBoc({idx:false,crc32:false}).toString('base64'),messageHashHex:cell.hash().toString('hex')});
    }
    return result;
}
module.exports={encode};
if (require.main===module) {
    const input=JSON.parse(fs.readFileSync(process.argv[2]));
    console.log(JSON.stringify({reference:'@ton/core 0.63.1 + @ton/ton 16.2.2; public-only Ed25519 verification',
        vectors:input.vectors.map(v=>encode(v,v.signatureHex))},null,2));
}
