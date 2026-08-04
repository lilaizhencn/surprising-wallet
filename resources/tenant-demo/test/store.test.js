import assert from "node:assert/strict";
import { test } from "node:test";
import { DemoStore } from "../src/store.js";

function envelope(id, type, data) {
  return { id, type, createdAt: new Date().toISOString(), data };
}

async function testStore() {
  const store = await DemoStore.open({ filename: ":memory:" });
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
      orderNo: "CW-demo-order-1",
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
      { available: "0.84999", locked: "0" }
    );
    const withdrawalView = (await store.withdrawals())[0];
    assert.equal(withdrawalView.status, "CONFIRMED");
    assert.equal(withdrawalView.network, "regtest");
    assert.equal(withdrawalView.orderNo, "CW-demo-order-1");
    const ledger = await store.ledger();
    assert.equal(ledger.length, 2);
    assert.equal(ledger.find(row => row.entryType === "DEPOSIT").txHash, "tx-deposit");
    assert.equal(ledger.find(row => row.entryType === "DEPOSIT").depositAddress, address.address);
    assert.equal(ledger.find(row => row.entryType === "WITHDRAWAL").txHash, "tx-withdraw");
    assert.equal(ledger.find(row => row.entryType === "WITHDRAWAL").amount, "0.40001");
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
    assert.equal((await store.ledger())[0].entryType, "WITHDRAWAL_RELEASE");
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

test("finalizes multiple EIP-7702-style withdrawals sharing one transaction hash", async () => {
  const store = await testStore();
  try {
    const user = await store.createUser({ externalId: "user-eip7702", displayName: "Batch User" });
    const address = await store.saveAddress(user.id, {
      id: "eip7702-address",
      chain: "ETH",
      network: "devtest",
      address: "0x1234567890abcdef1234567890abcdef12345678",
      addressVersion: 0,
      status: "ACTIVE"
    });
    const deposit = envelope("event-eip7702-deposit", "DEPOSIT.CONFIRMED", {
      subject: user.externalId, chain: "ETH", asset: "ETH", address: address.address,
      amount: "5", txHash: "tx-batch-deposit", logIndex: 0
    });
    await store.receiveWebhook(deposit, JSON.stringify(deposit));
    const first = await store.reserveWithdrawal({
      userId: user.id, chain: "ETH", asset: "ETH",
      toAddress: "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", amount: "1"
    });
    const second = await store.reserveWithdrawal({
      userId: user.id, chain: "ETH", asset: "ETH",
      toAddress: "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", amount: "2"
    });
    await store.acceptWithdrawal(first.id, { id: "batch-withdrawal-1", status: "CREATED" });
    await store.acceptWithdrawal(second.id, { id: "batch-withdrawal-2", status: "CREATED" });
    for (const [index, withdrawal] of [first, second].entries()) {
      const event = envelope(`event-eip7702-withdraw-${index}`, "WITHDRAWAL.CONFIRMED", {
        withdrawalId: `batch-withdrawal-${index + 1}`,
        externalReference: withdrawal.externalReference,
        chain: "ETH", asset: "ETH", amount: withdrawal.amount,
        status: "CONFIRMED", txHash: "0xshared-eip7702-tx"
      });
      await store.receiveWebhook(event, JSON.stringify(event));
    }
    const balance = (await store.balances())[0];
    assert.deepEqual({ available: balance.available, locked: balance.locked }, { available: "2", locked: "0" });
    assert.equal((await store.withdrawals()).filter(row => row.status === "CONFIRMED").length, 2);
    assert.equal((await store.ledger()).length, 3);
  } finally {
    await store.close();
  }
});

test("pages address history and exposes other users' addresses for withdrawal testing", async () => {
  const store = await testStore();
  try {
    const first = await store.createUser({ externalId: "history-user-1", displayName: "History One" });
    const second = await store.createUser({ externalId: "history-user-2", displayName: "History Two" });
    for (const [userId, version] of [[first.id, 0], [first.id, 1], [first.id, 2], [second.id, 0]]) {
      await store.saveAddress(userId, {
        id: `history-address-${userId}-${version}`, chain: "ETH", network: "devtest",
        address: `0x${String(version).padStart(40, "0")}`, addressVersion: version, status: "ACTIVE"
      });
    }
    const page = await store.addressHistory(first.id, "ETH", 2, 2);
    assert.deepEqual({ total: page.total, page: page.page, pages: page.pages },
      { total: 3, page: 2, pages: 2 });
    assert.equal(page.items.length, 1);
    assert.equal(page.items[0].addressVersion, 0);
    assert.equal((await store.latestAddress(first.id, "ETH")).addressVersion, 2);
    const others = await store.platformAddresses(first.id, "ETH");
    assert.equal(others.length, 1);
    assert.equal(others[0].userId, second.id);
  } finally {
    await store.close();
  }
});

test("keeps terminal withdrawal state stable and rejects mismatched callback data", async () => {
  const store = await testStore();
  try {
    const user = await store.registerUser({
      email: "callback-order@example.test", password: "callback-password", displayName: "Callback Order"
    });
    const address = await store.saveAddress(user.id, {
      id: "callback-order-address", chain: "ETH", network: "devtest",
      address: "0x1234567890123456789012345678901234567890", addressVersion: 0, status: "ACTIVE"
    });
    const deposit = envelope("event-callback-order-deposit", "DEPOSIT.CONFIRMED", {
      subject: user.externalId, chain: "ETH", asset: "ETH", address: address.address,
      amount: "3", txHash: "callback-order-deposit", logIndex: 0
    });
    await store.receiveWebhook(deposit, JSON.stringify(deposit));
    const withdrawal = await store.reserveWithdrawal({
      userId: user.id, custodyAddressId: address.id, chain: "ETH", asset: "ETH",
      toAddress: "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", amount: "1"
    });
    await store.acceptWithdrawal(withdrawal.id, { id: "callback-order-withdrawal", status: "CREATED" });
    const mismatched = envelope("event-callback-order-mismatch", "WITHDRAWAL.CONFIRMED", {
      withdrawalId: "callback-order-withdrawal", externalReference: withdrawal.externalReference,
      chain: "ETH", asset: "USDC", amount: "1", status: "CONFIRMED"
    });
    await assert.rejects(
      () => store.receiveWebhook(mismatched, JSON.stringify(mismatched)), /asset/
    );
    const confirmed = envelope("event-callback-order-confirmed", "WITHDRAWAL.CONFIRMED", {
      withdrawalId: "callback-order-withdrawal", externalReference: withdrawal.externalReference,
      chain: "ETH", asset: "ETH", amount: "1", status: "CONFIRMED", txHash: "shared-tx"
    });
    await store.receiveWebhook(confirmed, JSON.stringify(confirmed));
    const lateFailure = envelope("event-callback-order-failed", "WITHDRAWAL.FAILED", {
      withdrawalId: "callback-order-withdrawal", externalReference: withdrawal.externalReference,
      chain: "ETH", asset: "ETH", amount: "1", status: "FAILED", errorMessage: "late failure"
    });
    await store.receiveWebhook(lateFailure, JSON.stringify(lateFailure));
    assert.equal((await store.withdrawals(user.id))[0].status, "CONFIRMED");
    const balance = (await store.balances(user.id))[0];
    assert.deepEqual({ available: balance.available, locked: balance.locked }, { available: "2", locked: "0" });
  } finally {
    await store.close();
  }
});

test("registers users, hashes passwords, and invalidates sessions on logout", async () => {
  const store = await testStore();
  try {
    const user = await store.registerUser({
      email: "Login-User@example.test", password: "correct-password", displayName: "Login User"
    });
    assert.equal(user.email, "login-user@example.test");
    assert.equal((await store.users())[0].passwordHash, undefined);
    await assert.rejects(
      () => store.authenticateUser({ email: user.email, password: "wrong-password" }),
      /email or password is incorrect/
    );
    const authenticated = await store.authenticateUser({ email: user.email, password: "correct-password" });
    assert.equal(authenticated.id, user.id);
    const token = await store.createSession(user.id);
    assert.equal((await store.sessionUser(token)).id, user.id);
    await store.deleteSession(token);
    assert.equal(await store.sessionUser(token), null);
    await assert.rejects(
      () => store.registerUser({ email: user.email, password: "another-password", displayName: "Duplicate" }),
      /UNIQUE/
    );
  } finally {
    await store.close();
  }
});
