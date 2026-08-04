import { randomBytes, randomUUID, scryptSync, timingSafeEqual } from "node:crypto";
import { mkdir } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import {
  addDecimal,
  compareDecimal,
  normalizeDecimal,
  requirePositiveDecimal,
  subtractDecimal
} from "./decimal.js";

let BuiltinDatabaseSync;
try {
  ({ DatabaseSync: BuiltinDatabaseSync } = await import("node:sqlite"));
} catch {
  BuiltinDatabaseSync = undefined;
}

const moduleDir = dirname(fileURLToPath(import.meta.url));
const projectDir = dirname(moduleDir);
const defaultDatabaseFile = join(projectDir, "data", "tenant-demo.sqlite3");
const now = () => new Date().toISOString();

class SyncDatabase {
  constructor(filename) {
    this.database = new BuiltinDatabaseSync(filename);
  }

  run(sql, parameters = []) {
    const result = this.database.prepare(sql).run(...parameters);
    return Promise.resolve({ changes: result.changes, lastID: result.lastInsertRowid });
  }

  get(sql, parameters = []) {
    return Promise.resolve(this.database.prepare(sql).get(...parameters));
  }

  all(sql, parameters = []) {
    return Promise.resolve(this.database.prepare(sql).all(...parameters));
  }

  exec(sql) {
    this.database.exec(sql);
    return Promise.resolve();
  }

  close() {
    this.database.close();
  }
}

class CallbackDatabase {
  constructor(database) {
    this.database = database;
  }

  run(sql, parameters = []) {
    return new Promise((resolve, reject) => {
      this.database.run(sql, parameters, function callback(error) {
        if (error) return reject(error);
        return resolve({ changes: this.changes, lastID: this.lastID });
      });
    });
  }

  get(sql, parameters = []) {
    return new Promise((resolve, reject) => {
      this.database.get(sql, parameters, (error, row) => error ? reject(error) : resolve(row));
    });
  }

  all(sql, parameters = []) {
    return new Promise((resolve, reject) => {
      this.database.all(sql, parameters, (error, rows) => error ? reject(error) : resolve(rows));
    });
  }

  exec(sql) {
    return new Promise((resolve, reject) => {
      this.database.exec(sql, error => error ? reject(error) : resolve());
    });
  }

  close() {
    return new Promise((resolve, reject) => {
      this.database.close(error => error ? reject(error) : resolve());
    });
  }
}

async function openDatabase(filename) {
  if (BuiltinDatabaseSync) return new SyncDatabase(filename);
  const module = await import("sqlite3");
  const sqlite3 = module.default ?? module;
  const driver = typeof sqlite3.verbose === "function" ? sqlite3.verbose() : sqlite3;
  const database = await new Promise((resolve, reject) => {
    const connection = new driver.Database(filename, error => error ? reject(error) : resolve(connection));
  });
  database.configure("busyTimeout", 5000);
  return new CallbackDatabase(database);
}

function run(database, sql, parameters = []) {
  return database.run(sql, parameters);
}

function get(database, sql, parameters = []) {
  return database.get(sql, parameters);
}

function all(database, sql, parameters = []) {
  return database.all(sql, parameters);
}

function exec(database, sql) {
  return database.exec(sql);
}

function hashSecret(value) {
  return scryptSync(value, "tenant-demo-session-salt", 32).toString("hex");
}

export function hashPassword(password) {
  const salt = randomBytes(16).toString("hex");
  const digest = scryptSync(password, salt, 64).toString("hex");
  return `scrypt$${salt}$${digest}`;
}

export function verifyPassword(password, encoded) {
  const [algorithm, salt, digest] = String(encoded ?? "").split("$");
  if (algorithm !== "scrypt" || !salt || !digest) return false;
  try {
    const actual = scryptSync(password, salt, 64);
    const expected = Buffer.from(digest, "hex");
    return actual.length === expected.length && timingSafeEqual(actual, expected);
  } catch {
    return false;
  }
}

function safeEmail(value) {
  const email = String(value ?? "").trim().toLowerCase();
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]{2,120}$/.test(email) || email.length > 160) {
    throw new Error("email must be a valid address");
  }
  return email;
}

function safePassword(value) {
  const password = String(value ?? "");
  if (password.length < 8 || password.length > 128) {
    throw new Error("password must contain 8 to 128 characters");
  }
  return password;
}

function userView(row) {
  return row ? {
    id: row.id,
    email: row.email,
    externalId: row.externalId,
    displayName: row.displayName,
    role: row.role,
    createdAt: row.createdAt
  } : null;
}

export class DemoStore {
  static async open(options = {}) {
    const filename = options.filename
      ?? process.env.TENANT_DEMO_SQLITE_PATH
      ?? defaultDatabaseFile;
    if (filename !== ":memory:") await mkdir(dirname(filename), { recursive: true });
    const database = await openDatabase(filename);
    const store = new DemoStore(database, filename);
    try {
      await exec(database, "PRAGMA foreign_keys = ON; PRAGMA busy_timeout = 5000; PRAGMA journal_mode = WAL; PRAGMA synchronous = NORMAL;");
      await store.#initialize();
      return store;
    } catch (error) {
      await store.close();
      throw error;
    }
  }

  constructor(database, filename) {
    this.db = database;
    this.filename = filename;
    this.transactionQueue = Promise.resolve();
  }

  async #initialize() {
    await exec(this.db, `
      CREATE TABLE IF NOT EXISTS settings (
        key TEXT PRIMARY KEY,
        value TEXT NOT NULL
      );
      CREATE TABLE IF NOT EXISTS users (
        id TEXT PRIMARY KEY,
        email TEXT NOT NULL UNIQUE,
        external_id TEXT NOT NULL UNIQUE,
        password_hash TEXT NOT NULL,
        display_name TEXT NOT NULL,
        role TEXT NOT NULL DEFAULT 'USER',
        status TEXT NOT NULL DEFAULT 'ACTIVE',
        created_at TEXT NOT NULL,
        last_login_at TEXT
      );
      CREATE TABLE IF NOT EXISTS sessions (
        id TEXT PRIMARY KEY,
        user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        expires_at TEXT NOT NULL,
        created_at TEXT NOT NULL,
        last_seen_at TEXT NOT NULL
      );
      CREATE INDEX IF NOT EXISTS sessions_expiry_idx ON sessions(expires_at);
      CREATE TABLE IF NOT EXISTS addresses (
        id TEXT PRIMARY KEY,
        user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        chain TEXT NOT NULL,
        network TEXT,
        address TEXT NOT NULL,
        memo TEXT,
        address_version INTEGER NOT NULL,
        status TEXT NOT NULL,
        created_at TEXT NOT NULL,
        UNIQUE(user_id, chain, address_version)
      );
      CREATE INDEX IF NOT EXISTS addresses_user_idx ON addresses(user_id, created_at DESC);
      CREATE TABLE IF NOT EXISTS balances (
        user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
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
        user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        chain TEXT NOT NULL,
        asset TEXT NOT NULL,
        entry_type TEXT NOT NULL,
        direction TEXT NOT NULL,
        amount TEXT NOT NULL,
        reference_id TEXT NOT NULL,
        raw_json TEXT NOT NULL,
        created_at TEXT NOT NULL,
        UNIQUE(event_id, entry_type),
        UNIQUE(user_id, entry_type, reference_id, direction)
      );
      CREATE TABLE IF NOT EXISTS withdrawals (
        id TEXT PRIMARY KEY,
        custody_withdrawal_id TEXT UNIQUE,
        user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        custody_address_id TEXT NOT NULL,
        external_reference TEXT NOT NULL UNIQUE,
        idempotency_key TEXT NOT NULL UNIQUE,
        chain TEXT NOT NULL,
        network TEXT,
        asset TEXT NOT NULL,
        to_address TEXT NOT NULL,
        amount TEXT NOT NULL,
        order_no TEXT,
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
    const withdrawalColumns = await all(this.db, "PRAGMA table_info(withdrawals)");
    if (!withdrawalColumns.some(column => column.name === "network")) {
      await exec(this.db, "ALTER TABLE withdrawals ADD COLUMN network TEXT");
    }
    if (!withdrawalColumns.some(column => column.name === "order_no")) {
      await exec(this.db, "ALTER TABLE withdrawals ADD COLUMN order_no TEXT");
    }
    await run(this.db, `
      UPDATE withdrawals
      SET network = (
        SELECT a.network FROM addresses a WHERE a.id = withdrawals.custody_address_id
      )
      WHERE network IS NULL
    `);
  }

  async resetForTest() {
    if (this.filename !== ":memory:" && !this.filename.includes("test")) {
      throw new Error(`refusing to reset non-test database: ${this.filename}`);
    }
    await this.#transaction(async database => {
      await exec(database, `
        DELETE FROM webhook_events;
        DELETE FROM ledger_entries;
        DELETE FROM withdrawals;
        DELETE FROM balances;
        DELETE FROM addresses;
        DELETE FROM sessions;
        DELETE FROM users;
        DELETE FROM settings;
      `);
    });
  }

  async close() {
    this.db.close();
  }

  async #transaction(work) {
    const execute = this.transactionQueue.then(async () => {
      await run(this.db, "BEGIN IMMEDIATE");
      try {
        const result = await work(this.db);
        await run(this.db, "COMMIT");
        return result;
      } catch (error) {
        await run(this.db, "ROLLBACK").catch(() => {});
        throw error;
      }
    });
    this.transactionQueue = execute.catch(() => {});
    return execute;
  }

  async configuration() {
    const rows = await all(this.db, "SELECT key, value FROM settings");
    return Object.fromEntries(rows.map(row => [row.key, row.value]));
  }

  async saveConfiguration(values) {
    await this.#transaction(async database => {
      for (const [key, value] of Object.entries(values)) {
        if (value === undefined || value === null) continue;
        await run(database, `
          INSERT INTO settings(key, value) VALUES (?, ?)
          ON CONFLICT(key) DO UPDATE SET value = excluded.value
        `, [key, String(value).trim()]);
      }
    });
    return this.configuration();
  }

  async registerUser({ email, password, displayName }) {
    const normalizedEmail = safeEmail(email);
    const name = String(displayName ?? normalizedEmail.split("@")[0]).trim();
    if (!name || name.length > 120) throw new Error("displayName is required");
    const normalizedPassword = safePassword(password);
    const userId = randomUUID();
    const user = await this.#transaction(async database => {
      const createdAt = now();
      await run(database, `
        INSERT INTO users(id, email, external_id, password_hash, display_name, created_at)
        VALUES (?, ?, ?, ?, ?, ?)
      `, [userId, normalizedEmail, `user-${userId}`, hashPassword(normalizedPassword), name, createdAt]);
      return this.#user(database, userId);
    });
    return userView(user);
  }

  async authenticateUser({ email, password }) {
    const row = await get(this.db, `
      SELECT id, email, external_id AS externalId, password_hash AS passwordHash,
             display_name AS displayName, role, status, created_at AS createdAt
      FROM users WHERE lower(email) = lower(?)
    `, [String(email ?? "").trim()]);
    if (!row || row.status !== "ACTIVE" || !verifyPassword(String(password ?? ""), row.passwordHash)) {
      throw new Error("email or password is incorrect");
    }
    await run(this.db, "UPDATE users SET last_login_at = ? WHERE id = ?", [now(), row.id]);
    return userView(row);
  }

  async createSession(userId, ttlHours = 24 * 7) {
    const token = randomBytes(32).toString("base64url");
    const createdAt = now();
    const expiresAt = new Date(Date.now() + ttlHours * 60 * 60 * 1000).toISOString();
    await run(this.db, `
      INSERT INTO sessions(id, user_id, expires_at, created_at, last_seen_at)
      VALUES (?, ?, ?, ?, ?)
    `, [hashSecret(token), userId, expiresAt, createdAt, createdAt]);
    return token;
  }

  async sessionUser(token) {
    const value = String(token ?? "").trim();
    if (!value) return null;
    const row = await get(this.db, `
      SELECT u.id, u.email, u.external_id AS externalId, u.display_name AS displayName,
             u.role, u.status, u.created_at AS createdAt, s.expires_at AS expiresAt
      FROM sessions s JOIN users u ON u.id = s.user_id
      WHERE s.id = ?
    `, [hashSecret(value)]);
    if (!row || row.status !== "ACTIVE" || row.expiresAt <= now()) {
      if (row) await run(this.db, "DELETE FROM sessions WHERE id = ?", [hashSecret(value)]);
      return null;
    }
    await run(this.db, "UPDATE sessions SET last_seen_at = ? WHERE id = ?", [now(), hashSecret(value)]);
    return userView(row);
  }

  async deleteSession(token) {
    await run(this.db, "DELETE FROM sessions WHERE id = ?", [hashSecret(String(token ?? ""))]);
  }

  async createUser({ externalId, displayName, email, password, role = "USER" }) {
    const subject = String(externalId ?? `user-${randomUUID()}`).trim();
    const name = String(displayName ?? "").trim();
    if (!/^[A-Za-z0-9_][A-Za-z0-9._:-]{0,159}$/.test(subject)) {
      throw new Error("externalId must be a valid wallet subject");
    }
    if (!name || name.length > 120) throw new Error("displayName is required");
    const normalizedEmail = safeEmail(email ?? `${subject}@tenant-demo.local`);
    const passwordValue = password ? safePassword(password) : randomBytes(24).toString("base64url");
    const userId = randomUUID();
    return userView(await this.#transaction(async database => {
      await run(database, `
        INSERT INTO users(id, email, external_id, password_hash, display_name, role, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)
      `, [userId, normalizedEmail, subject, hashPassword(passwordValue), name, role, now()]);
      return this.#user(database, userId);
    }));
  }

  async #user(queryable, id) {
    return get(queryable, `
      SELECT id, email, external_id AS externalId, password_hash AS passwordHash,
             display_name AS displayName, role, status, created_at AS createdAt
      FROM users WHERE id = ?
    `, [id]).then(row => {
      if (!row) throw new Error("user not found");
      return row;
    });
  }

  async user(id) {
    return userView(await this.#user(this.db, id));
  }

  async #userBySubject(queryable, subject) {
    return get(queryable, `
      SELECT id, email, external_id AS externalId, password_hash AS passwordHash,
             display_name AS displayName, role, status, created_at AS createdAt
      FROM users WHERE external_id = ?
    `, [subject]);
  }

  async userBySubject(subject) {
    const row = await this.#userBySubject(this.db, subject);
    return userView(row);
  }

  async users() {
    const rows = await all(this.db, `
      SELECT id, email, external_id AS externalId, display_name AS displayName,
             role, status, created_at AS createdAt
      FROM users ORDER BY created_at DESC
    `);
    return rows.map(userView);
  }

  async saveAddress(userId, address) {
    await this.user(userId);
    await this.#transaction(async database => {
      await run(database, `
        INSERT INTO addresses(
          id, user_id, chain, network, address, memo, address_version, status, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(user_id, chain, address_version) DO UPDATE SET
          id = excluded.id, network = excluded.network, address = excluded.address,
          memo = excluded.memo, status = excluded.status
      `, [
        address.id, userId, String(address.chain).toUpperCase(), address.network ?? null,
        address.address, address.memo ?? null, Number(address.addressVersion ?? 0),
        address.status ?? "ACTIVE", address.createdAt ?? now()
      ]);
    });
    return this.address(address.id);
  }

  async #address(queryable, id) {
    const row = await get(queryable, `
      SELECT a.id, a.user_id AS userId, u.email, u.external_id AS externalId,
             u.display_name AS displayName, a.chain, a.network, a.address, a.memo,
             a.address_version AS addressVersion, a.status, a.created_at AS createdAt
      FROM addresses a JOIN users u ON u.id = a.user_id WHERE a.id = ?
    `, [id]);
    if (!row) throw new Error("deposit address not found");
    return row;
  }

  async address(id) {
    return this.#address(this.db, id);
  }

  async addresses(userId = null) {
    const predicate = userId ? "WHERE a.user_id = ?" : "";
    return all(this.db, `
      SELECT a.id, a.user_id AS userId, u.email, u.external_id AS externalId,
             u.display_name AS displayName, a.chain, a.network, a.address, a.memo,
             a.address_version AS addressVersion, a.status, a.created_at AS createdAt
      FROM addresses a JOIN users u ON u.id = a.user_id
      ${predicate} ORDER BY a.created_at DESC
    `, userId ? [userId] : []);
  }

  /** 查询当前用户按链分页的历史充值地址。 */
  async addressHistory(userId, chain, page = 1, pageSize = 5) {
    const normalizedChain = String(chain ?? "").trim().toUpperCase();
    if (!normalizedChain) throw new Error("chain is required");
    const safePage = Math.max(Number(page) || 1, 1);
    const safePageSize = Math.min(Math.max(Number(pageSize) || 5, 1), 20);
    const offset = (safePage - 1) * safePageSize;
    const totalRow = await get(this.db, `
      SELECT count(*) AS total FROM addresses WHERE user_id = ? AND chain = ?
    `, [userId, normalizedChain]);
    const items = await all(this.db, `
      SELECT a.id, a.user_id AS userId, u.email, u.external_id AS externalId,
             u.display_name AS displayName, a.chain, a.network, a.address, a.memo,
             a.address_version AS addressVersion, a.status, a.created_at AS createdAt
      FROM addresses a JOIN users u ON u.id = a.user_id
      WHERE a.user_id = ? AND a.chain = ?
      ORDER BY a.address_version DESC, a.created_at DESC
      LIMIT ? OFFSET ?
    `, [userId, normalizedChain, safePageSize, offset]);
    const total = Number(totalRow?.total ?? 0);
    return {
      items,
      total,
      page: safePage,
      pageSize: safePageSize,
      pages: Math.max(Math.ceil(total / safePageSize), 1)
    };
  }

  /** 查询指定链上当前用户最新的有效充值地址。 */
  async latestAddress(userId, chain) {
    const normalizedChain = String(chain ?? "").trim().toUpperCase();
    if (!normalizedChain) throw new Error("chain is required");
    const row = await get(this.db, `
      SELECT id FROM addresses
      WHERE user_id = ? AND chain = ? AND status = 'ACTIVE'
      ORDER BY address_version DESC, created_at DESC
      LIMIT 1
    `, [userId, normalizedChain]);
    return row ? this.address(row.id) : null;
  }

  /** 查询平台内其他用户的有效充值地址，供测试提现目标地址使用。 */
  async platformAddresses(userId, chain = "", limit = 100) {
    const normalizedChain = String(chain ?? "").trim().toUpperCase();
    const safeLimit = Math.min(Math.max(Number(limit) || 100, 1), 200);
    const chainPredicate = normalizedChain ? "AND a.chain = ?" : "";
    const parameters = normalizedChain ? [userId, normalizedChain, safeLimit] : [userId, safeLimit];
    return all(this.db, `
      SELECT a.id, a.user_id AS userId, u.email, u.external_id AS externalId,
             u.display_name AS displayName, a.chain, a.network, a.address, a.memo,
             a.address_version AS addressVersion, a.status, a.created_at AS createdAt
      FROM addresses a JOIN users u ON u.id = a.user_id
      WHERE a.user_id <> ? AND a.status = 'ACTIVE' ${chainPredicate}
      ORDER BY a.created_at DESC, a.address_version DESC
      LIMIT ?
    `, parameters);
  }

  /** 为指定链计算当前用户下一版本的充值地址版本号。 */
  async nextAddressVersion(userId, chain) {
    const normalizedChain = String(chain ?? "").trim().toUpperCase();
    if (!normalizedChain) throw new Error("chain is required");
    const row = await get(this.db, `
      SELECT coalesce(max(address_version), -1) + 1 AS next_version
      FROM addresses WHERE user_id = ? AND chain = ?
    `, [userId, normalizedChain]);
    return Number(row?.next_version ?? 0);
  }

  async balances(userId = null) {
    const predicate = userId ? "WHERE b.user_id = ?" : "";
    return all(this.db, `
      SELECT b.user_id AS userId, u.email, u.external_id AS externalId,
             u.display_name AS displayName, b.chain, b.asset, b.available, b.locked,
             b.updated_at AS updatedAt
      FROM balances b JOIN users u ON u.id = b.user_id
      ${predicate} ORDER BY b.asset, b.chain, u.external_id
    `, userId ? [userId] : []);
  }

  async ledger(userId = null) {
    const predicate = userId ? "WHERE l.user_id = ?" : "";
    const rows = await all(this.db, `
      SELECT l.id, l.event_id AS eventId, l.user_id AS userId,
             u.email, u.external_id AS externalId, l.chain, l.asset,
             l.entry_type AS entryType, l.direction, l.amount,
             l.reference_id AS referenceId, l.raw_json AS rawJson, l.created_at AS createdAt
      FROM ledger_entries l JOIN users u ON u.id = l.user_id
      ${predicate} ORDER BY l.created_at DESC
    `, userId ? [userId] : []);
    return rows.map(row => {
      let data = {};
      try {
        data = JSON.parse(row.rawJson ?? "{}").data ?? {};
      } catch {
        data = {};
      }
      const { rawJson, ...view } = row;
      return {
        ...view,
        txHash: data.txHash ?? null,
        depositAddress: data.address ?? null,
        address: data.address ?? data.toAddress ?? null
      };
    });
  }

  async reserveWithdrawal({ userId, custodyAddressId = null, chain, asset, toAddress, amount }) {
    const normalizedAmount = requirePositiveDecimal(amount);
    const normalizedChain = String(chain ?? "").toUpperCase();
    const normalizedAsset = String(asset ?? "").toUpperCase();
    const destination = String(toAddress ?? "").trim();
    if (!normalizedChain || !normalizedAsset || !destination || destination.length > 160) {
      throw new Error("chain, asset and toAddress are required");
    }
    const id = randomUUID();
    const externalReference = `demo-${id}`;
    const idempotencyKey = `demo:${id}`;
    await this.#transaction(async database => {
      await this.#user(database, userId);
      const address = custodyAddressId
        ? await this.#address(database, custodyAddressId)
        : await this.#latestAddress(database, userId, normalizedChain);
      if (!address) throw new Error("no active withdrawal address for selected chain");
      if (address.userId !== userId) throw new Error("withdrawal address does not belong to user");
      if (address.chain !== normalizedChain) throw new Error("withdrawal address belongs to a different chain");
      if (address.status !== "ACTIVE") throw new Error("withdrawal address is not active");
      await this.#lockBalance(database, userId, normalizedChain, normalizedAsset);
      const balance = await this.#balance(database, userId, normalizedChain, normalizedAsset);
      const available = subtractDecimal(balance.available, normalizedAmount);
      const locked = addDecimal(balance.locked, normalizedAmount);
      await this.#writeBalance(database, userId, normalizedChain, normalizedAsset, available, locked);
      const timestamp = now();
      await run(database, `
        INSERT INTO withdrawals(
          id, user_id, custody_address_id, external_reference, idempotency_key,
          chain, network, asset, to_address, amount, status, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'REQUESTING', ?, ?)
      `, [id, userId, address.id, externalReference, idempotencyKey,
        normalizedChain, address.network ?? null, normalizedAsset, destination,
        normalizedAmount, timestamp, timestamp]);
    });
    return this.withdrawal(id);
  }

  async acceptWithdrawal(id, remote) {
    await this.#transaction(async database => {
      const current = await this.#withdrawal(database, id);
      const nextFee = normalizeDecimal(remote.fee ?? current.fee ?? "0");
      if (compareDecimal(nextFee, current.fee) > 0) {
        await this.#reserveAdditionalWithdrawalFee(
          database, current, subtractDecimal(nextFee, current.fee));
      } else if (compareDecimal(nextFee, current.fee) < 0) {
        await this.#releaseAdditionalWithdrawalFee(
          database, current, subtractDecimal(current.fee, nextFee));
      }
      await run(database, `
        UPDATE withdrawals SET custody_withdrawal_id = coalesce(custody_withdrawal_id, ?),
          order_no = coalesce(order_no, ?),
          fee = ?, status = CASE WHEN status = 'REQUESTING' THEN ? ELSE status END,
          tx_hash = coalesce(tx_hash, ?), error_message = coalesce(error_message, ?), updated_at = ?
        WHERE id = ?
      `, [remote.id ?? null, remote.orderNo ?? null, nextFee, remote.status ?? "CREATED",
        remote.txHash ?? null, remote.errorMessage ?? null, now(), id]);
    });
    return this.withdrawal(id);
  }

  async releaseWithdrawal(id, message) {
    await this.#transaction(async database => {
      const withdrawal = await this.#withdrawal(database, id);
      const reservedAmount = addDecimal(withdrawal.amount, withdrawal.fee);
      if (!withdrawal.balanceFinalized) {
        await this.#lockBalance(database, withdrawal.userId, withdrawal.chain, withdrawal.asset);
        const balance = await this.#balance(database, withdrawal.userId, withdrawal.chain, withdrawal.asset);
        await this.#writeBalance(
          database, withdrawal.userId, withdrawal.chain, withdrawal.asset,
          addDecimal(balance.available, reservedAmount), subtractDecimal(balance.locked, reservedAmount)
        );
      }
      await run(database, `
        UPDATE withdrawals SET status = 'REQUEST_FAILED', error_message = ?,
          balance_finalized = 1, updated_at = ? WHERE id = ?
      `, [String(message).slice(0, 500), now(), id]);
      await run(database, `
        INSERT OR IGNORE INTO ledger_entries(
          id, event_id, user_id, chain, asset, entry_type, direction,
          amount, reference_id, raw_json, created_at
        ) VALUES (?, ?, ?, ?, ?, 'WITHDRAWAL_RELEASE', 'CREDIT', ?, ?, ?, ?)
      `, [randomUUID(), `request-failed-${id}`, withdrawal.userId, withdrawal.chain,
        withdrawal.asset, reservedAmount, withdrawal.externalReference,
        JSON.stringify({ reason: String(message).slice(0, 500) }), now()]);
    });
  }

  async #withdrawal(queryable, id) {
    const row = await get(queryable, `
      SELECT id, custody_withdrawal_id AS custodyWithdrawalId, user_id AS userId,
             custody_address_id AS custodyAddressId, external_reference AS externalReference,
             idempotency_key AS idempotencyKey, chain, network, asset,
             to_address AS toAddress, amount, order_no AS orderNo, fee, status,
             tx_hash AS txHash, error_message AS errorMessage,
             balance_finalized AS balanceFinalized, created_at AS createdAt, updated_at AS updatedAt
      FROM withdrawals WHERE id = ?
    `, [id]);
    if (!row) throw new Error("withdrawal not found");
    row.balanceFinalized = Boolean(row.balanceFinalized);
    return row;
  }

  /** 为已创建的提现补充锁定用户资产侧的手续费预留。 */
  async #reserveAdditionalWithdrawalFee(database, withdrawal, additionalFee) {
    await this.#lockBalance(database, withdrawal.userId, withdrawal.chain, withdrawal.asset);
    const balance = await this.#balance(database, withdrawal.userId, withdrawal.chain, withdrawal.asset);
    await this.#writeBalance(
      database,
      withdrawal.userId,
      withdrawal.chain,
      withdrawal.asset,
      subtractDecimal(balance.available, additionalFee),
      addDecimal(balance.locked, additionalFee)
    );
  }

  /** 释放提现手续费预留，并保持租户 demo 的余额与钱包服务一致。 */
  async #releaseAdditionalWithdrawalFee(database, withdrawal, releasedFee) {
    await this.#lockBalance(database, withdrawal.userId, withdrawal.chain, withdrawal.asset);
    const balance = await this.#balance(database, withdrawal.userId, withdrawal.chain, withdrawal.asset);
    await this.#writeBalance(
      database,
      withdrawal.userId,
      withdrawal.chain,
      withdrawal.asset,
      addDecimal(balance.available, releasedFee),
      subtractDecimal(balance.locked, releasedFee)
    );
  }

  /** 在事务中查询指定用户指定链的最新有效地址。 */
  async #latestAddress(queryable, userId, chain) {
    const row = await get(queryable, `
      SELECT id FROM addresses
      WHERE user_id = ? AND chain = ? AND status = 'ACTIVE'
      ORDER BY address_version DESC, created_at DESC
      LIMIT 1
    `, [userId, chain]);
    return row ? this.#address(queryable, row.id) : null;
  }

  async withdrawal(id) {
    return this.#withdrawal(this.db, id);
  }

  async withdrawals(userId = null) {
    const predicate = userId ? "WHERE w.user_id = ?" : "";
    return all(this.db, `
      SELECT w.id, w.custody_withdrawal_id AS custodyWithdrawalId,
             w.user_id AS userId, u.email, u.external_id AS externalId,
             w.custody_address_id AS custodyAddressId, w.external_reference AS externalReference,
             w.chain, w.network, w.asset, w.to_address AS toAddress, w.amount,
             w.order_no AS orderNo, w.fee, w.status,
             w.tx_hash AS txHash, w.error_message AS errorMessage,
             w.created_at AS createdAt, w.updated_at AS updatedAt
      FROM withdrawals w JOIN users u ON u.id = w.user_id
      ${predicate} ORDER BY w.created_at DESC
    `, userId ? [userId] : []);
  }

  async receiveWebhook(event, rawPayload) {
    const eventId = String(event?.id ?? "");
    const eventType = String(event?.type ?? "");
    if (!eventId || !eventType || !event?.data) throw new Error("invalid webhook envelope");
    try {
      return await this.#transaction(async database => {
        await run(database, `
          INSERT OR IGNORE INTO webhook_events(
            event_id, event_type, signature_valid, payload, received_at
          ) VALUES (?, ?, 1, ?, ?)
        `, [eventId, eventType, rawPayload, now()]);
        const locked = await get(database,
          "SELECT processed FROM webhook_events WHERE event_id = ?", [eventId]);
        if (locked?.processed) return { duplicate: true };
        if (eventType === "DEPOSIT.CONFIRMED") {
          await this.#applyDeposit(database, eventId, event.data, rawPayload);
        } else if (eventType.startsWith("WITHDRAWAL.")) {
          await this.#applyWithdrawal(database, eventId, eventType, event.data, rawPayload);
        }
        await run(database, `
          UPDATE webhook_events SET processed = 1, processed_at = ?, error_message = NULL
          WHERE event_id = ?
        `, [now(), eventId]);
        return { duplicate: false };
      });
    } catch (error) {
      await run(this.db, `
        INSERT OR IGNORE INTO webhook_events(
          event_id, event_type, signature_valid, payload, received_at, error_message
        ) VALUES (?, ?, 1, ?, ?, ?)
      `, [eventId, eventType, rawPayload, now(), String(error.message).slice(0, 500)]).catch(() => {});
      await run(this.db, "UPDATE webhook_events SET error_message = ? WHERE event_id = ?",
        [String(error.message).slice(0, 500), eventId]).catch(() => {});
      throw error;
    }
  }

  async webhookEvents() {
    return all(this.db, `
      SELECT event_id AS eventId, event_type AS eventType,
             signature_valid AS signatureValid, processed,
             error_message AS errorMessage, received_at AS receivedAt,
             processed_at AS processedAt
      FROM webhook_events ORDER BY received_at DESC
    `);
  }

  async #applyDeposit(database, eventId, data, rawPayload) {
    const user = await this.#userBySubject(database, String(data.subject ?? ""));
    if (!user) throw new Error(`unknown deposit subject: ${data.subject}`);
    const amount = requirePositiveDecimal(data.amount);
    const chain = String(data.chain ?? "").toUpperCase();
    const asset = String(data.asset ?? "").toUpperCase();
    if (!chain || !asset) throw new Error("deposit chain and asset are required");
    if (data.address) {
      const address = await get(database, `
        SELECT id FROM addresses
        WHERE user_id = ? AND chain = ? AND address = ? AND status = 'ACTIVE'
      `, [user.id, chain, String(data.address).trim()]);
      if (!address) throw new Error("deposit address does not belong to user");
    }
    const referenceId = `${chain}:${data.txHash ?? ""}:${data.logIndex ?? 0}`;
    const existing = await get(database, `
      SELECT id FROM ledger_entries
      WHERE user_id = ? AND entry_type = 'DEPOSIT' AND reference_id = ? AND direction = 'CREDIT'
    `, [user.id, referenceId]);
    if (existing) return;
    await this.#lockBalance(database, user.id, chain, asset);
    const balance = await this.#balance(database, user.id, chain, asset);
    await this.#writeBalance(
      database, user.id, chain, asset, addDecimal(balance.available, amount), balance.locked
    );
    await run(database, `
      INSERT INTO ledger_entries(
        id, event_id, user_id, chain, asset, entry_type, direction,
        amount, reference_id, raw_json, created_at
      ) VALUES (?, ?, ?, ?, ?, 'DEPOSIT', 'CREDIT', ?, ?, ?, ?)
    `, [randomUUID(), eventId, user.id, chain, asset, amount, referenceId, rawPayload, now()]);
  }

  async #applyWithdrawal(database, eventId, eventType, data, rawPayload) {
    const result = await get(database, `
      SELECT id FROM withdrawals WHERE custody_withdrawal_id = ? OR external_reference = ? LIMIT 1
    `, [data.withdrawalId ?? "", data.externalReference ?? ""]);
    if (!result) throw new Error("webhook withdrawal is unknown to demo exchange");
    const current = await this.#withdrawal(database, result.id);
    if (current.balanceFinalized) return;
    if (data.chain && String(data.chain).toUpperCase() !== current.chain) {
      throw new Error("withdrawal callback chain does not match request");
    }
    if (data.asset && String(data.asset).toUpperCase() !== current.asset) {
      throw new Error("withdrawal callback asset does not match request");
    }
    if (data.amount !== undefined && normalizeDecimal(data.amount) !== current.amount) {
      throw new Error("withdrawal callback amount does not match request");
    }
    const status = String(data.status ?? eventType.split(".").at(-1));
    const terminalConfirmed = eventType === "WITHDRAWAL.CONFIRMED";
    const terminalFailed = eventType === "WITHDRAWAL.FAILED";
    if ((terminalConfirmed || terminalFailed) && !current.balanceFinalized) {
      await this.#lockBalance(database, current.userId, current.chain, current.asset);
      const balance = await this.#balance(database, current.userId, current.chain, current.asset);
      const reservedAmount = addDecimal(current.amount, current.fee);
      const available = terminalFailed
        ? addDecimal(balance.available, reservedAmount)
        : balance.available;
      await this.#writeBalance(
        database, current.userId, current.chain, current.asset,
        available, subtractDecimal(balance.locked, reservedAmount)
      );
      await run(database, `
        INSERT INTO ledger_entries(
          id, event_id, user_id, chain, asset, entry_type, direction,
          amount, reference_id, raw_json, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      `, [randomUUID(), eventId, current.userId, current.chain, current.asset,
        terminalConfirmed ? "WITHDRAWAL" : "WITHDRAWAL_RELEASE",
        terminalConfirmed ? "DEBIT" : "CREDIT", reservedAmount,
        current.externalReference, rawPayload, now()]);
    }
    await run(database, `
      UPDATE withdrawals SET custody_withdrawal_id = coalesce(custody_withdrawal_id, ?),
        fee = ?, status = ?, tx_hash = coalesce(?, tx_hash), error_message = ?,
        balance_finalized = CASE WHEN ? THEN 1 ELSE balance_finalized END, updated_at = ?
      WHERE id = ?
    `, [data.withdrawalId ?? null, normalizeDecimal(data.fee ?? current.fee), status,
      data.txHash ?? null, data.errorMessage ?? null,
      terminalConfirmed || terminalFailed ? 1 : 0, now(), current.id]);
  }

  async #lockBalance() {
    // SQLite 的 BEGIN IMMEDIATE 已串行化写事务，不需要额外的 advisory lock。
  }

  async #balance(queryable, userId, chain, asset) {
    return (await get(queryable, `
      SELECT available, locked FROM balances WHERE user_id = ? AND chain = ? AND asset = ?
    `, [userId, chain, asset])) ?? { available: "0", locked: "0" };
  }

  async #writeBalance(queryable, userId, chain, asset, available, locked) {
    await run(queryable, `
      INSERT INTO balances(user_id, chain, asset, available, locked, updated_at)
      VALUES (?, ?, ?, ?, ?, ?)
      ON CONFLICT(user_id, chain, asset) DO UPDATE SET
        available = excluded.available, locked = excluded.locked, updated_at = excluded.updated_at
    `, [userId, chain, asset, normalizeDecimal(available), normalizeDecimal(locked), now()]);
  }
}
