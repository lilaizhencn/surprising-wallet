import assert from "node:assert/strict";
import { test } from "node:test";
import {
  WalletApiError,
  WalletClient,
  canonicalRequest,
  hmacBase64Url,
  sha256Hex,
  signedHeaders
} from "../src/wallet-client.js";

test("calls fetch without binding the wallet client as its receiver", async () => {
  const client = new WalletClient({ baseUrl: "https://wallet.example", keyId: "test-key",
    secret: "test-secret", fetchImpl: function (url, options) {
      assert.equal(this, undefined);
      assert.equal(options.redirect, "manual");
      assert.ok(options.signal instanceof AbortSignal);
      return Promise.resolve(Response.json([{ chain: "APTOS" }]));
    } });
  assert.deepEqual(await client.chains(), [{ chain: "APTOS" }]);
});

test("builds the exact custody API canonical request and signature", () => {
  const body = Buffer.from('{"chainId":"BTC","subject":"user-1","addressVersion":0}');
  const canonical = canonicalRequest(
    1_700_000_000,
    "abcdefghijklmnop",
    "post",
    "/custody/api/v1/addresses",
    body
  );
  assert.equal(canonical, [
    "1700000000",
    "abcdefghijklmnop",
    "POST",
    "/custody/api/v1/addresses",
    sha256Hex(body)
  ].join("\n"));
  const headers = signedHeaders({
    keyId: "swk_demo",
    secret: "sws_demo_secret",
    method: "POST",
    requestTarget: "/custody/api/v1/addresses",
    body,
    timestamp: 1_700_000_000,
    nonce: "abcdefghijklmnop"
  });
  assert.equal(headers["X-Custody-Key"], "swk_demo");
  assert.equal(headers["X-Custody-Signature"], hmacBase64Url("sws_demo_secret", canonical));
});

test("surfaces the structured custody API error message", () => {
  const error = new WalletApiError(409, {
    error: { code: "INVALID_STATE", message: "chain is not enabled" }
  });

  assert.equal(error.message, "chain is not enabled");
  assert.equal(error.status, 409);
});
