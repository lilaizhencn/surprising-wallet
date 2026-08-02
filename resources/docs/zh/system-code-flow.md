# 运行代码流程


![系统代码流程](../assets/system-code-flow-diagram.svg)

## 密钥加载与角色隔离

```text
各进程唯一 application.yaml / 未来 Nacos 或 KMS
  -> Spring sw.wallet.keys
  -> WalletKeyConfig
  -> 启动时校验 4 个 Base64 32 字节 Seed 且互不相同
  -> wallet-api：sig2 私钥 + 三组 public root + Ed25519
  -> wallet-sig1：仅 sig1 私钥 + 三组 public root
  -> wallet-sig2：仅 sig2 私钥 + 三组 public root
```

密钥不再写入 PostgreSQL，也没有运行时查询、明文展示或热更新接口。配置变更必须重启对应进程；已有派生地址后更换 Seed 会导致签名材料与地址不一致，因此必须作为受审计的整体密钥迁移处理。

## 多租户托管流程

托管控制面不管理交易所内部的用户模型。租户传 `chainId`、自己的 `subject` 和可选的 `addressVersion`，服务根据该链唯一启用的网络返回稳定地址。

```text
交易所后端
  -> 通过 HMAC 鉴权调用 POST /custody/api/v1/addresses
  -> 按 tenant + chain + subject + addressVersion 查询或创建 custody_address
  -> addressVersion 默认 0；递增版本即可更换地址，旧地址继续监听
  -> 相同 subject + addressVersion 的所有 EVM 链返回同一个地址
  -> 返回地址 ID、链、自动选择的网络、subject、addressVersion 和地址

链上扫描器
  -> 在同一事务中写 deposit_record 并增加 ledger_balance
  -> 写 custody_deposit 投影
  -> 写持久化 custody_event
  -> 仅当充值地址由公开 API 创建时，为每个端点生成 webhook_delivery
  -> 签名回调 DEPOSIT.CONFIRMED，并带回 subject 和充值地址；Console 地址不自动投递
```

Console 可附带标签和元数据手动创建地址；公开 API 不接收这些管理字段。地址仍计入租户资产总额，但其后续充值不自动投递 Webhook。创建地址本身也不产生 Webhook。所有 Console/API 查询和变更都强制应用租户隔离。

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
  -> EVM: native_symbol + gas_policy + fee_model
  -> eth_estimateGas + sender/relayer 链上原生币余额校验
  -> chain transaction builder（legacy / EIP-1559 / EIP-7702 type-4）
  -> signer service or local Ed25519 signer
  -> broadcast
  -> receipt confirm（执行费 + L1/DA 费 + Operator Fee）
  -> ledger finalize
```

同一个提现订单重试时，应返回或复用已有交易状态，不应重复广播新交易。

EVM 的三类配置必须分开解释：

- `native_symbol` 指定实际支付 Gas 的链原生资产及账务符号，例如 BNB、POL、MNT、ETH_ARB；Gas 金额换算读取对应 `chain_asset.decimals`，不能固定按 18 位处理。
- `gas_policy` 只决定交易报价和信封类型：`legacy-gas-price` 或 `eip1559`。
- `fee_model` 决定总费用组成：`standard`、`op-stack`、`op-stack-l1`、`arbitrum-nitro`、`scroll`。OP Stack/Scroll 必须计入回执的 L1 数据费，OP Stack 还可能有 Operator Fee；Arbitrum 的父链成本已进入 Nitro gas 计量，拆分审计时不得二次扣费。

逐链 Gas 资产、普通交易信封和 EIP-7702 当前启用状态见
[EVM 链 Gas 资产与交易模型矩阵](evm-chain-gas-and-transaction-model.md)。

发送普通 EVM 交易前，服务先通过 `eth_estimateGas` 估算并加安全余量，再按最终签名交易查询链级 L1/Operator 费用，校验发送方链上原生币余额。确认时以回执实际费用覆盖预估值；需要独立 L1 费用但回执缺失对应字段时中止结算并保留人工审计，禁止静默少扣。

Starknet：

- 使用 `sig2` BIP32 根按项目统一路径派生 Stark Curve 私钥，依据 `account_class_hash`、公钥 calldata 和公钥 salt 计算 counterfactual 账户地址；class hash 必须由对应网络配置，不在代码中隐式替换。
- 首次发送前先通过 `get_class_hash_at` 判断账户是否已部署；只有 RPC 明确返回“合约不存在”时才发起 `deploy_account_v3`，先估算资源费再广播，部署成功后通过 `account.execute_v3` 发送原生 STRK 或 ERC-20。
- Starknet 的 Gas 只使用 STRK。原生 STRK 也按其 ERC-20 合约的 `Transfer` 事件扫描，Token 数量按 uint256 的 low/high 两个 128 位字段合成，扫描结果写入 `starknet_transaction` 并通过统一充值入账幂等流程处理。
- Starknet 没有接入 EIP-7702；归集和提现仍走账户合约的单笔 `execute_v3`，交易、实际手续费、确认数和失败状态均保留审计记录。

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

EIP-7702 的外层交易由 relayer 支付当前链的原生 Gas，授权账户本身可以归集全部原生余额。签名交易写入加密 outbox 之前，系统同时完成租户 Gas 账户预留和 relayer 链上余额校验；任一不足都回滚未广播批次。type-4 只用于包含新 authorization 的批次，已委托账户继续使用 type-2 外层交易。确认阶段和普通 EVM 交易复用同一 `fee_model`，分别记录执行费、L1/DA 费、Operator Fee 与总费用。

## 扫描调度与开关判定

1. `wallet-api` 的扫描 Job 仅负责固定频率触发，不直接承载扫链业务。
2. `wallet-api` 内部业务服务根据链平均出块速度判断本轮到期链，未到期链不访问 RPC。
3. 到期链执行前实时校验“钱包总开关 → 全局任务开关 → 链配置启用 → 链任务开关”。
4. 管理员通过 console 修改开关后直接写 PostgreSQL；运行逻辑无本地缓存，下一次调度检查立即采用新值。
5. 关闭业务开关只阻止创建新链上动作，已广播交易继续确认、对账和发送最终回调。

## 链特性说明

Bitcoin-like 链：

- 使用 `AVAILABLE`、`LOCKED`、`SPENT` 状态的 UTXO 记录。
- 本地 regtest 覆盖 BTC/LTC/DOGE/BCH。
- 广播/并发测试由 `scripts/regtest/all-chain-regtest.sh test-utxo` 驱动。

EVM 链：

- 共享 EVM engine，通过 chain profile 区分链。
- ERC20 token 行为来自 `token_config`。
- 原生 Gas 币种由 `chain_profile.native_symbol` 与唯一启用的原生 `chain_asset` 共同约束。
- `gas_policy` 与 `fee_model` 分离，启动校验会拒绝模糊或未知的 EVM 费用策略。
- EIP-7702 relayer 与普通发送方都必须通过真实链上原生币余额校验后才能广播。
- Fork 测试每次一条链运行在 `127.0.0.1:8545`。

TRON：

- 使用 TRON 资源模型。
- TRC20 与 EVM 虽然概念类似，但运行路径独立。

SOL/TON/APTOS/SUI：

- 使用 Ed25519 key 派生。
- DB 测试覆盖确定性的 scanner/ledger/transaction 行为。
- live 测试依赖外部 devnet/testnet RPC、faucet 限流和已充值地址。

Starknet：

- 地址生成、账户部署、STRK 原生转账、ERC-20 转账、Transfer 扫描入账、原生币归集、Token 归集和交易确认已通过本地 Devnet 完整流程测试。
- 生产启用前必须为每个网络分别核验 account class hash、STRK 合约地址、Token 合约地址、RPC 的 `rpc/scan/broadcast` 三类用途和链上实际手续费；测试网配置默认关闭。
