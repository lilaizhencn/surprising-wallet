require("@nomicfoundation/hardhat-ethers");

const chainId = process.env.HARDHAT_CHAIN_ID ? Number(process.env.HARDHAT_CHAIN_ID) : 31337;
const deploymentRpcUrl = process.env.EVM_DEPLOY_RPC_URL;
const deploymentPrivateKey = process.env.EVM_DEPLOYER_PRIVATE_KEY;

if ((deploymentRpcUrl && !deploymentPrivateKey) || (!deploymentRpcUrl && deploymentPrivateKey)) {
  throw new Error("EVM_DEPLOY_RPC_URL and EVM_DEPLOYER_PRIVATE_KEY must be set together");
}

module.exports = {
  solidity: {
    version: "0.8.24",
    settings: {
      optimizer: {
        enabled: true,
        runs: 200
      }
    }
  },
  networks: {
    hardhat: {
      chainId,
      hardfork: "prague"
    },
    localhost: {
      url: "http://127.0.0.1:8545"
    },
    ...(deploymentRpcUrl ? {
      deployment: {
        url: deploymentRpcUrl,
        accounts: [deploymentPrivateKey]
      }
    } : {})
  }
};
