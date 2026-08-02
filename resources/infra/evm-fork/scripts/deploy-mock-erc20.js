const fs = require("fs");
const path = require("path");
const { Client } = require("pg");
const solc = require("solc");
const hre = require("hardhat");

const CHAIN = process.env.EVM_CHAIN || "ETH";
const NETWORK = process.env.EVM_NETWORK?.trim() || null;
const DB_URL = process.env.PG_URL || "postgresql://wallet:wallet123@127.0.0.1:5432/wallet";
const TOKEN_SYMBOLS = (process.env.TOKEN_SYMBOLS ?? "USDC,USDT")
  .split(",")
  .map((symbol) => symbol.trim().toUpperCase())
  .filter(Boolean);
const TOKEN_DEFINITIONS = {
  USDC: { symbol: "USDC", name: "USD Coin", decimals: 6 },
  USDC_E: { symbol: "USDC_E", name: "Bridged USD Coin", decimals: 6 },
  USDC_ETH: { symbol: "USDC_ETH", name: "ZetaChain ZRC20 USDC on Ethereum", decimals: 6 },
  USDT: { symbol: "USDT", name: "Tether USD", decimals: 6 },
  IOUSDT: { symbol: "IOUSDT", name: "IoTeX Tether USD", decimals: 6 },
  USDT_ETH: { symbol: "USDT_ETH", name: "ZetaChain ZRC20 USDT on Ethereum", decimals: 6 },
  USDT0: { symbol: "USDT0", name: "Tether USD0", decimals: 6 },
  USDM: { symbol: "USDM", name: "MegaUSD", decimals: 18 },
  USDG: { symbol: "USDG", name: "Global Dollar", decimals: 6 },
};

async function upsertTokenConfig(client, chain, token) {
  if (!NETWORK) {
    throw new Error("EVM_NETWORK is required for isolated mock token deployment");
  }
  const updated = await client.query(
    `insert into token_config(
        chain, symbol, standard, contract_address, decimals, enabled,
        min_deposit, min_withdraw, collect_enabled, created_at, updated_at,
        network, token_standard, contract_address_hex, min_deposit_amount,
        min_withdraw_amount, collect_threshold, gas_strategy, confirmation_required)
     values ($1, $2, 'ERC20', $3, $4, true,
             1, 1, true, now(), now(),
             $5, 'ERC20', $3, 1, 1, 1, 'native-gas', 1)
     on conflict (chain, network, symbol) do update set
        standard = excluded.standard,
        contract_address = excluded.contract_address,
        decimals = excluded.decimals,
        enabled = true,
        collect_enabled = true,
        token_standard = excluded.token_standard,
        contract_address_hex = excluded.contract_address_hex,
        min_deposit_amount = excluded.min_deposit_amount,
        min_withdraw_amount = excluded.min_withdraw_amount,
        collect_threshold = excluded.collect_threshold,
        gas_strategy = excluded.gas_strategy,
        confirmation_required = excluded.confirmation_required,
        updated_at = now()
     returning id`,
    [chain, token.symbol, token.address, token.decimals, NETWORK]
  );
  if (updated.rowCount !== 1) {
    throw new Error(`${chain}/${NETWORK}/${token.symbol} token configuration was not created`);
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
    network: NETWORK,
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
