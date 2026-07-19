# Documentation

[中文版本](README_CN.md)

This directory contains project documentation, database schema files, and documentation assets.

## Guide Map

| Topic | English | Chinese |
|---|---|---|
| Multi-tenant custody | [en/multi-tenant-custody.md](en/multi-tenant-custody.md) | [zh/multi-tenant-custody.md](zh/multi-tenant-custody.md) |
| Custody API | [openapi/custody-v1.yaml](openapi/custody-v1.yaml) | [openapi/custody-v1.yaml](openapi/custody-v1.yaml) |
| Startup and testing | [en/startup-and-testing.md](en/startup-and-testing.md) | [zh/startup-and-testing.md](zh/startup-and-testing.md) |
| Database | [en/database.md](en/database.md) | [zh/database.md](zh/database.md) |
| Architecture | [en/architecture.md](en/architecture.md) | [zh/architecture.md](zh/architecture.md) |
| Runtime code flow | [en/system-code-flow.md](en/system-code-flow.md) | [zh/system-code-flow.md](zh/system-code-flow.md) |
| Scripts and regtest | [en/scripts-and-regtest.md](en/scripts-and-regtest.md) | [zh/scripts-and-regtest.md](zh/scripts-and-regtest.md) |
| EVM fork testing | [en/evm-fork-testing.md](en/evm-fork-testing.md) | [zh/evm-fork-testing.md](zh/evm-fork-testing.md) |
| Infrastructure | [en/infra.md](en/infra.md) | [zh/infra.md](zh/infra.md) |

## Directory Layout

```text
docs/
  assets/       Diagrams and documentation images
  db/           SQL schema/init files and historical DB backups
  en/           English guides
  openapi/      Machine-readable custody API contract
  zh/           Chinese guides
```

Operational directories remain at the repository root:

- `scripts/` for regtest and test helper executables
- `infra/` for Docker and mock coin infrastructure
- `evm-fork/` for Hardhat fork runtime
