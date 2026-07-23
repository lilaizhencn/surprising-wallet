import assert from "node:assert/strict";
import fs from "node:fs/promises";
import pg from "pg";
import { normalizeDecimal } from "../src/decimal.js";

const { Client } = pg;
const demoBaseUrl = required("DEMO_BASE_URL").replace(/\/+$/, "");
const rpcUrl = required("EVM_RPC_URL");
const chain = required("TEST_CHAIN").toUpperCase();
const nativeSymbol = required("NATIVE_SYMBOL").toUpperCase();
const deployment = JSON.parse(await fs.readFile(required("EVM_DEPLOYMENT_FILE"), "utf8"));
const runId = Date.now().toString(36);

function required(name) {
  const value = String(process.env[name] ?? "").trim();
  if (!value) throw new Error(`${name} is required`);
  return value;
}

async function request(path, options = {}) {
  const response = await fetch(`${demoBaseUrl}${path}`, {
    ...options,
    headers: { "Content-Type": "application/json", ...(options.headers ?? {}) }
  });
  const text = await response.text();
  const payload = text ? JSON.parse(text) : null;
  if (!response.ok) {
    throw new Error(`${options.method ?? "GET"} ${path} returned HTTP ${response.status}: `
      + `${payload?.message ?? text}`);
  }
  return payload;
}

async function rpc(method, params) {
  const response = await fetch(rpcUrl, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ jsonrpc: "2.0", id: `${method}-${Date.now()}`, method, params })
  });
  const payload = await response.json();
  if (!response.ok || payload.error) {
    throw new Error(`${method} failed: ${JSON.stringify(payload.error ?? payload)}`);
  }
  return payload.result;
}

async function waitFor(description, predicate, timeoutMillis = 180_000) {
  const deadline = Date.now() + timeoutMillis;
  let lastValue;
  while (Date.now() < deadline) {
    lastValue = await predicate();
    if (lastValue) return lastValue;
    await new Promise(resolve => setTimeout(resolve, 1_000));
  }
  throw new Error(`timed out waiting for ${description}; last value=${JSON.stringify(lastValue)}`);
}

async function waitReceipt(txHash) {
  return waitFor(`transaction ${txHash}`, async () => {
    const receipt = await rpc("eth_getTransactionReceipt", [txHash]);
    if (!receipt) return null;
    assert.equal(receipt.status, "0x1", `transaction ${txHash} reverted`);
    return receipt;
  });
}

function quantity(value) {
  return `0x${BigInt(value).toString(16)}`;
}

function erc20TransferData(address, amount) {
  const recipient = address.toLowerCase().replace(/^0x/, "").padStart(64, "0");
  const encodedAmount = BigInt(amount).toString(16).padStart(64, "0");
  return `0xa9059cbb${recipient}${encodedAmount}`;
}

function balanceMap(rows, userId) {
  return Object.fromEntries(rows
    .filter(row => row.userId === userId && row.chain === chain)
    .map(row => [row.asset, {
      available: normalizeDecimal(row.available),
      locked: normalizeDecimal(row.locked)
    }]));
}

const user = await request("/api/users", {
  method: "POST",
  body: JSON.stringify({
    externalId: `${chain.toLowerCase()}-e2e-${runId}`,
    displayName: `${chain} Full Flow User`
  })
});
const createAddress = version => request(`/api/users/${encodeURIComponent(user.id)}/addresses`, {
  method: "POST",
  body: JSON.stringify({ chain, addressVersion: version })
});
const address = await createAddress(0);
const replay = await createAddress(0);
assert.equal(replay.id, address.id);
assert.equal(replay.address, address.address);
const rotated = await createAddress(1);
assert.notEqual(rotated.id, address.id);
assert.notEqual(rotated.address, address.address);

await waitReceipt(await rpc("eth_sendTransaction", [{
  from: deployment.deployer,
  to: address.address,
  value: quantity(5n * 10n ** 18n)
}]));
for (const token of Object.values(deployment.tokens)) {
  await waitReceipt(await rpc("eth_sendTransaction", [{
    from: deployment.deployer,
    to: token.address,
    data: erc20TransferData(address.address, 20n * 10n ** BigInt(token.decimals))
  }]));
}

const expectedAssets = [nativeSymbol, ...Object.keys(deployment.tokens)];
await waitFor(`${expectedAssets.join("/")} deposit callbacks`, async () => {
  const balances = balanceMap(await request("/api/assets"), user.id);
  return expectedAssets.every(symbol => balances[symbol]?.available === (symbol === nativeSymbol ? "5" : "20"))
    ? balances
    : null;
});

const audit = new Client({
  host: process.env.TENANT_DEMO_PG_HOST,
  port: Number(process.env.TENANT_DEMO_PG_PORT ?? 5432),
  database: process.env.TENANT_DEMO_PG_DATABASE,
  user: process.env.TENANT_DEMO_PG_USER,
  password: process.env.TENANT_DEMO_PG_PASSWORD ?? ""
});
await audit.connect();
const collectionAddress = await waitFor(`tenant ${expectedAssets.join("/")} collections`, async () => {
  const result = await audit.query(`
    SELECT count(*)::integer AS count, min(to_address) AS target,
           count(distinct lower(to_address))::integer AS target_count
      FROM collection_record
     WHERE chain = $1 AND lower(from_address) = lower($2)
       AND asset_symbol = ANY($3::varchar[]) AND status = 'CONFIRMED'
  `, [chain, address.address, expectedAssets]);
  const row = result.rows[0];
  return row.count === expectedAssets.length && row.target_count === 1 ? row.target : null;
});
await audit.end();
assert.notEqual(collectionAddress.toLowerCase(), address.address.toLowerCase());

const withdrawals = [];
for (const assetSymbol of expectedAssets) {
  withdrawals.push(await request(`/api/users/${encodeURIComponent(user.id)}/withdrawals`, {
    method: "POST",
    body: JSON.stringify({
      custodyAddressId: address.id,
      chain,
      assetSymbol,
      toAddress: deployment.deployer,
      amount: assetSymbol === nativeSymbol ? "0.2" : "1"
    })
  }));
}

const confirmed = await waitFor(`${expectedAssets.join("/")} withdrawal callbacks`, async () => {
  const rows = await request("/api/withdrawals");
  const selected = rows.filter(row => withdrawals.some(item => item.id === row.id));
  return selected.length === expectedAssets.length && selected.every(row => row.status === "CONFIRMED")
    ? selected
    : null;
});
const finalBalances = balanceMap(await request("/api/assets"), user.id);
assert.deepEqual(finalBalances[nativeSymbol], { available: "4.8", locked: "0" });
for (const symbol of Object.keys(deployment.tokens)) {
  assert.deepEqual(finalBalances[symbol], { available: "19", locked: "0" });
}
const events = await request("/api/events");
assert.ok(events.filter(event => event.eventType === "DEPOSIT.CONFIRMED").length >= expectedAssets.length);
assert.ok(events.filter(event => event.eventType === "WITHDRAWAL.CONFIRMED").length >= expectedAssets.length);

console.log(JSON.stringify({
  ok: true,
  chain,
  userId: user.id,
  subject: user.externalId,
  depositAddressId: address.id,
  depositAddress: address.address,
  rotatedAddress: rotated.address,
  collectionAddress,
  withdrawals: confirmed.map(row => ({ id: row.id, asset: row.asset, txHash: row.txHash })),
  balances: finalBalances
}, null, 2));
