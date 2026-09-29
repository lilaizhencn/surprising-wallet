import assert from "node:assert/strict";

const base = process.argv[2];
assert.ok(base?.startsWith("https://"), "HTTPS deployment URL is required");
for (const path of ["/", "/health", "/api/status"]) {
  let response;
  let failure;
  for (let attempt = 0; attempt < 6; attempt++) {
    try {
      response = await fetch(`${base}${path}`, { signal: AbortSignal.timeout(15000) });
      if (response.ok) break;
    } catch (error) { failure = error; }
    await new Promise(resolve => setTimeout(resolve, 5000));
  }
  assert.ok(response, `${path} unreachable: ${failure?.message}`);
  assert.equal(response.status, 200, `${path} must be healthy`);
  if (path === "/health") assert.equal((await response.json()).status, "UP");
  if (path === "/api/status") {
    const status = await response.json();
    assert.equal(status.configured, true, "wallet API credentials must be configured");
    assert.equal(status.webhookConfigured, true, "webhook signing secret must be configured");
  }
  console.log(`PASS ${path}`);
}

// Optional deployment-only account: checks real native fetch and signed wallet API,
// without allocating addresses or making any financial request.
if (process.env.TENANT_DEMO_SMOKE_USER) {
  const user = JSON.parse(process.env.TENANT_DEMO_SMOKE_USER);
  const login = await fetch(`${base}/api/auth/login`, { method: "POST",
    headers: { "content-type": "application/json", origin: base },
    body: JSON.stringify(user), signal: AbortSignal.timeout(20000) });
  assert.equal(login.status, 200, "deployment account login must succeed");
  const cookie = login.headers.get("set-cookie")?.split(";")[0];
  assert.ok(cookie, "login must set a session cookie");
  try {
    const chains = await fetch(`${base}/api/chains`, { headers: { cookie },
      signal: AbortSignal.timeout(20000) });
    assert.equal(chains.status, 200, "signed wallet API must be reachable from Workers");
    assert.ok((await chains.json()).some(chain => chain.chain === "APTOS"));
    console.log("PASS login and signed wallet API");
  } finally {
    await fetch(`${base}/api/auth/logout`, { method: "POST", headers: { cookie, origin: base },
      signal: AbortSignal.timeout(20000) });
  }
}
