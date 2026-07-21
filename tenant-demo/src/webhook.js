import { timingSafeEqual } from "node:crypto";
import { hmacBase64Url } from "./wallet-client.js";

export function verifyWebhook({ secret, timestamp, signature, body, nowSeconds, allowedSkew = 300 }) {
  const webhookSecret = String(secret ?? "").trim();
  const timestampText = String(timestamp ?? "").trim();
  const signatureText = String(signature ?? "").trim();
  if (!webhookSecret || !/^\d+$/.test(timestampText) || !signatureText.startsWith("v1=")) {
    return false;
  }
  const current = nowSeconds ?? Math.floor(Date.now() / 1000);
  if (Math.abs(current - Number(timestampText)) > allowedSkew) return false;
  const expected = `v1=${hmacBase64Url(webhookSecret, `${timestampText}.${body}`)}`;
  const expectedBytes = Buffer.from(expected);
  const actualBytes = Buffer.from(signatureText);
  return expectedBytes.length === actualBytes.length && timingSafeEqual(expectedBytes, actualBytes);
}
