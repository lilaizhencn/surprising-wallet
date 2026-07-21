import assert from "node:assert/strict";
import { spawn } from "node:child_process";
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

function run(command, args, env) {
  return new Promise(resolve => {
    const child = spawn(command, args, { env, stdio: ["ignore", "pipe", "pipe"] });
    let stdout = "";
    let stderr = "";
    child.stdout.on("data", chunk => stdout += chunk);
    child.stderr.on("data", chunk => stderr += chunk);
    child.on("close", code => resolve({ code, stdout, stderr }));
  });
}

test("bootstraps a tenant without printing generated API or webhook secrets", async () => {
  const demoConfiguration = [];
  const demo = await listen(async (request, response) => {
    if (request.method === "GET" && request.url === "/api/status") {
      return json(response, { webhookUrl: "http://127.0.0.1:9999/webhooks/custody" });
    }
    if (request.method === "PUT" && request.url === "/api/config") {
      let raw = "";
      for await (const chunk of request) raw += chunk;
      demoConfiguration.push(JSON.parse(raw));
      return json(response, { configured: true });
    }
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
    const result = await run(process.execPath, ["scripts/bootstrap-tenant.js"], {
      ...process.env,
      WALLET_BASE_URL: `http://127.0.0.1:${walletAddress.port}`,
      DEMO_BASE_URL: `http://127.0.0.1:${demoAddress.port}`,
      PLATFORM_ADMIN_EMAIL: "platform@example.com",
      PLATFORM_ADMIN_PASSWORD: "platform-password",
      TENANT_ADMIN_PASSWORD: "tenant-password",
      TEST_RUN_ID: "unit-test",
      TEST_CHAIN: "APTOS"
    });
    assert.equal(result.code, 0, result.stderr);
    assert.equal(JSON.parse(result.stdout).ok, true);
    assert.equal(result.stdout.includes("sws_generated_secret"), false);
    assert.equal(result.stdout.includes("whsec_generated_secret"), false);
    assert.equal(demoConfiguration[0].walletApiSecret, "sws_generated_secret");
    assert.equal(demoConfiguration[1].webhookSecret, "whsec_generated_secret");
  } finally {
    wallet.close();
    demo.close();
  }
});
