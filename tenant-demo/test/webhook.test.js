import assert from "node:assert/strict";
import { test } from "node:test";
import { hmacBase64Url } from "../src/wallet-client.js";
import { verifyWebhook } from "../src/webhook.js";

test("verifies custody webhook signatures and rejects stale requests", () => {
  const body = '{"id":"event-1","type":"DEPOSIT.CONFIRMED","data":{}}';
  const timestamp = "1700000000";
  const secret = "whsec_demo";
  const signature = `v1=${hmacBase64Url(secret, `${timestamp}.${body}`)}`;
  assert.equal(verifyWebhook({ secret, timestamp, signature, body, nowSeconds: 1_700_000_100 }), true);
  assert.equal(verifyWebhook({ secret, timestamp, signature, body, nowSeconds: 1_700_001_000 }), false);
  assert.equal(verifyWebhook({ secret, timestamp, signature: `${signature}x`, body, nowSeconds: 1_700_000_100 }), false);
});
