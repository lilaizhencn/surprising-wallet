import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import pg from "pg";
import { normalizeDecimal } from "../src/decimal.js";

const execute = promisify(execFile);
const { Client } = pg;
const demoBaseUrl = required("DEMO_BASE_URL").replace(/\/+$/, "");
const adminDirectory = required("APTOS_ADMIN_DIRECTORY");
const publisher = required("APTOS_FA_PUBLISHER");
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

async function aptos(...args) {
  await execute("aptos", args, { cwd: adminDirectory, maxBuffer: 4 * 1024 * 1024 });
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

function balanceMap(rows, userId) {
  return Object.fromEntries(rows
    .filter(row => row.userId === userId && row.chain === "APTOS")
    .map(row => [row.asset, {
      available: normalizeDecimal(row.available),
      locked: normalizeDecimal(row.locked)
    }]));
}

const user = await request("/api/users", {
  method: "POST",
  body: JSON.stringify({
    externalId: `aptos-e2e-${runId}`,
    displayName: "Aptos Full Flow User"
  })
});
const createAddress = version => request(`/api/users/${encodeURIComponent(user.id)}/addresses`, {
  method: "POST",
  body: JSON.stringify({ chain: "APTOS", addressVersion: version })
});
const address = await createAddress(0);
const replay = await createAddress(0);
assert.equal(replay.id, address.id);
assert.equal(replay.address, address.address);
const rotated = await createAddress(1);
assert.notEqual(rotated.id, address.id);
assert.notEqual(rotated.address, address.address);

await aptos("account", "transfer",
  "--account", address.address,
  "--amount", "500000000",
  "--profile", "fa-admin",
  "--assume-yes");
for (const symbol of ["usdc", "usdt"]) {
  await aptos("move", "run",
    "--function-id", `${publisher}::test_assets::mint_${symbol}`,
    "--args", `address:${address.address}`, "u64:20000000",
    "--profile", "fa-admin",
    "--assume-yes");
}

await waitFor("APT/USDC/USDT deposit callbacks", async () => {
  const balances = balanceMap(await request("/api/assets"), user.id);
  return balances.APT?.available === "5"
    && balances.USDC?.available === "20"
    && balances.USDT?.available === "20"
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
const collectionAddress = await waitFor("tenant APT/USDC/USDT collections", async () => {
  const result = await audit.query(`
    SELECT count(*)::integer AS count, min(to_address) AS target,
           count(distinct lower(to_address))::integer AS target_count
      FROM collection_record
     WHERE chain = 'APTOS' AND lower(from_address) = lower($1)
       AND asset_symbol IN ('APT', 'USDC', 'USDT') AND status = 'CONFIRMED'
  `, [address.address]);
  const row = result.rows[0];
  return row.count === 3 && row.target_count === 1 ? row.target : null;
});
await audit.end();
assert.notEqual(collectionAddress.toLowerCase(), address.address.toLowerCase());

const withdrawalRequests = [
  ["APT", "0.2"],
  ["USDC", "1"],
  ["USDT", "1"]
];
const withdrawals = [];
for (const [assetSymbol, amount] of withdrawalRequests) {
  withdrawals.push(await request(`/api/users/${encodeURIComponent(user.id)}/withdrawals`, {
    method: "POST",
    body: JSON.stringify({
      custodyAddressId: address.id,
      chain: "APTOS",
      assetSymbol,
      toAddress: publisher,
      amount
    })
  }));
}

const confirmed = await waitFor("APT/USDC/USDT withdrawal callbacks", async () => {
  const rows = await request("/api/withdrawals");
  const selected = rows.filter(row => withdrawals.some(item => item.id === row.id));
  return selected.length === 3 && selected.every(row => row.status === "CONFIRMED")
    ? selected
    : null;
});
const finalBalances = balanceMap(await request("/api/assets"), user.id);
assert.deepEqual(finalBalances.APT, { available: "4.8", locked: "0" });
assert.deepEqual(finalBalances.USDC, { available: "19", locked: "0" });
assert.deepEqual(finalBalances.USDT, { available: "19", locked: "0" });
const events = await request("/api/events");
assert.ok(events.filter(event => event.eventType === "DEPOSIT.CONFIRMED").length >= 3);
assert.ok(events.filter(event => event.eventType === "WITHDRAWAL.CONFIRMED").length >= 3);

console.log(JSON.stringify({
  ok: true,
  userId: user.id,
  subject: user.externalId,
  depositAddressId: address.id,
  depositAddress: address.address,
  rotatedAddress: rotated.address,
  collectionAddress,
  withdrawals: confirmed.map(row => ({ id: row.id, asset: row.asset, txHash: row.txHash })),
  balances: finalBalances
}, null, 2));
