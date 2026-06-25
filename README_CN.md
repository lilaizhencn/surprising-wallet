# Surprising Wallet

交易所级多链钱包后端。当前运行时以 DB Asset Model 为中心：

- `chain_profile` 负责链路由与网络元数据
- `chain_asset` 负责链原生资产
- `token_config` 负责 token 运行时配置
- `ledger_balance` 负责用户与系统余额

中文文档位于 [`docs/zh`](docs/zh)，英文文档位于 [`docs/en`](docs/en)。

[English README](README.md)

## 当前范围

支持的链族：

| 链族 | 链 | 运行模型 |
|---|---|---|
| Bitcoin-like UTXO | BTC, LTC, DOGE, BCH | UTXO、本地 regtest、2-of-3 签名 |
| EVM | Ethereum, BNB, Polygon, Arbitrum, Optimism, Base, Avalanche | 账户模型、ERC20、Hardhat fork 测试 |
| TRON | TRON Nile/mainnet profile | 账户模型、TRC20 |
| Ed25519 账户链 | SOL, TON, APTOS, SUI | 账户/token 服务、DB 测试、外部 devnet/testnet live 测试 |

## 快速启动

依赖环境：

- JDK 21
- Maven 3.8+
- PostgreSQL 14+
- Redis 6+
- Docker，用于 BTC/LTC/DOGE/BCH 本地 regtest
- Node.js 18+，用于 EVM fork 测试

创建数据库：

```bash
psql -U postgres -c "create user wallet with password 'wallet123';"
psql -U postgres -c "create database wallet owner wallet;"
psql -U postgres -d wallet -c "grant all on schema public to wallet;"
```

初始化全新测试库：

```bash
psql -U wallet -d wallet -f docs/db/surprising-wallet-init-pgsql.sql
```

初始化 SQL 已包含 `chain_profile`、`chain_rpc_node`、`wallet_system_config` 和 `wallet_public_key`。链开关、扫描起点、扫描批量、RPC 节点和 wallet-server 三个 public key 都从数据库读取。

构建：

```bash
mvn clean install -DskipTests
```

从仓库根目录启动服务：

```bash
mvn -pl backendservices/wallet-sig1 -am spring-boot:run
mvn -pl backendservices/wallet-sig2 -am spring-boot:run
mvn -pl backendservices/wallet-parent/wallet-server -am spring-boot:run
```

关键运行密钥：

```bash
export SW_DB_PASSWORD='<PostgreSQL 密码>'
export SW_SIG1_MASTER_KEY='<第一签 BIP32 tprv>'
export SW_SIG2_MASTER_KEY='<第二签 BIP32 tprv>'
export SW_ED25519_SEED='<32 字节 Ed25519 seed，hex 或 base64>'
export SW_WALLET_ADMIN_USERNAME='<钱包后台配置账号>'
export SW_WALLET_ADMIN_PASSWORD='<钱包后台配置密码>'
```

生产环境中第三个 BIP32 私钥根应离线保存，wallet-server 只配置三组公钥。
wallet-server 三组公钥配置在 `wallet_public_key`，不是 YAML/env。

## TokDou 前端接入

`~/Desktop/surprising/tokdou` 的钱包页面已接入本项目接口：

| 场景 | API Base |
|---|---|
| `npm run dev` | `http://localhost:8002` |
| build/部署 | `https://api.tokdou.com` |
| 临时覆盖 | `VITE_WALLET_API_BASE=https://... npm run dev` |

新增后端接口：

| 接口 | 用途 |
|---|---|
| `GET /wallet/v1/dashboard` | 钱包项目总览、总开关、链配置、RPC、token、地址、余额、交易记录、架构/测试/DB 文档 |
| `GET /wallet/v1/dashboard/address-transactions` | 查询地址充值/提现/归集记录 |
| `POST /wallet/v1/admin/login` | 钱包后台配置登录，HTTP Basic Auth |
| `GET /wallet/v1/admin/config` | 查询可管理配置表 |
| `PATCH /wallet/v1/admin/config/{table}/{id}` | 白名单字段更新配置 |

后台配置账号密码来自 `SW_WALLET_ADMIN_USERNAME`、`SW_WALLET_ADMIN_PASSWORD`，生产环境必须覆盖默认值。

## 测试环境

查看测试矩阵：

```bash
scripts/regtest/all-chain-regtest.sh matrix
```

运行账户链 DB-only 测试：

```bash
scripts/regtest/all-chain-regtest.sh test-db
```

运行 BTC/LTC/DOGE/BCH 本地 regtest：

```bash
scripts/regtest/all-chain-regtest.sh init
scripts/regtest/all-chain-regtest.sh test-utxo
```

运行 EVM fork 测试：

```bash
cd evm-fork
npm install
cd ..
scripts/regtest/all-chain-regtest.sh test-evm
```

BNB 与 Polygon 的公共 fork RPC 不再作为脚本默认值。若必须覆盖这两条链，需要配置经过验证、支持当前区块 fork 的私有或 archive-capable RPC，例如：

```bash
BNB_RPC_URL='https://...' POLYGON_RPC_URL='https://...' REQUIRE_ALL_EVM_FORKS=true scripts/regtest/all-chain-regtest.sh test-evm
```

外部 live/devnet 花费测试需要测试钱包有余额，并依赖 RPC/faucet 可用：

```bash
RUN_LIVE=true RUN_LIVE_SPENDING=true scripts/regtest/all-chain-regtest.sh test-all
```

## 文档

| 主题 | 英文 | 中文 |
|---|---|---|
| 文档索引 | [docs/README.md](docs/README.md) | [docs/README_CN.md](docs/README_CN.md) |
| 启动与测试 | [docs/en/startup-and-testing.md](docs/en/startup-and-testing.md) | [docs/zh/startup-and-testing.md](docs/zh/startup-and-testing.md) |
| 数据库 | [docs/en/database.md](docs/en/database.md) | [docs/zh/database.md](docs/zh/database.md) |
| 架构 | [docs/en/architecture.md](docs/en/architecture.md) | [docs/zh/architecture.md](docs/zh/architecture.md) |
| 运行流程 | [docs/en/system-code-flow.md](docs/en/system-code-flow.md) | [docs/zh/system-code-flow.md](docs/zh/system-code-flow.md) |
| Regtest 脚本 | [docs/en/scripts-and-regtest.md](docs/en/scripts-and-regtest.md) | [docs/zh/scripts-and-regtest.md](docs/zh/scripts-and-regtest.md) |
| EVM fork | [docs/en/evm-fork-testing.md](docs/en/evm-fork-testing.md) | [docs/zh/evm-fork-testing.md](docs/zh/evm-fork-testing.md) |
| 基础设施 | [docs/en/infra.md](docs/en/infra.md) | [docs/zh/infra.md](docs/zh/infra.md) |

## 仓库结构

```text
backendservices/                 Java 后端服务
currency-sdks/                   链 SDK 与钱包公共库
docs/                            文档、SQL schema、文档图片
docs/db/                         数据库初始化脚本与历史备份
evm-fork/                        Hardhat fork 运行环境，因脚本/测试引用保留在根目录
infra/                           Docker 与 mock coin 基础设施
scripts/                         Regtest 与迁移脚本，因测试引用保留在根目录
```
