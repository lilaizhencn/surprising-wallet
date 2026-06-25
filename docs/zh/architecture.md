# 架构

[English version](../en/architecture.md)

钱包由 Spring Boot 服务和 SDK 模块组成。运行时路由基于数据库资产元数据，而不是 enum 或数字 currency id 决策。

![架构图](../assets/architecture-diagram.svg)

## 运行模型

运行时资产来源：

| 表 | 作用 |
|---|---|
| `chain_profile` | 链 key、链族、启用网络、确认策略、扫描/提现/归集/划转开关、扫描起始高度、BIP44 coin type |
| `chain_rpc_node` | 每条链的 RPC/fullnode/indexer/faucet 节点、环境标签、优先级、认证和备注 |
| `wallet_system_config` | 全局扫描/提现/归集/划转总开关 |
| `wallet_public_key` | wallet-server 启动必需的三组 BIP32 public key |
| `chain_asset` | 原生资产和链内资产定义 |
| `token_config` | token 合约/配置、decimals、归集/提现策略 |
| `ledger_balance` | 按链隔离的用户/系统余额状态 |

应用应通过 `chain + symbol` 或 `chain + contract` 解析资产，然后把 runtime asset 传入 scanner、withdraw、collection 和 signing 流程。

## 模块

| 模块 | 职责 |
|---|---|
| `backendservices/wallet-parent/wallet-server` | Spring Boot 入口、任务、编排、应用配置 |
| `backendservices/wallet-parent/wallet-service` | 链服务、scanner、交易构建、repository |
| `backendservices/wallet-sig1` | BTC-like 2-of-3 第一签服务 |
| `backendservices/wallet-sig2` | 第二签服务和 EVM/TRON 签名路径 |
| `backendservices/wallet-sig-api` | 签名 API 共享契约 |
| `currency-sdks/bitcoin-sdk` | Bitcoin-like 交易、地址、脚本支持 |
| `currency-sdks/tron-sdk` | TRON SDK 集成 |
| `currency-sdks/wallet-common` | 链/key/runtime 公共工具 |
| `currency-sdks/wallet-client` | 客户端接口 |

## 链族

| 链族 | 链 | 本地测试支持 | live/testnet 支持 |
|---|---|---|---|
| Bitcoin-like UTXO | BTC, LTC, DOGE, BCH | Docker regtest 节点 | 外部 RPC 配置 |
| EVM | ETH, BNB, Polygon, Arbitrum, Optimism, Base, Avalanche | Hardhat fork | Sepolia 和其他配置测试网 |
| TRON | TRON | DB 测试 | Nile live flow |
| Solana | SOL, SPL token | DB 测试 | Devnet live flow |
| TON | TON, Jetton | DB 测试 | Testnet 连通性和 funded live flow |
| Aptos | APT, coin resource | DB 测试 | Devnet live flow |
| Sui | SUI, coin resource | DB 测试 | Testnet live flow |

## 签名模型

Bitcoin-like 链使用三组 BIP32 root：

```text
BIP32 root #1 -> pubKey1，在线第一签私钥 root
BIP32 root #2 -> pubKey2，在线第二签私钥 root
BIP32 root #3 -> pubKey3，离线恢复私钥 root
```

SOL/TON/APTOS/SUI 使用一个 Ed25519 master seed：

```text
SW_ED25519_SEED -> SLIP-0010 Ed25519 派生 -> 每条链/每个用户 key
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
- 构建到热钱包的转账。
- 确认并幂等更新 ledger 状态。

## 启动配置校验

wallet-server 启动时会检查 `chain_profile`、`chain_rpc_node`、`wallet_public_key` 和 `wallet_system_config`。同一链只能启用一个网络；生产环境禁止启用测试网络；启用链必须至少有一个匹配当前环境的 RPC 节点。校验结果会按链打印状态，缺失配置或关闭开关会输出 WARN。

## 运行目录

`scripts/`、`infra/` 和 `evm-fork/` 保留在仓库根目录，因为测试和脚本直接引用这些路径。它们的说明文档位于 `docs/`。
