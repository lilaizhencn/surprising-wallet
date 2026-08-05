import assert from "node:assert/strict";
import { createHmac, randomUUID } from "node:crypto";

const demoBaseUrl = String(process.env.DEMO_BASE_URL ?? "http://127.0.0.1:3001").replace(/\/+$/, "");
const count = Number(process.env.STRESS_USERS ?? 40);
const password = String(process.env.STRESS_PASSWORD ?? "stress-password");
const chain = String(process.env.STRESS_CHAIN ?? "ETH").toUpperCase();
const asset = String(process.env.STRESS_ASSET ?? (chain === "BTC" ? "BTC" : "ETH")).toUpperCase();
const webhookSecret = String(process.env.STRESS_WEBHOOK_SECRET ?? "");
const runId = Date.now().toString(36);

if (!webhookSecret) throw new Error("STRESS_WEBHOOK_SECRET is required for callback simulation");
if (!Number.isInteger(count) || count < 1 || count > 200) throw new Error("STRESS_USERS must be 1..200");

async function request(path, options = {}) {
  const response = await fetch(`${demoBaseUrl}${path}`, {
    ...options,
    headers: { "Content-Type": "application/json", ...(options.headers ?? {}) }
  });
  const text = await response.text();
  const payload = text ? JSON.parse(text) : null;
  if (!response.ok) throw new Error(`${options.method ?? "GET"} ${path} returned HTTP ${response.status}: ${payload?.message ?? text}`);
  return { payload, cookie: response.headers.get("set-cookie")?.split(";", 1)[0] };
}

async function parallel(items, worker, batchSize = 10) {
  const results = [];
  for (let index = 0; index < items.length; index += batchSize) {
    results.push(...await Promise.all(items.slice(index, index + batchSize).map(worker)));
  }
  return results;
}

function webhookBody(id, type, data) {
  return JSON.stringify({ id, type, createdAt: new Date().toISOString(), data });
}

async function sendWebhook(id, type, data) {
  const body = webhookBody(id, type, data);
  const timestamp = String(Math.floor(Date.now() / 1000));
  const signature = createHmac("sha256", webhookSecret)
    .update(`${timestamp}.${id}.${type}.${body}`).digest("base64url");
  return request("/webhooks/custody", {
    method: "POST",
    headers: {
      "X-Custody-Event-Id": id,
      "X-Custody-Event-Type": type,
      "X-Custody-Timestamp": timestamp,
      "X-Custody-Signature": `v1=${signature}`
    }, body
  });
}

const accounts = await parallel(Array.from({ length: count }, (_, index) => index), async index => {
  const email = `stress-${runId}-${index}@example.test`;
  const registered = await request("/api/auth/register", {
    method: "POST", body: JSON.stringify({ email, password, displayName: `Stress ${index}` })
  });
  const address = (await request("/api/me/addresses", {
    method: "POST", headers: { Cookie: registered.cookie },
    body: JSON.stringify({ chain, addressVersion: 0 })
  })).payload;
  return { user: registered.payload.user, cookie: registered.cookie, address };
});

await parallel(accounts, async account => {
  const eventId = `stress-deposit-${runId}-${account.user.id}`;
  const data = { subject: account.user.externalId, chain, asset, address: account.address.address,
    amount: "1", txHash: `stress-deposit-tx-${runId}-${account.user.id}`, logIndex: 0 };
  await Promise.all([
    sendWebhook(eventId, "DEPOSIT.CONFIRMED", data),
    sendWebhook(eventId, "DEPOSIT.CONFIRMED", data)
  ]);
});

const withdrawals = await parallel(accounts, async account => (await request("/api/me/withdrawals", {
  method: "POST",
  headers: { Cookie: account.cookie, "Idempotency-Key": randomUUID() },
  body: JSON.stringify({ custodyAddressId: account.address.id, chain, assetSymbol: asset,
    toAddress: chain === "BTC" ? "bcrt1qexternalstress" : "0x1111111111111111111111111111111111111111",
    amount: "0.25" })
})).payload);

await parallel(withdrawals, async withdrawal => sendWebhook(
  `stress-withdrawal-${runId}-${withdrawal.id}`,
  "WITHDRAWAL.CONFIRMED",
  { withdrawalId: withdrawal.custodyWithdrawalId ?? withdrawal.id,
    externalReference: withdrawal.externalReference, chain, asset, amount: "0.25",
    status: "CONFIRMED", txHash: `stress-shared-eip7702-tx-${runId}` }
));

const snapshots = await parallel(accounts, async account =>
  (await request("/api/me", { headers: { Cookie: account.cookie } })).payload);
for (const snapshot of snapshots) {
  const balance = snapshot.balances.find(row => row.chain === chain && row.asset === asset);
  assert.deepEqual({ available: balance?.available, locked: balance?.locked }, { available: "0.75", locked: "0" });
  assert.equal(snapshot.ledger.length, 2);
  assert.equal(snapshot.withdrawals[0]?.status, "CONFIRMED");
}

console.log(JSON.stringify({
  ok: true, users: count, chain, asset, duplicateDeposits: count,
  sharedWithdrawalTxHash: `stress-shared-eip7702-tx-${runId}`,
  expectedPerUser: { available: "0.75", locked: "0", ledgerEntries: 2 }
}, null, 2));
