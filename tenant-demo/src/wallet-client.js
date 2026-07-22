import { createHash, createHmac, randomBytes } from "node:crypto";

export function sha256Hex(body) {
  return createHash("sha256").update(body).digest("hex");
}

export function canonicalRequest(timestamp, nonce, method, requestTarget, body) {
  return `${timestamp}\n${nonce}\n${method.toUpperCase()}\n${requestTarget}\n${sha256Hex(body)}`;
}

export function hmacBase64Url(secret, value) {
  return createHmac("sha256", secret).update(value).digest("base64url");
}

export function signedHeaders({ keyId, secret, method, requestTarget, body, timestamp, nonce }) {
  const requestTimestamp = timestamp ?? Math.floor(Date.now() / 1000);
  const requestNonce = nonce ?? randomBytes(24).toString("base64url");
  const canonical = canonicalRequest(requestTimestamp, requestNonce, method, requestTarget, body);
  return {
    "X-Custody-Key": keyId,
    "X-Custody-Timestamp": String(requestTimestamp),
    "X-Custody-Nonce": requestNonce,
    "X-Custody-Signature": hmacBase64Url(secret, canonical)
  };
}

export class WalletApiError extends Error {
  constructor(status, payload) {
    const message = payload?.error?.message
      ?? payload?.message
      ?? (typeof payload?.error === "string" ? payload.error : null)
      ?? `wallet API returned HTTP ${status}`;
    super(message);
    this.name = "WalletApiError";
    this.status = status;
    this.payload = payload;
  }
}

export class WalletClient {
  constructor({ baseUrl, keyId, secret, fetchImpl = fetch }) {
    this.baseUrl = String(baseUrl ?? "").replace(/\/+$/, "");
    this.keyId = String(keyId ?? "").trim();
    this.secret = String(secret ?? "").trim();
    this.fetchImpl = fetchImpl;
    if (!this.baseUrl || !this.keyId || !this.secret) {
      throw new Error("wallet base URL, API key ID and API secret are required");
    }
  }

  async request(method, path, { body, idempotencyKey } = {}) {
    const url = new URL(path, `${this.baseUrl}/`);
    const requestTarget = `${url.pathname}${url.search}`;
    const payload = body === undefined ? Buffer.alloc(0) : Buffer.from(JSON.stringify(body));
    const headers = signedHeaders({
      keyId: this.keyId,
      secret: this.secret,
      method,
      requestTarget,
      body: payload
    });
    if (body !== undefined) headers["Content-Type"] = "application/json";
    if (idempotencyKey) headers["Idempotency-Key"] = idempotencyKey;
    const response = await this.fetchImpl(url, {
      method,
      headers,
      body: body === undefined ? undefined : payload
    });
    const text = await response.text();
    let result = null;
    if (text) {
      try {
        result = JSON.parse(text);
      } catch {
        result = { message: text };
      }
    }
    if (!response.ok) throw new WalletApiError(response.status, result);
    return result;
  }

  chains() {
    return this.request("GET", "/custody/api/v1/chains");
  }

  createAddress(chainId, subject, addressVersion = 0) {
    return this.request("POST", "/custody/api/v1/addresses", {
      body: { chainId, subject, addressVersion }
    });
  }

  addresses() {
    return this.request("GET", "/custody/api/v1/addresses?limit=500&offset=0");
  }

  assets() {
    return this.request("GET", "/custody/api/v1/assets");
  }

  deposits() {
    return this.request("GET", "/custody/api/v1/deposits?limit=500&offset=0");
  }

  withdrawals() {
    return this.request("GET", "/custody/api/v1/withdrawals?limit=500&offset=0");
  }

  createWithdrawal(command, idempotencyKey) {
    return this.request("POST", "/custody/api/v1/withdrawals", {
      body: command,
      idempotencyKey
    });
  }
}
