import assert from "node:assert/strict";

const demoBaseUrl = String(process.env.DEMO_BASE_URL ?? "http://127.0.0.1:9300").replace(/\/+$/, "");
const chain = String(process.env.TEST_CHAIN ?? "APTOS").toUpperCase();
const runId = Date.now().toString(36);

async function request(path, options = {}) {
  const response = await fetch(`${demoBaseUrl}${path}`, {
    ...options,
    headers: { "Content-Type": "application/json", ...(options.headers ?? {}) }
  });
  const text = await response.text();
  const payload = text ? JSON.parse(text) : null;
  if (!response.ok) throw new Error(`${path} returned HTTP ${response.status}: ${payload?.message ?? text}`);
  return payload;
}

const health = await request("/health");
assert.equal(health.status, "UP");
const status = await request("/api/status");
assert.equal(status.configured, true, "tenant demo must have wallet API credentials");
const chains = await request("/api/chains");
const selected = chains.find(item => item.chain === chain);
assert.ok(selected, `${chain} must be opened for the demo tenant`);

const user = await request("/api/users", {
  method: "POST",
  body: JSON.stringify({ externalId: `verify-${chain.toLowerCase()}-${runId}`, displayName: `${chain} Verification` })
});
const createAddress = version => request(`/api/users/${encodeURIComponent(user.id)}/addresses`, {
  method: "POST",
  body: JSON.stringify({ chain, addressVersion: version })
});
const version0 = await createAddress(0);
const replay = await createAddress(0);
assert.equal(replay.id, version0.id, "same subject and version must return the same custody address");
assert.equal(replay.address, version0.address);
const version1 = await createAddress(1);
assert.notEqual(version1.id, version0.id, "rotated address must have a different custody address ID");
assert.notEqual(version1.address, version0.address, "rotated address must have a different chain address");

const localAddresses = await request("/api/addresses");
assert.ok(localAddresses.some(item => item.id === version0.id));
assert.ok(localAddresses.some(item => item.id === version1.id));
await request("/api/wallet/assets");

console.log(JSON.stringify({
  ok: true,
  chain,
  network: selected.network,
  subject: user.externalId,
  idempotentAddress: version0.address,
  rotatedAddress: version1.address,
  openedAssets: selected.assetSymbols
}, null, 2));
