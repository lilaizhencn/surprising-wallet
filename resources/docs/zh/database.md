# 数据库说明


数据库相关文件位于 `../db`。

## 文件

| 文件 | 用途 |
|---|---|
| `../db/surprising-wallet-init-pgsql.sql` | 唯一数据库初始化文件，包含完整 schema 和链/token 静态初始化数据。 |

## 初始化顺序

复用既有 `127.0.0.1:5432` PostgreSQL18 / PGMQ1.11.1，从仓库根目录初始化一个新的隔离测试库：

```bash
createdb -h 127.0.0.1 -p 5432 surprising_wallet_test_manual_init
psql -h 127.0.0.1 -p 5432 -d surprising_wallet_test_manual_init -X -v ON_ERROR_STOP=1 -f resources/docs/db/surprising-wallet-init-pgsql.sql
```

`surprising-wallet-init-pgsql.sql` 只用于可丢弃或全新的数据库。它包含 `pg_dump --clean` 生成的重置语句，不用于生产库原地升级。

## 种子数据范围

初始化文件包含 `chain_profile`、`chain_asset`、`token_config`、`wallet_system_config`、`chain_rpc_node` 的静态配置数据。Java 不加载钱包根 Seed；联合公钥与版本、签名请求和尝试由四张 `wallet_mpc_*` 表保存，私有份额留在独立持有者。MPC 通信配置通过 Spring `sw.wallet.mpc` 加载。

初始化文件允许保留未启用的 RPC 和 token 模板行；脚本末尾会把仍带占位 URL、密钥或 token 合约的 `chain_rpc_node`、`token_config`、`chain_asset` 统一设为禁用/不活跃。填入真实值后，再在对应环境启用节点、token 和资产。

它不包含地址、余额、scan-height、充值、提现、归集、签名、UTXO、链上交易等运行期数据。

## DB Asset Model

| 表 | 运行时作用 |
|---|---|
| `chain_profile` | 链 key、链族、网络、确认数、扫描/提现/归集/划转开关、扫描起始高度、BIP44 coin type |
| `chain_rpc_node` | 每条链/网络/环境/purpose 的 RPC/fullnode/indexer/faucet 节点、优先级、认证、请求间隔、备注 |
| `wallet_system_config` | 全局扫描/提现/归集/划转总开关，以及可选提现后台审核开关 |
| `chain_asset` | 链原生资产和链内资产定义 |
| `token_config` | token 合约、decimals、启用状态、最小充值/提现、归集策略 |
| `chain_address` | 租户的 UTXO/账户链地址注册表，通过 `mpc_key_id` 绑定已发布的联合公钥身份 |
| `wallet_mpc_key` | 租户密钥、公钥、当前活动版本与建钥/刷新状态；不保存私有份额 |
| `wallet_mpc_key_epoch` | 原建钥/刷新描述符、固定参与方、公钥、版本状态及刷新 actor/reason |
| `wallet_mpc_sign_request` | 同租户的固定业务签名意图、密钥版本、原文摘要及已选尝试 |
| `wallet_mpc_sign_attempt` | 不可变节点会话、观察结果、加密签名、显式重放人与原因；保留原尝试历史 |
| `chain_scan_height` | scanner checkpoint |
| `deposit_record` | 归一化充值事件 |
| `ledger_balance` | 按租户、链、资产和账户隔离的余额 |
| `utxo_record` | Bitcoin-like UTXO 运行时状态 |

## Custody 表

| 表 | 作用 |
|---|---|
| `custody_tenant`、`custody_tenant_user`、`custody_session` | 租户和 Console 身份 |
| `custody_api_key`、`custody_api_nonce`、`custody_ip_rule` | 签名 API 认证和网络策略 |
| `custody_address` | 租户到链地址的分配关系；一个链地址只能归属一个租户 |
| `custody_deposit`、`custody_withdrawal` | 租户范围的充提投影 |
| `custody_ledger_entry` | 用于对账的不可变 Custody 事件账本 |
| `custody_event`、`custody_webhook_delivery`、`custody_webhook_endpoint` | 持久化 Webhook 扇出和投递状态 |
| `custody_idempotency_key` | 地址和永久提现请求幂等 |
| `custody_audit_log` | 租户操作和安全审计 |

## Ledger 语义

`ledger_balance` 首先按租户隔离，再区分链、资产与账户。Ethereum 上的 USDT 和 TRON 上的 USDT 是不同 ledger 行，因为执行链路和结算语义不同。

如果产品层需要展示全局 USDT 总余额，应在查询/API 层按 symbol 和 account 聚合。不要用硬编码 currency id 合并运行时执行路径。

## 运行时表

运行时状态存储在 `ledger_balance`、`chain_address`、`token_config`、
`chain_scan_height` 以及链专用交易服务/表中。

## 启动校验

wallet-api 启动时会检查：

- Spring `sw.wallet.mpc` 配置三名持有者身份、HTTPS 端点和 mTLS 文件；缺失或非法时启动失败。钱包地址按租户绑定已发布的 MPC 密钥，恢复保留原公钥、版本和参与方，不从本地 Seed 派生。
- 同一 `chain` 同一时刻只能启用一个 network。
- 非生产环境可以同时保存 devnet 和 testnet 等多套 profile，并按测试场景切换启用；`sw.app.env.name=prod` 时只允许启用生产网络。
- 每个启用 profile 必须有当前环境可用的必需 `chain_rpc_node`；例如 DOT 需要 `rpc` 和 `runtime`，启用 DOT token 时还需要 `asset_rpc`。
- XMR 的 Java 侧必需节点为 `daemon`；三方私有 wallet-rpc 由各自原生持有者配置，不向 Java 暴露私有钱包接口。
- wallet-api 在 MPC 通信配置完成后启动；原生持有者不连接 PostgreSQL 或 PGMQ。
- 启用的 `chain_rpc_node` 不能包含 `CHANGE_ME`、`YOUR_*`、`REPLACE_ME` 等占位符 URL 或认证信息。
- 启用的 `token_config` 和非原生 `chain_asset` 必须配置真实合约地址/asset id，不能为空或包含占位符。
- 启用的 `token_config.network` 如果有值，必须匹配同链任意一个当前启用的 `chain_profile.network`。
- 每个 active 的非原生 `chain_asset` 都必须存在同 `chain`、同 `symbol` 且已启用的 `token_config`，两张表的合约地址/asset id 必须一致。这样可以避免前端已经展示 token，但扫描或提现服务无法解析 token 配置。
- 每条链的任务开关、扫描起始高度、扫描批量和 RPC 节点数量会打印到日志；缺失或关闭项会输出 WARN。

钱包地址由租户地址入口在 DKG 完成后发布，并通过 `chain_address.mpc_key_id` 绑定同租户密钥；数据库约束保留原地址身份。启动不从本地根密钥生成全局热钱包。

## 权限

`wallet` 数据库用户需要 schema 权限：

```bash
psql -U postgres -d wallet -c "grant all on schema public to wallet;"
psql -U postgres -d wallet -c "grant all privileges on all tables in schema public to wallet;"
psql -U postgres -d wallet -c "grant all privileges on all sequences in schema public to wallet;"
```
