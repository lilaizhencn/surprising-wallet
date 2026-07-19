# Surprising Wallet

面向交易所、支付、电商等业务的多租户区块链托管基础设施。一套钱包服务可以同时服务多个租户。

[English](README.md) · [多租户托管模型](docs/zh/multi-tenant-custody.md) ·
[API 契约](docs/openapi/custody-v1.yaml) · [文档索引](docs/README_CN.md)

## 产品边界

Surprising Wallet 负责：

- 租户隔离的确定性充值地址；
- 扫链、确认后充值入账、链上余额、提现和对账；
- API 请求签名、防重放、IP 白名单、Console 会话和审计日志；
- 带签名、持久化投递记录和重试能力的充提 Webhook。

租户自己管理客户、商户、订单和内部余额规则。分配地址时，租户只需传入不透明的
`externalReference`；钱包基础设施不会创建或建模租户内部用户。

```text
租户凭证 + chain + externalReference -> 稳定的唯一充值地址
链上充值 -> tenant + externalReference -> 签名 Webhook
```

Console 手动创建地址时可以不传 `externalReference`，每次都会分配新地址。创建地址
不发送 Webhook；只发送充值和提现生命周期事件。

## 仓库

- 后端：当前仓库
- React + Ant Design Console：[surprising-wallet-web](https://github.com/lilaizhencn/surprising-wallet-web)

## 核心模块

| 模块 | 职责 |
|---|---|
| `wallet-server` | Custody/Console API、任务、启动校验、Webhook 投递 |
| `wallet-service` | 链适配、扫链、账本、提现、归集 |
| `wallet-sig1`、`wallet-sig2` | 隔离签名服务 |
| `currency-sdks/*` | Bitcoin-like、TRON 和通用链/密钥能力 |

运行模型覆盖 Bitcoin-like、EVM、TRON、Solana、TON、Aptos、Sui、XRP、
Cardano、Polkadot、NEAR、Monero、HyperEVM 和 HyperCore。实际启用的网络和资产
由数据库控制。项目现有测试网资产、测试币和测试 seed 均原样保留。

## 本地启动

依赖 JDK 21、Maven、PostgreSQL 和 Redis。

```bash
psql -U wallet -d wallet -f docs/db/surprising-wallet-init-pgsql.sql
mvn -pl backendservices/wallet-parent/wallet-server -am package
java -jar backendservices/wallet-parent/wallet-server/target/wallet-server-1.0.0-SNAPSHOT.jar
```

Custody 必需密钥：

```text
SW_CUSTODY_SECRET_MASTER_KEY   32 字节 Base64 或 64 位十六进制密钥
SW_CUSTODY_PLATFORM_ADMIN_EMAIL
SW_CUSTODY_PLATFORM_ADMIN_PASSWORD
```

数据库、Redis、HTTP、CORS、链密钥和生产启动要求见
[启动与测试](docs/zh/startup-and-testing.md)。不要在已有生产库执行全新数据库初始化 SQL。

## 验证

```bash
mvn -pl backendservices/wallet-parent/wallet-server -am test
```

真实链测试需要有余额的测试地址，并受外部 RPC/Faucet 可用性影响，因此默认不运行。
参见[脚本与 Regtest](docs/zh/scripts-and-regtest.md)。
