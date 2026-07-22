#!/usr/bin/env node

const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '../..');
const solc = require(path.join(root, 'evm-fork/node_modules/solc'));
const sourcePath = path.join(root, 'evm-fork/contracts/MockERC20.sol');
const outputPath = process.argv[2];

if (!outputPath) {
  throw new Error('usage: tron-compile-token.js <output-json>');
}

const input = {
  language: 'Solidity',
  sources: {
    'MockERC20.sol': { content: fs.readFileSync(sourcePath, 'utf8') },
  },
  settings: {
    optimizer: { enabled: true, runs: 200 },
    evmVersion: 'paris',
    outputSelection: {
      '*': { '*': ['abi', 'evm.bytecode.object'] },
    },
  },
};

const compiled = JSON.parse(solc.compile(JSON.stringify(input)));
const errors = (compiled.errors || []).filter((entry) => entry.severity === 'error');
if (errors.length > 0) {
  throw new Error(errors.map((entry) => entry.formattedMessage).join('\n'));
}

const contract = compiled.contracts['MockERC20.sol'].MockERC20;
fs.writeFileSync(outputPath, JSON.stringify({
  abi: contract.abi,
  bytecode: contract.evm.bytecode.object,
}));
