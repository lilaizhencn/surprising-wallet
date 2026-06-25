# 数据库说明

[English version](../en/database.md)

数据库相关文件位于 `docs/db/`。

## 文件

| 文件 | 用途 |
|---|---|
| `docs/db/surprising-wallet-init-pgsql.sql` | 唯一的全新本地初始化快照，从当前 DB Asset Model schema 导出，包含表结构和静态链/token 配置种子数据。 |

## 初始化顺序

全新本地测试库：

```bash
psql -U wallet -d wallet -f docs/db/surprising-wallet-init-pgsql.sql
```

`surprising-wallet-init-pgsql.sql` 只用于可丢弃或全新的数据库。它包含 `pg_dump --clean` 生成的重置语句，不是生产迁移脚本。

## 种子数据范围

初始化文件包含 `chain_profile`、`chain_asset`、`token_config`、`token_registry`、`wallet_system_config`、`wallet_public_key`、`chain_rpc_node` 的静态配置数据。

它不包含地址、余额、scan-height、充值、提现、归集、签名、UTXO、链上交易等运行期数据。

## DB Asset Model

| 表 | 运行时作用 |
|---|---|
| `chain_profile` | 链 key、链族、网络、确认数、扫描/提现/归集/划转开关、扫描起始高度、BIP44 coin type |
| `chain_rpc_node` | 每条链/网络/环境/purpose 的 RPC/fullnode/indexer/faucet 节点、优先级、认证、备注 |
| `wallet_system_config` | 全局扫描/提现/归集/划转总开关 |
| `wallet_public_key` | wallet-server 启动必需的三组 BIP32 public key |
| `chain_asset` | 链原生资产和链内资产定义 |
| `token_config` | token 合约、decimals、启用状态、最小充值/提现、归集策略 |
| `chain_address` | UTXO/账户链地址注册表 |
| `chain_scan_height` | scanner checkpoint |
| `deposit_record` | 归一化充值事件 |
| `ledger_balance` | 按链隔离的账户余额 |
| `utxo_record` | Bitcoin-like UTXO 运行时状态 |

## Ledger 语义

`ledger_balance` 是按链隔离的。Ethereum 上的 USDT 和 TRON 上的 USDT 是不同 ledger 行，因为执行链路和结算语义不同。

如果产品层需要展示全局 USDT 总余额，应在查询/API 层按 symbol 和 account 聚合。不要用硬编码 currency id 合并运行时执行路径。

## 运行时表

运行时状态存储在 `ledger_balance`、`chain_address`、`token_config`、
`chain_scan_height` 以及链专用交易服务/表中。

## 启动校验

wallet-server 启动时会检查：

- `wallet_public_key` 必须启用 slot 1、2、3。
- `chain_profile` 中同一 `chain` 只能有一个启用 network。
- `sw.app.env.name=prod` 时，启用 profile 不允许是 testnet/devnet/regtest。
- 每个启用 profile 至少要有一个当前环境可用的 `chain_rpc_node`。
- 每条链的任务开关、扫描起始高度、扫描批量和 RPC 节点数量会打印到日志；缺失或关闭项会输出 WARN。

## 权限

`wallet` 数据库用户需要 schema 权限：

```bash
psql -U postgres -d wallet -c "grant all on schema public to wallet;"
psql -U postgres -d wallet -c "grant all privileges on all tables in schema public to wallet;"
psql -U postgres -d wallet -c "grant all privileges on all sequences in schema public to wallet;"
```
