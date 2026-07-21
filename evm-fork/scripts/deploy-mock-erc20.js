const fs = require("fs");
const path = require("path");
const { Client } = require("pg");
const solc = require("solc");
const hre = require("hardhat");

const CHAIN = process.env.EVM_CHAIN || "ETH";
const DB_URL = process.env.PG_URL || "postgresql://wallet:wallet123@127.0.0.1:5432/wallet";
const TOKEN_SYMBOLS = (process.env.TOKEN_SYMBOLS ?? "USDC,USDT")
  .split(",")
  .map((symbol) => symbol.trim().toUpperCase())
  .filter(Boolean);
const TOKEN_DEFINITIONS = {
  USDC: { symbol: "USDC", name: "USD Coin", decimals: 6 },
  USDT: { symbol: "USDT", name: "Tether USD", decimals: 6 },
};

async function upsertTokenConfig(client, chain, token) {
  const updated = await client.query(
    `update token_config
        set contract_address = $3,
            contract_address_hex = $3,
            decimals = $4,
            standard = 'ERC20',
            token_standard = 'ERC20',
            collect_enabled = true,
            updated_at = now()
      where chain = $1 and symbol = $2 and enabled = true`,
    [chain, token.symbol, token.address, token.decimals]
  );
  if (updated.rowCount !== 1) {
    throw new Error(`${chain}/${token.symbol} must have exactly one enabled token configuration`);
  }
  await client.query(
    `update chain_asset
        set contract_address = $3, decimals = $4, updated_at = now()
      where chain = $1 and symbol = $2 and active = true`,
    [chain, token.symbol, token.address, token.decimals]
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

  const tokens = {};
  for (const symbol of TOKEN_SYMBOLS) {
    const definition = TOKEN_DEFINITIONS[symbol];
    if (!definition) {
      throw new Error(`unsupported local mock token ${symbol}`);
    }
    const contract = await factory.deploy(definition.name, definition.symbol, definition.decimals);
    await contract.waitForDeployment();
    await (await contract.mint(
      deployer.address,
      hre.ethers.parseUnits("1000000", definition.decimals),
    )).wait();
    tokens[symbol] = { ...definition, address: await contract.getAddress() };
  }

  const deployment = {
    chain: CHAIN,
    deployer: deployer.address,
    tokens,
  };

  const client = new Client({ connectionString: DB_URL });
  await client.connect();
  for (const token of Object.values(tokens)) {
    await upsertTokenConfig(client, CHAIN, token);
  }
  await client.end();

  const outDir = process.env.DEPLOYMENT_OUT_DIR
    ? path.resolve(process.env.DEPLOYMENT_OUT_DIR)
    : path.join(__dirname, "..", "deployments");
  fs.mkdirSync(outDir, { recursive: true });
  fs.writeFileSync(path.join(outDir, `${CHAIN}.json`), JSON.stringify(deployment, null, 2));
  console.log(JSON.stringify(deployment, null, 2));
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
