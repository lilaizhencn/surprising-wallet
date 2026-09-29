import assert from "node:assert/strict";
import { randomBytes } from "node:crypto";
import { writeFile, mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { pathToFileURL } from "node:url";
import { spawn } from "node:child_process";

/** Create a fresh wallet tenant and provision Worker Secrets before webhook verification. */
export async function bootstrapTenant(options, deploy) {
  const { walletBaseUrl, demoBaseUrl, platformEmail, platformPassword,
    tenantPassword, tenantSlug, tenantEmail, chain, runId = "cloudflare",
    demoSetupToken = randomBytes(32).toString("base64url") } = options;
  assert.ok(chain, "an explicitly selected platform-enabled TEST_CHAIN is required");

  async function request(baseUrl, path, { method = "GET", body, cookie } = {}) {
    const headers = {};
    if (body !== undefined) headers["Content-Type"] = "application/json";
    if (cookie) headers.Cookie = cookie;
    const response = await fetch(`${baseUrl}${path}`, {
      method,
      signal: AbortSignal.timeout(20000),
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
  const webhook = (await request(walletBaseUrl, "/custody/console/v1/webhooks", {
    method: "POST",
    cookie: tenantCookie,
    body: { name: `Tenant Demo ${runId}`, url: `${demoBaseUrl}/webhooks/custody` }
  })).payload;
  await deploy({
    TENANT_DEMO_SETUP_TOKEN: demoSetupToken,
    WALLET_KEY_ID: apiKey.keyId,
    WALLET_API_SECRET: apiKey.secret,
    WEBHOOK_SECRET: webhook.signingSecret
  }, { tenantId: tenant.id, webhookId: webhook.id, tenantEmail, tenantSlug });
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

  return {
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
  };

}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const required = name => {
    const value = process.env[name];
    if (!value) throw new Error(`${name} is required`);
    return value;
  };
  const runId = Date.now().toString(36);
  const result = await bootstrapTenant({
    walletBaseUrl: required("WALLET_BASE_URL"),
    demoBaseUrl: required("DEMO_BASE_URL"),
    platformEmail: required("PLATFORM_ADMIN_EMAIL"),
    platformPassword: required("PLATFORM_ADMIN_PASSWORD"),
    tenantPassword: required("TENANT_ADMIN_PASSWORD"),
    tenantSlug: required("TENANT_SLUG"),
    tenantEmail: required("TENANT_ADMIN_EMAIL"),
    chain: required("TEST_CHAIN"), runId
  }, async secrets => {
    const directory = await mkdtemp(join(tmpdir(), "tenant-demo-bootstrap-"));
    try {
      const file = join(directory, "secrets.json");
      await writeFile(file, JSON.stringify(secrets), { mode: 0o600 });
      const code = await new Promise((resolve, reject) => {
        const child = spawn("cf", ["deploy", "--secrets-file", file], { stdio: "inherit" });
        child.on("error", reject);
        child.on("exit", resolve);
      });
      assert.equal(code, 0, "Cloudflare deployment must succeed before webhook activation");
    } finally { await rm(directory, { recursive: true, force: true }); }
  });
  console.log(JSON.stringify(result, null, 2));
}
