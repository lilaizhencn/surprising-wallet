import assert from "node:assert/strict";
import { createHandler } from "../src/server.js";
import { hmacBase64Url } from "../src/wallet-client.js";

export function registerHttpTests(test, testStore, storage) {
  const env = { TENANT_DEMO_PUBLIC_BASE_URL: "https://demo.example",
    TENANT_DEMO_SETUP_TOKEN: "test-setup-token", WALLET_BASE_URL: "https://wallet.example",
    WALLET_KEY_ID: "test-key", WALLET_API_SECRET: "test-api-secret", WEBHOOK_SECRET: "test-webhook-secret" };
  const request = (path, { method = "GET", body, cookie, headers = {} } = {}) =>
    new Request(`https://demo.example${path}`, { method,
      headers: { ...(cookie ? { cookie } : {}), ...headers },
      body: body === undefined ? undefined : JSON.stringify(body) });

  test("HTTP login persists sessions, isolates users, and rejects foreign origins", async () => {
    const store = await testStore();
    const handle = createHandler(store, storage, env);
    const registration = await handle(request("/api/auth/register", { method: "POST",
      body: { email: "http@example.test", password: "a-long-test-password" } }));
    assert.equal(registration.status, 201);
    const cookie = registration.headers.get("set-cookie");
    assert.ok(cookie.includes("Secure") && cookie.includes("HttpOnly"));
    const reopened = createHandler(store, storage, env);
    assert.equal((await (await reopened(request("/api/session", { cookie }))).json()).authenticated, true);
    assert.equal((await handle(request("/api/me"))).status, 401);
    assert.equal((await handle(request("/api/auth/logout", { method: "POST", cookie,
      headers: { origin: "https://evil.example" } }))).status, 403);
    assert.equal((await handle(request("/api/admin/snapshot"))).status, 403);
    const status = await (await handle(request("/api/status"))).text();
    assert.ok(!status.includes(env.WALLET_API_SECRET) && !status.includes(env.WEBHOOK_SECRET));
    await handle(request("/api/auth/logout", { method: "POST", cookie }));
    assert.equal((await (await handle(request("/api/session", { cookie }))).json()).authenticated, false);
    const login = await handle(request("/api/auth/login", { method: "POST",
      body: { email: "http@example.test", password: "a-long-test-password" } }));
    assert.equal(login.status, 200);
  });

  test("HTTP login limits survive handler recreation", async () => {
    const store = await testStore();
    for (let i = 0; i < 8; i++) {
      const result = await createHandler(store, storage, env)(request("/api/auth/login", {
        method: "POST", body: { email: "missing@example.test", password: "invalid-password" },
        headers: { "cf-connecting-ip": "192.0.2.5" }
      }));
      assert.equal(result.status, 401);
    }
    assert.equal((await createHandler(store, storage, env)(request("/api/auth/login", {
      method: "POST", body: {}, headers: { "cf-connecting-ip": "192.0.2.5" }
    }))).status, 429);
  });

  test("HTTP signed callbacks are idempotent; uncertain withdrawals retain frozen funds", async () => {
    const store = await testStore();
    const user = await store.createUser({ externalId: "http-ledger", displayName: "HTTP ledger" });
    await store.saveAddress(user.id, { id: "http-address", chain: "ETH", network: "testnet",
      address: "0xsource", addressVersion: 0, status: "ACTIVE" });
    let calls = 0;
    const handle = createHandler(store, storage, env, async () => {
      calls++;
      return Response.json({ message: "temporary wallet outage" }, { status: 503 });
    });
    const event = { id: "http-deposit", type: "DEPOSIT.CONFIRMED", data: {
      subject: user.externalId, chain: "ETH", asset: "ETH", amount: "1",
      address: "0xsource", txHash: "http-tx", logIndex: 0
    } };
    const timestamp = String(Math.floor(Date.now() / 1000));
    const raw = JSON.stringify(event);
    const headers = { "x-custody-event-id": event.id, "x-custody-event-type": event.type,
      "x-custody-timestamp": timestamp,
      "x-custody-signature": `v1=${hmacBase64Url(env.WEBHOOK_SECRET, `${timestamp}.${event.id}.${event.type}.${raw}`)}` };
    assert.equal((await handle(request("/webhooks/custody", { method: "POST", body: event }))).status, 401);
    for (let i = 0; i < 2; i++) assert.equal((await handle(request("/webhooks/custody", {
      method: "POST", body: event, headers
    }))).status, 200);
    assert.equal((await store.balances(user.id))[0].available, "1");
    const cookie = `tenant_demo_session=${await store.createSession(user.id)}`;
    for (let i = 0; i < 2; i++) {
      const response = await handle(request("/api/me/withdrawals", { method: "POST", cookie,
        headers: { "idempotency-key": "http-withdrawal" }, body: { chain: "ETH", assetSymbol: "ETH",
          toAddress: "0xdestination", amount: "0.1", businessOrderNo: "http-order" } }));
      assert.equal(response.status, 202);
      assert.equal((await response.json()).status, "PENDING_REVIEW");
    }
    assert.equal(calls, 1);
    const balance = (await store.balances(user.id))[0];
    assert.equal(balance.available, "0.9");
    assert.equal(balance.locked, "0.1");
  });

  test("failed SQL transaction rolls back all writes", async () => {
    const store = await testStore();
    await assert.rejects(store.db.transaction(async db => {
      await db.run("INSERT INTO webhook_events(event_id,event_type,signature_valid,payload,received_at) VALUES (?,?,?,?,?)",
        ["rollback-event", "TEST", 1, "{}", new Date().toISOString()]);
      throw new Error("injected failure");
    }), /injected failure/);
    assert.equal((await store.webhookEvents()).length, 0);
  });
}
