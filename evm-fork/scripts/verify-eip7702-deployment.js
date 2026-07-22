const fs = require("fs");
const path = require("path");
const { ethers } = require("ethers");

function required(name) {
  const value = (process.env[name] || "").trim();
  if (!value) throw new Error(`${name} is required`);
  return value;
}

function requireHash(name, value) {
  if (typeof value !== "string" || !/^0x[0-9a-fA-F]{64}$/.test(value)) {
    throw new Error(`${name} must be a bytes32 hex value`);
  }
  return value.toLowerCase();
}

async function main() {
  const deploymentFile = path.resolve(required("EIP7702_DEPLOYMENT_FILE"));
  const deployment = JSON.parse(fs.readFileSync(deploymentFile, "utf8"));
  const provider = new ethers.JsonRpcProvider(required("EVM_VERIFY_RPC_URL"));
  const network = await provider.getNetwork();
  if (network.chainId.toString() !== String(deployment.chainId)) {
    throw new Error(`chainId mismatch: RPC=${network.chainId} deployment=${deployment.chainId}`);
  }

  const contracts = [
    ["collector", deployment.collectorAddress, deployment.collectorCodeHash],
    ["delegate", deployment.delegateAddress, deployment.delegateCodeHash]
  ];
  const verified = {};
  for (const [label, address, expectedHashValue] of contracts) {
    if (!ethers.isAddress(address) || address === ethers.ZeroAddress) {
      throw new Error(`${label} address is invalid`);
    }
    const code = await provider.getCode(address);
    if (code === "0x") throw new Error(`${label} runtime code is missing`);
    const actualHash = ethers.keccak256(code).toLowerCase();
    const expectedHash = requireHash(`${label}CodeHash`, expectedHashValue);
    if (actualHash !== expectedHash) {
      throw new Error(`${label} code hash mismatch: RPC=${actualHash} deployment=${expectedHash}`);
    }
    verified[label] = { address: ethers.getAddress(address), codeHash: actualHash };
  }

  process.stdout.write(`${JSON.stringify({
    deploymentFile,
    chainId: network.chainId.toString(),
    verified
  }, null, 2)}\n`);
}

main().catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});
