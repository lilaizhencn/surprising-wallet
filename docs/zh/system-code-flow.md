# 运行代码流程

[English version](../en/system-code-flow.md)

![系统代码流程](../assets/system-code-flow-diagram.svg)

## 多租户托管流程

托管控制面不管理交易所内部的用户模型。交易所只传入不透明的 `externalReference`；永久键 `(tenantId, chain, externalReference)` 始终返回同一地址。

```text
交易所后端
  -> 通过 HMAC 鉴权调用 POST /custody/api/v1/addresses
  -> custody_address 幂等查询或创建
  -> 分配 chain_address
  -> 返回地址和 externalReference

链上扫描器
  -> 在同一事务中写 deposit_record 并增加 ledger_balance
  -> 写 custody_deposit 投影
  -> 写持久化 custody_event
  -> 为每个端点生成 webhook_delivery
  -> 签名回调 DEPOSIT.CONFIRMED，并带回 externalReference
```

Console 手动创建的地址可以不传 `externalReference`；这些地址仍计入租户资产总额，但不绑定租户内部用户。创建地址本身不产生 Webhook。所有 Console/API 查询和变更都强制应用租户隔离。

提现请求使用永久幂等键，复用现有的资金锁定、广播和确认流程，并从持久化投递记录发送签名生命周期 Webhook。

## 资产解析

运行时代码应通过数据库元数据解析资产：

```text
request chain/symbol/contract
  -> chain_profile
  -> chain_asset
  -> token_config when token asset
  -> BlockchainRuntimeService/BlockchainAdapter input
```

业务路由应使用 `chain + asset` lookup。legacy enum 和数字 currency id 只应在不可避免时作为兼容映射存在。

## 充值流程

```text
chain RPC/indexer
  -> chain scanner
  -> chain_address match
  -> deposit_record insert or idempotent skip
  -> ledger_balance credit
  -> notification/API layer
```

幂等性由交易身份和 chain/address/asset 约束保证。scanner 重放不能重复增加 `ledger_balance`。

## 提现流程

```text
external withdrawal request
  -> asset lookup from chain_profile/chain_asset/token_config
  -> ledger lock
  -> chain transaction builder
  -> signer service or local Ed25519 signer
  -> broadcast
  -> confirm
  -> ledger finalize
```

同一个提现订单重试时，应返回或复用已有交易状态，不应重复广播新交易。

## 归集流程

```text
collect job
  -> find eligible chain_address balances
  -> asset policy from token_config
  -> build transfer to hot wallet
  -> sign/broadcast
  -> confirm collection
  -> ledger update
```

token 归集使用 token 专属策略，同时使用链服务中的原生 gas 策略。

## 链特性说明

Bitcoin-like 链：

- 使用 `AVAILABLE`、`LOCKED`、`SPENT` 状态的 UTXO 记录。
- 本地 regtest 覆盖 BTC/LTC/DOGE/BCH。
- 广播/并发测试由 `scripts/regtest/all-chain-regtest.sh test-utxo` 驱动。

EVM 链：

- 共享 EVM engine，通过 chain profile 区分链。
- ERC20 token 行为来自 `token_config`。
- Fork 测试每次一条链运行在 `127.0.0.1:8545`。

TRON：

- 使用 TRON 资源模型。
- TRC20 与 EVM 虽然概念类似，但运行路径独立。

SOL/TON/APTOS/SUI：

- 使用 Ed25519 key 派生。
- DB 测试覆盖确定性的 scanner/ledger/transaction 行为。
- live 测试依赖外部 devnet/testnet RPC、faucet 限流和已充值地址。

