# 启动与测试教程

[English version](../en/startup-and-testing.md)

本文说明如何配置项目、启动本地服务，以及如何运行各类测试环境。

## 1. 环境依赖

需要安装：

- JDK 21
- Maven 3.8+
- PostgreSQL 14+
- Redis 6+
- Docker，用于 BTC/LTC/DOGE/BCH 本地 regtest 节点
- Node.js 18+ 和 npm，用于 EVM fork 测试

检查版本：

```bash
java -version
mvn -version
psql --version
redis-server --version
docker --version
node --version
npm --version
```

## 2. 数据库初始化

创建数据库和用户：

```bash
psql -U postgres -c "create user wallet with password 'wallet123';"
psql -U postgres -c "create database wallet owner wallet;"
psql -U postgres -d wallet -c "grant all on schema public to wallet;"
```

用唯一初始化文件初始化全新本地测试库：

```bash
psql -U wallet -d wallet -f docs/db/surprising-wallet-init-pgsql.sql
```

注意：

- `docs/db/surprising-wallet-init-pgsql.sql` 是唯一 DB 初始化文件，从当前本地 DB schema 导出，并包含静态链/token 配置种子数据。
- 它只用于全新本地库，包含重置表的行为。
- 不要在生产或共享环境执行 destructive SQL。

## 3. Redis

本地启动 Redis：

```bash
redis-server
```

默认本地配置使用 `127.0.0.1:6379`。

## 4. 构建

在仓库根目录执行：

```bash
mvn clean install -DskipTests
```

快速编译检查：

```bash
mvn -pl backendservices/wallet-parent/wallet-service -am test -DskipTests
```

## 5. 密钥与运行配置

Bitcoin-like 链、EVM 和 TRON 使用 BIP32/secp256k1 根密钥。

```bash
export SW_SIG1_MASTER_KEY='<第一签 BIP32 tprv>'
export SW_SIG2_MASTER_KEY='<第二签 BIP32 tprv>'
```

wallet-server 的三组 public key 从 `wallet_public_key` 表读取。初始化 SQL 已写入当前测试 public key；生产环境必须把 1/2/3 三个 slot 替换为生产 xpub，并保持 `enabled=true`。启动时缺任何一个 slot 都会失败。

SOL/TON/APTOS/SUI 使用一个 Ed25519 master seed：

```bash
export SW_ED25519_SEED='<32 字节 hex 或 base64 seed>'
```

`application-test.yaml` 中有 Ed25519 fallback seed。生产环境必须使用环境变量或密钥系统注入。

wallet-server 常用环境变量：

```bash
export SW_DB_PASSWORD='<PostgreSQL 密码>'
export SW_ED25519_SEED='<32 字节 Ed25519 seed，hex 或 base64>'
export SW_WALLET_ADMIN_USERNAME='<钱包后台配置账号>'
export SW_WALLET_ADMIN_PASSWORD='<钱包后台配置密码>'
```

签名服务常用环境变量：

```bash
export SW_SIG1_MASTER_KEY='<第一签 BIP32 tprv>'
export SW_SIG2_MASTER_KEY='<第二签 BIP32 tprv>'
```

链运行配置不再通过 YAML/env 配置：

| 配置 | 数据库来源 |
|---|---|
| 全局扫描/提现/归集/划转开关 | `wallet_system_config` |
| 单链扫描/提现/归集/划转开关 | `chain_profile.scan_enabled/withdraw_enabled/collection_enabled/transfer_enabled` |
| 单链扫描起始高度与单轮扫描上限 | `chain_profile.scan_start_height/scan_max_blocks_per_run` |
| 单链扫描批量 | `chain_profile.scan_batch_size` |
| 链网络、确认数、链 ID、gas policy | `chain_profile` |
| RPC/fullnode/indexer/faucet 节点 | `chain_rpc_node` |
| wallet-server 三个 public key | `wallet_public_key` |
| 每链默认热提钱包 | `chain_address` 中原生资产 `user_id=0/biz=0/address_index=0/wallet_role=DEPOSIT` |

当前运行时代码已接入 `global.all.enabled`、扫描、提现和归集开关。`transfer_enabled` 保留给后续内部划转入口；新增划转入口时必须调用 `WalletRuntimeConfigService.requireTaskEnabled(chain, TASK_TRANSFER, ...)`，不要把该开关套用到创建地址或提现流程。

TokDou 钱包页面读取 wallet-server：

| 场景 | API Base |
|---|---|
| `npm run dev` | `http://localhost:8002` |
| build/部署 | `https://api.tokdou.com` |
| 临时覆盖 | `VITE_WALLET_API_BASE=https://... npm run dev` |

## 6. 应用配置

主要配置文件：

| 文件 | 用途 |
|---|---|
| `backendservices/wallet-parent/wallet-server/src/main/resources/application.yaml` | 本地 wallet-server 配置 |
| `backendservices/wallet-parent/wallet-server/src/main/resources/application-test.yaml` | 测试 profile 配置 |
| `backendservices/wallet-parent/wallet-server/src/main/resources/application-prod.yaml` | 生产占位配置 |
| `backendservices/wallet-sig1/src/main/resources/application.yaml` | 第一签服务配置 |
| `backendservices/wallet-sig2/src/main/resources/application.yaml` | 第二签服务配置 |

本地必配项：

- PostgreSQL URL、用户名、密码
- Redis host/port
- `chain_profile` 中每条启用链只能启用一个 network
- 启用链至少有一个匹配当前 `sw.app.env.name` 的 `chain_rpc_node`
- `wallet_public_key` 中 slot 1/2/3 必须启用
- 每条启用链必须且只能有一条默认热提钱包地址：`chain_address` 原生资产、`user_id=0`、`biz=0`、`address_index=0`、`wallet_role=DEPOSIT`
- 签名服务私钥
- Ed25519 链使用的 `SW_ED25519_SEED`
- 钱包后台配置页使用的 `SW_WALLET_ADMIN_USERNAME`、`SW_WALLET_ADMIN_PASSWORD`

启动校验会打印每条链的网络、任务开关、扫描起点、扫描批量和 RPC 节点数量。wallet-server 会按 `wallet_public_key` 或 `SW_ED25519_SEED` 推导每条启用链的 `0/0/0` 默认热提地址，并和 `chain_address` 比对；缺失、重复或地址/path 不一致会直接启动失败。生产环境如果启用了 testnet/devnet/regtest profile 也会直接失败。

## 7. 启动服务

从仓库根目录开三个终端。

终端 1：

```bash
mvn -pl backendservices/wallet-sig1 -am spring-boot:run
```

终端 2：

```bash
mvn -pl backendservices/wallet-sig2 -am spring-boot:run
```

终端 3：

```bash
mvn -pl backendservices/wallet-parent/wallet-server -am spring-boot:run
```

默认端口：

| 服务 | 端口 |
|---|---:|
| `wallet-server` | 8002 |
| `wallet-sig1` | 8004 |
| `wallet-sig2` | 8081 |

## 8. 测试矩阵

查看支持的测试环境：

```bash
scripts/regtest/all-chain-regtest.sh matrix
```

覆盖范围：

| 命令 | 覆盖 |
|---|---|
| `test-db` | SOL/TON/APTOS/SUI/DOGE 和 UTXO migration 的 DB-only scanner/ledger/flow 测试 |
| `test-utxo` | BTC/LTC/DOGE/BCH 本地 regtest、并发、广播测试 |
| `test-evm` | ETH/BNB/POLYGON/ARBITRUM/OPTIMISM/BASE/AVAX_C 的 EVM fork 测试 |
| `test-live` | 外部 testnet 连通性测试，以及可选花费测试 |
| `test-all` | UTXO、EVM、DB 测试，以及可选 live 测试 |

当前自动化测试的边界：

- `test-utxo` 会在本地 BTC/LTC/DOGE/BCH regtest 中模拟地址创建、充值扫描入账、归集、提现、UTXO 锁定/选择、两次签名、广播和确认。
- `test-evm` 会在 Hardhat fork 中模拟 EVM 原生币/ERC20 充值、提现、归集和确认流程。
- `test-live` 默认验证外部 testnet/devnet 连通性；真实花费广播需要 `RUN_LIVE_SPENDING=true`、测试币余额、签名私钥和 Ed25519 seed。
- 生产级全链端到端演练还需要同时启动 `wallet-sig1`、`wallet-sig2`、`wallet-server`，并由前端或 API 触发真实业务请求。

## 9. 运行 DB-only 测试

```bash
scripts/regtest/all-chain-regtest.sh test-db
```

这些测试不需要本地区块链节点，但需要本地 PostgreSQL。

## 10. 运行本地 UTXO Regtest

启动本地节点：

```bash
scripts/regtest/all-chain-regtest.sh init
scripts/regtest/all-chain-regtest.sh status
```

运行完整本地 UTXO 流程：

```bash
scripts/regtest/all-chain-regtest.sh test-utxo
```

调整广播压力：

```bash
BITCOINLIKE_BROADCAST_DEPOSITS=80 \
BITCOINLIKE_BROADCAST_WITHDRAWALS=40 \
scripts/regtest/all-chain-regtest.sh test-utxo
```

停止或重置节点：

```bash
scripts/regtest/all-chain-regtest.sh stop
scripts/regtest/all-chain-regtest.sh reset
```

## 11. 运行 EVM Fork 测试

安装依赖：

```bash
cd evm-fork
npm install
cd ..
```

运行：

```bash
scripts/regtest/all-chain-regtest.sh test-evm
```

只跑部分链：

```bash
CHAIN_FILTER=ETH,BASE scripts/regtest/all-chain-regtest.sh test-evm
```

## 12. 运行外部 Live 测试

只跑连通性：

```bash
RUN_LIVE=true scripts/regtest/all-chain-regtest.sh test-all
```

连通性加花费测试：

```bash
RUN_LIVE=true RUN_LIVE_SPENDING=true scripts/regtest/all-chain-regtest.sh test-all
```

live 花费测试需要测试钱包有余额，并且 RPC/faucet 可用：

- TRON Nile 需要测试 TRX/TRC20 余额。
- SOL devnet 如果 `requestAirdrop` 限流，需要手动充值。
- TON full flow 要求派生 owner 地址至少有 1 testnet TON。
- Aptos devnet 可能需要不限流或低限流 RPC。
- Sui testnet faucet 有限流，token 测试还需要 `SUI_MOCK_COIN_TYPE`。

## 13. 常见问题

PostgreSQL 权限错误：

```bash
psql -U postgres -d wallet -c "grant all on schema public to wallet;"
```

指定测试不存在导致失败：

```bash
mvn ... -Dsurefire.failIfNoSpecifiedTests=false
```

EVM fork 端口被占用：

```bash
lsof -ti tcp:8545 | xargs kill
```

外部 RPC 限流：

- 通过对应环境变量切换私有 RPC。
- 等待 faucet/RPC 冷却后重试。
- CI 优先使用 DB-only 测试。
