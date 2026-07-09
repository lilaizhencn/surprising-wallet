require("@nomicfoundation/hardhat-ethers");

const chainId = process.env.HARDHAT_CHAIN_ID ? Number(process.env.HARDHAT_CHAIN_ID) : 31337;

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
      chainId
    },
    localhost: {
      url: "http://127.0.0.1:8545"
    }
  }
};
