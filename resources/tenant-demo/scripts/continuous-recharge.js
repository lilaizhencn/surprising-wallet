import assert from "node:assert/strict";

const demoBaseUrl = String(process.env.DEMO_BASE_URL ?? "http://127.0.0.1:3001").replace(/\/+$/, "");
const credentialEntries = String(process.env.TEST_USER_CREDENTIALS ?? "")
  .split(",").map(value => value.trim()).filter(Boolean);
const credentials = credentialEntries.length
  ? credentialEntries.map(entry => {
    const separator = entry.indexOf("=");
    if (separator <= 0) throw new Error("TEST_USER_CREDENTIALS must use email=password entries");
    return { email: entry.slice(0, separator).trim(), password: entry.slice(separator + 1) };
  })
  : String(process.env.TEST_USER_EMAILS ?? "").split(",").map(email => ({
    email: email.trim(), password: String(process.env.TEST_USER_PASSWORD ?? "")
  })).filter(entry => entry.email);
const chainOverride = String(process.env.CONTINUOUS_CHAIN ?? "").trim().toUpperCase();
const minDelay = Number(process.env.CONTINUOUS_MIN_DELAY_MS ?? 15_000);
const maxDelay = Number(process.env.CONTINUOUS_MAX_DELAY_MS ?? 90_000);
const maxCycles = Number(process.env.CONTINUOUS_MAX_CYCLES ?? 0);
if (!credentials.length || credentials.some(entry => entry.password.length < 8)) {
  throw new Error("TEST_USER_CREDENTIALS or TEST_USER_EMAILS and TEST_USER_PASSWORD are required");
}
if (!(maxDelay >= minDelay && minDelay >= 1_000)) throw new Error("continuous delay range is invalid");

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

const sessions = [];
for (const credential of credentials) {
  const login = await request("/api/auth/login", {
    method: "POST", body: JSON.stringify(credential)
  });
  const chains = (await request("/api/chains", { headers: { Cookie: login.cookie } })).payload;
  const selected = chains.find(row => row.chain === chainOverride) ?? chains[0];
  assert.ok(selected, `no enabled chain for ${credential.email}`);
  sessions.push({ email: credential.email, cookie: login.cookie, chain: selected.chain, version: 0 });
}

let cycle = 0;
while (maxCycles === 0 || cycle < maxCycles) {
  cycle += 1;
  for (const session of sessions) {
    session.version += 1;
    const address = (await request("/api/me/addresses", {
      method: "POST", headers: { Cookie: session.cookie },
      body: JSON.stringify({ chain: session.chain, addressVersion: session.version })
    })).payload;
    console.log(JSON.stringify({ event: "address-created", cycle, email: session.email,
      chain: session.chain, addressVersion: session.version, address: address.address }));
    const delay = minDelay + Math.floor(Math.random() * (maxDelay - minDelay + 1));
    await new Promise(resolve => setTimeout(resolve, delay));
  }
}
