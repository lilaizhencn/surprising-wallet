import { timingSafeEqual } from "node:crypto";
import { hmacBase64Url } from "./wallet-client.js";

export function verifyWebhook({ secret, eventId, eventType, timestamp, signature, body,
  nowSeconds, allowedSkew = 300 }) {
  const webhookSecret = String(secret ?? "").trim();
  const eventIdText = String(eventId ?? "").trim();
  const eventTypeText = String(eventType ?? "").trim();
  const timestampText = String(timestamp ?? "").trim();
  const signatureText = String(signature ?? "").trim();
  if (!webhookSecret || !eventIdText || !eventTypeText || !/^\d+$/.test(timestampText)
    || !signatureText.startsWith("v1=")) {
    return false;
  }
  const current = nowSeconds ?? Math.floor(Date.now() / 1000);
  if (Math.abs(current - Number(timestampText)) > allowedSkew) return false;
  let payload;
  try {
    payload = JSON.parse(body);
  } catch {
    return false;
  }
  if (!payload || typeof payload !== "object"
    || String(payload.id ?? "") !== eventIdText
    || String(payload.type ?? "") !== eventTypeText) {
    return false;
  }
  const expected = `v1=${hmacBase64Url(webhookSecret,
    `${timestampText}.${eventIdText}.${eventTypeText}.${body}`)}`;
  const expectedBytes = Buffer.from(expected);
  const actualBytes = Buffer.from(signatureText);
  return expectedBytes.length === actualBytes.length && timingSafeEqual(expectedBytes, actualBytes);
}
