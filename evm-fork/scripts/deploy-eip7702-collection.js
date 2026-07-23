const fs = require("fs");
const path = require("path");
const hre = require("hardhat");

function requiredAddress(name) {
  const value = (process.env[name] || "").trim();
  if (!hre.ethers.isAddress(value) || value === hre.ethers.ZeroAddress) {
    throw new Error(`${name} must be a non-zero EVM address`);
  }
  return hre.ethers.getAddress(value);
}

async function runtimeCodeHash(address) {
  const code = await hre.ethers.provider.getCode(address);
  if (code === "0x") throw new Error(`missing runtime code at ${address}`);
  return hre.ethers.keccak256(code);
}

async function main() {
  const chain = (process.env.EVM_CHAIN || "ETH").trim().toUpperCase();
  const network = (process.env.EVM_NETWORK || "local").trim().toLowerCase();
  const admin = requiredAddress("EIP7702_ADMIN_ADDRESS");
  const relayer = requiredAddress("EIP7702_RELAYER_ADDRESS");
  const [deployer] = await hre.ethers.getSigners();
  const rpcNetwork = await hre.ethers.provider.getNetwork();

  const Collector = await hre.ethers.getContractFactory("Eip7702BatchCollector");
  const collector = await Collector.deploy(admin, relayer);
  await collector.waitForDeployment();
  const collectorAddress = await collector.getAddress();

  const Delegate = await hre.ethers.getContractFactory("Eip7702CollectionDelegate");
  const delegate = await Delegate.deploy(collectorAddress);
  await delegate.waitForDeployment();
  const delegateAddress = await delegate.getAddress();

  const PayoutDelegate = await hre.ethers.getContractFactory("Eip7702PayoutDelegate");
  const payoutDelegate = await PayoutDelegate.deploy(relayer);
  await payoutDelegate.waitForDeployment();
  const payoutDelegateAddress = await payoutDelegate.getAddress();

  const deployment = {
    schemaVersion: 2,
    chain,
    network,
    chainId: rpcNetwork.chainId.toString(),
    deployer: deployer.address,
    admin,
    relayer,
    collectorAddress,
    collectorCodeHash: await runtimeCodeHash(collectorAddress),
    delegateAddress,
    delegateCodeHash: await runtimeCodeHash(delegateAddress),
    payoutDelegateAddress,
    payoutDelegateCodeHash: await runtimeCodeHash(payoutDelegateAddress)
  };

  const configuredOutput = (process.env.EIP7702_DEPLOYMENT_FILE || "").trim();
  const out = configuredOutput
    ? path.resolve(configuredOutput)
    : path.join(__dirname, "..", "deployments", `${chain}-EIP7702-${network}.json`);
  const allowOverwrite = process.env.EIP7702_ALLOW_OVERWRITE === "true";
  if (allowOverwrite && network !== "devtest" && network !== "local") {
    throw new Error("EIP7702_ALLOW_OVERWRITE is restricted to devtest/local deployments");
  }
  fs.mkdirSync(path.dirname(out), { recursive: true });
  fs.writeFileSync(out, `${JSON.stringify(deployment, null, 2)}\n`, {
    flag: allowOverwrite ? "w" : "wx",
    mode: 0o640
  });
  process.stdout.write(`${JSON.stringify(deployment, null, 2)}\n`);
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
