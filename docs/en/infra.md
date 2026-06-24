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
  aptos/mock-coin/
  sui/mock-coin/
```

## UTXO Regtest Images

`infra/regtest/*` contains Dockerfiles and entrypoints for local BTC/LTC/DOGE/BCH regtest nodes.

These are used by:

```bash
scripts/regtest/all-chain-regtest.sh init
scripts/regtest/all-chain-regtest.sh test-utxo
```

Do not move these files into `docs/`; the regtest scripts resolve them from the repository root.

## Aptos Mock Coin

`infra/aptos/mock-coin/` contains a Move package for Aptos token live testing.

The Java live token test expects a coin type in this shape:

```text
<publisher-address>::mock_coin::MockCoin
```

The test can use:

```bash
export APTOS_TEST_COIN_TYPE='<publisher-address>::mock_coin::MockCoin'
```

Publishing and funding are external testnet operations. Public devnet RPC and faucet endpoints may rate-limit.

## Sui Mock Coin

`infra/sui/mock-coin/` contains a Sui Move package for Sui token live testing.

The Java live token test requires:

```bash
export SUI_MOCK_COIN_TYPE='<package-id>::mock_coin::MOCK_COIN'
```

The owner address used by the test must already hold the mock coin and enough SUI for gas.

## Operational Rule

`infra/` is not documentation content. It is runtime/test infrastructure. Documentation about it belongs in `docs/`, while the executable infrastructure files remain at the root.

