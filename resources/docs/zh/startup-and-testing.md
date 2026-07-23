# 启动与测试教程


本文说明如何配置项目、启动本地服务，以及如何运行各类测试环境。

## 1. 环境依赖

需要安装：

- JDK 21
- Maven 3.8+
- PostgreSQL 14+
- Redis 6+
- Docker，用于 BTC/LTC/DOGE/BCH 本地 regtest 节点
- Node.js 20.19+，用于 Console 和 EVM fork 工具

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
psql -U wallet -d wallet -f resources/docs/db/surprising-wallet-init-pgsql.sql
```

注意：

- `resources/docs/db/surprising-wallet-init-pgsql.sql` 是唯一 DB 初始化文件，从当前本地 DB schema 导出，并包含静态链/token 配置种子数据。
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
mvn -pl wallet-service -am test -DskipTests
```

## 5. 密钥与运行配置

四个根 Seed 统一保存在单行 `wallet_key_config`：sig1、sig2、recovery 三个 BIP32 Seed，以及一个 Ed25519 Seed。四个值必须互不相同，统一使用 Base64 编码的 32 字节数据，并通过平台超级管理员的 Wallet keys 页面原子保存。当前阶段数据库明文存储，页面允许查看原文。

wallet-api 从 Keyset 派生三组 public root；sig1/sig2 分别使用各自需要的 private root。已有派生地址后，管理接口禁止变更整套 Keyset。

wallet-api 常用环境变量：

```bash
export SW_HTTP_PORT='8002'
export SW_DB_URL='jdbc:postgresql://127.0.0.1:5432/wallet'
export SW_DB_USERNAME='wallet'
export SW_DB_PASSWORD='<PostgreSQL 密码>'
export SW_REDIS_HOST='127.0.0.1'
export SW_REDIS_PORT='6379'
export SW_REDIS_PASSWORD='<Redis 密码>'
export SW_APP_ENV='dev'
export SW_WALLET_ADMIN_USERNAME='<钱包后台配置账号>'
export SW_WALLET_ADMIN_PASSWORD='<钱包后台配置密码>'
export SW_CUSTODY_SECRET_MASTER_KEY='<32 字节 Base64 或 64 位十六进制密钥>'
export SW_CUSTODY_PLATFORM_ADMIN_EMAIL='<初始平台管理员邮箱>'
export SW_CUSTODY_PLATFORM_ADMIN_PASSWORD='<初始平台管理员密码>'
export SW_CUSTODY_CORS_ORIGINS='https://console.example.com'
```

`SW_CUSTODY_SECRET_MASTER_KEY` 为必填项，用于加密保存 API/Webhook Secret。只有数据库中
不存在平台管理员时才会引导创建；以后修改环境变量密码不会覆盖已有账户。

链运行配置不再通过 YAML/env 配置：

| 配置 | 数据库来源 |
|---|---|
| 全局扫描/提现/归集/划转开关 | `wallet_system_config` |
| 单链扫描/提现/归集/划转开关 | `chain_profile.scan_enabled/withdraw_enabled/collection_enabled/transfer_enabled` |
| 单链扫描起始高度与单轮扫描上限 | `chain_profile.scan_start_height/scan_max_blocks_per_run` |
| 单链扫描批量 | `chain_profile.scan_batch_size` |
| 链网络、确认数、链 ID、gas policy | `chain_profile` |
| RPC/fullnode/indexer/faucet 节点 | `chain_rpc_node` |
| 四个钱包根 Seed | `wallet_key_config` |
| 每链默认热提钱包 | `chain_address` 中原生资产 `user_id=0/biz=0/address_index=0/wallet_role=DEPOSIT` |

部署前必须按环境提前确认 scanner checkpoint。全新系统通常把 `chain_scan_height.best_height/safe_height` 设置到当前最新安全块附近，让服务只扫描部署后的新区块；如果要补历史充值，再按业务窗口把高度往前调。不要从创世块或很早的历史高度开始扫，这会让服务长时间追块并消耗大量 RPC 配额。

当前运行时代码已接入 `global.all.enabled`、扫描、提现和归集开关。`transfer_enabled` 保留给后续内部划转入口；新增划转入口时必须调用 `WalletRuntimeConfigService.requireTaskEnabled(chain, TASK_TRANSFER, ...)`，不要把该开关套用到创建地址或提现流程。

TokDou 钱包页面读取 wallet-api：

| 场景 | API Base |
|---|---|
| `npm run dev` | `http://localhost:8002` |
| build/部署 | `https://api.tokdou.com` |
| 临时覆盖 | `VITE_WALLET_API_BASE=https://... npm run dev` |

## 6. 应用配置

主要配置文件：

| 文件 | 用途 |
|---|---|
| `wallet-api/src/main/resources/application.yaml` | 本地 wallet-api 配置 |
| `wallet-api/src/main/resources/application-test.yaml` | 测试 profile 配置 |
| `wallet-api/src/main/resources/application-prod.yaml` | 生产占位配置 |
| `wallet-sig1/src/main/resources/application.yaml` | 第一签服务配置 |
| `wallet-sig2/src/main/resources/application.yaml` | 第二签服务配置 |

本地必配项：

- PostgreSQL URL、用户名、密码
- Redis host/port
- `chain_profile` 中每条启用链只能启用一个 network
- 启用链至少有一个匹配当前 `sw.app.env.name` 的 `chain_rpc_node`
- 启用的 `chain_rpc_node` 必须配置真实 RPC URL 和认证信息；启动时会拒绝 `CHANGE_ME`、`YOUR_*`、`REPLACE_ME` 等占位符
- 平台 Wallet keys 页面已原子配置 `wallet_key_config` 四个 Base64 32 字节 Seed
- 每条启用链必须且只能有一条默认热提钱包地址：`chain_address` 原生资产、`user_id=0`、`biz=0`、`address_index=0`、`wallet_role=DEPOSIT`
- Keyset 配置完成后再启动 sig1 和 sig2
- 钱包后台配置页使用的 `SW_WALLET_ADMIN_USERNAME`、`SW_WALLET_ADMIN_PASSWORD`

启动校验会打印每条链的网络、任务开关、扫描起点、扫描批量和 RPC 节点数量。Keyset 已配置时，wallet-api 会从它推导每条启用链的 `0/0/0` 默认热提地址并和 `chain_address` 比对；缺失、重复或地址/path 不一致会直接启动失败。Keyset 未配置时只开放管理能力，地址派生和签名不可用。启用的 RPC 节点如果仍包含占位符 URL 或认证信息，也会直接启动失败。

## 7. 启动服务

从仓库根目录开三个终端。

终端 1：

```bash
mvn -pl wallet-sig1 -am spring-boot:run
```

终端 2：

```bash
mvn -pl wallet-sig2 -am spring-boot:run
```

终端 3：

```bash
mvn -pl wallet-api -am spring-boot:run
```

默认端口：

| 服务 | 端口 |
|---|---:|
| `wallet-api` | 8002 |
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
| `test-db` | SOL/TON/APTOS/SUI/DOGE 和 UTXO 运行状态的 DB-only scanner/ledger/flow 测试 |
| `test-utxo` | BTC/LTC/DOGE/BCH 本地 regtest、并发、广播测试 |
| `test-xmr` | XMR 本地 wallet-rpc regtest 充值、提现、归集和幂等测试 |
| `test-evm` | 核心 EVM 链的 fork 测试；HyperEVM、Mantle、Linea、Scroll、Unichain 等新增 EVM 链通过 DB profile 复用共享 EVM 路径 |
| `test-live` | 外部 testnet 连通性测试，以及可选花费测试 |
| `test-all` | UTXO、EVM、DB 测试，以及可选 live 测试 |

当前自动化测试的边界：

- `test-utxo` 会在本地 BTC/LTC/DOGE/BCH regtest 中模拟地址创建、充值扫描入账、归集、提现、UTXO 锁定/选择、两次签名、广播和确认。
- `test-xmr` 会启动 XMR regtest wallet-rpc，创建真实子地址，充值并扫描入账，广播项目内提现，验证扫描幂等，并确认归集流程。
- `test-evm` 会在 Hardhat fork 中模拟 EVM 原生币/ERC20 充值、提现、归集和确认流程。
- `test-live` 默认验证外部 testnet/devnet 连通性；真实花费广播需要 `RUN_LIVE_SPENDING=true`、测试币余额、签名私钥和 Ed25519 seed。
- 生产级全链端到端演练还需要同时启动 `wallet-sig1`、`wallet-sig2`、`wallet-api`，并由前端或 API 触发真实业务请求。

## 9. 运行 DB-only 测试

```bash
scripts/regtest/all-chain-regtest.sh test-db
```

这些测试不需要本地区块链节点，但需要本地 PostgreSQL。

使用本机 PostgreSQL 18 中自动创建并清理的临时数据库，验证托管充值投影和事务回滚边界：

```bash
scripts/regtest/run-custody-db-tests.sh
```

脚本不会启动独立数据库实例，只会复用 `127.0.0.1:5432`，且拒绝 PostgreSQL 18
以外的服务。测试会创建并回滚自己的租户和地址数据。它会验证已禁用的托管地址收到
确认充值后，租户充值投影、托管账本和持久化事件仍在同一事务中更新；
任一观察器失败时，原始充值记录和余额也会一起回滚。

## 10. 运行本地 UTXO 和 XMR Regtest

启动本地节点：

```bash
scripts/regtest/all-chain-regtest.sh init
scripts/regtest/all-chain-regtest.sh status
```

运行完整本地 UTXO 流程：

```bash
scripts/regtest/all-chain-regtest.sh test-utxo
```

运行 XMR wallet-rpc 流程：

```bash
scripts/regtest/all-chain-regtest.sh test-xmr
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
- Aptos testnet 使用 Aptos Labs fullnode；testnet 没有程序化 faucet，需要提前给系统 faucet 或热钱包充值测试币。
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

- 通过 `chain_rpc_node.rpc_url` 或 `api_key` 切换私有 RPC。
- 等待 faucet/RPC 冷却后重试。
- CI 优先使用 DB-only 测试。
