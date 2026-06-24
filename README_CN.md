# Surprising Wallet — 多链企业级钱包系统

[🇺🇸 English Version](./README.md)

一个生产级的多链钱包后端，支持 **BTC（UTXO/SegWit 多签）**、**EVM 兼容链**、**TRON**，并为 Solana 和 TON 提供未来可扩展的适配器。基于 Java 17、Spring Boot、PostgreSQL 和 Redis 构建。

---

## 目录

- [架构概览](#架构概览)
- [支持的链与代币](#支持的链与代币)
- [环境要求](#环境要求)
- [快速开始](#快速开始)
- [SQL 初始化](#sql-初始化)
- [公私钥配置](#公私钥配置)
- [服务模块](#服务模块)
- [核心业务流程](#核心业务流程)
  - [地址创建](#1-地址创建)
  - [区块扫描与入账检测](#2-区块扫描与入账检测)
  - [入账确认](#3-入账确认)
  - [资金归集](#4-资金归集)
  - [提现](#5-提现)
  - [多签与单签](#6-多签与单签)
- [配置参考](#配置参考)
- [测试](#测试)
- [重要注意事项](#重要注意事项)
- [项目结构](#项目结构)
- [许可证](#许可证)

---

## 架构概览

系统采用分层架构，职责清晰分离：

```
┌──────────────────────────────────────────────────────────┐
│                    wallet-server                          │
│   （任务编排：扫描 / 归集 / 提现）                         │
├──────────────────────────────────────────────────────────┤
│                    wallet-service                         │
│   （领域层：链适配器、钱包、DAO）                          │
├──────────────┬──────────────┬──────────────┬─────────────┤
│  wallet-sig1 │  wallet-sig2 │ wallet-sig-api│   common   │
│ （第一签）   │ （第二签）    │ （签名API）   │ （工具类）  │
├──────────────┴──────────────┴──────────────┴─────────────┤
│                   currency-sdks                          │
│  bitcoin-sdk │ wallet-common │ wallet-client │ tron-sdk  │
├──────────────────────────────────────────────────────────┤
│              PostgreSQL + Redis                          │
└──────────────────────────────────────────────────────────┘
```

### 链适配器模式

所有区块链交互通过统一的 **`BlockchainAdapter`** 接口进行，由 **`BlockchainAdapterRegistry`** 提供运行时查找：

| 适配器 | 家族 | 模型 | 状态 |
|---|---|---|---|
| `BtcChainAdapter` | bitcoin | UTXO（P2WSH 2-of-3 多签） | ✅ 生产可用 |
| `EvmChainAdapter` | evm | 账户模型（ETH, BNB, Polygon, Arbitrum, Optimism, Base, Avalanche） | ✅ 生产可用 |
| `TronChainAdapter` | tron | 账户模型（能量/带宽模型） | ✅ 生产可用 |
| `SolanaChainAdapter` | solana | 账户模型（计算单元模型） | 🔮 未来扩展 |
| `TonChainAdapter` | ton | 账户模型（转发费模型） | 🔮 未来扩展 |

**核心设计原则**：每条链家族拥有自己的适配器。EVM 链共享一个引擎，通过链配置（RPC URL、链 ID、Gas 策略）区分。BTC 保持独立的 UTXO 引擎。TRON 因其能量/带宽资源模型而单独处理。未来链适配器（Solana、TON）在真正接入 RPC 运行时之前会**快速失败**（抛出 `UnsupportedOperationException`），从而避免静默丢失入账。

---

## 支持的链与代币

### 测试网（默认启用）

| 链 | 链 ID | 原生代币 | ERC20/代币标准 | Gas 策略 |
|---|---|---|---|---|
| BTC Testnet | `testnet3` | BTC | — | segwit-vbytes |
| Ethereum Sepolia | `11155111` | ETH | ERC20（USDT, USDC） | eip1559 |
| BNB Chain Testnet | `97` | BNB | BEP20（USDT, USDC） | legacy-gas-price |
| Polygon Amoy | `80002` | MATIC | ERC20（USDT, USDC） | eip1559 |
| Arbitrum Sepolia | `421614` | ETH | ERC20（USDT, USDC） | eip1559-l2 |
| Optimism Sepolia | `11155420` | ETH | ERC20（USDT, USDC） | eip1559-l2 |
| Base Sepolia | `84532` | ETH | ERC20（USDT, USDC） | eip1559-l2 |
| Avalanche Fuji | `43113` | AVAX | ERC20（USDT, USDC） | eip1559 |
| TRON Mainnet | `tron-mainnet` | TRX | TRC20 | energy-bandwidth |

### 主网（默认禁用 — 在 `application.yaml` 中启用）

| 链 | 链 ID | 确认数 |
|---|---|---|
| Bitcoin Mainnet | `mainnet` | 6 |
| Ethereum Mainnet | `1` | 24 |
| BNB Chain Mainnet | `56` | 20 |
| Polygon Mainnet | `137` | 128 |
| Arbitrum One | `42161` | 40 |
| Optimism Mainnet | `10` | 40 |
| Base Mainnet | `8453` | 40 |
| Avalanche C-Chain | `43114` | 20 |
| Solana Mainnet | `mainnet-beta` | 32 |

---

## 环境要求

- **JDK 17+**
- **Maven 3.8+**
- **PostgreSQL 14+**
- **Redis 6+**
- （可选）Node.js 18+ 用于 `evm-fork/` 测试

---

## 快速开始

### 1. 克隆与构建

```bash
git clone <仓库地址>
cd surprising-wallet
mvn clean install -DskipTests
```

项目包含 **14 个 Maven 模块**，分为两个父级反应器。

### 2. 初始化数据库

连接到 PostgreSQL 并运行初始化脚本：

```bash
# 创建钱包数据库和用户
psql -U postgres -c "CREATE USER wallet WITH PASSWORD 'wallet123';"
psql -U postgres -c "CREATE DATABASE wallet OWNER wallet;"

# 运行 BTC 专用表结构（旧表）
psql -U wallet -d wallet -f surprising-wallet-init-pgsql.sql

# 运行多链表结构（chain_asset, token_config, deposit_record 等）
psql -U wallet -d wallet -f multi-chain-wallet-schema.sql
```

详情请参阅 [SQL 初始化](#sql-初始化)。

### 3. 应用配置

编辑 `backendservices/wallet-parent/wallet-server/src/main/resources/application.yaml`：

- 在 `atomex.chains.*` 下配置 RPC URL
- 在 `atomex.wallet.pubKey1/2/3` 下配置扩展公钥
- 调整链的 `enabled` 开关

编辑 `backendservices/wallet-sig1/src/main/resources/application.yaml`：

- 设置 `atomex.wallet.masterKey`（第一签名者的扩展私钥）

编辑 `backendservices/wallet-sig2/src/main/resources/application.yaml`：

- 设置 `atomex.wallet.masterKey`（第二签名者的扩展私钥）

### 4. 启动服务（按顺序）

```bash
# 1. 启动 wallet-server（主编排服务，端口 8002）
cd backendservices/wallet-parent/wallet-server
mvn spring-boot:run

# 2. 启动 wallet-sig1（第一签名服务，端口 8004）
cd backendservices/wallet-sig1
mvn spring-boot:run

# 3. 启动 wallet-sig2（第二签名服务，端口 8081）
cd backendservices/wallet-sig2
mvn spring-boot:run
```

### 5. 验证健康状态

```bash
curl http://localhost:8002/actuator/health
# 预期输出：{"status":"UP"}
```

---

## SQL 初始化

项目提供两个 SQL 文件。需要**同时应用**以获得完整的数据库结构。

### `surprising-wallet-init-pgsql.sql` — BTC 兼容表结构

创建 BTC 兼容表以及统一运行时表：

| 表 | 用途 |
|---|---|
| `best_block_height` | 跟踪每种币的扫描进度 |
| `currency_balance` | 每种币的热钱包总余额 |
| `btc_address` | 历史 BTC 地址兼容表 |
| `chain_address` | BTC-like / Account 链运行时地址注册表 |
| `utxo_record` | BTC/LTC/DOGE/BCH 运行时 UTXO 集合表 |
| `btc_withdraw_record` | 提现请求记录 |
| `btc_withdraw_transaction` | 已签名交易负载 |
| `user_asset` | 用户余额账本（balance + frozen 列，乐观锁 version） |
| `system_param` | 运行时参数（网络、脚本类型、确认数、费率） |
| `wallet_multisig_config` | 2-of-3 多签公钥配置 |

**注意**：此脚本会**删除所有现有表**并重建。仅在新数据库上运行。

### `multi-chain-wallet-schema.sql` — 多链扩展

使用 `CREATE TABLE IF NOT EXISTS` — 可安全增量应用：

| 表 | 用途 |
|---|---|
| `chain_profile` | 新链 runtime id、BIP44 coin type、网络、确认数、fee/dust、RPC/explorer 配置 |
| `chain_asset` | 链原生资产定义 |
| `token_registry` | 代币元数据（旧表，作为回退读取） |
| `token_config` | **主要**代币配置（启用状态、最小充值/提现、归集设置） |
| `chain_scan_height` | 每条链的扫描器进度跟踪 |
| `hot_wallet_address` | 每条链/资产的热钱包地址 |
| `deposit_record` | 跨所有链的归一化充值事件 |
| `utxo_record` | 按链隔离的 Bitcoin-like UTXO 状态（`AVAILABLE`/`LOCKED`/`SPENT`） |
| `withdrawal_order` | 多链提现订单 |
| `evm_nonce` | EVM 每条链/地址的 nonce 管理 |
| `evm_transaction` / `evm_tx` | EVM 交易记录 |
| `evm_token_transfer` | ERC20/BEP20 转账事件 |
| `tron_transaction` / `tron_tx` | TRON 交易记录 |
| `tron_token_transfer` | TRC20 转账事件 |
| `sol_transaction` | Solana 交易记录（未来） |
| `ton_transaction` | TON 交易记录（未来） |
| `ledger_balance` | 链上余额对账 |

### 代币配置示例

运行 SQL 后，插入代币配置：

```sql
-- 在 Ethereum Sepolia 上启用 USDT
INSERT INTO token_config (chain, symbol, standard, contract_address, decimals, enabled, min_deposit, min_withdraw, collect_enabled)
VALUES ('ethereum-sepolia', 'USDT', 'ERC20', '0x你的USDT合约地址', 6, true, 1.0, 10.0, true);

-- 在 BNB Chain Testnet 上启用 USDC
INSERT INTO token_config (chain, symbol, standard, contract_address, decimals, enabled, min_deposit, min_withdraw, collect_enabled)
VALUES ('bnb-chain-testnet', 'USDC', 'BEP20', '0x你的USDC合约地址', 18, true, 1.0, 10.0, true);
```

`JdbcTokenRegistry` 优先读取 `token_config`，在找不到时回退到 `token_registry`，保证向后兼容。

新链以 `chain_profile`、`chain_asset`、`token_config` 为配置事实来源。
`CurrencyEnum` 和 `CurrencyIds` 仅作为 legacy 兼容层；runtime currency id 与
BIP44 coin type 必须分离。

---

## 公私钥配置

钱包使用基于 BIP44 的 **HD（分层确定性）** 密钥派生。

### BTC 多签（2-of-3 P2WSH）

**wallet-server** 仅持有**扩展公钥**（`tpub...`）用于地址派生：

```yaml
atomex:
  wallet:
    pubKey1: tpubD6NzVbkrYhZ4YeTnP6ae6en8YvKSvxvvCwh5X7gNpwqEeix6o7etGgsyGywcB9gS1bGTmC4WfLKAdK6vxDEzedd7PMRLcYk5yZLj5JkLAVB
    pubKey2: tpubD6NzVbkrYhZ4WuN2bmdffo5p894oRYGQVCfKe3TKT4QVw7qQT18jG1FYbYyB3ePESejLdfaEFMRpsYGVjb4Bh6HiiWaSU8iJRVE46EirNBT
    pubKey3: tpubD6NzVbkrYhZ4XKeuSHwv2p3snJxWjacFsu2rEEht2qMaM5FYV2RkbMaJEYNZGK7B3i8D46RTs83DJNPh2Jd5MzXivXCiHLbqAFKv8MKxrC4
```

**wallet-sig1** 持有第一签名者的**扩展私钥**（`tprv...`）：

```yaml
atomex:
  wallet:
    masterKey: ${ATOMEX_SIG1_MASTER_KEY:}
```

**wallet-sig2** 持有第二签名者的**扩展私钥**（`tprv...`）：

```yaml
atomex:
  wallet:
    masterKey: ${ATOMEX_SIG2_MASTER_KEY:}
```

### EVM 单签

对于 EVM 链，地址派生使用相同的 HD 根密钥，但生成 secp256k1 以太坊地址。签名为**单密钥**模式 — sig2 直接签名并通过 Redis 回传。

### 派生路径

```
m / 44' / <币种索引> / <业务线> / <用户ID> / <地址索引>
```

| 参数 | 说明 |
|---|---|
| `币种索引` | BIP44 币种类型（BTC=0, ETH=60, TRX=195） |
| `业务线` | 业务线（例如：0=热钱包, 1=现货, 2=C2C） |
| `用户ID` | 用户标识 |
| `地址索引` | 每个（业务线, 用户ID）的顺序索引 |

### 安全注意事项

- **绝对不要**将私钥放在 wallet-server 上 — 它只需要公钥进行地址生成。
- 私钥只存在于 wallet-sig1 和 wallet-sig2（隔离的签名服务）。
- 对于 BTC 2-of-3 多签，任意 2 个签名者即可授权交易。
- 第三把密钥（`pubKey3`）是备份/恢复密钥 — 其私钥应存储在冷存储中。

---

## 服务模块

### wallet-server（端口 8002）

编排层。运行定时任务：

| 任务 | 描述 |
|---|---|
| `ScanBtcBlockJob` | 扫描 BTC 区块，检测系统地址的入账 |
| `ScanEthBlockJob` | 扫描 EVM 区块，检测原生 & ERC20 入账 |
| `ScanTronBlockJob` | 扫描 TRON 区块，检测 TRX & TRC20 入账 |
| `BtcCollectionJob` | 将用户地址的 UTXO 归集到热钱包 |
| `EthTransferJob` / `Erc20TransferJob` | 将用户地址的 ETH/ERC20 余额归集到热钱包 |
| `TronTransferJob` | 将用户地址的 TRX/TRC20 余额归集到热钱包 |
| `BatchBtcWithdrawJob` | 处理待处理的 BTC 提现请求 |
| `GetWithdrawRecordJob` | 监控提现确认状态 |
| `RetryFailedWithdraw` | 重试卡住的提现 |
| `RbfBumpJob` | 在需要时通过 RBF 提高 BTC 交易费 |
| `SendRawTxJob` | 将已签名交易广播到网络 |
| `FeeRateUpdater` | 刷新 BTC 费率估算 |

### wallet-sig1（端口 8004）

第一签名服务。监听 Redis 队列（`WALLET_WITHDRAW_SIG_FIRST_KEY`）中的未签名交易，生成第一个见证/签名，然后转发到第二签名队列。

### wallet-sig2（端口 8081）

第二签名服务。从 Redis 队列（`WALLET_WITHDRAW_SIG_SECOND_KEY`）中获取交易，完成第二次签名（验证 BTC 多签的见证），并将完全签名的原始交易推送回 wallet-server 进行广播。

### wallet-service（库）

领域层，包含：
- **链适配器**：每个链家族的 `BlockchainAdapter` 实现
- **钱包实现**：`BtcWallet`、`EthWallet`、`Erc20Wallet`、`TronWallet`
- **DAO 和服务**：MyBatis 仓库、交易管理、地址管理
- **代币注册表**：`JdbcTokenRegistry`（主要）、`InMemoryTokenRegistry`（回退）

### currency-sdks（库）

| 模块 | 用途 |
|---|---|
| `bitcoin-sdk` | BTC 算法层 — `WitnessTransactionBuilder`、`P2wshFeeCalculator`、`TransactionBroadcastValidator`、UTXO 选择 |
| `wallet-common` | 共享领域模型 — `ChainType`、`ChainProfile`、`TransferQuote`、`DepositEvent`、实体 POJO |
| `wallet-client` | RPC 命令层 — `BtcCommand`、`EthLikeCommand`、`BlockChainBtcCommand` |
| `tron-sdk` | TRON 协议工具 |

### evm-fork（测试）

基于 Hardhat 的本地 EVM Fork 测试环境。部署模拟 ERC20 代币，并针对分叉的测试网状态验证完整的业务流程（入账→扫描→确认→归集→提现）。

---

## 核心业务流程

### 1. 地址创建

**BTC（P2WSH 2-of-3 多签）**：

1. 从 3 个扩展公钥使用 BIP44 路径派生子公钥。
2. 构造 P2WSH 赎回脚本：`OP_2 <公钥1> <公钥2> <公钥3> OP_3 OP_CHECKMULTISIG`。
3. 计算 SegWit 见证程序（赎回脚本的 SHA256 → RIPEMD160）。
4. 编码为 Bech32 地址（P2WSH，测试网以 `tb1` 开头）。
5. 存入 `btc_address`，包含派生路径、公钥、赎回脚本和见证脚本。

**EVM 链**：

1. 从 HD 根密钥在 BIP44 路径处派生子公钥。
2. 提取以太坊地址：`keccak256(公钥)[12:32]` → 加 `0x` 前缀。
3. 存入地址分片表。

**TRON**：

1. 从 HD 根密钥在 BIP44 路径处派生（TRON 币种类型 195）。
2. 编码为 Base58 TRON 地址（以 `T` 开头）。

### 2. 区块扫描与入账检测

扫描流水线在 wallet-server 上作为定时任务运行：

```
┌──────────┐    ┌────────────────┐    ┌──────────────┐    ┌─────────────┐
│ 获取最佳 │───▶│ 计算扫描      │───▶│ 获取区块      │───▶│ 提取相关     │
│ 高度     │    │ 范围          │    │ （按高度）    │    │ 交易         │
└──────────┘    └────────────────┘    └──────────────┘    └─────────────┘
                                                                │
                                                                ▼
┌──────────┐    ┌────────────────┐    ┌──────────────┐    ┌─────────────┐
│ 更新     │◀───│ 确认入账      │◀───│ 存入数据库    │◀───│ 匹配         │
│ 高度     │    │ （如果已确认） │    │ （幂等）      │    │ 地址         │
└──────────┘    └────────────────┘    └──────────────┘    └─────────────┘
```

**BTC 入账检测**：
- 通过 BTC JSON-RPC 按高度获取区块。
- 对每笔交易，检查是否有输出地址匹配 `btc_address` 中的系统地址。
- 将每个匹配的输出存储为 `UtxoTransaction`（通过 `UNIQUE(tx_id, seq)` 实现幂等插入）。
- UTXO 单独跟踪；已花费的 UTXO 通过 `spent_tx_id` 标记。

**EVM 入账检测**：
- 通过 `eth_getBlockByNumber` 获取包含完整交易对象的区块。
- 对于原生转账：检查 `to` 地址是否匹配任何系统地址。
- 对于 ERC20 转账：使用 `token_config`/`token_registry` 中的代币合约地址解码 `Transfer(address,address,uint256)` 事件日志。
- 存储为 `deposit_record`，带幂等的 `UNIQUE(chain, tx_hash, log_index)`。

**TRON 入账检测**：
- 类似于 EVM，但使用 TRON 的 gRPC/HTTP API。
- 将 TRX 原生转账与 TRC20 代币转账分离。

**幂等性**：所有入账插入使用 `ON DUPLICATE KEY` / 唯一约束 — 安全地重新扫描同一区块。

### 3. 入账确认

一旦入账达到所需的**确认阈值**：

1. 入账记录的状态从 `DETECTED` 转换为 `CREDITED`。
2. 用户的 `user_asset` 余额递增（或多链使用 `ledger_balance`）。
3. 对于 BTC：UTXO 特定的 `credited` 标志设置为 `true`。
4. 确认操作是幂等的 — 重复扫描不会导致重复入账。

**确认要求**（可在 `application.yaml` 中按链配置）：

| 链 | 测试网确认数 | 主网确认数 |
|---|---|---|
| BTC | 1 | 6 |
| Ethereum | 12 | 24 |
| BNB Chain | 20 | 20 |
| Polygon | 64 | 128 |
| Arbitrum/Optimism/Base | 40 | 40 |
| Avalanche | 20 | 20 |
| TRON | 20 | 20 |

### 4. 资金归集

用户入账定期归集到集中热钱包：

**BTC 归集**：
1. 查询具有足够确认数的未花费 UTXO。
2. 使用防尘优化器选择 UTXO。
3. 构建 SegWit 交易：输入 = 选中的 UTXO，输出 = 热钱包地址。
4. 计算费用（基于 SegWit vbyte）并从归集金额中扣除。
5. 推送到 Redis 签名队列进行多签。
6. 两个签名完成后广播。

**EVM 归集**：
1. 在链上查询用户地址的账户余额。
2. 如果余额 > `RESERVED`（Gas 缓冲）+ Gas 成本，构建转账。
3. 对于原生：直接 ETH 转账（21,000 Gas）。
4. 对于 ERC20：调用代币合约的 `transfer()`。
5. Nonce 通过 `EvmNonceManager` 使用数据库预留 nonce 进行管理，防止冲突。

**归集安全性**：
- 用户地址上保留 `RESERVED` 金额以支付 Gas 费。
- 检测并跳过内部转账（热钱包→热钱包）。
- 归集运行有速率限制以防止 nonce 拥塞。

### 5. 提现

```
用户请求           冻结余额            构建交易            签名队列
    │                 │                   │                    │
    ▼                 ▼                   ▼                    ▼
┌────────┐    ┌──────────────┐    ┌──────────────┐    ┌──────────────┐
│ 创建   │───▶│ 冻结用户     │───▶│ 选择 UTXO    │───▶│ 推送到 Redis │
│ 订单   │    │ 余额         │    │ / 构建交易   │    │ (SIG_FIRST)  │
└────────┘    └──────────────┘    └──────────────┘    └──────────────┘
                                                                │
                                        ┌───────────────────────┘
                                        ▼
┌──────────────┐    ┌──────────────┐    ┌──────────────┐
│ 更新订单     │◀───│ 广播原始     │◀───│ wallet-sig1  │
│ 状态         │    │ 交易         │    │ → wallet-sig2│
└──────────────┘    └──────────────┘    └──────────────┘
```

**逐步说明**：

1. **订单创建**：用户请求提现。系统验证余额，在 `user_asset.frozen` 中冻结金额。
2. **交易构建**：
   - **BTC**：从热钱包选择 UTXO，构造带找零输出的 P2WSH 交易。
   - **EVM**：通过 `EvmNonceManager` 预留 nonce，使用 Gas 估算构建原始交易。
3. **签名流水线**（通过 Redis 队列）：
   - wallet-sig1 获取未签名交易，生成第一个签名/见证，推送到第二队列。
   - wallet-sig2 获取交易，添加第二个签名，验证完整性，携带 `rawTransaction` 十六进制数据推送回。
4. **广播**：wallet-server 的 `SendRawTxJob` 将已签名交易发送到网络。
5. **确认跟踪**：`GetWithdrawRecordJob` 轮询交易状态。确认后：
   - 锁定余额被结算（从 `frozen` 和 `balance` 中扣除）。
   - 订单状态转换为 `CONFIRMED`。

**失败恢复**：
- `RetryFailedWithdraw`：重新广播卡住的交易。
- `RbfBumpJob`：通过 Replace-By-Fee 提高 BTC 交易费用。
- Nonce 恢复：EVM nonce 不匹配时，系统使用修正后的 nonce 重试。

### 6. 多签与单签

| 方面 | BTC（多签） | EVM / TRON（单签） |
|---|---|---|
| **方案** | 2-of-3 P2WSH（SegWit） | 单私钥 |
| **地址格式** | Bech32（测试网 `tb1...`，主网 `bc1...`） | Hex（`0x...`）/ Base58（`T...`） |
| **签名流程** | sig1 → sig2（需要两个签名） | 仅 sig2（单签名） |
| **密钥分布** | 3 个扩展公钥（server）+ 2 个私钥（sig1, sig2）+ 1 个冷备份 | HD 根 → 单私钥（sig2） |
| **费用模型** | 基于 vbyte（SegWit 权重单位） | Gas 限额 × Gas 价格（链特定） |
| **找零地址** | 每笔交易生成 | 不适用（账户模型） |
| **防尘保护** | UTXO 级防尘阈值 | Gas 成本 vs 转账金额检查 |

---

## 配置参考

### 关键 `application.yaml` 属性

#### wallet-server

| 属性 | 描述 | 默认值 |
|---|---|---|
| `atomex.wallet.network` | 网络模式（`test` 或 `main`） | `test` |
| `atomex.wallet.scan.enabled-currencies` | 要扫描的币种 | `btc` |
| `atomex.wallet.scan.start-height` | 初始扫描高度（`0` = 从创世开始） | `0` |
| `atomex.wallet.collection.enabled-currencies` | 要自动归集的币种 | `btc` |
| `atomex.wallet.confirmations.deposit` | 确认入账所需确认数 | `1` |
| `atomex.wallet.confirmations.withdraw` | 提现跟踪目标确认数 | `6` |
| `atomex.wallet.fee.default-rate-sat-vb` | 默认 BTC 费率（sat/vB） | `10` |
| `atomex.wallet.hot.user-id` | 热钱包用户 ID | `0` |
| `atomex.wallet.hot.biz` | 热钱包业务线 | `0` |
| `atomex.wallet.hot.address-index` | 热钱包地址索引 | `0` |
| `atomex.btc.server` | BTC RPC 端点 | `https://bitcoin-testnet-rpc.publicnode.com` |
| `atomex.eth.server` | ETH RPC 端点 | `https://ethereum-sepolia-rpc.publicnode.com` |
| `atomex.eth.chain-id` | ETH 链 ID | `11155111` |
| `atomex.tron.server` | TRON gRPC 端点 | `grpc.trongrid.io:50051` |

#### 每条链的配置（`atomex.chains.<链名称>`）

| 属性 | 描述 |
|---|---|
| `enabled` | 启用/禁用此链 |
| `family` | 链家族：`btc`、`evm`、`tron`、`solana`、`ton` |
| `rpcUrl` | JSON-RPC / HTTP 端点 |
| `chainId` | 网络标识符 |
| `explorerUrl` | 用于交易链接的区块浏览器基础 URL |
| `confirmations` | 最终性所需的确认数 |
| `gasPolicy` | Gas 估算策略 |
| `scanBatchSize` | 每批扫描的区块数 |

**Gas 策略**：

| 策略 | 使用者 | 描述 |
|---|---|---|
| `segwit-vbytes` | BTC | SegWit 虚拟字节费用估算 |
| `eip1559` | ETH, Polygon, Avalanche | 基础费用 + 优先费用 |
| `eip1559-l2` | Arbitrum, Optimism, Base | L2 感知的 EIP-1559 |
| `legacy-gas-price` | BNB Chain | 固定 Gas 价格 |
| `energy-bandwidth` | TRON | 能量 + 带宽资源模型 |
| `compute-unit` | Solana（未来） | 计算单元预算 |
| `ton-forward-fee` | TON（未来） | TON 转发费模型 |

#### 系统参数（`system_param` 表）

| 键 | 描述 | 示例值 |
|---|---|---|
| `wallet.network` | BTC 网络 | `testnet3` |
| `wallet.script_type` | BTC 多签类型 | `P2WSH` |
| `wallet.deposit_confirmations` | 入账确认最小确认数 | `1` |
| `wallet.withdraw_confirmations` | 提现目标确认数 | `6` |
| `wallet.fee_rate_sat_vb` | 默认费率 | `10` |
| `wallet.scan_start_height` | 初始扫描高度 | `0` |

---

## 测试

### 单元与集成测试

```bash
# 运行全部 14 个模块的所有测试
mvn clean install -DskipTests=false

# 仅运行 BTC SDK 测试
cd currency-sdks/bitcoin-sdk
mvn test
```

**测试覆盖**：
- BTC SegWit 多签测试：21 个测试（地址生成、见证结构、签名、费用计算、UTXO 优化）
- 区块链适配器注册测试
- EVM Fork 集成测试（基于 Hardhat，需要 Node.js）

### EVM Fork 回归测试

位于 `evm-fork/`，这些测试针对分叉的测试网状态验证完整的业务流程：

```bash
cd evm-fork
npm install

# 运行单用户流程测试
bash scripts/run-fork-regression.sh

# 运行多用户稳定性测试
RUN_MULTIUSER=true bash scripts/run-fork-regression.sh
```

Fork 测试执行：
1. 通过 Hardhat 分叉实时测试网状态。
2. 部署带 mint 功能的模拟 ERC20 代币（USDT、USDC）。
3. 创建 HD 派生的钱包地址。
4. 执行原生 & ERC20 转账。
5. 验证入账扫描、确认、归集和提现。
6. 验证账本与链上余额的一致性。
7. 测试多用户隔离（用户 A/B/C/D 分别执行不同的流程）。

---

## 重要注意事项

### 生产就绪性

1. **绝不提交私钥**。此仓库的 `application.yaml` 文件仅包含**测试网密钥**。对于生产环境，请使用环境变量、密钥管理服务（AWS Secrets Manager、HashiCorp Vault）或加密配置。

2. **RPC 可靠性**：测试网使用 PublicNode 端点。对于主网，请使用专用 RPC 提供商（Infura、Alchemy、QuickNode）或自托管全节点。

3. **Polygon Amoy**：已知存在因缺少历史状态导致的 Fork RPC 问题。Polygon fork 集成测试目前被阻止。对于生产 Polygon 主网，请使用归档节点访问。

4. **Solana 和 TON**：适配器存在但**快速失败** — 在真正接入 RPC 扫描器运行时之前会抛出 `UnsupportedOperationException`。在连接器实现完成之前，不要在生产环境启用它们。

5. **数据库权限**：`wallet` 数据库用户需要对 schema `public` 有 `CREATE` 权限才能运行 `multi-chain-wallet-schema.sql`。如果使用受限用户，请由 DBA 运行迁移。

6. **Redis**：用作签名流水线消息总线。确保配置 Redis 持久化（AOF/RDB），避免丢失正在处理的签名任务。

### BTC 特定注意事项

- **UTXO 管理**：系统单独跟踪每个 UTXO。长时间运行的钱包会累积大量 UTXO。归集任务会定期合并它们。
- **费用估算**：使用 SegWit vbyte 计算。公式考虑了见证折扣（1 权重单位 = 0.25 vbyte 用于见证数据）。
- **找零输出**：每笔 BTC 交易都会生成一个找零输出回到热钱包地址。
- **RBF（Replace-By-Fee）**：`RbfBumpJob` 可以提高待处理交易的费用。

### EVM 特定注意事项

- **Nonce 管理**：`EvmNonceManager` 使用基于数据库的预留系统。Nonce 被原子性地预留，防止并发转账之间的冲突。
- **Gas 估算**：动态。使用 `eth_estimateGas` + `eth_gasPrice`（对于 EIP-1559 链使用 `eth_maxPriorityFeePerGas`）。
- **代币小数位**：存储在 `token_config.decimals`。原生 ETH 使用 18，USDT/USDC 通常使用 6。
- **L2 链**：Arbitrum、Optimism 和 Base 使用 `eip1559-l2` Gas 策略，该策略考虑了 L1 数据费组件。

### TRON 特定注意事项

- **能量和带宽**：TRON 使用不同于 Gas 的资源模型。交易消耗能量（用于智能合约调用）和带宽（用于数据）。用户可以冻结 TRX 来获取资源。
- **双 API**：同时支持 gRPC（旧版 `atomex.tron.server`）和 HTTP（PublicNode）端点。
- **地址格式**：Base58check 编码，以 `T` 开头。

### 安全考虑

1. **密钥隔离**：wallet-server（地址生成）永远看不到私钥。只有 wallet-sig1/sig2 持有签名密钥。
2. **多签阈值**：BTC 需要 2-of-3 签名。单个被攻破的签名服务器无法转移资金。
3. **冷备份密钥**：第三把 Bitcoin 密钥（`pubKey3`）的私钥应存储在冷存储中用于灾难恢复。
4. **乐观锁**：`user_asset.version` 防止并发余额更新损坏用户余额。
5. **幂等入账**：所有入账插入使用唯一约束 — 重新扫描同一区块不会导致重复入账。

---

## 项目结构

```
surprising-wallet/
├── pom.xml                          # 根 Maven 聚合器
├── surprising-wallet-init-pgsql.sql # BTC 旧表结构
├── multi-chain-wallet-schema.sql    # 多链扩展表结构
│
├── currency-sdks/                   # 区块链 SDK 库
│   ├── pom.xml
│   ├── bitcoin-sdk/                 # BTC 算法库
│   │   └── src/main/java/.../
│   │       ├── WitnessTransactionBuilder.java
│   │       ├── P2wshFeeCalculator.java
│   │       └── TransactionBroadcastValidator.java
│   ├── wallet-common/               # 共享领域模型
│   │   └── src/main/java/.../common/
│   │       ├── chain/               # ChainType, ChainProfile, TransferQuote
│   │       ├── pojo/                # 实体 POJO
│   │       └── dto/                 # DTO
│   ├── wallet-client/               # RPC 命令层
│   └── tron-sdk/                    # TRON 工具
│
├── backendservices/                 # 后端服务模块
│   ├── pom.xml
│   ├── common/                      # 共享工具（加密、日期、枚举）
│   ├── wallet-sig-api/              # 签名 API 契约
│   ├── wallet-sig1/                 # 第一签名服务
│   │   └── src/main/java/.../sig/first/
│   │       ├── jobs/FirstSignJob.java
│   │       └── service/
│   │           └── BtcFirstSignService.java
│   ├── wallet-sig2/                 # 第二签名服务
│   │   └── src/main/java/.../sig/second/
│   │       └── impl/BtcSecondSignService.java
│   └── wallet-parent/
│       ├── wallet-service/          # 领域层
│       │   └── src/main/java/.../wallet/service/
│       │       ├── chain/           # BlockchainAdapter 实现
│       │       │   ├── btc/BtcChainAdapter.java
│       │       │   ├── evm/         # EvmChainAdapter + 子引擎
│       │       │   ├── tron/TronChainAdapter.java
│       │       │   └── future/      # SolanaChainAdapter, TonChainAdapter
│       │       ├── wallet/          # 钱包实现
│       │       │   ├── impl/BtcWallet.java
│       │       │   ├── impl/Erc20Wallet.java
│       │       │   └── impl/TronWallet.java
│       │       ├── service/         # 业务服务
│       │       │   ├── TransactionService.java
│       │       │   ├── UserAssetService.java
│       │       │   └── ...
│       │       └── dao/             # MyBatis 仓库
│       └── wallet-server/           # 任务编排
│           └── src/main/java/.../wallet/jobs/
│               ├── deposit/         # 区块扫描任务
│               ├── transfer/        # 归集任务
│               └── withdraw/        # 提现任务
│
└── evm-fork/                        # Hardhat EVM Fork 测试环境
    ├── contracts/MockERC20.sol
    ├── scripts/
    │   └── run-fork-regression.sh
    └── hardhat.config.js
```

---

## 许可证

专有软件。保留所有权利。

---

## 贡献

这是一个内部项目。请联系维护者获取贡献指南。

---

🤖 由 [Claude Code](https://claude.com/claude-code) 生成
