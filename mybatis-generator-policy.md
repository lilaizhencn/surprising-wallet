# MyBatis Generator Policy

MBG is restricted to persistence artifacts only.

Allowed generated output:

- Entity/POJO classes.
- Mapper interfaces.
- Mapper XML files.

Forbidden generated output:

- Service classes.
- Wallet business logic.
- Chain adapter logic.
- Scanner jobs.
- Fee calculation.
- Signing or transaction-building logic.

Workflow for new tables:

1. Add or update the SQL migration/init script first.
2. Run MBG only for entity, mapper, and XML artifacts.
3. Keep hand-written logic under service, adapter, domain, or job packages.
4. Review mapper XML against the SQL table definition before commit.

Current multi-chain additions are defined in `multi-chain-wallet-schema.sql`.
