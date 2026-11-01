'use strict';
// Isolated native-node administrator. Signing files remain inside the protected test volume.
const fs = require('node:fs');
const {execFileSync} = require('node:child_process');
const [descriptor, command, ...args] = process.argv.slice(2);
const fixture = JSON.parse(fs.readFileSync(descriptor, 'utf8'));
const endpoint = new URL(fixture.rpcUrl);
if (endpoint.protocol !== 'http:' || endpoint.hostname !== '127.0.0.1'
    || fixture.context !== 'colima-wallet-mpc-test' || fixture.container !== 'surprising-wallet-mpc-cardano-1113'
    || fixture.magic !== 424242 || fixture.data !== '/data/live-first') throw Error('isolated Cardano fixture required');
const docker = (...argv) => execFileSync('docker', ['--context', fixture.context, 'exec', fixture.container, ...argv],
    {encoding: 'utf8', timeout: 45000, maxBuffer: 4 * 1024 * 1024}).trim();
const cli = (...argv) => docker('/opt/cardano/bin/cardano-cli', 'conway', ...argv);
const txId = (...argv) => {
    const value = cli('transaction', 'txid', ...argv);
    const hash = value.startsWith('{') ? JSON.parse(value).txhash : value;
    if (!/^[0-9a-f]{64}$/.test(hash)) throw Error('official CLI returned an invalid transaction ID');
    return hash;
};
const network = ['--testnet-magic', String(fixture.magic), '--socket-path', fixture.data + '/socket/node1/sock'];
const root = fixture.data + '/mpc-acceptance';
const funder = fixture.data + '/utxo-keys/utxo1';
const write = (file, content) => execFileSync('docker', ['--context', fixture.context, 'exec', '-i', fixture.container,
    'sh', '-c', 'umask 077; cat > "$1"', 'sh', file], {input: content, encoding: 'utf8', timeout: 10000});
docker('mkdir', '-p', root);
const keyHash = cli('address', 'key-hash', '--payment-verification-key-file', funder + '/utxo.vkey');
write(root + '/policy.json', JSON.stringify({type: 'sig', keyHash}));
const policy = cli('transaction', 'policyid', '--script-file', root + '/policy.json');
const units = {USDT: policy + '55534454', USDC: policy + '55534443'};
if (command === 'info') {
    process.stdout.write(JSON.stringify({policy, units}) + '\n');
} else if (command === 'fund') {
    const [recipient, lovelace, tokenAmount = '0'] = args;
    if (!/^addr_test1[0-9a-z]+$/.test(recipient) || !/^[1-9][0-9]{0,13}$/.test(lovelace)
        || !/^(0|[1-9][0-9]{0,13})$/.test(tokenAmount)) throw Error('invalid isolated Cardano funding request');
    const address = docker('cat', funder + '/utxo.addr');
    const nonce = require('node:crypto').randomUUID();
    const work = root + '/' + nonce;
    docker('mkdir', work);
    cli('query', 'utxo', '--address', address, ...network, '--out-file', work + '/utxos.json');
    const utxos = JSON.parse(docker('cat', work + '/utxos.json'));
    const inputs = Object.keys(utxos).flatMap(ref => ['--tx-in', ref]);
    if (!inputs.length) throw Error('isolated genesis funder has no unspent input');
    let output = recipient + '+' + lovelace;
    let mint = [];
    if (BigInt(tokenAmount) > 0n) {
        const minted = Object.values(units).map(unit => tokenAmount + ' ' + unit.slice(0, 56) + '.' + unit.slice(56)).join(' + ');
        output += '+' + minted;
        mint = ['--mint', minted, '--mint-script-file', root + '/policy.json'];
    }
    cli('transaction', 'build', ...network, ...inputs, '--tx-out', output, '--change-address', address,
        '--witness-override', '1', ...mint, '--out-file', work + '/body.json');
    cli('transaction', 'sign', '--testnet-magic', String(fixture.magic), '--tx-body-file', work + '/body.json',
        '--signing-key-file', funder + '/utxo.skey', '--out-file', work + '/signed.json');
    cli('transaction', 'submit', ...network, '--tx-file', work + '/signed.json');
    const txHash = txId('--tx-file', work + '/signed.json');
    process.stdout.write(JSON.stringify({txHash, recipient, lovelace, tokenAmount, units}) + '\n');
} else if (command === 'hash') {
    const [transactionHex] = args;
    if (!/^(?:[0-9a-f]{2}){1,65536}$/.test(transactionHex)) throw Error('bounded public signed bytes required');
    const file = root + '/public-' + require('node:crypto').randomUUID() + '.json';
    write(file, JSON.stringify({type: 'Tx ConwayEra', description: 'Public MPC signed transaction', cborHex: transactionHex}));
    process.stdout.write(JSON.stringify({txHash: txId('--tx-file', file)}) + '\n');
} else throw Error('unsupported isolated Cardano fixture operation');
