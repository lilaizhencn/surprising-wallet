import assert from "node:assert/strict";

const walletBaseUrl = String(process.env.WALLET_BASE_URL ?? "http://127.0.0.1:8002").replace(/\/+$/, "");
const demoBaseUrl = String(process.env.DEMO_BASE_URL ?? "http://127.0.0.1:9300").replace(/\/+$/, "");
const platformEmail = required("PLATFORM_ADMIN_EMAIL");
const platformPassword = required("PLATFORM_ADMIN_PASSWORD");
const tenantPassword = required("TENANT_ADMIN_PASSWORD");
const chain = String(process.env.TEST_CHAIN ?? "APTOS").toUpperCase();
const runId = String(process.env.TEST_RUN_ID ?? Date.now().toString(36)).toLowerCase();
const tenantSlug = String(process.env.TENANT_SLUG ?? `wallet-test-${runId}`).toLowerCase();
const tenantEmail = String(process.env.TENANT_ADMIN_EMAIL ?? `admin-${runId}@wallet-test.local`).toLowerCase();

function required(name) {
  const value = String(process.env[name] ?? "").trim();
  if (!value) throw new Error(`${name} is required`);
  return value;
}

async function request(baseUrl, path, { method = "GET", body, cookie } = {}) {
  const headers = {};
  if (body !== undefined) headers["Content-Type"] = "application/json";
  if (cookie) headers.Cookie = cookie;
  const response = await fetch(`${baseUrl}${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  const text = await response.text();
  let payload = null;
  if (text) {
    try { payload = JSON.parse(text); } catch { payload = { message: text }; }
  }
  if (!response.ok) {
    throw new Error(`${method} ${path} returned HTTP ${response.status}: ${payload?.message ?? text}`);
  }
  return { payload, cookie: response.headers.get("set-cookie")?.split(";", 1)[0] };
}

const platformLogin = await request(walletBaseUrl, "/custody/platform/v1/auth/login", {
  method: "POST",
  body: { email: platformEmail, password: platformPassword }
});
assert.ok(platformLogin.cookie, "platform login must set a session cookie");
const tenant = (await request(walletBaseUrl, "/custody/platform/v1/tenants", {
  method: "POST",
  cookie: platformLogin.cookie,
  body: {
    slug: tenantSlug,
    name: `Wallet Test ${runId}`,
    adminEmail: tenantEmail,
    adminDisplayName: "Test Tenant Administrator",
    adminPassword: tenantPassword
  }
})).payload;
await request(walletBaseUrl, "/custody/platform/v1/auth/logout", {
  method: "POST",
  cookie: platformLogin.cookie
});

const tenantLogin = await request(walletBaseUrl, "/custody/console/v1/auth/login", {
  method: "POST",
  body: { email: tenantEmail, password: tenantPassword }
});
assert.ok(tenantLogin.cookie, "tenant login must set a session cookie");
const tenantCookie = tenantLogin.cookie;
const openedChain = (await request(walletBaseUrl, `/custody/console/v1/chains/${chain}`, {
  method: "PUT",
  cookie: tenantCookie,
  body: { enabled: true }
})).payload;
const gasAccount = (await request(walletBaseUrl, "/custody/console/v1/gas-accounts", {
  method: "POST",
  cookie: tenantCookie,
  body: { chain }
})).payload;
const apiKey = (await request(walletBaseUrl, "/custody/console/v1/api-keys", {
  method: "POST",
  cookie: tenantCookie,
  body: { name: `Tenant Demo ${runId}` }
})).payload;
const demoStatus = (await request(demoBaseUrl, "/api/status")).payload;
await request(demoBaseUrl, "/api/config", {
  method: "PUT",
  body: {
    walletBaseUrl,
    walletKeyId: apiKey.keyId,
    walletApiSecret: apiKey.secret
  }
});
const webhook = (await request(walletBaseUrl, "/custody/console/v1/webhooks", {
  method: "POST",
  cookie: tenantCookie,
  body: { name: `Tenant Demo ${runId}`, url: demoStatus.webhookUrl }
})).payload;
await request(demoBaseUrl, "/api/config", {
  method: "PUT",
  body: { webhookSecret: webhook.signingSecret }
});
await request(walletBaseUrl, `/custody/console/v1/webhooks/${webhook.id}/verify`, {
  method: "POST",
  cookie: tenantCookie
});
await request(walletBaseUrl, `/custody/console/v1/webhooks/${webhook.id}/status`, {
  method: "PATCH",
  cookie: tenantCookie,
  body: { enabled: true }
});
const onboarding = (await request(walletBaseUrl, "/custody/console/v1/onboarding", {
  cookie: tenantCookie
})).payload;
await request(walletBaseUrl, "/custody/console/v1/auth/logout", {
  method: "POST",
  cookie: tenantCookie
});

console.log(JSON.stringify({
  ok: true,
  tenantId: tenant.id,
  tenantSlug,
  tenantEmail,
  chain,
  network: openedChain.network,
  assets: openedChain.assetSymbols,
  gasAddress: gasAccount.address,
  webhookStatus: "ACTIVE",
  onboarding
}, null, 2));
