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
const port = Number(process.env.TENANT_DEMO_PORT ?? 3001);
const publicBaseUrl = String(process.env.TENANT_DEMO_PUBLIC_BASE_URL
  ?? `http://127.0.0.1:${port}`).replace(/\/+$/, "");
const cookieName = "tenant_demo_session";
const cookieSecure = String(process.env.TENANT_DEMO_COOKIE_SECURE ?? "false") === "true";
const setupToken = String(process.env.TENANT_DEMO_SETUP_TOKEN ?? "").trim();
const store = await DemoStore.open();
const loginFailures = new Map();

function json(response, status, value, headers = {}) {
  const body = JSON.stringify(value);
  response.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Content-Length": Buffer.byteLength(body),
    "Cache-Control": "no-store",
    ...headers
  });
  response.end(body);
}

function error(message, status = 400, extra = {}) {
  return Object.assign(new Error(message), { status, ...extra });
}

async function body(request, limit = 2 * 1024 * 1024) {
  const chunks = [];
  let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > limit) throw error("request body exceeds 2 MiB", 413);
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
    throw error("request body must be valid JSON", 400);
  }
}

function parseCookies(request) {
  return Object.fromEntries(String(request.headers.cookie ?? "").split(";")
    .map(value => value.trim().split("="))
    .filter(([key, value]) => key && value)
    .map(([key, ...value]) => [key, decodeURIComponent(value.join("="))]));
}

function requestProtocol(request) {
  const forwarded = String(request.headers["x-forwarded-proto"] ?? "")
    .split(",", 1)[0].trim().toLowerCase();
  return forwarded || (request.socket.encrypted ? "https" : "http");
}

function shouldUseSecureCookie(request) {
  return cookieSecure && requestProtocol(request) === "https";
}

function sessionCookie(request, token, maxAge = 7 * 24 * 60 * 60) {
  return `${cookieName}=${encodeURIComponent(token)}; Path=/; HttpOnly; SameSite=Lax; Max-Age=${maxAge}`
    + (shouldUseSecureCookie(request) ? "; Secure" : "");
}

function clearSessionCookie(request) {
  return `${cookieName}=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0`
    + (shouldUseSecureCookie(request) ? "; Secure" : "");
}

async function currentUser(request) {
  const token = parseCookies(request)[cookieName];
  return store.sessionUser(token);
}

async function requireUser(request) {
  const user = await currentUser(request);
  if (!user) throw error("login required", 401);
  return user;
}

function requireSetup(request) {
  if (!setupToken || request.headers["x-tenant-demo-setup-token"] !== setupToken) {
    throw error("setup authorization required", 403);
  }
}

async function walletClient() {
  const config = await store.configuration();
  return new WalletClient({
    baseUrl: config.walletBaseUrl,
    keyId: config.walletKeyId,
    secret: config.walletApiSecret
  });
}

async function publicConfiguration() {
  const config = await store.configuration();
  return {
    walletBaseUrl: config.walletBaseUrl ?? "",
    configured: Boolean(config.walletBaseUrl && config.walletKeyId && config.walletApiSecret),
    webhookConfigured: Boolean(config.webhookSecret),
    webhookUrl: `${publicBaseUrl}/webhooks/custody`
  };
}

function checkLoginRateLimit(ip) {
  const entry = loginFailures.get(ip);
  if (entry?.blockedUntil > Date.now()) throw error("too many login attempts, retry later", 429);
}

function recordLoginFailure(ip) {
  const current = loginFailures.get(ip) ?? { count: 0, blockedUntil: 0 };
  current.count += 1;
  if (current.count >= 8) {
    current.count = 0;
    current.blockedUntil = Date.now() + 60_000;
  }
  loginFailures.set(ip, current);
}

function clearLoginFailures(ip) {
  loginFailures.delete(ip);
}

async function authApi(request, response, url) {
  if (request.method === "GET" && url.pathname === "/api/session") {
    const user = await currentUser(request);
    return json(response, 200, { authenticated: Boolean(user), user });
  }
  if (request.method === "POST" && url.pathname === "/api/auth/register") {
    const input = await jsonBody(request);
    try {
      const user = await store.registerUser(input);
      const token = await store.createSession(user.id);
      return json(response, 201, { user }, { "Set-Cookie": sessionCookie(request, token) });
    } catch (cause) {
      if (String(cause.message).includes("UNIQUE")) throw error("email is already registered", 409);
      throw cause;
    }
  }
  if (request.method === "POST" && url.pathname === "/api/auth/login") {
    const ip = request.socket.remoteAddress ?? "unknown";
    checkLoginRateLimit(ip);
    try {
      const user = await store.authenticateUser(await jsonBody(request));
      clearLoginFailures(ip);
      const token = await store.createSession(user.id);
      return json(response, 200, { user }, { "Set-Cookie": sessionCookie(request, token) });
    } catch (cause) {
      recordLoginFailure(ip);
      throw error(cause.message === "email or password is incorrect"
        ? cause.message : "email or password is incorrect", 401);
    }
  }
  if (request.method === "POST" && url.pathname === "/api/auth/logout") {
    await store.deleteSession(parseCookies(request)[cookieName]);
    return json(response, 200, { ok: true }, { "Set-Cookie": clearSessionCookie(request) });
  }
  return false;
}

async function api(request, response, url) {
  const handledAuth = await authApi(request, response, url);
  if (handledAuth !== false) return handledAuth;
  if (request.method === "GET" && url.pathname === "/api/status") {
    const [configuration, users, addresses, events] = await Promise.all([
      publicConfiguration(), store.users(), store.addresses(), store.webhookEvents()
    ]);
    return json(response, 200, {
      ...configuration, users: users.length, addresses: addresses.length, events: events.length
    });
  }
  if (request.method === "PUT" && url.pathname === "/api/config") {
    requireSetup(request);
    const input = await jsonBody(request);
    const current = await store.configuration();
    const update = {
      walletBaseUrl: input.walletBaseUrl ?? current.walletBaseUrl,
      walletKeyId: input.walletKeyId ?? current.walletKeyId,
      walletApiSecret: input.walletApiSecret && !input.walletApiSecret.includes("••")
        ? input.walletApiSecret : current.walletApiSecret,
      webhookSecret: input.webhookSecret && !input.webhookSecret.includes("••")
        ? input.webhookSecret : current.webhookSecret
    };
    if (!update.walletBaseUrl || !update.walletKeyId || !update.walletApiSecret) {
      throw error("walletBaseUrl, walletKeyId and walletApiSecret are required", 400);
    }
    await store.saveConfiguration(update);
    return json(response, 200, await publicConfiguration());
  }
  if (request.method === "GET" && url.pathname === "/api/chains") {
    await requireUser(request);
    return json(response, 200, await (await walletClient()).chains());
  }
  if (request.method === "GET" && url.pathname === "/api/admin/snapshot") {
    requireSetup(request);
    const [users, addresses, balances, ledger, withdrawals, events] = await Promise.all([
      store.users(), store.addresses(), store.balances(), store.ledger(),
      store.withdrawals(), store.webhookEvents()
    ]);
    return json(response, 200, { users, addresses, balances, ledger, withdrawals, events });
  }

  const user = await requireUser(request);
  if (request.method === "GET" && url.pathname === "/api/me") {
    const [addresses, balances, ledger, withdrawals] = await Promise.all([
      store.addresses(user.id), store.balances(user.id), store.ledger(user.id), store.withdrawals(user.id)
    ]);
    return json(response, 200, { user, addresses, balances, ledger, withdrawals });
  }
  if (request.method === "GET" && url.pathname === "/api/me/addresses") {
    return json(response, 200, await store.addresses(user.id));
  }
  if (request.method === "GET" && url.pathname === "/api/me/address-history") {
    return json(response, 200, await store.addressHistory(
      user.id, url.searchParams.get("chain"), url.searchParams.get("page"),
      url.searchParams.get("pageSize")));
  }
  if (request.method === "GET" && url.pathname === "/api/me/platform-addresses") {
    return json(response, 200, await store.platformAddresses(
      user.id, url.searchParams.get("chain"), url.searchParams.get("limit")));
  }
  if (request.method === "POST" && url.pathname === "/api/me/addresses") {
    const input = await jsonBody(request);
    const chain = String(input.chain ?? "").trim().toUpperCase();
    if (!chain) throw error("chain is required", 400);
    const addressVersion = input.addressVersion === undefined || input.addressVersion === null
      || String(input.addressVersion).trim() === ""
      ? await store.nextAddressVersion(user.id, chain)
      : Number(input.addressVersion);
    if (!Number.isInteger(addressVersion) || addressVersion < 0) {
      throw error("addressVersion must be a non-negative integer", 400);
    }
    const remote = await (await walletClient()).createAddress(
      chain, user.externalId, addressVersion
    );
    return json(response, 201, await store.saveAddress(user.id, remote));
  }
  if (request.method === "GET" && url.pathname === "/api/me/balances") {
    return json(response, 200, await store.balances(user.id));
  }
  if (request.method === "GET" && url.pathname === "/api/me/ledger") {
    return json(response, 200, await store.ledger(user.id));
  }
  if (request.method === "GET" && url.pathname === "/api/me/withdrawals") {
    return json(response, 200, await store.withdrawals(user.id));
  }
  if (request.method === "POST" && url.pathname === "/api/me/withdrawals") {
    const input = await jsonBody(request);
    const reserved = await store.reserveWithdrawal({
      userId: user.id,
      custodyAddressId: input.custodyAddressId || null,
      chain: input.chain,
      asset: input.assetSymbol,
      toAddress: input.toAddress,
      amount: input.amount
    });
    try {
      const remote = await (await walletClient()).createWithdrawal({
        custodyAddressId: reserved.custodyAddressId,
        chain: reserved.chain,
        assetSymbol: reserved.asset,
        toAddress: reserved.toAddress,
        amount: reserved.amount,
        externalReference: reserved.externalReference,
        confirmed: true
      }, reserved.idempotencyKey);
      return json(response, 202, await store.acceptWithdrawal(reserved.id, remote));
    } catch (cause) {
      await store.releaseWithdrawal(reserved.id, cause.message);
      throw cause;
    }
  }
  if (request.method === "GET" && url.pathname === "/api/wallet/assets") {
    return json(response, 200, await (await walletClient()).assets());
  }
  if (request.method === "GET" && url.pathname === "/api/wallet/deposits") {
    return json(response, 200, await (await walletClient()).deposits());
  }
  throw error("API route not found", 404);
}

async function webhook(request, response) {
  const raw = await body(request);
  const config = await store.configuration();
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
  await store.receiveWebhook(event, raw);
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
      "Content-Length": content.length,
      "Cache-Control": "no-cache"
    });
    response.end(content);
  } catch (cause) {
    if (cause.code === "ENOENT") return json(response, 404, { error: "NOT_FOUND" });
    throw cause;
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
    return json(response, 404, { error: "NOT_FOUND" });
  } catch (cause) {
    console.error(`[${randomUUID()}] ${request.method} ${url.pathname}: ${cause.stack ?? cause}`);
    return json(response, cause.status ?? 400, {
      error: cause.name === "WalletApiError" ? "WALLET_API_ERROR" : "DEMO_ERROR",
      message: cause.message, walletStatus: cause.status, walletPayload: cause.payload
    });
  }
});

server.listen(port, "127.0.0.1", () => {
  console.log(`Tenant wallet demo: ${publicBaseUrl}`);
  console.log(`Custody webhook URL: ${publicBaseUrl}/webhooks/custody`);
});

for (const signal of ["SIGINT", "SIGTERM"]) {
  process.on(signal, () => {
    server.close(async () => {
      await store.close();
      process.exit(0);
    });
  });
}
