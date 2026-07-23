const crypto = require("crypto");
const fs = require("fs");
const path = require("path");
const { Client } = require("pg");
const { ethers } = require("ethers");

function required(name) {
  const value = (process.env[name] || "").trim();
  if (!value) throw new Error(`${name} is required`);
  return value;
}

function requireAddress(name, value) {
  if (!ethers.isAddress(value) || value === ethers.ZeroAddress) {
    throw new Error(`${name} is not a non-zero EVM address`);
  }
  return ethers.getAddress(value);
}

function requireHash(name, value) {
  if (!/^0x[0-9a-fA-F]{64}$/.test(value || "")) {
    throw new Error(`${name} is not a bytes32 hash`);
  }
  return value.toLowerCase();
}

async function main() {
  const deploymentFile = path.resolve(required("EIP7702_DEPLOYMENT_FILE"));
  const deployment = JSON.parse(fs.readFileSync(deploymentFile, "utf8"));
  const chain = required("EVM_CHAIN").toUpperCase();
  const network = required("EVM_NETWORK").toLowerCase();
  if (network !== "devtest" && network !== "local") {
    throw new Error("devtest configuration refuses non-devtest/local networks");
  }
  if (deployment.chain !== chain || deployment.network !== network) {
    throw new Error("deployment chain/network does not match the requested devtest runtime");
  }
  if (String(deployment.chainId) !== "31337") {
    throw new Error(`devtest EIP-7702 chain id must be 31337, got ${deployment.chainId}`);
  }

  const collector = requireAddress("collectorAddress", deployment.collectorAddress);
  const delegate = requireAddress("delegateAddress", deployment.delegateAddress);
  const payoutDelegate = requireAddress("payoutDelegateAddress", deployment.payoutDelegateAddress);
  const relayer = requireAddress("relayer", deployment.relayer);
  const collectorHash = requireHash("collectorCodeHash", deployment.collectorCodeHash);
  const delegateHash = requireHash("delegateCodeHash", deployment.delegateCodeHash);
  const payoutDelegateHash = requireHash(
    "payoutDelegateCodeHash", deployment.payoutDelegateCodeHash);

  const client = new Client({ connectionString: required("PG_URL") });
  await client.connect();
  try {
    await client.query("begin");
    await client.query("select pg_advisory_xact_lock(hashtext('surprising-wallet:devtest:eip7702'))");
    const profile = await client.query(
      `select chain_id from chain_profile
        where chain = $1 and network = $2 and enabled = true`,
      [chain, network]
    );
    if (profile.rowCount !== 1 || String(profile.rows[0].chain_id) !== "31337") {
      throw new Error(`${chain}/${network} must have one enabled chain_profile with chain id 31337`);
    }
    const relayerRow = await client.query(
      `select id, address from chain_address
        where tenant_id is null and chain = $1 and wallet_role = 'EIP7702_RELAYER'
          and enabled = true`,
      [chain]
    );
    if (relayerRow.rowCount !== 1
        || relayerRow.rows[0].address.toLowerCase() !== relayer.toLowerCase()) {
      throw new Error("deployment relayer does not match the unique platform relayer chain_address");
    }

    await client.query(
      `update evm_7702_config
          set status = 'PAUSED', updated_at = now()
        where chain = $1 and network = $2 and version <> 1
          and status in ('ACTIVE', 'SHADOW')`,
      [chain, network]
    );
    await client.query(
      `insert into evm_7702_config(
          id, chain, network, chain_id, version,
          delegate_address, delegate_code_hash,
          collector_address, collector_code_hash,
          payout_delegate_address, payout_delegate_code_hash,
          relayer_chain_address_id, relayer_address, status,
          max_batch_items, max_batch_gas, block_gas_ratio,
          gas_limit_multiplier, signature_ttl_seconds, required_confirmations,
          native_collection_enabled, batch_withdrawal_enabled,
          withdrawal_max_wait_ms, withdrawal_max_batch_items)
        values ($1, $2, $3, 31337, 1, $4, $5, $6, $7, $8, $9, $10, $11,
                'ACTIVE', 20, 5000000, 0.5000, 1.2000, 900, 1, true, true, 3000, 20)
        on conflict (chain, network, version) do update set
          delegate_address = excluded.delegate_address,
          delegate_code_hash = excluded.delegate_code_hash,
          collector_address = excluded.collector_address,
          collector_code_hash = excluded.collector_code_hash,
          payout_delegate_address = excluded.payout_delegate_address,
          payout_delegate_code_hash = excluded.payout_delegate_code_hash,
          relayer_chain_address_id = excluded.relayer_chain_address_id,
          relayer_address = excluded.relayer_address,
          status = excluded.status,
          native_collection_enabled = excluded.native_collection_enabled,
          batch_withdrawal_enabled = excluded.batch_withdrawal_enabled,
          withdrawal_max_wait_ms = excluded.withdrawal_max_wait_ms,
          withdrawal_max_batch_items = excluded.withdrawal_max_batch_items,
          updated_at = now()`,
      [crypto.randomUUID(), chain, network, delegate, delegateHash, collector, collectorHash,
        payoutDelegate, payoutDelegateHash, relayerRow.rows[0].id, relayer]
    );
    await client.query("commit");
    process.stdout.write(`${JSON.stringify({
      chain,
      network,
      relayer,
      collector,
      delegate,
      payoutDelegate,
      status: "ACTIVE",
      nativeCollectionEnabled: true,
      batchWithdrawalEnabled: true
    }, null, 2)}\n`);
  } catch (error) {
    await client.query("rollback");
    throw error;
  } finally {
    await client.end();
  }
}

main().catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});
