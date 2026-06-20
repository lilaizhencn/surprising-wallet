const fs = require("fs");
const path = require("path");
const { Client } = require("pg");
const solc = require("solc");
const hre = require("hardhat");

const CHAIN = process.env.EVM_CHAIN || "ETH";
const DB_URL = process.env.PG_URL || "postgresql://wallet:wallet123@127.0.0.1:5432/wallet";

async function upsertTokenConfig(client, chain, token) {
  await client.query(
    `insert into token_config(chain, symbol, standard, contract_address, decimals, enabled,
                              min_deposit, min_withdraw, collect_enabled, created_at, updated_at)
     values ($1, $2, 'ERC20', $3, 6, true, 0, 0, true, now(), now())
     on conflict (chain, symbol) do update set
       contract_address = excluded.contract_address,
       decimals = excluded.decimals,
       standard = excluded.standard,
       enabled = true,
       collect_enabled = true,
       updated_at = now()`,
    [chain, token.symbol, token.address]
  );
}

async function main() {
  const [deployer] = await hre.ethers.getSigners();
  const sourcePath = path.join(__dirname, "..", "contracts", "MockERC20.sol");
  const source = fs.readFileSync(sourcePath, "utf8");
  const input = {
    language: "Solidity",
    sources: {
      "MockERC20.sol": { content: source }
    },
    settings: {
      optimizer: { enabled: true, runs: 200 },
      outputSelection: {
        "*": { "*": ["abi", "evm.bytecode"] }
      }
    }
  };
  const output = JSON.parse(solc.compile(JSON.stringify(input)));
  if (output.errors) {
    const fatal = output.errors.filter((error) => error.severity === "error");
    if (fatal.length > 0) {
      throw new Error(fatal.map((error) => error.formattedMessage).join("\n"));
    }
  }
  const contract = output.contracts["MockERC20.sol"].MockERC20;
  const factory = new hre.ethers.ContractFactory(contract.abi, contract.evm.bytecode.object, deployer);

  const usdt = await factory.deploy("Tether USD", "USDT", 6);
  await usdt.waitForDeployment();
  const usdc = await factory.deploy("USD Coin", "USDC", 6);
  await usdc.waitForDeployment();

  await (await usdt.mint(deployer.address, hre.ethers.parseUnits("1000000", 6))).wait();
  await (await usdc.mint(deployer.address, hre.ethers.parseUnits("1000000", 6))).wait();

  const deployment = {
    chain: CHAIN,
    deployer: deployer.address,
    tokens: {
      USDT: { symbol: "USDT", name: "Tether USD", decimals: 6, address: await usdt.getAddress() },
      USDC: { symbol: "USDC", name: "USD Coin", decimals: 6, address: await usdc.getAddress() }
    }
  };

  const client = new Client({ connectionString: DB_URL });
  await client.connect();
  await upsertTokenConfig(client, CHAIN, deployment.tokens.USDT);
  await upsertTokenConfig(client, CHAIN, deployment.tokens.USDC);
  await client.end();

  const outDir = path.join(__dirname, "..", "deployments");
  fs.mkdirSync(outDir, { recursive: true });
  fs.writeFileSync(path.join(outDir, `${CHAIN}.json`), JSON.stringify(deployment, null, 2));
  console.log(JSON.stringify(deployment, null, 2));
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
