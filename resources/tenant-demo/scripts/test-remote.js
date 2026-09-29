import assert from "node:assert/strict";
import { registerStoreTests } from "../test/store-cases.js";
import { registerHttpTests } from "../test/http-cases.js";

const base = new URL("/api/test", process.env.TENANT_DEMO_TEST_URL).href;
const token = process.env.TENANT_DEMO_TEST_TOKEN;
assert.ok(base && token, "TENANT_DEMO_TEST_URL and TENANT_DEMO_TEST_TOKEN are required");
const headers = { authorization: `Bearer ${token}`, "content-type": "application/json" };
const listed = await fetch(base, { headers });
assert.equal(listed.status, 200, "remote test worker must be reachable");
const tests = await listed.json();
const expected = [];
registerStoreTests(name => expected.push(name));
registerHttpTests(name => expected.push(name));
assert.deepEqual(tests, expected, "the deployed test suite must match this checkout");
let failures = 0;
for (const [index, name] of tests.entries()) {
  const response = await fetch(base, { method: "POST", headers, body: JSON.stringify({ index }) });
  const result = await response.json();
  console.log(`${result.ok ? "PASS" : "FAIL"} ${name}${result.error ? `: ${result.error}` : ""}`);
  if (!response.ok || !result.ok) failures++;
}
assert.equal(failures, 0, `${failures} remote ledger tests failed`);
