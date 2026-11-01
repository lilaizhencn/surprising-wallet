'use strict';
// Isolated consensus oracle/faucet. Java receives public state only; test administrator keys stay outside the checkout.
const fs=require('node:fs');
const {ApiPromise,WsProvider}=require('@polkadot/api');
const {Keyring}=require('@polkadot/keyring');
const {cryptoWaitReady,encodeAddress}=require('@polkadot/util-crypto');
const {u8aToHex}=require('@polkadot/util');
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
async function main() {
 const [descriptor,command,role,...args]=process.argv.slice(2),fixture=JSON.parse(fs.readFileSync(descriptor,'utf8')),entry=fixture[role];
 if(!['RELAY','ASSET_HUB'].includes(role)||!entry)throw Error('isolated network role is required');
 const endpoint=new URL(entry.endpoint);if(endpoint.protocol!=='ws:'||endpoint.hostname!=='127.0.0.1')throw Error('fixture requires an isolated loopback node');
 await cryptoWaitReady();const api=await ApiPromise.create({provider:new WsProvider(entry.endpoint),noInitWarn:true});
 try {
  if(api.genesisHash.toHex()!==entry.genesisHash||!(await api.rpc.system.chain()).toString().includes('Development'))throw Error('fixture development genesis differs');
  let result;
  if(command==='info') {
   const [owner,symbol]=args,assetId=symbol==='DOT'?null:fixture[symbol];if(symbol!=='DOT'&&(!assetId||role!=='ASSET_HUB'))throw Error('unknown fixture asset');
   const hash=await api.rpc.chain.getFinalizedHead(),header=await api.rpc.chain.getHeader(hash),account=await api.query.system.account(owner),version=await api.rpc.state.getRuntimeVersion(hash);
   const keep=assetId?api.tx.assets.transferKeepAlive:api.tx.balances.transferKeepAlive,death=assetId?api.tx.assets.transfer:api.tx.balances.transferAllowDeath;
   result={role,genesisHash:entry.genesisHash,blockHash:hash.toHex(),blockHeight:header.number.toNumber(),nonce:(await api.rpc.system.accountNextIndex(owner)).toNumber(),specVersion:version.specVersion.toNumber(),transactionVersion:version.transactionVersion.toNumber(),extensions:api.registry.signedExtensions,callIndices:{keepAlive:u8aToHex(keep.callIndex),allowDeath:u8aToHex(death.callIndex)},nativeBalance:account.data.free.toString(),decimals:assetId?(await api.query.assets.metadata(assetId)).decimals.toNumber():api.registry.chainDecimals[0],assetId,balance:assetId?await assetBalance(api,assetId,owner):account.data.free.toString()};
  } else if(command==='balance') {
   const [owner,symbol]=args;result={balance:symbol==='DOT'?(await api.query.system.account(owner)).data.free.toString():await assetBalance(api,fixture[symbol],owner)};
  } else if(command==='broadcast') {
   if(!/^0x[0-9a-f]{2,16384}$/.test(args[0]))throw Error('bounded selected extrinsic hex is required');
   result={txHash:(await api.rpc.author.submitExtrinsic(args[0])).toHex()};
  } else if(command==='proof') {
   const [txHash,startValue,endValue]=args;let next=Number(startValue);const last=Number(endValue),deadline=Date.now()+45000;
   if(!/^0x[0-9a-f]{64}$/.test(txHash)||!Number.isSafeInteger(next)||next<0||last<next||last-next>4096)throw Error('bounded original intent search is required');
   while(!result&&Date.now()<deadline) {
    const finalHash=await api.rpc.chain.getFinalizedHead(),finalHeader=await api.rpc.chain.getHeader(finalHash),latest=finalHeader.number.toNumber();
    for(;next<=Math.min(latest,last);next++) {
     const hash=await api.rpc.chain.getBlockHash(next),block=await api.rpc.chain.getBlock(hash),index=block.block.extrinsics.findIndex(tx=>tx.hash.toHex()===txHash);
     if(index<0)continue;
     const at=await api.at(hash),records=await at.query.system.events(),version=await api.rpc.state.getRuntimeVersion(hash);
     result={role,genesisHash:entry.genesisHash,finalizedHeight:latest,blockHeight:next,blockHash:hash.toHex(),headerHex:block.block.header.toHex(),txHash,extrinsicHex:block.block.extrinsics[index].toHex(),extrinsicIndex:index,specVersion:version.specVersion.toNumber(),transactionVersion:version.transactionVersion.toNumber(),eventsHex:records.toHex(),events:records.filter(record=>record.phase.isApplyExtrinsic&&record.phase.asApplyExtrinsic.toNumber()===index).map(({event})=>({section:event.section,method:event.method,data:event.data.map(value=>value.toString())}))};break;
    }
    if(!result){if(next>last)throw Error('original selected transaction is absent from its finalized era');await sleep(200);}
   }
   if(!result)throw Error('selected extrinsic finality timeout');
  } else {
   const keyPath=fs.realpathSync(fixture.adminKeyFile);
   if(!keyPath.startsWith('/tmp/surprising-wallet-mpc-dot-')&&!keyPath.startsWith('/private/tmp/surprising-wallet-mpc-dot-'))throw Error('test administrator key must stay isolated outside checkout');
   const key=JSON.parse(fs.readFileSync(keyPath,'utf8')),pair=new Keyring({type:'sr25519',ss58Format:42}).addFromUri(key.secretUri);let tx;
   if(command==='fund')tx=api.tx.balances.transferKeepAlive(args[0],positive(args[1]));
   else if(command==='asset-init') {
    const [symbol,owner,amount]=args,id=fixture[symbol];if(role!=='ASSET_HUB'||!['USDT','USDC'].includes(symbol))throw Error('fixture token is restricted to Asset Hub');
    const current=await api.query.assets.asset(id),calls=[];
    if(current.isNone){calls.push(api.tx.assets.create(id,pair.address,'1'),api.tx.assets.setMetadata(id,'Local '+symbol,symbol,6));}
    else {const metadata=await api.query.assets.metadata(id);if(metadata.symbol.toUtf8()!==symbol||metadata.decimals.toNumber()!==6||encodeAddress(current.unwrap().owner.toU8a(),42)!==pair.address)throw Error('existing fixture token identity differs');}
    calls.push(api.tx.assets.mint(id,pair.address,positive(amount)),api.tx.assets.transfer(id,owner,positive(amount)));tx=api.tx.utility.batchAll(calls);
   } else if(command==='asset-freeze'||command==='asset-thaw') {
    if(role!=='ASSET_HUB'||!['USDT','USDC'].includes(args[0]))throw Error('fixture asset account required');
    tx=api.tx.assets[command==='asset-freeze'?'freeze':'thaw'](fixture[args[0]],args[1]);
   } else throw Error('unsupported isolated fixture command');
   result=await submit(api,tx,pair);
  }
  process.stdout.write(JSON.stringify(result)+'\n');
 }finally{await api.disconnect();}
}
function positive(value){if(!/^[1-9][0-9]*$/.test(value)||BigInt(value)>=(1n<<128n))throw Error('positive u128 fixture amount required');return value;}
async function assetBalance(api,id,owner){const value=await api.query.assets.account(id,owner);return value.isSome?value.unwrap().balance.toString():'0';}
async function submit(api,tx,pair) {
 let unsubscribe;
 const outcome=await Promise.race([new Promise((resolve,reject)=>{tx.signAndSend(pair,{nonce:-1},result=>{if(result.isError)reject(Error('fixture transaction rejected'));if(result.status.isFinalized)resolve(result);}).then(value=>{unsubscribe=value;}).catch(reject);}),new Promise((_,reject)=>setTimeout(()=>reject(Error('fixture finality timeout')),45000))]);
 if(unsubscribe)unsubscribe();if(outcome.dispatchError)throw Error('fixture execution failed: '+outcome.dispatchError.toString());return {txHash:outcome.txHash.toHex(),blockHash:outcome.status.asFinalized.toHex()};
}
main().then(()=>process.exit(0)).catch(error=>{console.error(error.message);process.exit(1);});
