/** SQLite access is confined to the owning Durable Object. */
export class DurableDatabase {
  constructor(storage) {
    this.storage = storage;
  }

  async run(sql, parameters = []) {
    this.storage.sql.exec(sql, ...parameters).toArray();
    const row = this.storage.sql.exec("SELECT changes() AS changes, last_insert_rowid() AS lastID").one();
    return row;
  }

  async get(sql, parameters = []) {
    return this.storage.sql.exec(sql, ...parameters).toArray()[0];
  }

  async all(sql, parameters = []) {
    return this.storage.sql.exec(sql, ...parameters).toArray();
  }

  async exec(sql) {
    this.storage.sql.exec(sql).toArray();
  }

  transaction(work) {
    return this.storage.transaction(() => work(this));
  }
}
