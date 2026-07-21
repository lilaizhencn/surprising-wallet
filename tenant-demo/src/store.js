import { randomUUID } from "node:crypto";
import { mkdirSync } from "node:fs";
import { dirname } from "node:path";
import { DatabaseSync } from "node:sqlite";
import { addDecimal, normalizeDecimal, requirePositiveDecimal, subtractDecimal } from "./decimal.js";

const now = () => new Date().toISOString();

export class DemoStore {
  constructor(filename) {
    if (filename !== ":memory:") mkdirSync(dirname(filename), { recursive: true });
    this.db = new DatabaseSync(filename);
    this.db.exec("PRAGMA foreign_keys = ON; PRAGMA journal_mode = WAL;");
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS settings (
        key TEXT PRIMARY KEY,
        value TEXT NOT NULL
      );
      CREATE TABLE IF NOT EXISTS users (
        id TEXT PRIMARY KEY,
        external_id TEXT NOT NULL UNIQUE,
        display_name TEXT NOT NULL,
        created_at TEXT NOT NULL
      );
      CREATE TABLE IF NOT EXISTS addresses (
        id TEXT PRIMARY KEY,
        user_id TEXT NOT NULL REFERENCES users(id),
        chain TEXT NOT NULL,
        network TEXT,
        address TEXT NOT NULL,
        memo TEXT,
        address_version INTEGER NOT NULL,
        status TEXT NOT NULL,
        created_at TEXT NOT NULL,
        UNIQUE(user_id, chain, address_version)
      );
      CREATE TABLE IF NOT EXISTS balances (
        user_id TEXT NOT NULL REFERENCES users(id),
        chain TEXT NOT NULL,
        asset TEXT NOT NULL,
        available TEXT NOT NULL DEFAULT '0',
        locked TEXT NOT NULL DEFAULT '0',
        updated_at TEXT NOT NULL,
        PRIMARY KEY(user_id, chain, asset)
      );
      CREATE TABLE IF NOT EXISTS ledger_entries (
        id TEXT PRIMARY KEY,
        event_id TEXT NOT NULL,
        user_id TEXT NOT NULL REFERENCES users(id),
        chain TEXT NOT NULL,
        asset TEXT NOT NULL,
        entry_type TEXT NOT NULL,
        direction TEXT NOT NULL,
        amount TEXT NOT NULL,
        reference_id TEXT,
        raw_json TEXT NOT NULL,
        created_at TEXT NOT NULL,
        UNIQUE(event_id, entry_type)
      );
      CREATE TABLE IF NOT EXISTS withdrawals (
        id TEXT PRIMARY KEY,
        custody_withdrawal_id TEXT UNIQUE,
        user_id TEXT NOT NULL REFERENCES users(id),
        custody_address_id TEXT NOT NULL,
        external_reference TEXT NOT NULL UNIQUE,
        idempotency_key TEXT NOT NULL UNIQUE,
        chain TEXT NOT NULL,
        asset TEXT NOT NULL,
        to_address TEXT NOT NULL,
        amount TEXT NOT NULL,
        fee TEXT NOT NULL DEFAULT '0',
        status TEXT NOT NULL,
        tx_hash TEXT,
        error_message TEXT,
        balance_finalized INTEGER NOT NULL DEFAULT 0,
        created_at TEXT NOT NULL,
        updated_at TEXT NOT NULL
      );
      CREATE TABLE IF NOT EXISTS webhook_events (
        event_id TEXT PRIMARY KEY,
        event_type TEXT NOT NULL,
        signature_valid INTEGER NOT NULL,
        processed INTEGER NOT NULL DEFAULT 0,
        payload TEXT NOT NULL,
        error_message TEXT,
        received_at TEXT NOT NULL,
        processed_at TEXT
      );
    `);
  }

  close() {
    this.db.close();
  }

  configuration() {
    const rows = this.db.prepare("SELECT key, value FROM settings").all();
    return Object.fromEntries(rows.map(row => [row.key, row.value]));
  }

  saveConfiguration(values) {
    const statement = this.db.prepare(`
      INSERT INTO settings(key, value) VALUES (?, ?)
      ON CONFLICT(key) DO UPDATE SET value = excluded.value
    `);
    this.db.exec("BEGIN IMMEDIATE");
    try {
      for (const [key, value] of Object.entries(values)) {
        if (value !== undefined && value !== null) statement.run(key, String(value).trim());
      }
      this.db.exec("COMMIT");
    } catch (error) {
      this.db.exec("ROLLBACK");
      throw error;
    }
    return this.configuration();
  }

  createUser({ externalId, displayName }) {
    const subject = String(externalId ?? "").trim();
    const name = String(displayName ?? "").trim();
    if (!/^[A-Za-z0-9_][A-Za-z0-9._:-]{0,159}$/.test(subject)) {
      throw new Error("externalId must be a valid wallet subject");
    }
    if (!name || name.length > 120) throw new Error("displayName is required");
    const user = { id: randomUUID(), externalId: subject, displayName: name, createdAt: now() };
    this.db.prepare(`
      INSERT INTO users(id, external_id, display_name, created_at) VALUES (?, ?, ?, ?)
    `).run(user.id, user.externalId, user.displayName, user.createdAt);
    return user;
  }

  user(id) {
    const row = this.db.prepare(`
      SELECT id, external_id externalId, display_name displayName, created_at createdAt
      FROM users WHERE id = ?
    `).get(id);
    if (!row) throw new Error("user not found");
    return row;
  }

  userBySubject(subject) {
    return this.db.prepare(`
      SELECT id, external_id externalId, display_name displayName, created_at createdAt
      FROM users WHERE external_id = ?
    `).get(subject);
  }

  users() {
    return this.db.prepare(`
      SELECT id, external_id externalId, display_name displayName, created_at createdAt
      FROM users ORDER BY created_at DESC
    `).all();
  }

  saveAddress(userId, address) {
    this.user(userId);
    this.db.prepare(`
      INSERT INTO addresses(
        id, user_id, chain, network, address, memo, address_version, status, created_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(user_id, chain, address_version) DO UPDATE SET
        id = excluded.id,
        network = excluded.network,
        address = excluded.address,
        memo = excluded.memo,
        status = excluded.status
    `).run(
      address.id, userId, address.chain, address.network ?? null, address.address,
      address.memo ?? null, Number(address.addressVersion ?? 0), address.status ?? "ACTIVE",
      address.createdAt ?? now()
    );
    return this.address(address.id);
  }

  address(id) {
    const row = this.db.prepare(`
      SELECT a.id, a.user_id userId, u.external_id externalId, u.display_name displayName,
             a.chain, a.network, a.address, a.memo, a.address_version addressVersion,
             a.status, a.created_at createdAt
      FROM addresses a JOIN users u ON u.id = a.user_id WHERE a.id = ?
    `).get(id);
    if (!row) throw new Error("deposit address not found");
    return row;
  }

  addresses() {
    return this.db.prepare(`
      SELECT a.id, a.user_id userId, u.external_id externalId, u.display_name displayName,
             a.chain, a.network, a.address, a.memo, a.address_version addressVersion,
             a.status, a.created_at createdAt
      FROM addresses a JOIN users u ON u.id = a.user_id
      ORDER BY a.created_at DESC
    `).all();
  }

  balances() {
    return this.db.prepare(`
      SELECT b.user_id userId, u.external_id externalId, u.display_name displayName,
             b.chain, b.asset, b.available, b.locked, b.updated_at updatedAt
      FROM balances b JOIN users u ON u.id = b.user_id
      ORDER BY b.asset, b.chain, u.external_id
    `).all();
  }

  ledger() {
    return this.db.prepare(`
      SELECT l.id, l.event_id eventId, l.user_id userId, u.external_id externalId,
             l.chain, l.asset, l.entry_type entryType, l.direction, l.amount,
             l.reference_id referenceId, l.created_at createdAt
      FROM ledger_entries l JOIN users u ON u.id = l.user_id
      ORDER BY l.created_at DESC
    `).all();
  }

  reserveWithdrawal({ userId, custodyAddressId, chain, asset, toAddress, amount }) {
    this.user(userId);
    const address = this.address(custodyAddressId);
    if (address.userId !== userId) throw new Error("withdrawal address does not belong to user");
    if (address.chain !== chain) throw new Error("withdrawal address belongs to a different chain");
    const normalizedAmount = requirePositiveDecimal(amount);
    const id = randomUUID();
    const externalReference = `demo-${id}`;
    const idempotencyKey = `demo:${id}`;
    this.db.exec("BEGIN IMMEDIATE");
    try {
      const balance = this.#balance(userId, chain, asset);
      const available = subtractDecimal(balance.available, normalizedAmount);
      const locked = addDecimal(balance.locked, normalizedAmount);
      this.#writeBalance(userId, chain, asset, available, locked);
      const timestamp = now();
      this.db.prepare(`
        INSERT INTO withdrawals(
          id, user_id, custody_address_id, external_reference, idempotency_key,
          chain, asset, to_address, amount, status, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'REQUESTING', ?, ?)
      `).run(id, userId, custodyAddressId, externalReference, idempotencyKey,
        chain, asset, toAddress, normalizedAmount, timestamp, timestamp);
      this.db.exec("COMMIT");
    } catch (error) {
      this.db.exec("ROLLBACK");
      throw error;
    }
    return this.withdrawal(id);
  }

  acceptWithdrawal(id, remote) {
    this.db.prepare(`
      UPDATE withdrawals SET custody_withdrawal_id = coalesce(custody_withdrawal_id, ?),
             fee = ?,
             status = case when status = 'REQUESTING' then ? else status end,
             tx_hash = coalesce(tx_hash, ?),
             error_message = coalesce(error_message, ?),
             updated_at = ? WHERE id = ?
    `).run(remote.id, normalizeDecimal(remote.fee ?? "0"), remote.status ?? "CREATED",
      remote.txHash ?? null, remote.errorMessage ?? null, now(), id);
    return this.withdrawal(id);
  }

  releaseWithdrawal(id, message) {
    this.db.exec("BEGIN IMMEDIATE");
    try {
      const withdrawal = this.withdrawal(id);
      if (!withdrawal.balanceFinalized) {
        const balance = this.#balance(withdrawal.userId, withdrawal.chain, withdrawal.asset);
        this.#writeBalance(
          withdrawal.userId,
          withdrawal.chain,
          withdrawal.asset,
          addDecimal(balance.available, withdrawal.amount),
          subtractDecimal(balance.locked, withdrawal.amount)
        );
      }
      this.db.prepare(`
        UPDATE withdrawals SET status = 'REQUEST_FAILED', error_message = ?,
               balance_finalized = 1, updated_at = ? WHERE id = ?
      `).run(String(message).slice(0, 500), now(), id);
      this.db.exec("COMMIT");
    } catch (error) {
      this.db.exec("ROLLBACK");
      throw error;
    }
  }

  withdrawal(id) {
    const row = this.db.prepare(`
      SELECT id, custody_withdrawal_id custodyWithdrawalId, user_id userId,
             custody_address_id custodyAddressId, external_reference externalReference,
             idempotency_key idempotencyKey, chain, asset, to_address toAddress,
             amount, fee, status, tx_hash txHash, error_message errorMessage,
             balance_finalized balanceFinalized, created_at createdAt, updated_at updatedAt
      FROM withdrawals WHERE id = ?
    `).get(id);
    if (!row) throw new Error("withdrawal not found");
    row.balanceFinalized = Boolean(row.balanceFinalized);
    return row;
  }

  withdrawals() {
    return this.db.prepare(`
      SELECT w.id, w.custody_withdrawal_id custodyWithdrawalId, w.user_id userId,
             u.external_id externalId, w.custody_address_id custodyAddressId,
             w.external_reference externalReference, w.chain, w.asset,
             w.to_address toAddress, w.amount, w.fee, w.status, w.tx_hash txHash,
             w.error_message errorMessage, w.created_at createdAt, w.updated_at updatedAt
      FROM withdrawals w JOIN users u ON u.id = w.user_id
      ORDER BY w.created_at DESC
    `).all();
  }

  receiveWebhook(event, rawPayload) {
    const eventId = String(event?.id ?? "");
    const eventType = String(event?.type ?? "");
    if (!eventId || !eventType || !event?.data) throw new Error("invalid webhook envelope");
    this.db.prepare(`
      INSERT INTO webhook_events(event_id, event_type, signature_valid, payload, received_at)
      VALUES (?, ?, 1, ?, ?)
      ON CONFLICT(event_id) DO NOTHING
    `).run(eventId, eventType, rawPayload, now());
    const existing = this.db.prepare(
      "SELECT processed FROM webhook_events WHERE event_id = ?"
    ).get(eventId);
    if (existing?.processed) return { duplicate: true };

    this.db.exec("BEGIN IMMEDIATE");
    try {
      if (eventType === "DEPOSIT.CONFIRMED") this.#applyDeposit(eventId, event.data, rawPayload);
      else if (eventType.startsWith("WITHDRAWAL.")) this.#applyWithdrawal(eventId, eventType, event.data, rawPayload);
      this.db.prepare(`
        UPDATE webhook_events SET processed = 1, processed_at = ?, error_message = NULL
        WHERE event_id = ?
      `).run(now(), eventId);
      this.db.exec("COMMIT");
      return { duplicate: false };
    } catch (error) {
      this.db.exec("ROLLBACK");
      this.db.prepare(
        "UPDATE webhook_events SET error_message = ? WHERE event_id = ?"
      ).run(String(error.message).slice(0, 500), eventId);
      throw error;
    }
  }

  webhookEvents() {
    return this.db.prepare(`
      SELECT event_id eventId, event_type eventType, signature_valid signatureValid,
             processed, error_message errorMessage, received_at receivedAt,
             processed_at processedAt
      FROM webhook_events ORDER BY received_at DESC
    `).all().map(row => ({
      ...row,
      signatureValid: Boolean(row.signatureValid),
      processed: Boolean(row.processed)
    }));
  }

  #applyDeposit(eventId, data, rawPayload) {
    const user = this.userBySubject(String(data.subject ?? ""));
    if (!user) throw new Error(`unknown deposit subject: ${data.subject}`);
    const amount = requirePositiveDecimal(data.amount);
    const chain = String(data.chain ?? "").toUpperCase();
    const asset = String(data.asset ?? "").toUpperCase();
    const balance = this.#balance(user.id, chain, asset);
    this.#writeBalance(user.id, chain, asset, addDecimal(balance.available, amount), balance.locked);
    this.db.prepare(`
      INSERT INTO ledger_entries(
        id, event_id, user_id, chain, asset, entry_type, direction,
        amount, reference_id, raw_json, created_at
      ) VALUES (?, ?, ?, ?, ?, 'DEPOSIT', 'CREDIT', ?, ?, ?, ?)
    `).run(randomUUID(), eventId, user.id, chain, asset, amount,
      `${chain}:${data.txHash}:${data.logIndex}`, rawPayload, now());
  }

  #applyWithdrawal(eventId, eventType, data, rawPayload) {
    const withdrawal = this.db.prepare(`
      SELECT id FROM withdrawals
      WHERE custody_withdrawal_id = ? OR external_reference = ? LIMIT 1
    `).get(data.withdrawalId ?? "", data.externalReference ?? "");
    if (!withdrawal) throw new Error("webhook withdrawal is unknown to demo exchange");
    const current = this.withdrawal(withdrawal.id);
    const status = String(data.status ?? eventType.split(".").at(-1));
    const terminalConfirmed = eventType === "WITHDRAWAL.CONFIRMED";
    const terminalFailed = eventType === "WITHDRAWAL.FAILED";
    if ((terminalConfirmed || terminalFailed) && !current.balanceFinalized) {
      const balance = this.#balance(current.userId, current.chain, current.asset);
      const available = terminalFailed
        ? addDecimal(balance.available, current.amount)
        : balance.available;
      this.#writeBalance(
        current.userId,
        current.chain,
        current.asset,
        available,
        subtractDecimal(balance.locked, current.amount)
      );
      this.db.prepare(`
        INSERT INTO ledger_entries(
          id, event_id, user_id, chain, asset, entry_type, direction,
          amount, reference_id, raw_json, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      `).run(randomUUID(), eventId, current.userId, current.chain, current.asset,
        terminalConfirmed ? "WITHDRAWAL" : "WITHDRAWAL_RELEASE",
        terminalConfirmed ? "DEBIT" : "CREDIT", current.amount,
        current.externalReference, rawPayload, now());
    }
    this.db.prepare(`
      UPDATE withdrawals SET custody_withdrawal_id = coalesce(custody_withdrawal_id, ?),
             fee = ?, status = ?, tx_hash = ?, error_message = ?,
             balance_finalized = ?, updated_at = ? WHERE id = ?
    `).run(data.withdrawalId ?? null, normalizeDecimal(data.fee ?? current.fee), status,
      data.txHash ?? current.txHash, data.errorMessage ?? null,
      current.balanceFinalized || terminalConfirmed || terminalFailed ? 1 : 0,
      now(), current.id);
  }

  #balance(userId, chain, asset) {
    return this.db.prepare(`
      SELECT available, locked FROM balances WHERE user_id = ? AND chain = ? AND asset = ?
    `).get(userId, chain, asset) ?? { available: "0", locked: "0" };
  }

  #writeBalance(userId, chain, asset, available, locked) {
    this.db.prepare(`
      INSERT INTO balances(user_id, chain, asset, available, locked, updated_at)
      VALUES (?, ?, ?, ?, ?, ?)
      ON CONFLICT(user_id, chain, asset) DO UPDATE SET
        available = excluded.available,
        locked = excluded.locked,
        updated_at = excluded.updated_at
    `).run(userId, chain, asset, normalizeDecimal(available), normalizeDecimal(locked), now());
  }
}
