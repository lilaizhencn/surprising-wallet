#!/usr/bin/env node

import { createHmac, createPrivateKey, createPublicKey } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";

const [homeDir, masterSeedHex] = process.argv.slice(2);
if (!homeDir || !/^[0-9a-f]{64}$/i.test(masterSeedHex ?? "")) {
  throw new Error("usage: near-sandbox-genesis.mjs <home> <32-byte-ed25519-seed-hex>");
}

const actors = [
  [0, 0, 0],
  [9, 900001, 0],
  [1, 100001, 0],
  [1, 100002, 0],
  [9, 900002, 0],
  [9, 900003, 0],
];
const balance = 10_000_000_000_000_000_000_000_000_000n;

function deriveSeed(path) {
  let digest = createHmac("sha512", Buffer.from("ed25519 seed", "ascii"))
    .update(Buffer.from(masterSeedHex, "hex"))
    .digest();
  let key = digest.subarray(0, 32);
  let chainCode = digest.subarray(32);
  for (const index of path) {
    const data = Buffer.alloc(37);
    data[0] = 0;
    key.copy(data, 1);
    data.writeUInt32BE((index | 0x80000000) >>> 0, 33);
    digest = createHmac("sha512", chainCode).update(data).digest();
    key = digest.subarray(0, 32);
    chainCode = digest.subarray(32);
  }
  return key;
}

function publicKey(seed) {
  const pkcs8 = Buffer.concat([
    Buffer.from("302e020100300506032b657004220420", "hex"),
    seed,
  ]);
  const privateKey = createPrivateKey({ key: pkcs8, format: "der", type: "pkcs8" });
  const spki = createPublicKey(privateKey).export({ format: "der", type: "spki" });
  return spki.subarray(spki.length - 32);
}

const genesisPath = join(homeDir, "genesis.json");
const genesis = JSON.parse(readFileSync(genesisPath, "utf8"));
for (const [biz, userId, addressIndex] of actors) {
  const pub = publicKey(deriveSeed([44, 397, biz, userId, addressIndex]));
  const accountId = pub.toString("hex");
  const keyBase58 = base58(pub);
  genesis.records.push({
    Account: {
      account_id: accountId,
      account: {
        amount: balance.toString(),
        locked: "0",
        code_hash: "11111111111111111111111111111111",
        storage_usage: 182,
      },
    },
  });
  genesis.records.push({
    AccessKey: {
      account_id: accountId,
      public_key: `ed25519:${keyBase58}`,
      access_key: { nonce: 0, permission: "FullAccess" },
    },
  });
  genesis.total_supply = (BigInt(genesis.total_supply) + balance).toString();
  process.stdout.write(`${biz}/${userId}/${addressIndex} ${accountId}\n`);
}
writeFileSync(genesisPath, JSON.stringify(genesis));

function base58(bytes) {
  const alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
  let value = BigInt(`0x${bytes.toString("hex")}`);
  let encoded = "";
  while (value > 0n) {
    encoded = alphabet[Number(value % 58n)] + encoded;
    value /= 58n;
  }
  for (const byte of bytes) {
    if (byte !== 0) break;
    encoded = `1${encoded}`;
  }
  return encoded || "1";
}
