import assert from "node:assert/strict";
import { test } from "node:test";
import { DemoStore } from "../src/store.js";

function envelope(id, type, data) {
  return { id, type, createdAt: new Date().toISOString(), data };
}

test("credits deposits idempotently and finalizes a confirmed withdrawal", () => {
  const store = new DemoStore(":memory:");
  try {
    const user = store.createUser({ externalId: "user-1001", displayName: "Demo User" });
    const address = store.saveAddress(user.id, {
      id: "2c9d9422-cfcf-4cad-af11-12b06af6eb18",
      chain: "BTC",
      network: "regtest",
      address: "bcrt1qdestination",
      addressVersion: 0,
      status: "ACTIVE"
    });
    const deposit = envelope("event-deposit-1", "DEPOSIT.CONFIRMED", {
      subject: user.externalId,
      chain: "BTC",
      asset: "BTC",
      address: address.address,
      amount: "1.25000000",
      txHash: "tx-deposit",
      logIndex: 0
    });
    store.receiveWebhook(deposit, JSON.stringify(deposit));
    assert.equal(store.balances()[0].available, "1.25");
    assert.equal(store.receiveWebhook(deposit, JSON.stringify(deposit)).duplicate, true);
    assert.equal(store.balances()[0].available, "1.25");

    const reserved = store.reserveWithdrawal({
      userId: user.id,
      custodyAddressId: address.id,
      chain: "BTC",
      asset: "BTC",
      toAddress: "bcrt1qexternal",
      amount: "0.4"
    });
    assert.deepEqual(
      { available: store.balances()[0].available, locked: store.balances()[0].locked },
      { available: "0.85", locked: "0.4" }
    );
    store.acceptWithdrawal(reserved.id, {
      id: "6b26cc92-e53d-40cd-9aa9-33b3eedb1396",
      fee: "0.00001",
      status: "CREATED"
    });
    const confirmed = envelope("event-withdraw-1", "WITHDRAWAL.CONFIRMED", {
      withdrawalId: "6b26cc92-e53d-40cd-9aa9-33b3eedb1396",
      externalReference: reserved.externalReference,
      status: "CONFIRMED",
      fee: "0.00001",
      txHash: "tx-withdraw"
    });
    store.receiveWebhook(confirmed, JSON.stringify(confirmed));
    assert.deepEqual(
      { available: store.balances()[0].available, locked: store.balances()[0].locked },
      { available: "0.85", locked: "0" }
    );
    assert.equal(store.withdrawals()[0].status, "CONFIRMED");
    assert.equal(store.ledger().length, 2);
  } finally {
    store.close();
  }
});

test("releases reserved user funds when the wallet API request fails", () => {
  const store = new DemoStore(":memory:");
  try {
    const user = store.createUser({ externalId: "user-1002", displayName: "Failure User" });
    const address = store.saveAddress(user.id, {
      id: "ba4b0832-f88d-4ab0-9818-0598c7babd31",
      chain: "APTOS",
      network: "testnet",
      address: "0x1234",
      addressVersion: 0,
      status: "ACTIVE"
    });
    const deposit = envelope("event-deposit-2", "DEPOSIT.CONFIRMED", {
      subject: user.externalId,
      chain: "APTOS",
      asset: "USDC",
      amount: "5",
      txHash: "tx-deposit-2",
      logIndex: 0
    });
    store.receiveWebhook(deposit, JSON.stringify(deposit));
    const reserved = store.reserveWithdrawal({
      userId: user.id,
      custodyAddressId: address.id,
      chain: "APTOS",
      asset: "USDC",
      toAddress: "0xabcd",
      amount: "2"
    });
    store.releaseWithdrawal(reserved.id, "wallet rejected request");
    assert.deepEqual(
      { available: store.balances()[0].available, locked: store.balances()[0].locked },
      { available: "5", locked: "0" }
    );
    assert.equal(store.withdrawals()[0].status, "REQUEST_FAILED");
  } finally {
    store.close();
  }
});
