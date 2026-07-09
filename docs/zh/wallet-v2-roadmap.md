# Surprising Wallet V2 开发规划

[English version](../en/wallet-v2-roadmap.md)

本文描述 Surprising Wallet 下一阶段产品和技术方向。文档中的 Robinhood Chain、USDG、Tokenized Stocks、Tokenized ETFs、LI.FI、Socket 等均按规划目标记录；具体 chain id、RPC、合约地址、报价源、风控参数和合规开关以实施阶段的配置、审计和上线审批为准。

## 总体目标

V2 以统一资产平台为目标，把 Crypto、稳定币、RWA、Tokenized Stocks、Tokenized ETFs、DeFi 和 Earn 等资产能力纳入同一套钱包体验：

- 用户只看到统一 Portfolio 和统一 Exchange 入口。
- 资产价格由统一 Oracle 价格中心提供。
- 交易路径由系统自动选择，同链兑换、跨链桥、跨链换币不再拆成多个用户入口。
- 跨链能力采用成熟 Aggregator，不自研跨链协议。
- 余额、流水、报价、交易状态和链上确认必须可审计、可追踪、可回滚。

## Oracle 价格中心

目标是建立统一资产价格中心，为钱包内所有资产提供实时价格支持。

### 覆盖范围

| 资产类型 | 用途 |
|---|---|
| Crypto | 实时价格、资产折算、Portfolio 展示 |
| 稳定币 | USDG、USDC、USDT 等稳定币价格锚定和偏离检测 |
| Robinhood Chain Stock Token | AAPL、NVDA、MSFT 等 Tokenized Stocks 估值 |
| Tokenized ETF | QQQ、SPY 等 ETF 类资产估值 |
| DeFi / Earn | 后续收益类资产净值、头寸和收益展示 |

### 实施要点

- Oracle 输出统一的 `asset + chain + quoteCurrency` 价格视图，Portfolio 不直接依赖单个外部源。
- 价格必须带有来源、时间戳、精度、过期时间和异常状态。
- 稳定币需要做脱锚告警，不能默认永远等于 1 USD。
- RWA 和证券类 token 需要支持交易时段、停牌、价格延迟、公司行为等扩展字段。
- 金额计算继续使用整数最小单位或 `BigDecimal`，不得使用浮点数。

## Robinhood Chain

目标是接入 Robinhood Chain，布局 RWA 与 Tokenized Assets 生态。

### 规划支持资产

| 资产 | 定位 |
|---|---|
| ETH | Gas 资产 |
| USDG | 美元稳定币 |
| AAPL、NVDA、MSFT 等 | Tokenized Stocks |
| QQQ、SPY 等 | Tokenized ETFs |

### 钱包侧职责

- 在 `chain_profile`、`chain_rpc_node`、`chain_asset`、`token_config` 中配置 Robinhood Chain 和链上资产。
- 复用现有 EVM 类链的地址、签名、扫描、提现、归集和余额流水能力，除非 Robinhood Chain 最终协议要求独立适配。
- Portfolio 通过 Oracle 获取 USDG、股票 token 和 ETF token 的估值。
- 证券类资产展示、交易、转账和 Earn 能力必须预留合规、地区、KYC、资产可用性开关。

## Unified Exchange

产品上只保留一个 `Exchange` 入口，不再单独暴露 `Bridge` 功能。Bridge 是 Cross-chain Swap 的内部路径之一，用户不需要理解底层是 swap 还是 bridge。

### 路由规则

| 用户输入 | 系统路径 |
|---|---|
| 同链、不同资产 | Swap |
| 不同链、同资产 | Bridge |
| 不同链、不同资产 | Cross-chain Swap |

系统根据用户输入的源链、源资产、目标链、目标资产和数量自动选择最优路径，并展示统一 quote、费用、预计到账、风险提示和状态。

## Bridge 与 Cross-chain Swap 实现

不自行开发跨链协议，优先采用成熟 Aggregator。

### 优先级

| 方案 | 定位 |
|---|---|
| LI.FI | 首选 Aggregator |
| Socket | 备选 Aggregator |

Aggregator 负责自动完成：

- 最优路由
- Bridge
- DEX Swap
- Gas 处理
- 流动性选择

钱包负责：

- 获取 Quote
- 校验资产、链、金额、滑点、费用和最小到账
- 生成用户需要签名的交易
- 提交或引导用户广播交易
- 查询交易状态
- 记录 quote、route、用户签名、链上 tx、状态变更和最终到账
- 将所有状态变化落入可审计流水

### 状态与幂等

Exchange 订单至少需要区分：

| 状态 | 含义 |
|---|---|
| `QUOTED` | 已获取报价，尚未签名或提交 |
| `SIGNED` | 用户已签名 |
| `SUBMITTED` | 交易已提交到源链或 Aggregator |
| `SOURCE_CONFIRMED` | 源链交易已确认 |
| `BRIDGING` | 跨链或中间路由执行中 |
| `TARGET_CONFIRMED` | 目标链到账或目标 swap 已确认 |
| `COMPLETED` | 钱包账务与状态已完成 |
| `FAILED` | 交易失败，需进入失败处理 |
| `REFUNDING` | 退款或补偿处理中 |
| `REFUNDED` | 退款完成 |

所有状态推进必须以 `orderId + routeId + chainTxHash + step` 做幂等约束，避免重复回调、重复轮询或服务重启导致重复入账。

## USDG 支持

USDG 定位为美元稳定币，未来作为钱包标准稳定币支持。

规划场景：

- 转账
- 支付
- DeFi
- Earn
- Portfolio 估值和稳定币资产分组

上线前必须验证：

- 合约地址和 decimals
- 链上充值、提现、归集、内部转账
- 稳定币脱锚价格处理
- 手续费资产和 gas 余额要求
- 大额和高频转账的流水幂等

## QQQ 支持

QQQ 定位为 Tokenized ETF，未来作为证券类资产统一纳入钱包管理，与 Crypto 资产共同展示。

规划要求：

- 通过 Oracle 获取估值。
- Portfolio 中与 Crypto、稳定币一起展示，但应保留 RWA / Securities 分类。
- 预留交易时段、价格延迟、地区限制、KYC、资产下架和公司行为字段。
- 行情、余额和转账状态要能单独审计。

## Roadmap

### Phase 1

| 能力 | 目标 |
|---|---|
| Oracle 接入 | 建立统一价格中心，为 Crypto、稳定币和未来 RWA 资产提供价格 |
| Robinhood Chain 接入 | 完成链配置、资产配置、地址/签名/扫描/提现路径验证 |
| LI.FI 集成 | 完成 quote、route、签名、提交、状态查询和失败处理骨架 |
| Exchange 统一入口 | 用一个入口承载 swap、bridge、cross-chain swap |

### Phase 2

支持主流 EVM 资产：

- ETH
- USDC
- USDT

实现：

- 同链 Swap
- Bridge
- Cross-chain Swap
- 统一用户体验、统一状态和统一流水

### Phase 3

接入 Robinhood Chain 生态资产：

- USDG
- Tokenized Stocks
- Tokenized ETFs，例如 QQQ、SPY

完善：

- Portfolio
- RWA 分类展示
- DeFi / Earn 能力
- 证券类资产扩展字段和风控开关

## 上线检查

| 检查项 | 要求 |
|---|---|
| 价格 | 来源、时间戳、过期、精度、异常状态可审计 |
| 资产 | `chain_profile`、`chain_asset`、`token_config` 配置完整 |
| 余额 | 每一次余额变化都有流水，支持账账核对 |
| 幂等 | quote、订单、链上交易、回调、轮询、重试均有幂等键 |
| 状态 | pending、confirmed、failed、refund、reorg 等状态边界清晰 |
| 风险 | 滑点、最小到账、费用、路由变化、Aggregator 失败都有用户提示和后台记录 |
| 安全 | 不保存用户私钥，不记录明文签名密钥，不把生产 RPC key 写入仓库 |
| 回滚 | Aggregator、链、资产和 Exchange 入口需要具备配置级关闭能力 |
