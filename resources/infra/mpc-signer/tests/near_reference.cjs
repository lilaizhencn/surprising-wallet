'use strict';
// Public-only vectors. Install near-api-js 7.4.0 outside the checkout and set NODE_PATH.
const fs=require('node:fs'),crypto=require('node:crypto'),sdk=require('near-api-js');
function encode(u,signatureHex) {
    const publicKey=sdk.PublicKey.fromString('ed25519:'+sdk.baseEncode(Buffer.from(u.publicKeyHex,'hex')));
    if(Buffer.from(publicKey.data).toString('hex')!==u.sender)throw Error('NEAR sender differs from joint public key');
    const args={receiver_id:u.recipient,amount:String(u.amountAtomic)};if(u.memo!==null)args.memo=u.memo;
    const action=u.tokenContract===null?sdk.actions.transfer(BigInt(u.amountAtomic))
        :sdk.actions.functionCall('ft_transfer',Buffer.from(JSON.stringify(args)),BigInt(u.gas),1n);
    const transaction=sdk.createTransaction(u.sender,publicKey,u.tokenContract===null?u.recipient:u.tokenContract,BigInt(u.nonce),[action],sdk.baseDecode(u.blockHash));
    const bytes=Buffer.from(sdk.encodeTransaction(transaction)),digest=crypto.createHash('sha256').update(bytes).digest();
    const result={unsigned:u,txBytesBase64:bytes.toString('base64'),signingHashHex:digest.toString('hex'),txHash:sdk.baseEncode(digest)};
    if(signatureHex) {
        const key=crypto.createPublicKey({key:Buffer.concat([Buffer.from('302a300506032b6570032100','hex'),Buffer.from(u.publicKeyHex,'hex')]),format:'der',type:'spki'});
        if(!crypto.verify(null,digest,key,Buffer.from(signatureHex,'hex')))throw Error('NEAR public Ed25519 witness differs');
        const signed=new sdk.SignedTransaction({transaction,signature:new sdk.Signature({keyType:0,data:Buffer.from(signatureHex,'hex')})});
        result.signatureHex=signatureHex;result.signedTransactionBase64=Buffer.from(sdk.encodeTransaction(signed)).toString('base64');
    }
    return result;
}
module.exports={encode};
if(require.main===module) {
    const input=JSON.parse(fs.readFileSync(process.argv[2],'utf8'));
    for(const expected of input.vectors) {
        const actual=encode(expected.unsigned,expected.signatureHex);
        for(const field of ['txBytesBase64','signingHashHex','txHash','signedTransactionBase64'])if(actual[field]!==expected[field])throw Error('NEAR independent SDK mismatch: '+field);
    }
    console.log('NEAR independent SDK public vectors passed: '+input.vectors.length);
}
