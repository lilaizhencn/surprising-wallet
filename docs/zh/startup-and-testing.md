# 启动与测试教程

[English version](../en/startup-and-testing.md)

本文说明如何配置项目、启动本地服务，以及如何运行各类测试环境。

## 1. 环境依赖

需要安装：

- JDK 17+
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

## 5. 密钥配置

Bitcoin-like 链、EVM 和 TRON 使用 BIP32/secp256k1 根密钥。

```bash
export ATOMEX_SIG1_MASTER_KEY='<第一签 BIP32 tprv>'
export ATOMEX_SIG2_MASTER_KEY='<第二签 BIP32 tprv>'
```

wallet-server 需要配置三组公钥：

```yaml
atomex:
  wallet:
    pubKey1: <第一签 xpub/tpub>
    pubKey2: <第二签 xpub/tpub>
    pubKey3: <离线恢复签 xpub/tpub>
```

SOL/TON/APTOS/SUI 使用一个 Ed25519 master seed：

```bash
export ATOMEX_MASTER_SEED='<32 字节 hex 或 base64 seed>'
```

本地测试配置中有 Ed25519 fallback seed。生产环境必须使用环境变量或密钥系统注入。

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
- 要测试的链 RPC URL
- BIP32 公钥和签名服务私钥
- Ed25519 链使用的 `ATOMEX_MASTER_SEED`

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
