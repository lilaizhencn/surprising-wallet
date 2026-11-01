'use strict';
// Public mainnet metadata read; ephemeral independent reference keys are discarded. No transaction is submitted.
const fs=require('node:fs'),crypto=require('node:crypto');
const {ApiPromise,WsProvider}=require('@polkadot/api');
const {encodeAddress,signatureVerify,blake2AsU8a}=require('@polkadot/util-crypto');
const {u8aToHex}=require('@polkadot/util');
const provider=new WsProvider('wss://polkadot-asset-hub-rpc.polkadot.io',1000);
const timer=setTimeout(()=>{console.error('Public metadata capture timed out');provider.disconnect().finally(()=>process.exit(2));},30000);
(async()=>{
 const api=await ApiPromise.create({provider,noInitWarn:true});
 try {
  if(api.genesisHash.toHex()!=='0x68d56f15f85d3136970ec16946040bc1752654e906147f7e43e9d539d7c3de2f')throw Error('Public Polkadot Hub genesis differs');
  const metadata=api.runtimeMetadata.asLatest.extrinsic.toJSON(),indices=metadata.transactionExtensionsByVersion['0'];
  const extensions=indices.map(index=>metadata.transactionExtensions[index].identifier);
  // This SDK instance is isolated in this process; the production gateway never mutates a shared registry.
  api.registry.setSignedExtensions(extensions);
  const hash=await api.rpc.chain.getFinalizedHead(),header=await api.rpc.chain.getHeader(hash),height=header.number.toNumber(),vectors=[];
  for(const assetId of [null,'1337']){
   const pair=crypto.generateKeyPairSync('ed25519'),pub=pair.publicKey.export({format:'der',type:'spki'}).subarray(-32),sender=encodeAddress(pub,0),recipient=encodeAddress(Buffer.alloc(32,0xcd),0);
   const keep=assetId?api.tx.assets.transferKeepAlive:api.tx.balances.transferKeepAlive,death=assetId?api.tx.assets.transfer:api.tx.balances.transferAllowDeath,keepAlive=assetId===null;
   const tx=assetId?death(assetId,recipient,'1'):keep(recipient,'1');
   const u={network:'mainnet',role:'ASSET_HUB',genesisHash:api.genesisHash.toHex(),specVersion:api.runtimeVersion.specVersion.toNumber(),transactionVersion:api.runtimeVersion.transactionVersion.toNumber(),sender,publicKeyHex:pub.toString('hex'),recipient,amountAtomic:'1',assetId,nonce:0,eraPeriod:64,blockHeight:height,blockHash:hash.toHex(),extensions,callIndices:{keepAlive:u8aToHex(keep.callIndex),allowDeath:u8aToHex(death.callIndex)},keepAlive};
   const payload={address:sender,blockHash:u.blockHash,blockNumber:height,era:api.registry.createType('ExtrinsicEra',{current:height,period:64}).toHex(),genesisHash:u.genesisHash,method:tx.method.toHex(),nonce:0,specVersion:u.specVersion,transactionVersion:u.transactionVersion,tip:0,assetId:null,mode:0,metadataHash:null,version:4,signedExtensions:extensions};
   const bytes=api.registry.createType('ExtrinsicPayload',payload,{version:4}).toU8a({method:true}),message=bytes.length>256?blake2AsU8a(bytes):bytes,signature=crypto.sign(null,message,pair.privateKey);
   if(!signatureVerify(message,signature,sender).isValid)throw Error('SDK rejected independent external Ed25519 witness');
   tx.addSignature(sender,Buffer.concat([Buffer.from([0]),signature]),payload);
   vectors.push({unsigned:u,callHex:payload.method,payloadHex:u8aToHex(bytes),signingMessageHex:u8aToHex(message),signatureHex:signature.toString('hex'),selected:{extrinsicHex:tx.toHex(),txHash:tx.hash.toHex()}});
  }
  fs.writeFileSync(process.argv[2],JSON.stringify({provenance:'Public live Polkadot Hub metadata; isolated @polkadot/api16.5.6 selects V4 pipeline0. Fresh independent reference private keys discarded. Native/USDC vectors are offline only; nothing is broadcast.',captured:new Date().toISOString(),metadataVersion:api.runtimeMetadata.version,extensionVersion:0,vectors},null,2)+'\n');
 }finally{clearTimeout(timer);await api.disconnect();}
})().then(()=>process.exit(0)).catch(async error=>{console.error(error.message);clearTimeout(timer);await provider.disconnect();process.exit(1);});
