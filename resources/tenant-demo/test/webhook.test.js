import assert from "node:assert/strict";
import { test } from "node:test";
import { hmacBase64Url } from "../src/wallet-client.js";
import { verifyWebhook } from "../src/webhook.js";

test("verifies custody webhook signatures and rejects stale requests", () => {
  const body = '{"id":"event-1","type":"DEPOSIT.CONFIRMED","data":{}}';
  const timestamp = "1700000000";
  const secret = "whsec_demo";
  const eventId = "event-1";
  const eventType = "DEPOSIT.CONFIRMED";
  const signature = `v1=${hmacBase64Url(secret,
    `${timestamp}.${eventId}.${eventType}.${body}`)}`;
  const input = { secret, eventId, eventType, timestamp, signature, body };
  assert.equal(verifyWebhook({ ...input, nowSeconds: 1_700_000_100 }), true);
  assert.equal(verifyWebhook({ ...input, nowSeconds: 1_700_001_000 }), false);
  assert.equal(verifyWebhook({ ...input, signature: `${signature}x`, nowSeconds: 1_700_000_100 }), false);
  assert.equal(verifyWebhook({ ...input, eventId: "event-2", nowSeconds: 1_700_000_100 }), false);
});
