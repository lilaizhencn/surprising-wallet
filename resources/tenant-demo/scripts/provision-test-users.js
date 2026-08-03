import assert from "node:assert/strict";

const demoBaseUrl = String(process.env.DEMO_BASE_URL ?? "http://127.0.0.1:3001").replace(/\/+$/, "");
const count = Number(process.env.TEST_USER_COUNT ?? 40);
const commonPassword = String(process.env.TEST_USER_PASSWORD ?? "");
const fixedEmail = String(process.env.TEST_FIXED_EMAIL ?? "602884291@qq.com").toLowerCase();
const fixedPassword = String(process.env.TEST_FIXED_PASSWORD ?? "");
const chainOverride = String(process.env.TEST_CHAIN ?? "").trim().toUpperCase();
const runId = Date.now().toString(36);

if (!Number.isInteger(count) || count < 1 || count > 500) throw new Error("TEST_USER_COUNT must be 1..500");
if (commonPassword.length < 8 || fixedPassword.length < 8) {
  throw new Error("TEST_USER_PASSWORD and TEST_FIXED_PASSWORD must be at least 8 characters");
}

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

async function loginOrRegister(email, password, displayName) {
  try {
    const registered = await request("/api/auth/register", {
      method: "POST", body: JSON.stringify({ email, password, displayName })
    });
    return { user: registered.payload.user, cookie: registered.cookie };
  } catch (error) {
    if (!error.message.includes("HTTP 409")) throw error;
    const loggedIn = await request("/api/auth/login", {
      method: "POST", body: JSON.stringify({ email, password })
    });
    return { user: loggedIn.payload.user, cookie: loggedIn.cookie };
  }
}

const accounts = [{ email: fixedEmail, password: fixedPassword, displayName: "浏览验收账号" }];
for (let index = 0; index < count - 1; index += 1) {
  accounts.push({
    email: `wallet-test-${runId}-${String(index + 1).padStart(2, "0")}@example.test`,
    password: commonPassword,
    displayName: `压力测试用户 ${index + 1}`
  });
}

const probe = await loginOrRegister(accounts[0].email, accounts[0].password, accounts[0].displayName);
const chainRows = (await request("/api/chains", { headers: { Cookie: probe.cookie } })).payload;
const chain = chainOverride || chainRows[0]?.chain;
assert.ok(chain, "wallet API must expose at least one enabled chain");
assert.ok(chainRows.some(row => row.chain === chain), `${chain} is not enabled for the tenant`);

const result = [];
for (const [index, account] of accounts.entries()) {
  const logged = index === 0 ? probe : await loginOrRegister(account.email, account.password, account.displayName);
  const address = (await request("/api/me/addresses", {
    method: "POST", headers: { Cookie: logged.cookie },
    body: JSON.stringify({ chain, addressVersion: 0 })
  })).payload;
  result.push({ email: account.email, userId: logged.user.id, chain, address: address.address });
}

console.log(JSON.stringify({ ok: true, count: result.length, chain, accounts: result }, null, 2));
