#!/usr/bin/env node

const fs = require('fs');
const TronWeb = require('tronweb');

const artifactPath = process.argv[2];
const outputPath = process.argv[3];
const privateKey = process.env.TRON_LOCAL_SOURCE_KEY;

if (!artifactPath || !outputPath || !privateKey) {
  throw new Error('artifact path, output path, and TRON_LOCAL_SOURCE_KEY are required');
}

const artifact = JSON.parse(fs.readFileSync(artifactPath, 'utf8'));
const tronWeb = new TronWeb(
  'http://127.0.0.1:18190',
  'http://127.0.0.1:18191',
  'http://127.0.0.1:8060',
  privateKey,
);
const owner = tronWeb.address.fromPrivateKey(privateKey);

async function deploy(symbol) {
  const contract = await tronWeb.contract().new({
    abi: artifact.abi,
    bytecode: artifact.bytecode,
    feeLimit: 1000000000,
    callValue: 0,
    userFeePercentage: 100,
    parameters: [`Local ${symbol}`, symbol, 6],
  });
  const address = tronWeb.address.fromHex(contract.address);
  await contract.mint(owner, '1000000000000').send({ feeLimit: 1000000000 });
  return address;
}

(async () => {
  const result = {
    sourceAddress: owner,
    usdtContract: await deploy('USDT'),
    usdcContract: await deploy('USDC'),
  };
  fs.writeFileSync(outputPath, JSON.stringify(result));
})().catch((error) => {
  process.stderr.write(`${error.stack || error.message}\n`);
  process.exit(1);
});
