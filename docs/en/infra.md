# Infrastructure

[中文版本](../zh/infra.md)

The `infra/` directory contains runtime support files. It stays at the repository root because regtest scripts and external tooling reference it by path.

## Layout

```text
infra/
  regtest/
    bitcoin/
    litecoin/
    dogecoin/
    bitcoincash/
```

## UTXO Regtest Images

`infra/regtest/*` contains Dockerfiles and entrypoints for local BTC/LTC/DOGE/BCH regtest nodes.

These are used by:

```bash
scripts/regtest/all-chain-regtest.sh init
scripts/regtest/all-chain-regtest.sh test-utxo
```

Do not move these files into `docs/`; the regtest scripts resolve them from the repository root.

## Operational Rule

`infra/` is not documentation content. It is runtime/test infrastructure. Documentation about it belongs in `docs/`, while the executable infrastructure files remain at the root.
