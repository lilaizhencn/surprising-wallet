# 基础设施

[English version](../en/infra.md)

`infra/` 目录包含运行和测试支撑文件。该目录保留在仓库根目录，因为 regtest 脚本和外部工具按固定路径引用它。

## 结构

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

## UTXO Regtest 镜像

`infra/regtest/*` 包含本地 BTC/LTC/DOGE/BCH regtest 节点的 Dockerfile 和 entrypoint。

这些文件由以下命令使用：

```bash
scripts/regtest/all-chain-regtest.sh init
scripts/regtest/all-chain-regtest.sh test-utxo
```

不要把这些文件移动到 `docs/`；regtest 脚本会从仓库根目录解析它们。

## Aptos Mock Coin

`infra/aptos/mock-coin/` 包含 Aptos token live 测试使用的 Move package。

Java live token 测试期望 coin type 形如：

```text
<publisher-address>::mock_coin::MockCoin
```

测试可以使用：

```bash
export APTOS_TEST_COIN_TYPE='<publisher-address>::mock_coin::MockCoin'
```

发布和充值属于外部 testnet 操作。公共 devnet RPC 和 faucet 可能限流。

## Sui Mock Coin

`infra/sui/mock-coin/` 包含 Sui token live 测试使用的 Move package。

Java live token 测试需要：

```bash
export SUI_MOCK_COIN_TYPE='<package-id>::mock_coin::MOCK_COIN'
```

测试使用的 owner 地址必须已经持有 mock coin，并且有足够 SUI 支付 gas。

## 操作规则

`infra/` 不是文档内容，而是运行/测试基础设施。它的说明放在 `docs/`，可执行基础设施文件保留在根目录。

