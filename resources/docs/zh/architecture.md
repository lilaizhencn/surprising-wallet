# 架构

全模块位于项目根目录，扁平 Maven 多模块布局。

![架构图](../assets/architecture-diagram.svg)

## Custody 控制面

多租户托管层位于现有链引擎之上：

```text
平台 Console -> 租户生命周期
租户 Console/API -> 租户范围的地址、资产、充值、提现
                         |
                         v
                 现有钱包账本和链引擎
                         |
                         v
                 扫链 / 签名 / RPC 服务
```

租户身份始终来自 Console 会话或 API 凭证。公开地址 API 接收 `chainId` 和租户定义的
`subject` 和可选的 `addressVersion`；同一租户、链、subject 和版本重复调用返回同一地址，递增版本可更换地址，相同 subject 和版本的所有 EVM 链地址一致。扫链确认入账后，会在同一数据库事务中映射 Custody 充值、
租户资产和持久化 Webhook 事件。详见[多租户托管钱包](multi-tenant-custody.md)。

## 运行模型

运行时资产来源：

| 表 | 作用 |
|---|---|
| `chain_profile` | 链 key、链族、启用网络、确认策略、扫描/提现/归集/划转开关、扫描起始高度、BIP44 coin type |
| `chain_rpc_node` | 每条链的 RPC/fullnode/indexer/faucet 节点、环境标签、优先级、认证和备注 |
| `wallet_system_config` | 全局扫描/提现/归集/划转总开关 |
| `wallet_key_config` | 原子保存 sig1、sig2、recovery 三个 BIP32 Seed 和一个 Ed25519 Seed 的单行 Keyset |
| `chain_asset` | 原生资产和链内资产定义 |
| `token_config` | token 合约/配置、decimals、归集/提现策略 |
| `ledger_balance` | 按链隔离的用户/系统余额状态 |
| `custody_*` | 租户、凭证、地址分配、充提投影、Webhook、幂等和审计控制面状态 |

应用应通过 `chain + symbol` 或 `chain + contract` 解析资产，然后把 runtime asset 传入 scanner、withdraw、collection 和 signing 流程。

## 模块

| 模块 | 职责 |
|---|---|
| `wallet-api` | Custody/Console REST API、充值扫描任务、提现批处理、归集协调、Gas 对账、Webhook 投递、EIP-7702 归集与提现、启动校验 |
| `wallet-service` | 链适配器（Bitcoin-like/EVM/TRON/Solana/TON/Aptos/Sui/XRP/Cardano/Polkadot/NEAR/Monero/HyperEVM/HyperCore）、扫链充值、账本管理、提现流程、UTXO 归集、Gas 估算 |
| `wallet-sig1` | BTC-like 2-of-3 第一签服务：对 BTC、BCH、LTC、DOGE 提现交易生成部分签名，轮询 Redis 队列 |
| `wallet-sig2` | 第二签服务：对 BTC、BCH、LTC、DOGE、ETH、ERC20、TRON 交易完成最终签名并广播 |
| `common` | 共享基础设施：Redis 封装、链数据模型、钱包密钥管理、Ed25519 密钥派生、Ethereum 密码学工具 |
| `chain-sdks` | Bitcoin-like 链和 TRON 链 SDK：多签地址、SegWit 交易、UTXO 选择、BIP32、gRPC 客户端、Protobuf 合约、ECKey 密码学 |

所有模块的 parent POM 为根目录 `pom.xml`，继承 Spring Boot starter parent 并提供统一的版本和依赖管理。

## 链族

| 链族 | 链 | 本地测试支持 | live/testnet 支持 |
|---|---|---|---|
| Bitcoin-like UTXO | BTC, LTC, DOGE, BCH | Docker regtest 节点 | 外部 RPC 配置 |
| EVM | ETH, BNB, POLYGON | Hardhat fork | Sepolia、BSC testnet、Amoy |
| EVM L2 | ARBITRUM, OPTIMISM, BASE, AVAX_C | Hardhat fork | Sepolia L2、Avalanche Fuji |
| EVM L2 (新增) | MANTLE, LINEA, SCROLL, UNICHAIN, HyperEVM | Hardhat fork | Mantle Sepolia、Linea Sepolia、Scroll Sepolia、Unichain Sepolia、HyperEVM testnet |
| TRON | TRON | DB 测试 | Nile live flow |
| Solana | SOL | DB 测试 | Devnet live flow |
| TON | TON | DB 测试 | Testnet |
| Aptos | APT | DB 测试 | Testnet |
| Sui | SUI | DB 测试 | Testnet |
| XRP | XRP | DB 测试 | Testnet |
| Cardano | ADA | DB 测试 | Preprod testnet |
| Polkadot | DOT | 本地 dev 链 + Node.js Sidecar | Westend + Asset Hub |
| NEAR | NEAR | DB 测试 | Testnet |
| Monero | XMR | Docker regtest wallet-rpc | Testnet |
| HyperCore | HYPE | DB/API 测试 | Hyperliquid testnet API |

HyperEVM 复用 EVM 通用路径。HyperCore 使用独立的账户层适配器，通过 Hyperliquid `/info` 和 `/exchange` API 工作。Polkadot 通过 `resources/infra/polkadot-runtime-service` Node.js 桥接服务与链交互。参见 [HyperEVM 与 HyperCore 接入说明](hyperevm-hypercore.md)。

## 签名模型

单行 `wallet_key_config` 原子保存四个 Base64 编码的 32 字节 Seed。Bitcoin-like 链使用其中三组 BIP32 root：

```text
BIP32 root #1 -> pubKey1，在线第一签私钥 root
BIP32 root #2 -> pubKey2，在线第二签私钥 root
BIP32 root #3 -> pubKey3，离线恢复私钥 root
```

SOL/TON/APTOS/SUI 使用第四个 Ed25519 master seed：

```text
Ed25519 Seed -> SLIP-0010 Ed25519 派生 -> 每条链/每个用户 key
```

生产环境不要把 BIP32 raw seed 复用为 Ed25519 seed。不同生产根密钥材料应隔离。

## 主流程边界

Scanner：

- 读取链状态。
- 匹配 `chain_address` 中注册的地址。
- 向 `deposit_record` 写入归一化充值事件。
- 幂等入账 `ledger_balance`。

Withdraw：

- 从数据库解析资产配置。
- 锁定 ledger balance。
- 构建、签名、广播交易。
- 确认并释放/完成 ledger 状态。

Collection：

- 扫描可归集用户余额。
- 从 `token_config` 读取资产策略。
- 构建到固定默认热提钱包的转账；默认热提钱包为每条链原生资产 `chain_address` 的 `user_id=0/biz=0/address_index=0/wallet_role=DEPOSIT`。
- 确认并幂等更新 ledger 状态。

## 启动配置校验

wallet-api 启动时会检查 `chain_profile`、`chain_rpc_node`、`wallet_key_config`、默认热提钱包和 `wallet_system_config`。Keyset 尚未配置时，wallet-api 保持可启动，以便平台超级管理员完成首次配置；依赖密钥的运行路径不可用。Keyset 已配置时，默认热提钱包会通过代码推导后与 `chain_address` 比对，缺失或不一致会启动失败。同一链同一时刻只能启用一个网络；非生产环境可以同时保存 devnet/testnet profile 并切换启用，生产环境只允许启用生产网络。启用 profile 必须至少有一个匹配当前环境的 RPC 节点。校验结果会按链打印状态，缺失配置或关闭开关会输出 WARN。

## 运行目录

`resources/` 下集中存放 infra（EVM fork、Polkadot sidecar、regtest、Move 合约、systemd 服务）、docs（文档、SQL）和 scripts（测试启动脚本）。
