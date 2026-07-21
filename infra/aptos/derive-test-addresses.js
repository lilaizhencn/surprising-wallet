#!/usr/bin/env node

const crypto = require("node:crypto");

const masterSeed = Buffer.from(
  process.env.APTOS_TEST_MASTER_SEED
    ?? "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
  "hex",
);

if (masterSeed.length < 16) {
  throw new Error("APTOS_TEST_MASTER_SEED must contain at least 128 bits of hex data");
}

function deriveAddress(path) {
  let digest = crypto.createHmac("sha512", Buffer.from("ed25519 seed"))
    .update(masterSeed)
    .digest();
  let key = digest.subarray(0, 32);
  let chainCode = digest.subarray(32);

  for (const index of path) {
    const data = Buffer.alloc(37);
    key.copy(data, 1);
    data.writeUInt32BE((index | 0x80000000) >>> 0, 33);
    digest = crypto.createHmac("sha512", chainCode).update(data).digest();
    key = digest.subarray(0, 32);
    chainCode = digest.subarray(32);
  }

  const privateKey = crypto.createPrivateKey({
    key: Buffer.concat([
      Buffer.from("302e020100300506032b657004220420", "hex"),
      key,
    ]),
    format: "der",
    type: "pkcs8",
  });
  const publicKey = crypto.createPublicKey(privateKey)
    .export({ format: "der", type: "spki" })
    .subarray(-32);
  return `0x${crypto.createHash("sha3-256")
    .update(Buffer.concat([publicKey, Buffer.from([0])]))
    .digest("hex")}`;
}

const path = (userId, addressIndex) => [44, 637, 0, userId, addressIndex];

process.stdout.write(`${JSON.stringify({
  external: deriveAddress(path(6112, 1310012)),
  owner: deriveAddress(path(6111, 1310011)),
  hot: deriveAddress(path(6110, 1310010)),
})}\n`);
