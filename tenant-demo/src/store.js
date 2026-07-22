import { randomUUID } from "node:crypto";
import pg from "pg";
import { addDecimal, normalizeDecimal, requirePositiveDecimal, subtractDecimal } from "./decimal.js";

const { Pool } = pg;
const now = () => new Date().toISOString();

function databaseConfiguration(overrides = {}) {
  const schema = String(overrides.schema ?? process.env.TENANT_DEMO_PG_SCHEMA ?? "tenant_demo");
  if (!/^[a-z][a-z0-9_]{0,62}$/.test(schema)) {
    throw new Error("TENANT_DEMO_PG_SCHEMA must be a safe PostgreSQL identifier");
  }
  return {
    schema,
    pool: {
      host: overrides.host ?? process.env.TENANT_DEMO_PG_HOST ?? "127.0.0.1",
      port: Number(overrides.port ?? process.env.TENANT_DEMO_PG_PORT ?? 5432),
      database: overrides.database ?? process.env.TENANT_DEMO_PG_DATABASE ?? "wallet",
      user: overrides.user ?? process.env.TENANT_DEMO_PG_USER ?? "wallet",
      password: overrides.password ?? process.env.TENANT_DEMO_PG_PASSWORD ?? "",
      max: Number(overrides.maxConnections ?? 10),
      options: `-c search_path=${schema},public`
    }
  };
}

export class DemoStore {
  static async open(options = {}) {
    const configuration = databaseConfiguration(options);
    const pool = new Pool(configuration.pool);
    const store = new DemoStore(pool, configuration.schema);
    try {
      await pool.query(`CREATE SCHEMA IF NOT EXISTS ${configuration.schema}`);
      await store.#initialize();
      return store;
    } catch (error) {
      await pool.end();
      throw error;
    }
  }

  constructor(pool, schema) {
    this.db = pool;
    this.schema = schema;
  }

  async #initialize() {
    await this.db.query(`
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
        balance_finalized BOOLEAN NOT NULL DEFAULT FALSE,
        created_at TEXT NOT NULL,
        updated_at TEXT NOT NULL
      );
      CREATE TABLE IF NOT EXISTS webhook_events (
        event_id TEXT PRIMARY KEY,
        event_type TEXT NOT NULL,
        signature_valid BOOLEAN NOT NULL,
        processed BOOLEAN NOT NULL DEFAULT FALSE,
        payload TEXT NOT NULL,
        error_message TEXT,
        received_at TEXT NOT NULL,
        processed_at TEXT
      );
    `);
  }

  async resetForTest() {
    const result = await this.db.query("SELECT current_database() AS name");
    const database = result.rows[0]?.name ?? "";
    if (!database.startsWith("surprising_wallet_test_")) {
      throw new Error(`refusing to reset non-test database: ${database}`);
    }
    await this.db.query(`
      TRUNCATE TABLE webhook_events, ledger_entries, withdrawals, balances,
                     addresses, users, settings CASCADE
    `);
  }

  async close() {
    await this.db.end();
  }

  async #transaction(work) {
    const client = await this.db.connect();
    try {
      await client.query("BEGIN");
      const result = await work(client);
      await client.query("COMMIT");
      return result;
    } catch (error) {
      await client.query("ROLLBACK");
      throw error;
    } finally {
      client.release();
    }
  }

  async configuration() {
    const result = await this.db.query("SELECT key, value FROM settings");
    return Object.fromEntries(result.rows.map(row => [row.key, row.value]));
  }

  async saveConfiguration(values) {
    await this.#transaction(async client => {
      for (const [key, value] of Object.entries(values)) {
        if (value === undefined || value === null) continue;
        await client.query(`
          INSERT INTO settings(key, value) VALUES ($1, $2)
          ON CONFLICT(key) DO UPDATE SET value = excluded.value
        `, [key, String(value).trim()]);
      }
    });
    return this.configuration();
  }

  async createUser({ externalId, displayName }) {
    const subject = String(externalId ?? "").trim();
    const name = String(displayName ?? "").trim();
    if (!/^[A-Za-z0-9_][A-Za-z0-9._:-]{0,159}$/.test(subject)) {
      throw new Error("externalId must be a valid wallet subject");
    }
    if (!name || name.length > 120) throw new Error("displayName is required");
    const user = { id: randomUUID(), externalId: subject, displayName: name, createdAt: now() };
    await this.db.query(`
      INSERT INTO users(id, external_id, display_name, created_at) VALUES ($1, $2, $3, $4)
    `, [user.id, user.externalId, user.displayName, user.createdAt]);
    return user;
  }

  async #user(queryable, id) {
    const result = await queryable.query(`
      SELECT id, external_id AS "externalId", display_name AS "displayName",
             created_at AS "createdAt"
      FROM users WHERE id = $1
    `, [id]);
    if (!result.rows[0]) throw new Error("user not found");
    return result.rows[0];
  }

  async user(id) {
    return this.#user(this.db, id);
  }

  async #userBySubject(queryable, subject) {
    const result = await queryable.query(`
      SELECT id, external_id AS "externalId", display_name AS "displayName",
             created_at AS "createdAt"
      FROM users WHERE external_id = $1
    `, [subject]);
    return result.rows[0];
  }

  async userBySubject(subject) {
    return this.#userBySubject(this.db, subject);
  }

  async users() {
    const result = await this.db.query(`
      SELECT id, external_id AS "externalId", display_name AS "displayName",
             created_at AS "createdAt"
      FROM users ORDER BY created_at DESC
    `);
    return result.rows;
  }

  async saveAddress(userId, address) {
    await this.user(userId);
    await this.db.query(`
      INSERT INTO addresses(
        id, user_id, chain, network, address, memo, address_version, status, created_at
      ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
      ON CONFLICT(user_id, chain, address_version) DO UPDATE SET
        id = excluded.id,
        network = excluded.network,
        address = excluded.address,
        memo = excluded.memo,
        status = excluded.status
    `, [
      address.id, userId, address.chain, address.network ?? null, address.address,
      address.memo ?? null, Number(address.addressVersion ?? 0), address.status ?? "ACTIVE",
      address.createdAt ?? now()
    ]);
    return this.address(address.id);
  }

  async #address(queryable, id) {
    const result = await queryable.query(`
      SELECT a.id, a.user_id AS "userId", u.external_id AS "externalId",
             u.display_name AS "displayName", a.chain, a.network, a.address, a.memo,
             a.address_version AS "addressVersion", a.status, a.created_at AS "createdAt"
      FROM addresses a JOIN users u ON u.id = a.user_id WHERE a.id = $1
    `, [id]);
    if (!result.rows[0]) throw new Error("deposit address not found");
    return result.rows[0];
  }

  async address(id) {
    return this.#address(this.db, id);
  }

  async addresses() {
    const result = await this.db.query(`
      SELECT a.id, a.user_id AS "userId", u.external_id AS "externalId",
             u.display_name AS "displayName", a.chain, a.network, a.address, a.memo,
             a.address_version AS "addressVersion", a.status, a.created_at AS "createdAt"
      FROM addresses a JOIN users u ON u.id = a.user_id
      ORDER BY a.created_at DESC
    `);
    return result.rows;
  }

  async balances() {
    const result = await this.db.query(`
      SELECT b.user_id AS "userId", u.external_id AS "externalId",
             u.display_name AS "displayName", b.chain, b.asset, b.available, b.locked,
             b.updated_at AS "updatedAt"
      FROM balances b JOIN users u ON u.id = b.user_id
      ORDER BY b.asset, b.chain, u.external_id
    `);
    return result.rows;
  }

  async ledger() {
    const result = await this.db.query(`
      SELECT l.id, l.event_id AS "eventId", l.user_id AS "userId",
             u.external_id AS "externalId", l.chain, l.asset,
             l.entry_type AS "entryType", l.direction, l.amount,
             l.reference_id AS "referenceId", l.created_at AS "createdAt"
      FROM ledger_entries l JOIN users u ON u.id = l.user_id
      ORDER BY l.created_at DESC
    `);
    return result.rows;
  }

  async reserveWithdrawal({ userId, custodyAddressId, chain, asset, toAddress, amount }) {
    const normalizedAmount = requirePositiveDecimal(amount);
    const id = randomUUID();
    const externalReference = `demo-${id}`;
    const idempotencyKey = `demo:${id}`;
    await this.#transaction(async client => {
      await this.#user(client, userId);
      const address = await this.#address(client, custodyAddressId);
      if (address.userId !== userId) throw new Error("withdrawal address does not belong to user");
      if (address.chain !== chain) throw new Error("withdrawal address belongs to a different chain");
      await this.#lockBalance(client, userId, chain, asset);
      const balance = await this.#balance(client, userId, chain, asset);
      const available = subtractDecimal(balance.available, normalizedAmount);
      const locked = addDecimal(balance.locked, normalizedAmount);
      await this.#writeBalance(client, userId, chain, asset, available, locked);
      const timestamp = now();
      await client.query(`
        INSERT INTO withdrawals(
          id, user_id, custody_address_id, external_reference, idempotency_key,
          chain, asset, to_address, amount, status, created_at, updated_at
        ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, 'REQUESTING', $10, $11)
      `, [id, userId, custodyAddressId, externalReference, idempotencyKey,
        chain, asset, toAddress, normalizedAmount, timestamp, timestamp]);
    });
    return this.withdrawal(id);
  }

  async acceptWithdrawal(id, remote) {
    await this.db.query(`
      UPDATE withdrawals SET custody_withdrawal_id = coalesce(custody_withdrawal_id, $1),
             fee = $2,
             status = case when status = 'REQUESTING' then $3 else status end,
             tx_hash = coalesce(tx_hash, $4),
             error_message = coalesce(error_message, $5),
             updated_at = $6 WHERE id = $7
    `, [remote.id, normalizeDecimal(remote.fee ?? "0"), remote.status ?? "CREATED",
      remote.txHash ?? null, remote.errorMessage ?? null, now(), id]);
    return this.withdrawal(id);
  }

  async releaseWithdrawal(id, message) {
    await this.#transaction(async client => {
      const withdrawal = await this.#withdrawal(client, id);
      if (!withdrawal.balanceFinalized) {
        await this.#lockBalance(client, withdrawal.userId, withdrawal.chain, withdrawal.asset);
        const balance = await this.#balance(
          client, withdrawal.userId, withdrawal.chain, withdrawal.asset
        );
        await this.#writeBalance(
          client, withdrawal.userId, withdrawal.chain, withdrawal.asset,
          addDecimal(balance.available, withdrawal.amount),
          subtractDecimal(balance.locked, withdrawal.amount)
        );
      }
      await client.query(`
        UPDATE withdrawals SET status = 'REQUEST_FAILED', error_message = $1,
               balance_finalized = TRUE, updated_at = $2 WHERE id = $3
      `, [String(message).slice(0, 500), now(), id]);
    });
  }

  async #withdrawal(queryable, id) {
    const result = await queryable.query(`
      SELECT id, custody_withdrawal_id AS "custodyWithdrawalId", user_id AS "userId",
             custody_address_id AS "custodyAddressId", external_reference AS "externalReference",
             idempotency_key AS "idempotencyKey", chain, asset, to_address AS "toAddress",
             amount, fee, status, tx_hash AS "txHash", error_message AS "errorMessage",
             balance_finalized AS "balanceFinalized", created_at AS "createdAt",
             updated_at AS "updatedAt"
      FROM withdrawals WHERE id = $1
    `, [id]);
    if (!result.rows[0]) throw new Error("withdrawal not found");
    return result.rows[0];
  }

  async withdrawal(id) {
    return this.#withdrawal(this.db, id);
  }

  async withdrawals() {
    const result = await this.db.query(`
      SELECT w.id, w.custody_withdrawal_id AS "custodyWithdrawalId",
             w.user_id AS "userId", u.external_id AS "externalId",
             w.custody_address_id AS "custodyAddressId",
             w.external_reference AS "externalReference", w.chain, w.asset,
             w.to_address AS "toAddress", w.amount, w.fee, w.status,
             w.tx_hash AS "txHash", w.error_message AS "errorMessage",
             w.created_at AS "createdAt", w.updated_at AS "updatedAt"
      FROM withdrawals w JOIN users u ON u.id = w.user_id
      ORDER BY w.created_at DESC
    `);
    return result.rows;
  }

  async receiveWebhook(event, rawPayload) {
    const eventId = String(event?.id ?? "");
    const eventType = String(event?.type ?? "");
    if (!eventId || !eventType || !event?.data) throw new Error("invalid webhook envelope");
    await this.db.query(`
      INSERT INTO webhook_events(event_id, event_type, signature_valid, payload, received_at)
      VALUES ($1, $2, TRUE, $3, $4)
      ON CONFLICT(event_id) DO NOTHING
    `, [eventId, eventType, rawPayload, now()]);
    try {
      return await this.#transaction(async client => {
        const locked = await client.query(`
          SELECT processed FROM webhook_events WHERE event_id = $1 FOR UPDATE
        `, [eventId]);
        if (locked.rows[0]?.processed) return { duplicate: true };
        if (eventType === "DEPOSIT.CONFIRMED") {
          await this.#applyDeposit(client, eventId, event.data, rawPayload);
        } else if (eventType.startsWith("WITHDRAWAL.")) {
          await this.#applyWithdrawal(client, eventId, eventType, event.data, rawPayload);
        }
        await client.query(`
          UPDATE webhook_events SET processed = TRUE, processed_at = $1, error_message = NULL
          WHERE event_id = $2
        `, [now(), eventId]);
        return { duplicate: false };
      });
    } catch (error) {
      await this.db.query(
        "UPDATE webhook_events SET error_message = $1 WHERE event_id = $2",
        [String(error.message).slice(0, 500), eventId]
      );
      throw error;
    }
  }

  async webhookEvents() {
    const result = await this.db.query(`
      SELECT event_id AS "eventId", event_type AS "eventType",
             signature_valid AS "signatureValid", processed,
             error_message AS "errorMessage", received_at AS "receivedAt",
             processed_at AS "processedAt"
      FROM webhook_events ORDER BY received_at DESC
    `);
    return result.rows;
  }

  async #applyDeposit(client, eventId, data, rawPayload) {
    const user = await this.#userBySubject(client, String(data.subject ?? ""));
    if (!user) throw new Error(`unknown deposit subject: ${data.subject}`);
    const amount = requirePositiveDecimal(data.amount);
    const chain = String(data.chain ?? "").toUpperCase();
    const asset = String(data.asset ?? "").toUpperCase();
    await this.#lockBalance(client, user.id, chain, asset);
    const balance = await this.#balance(client, user.id, chain, asset);
    await this.#writeBalance(
      client, user.id, chain, asset, addDecimal(balance.available, amount), balance.locked
    );
    await client.query(`
      INSERT INTO ledger_entries(
        id, event_id, user_id, chain, asset, entry_type, direction,
        amount, reference_id, raw_json, created_at
      ) VALUES ($1, $2, $3, $4, $5, 'DEPOSIT', 'CREDIT', $6, $7, $8, $9)
    `, [randomUUID(), eventId, user.id, chain, asset, amount,
      `${chain}:${data.txHash}:${data.logIndex}`, rawPayload, now()]);
  }

  async #applyWithdrawal(client, eventId, eventType, data, rawPayload) {
    const result = await client.query(`
      SELECT id FROM withdrawals
      WHERE custody_withdrawal_id = $1 OR external_reference = $2 LIMIT 1
    `, [data.withdrawalId ?? "", data.externalReference ?? ""]);
    if (!result.rows[0]) throw new Error("webhook withdrawal is unknown to demo exchange");
    const current = await this.#withdrawal(client, result.rows[0].id);
    const status = String(data.status ?? eventType.split(".").at(-1));
    const terminalConfirmed = eventType === "WITHDRAWAL.CONFIRMED";
    const terminalFailed = eventType === "WITHDRAWAL.FAILED";
    if ((terminalConfirmed || terminalFailed) && !current.balanceFinalized) {
      await this.#lockBalance(client, current.userId, current.chain, current.asset);
      const balance = await this.#balance(client, current.userId, current.chain, current.asset);
      const available = terminalFailed
        ? addDecimal(balance.available, current.amount)
        : balance.available;
      await this.#writeBalance(
        client, current.userId, current.chain, current.asset,
        available, subtractDecimal(balance.locked, current.amount)
      );
      await client.query(`
        INSERT INTO ledger_entries(
          id, event_id, user_id, chain, asset, entry_type, direction,
          amount, reference_id, raw_json, created_at
        ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)
      `, [randomUUID(), eventId, current.userId, current.chain, current.asset,
        terminalConfirmed ? "WITHDRAWAL" : "WITHDRAWAL_RELEASE",
        terminalConfirmed ? "DEBIT" : "CREDIT", current.amount,
        current.externalReference, rawPayload, now()]);
    }
    await client.query(`
      UPDATE withdrawals SET custody_withdrawal_id = coalesce(custody_withdrawal_id, $1),
             fee = $2, status = $3, tx_hash = $4, error_message = $5,
             balance_finalized = $6, updated_at = $7 WHERE id = $8
    `, [data.withdrawalId ?? null, normalizeDecimal(data.fee ?? current.fee), status,
      data.txHash ?? current.txHash, data.errorMessage ?? null,
      current.balanceFinalized || terminalConfirmed || terminalFailed, now(), current.id]);
  }

  async #lockBalance(client, userId, chain, asset) {
    const key = `${userId.length}:${userId}${chain.length}:${chain}${asset.length}:${asset}`;
    await client.query(
      "SELECT pg_advisory_xact_lock(hashtextextended($1, 0))",
      [key]
    );
  }

  async #balance(queryable, userId, chain, asset) {
    const result = await queryable.query(`
      SELECT available, locked FROM balances
      WHERE user_id = $1 AND chain = $2 AND asset = $3
    `, [userId, chain, asset]);
    return result.rows[0] ?? { available: "0", locked: "0" };
  }

  async #writeBalance(queryable, userId, chain, asset, available, locked) {
    await queryable.query(`
      INSERT INTO balances(user_id, chain, asset, available, locked, updated_at)
      VALUES ($1, $2, $3, $4, $5, $6)
      ON CONFLICT(user_id, chain, asset) DO UPDATE SET
        available = excluded.available,
        locked = excluded.locked,
        updated_at = excluded.updated_at
    `, [userId, chain, asset, normalizeDecimal(available), normalizeDecimal(locked), now()]);
  }
}
