import assert from "node:assert/strict";
import { test } from "node:test";
import { DemoStore } from "../src/store.js";

function envelope(id, type, data) {
  return { id, type, createdAt: new Date().toISOString(), data };
}

async function testStore() {
  const store = await DemoStore.open();
  await store.resetForTest();
  return store;
}

test("credits deposits idempotently and finalizes a confirmed withdrawal", async () => {
  const store = await testStore();
  try {
    const user = await store.createUser({ externalId: "user-1001", displayName: "Demo User" });
    const address = await store.saveAddress(user.id, {
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
    await store.receiveWebhook(deposit, JSON.stringify(deposit));
    assert.equal((await store.balances())[0].available, "1.25");
    assert.equal((await store.receiveWebhook(deposit, JSON.stringify(deposit))).duplicate, true);
    assert.equal((await store.balances())[0].available, "1.25");

    const reserved = await store.reserveWithdrawal({
      userId: user.id,
      custodyAddressId: address.id,
      chain: "BTC",
      asset: "BTC",
      toAddress: "bcrt1qexternal",
      amount: "0.4"
    });
    let balance = (await store.balances())[0];
    assert.deepEqual(
      { available: balance.available, locked: balance.locked },
      { available: "0.85", locked: "0.4" }
    );
    await store.acceptWithdrawal(reserved.id, {
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
    await store.receiveWebhook(confirmed, JSON.stringify(confirmed));
    balance = (await store.balances())[0];
    assert.deepEqual(
      { available: balance.available, locked: balance.locked },
      { available: "0.85", locked: "0" }
    );
    assert.equal((await store.withdrawals())[0].status, "CONFIRMED");
    assert.equal((await store.ledger()).length, 2);
  } finally {
    await store.close();
  }
});

test("releases reserved user funds when the wallet API request fails", async () => {
  const store = await testStore();
  try {
    const user = await store.createUser({ externalId: "user-1002", displayName: "Failure User" });
    const address = await store.saveAddress(user.id, {
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
    await store.receiveWebhook(deposit, JSON.stringify(deposit));
    const reserved = await store.reserveWithdrawal({
      userId: user.id,
      custodyAddressId: address.id,
      chain: "APTOS",
      asset: "USDC",
      toAddress: "0xabcd",
      amount: "2"
    });
    await store.releaseWithdrawal(reserved.id, "wallet rejected request");
    const balance = (await store.balances())[0];
    assert.deepEqual(
      { available: balance.available, locked: balance.locked },
      { available: "5", locked: "0" }
    );
    assert.equal((await store.withdrawals())[0].status, "REQUEST_FAILED");
  } finally {
    await store.close();
  }
});

test("serializes concurrent deposits and applies each event exactly once", async () => {
  const store = await testStore();
  try {
    const user = await store.createUser({ externalId: "user-concurrent", displayName: "Concurrent User" });
    const events = Array.from({ length: 40 }, (_, index) => envelope(
      `event-concurrent-${index}`,
      "DEPOSIT.CONFIRMED",
      {
        subject: user.externalId,
        chain: "APTOS",
        asset: "USDT",
        amount: "0.25",
        txHash: `tx-concurrent-${index}`,
        logIndex: 0
      }
    ));
    await Promise.all(events.flatMap(event => [
      store.receiveWebhook(event, JSON.stringify(event)),
      store.receiveWebhook(event, JSON.stringify(event))
    ]));
    const balance = (await store.balances())[0];
    assert.equal(balance.available, "10");
    assert.equal(balance.locked, "0");
    assert.equal((await store.ledger()).length, 40);
    assert.equal((await store.webhookEvents()).filter(event => event.processed).length, 40);
  } finally {
    await store.close();
  }
});
