import { randomUUID } from "node:crypto";
import { readFile } from "node:fs/promises";
import http from "node:http";
import { dirname, extname, join, normalize } from "node:path";
import { fileURLToPath } from "node:url";
import { DemoStore } from "./store.js";
import { verifyWebhook } from "./webhook.js";
import { WalletClient } from "./wallet-client.js";

const moduleDir = dirname(fileURLToPath(import.meta.url));
const projectDir = dirname(moduleDir);
const publicDir = join(projectDir, "public");
const port = Number(process.env.TENANT_DEMO_PORT ?? 9300);
const databaseFile = process.env.TENANT_DEMO_DB ?? join(projectDir, "data", "tenant-demo.db");
const store = new DemoStore(databaseFile);

function json(response, status, value) {
  const body = JSON.stringify(value);
  response.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Content-Length": Buffer.byteLength(body),
    "Cache-Control": "no-store"
  });
  response.end(body);
}

async function body(request, limit = 2 * 1024 * 1024) {
  const chunks = [];
  let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > limit) throw Object.assign(new Error("request body exceeds 2 MiB"), { status: 413 });
    chunks.push(chunk);
  }
  return Buffer.concat(chunks).toString("utf8");
}

async function jsonBody(request) {
  const raw = await body(request);
  if (!raw) return {};
  try {
    return JSON.parse(raw);
  } catch {
    throw Object.assign(new Error("request body must be valid JSON"), { status: 400 });
  }
}

function walletClient() {
  const config = store.configuration();
  return new WalletClient({
    baseUrl: config.walletBaseUrl,
    keyId: config.walletKeyId,
    secret: config.walletApiSecret
  });
}

function publicConfiguration() {
  const config = store.configuration();
  const mask = value => value ? `${value.slice(0, 7)}••••${value.slice(-4)}` : "";
  return {
    walletBaseUrl: config.walletBaseUrl ?? "http://127.0.0.1:8002",
    walletKeyId: config.walletKeyId ?? "",
    walletApiSecret: mask(config.walletApiSecret),
    webhookSecret: mask(config.webhookSecret),
    webhookUrl: `http://127.0.0.1:${port}/webhooks/custody`,
    configured: Boolean(config.walletBaseUrl && config.walletKeyId && config.walletApiSecret)
  };
}

async function api(request, response, url) {
  if (request.method === "GET" && url.pathname === "/api/status") {
    return json(response, 200, {
      ...publicConfiguration(),
      users: store.users().length,
      addresses: store.addresses().length,
      events: store.webhookEvents().length
    });
  }
  if (request.method === "PUT" && url.pathname === "/api/config") {
    const input = await jsonBody(request);
    const current = store.configuration();
    const update = {
      walletBaseUrl: input.walletBaseUrl ?? current.walletBaseUrl ?? "http://127.0.0.1:8002",
      walletKeyId: input.walletKeyId ?? current.walletKeyId,
      walletApiSecret: input.walletApiSecret && !input.walletApiSecret.includes("••")
        ? input.walletApiSecret
        : current.walletApiSecret,
      webhookSecret: input.webhookSecret && !input.webhookSecret.includes("••")
        ? input.webhookSecret
        : current.webhookSecret
    };
    if (!update.walletBaseUrl || !update.walletKeyId || !update.walletApiSecret) {
      throw new Error("walletBaseUrl, walletKeyId and walletApiSecret are required");
    }
    store.saveConfiguration(update);
    return json(response, 200, publicConfiguration());
  }
  if (request.method === "GET" && url.pathname === "/api/users") {
    return json(response, 200, store.users());
  }
  if (request.method === "POST" && url.pathname === "/api/users") {
    return json(response, 201, store.createUser(await jsonBody(request)));
  }
  if (request.method === "GET" && url.pathname === "/api/addresses") {
    return json(response, 200, store.addresses());
  }
  const addressMatch = /^\/api\/users\/([^/]+)\/addresses$/.exec(url.pathname);
  if (request.method === "POST" && addressMatch) {
    const user = store.user(decodeURIComponent(addressMatch[1]));
    const input = await jsonBody(request);
    const remote = await walletClient().createAddress(
      String(input.chain ?? "").toUpperCase(), user.externalId, Number(input.addressVersion ?? 0)
    );
    return json(response, 201, store.saveAddress(user.id, remote));
  }
  if (request.method === "GET" && url.pathname === "/api/chains") {
    return json(response, 200, await walletClient().chains());
  }
  if (request.method === "GET" && url.pathname === "/api/assets") {
    return json(response, 200, store.balances());
  }
  if (request.method === "GET" && url.pathname === "/api/ledger") {
    return json(response, 200, store.ledger());
  }
  if (request.method === "GET" && url.pathname === "/api/wallet/assets") {
    return json(response, 200, await walletClient().assets());
  }
  if (request.method === "GET" && url.pathname === "/api/wallet/deposits") {
    return json(response, 200, await walletClient().deposits());
  }
  if (request.method === "GET" && url.pathname === "/api/withdrawals") {
    return json(response, 200, store.withdrawals());
  }
  const withdrawalMatch = /^\/api\/users\/([^/]+)\/withdrawals$/.exec(url.pathname);
  if (request.method === "POST" && withdrawalMatch) {
    const userId = decodeURIComponent(withdrawalMatch[1]);
    const input = await jsonBody(request);
    const reserved = store.reserveWithdrawal({
      userId,
      custodyAddressId: input.custodyAddressId,
      chain: String(input.chain ?? "").toUpperCase(),
      asset: String(input.assetSymbol ?? "").toUpperCase(),
      toAddress: String(input.toAddress ?? "").trim(),
      amount: input.amount
    });
    try {
      const remote = await walletClient().createWithdrawal({
        custodyAddressId: reserved.custodyAddressId,
        chain: reserved.chain,
        assetSymbol: reserved.asset,
        toAddress: reserved.toAddress,
        amount: reserved.amount,
        externalReference: reserved.externalReference,
        confirmed: true
      }, reserved.idempotencyKey);
      return json(response, 201, store.acceptWithdrawal(reserved.id, remote));
    } catch (error) {
      store.releaseWithdrawal(reserved.id, error.message);
      throw error;
    }
  }
  if (request.method === "GET" && url.pathname === "/api/events") {
    return json(response, 200, store.webhookEvents());
  }
  if (request.method === "GET" && url.pathname === "/api/snapshot") {
    return json(response, 200, {
      users: store.users(),
      addresses: store.addresses(),
      balances: store.balances(),
      withdrawals: store.withdrawals(),
      events: store.webhookEvents()
    });
  }
  json(response, 404, { error: "NOT_FOUND", message: "API route not found" });
}

async function webhook(request, response) {
  const raw = await body(request);
  const config = store.configuration();
  const verified = verifyWebhook({
    secret: config.webhookSecret,
    timestamp: request.headers["x-custody-timestamp"],
    signature: request.headers["x-custody-signature"],
    body: raw
  });
  if (!verified) return json(response, 401, { error: "INVALID_SIGNATURE" });
  let event;
  try {
    event = JSON.parse(raw);
  } catch {
    return json(response, 400, { error: "INVALID_JSON" });
  }
  if (event.type === "WEBHOOK.VERIFICATION") {
    return json(response, 200, { challenge: event.data?.challenge });
  }
  store.receiveWebhook(event, raw);
  return json(response, 200, { received: true, eventId: event.id });
}

async function staticFile(response, pathname) {
  const requested = pathname === "/" ? "index.html" : pathname.slice(1);
  const file = normalize(join(publicDir, requested));
  if (!file.startsWith(publicDir)) return json(response, 403, { error: "FORBIDDEN" });
  const types = {
    ".html": "text/html; charset=utf-8",
    ".css": "text/css; charset=utf-8",
    ".js": "text/javascript; charset=utf-8",
    ".svg": "image/svg+xml"
  };
  try {
    const content = await readFile(file);
    response.writeHead(200, {
      "Content-Type": types[extname(file)] ?? "application/octet-stream",
      "Content-Length": content.length
    });
    response.end(content);
  } catch (error) {
    if (error.code === "ENOENT") return json(response, 404, { error: "NOT_FOUND" });
    throw error;
  }
}

const server = http.createServer(async (request, response) => {
  const url = new URL(request.url, `http://${request.headers.host ?? "127.0.0.1"}`);
  try {
    if (request.method === "GET" && url.pathname === "/health") {
      return json(response, 200, { status: "UP" });
    }
    if (request.method === "POST" && url.pathname === "/webhooks/custody") {
      return await webhook(request, response);
    }
    if (url.pathname.startsWith("/api/")) return await api(request, response, url);
    if (request.method === "GET") return await staticFile(response, url.pathname);
    json(response, 404, { error: "NOT_FOUND" });
  } catch (error) {
    console.error(`[${randomUUID()}] ${request.method} ${url.pathname}: ${error.stack ?? error}`);
    json(response, error.status ?? 400, {
      error: error.name === "WalletApiError" ? "WALLET_API_ERROR" : "DEMO_ERROR",
      message: error.message,
      walletStatus: error.status,
      walletPayload: error.payload
    });
  }
});

server.listen(port, "127.0.0.1", () => {
  console.log(`Tenant exchange demo: http://127.0.0.1:${port}`);
  console.log(`Custody webhook URL: http://127.0.0.1:${port}/webhooks/custody`);
});

for (const signal of ["SIGINT", "SIGTERM"]) {
  process.on(signal, () => {
    server.close(() => {
      store.close();
      process.exit(0);
    });
  });
}
