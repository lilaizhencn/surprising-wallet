# EVM 链 Gas 资产与交易模型矩阵

本文档说明 Surprising Wallet 当前数据库基线中全部 EVM 链需要准备哪一种
Gas 资产、普通交易使用哪一种信封，以及 EIP-7702 是否已在项目中启用。

- 核对日期：2026-08-02。
- 配置基线：`resources/docs/db/surprising-wallet-init-pgsql.sql`。
- 范围：18 个 EVM 链、36 个网络 profile。
- `✓` 表示该网络 profile 在基线中 `enabled = true`。
- 本表描述的是**项目当前配置与实现**。链协议支持某种交易类型，不代表项目已经启用该路径。

## 先明确“L2 使用 ETH”的含义

“L2 使用 ETH 作为 Gas”表示发送方必须在**该 L2 网络地址**中持有 ETH。普通
L2 交易不会直接从发送方的 Ethereum L1 地址扣款，也不能用仅存在于 L1 的 ETH
余额支付。

例如：

- 在 Base、Optimism、Arbitrum、Scroll 上发送交易，需要对应 L2 上的 ETH。
- OP Stack 或 Scroll 的 L1 数据费虽然受 Ethereum L1 成本影响，但由协议从发送方
  的 L2 Gas 余额中自动计收。
- 只有执行跨链桥的 L1 deposit、prove 或 claim 等独立 L1 交易时，才需要另外准备
  Ethereum L1 上的 ETH。
- Mantle 是 Ethereum L2，但 L2 交易使用 MNT；官方桥接说明也明确区分“L1 使用
  ETH、Mantle L2 使用 MNT”。

`native_symbol` 是钱包内部的链级账务符号。`ETH_BASE`、`ETH_ARB`、
`ETH_SCROLL` 等都代表“对应网络上的 ETH”，用于避免不同链的 ETH 余额在账务中
混在一起，并不是另一种 ERC-20 Token。

## 当前链配置总表

| 链 | 网络（Chain ID） | 内部 `native_symbol` | 实际需要准备的 Gas | 普通交易模型 | `fee_model` | 项目当前 EIP-7702 |
|---|---|---|---|---|---|---|
| ARBITRUM | mainnet (42161)<br>sepolia (421614) ✓ | ETH_ARB | ETH；该 L2 上的 ETH | Type 2（EIP-1559） | arbitrum-nitro | 未启用；已有生产 runbook |
| AVAX_C | fuji (43113) ✓<br>mainnet (43114) | AVAX_C | AVAX；链自身原生币 | Type 2（EIP-1559） | standard | 未启用 |
| BASE | mainnet (8453)<br>sepolia (84532) ✓ | ETH_BASE | ETH；该 L2 上的 ETH | Type 2（EIP-1559） | op-stack | 未启用；已有生产 runbook |
| BERACHAIN | bepolia (80069)<br>mainnet (80094) | BERA | BERA；链自身原生币 | Type 2（EIP-1559） | standard | 未启用 |
| BNB | mainnet (56)<br>testnet (97) ✓ | BNB | BNB；链自身原生币 | Type 0（legacy） | standard | 未启用；已有生产 runbook |
| ETH | mainnet (1)<br>sepolia (11155111) ✓ | ETH | ETH；Ethereum L1 原生币 | Type 2（EIP-1559） | standard | 未启用；已有生产 runbook |
| GNOSIS | chiado (10200)<br>mainnet (100) | XDAI | XDAI；链自身原生币 | Type 2（EIP-1559） | standard | 未启用 |
| HYPEREVM | mainnet (999)<br>testnet (998) ✓ | HYPE | HYPE；链自身原生币 | Type 0（legacy） | standard | 未启用 |
| LINEA | mainnet (59144)<br>sepolia (59141) ✓ | ETH_LINEA | ETH；该 L2 上的 ETH | Type 2（EIP-1559） | standard | 未启用 |
| MANTLE | mainnet (5000)<br>sepolia (5003) ✓ | MNT | MNT；链自身原生币 | Type 2（EIP-1559） | op-stack-l1 | 未启用 |
| MONAD | mainnet (143)<br>testnet (10143) | MON | MON；链自身原生币 | Type 2（EIP-1559） | standard | 未启用 |
| OPTIMISM | mainnet (10)<br>sepolia (11155420) ✓ | ETH_OP | ETH；该 L2 上的 ETH | Type 2（EIP-1559） | op-stack | 未启用；已有生产 runbook |
| POLYGON | amoy (80002) ✓<br>mainnet (137) | POL | POL；链自身原生币 | Type 2（EIP-1559） | standard | 未启用；已有生产 runbook |
| ROBINHOOD_CHAIN | mainnet (4663)<br>testnet (46630) | ETH_ROBINHOOD | ETH；该 L2 上的 ETH | Type 2（EIP-1559） | standard | 未启用 |
| SCROLL | mainnet (534352)<br>sepolia (534351) ✓ | ETH_SCROLL | ETH；该 L2 上的 ETH | Type 2（EIP-1559） | scroll | 未启用 |
| UNICHAIN | mainnet (130)<br>sepolia (1301) ✓ | ETH_UNICHAIN | ETH；该 L2 上的 ETH | Type 2（EIP-1559） | op-stack | 未启用 |
| X_LAYER | mainnet (196)<br>testnet (1952) | OKB | OKB；链自身原生币 | Type 2（EIP-1559） | standard | 未启用 |
| ZKSYNC | mainnet (324)<br>sepolia (300) | ETH_ZKSYNC | ETH；该 L2 上的 ETH | Type 2（EIP-1559） | standard | 未启用 |

## 如何理解“交易模型”

### 普通交易

- `Type 0（legacy）`：使用 `gasPrice`。当前只有 BNB 和 HYPEREVM 配置为该模型。
- `Type 2（EIP-1559）`：使用 `maxFeePerGas` 和
  `maxPriorityFeePerGas`。其余 16 个 EVM 链当前都使用该模型。
- `fee_model` 不决定交易信封。它只决定确认和对账时是否需要额外核算 L1 数据费、
  operator fee，或拆分 Arbitrum Nitro 父链成本。

### EIP-7702

EIP-7702 是 Type 4 set-code 交易，不是某条链的通用替代交易模型。项目当前状态为：

1. 数据库基线的 `evm_7702_config` 没有任何记录，因此没有任何链实际启用
   EIP-7702 归集或批量提现。
2. ETH、Arbitrum、Base、BNB、Optimism、Polygon 已有生产 runbook，但 runbook
   不等于运行配置。
3. 只有某条链存在 `ACTIVE` 的 `evm_7702_config`，并且对应业务任务已开启时，
   才会进入 EIP-7702 工作流。
4. 批次包含新 authorization 时，外层交易为 Type 4；账户已经委托且本批次不需要
   新 authorization 时，外层交易继续使用 Type 2。
5. 外层 relayer 使用该链的 Gas 资产付款。例如 Polygon 使用 POL、BNB 使用 BNB、
   Mantle 使用 MNT、Base 使用 Base L2 上的 ETH。authority 账户不替 relayer
   支付外层 Gas。

链协议支持 EIP-7702 也不会自动启用项目功能。例如 BSC 已在 Pascal 升级实现
EIP-7702，但本项目基线仍没有对应的 ACTIVE 配置。

## 费用模型

| `fee_model` | 当前链 | 结算含义 |
|---|---|---|
| `standard` | Ethereum、Avalanche、BNB、Polygon、Linea 及多数独立 EVM 链 | 使用回执执行费；Linea 的 L1 成本已内化到 L2 Gas 价格，并不表示 Linea 没有 L1 成本 |
| `op-stack` | Base、Optimism、Unichain | 执行费 + L1 数据费 + 链升级后适用的 operator fee |
| `op-stack-l1` | Mantle | MNT 执行费 + 通过 oracle 取得的 L1 数据费；当前不计 OP operator fee |
| `arbitrum-nitro` | Arbitrum | 父链成本已进入 Nitro gas；拆分记录时不能在总费用上二次相加 |
| `scroll` | Scroll | ETH 执行费 + 独立 Scroll L1 数据费 |

### 尚未启用链的费用模型复核

表中的 `fee_model` 是当前数据库配置，不是对所有未启用链的生产认证。已发现以下
配置在正式启用前必须重新做 receipt/oracle 验证：

- Robinhood Chain 官方说明其为 Arbitrum L2，但当前基线仍是 `standard`。

该链的 profile 当前全部禁用，因此不会进入发送路径。启用前应根据 RPC 实测结果决定
是否调整 `fee_model`，不能只根据“使用某技术栈”直接推断所有回执扩展字段完全相同。

## 官方核对资料

- [EIP-7702：Type 4、authorization list 与 relayer 赞助模型](https://eips.ethereum.org/EIPS/eip-7702)
- [OP Stack 费用：执行费、L1 数据费与 operator fee](https://docs.optimism.io/op-stack/transactions/fees)
- [Arbitrum Nitro：NitroGas 和费用以 ETH 计价](https://docs.arbitrum.io/nitro-whitepaper.pdf)
- [Scroll：L2 fee + L1 fee，均从 Scroll 上的 ETH 余额支付](https://docs.scroll.io/en/developers/transaction-fees-on-scroll/)
- [Linea：L1 发布和证明成本已包含在 Linea Gas 定价中](https://support.linea.build/getting-started/what-does-gas-pay-for)
- [Mantle：L1 交易使用 ETH，Mantle L2 交易使用 MNT](https://www.mantle.xyz/blog/announcements/bridging-on-mantle-mainnet)
- [Polygon PoS：POL 是原生 Gas 和质押资产](https://docs.polygon.technology/pos/concepts/tokens/pol)
- [Avalanche C-Chain：AVAX 与 EIP-1559 风格动态费用](https://build.avax.network/docs/rpcs/other/guides/txn-fees)
- [BSC Pascal：BEP-441 实现 EIP-7702](https://docs.bnbchain.org/announce/pascal-bsc/)
- [Berachain：BERA 是原生 Gas 资产](https://docs.berachain.com/general/tokens/bera)
- [Robinhood Chain：Arbitrum L2，使用 L2 上的 ETH](https://docs.robinhood.com/chain/connecting/)
- [Arbitrum Orbit：支持自定义原生 Gas Token](https://blog.arbitrum.io/an-quick-introduction-to-arbitrum-orbit/)

## 维护规则

修改任一 EVM 链的 `native_symbol`、`gas_policy`、`fee_model`、网络启用状态或
`evm_7702_config` 后，必须同步更新本表。上线前仍需通过目标链 RPC 检查实际
chain ID、最新区块 `baseFeePerGas`、交易类型支持、费用回执扩展字段以及 relayer
余额，不能只依赖静态文档。
