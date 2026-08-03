import assert from "node:assert/strict";

const demoBaseUrl = String(process.env.DEMO_BASE_URL ?? "http://127.0.0.1:3001").replace(/\/+$/, "");
const chain = String(process.env.TEST_CHAIN ?? "ETH").toUpperCase();
const runId = Date.now().toString(36);
const email = String(process.env.TEST_USER_EMAIL ?? `verify-${runId}@wallet-test.local`).toLowerCase();
const password = String(process.env.TEST_USER_PASSWORD ?? "verification-password");

async function request(path, options = {}) {
  const response = await fetch(`${demoBaseUrl}${path}`, {
    ...options,
    headers: { "Content-Type": "application/json", ...(options.headers ?? {}) }
  });
  const text = await response.text();
  const payload = text ? JSON.parse(text) : null;
  if (!response.ok) throw new Error(`${path} returned HTTP ${response.status}: ${payload?.message ?? text}`);
  return { payload, cookie: response.headers.get("set-cookie")?.split(";", 1)[0] };
}

const health = await request("/health");
assert.equal(health.payload.status, "UP");
const status = (await request("/api/status")).payload;
assert.equal(status.configured, true, "tenant demo must have wallet API credentials");
const login = await request("/api/auth/register", {
  method: "POST", body: JSON.stringify({ email, password, displayName: `Verification ${runId}` })
});
assert.ok(login.cookie, "register must set a session cookie");
const cookie = login.cookie;
const chains = (await request("/api/chains", { headers: { Cookie: cookie } })).payload;
const selected = chains.find(item => item.chain === chain);
assert.ok(selected, `${chain} must be opened for the demo tenant`);

const createAddress = version => request("/api/me/addresses", {
  method: "POST", headers: { Cookie: cookie },
  body: JSON.stringify({ chain, addressVersion: version })
});
const version0 = (await createAddress(0)).payload;
const replay = (await createAddress(0)).payload;
assert.equal(replay.id, version0.id, "same subject and version must return the same custody address");
assert.equal(replay.address, version0.address);
const version1 = (await createAddress(1)).payload;
assert.notEqual(version1.id, version0.id, "rotated address must have a different custody address ID");
assert.notEqual(version1.address, version0.address, "rotated address must have a different chain address");
const me = (await request("/api/me", { headers: { Cookie: cookie } })).payload;
assert.ok(me.addresses.some(item => item.id === version0.id));
assert.ok(me.addresses.some(item => item.id === version1.id));
await request("/api/wallet/assets", { headers: { Cookie: cookie } });

console.log(JSON.stringify({
  ok: true, chain, network: selected.network, email,
  idempotentAddress: version0.address, rotatedAddress: version1.address,
  openedAssets: selected.assetSymbols
}, null, 2));
