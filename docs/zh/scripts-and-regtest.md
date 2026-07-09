# 脚本与 Regtest

[English version](../en/scripts-and-regtest.md)

运行脚本保留在仓库根目录 `scripts/` 下，因为 Java 测试和 shell 脚本直接引用这些路径。

## 主入口

```bash
scripts/regtest/all-chain-regtest.sh <command>
```

命令：

| 命令 | 用途 |
|---|---|
| `matrix` | 展示支持的 local/fork/live 覆盖范围 |
| `init` | 启动 BTC/LTC/DOGE/BCH 本地 regtest 节点和 XMR wallet-rpc regtest |
| `status` | 查看节点和 EVM fork 状态 |
| `stop` | 停止本地 UTXO/XMR regtest 节点 |
| `reset` | 重置本地 UTXO/XMR regtest 节点和 volume |
| `test-utxo` | 运行 BTC/LTC/DOGE/BCH 本地全流程测试 |
| `test-xmr` | 运行 XMR 本地充值、提现、归集 regtest 集成测试 |
| `test-evm` | 运行 EVM fork 回归测试 |
| `test-db` | 运行账户链 DB-only 测试 |
| `test-live` | 运行外部 testnet 连通性和可选花费测试 |
| `test-local` | 运行 UTXO 和 EVM 测试 |
| `test-all` | 运行 local、DB 测试，以及可选 live 测试 |

## 本地 UTXO 和 XMR 节点

UTXO regtest 脚本使用 `infra/regtest/` 下的 Docker 构建材料。XMR 使用
`monerod --regtest`，并启动两个 `monero-wallet-rpc` 容器：一个项目钱包，
一个 funder 钱包。

```bash
scripts/regtest/all-chain-regtest.sh init
scripts/regtest/all-chain-regtest.sh status
scripts/regtest/all-chain-regtest.sh test-utxo
scripts/regtest/all-chain-regtest.sh test-xmr
```

`test-xmr` 需要 Docker、`curl`、`python3`、Maven，以及已经用
`docs/db/surprising-wallet-init-pgsql.sql` 初始化过的 PostgreSQL 数据库。
默认连接 `jdbc:postgresql://127.0.0.1:5432/wallet`、用户 `wallet`、空密码；
可通过 `MONERO_REGTEST_DB_URL`、`MONERO_REGTEST_DB_USER`、
`MONERO_REGTEST_DB_PASSWORD` 覆盖。如果 wallet-rpc 容器启用了认证，设置
`MONERO_REGTEST_RPC_LOGIN=user:password`；Java 测试会把 regtest RPC 节点
upsert 为 `DIGEST`，否则使用 `NONE`。

调整广播压力：

```bash
BITCOINLIKE_BROADCAST_DEPOSITS=80 \
BITCOINLIKE_BROADCAST_WITHDRAWALS=40 \
scripts/regtest/all-chain-regtest.sh test-utxo
```

## EVM Fork

```bash
cd evm-fork
npm install
cd ..
scripts/regtest/all-chain-regtest.sh test-evm
```

只跑部分链：

```bash
CHAIN_FILTER=ETH,BASE scripts/regtest/all-chain-regtest.sh test-evm
```

## 外部 Testnet

```bash
scripts/regtest/all-chain-regtest.sh test-live
```

花费测试默认关闭：

```bash
RUN_LIVE_SPENDING=true scripts/regtest/all-chain-regtest.sh test-live
```

这些流程依赖测试账户余额以及外部 RPC/faucet 可用性。

## 数据库初始化

项目只保留一份数据库初始化文件：

```text
docs/db/surprising-wallet-init-pgsql.sql
```

`scripts/` 目录不再保存独立 SQL 升级或 cutover 脚本。全新本地库、test2/sandbox 库都以这份 init SQL 为准。

