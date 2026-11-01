'use strict';
// Local test administrator only. The Java wallet and MPC parties never receive this keystore.
const fs=require('node:fs'),path=require('node:path'),sdk=require('near-api-js');
async function main() {
    const [descriptor,command,...args]=process.argv.slice(2),config=JSON.parse(fs.readFileSync(descriptor,'utf8'));
    const endpoint=new URL(config.endpoint);
    if(endpoint.protocol!=='http:' || endpoint.hostname!=='127.0.0.1')throw Error('NEAR fixture requires its isolated loopback ledger');
    const status=await fetch(config.endpoint,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({jsonrpc:'2.0',id:1,method:'status',params:[]})}).then(r=>r.json());
    if(status.result?.chain_id!=='mpc-localnet-2134' || status.result?.genesis_hash!==config.genesisHash)throw Error('NEAR fixture genesis identity differs');
    const keyPath=fs.realpathSync(config.adminKeyFile);
    if(!keyPath.startsWith('/tmp/surprising-wallet-mpc-near-') && !keyPath.startsWith('/private/tmp/surprising-wallet-mpc-near-'))throw Error('NEAR fixture keystore must stay isolated outside checkout');
    const record=JSON.parse(fs.readFileSync(keyPath,'utf8'));
    const administrator=new sdk.Account(record.account_id,config.endpoint,sdk.KeyPairSigner.fromSecretKey(record.secret_key));
    const gas=100_000_000_000_000n;let receiverId,actions;
    const positive=value=>{if(!/^[1-9][0-9]*$/.test(value))throw Error('positive integer fixture amount required');return BigInt(value);};
    if(command==='fund') {
        [receiverId]=args;actions=[sdk.actions.transfer(positive(args[1]))];
    } else if(command==='deploy') {
        const [symbol,contract,supply]=args;
        if(!['USDT','USDC'].includes(symbol) || !contract.endsWith('.'+record.account_id))throw Error('fixture token must be an administrator subaccount');
        const publicKey=sdk.PublicKey.fromString(record.public_key),code=fs.readFileSync(path.resolve(config.tokenWasm));
        receiverId=contract;actions=[sdk.actions.createAccount(),sdk.actions.transfer(20n*10n**24n),sdk.actions.addFullAccessKey(publicKey),sdk.actions.deployContract(code),
            sdk.actions.functionCall('init',Buffer.from(JSON.stringify({owner_id:record.account_id,total_supply:positive(supply).toString(),metadata:{spec:'ft-1.0.0',name:'Local '+symbol,symbol,icon:null,reference:null,reference_hash:null,decimals:6}})),gas,0n)];
    } else if(command==='register') {
        receiverId=args[0];actions=[sdk.actions.functionCall('storage_deposit',Buffer.from(JSON.stringify({account_id:args[1],registration_only:true})),gas,20_000_000_000_000_000_000_000n)];
    } else if(command==='token-fund' || command==='token-call') {
        const transfer={receiver_id:args[1],amount:positive(args[2]).toString(),memo:'isolated MPC fixture funding'};
        if(command==='token-call')transfer.msg='';
        receiverId=args[0];actions=[sdk.actions.functionCall(command==='token-call'?'ft_transfer_call':'ft_transfer',Buffer.from(JSON.stringify(transfer)),gas,1n)];
    } else throw Error('unsupported NEAR fixture command');
    const result=await administrator.signAndSendTransaction({receiverId,actions,waitUntil:'FINAL',throwOnFailure:true,retries:1});
    process.stdout.write(JSON.stringify(result,(_,v)=>typeof v==='bigint'?v.toString():v)+'\n');
}
main().catch(error=>{console.error(error.message);process.exitCode=1;});
