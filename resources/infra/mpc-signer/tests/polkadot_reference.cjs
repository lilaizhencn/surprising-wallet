'use strict';
// Independent SDK vectors only: fresh ephemeral signing keys are discarded; no wallet shares or administrator key.
const fs=require('node:fs'),crypto=require('node:crypto');
const {ApiPromise,WsProvider}=require('@polkadot/api');
const {encodeAddress,signatureVerify,blake2AsU8a}=require('@polkadot/util-crypto');
const {u8aToHex}=require('@polkadot/util');
async function main() {
 const [descriptor,output]=process.argv.slice(2),config=JSON.parse(fs.readFileSync(descriptor,'utf8'));
 const vectors=[],snapshots=[];
 for(const role of ['RELAY','ASSET_HUB']) {
  const entry=config[role],url=new URL(entry.endpoint);
  if(url.protocol!=='ws:' || url.hostname!=='127.0.0.1')throw Error('independent reference requires isolated loopback node');
  const api=await ApiPromise.create({provider:new WsProvider(entry.endpoint),noInitWarn:true});
  try {
   if(api.genesisHash.toHex()!==entry.genesisHash)throw Error('reference genesis differs');
   const blockHash=await api.rpc.chain.getFinalizedHead(),header=await api.rpc.chain.getHeader(blockHash),blockHeight=header.number.toNumber();
   snapshots.push({role,genesisHash:api.genesisHash.toHex(),specVersion:api.runtimeVersion.specVersion.toString(),transactionVersion:api.runtimeVersion.transactionVersion.toString(),extensions:api.registry.signedExtensions});
   const cases=role==='RELAY'?[{amount:'1000000000000',nonce:0,keepAlive:true},{amount:((1n<<128n)-1n).toString(),nonce:4294967295,keepAlive:false}]
       :[{amount:'1000000000000',nonce:64,keepAlive:true},{assetId:'10001',amount:'1000000',nonce:63,keepAlive:true},{assetId:'4294967295',amount:((1n<<128n)-1n).toString(),nonce:16384,keepAlive:false}];
   for(const c of cases) {
    const pair=crypto.generateKeyPairSync('ed25519'),publicKey=pair.publicKey.export({format:'der',type:'spki'}).subarray(-32),sender=encodeAddress(publicKey,42),recipient=encodeAddress(Buffer.alloc(32,0xcd),42);
    const keep=c.assetId?api.tx.assets.transferKeepAlive:api.tx.balances.transferKeepAlive,death=c.assetId?api.tx.assets.transfer:api.tx.balances.transferAllowDeath;
    const tx=c.assetId?(c.keepAlive?keep:death)(c.assetId,recipient,c.amount):(c.keepAlive?keep:death)(recipient,c.amount);
    const u={network:'localnet',role,genesisHash:api.genesisHash.toHex(),specVersion:api.runtimeVersion.specVersion.toNumber(),transactionVersion:api.runtimeVersion.transactionVersion.toNumber(),sender,publicKeyHex:publicKey.toString('hex'),recipient,amountAtomic:c.amount,assetId:c.assetId??null,nonce:c.nonce,eraPeriod:64,blockHeight,blockHash:blockHash.toHex(),extensions:api.registry.signedExtensions,callIndices:{keepAlive:u8aToHex(keep.callIndex),allowDeath:u8aToHex(death.callIndex)},keepAlive:c.keepAlive};
    const payload={address:sender,blockHash:u.blockHash,blockNumber:blockHeight,era:api.registry.createType('ExtrinsicEra',{current:blockHeight,period:64}).toHex(),genesisHash:u.genesisHash,method:tx.method.toHex(),nonce:c.nonce,specVersion:u.specVersion,transactionVersion:u.transactionVersion,tip:0,assetId:null,mode:0,metadataHash:null,version:4,signedExtensions:u.extensions};
    const encoded=api.registry.createType('ExtrinsicPayload',payload,{version:4}),bytes=encoded.toU8a({method:true}),message=bytes.length>256?blake2AsU8a(bytes):bytes;
    const signature=crypto.sign(null,message,pair.privateKey);
    if(!signatureVerify(message,signature,sender).isValid)throw Error('SDK independently rejected external Ed25519 reference signature');
    tx.addSignature(sender,Buffer.concat([Buffer.from([0]),signature]),payload);
    vectors.push({ss58Addresses:Object.fromEntries([0,42,1000,16383].map(prefix=>[prefix,encodeAddress(publicKey,prefix)])),unsigned:u,callHex:payload.method,payloadHex:u8aToHex(bytes),signingMessageHex:u8aToHex(message),signatureHex:signature.toString('hex'),selected:{extrinsicHex:tx.toHex(),txHash:tx.hash.toHex()}});
   }
  }finally{await api.disconnect();}
 }
 fs.writeFileSync(output,JSON.stringify({provenance:'@polkadot/api16.5.6 and util-crypto14.0.3 against official isolated v1.25.0 relay/AssetHub metadata; ephemeral Node Ed25519 keys discarded.',snapshots,vectors},null,2)+'\n');
 console.log(JSON.stringify({vectors:vectors.length}));
}
main().then(()=>process.exit(0)).catch(error=>{console.error(error.message);process.exitCode=1;process.exit(1);});
