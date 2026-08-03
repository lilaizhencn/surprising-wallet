import assert from "node:assert/strict";

const demoBaseUrl = String(process.env.DEMO_BASE_URL ?? "http://127.0.0.1:3001").replace(/\/+$/, "");
const requestedUsers = Number(process.env.STRESS_REAL_USERS ?? 20);
const amount = String(process.env.STRESS_REAL_AMOUNT ?? "0.1");
const credentialEntries = String(process.env.TEST_USER_CREDENTIALS ?? "")
  .split(",").map(value => value.trim()).filter(Boolean);
const credentials = credentialEntries.map(entry => {
  const separator = entry.indexOf("=");
  if (separator <= 0) throw new Error("TEST_USER_CREDENTIALS must use email=password entries");
  return { email: entry.slice(0, separator).trim(), password: entry.slice(separator + 1) };
}).filter(entry => entry.email !== "602884291@qq.com");

assert.ok(Number.isInteger(requestedUsers) && requestedUsers >= 2 && requestedUsers <= credentials.length,
  "STRESS_REAL_USERS must be between 2 and the available non-fixed accounts");

async function request(path, options = {}) {
  const response = await fetch(`${demoBaseUrl}${path}`, {
    ...options,
    headers: { "Content-Type": "application/json", ...(options.headers ?? {}) }
  });
  const text = await response.text();
  const payload = text ? JSON.parse(text) : null;
  if (!response.ok) {
    throw new Error(`${options.method ?? "GET"} ${path} returned HTTP ${response.status}: ${payload?.message ?? text}`);
  }
  return { payload, cookie: response.headers.get("set-cookie")?.split(";", 1)[0] };
}

const selected = credentials.slice(0, requestedUsers);
const accounts = await Promise.all(selected.map(async credential => {
  const login = await request("/api/auth/login", {
    method: "POST", body: JSON.stringify(credential)
  });
  const snapshot = (await request("/api/me", { headers: { Cookie: login.cookie } })).payload;
  const address = snapshot.addresses.find(row => row.chain === "ETH" && row.status === "ACTIVE");
  const balance = snapshot.balances.find(row => row.chain === "ETH" && row.asset === "ETH");
  assert.ok(address, `${credential.email} has no active ETH address`);
  assert.ok(balance && Number(balance.available) >= Number(amount), `${credential.email} has insufficient ETH`);
  return { email: credential.email, cookie: login.cookie, address, balanceBefore: balance.available };
}));

const withdrawals = await Promise.all(accounts.map((account, index) => request("/api/me/withdrawals", {
  method: "POST",
  headers: { Cookie: account.cookie },
  body: JSON.stringify({
    custodyAddressId: account.address.id,
    chain: "ETH",
    assetSymbol: "ETH",
    toAddress: `0x${(index + 0x1000).toString(16).padStart(40, "0")}`,
    amount
  })
}).then(result => ({ ...result.payload, email: account.email, cookie: account.cookie }))));

async function sleep(milliseconds) {
  await new Promise(resolve => setTimeout(resolve, milliseconds));
}

let snapshots = [];
for (let attempt = 1; attempt <= 36; attempt += 1) {
  snapshots = await Promise.all(withdrawals.map(withdrawal =>
    request("/api/me", { headers: { Cookie: withdrawal.cookie } }).then(result => result.payload)));
  const current = snapshots.map(snapshot => snapshot.withdrawals.find(row =>
    row.id === withdrawals.find(withdrawal => withdrawal.email === snapshot.user.email)?.id));
  if (current.every(row => ["CONFIRMED", "FAILED", "REQUEST_FAILED", "CANCELLED"].includes(row?.status))) break;
  await sleep(5_000);
}

const current = snapshots.map((snapshot, index) => {
  const withdrawal = snapshot.withdrawals.find(row => row.id === withdrawals[index].id);
  const balance = snapshot.balances.find(row => row.chain === "ETH" && row.asset === "ETH");
  const ledger = snapshot.ledger.filter(row => row.referenceId === withdrawals[index].externalReference);
  return { status: withdrawal?.status, txHash: withdrawal?.txHash, locked: balance?.locked, ledgerEntries: ledger.length };
});

assert.equal(current.length, requestedUsers);
assert.ok(current.every(row => row.status === "CONFIRMED"), JSON.stringify(current));
assert.ok(current.every(row => row.txHash), JSON.stringify(current));
assert.ok(current.every(row => row.locked === "0"), JSON.stringify(current));
assert.ok(current.every(row => row.ledgerEntries === 1), JSON.stringify(current));

const txGroups = new Map();
for (const row of current) txGroups.set(row.txHash, (txGroups.get(row.txHash) ?? 0) + 1);
console.log(JSON.stringify({
  ok: true,
  users: requestedUsers,
  amount,
  confirmed: current.length,
  sharedTxidGroups: [...txGroups.values()].filter(count => count > 1).length,
  maxBatchSize: Math.max(...txGroups.values()),
  lockedBalances: [...new Set(current.map(row => row.locked))],
  ledgerEntriesPerUser: [...new Set(current.map(row => row.ledgerEntries))]
}, null, 2));
