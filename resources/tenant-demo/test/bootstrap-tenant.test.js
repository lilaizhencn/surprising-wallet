import assert from "node:assert/strict";
import { bootstrapTenant } from "../scripts/bootstrap-tenant.js";
import http from "node:http";
import { once } from "node:events";
import { test } from "node:test";

function json(response, value, cookie) {
  const body = JSON.stringify(value);
  response.writeHead(200, {
    "Content-Type": "application/json",
    "Content-Length": Buffer.byteLength(body),
    ...(cookie ? { "Set-Cookie": `${cookie}; Path=/custody; HttpOnly` } : {})
  });
  response.end(body);
}

async function listen(handler) {
  const server = http.createServer(handler);
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  return server;
}

test("bootstraps a tenant without printing generated API or webhook secrets", async () => {
  const demoConfiguration = [];
  const demo = await listen((request, response) => {
    demoConfiguration.push(request.url);
    response.writeHead(404).end();
  });
  const wallet = await listen((request, response) => {
    const route = `${request.method} ${request.url}`;
    const responses = {
      "POST /custody/platform/v1/auth/login": [{ token: "platform" }, "SW_CUSTODY_SESSION=platform"],
      "POST /custody/platform/v1/tenants": [{ id: "tenant-id" }],
      "POST /custody/platform/v1/auth/logout": [{ ok: true }],
      "POST /custody/console/v1/auth/login": [{ token: "tenant" }, "SW_CUSTODY_SESSION=tenant"],
      "PUT /custody/console/v1/chains/APTOS": [{ network: "testnet", assetSymbols: ["APT", "USDC", "USDT"] }],
      "POST /custody/console/v1/gas-accounts": [{ address: "0xgas" }],
      "POST /custody/console/v1/api-keys": [{ keyId: "swk_generated", secret: "sws_generated_secret" }],
      "POST /custody/console/v1/webhooks": [{ id: "webhook-id", signingSecret: "whsec_generated_secret" }],
      "POST /custody/console/v1/webhooks/webhook-id/verify": [{ status: "ACTIVE" }],
      "PATCH /custody/console/v1/webhooks/webhook-id/status": [{ ok: true }],
      "GET /custody/console/v1/onboarding": [{ ready: true }],
      "POST /custody/console/v1/auth/logout": [{ ok: true }]
    };
    const configured = responses[route];
    if (!configured) return response.writeHead(404).end();
    return json(response, configured[0], configured[1]);
  });
  try {
    const walletAddress = wallet.address();
    const demoAddress = demo.address();
    let secrets;
    const result = await bootstrapTenant({
      walletBaseUrl: `http://127.0.0.1:${walletAddress.port}`,
      demoBaseUrl: `http://127.0.0.1:${demoAddress.port}`,
      platformEmail: "platform@example.com", platformPassword: "platform-password",
      tenantPassword: "tenant-password", tenantEmail: "tenant@example.com",
      tenantSlug: "unit-test", chain: "APTOS", runId: "unit-test"
    }, async values => { secrets = values; });
    assert.equal(result.ok, true);
    assert.equal(JSON.stringify(result).includes("sws_generated_secret"), false);
    assert.equal(JSON.stringify(result).includes("whsec_generated_secret"), false);
    assert.equal(secrets.WALLET_API_SECRET, "sws_generated_secret");
    assert.equal(secrets.WEBHOOK_SECRET, "whsec_generated_secret");
    assert.equal(demoConfiguration.length, 0, "secrets must not be sent to demo HTTP API");
  } finally {
    wallet.close();
    demo.close();
  }
});
