import { randomUUID, timingSafeEqual } from "node:crypto";
import { subtractDecimal } from "./decimal.js";
import { verifyWebhook } from "./webhook.js";
import { WalletClient } from "./wallet-client.js";

/** One handler per Durable Object. Calls are serialized by the owning object. */
export function createHandler(store, storage, env, fetchImpl = fetch) {
  const cookieName = "tenant_demo_session";
  const setupToken = env.TENANT_DEMO_SETUP_TOKEN;
  const publicBaseUrl = env.TENANT_DEMO_PUBLIC_BASE_URL;
  const configuration = () => ({
    walletBaseUrl: env.WALLET_BASE_URL,
    walletKeyId: env.WALLET_KEY_ID,
    walletApiSecret: env.WALLET_API_SECRET,
    webhookSecret: env.WEBHOOK_SECRET
  });
  const defaultWithdrawalFees = Object.freeze({
    USDT: "1", USDC: "1", DAI: "1", BUSD: "1", TUSD: "1",
    ETH: "0.001", BTC: "0.0001", SOL: "0.01", BNB: "0.001",
    MATIC: "1", POL: "1", AVAX: "0.01", DOT: "0.1", NEAR: "0.1",
    ADA: "1", XRP: "0.1", TRX: "1", TON: "0.01", SUI: "0.01",
    APTOS: "0.01", LTC: "0.001", DOGE: "1", BCH: "0.001"
  });

  async function withdrawalFeeConfig() {
    return defaultWithdrawalFees;
  }

  function json(status, value, headers = {}) {
    const body = JSON.stringify(value);
    return new Response(body, { status, headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store",
      ...headers
    }});
  }

  function error(message, status = 400, extra = {}) {
    return Object.assign(new Error(message), { status, ...extra });
  }

  async function body(request, limit = 2 * 1024 * 1024) {
    const chunks = [];
    let size = 0;
    for await (const chunk of request.body ?? []) {
      size += chunk.byteLength;
      if (size > limit) throw error("request body exceeds 2 MiB", 413);
      chunks.push(Buffer.from(chunk));
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
    return Object.fromEntries(String(request.headers.get("cookie") ?? "").split(";")
      .map(value => value.trim().split("="))
      .filter(([key, value]) => key && value)
      .map(([key, ...value]) => [key, decodeURIComponent(value.join("="))]));
  }

  function shouldUseSecureCookie(request) {
    return new URL(request.url).protocol === "https:";
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
    const supplied = Buffer.from(request.headers.get("x-tenant-demo-setup-token") ?? "");
    const expected = Buffer.from(setupToken ?? "");
    if (!setupToken || supplied.length !== expected.length || !timingSafeEqual(supplied, expected)) {
      throw error("setup authorization required", 403);
    }
  }

  async function walletClient() {
    const config = configuration();
    return new WalletClient({
      baseUrl: config.walletBaseUrl,
      keyId: config.walletKeyId,
      secret: config.walletApiSecret,
      fetchImpl
    });
  }

  async function publicConfiguration() {
    const config = configuration();
    return {
      walletBaseUrl: config.walletBaseUrl ?? "",
      configured: Boolean(config.walletBaseUrl && config.walletKeyId && config.walletApiSecret),
      webhookConfigured: Boolean(config.webhookSecret),
      webhookUrl: `${publicBaseUrl}/webhooks/custody`
    };
  }

  async function checkLoginRateLimit(ip) {
    const entry = await storage.get(`login:${ip}`);
    if (entry?.blockedUntil > Date.now()) throw error("too many login attempts, retry later", 429);
  }

  async function recordLoginFailure(ip) {
    const previous = await storage.get(`login:${ip}`);
    const current = previous?.expiresAt > Date.now() ? previous : { count: 0, blockedUntil: 0 };
    current.count += 1;
    if (current.count >= 8) {
      current.count = 0;
      current.blockedUntil = Date.now() + 60_000;
    }
    current.expiresAt = Date.now() + 60_000;
    await storage.put(`login:${ip}`, current);
  }

  async function clearLoginFailures(ip) {
    await storage.delete(`login:${ip}`);
  }

  /** 判断钱包 API 是否明确拒绝了请求；网络错误和 5xx 结果不能直接解冻。 */
  function isDefinitiveWalletRejection(cause) {
    const status = Number(cause?.status);
    return Number.isInteger(status) && status >= 400 && status < 500 && status !== 408 && status !== 429;
  }

  /** 读取分页参数并限制单次返回量，避免 Console 请求拖垮租户 Demo。 */
  function pageParameter(value, fallback, maximum) {
    const parsed = Number(value);
    if (!Number.isInteger(parsed)) return fallback;
    return Math.min(Math.max(parsed, 1), maximum);
  }

  async function authApi(request, url) {
    if (request.method === "GET" && url.pathname === "/api/session") {
      const user = await currentUser(request);
      return json(200, { authenticated: Boolean(user), user });
    }
    if (request.method === "POST" && url.pathname === "/api/auth/register") {
      const input = await jsonBody(request);
      try {
        const user = await store.registerUser(input);
        const token = await store.createSession(user.id);
        return json(201, { user }, { "Set-Cookie": sessionCookie(request, token) });
      } catch (cause) {
        if (String(cause.message).includes("UNIQUE")) throw error("email is already registered", 409);
        throw cause;
      }
    }
    if (request.method === "POST" && url.pathname === "/api/auth/login") {
      const ip = request.headers.get("cf-connecting-ip") ?? "unknown";
      await checkLoginRateLimit(ip);
      try {
        const user = await store.authenticateUser(await jsonBody(request));
        await clearLoginFailures(ip);
        const token = await store.createSession(user.id);
        return json(200, { user }, { "Set-Cookie": sessionCookie(request, token) });
      } catch (cause) {
        await recordLoginFailure(ip);
        throw error(cause.message === "email or password is incorrect"
          ? cause.message : "email or password is incorrect", 401);
      }
    }
    if (request.method === "POST" && url.pathname === "/api/auth/logout") {
      await store.deleteSession(parseCookies(request)[cookieName]);
      return json(200, { ok: true }, { "Set-Cookie": clearSessionCookie(request) });
    }
    return false;
  }

  async function api(request, url) {
    const handledAuth = await authApi(request, url);
    if (handledAuth !== false) return handledAuth;
    if (request.method === "GET" && url.pathname === "/api/status") {
      const [config, users, addresses, events, fees] = await Promise.all([
        publicConfiguration(), store.users(), store.addresses(), store.webhookEvents(),
        withdrawalFeeConfig()
      ]);
      return json(200, {
        ...config, users: users.length, addresses: addresses.length, events: events.length,
        withdrawalFees: fees
      });
    }
    if (request.method === "GET" && url.pathname === "/api/chains") {
      await requireUser(request);
      return json(200, await (await walletClient()).chains());
    }
    if (request.method === "GET" && url.pathname === "/api/admin/snapshot") {
      requireSetup(request);
      const [users, addresses, balances, ledger, withdrawals, events] = await Promise.all([
        store.users(), store.addresses(), store.balances(), store.ledger(),
        store.withdrawals(), store.webhookEvents()
      ]);
      return json(200, { users, addresses, balances, ledger, withdrawals, events });
    }

    const user = await requireUser(request);
    const detailMatch = /^\/api\/me\/(ledger|withdrawals)\/([^/]+)$/.exec(url.pathname);
    if (request.method === "GET" && detailMatch) {
      const id = decodeURIComponent(detailMatch[2]);
      return json(200, detailMatch[1] === "ledger"
        ? await store.ledgerDetail(user.id, id)
        : await store.withdrawalDetail(user.id, id));
    }
    if (request.method === "GET" && url.pathname === "/api/me") {
      const [addresses, balances, ledger, withdrawals] = await Promise.all([
        store.addresses(user.id), store.balances(user.id), store.ledger(user.id), store.withdrawals(user.id)
      ]);
      return json(200, { user, addresses, balances, ledger, withdrawals });
    }
    if (request.method === "GET" && url.pathname === "/api/me/addresses") {
      return json(200, await store.addresses(user.id));
    }
    if (request.method === "GET" && url.pathname === "/api/me/address-history") {
      return json(200, await store.addressHistory(
        user.id, url.searchParams.get("chain"), url.searchParams.get("page"),
        url.searchParams.get("pageSize")));
    }
    if (request.method === "GET" && url.pathname === "/api/me/platform-addresses") {
      return json(200, await store.platformAddresses(
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
      return json(201, await store.saveAddress(user.id, remote));
    }
    if (request.method === "GET" && url.pathname === "/api/me/balances") {
      return json(200, await store.balances(user.id));
    }
    if (request.method === "GET" && url.pathname === "/api/me/ledger") {
      return json(200, await store.ledgerPage(user.id, {
        entryType: url.searchParams.get("entryType"),
        txId: url.searchParams.get("txId"),
        address: url.searchParams.get("address"),
        businessOrderNo: url.searchParams.get("businessOrderNo"),
        page: pageParameter(url.searchParams.get("page"), 1, 1000000),
        pageSize: pageParameter(url.searchParams.get("pageSize"), 10, 100)
      }));
    }
    if (request.method === "GET" && url.pathname === "/api/me/withdrawals") {
      return json(200, await store.withdrawalsPage(user.id, {
        businessOrderNo: url.searchParams.get("businessOrderNo"),
        address: url.searchParams.get("address"),
        txId: url.searchParams.get("txId"),
        status: url.searchParams.get("status"),
        page: pageParameter(url.searchParams.get("page"), 1, 1000000),
        pageSize: pageParameter(url.searchParams.get("pageSize"), 10, 100)
      }));
    }
    if (request.method === "POST" && url.pathname === "/api/me/withdrawals") {
      const input = await jsonBody(request);
      const idempotencyKey = String(request.headers.get("idempotency-key") ?? "").trim();
      if (!idempotencyKey) throw error("Idempotency-Key is required", 400);
      const existing = await store.withdrawalByIdempotency(user.id, idempotencyKey);
      if (existing) return json(202, existing);
      const fees = await withdrawalFeeConfig();
      const platformFee = fees[String(input.assetSymbol ?? "").toUpperCase().trim()] ?? "0";
      let reserved;
      try {
        reserved = await store.reserveWithdrawal({
          userId: user.id,
          custodyAddressId: input.custodyAddressId || null,
          chain: input.chain,
          asset: input.assetSymbol,
          toAddress: input.toAddress,
          amount: input.amount,
          businessOrderNo: input.businessOrderNo,
          idempotencyKey,
          platformFee
        });
      } catch (cause) {
        if (String(cause.message).includes("UNIQUE") || String(cause.message).includes("idempotency")) {
          const duplicate = await store.withdrawalByIdempotency(user.id, idempotencyKey);
          if (duplicate) return json(202, duplicate);
        }
        if (String(cause.message).includes("business order number already exists")) {
          throw error(cause.message, 409);
        }
        throw cause;
      }
      try {
        const netAmount = platformFee && platformFee !== "0"
          ? subtractDecimal(reserved.amount, platformFee)
          : reserved.amount;
        const remote = await (await walletClient()).createWithdrawal({
          custodyAddressId: reserved.custodyAddressId,
          chain: reserved.chain,
          assetSymbol: reserved.asset,
          toAddress: reserved.toAddress,
          amount: netAmount,
          externalReference: reserved.externalReference,
          confirmed: true
        }, reserved.idempotencyKey);
        return json(202, await store.acceptWithdrawal(reserved.id, remote));
      } catch (cause) {
        if (isDefinitiveWalletRejection(cause)) {
          await store.releaseWithdrawal(reserved.id, cause.message);
          throw cause;
        }
        return json(202, await store.markWithdrawalPending(reserved.id, cause.message));
      }
    }
    if (request.method === "GET" && url.pathname === "/api/wallet/assets") {
      return json(200, await (await walletClient()).assets());
    }
    if (request.method === "GET" && url.pathname === "/api/wallet/deposits") {
      return json(200, await (await walletClient()).deposits());
    }
    throw error("API route not found", 404);
  }

  async function webhook(request) {
    const raw = await body(request);
    const config = configuration();
    const verified = verifyWebhook({
      secret: config.webhookSecret,
      eventId: request.headers.get("x-custody-event-id"),
      eventType: request.headers.get("x-custody-event-type"),
      timestamp: request.headers.get("x-custody-timestamp"),
      signature: request.headers.get("x-custody-signature"),
      body: raw
    });
    if (!verified) return json(401, { error: "INVALID_SIGNATURE" });
    let event;
    try {
      event = JSON.parse(raw);
    } catch {
      return json(400, { error: "INVALID_JSON" });
    }
    if (event.type === "WEBHOOK.VERIFICATION") {
      return json(200, { challenge: event.data?.challenge });
    }
    await store.receiveWebhook(event, raw);
    return json(200, { received: true, eventId: event.id });
  }


  return async request => {
    const url = new URL(request.url);
    try {
      if (!["GET", "HEAD", "OPTIONS"].includes(request.method)
          && url.pathname !== "/webhooks/custody") {
        const origin = request.headers.get("origin");
        if (origin && origin !== new URL(publicBaseUrl).origin) {
          return json(403, { error: "INVALID_ORIGIN" });
        }
      }
      if (request.method === "GET" && url.pathname === "/health") {
        return json(200, { status: "UP" });
      }
      if (request.method === "POST" && url.pathname === "/webhooks/custody") {
        return await webhook(request);
      }
      if (url.pathname.startsWith("/api/")) return await api(request, url);
      return json(404, { error: "NOT_FOUND" });
    } catch (cause) {
      const id = randomUUID();
      console.error(JSON.stringify({ id, method: request.method, path: url.pathname,
        error: cause.name, status: cause.status ?? 400 }));
      return json(cause.status ?? 400, { error: cause.name === "WalletApiError"
        ? "WALLET_API_ERROR" : "DEMO_ERROR", message: cause.message, requestId: id });
    }
  };
}
