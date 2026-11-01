'use strict';
// Public-only reference. Install @mysten/sui 2.38.0 outside the checkout and set NODE_PATH.
const fs = require('node:fs');
async function encode(u, signatureBase64) {
    const {Transaction} = await import(require.resolve('@mysten/sui/transactions'));
    const {messageWithIntent} = await import(require.resolve('@mysten/sui/cryptography'));
    const {Ed25519PublicKey} = await import(require.resolve('@mysten/sui/keypairs/ed25519'));
    const {blake2b} = await import(require.resolve('@noble/hashes/blake2.js'));
    const publicKey = new Ed25519PublicKey(Buffer.from(u.publicKeyHex, 'hex'));
    if (publicKey.toSuiAddress() !== u.sender) throw Error('Sender differs from public joint key');
    const tx = new Transaction();
    tx.setSender(u.sender); tx.setGasOwner(u.sender);
    tx.setGasPayment(u.gasPayment.map(c => ({objectId:c.objectId, version:c.version, digest:c.digest})));
    tx.setGasPrice(BigInt(u.gasPrice)); tx.setGasBudget(BigInt(u.gasBudget));
    if (u.coinType === '0x2::sui::SUI' || u.coinType === '0x'+'0'.repeat(63)+'2::sui::SUI') {
        const recipient = tx.pure.address(u.recipient), amount = tx.pure.u64(BigInt(u.amountAtomic));
        const coin = tx.splitCoins(tx.gas, [amount]);
        tx.transferObjects([coin], recipient);
    } else {
        const coins = u.inputs.map(c => tx.objectRef({objectId:c.objectId,version:c.version,digest:c.digest}));
        const amount = tx.pure.u64(BigInt(u.amountAtomic)), recipient = tx.pure.address(u.recipient);
        if (coins.length > 1) tx.mergeCoins(coins[0], coins.slice(1));
        tx.moveCall({target:'0x2::coin::value',typeArguments:[u.coinType],arguments:[coins[0]]});
        const [part] = tx.splitCoins(coins[0], [amount]);
        tx.transferObjects([part], recipient);
    }
    const bytes = await tx.build();
    const signingHashHex = Buffer.from(blake2b(messageWithIntent('TransactionData',bytes),{dkLen:32})).toString('hex');
    const result = {...u, signingHashHex,txBytesBase64:Buffer.from(bytes).toString('base64'),digest:await tx.getDigest()};
    if (signatureBase64) {
        if (!await publicKey.verifyTransaction(bytes,signatureBase64)) throw Error('Invalid public Sui Ed25519 witness');
        result.signatureBase64 = signatureBase64;
    }
    return result;
}
module.exports={encode};
if (require.main===module) (async()=>{
    const input=JSON.parse(fs.readFileSync(process.argv[2]));
    const vectors=[];for(const value of input.vectors)vectors.push(await encode(value,value.signatureBase64));
    console.log(JSON.stringify({reference:'@mysten/sui 2.38.0; public-only PTB and Ed25519 verification',vectors},null,2));
})().catch(error=>{console.error(error.message);process.exitCode=1;});
