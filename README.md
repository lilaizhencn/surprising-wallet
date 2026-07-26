# Surprising Wallet

面向交易所、支付、电商等业务的多租户区块链托管基础设施。一套钱包服务可以同时服务多个租户。

[多租户托管模型](resources/docs/zh/multi-tenant-custody.md) ·
[API 契约](resources/docs/openapi/custody-v1.yaml) · [文档索引](resources/docs/README_CN.md)

![架构图](resources/docs/assets/architecture-diagram.svg)

## 核心流程

![核心流程](resources/docs/assets/system-code-flow-diagram.svg)

## 产品边界

Surprising Wallet 负责：

- 租户隔离的确定性充值地址；
- 扫链、确认后充值入账、链上余额、提现和对账；
- 租户预充值原生币的 Gas 账户，以及提现手续费预留与结算；
- API 请求签名、防重放、IP 白名单、Console 会话和审计日志；
- 带签名、完整尝试历史、自动重试和手动重放能力的充提 Webhook。

租户自己管理客户、商户、订单和内部余额规则。分配地址时，租户只需传入不透明的
`externalReference`；钱包基础设施不会创建或建模租户内部用户。

```text
租户凭证 + chain + externalReference -> 稳定的唯一充值地址
链上充值 -> tenant + externalReference -> 签名 Webhook
```

## 仓库

- 后端：当前仓库
- React + Ant Design Console：[surprising-wallet-web](https://github.com/lilaizhencn/surprising-wallet-web)

## 项目结构

```
surprising-wallet/
├── pom.xml              # 父 POM：版本、依赖管理、模块聚合
├── common/              # 共享基础设施
├── chain-sdks/           # Bitcoin-like 和 TRON 链 SDK
├── wallet-sig1/          # 第一签名服务
├── wallet-sig2/          # 第二签名服务
├── wallet-service/       # 链适配与业务逻辑
├── wallet-api/           # HTTP API 与定时任务
└── resources/docs/      # 文档、OpenAPI、数据库脚本
```

### 模块职责

| 模块 | 职责 |
|---|---|
| `common` | Redis 封装、链数据模型、钱包密钥配置、Ed25519 密钥派生、Ethereum 密码学工具、Spring 基础设施 |
| `chain-sdks` | Bitcoin-like 链和 TRON 链 SDK：多签地址、SegWit 交易、UTXO 选择、BIP32、gRPC 客户端、Protobuf 合约、ECKey 密码学 |
| `wallet-sig1` | 第一轮签名服务：对 BTC、BCH、LTC、DOGE 提现交易生成部分签名，轮询 Redis 队列获取待签任务 |
| `wallet-sig2` | 第二轮签名服务：对 BTC、BCH、LTC、DOGE、ETH、ERC20、TRON 交易完成最终签名并广播 |
| `wallet-service` | 链适配器（Bitcoin-like/EVM/TRON/Solana/TON/Aptos/Sui/XRP/Cardano/Polkadot/NEAR/Monero/HyperEVM/HyperCore）、扫链充值、账本管理、提现流程、UTXO 归集、Gas 估算 |
| `wallet-api` | Custody REST API、Console 管理后台、充值扫描任务、提现批处理、Gas 对账、Webhook 投递、EIP-7702 归集与提现、启动校验 |

运行模型覆盖 39 条链、14 个链族：

| 链族 | 链 |
|------|-----|
| Bitcoin-like UTXO | BTC, LTC, DOGE, BCH |
| EVM | ETH, BNB, POLYGON, BERACHAIN, GNOSIS, MONAD |
| EVM L2 | ARBITRUM, OPTIMISM, BASE, AVAX_C, MANTLE, LINEA, SCROLL, UNICHAIN, HyperEVM, CELO, WORLD_CHAIN, INK, TAIKO, SONEIUM, MODE, LISK, KATANA, MEGAETH |
| TRON | TRON |
| Solana | SOL |
| TON | TON |
| Aptos | APT |
| Sui | SUI |
| XRP | XRP |
| Cardano | ADA |
| Polkadot | DOT |
| NEAR | NEAR |
| Monero | XMR |
| HyperCore | HYPE |

实际启用的网络和资产由数据库控制。

## 本地启动

依赖 JDK 21、Maven、PostgreSQL 和 Redis。

```bash
# 初始化数据库
# psql -U wallet -d wallet -f resources/docs/db/surprising-wallet-init-pgsql.sql

# 编译并打包
mvn -pl wallet-api -am package

# 启动
java -jar wallet-api/target/wallet-api-1.0.0-SNAPSHOT.jar
```

Custody 必需密钥：

```text
SW_CUSTODY_SECRET_MASTER_KEY   32 字节 Base64 或 64 位十六进制密钥
SW_CUSTODY_PLATFORM_ADMIN_EMAIL
SW_CUSTODY_PLATFORM_ADMIN_PASSWORD
```

数据库、Redis、HTTP、CORS、链密钥配置和生产启动要求见
[启动与测试](resources/docs/zh/startup-and-testing.md)。

## 验证

```bash
# 运行测试
mvn -pl wallet-api -am test

# 编译全部模块
mvn compile
```

真实链测试需要有余额的测试地址，并受外部 RPC/Faucet 可用性影响，因此默认不运行。
参见[脚本与 Regtest](resources/docs/zh/scripts-and-regtest.md)。

## 接下来的计划 TODO
### 一、非链路接入类计划（先）
1. 规划并接入 oracle 预言机、MPC 方案、跨链桥；为每条已接入链建立独立文档（生态、技术活跃度、开发者社区、类库资料，尤其补齐 Java）。
2. 统一各环境配置入库（如 Token 配置）并保持环境一致性，新增配置更新机制，不允许配置散落，关于确认数之类的配置需要参考 binance okx 等交易所的确认策略。在 web 端管理员可以管理每个链和链上 tokens的配置和租户端可以查看链和 tokens的配置信息。这些数据由于基本不会变，所以直接在 initsql 里添加为初始化数据即可。
3. 强化后台 web 端链上合约治理：合约编辑、合约审计、合约部署、发布前安全评审。
4. 提升回调稳定性与可观测性，审查代码实现是否足够稳健和安全；明确 EVM7702 充提回调与审计策略，并在 API 文档中补充回调策略与失败恢复说明。

需要对以下所有的链查询最新的各个链的状态，确认是 L1 L2 以及每个链的原生 gas 币名字和简称和其他配置信息，以及官方网站和社区地址
是否有无法接入的也要标记出来并说明原因。是否开源等等。以及这些链上目前有发行什么。
### 二、待接入网络研究清单（2026-07-26 复核）

> 本节只记录网络级接入对象。资料以项目官方文档、官网或基金会公告为准；`L2/L3` 沿用项目官方定位，不等同于对其安全性或去中心化程度背书。
>
> `Gas 资产` 指普通用户直接签名交易时默认扣除的资产；Paymaster、代付、Gasless 和 Fee Abstraction 单独写在 `Gas 模式` 中。正式接入前仍必须复核 chain ID、主网 RPC、归档能力、最终性、重组处理、原生币精度、Token 标准、浏览器和节点运维方案。

状态约定：

- `已完成`：已融入现有链适配架构，并完成该链要求的本地功能测试。
- `候选`：存在可验证主网和官方开发资料，可进入技术评估。
- `专项`：不是标准 EVM 链，或地址、交易、Gas/最终性模型特殊，必须单独设计适配器。
- `尽调`：主网或资料存在，但成熟度、持续运营、节点来源或文档质量不足，不进入近期排期。
- `暂停`：被替代、目标网络含糊，或无法从官方资料确认可安全接入。

#### 已完成的 EVM 接入

| 网络 | 状态 | 本地测试资产 | 备注 |
|---|---|---|---|
| Ethereum、BNB Smart Chain、Polygon PoS | 已完成 | 原生币、USDC、USDT | 现有通用 EVM 与 Hardhat 矩阵 |
| Arbitrum One、Optimism、Base、Avalanche C-Chain | 已完成 | 原生币、链上已配置 ERC-20 | 现有通用 EVM 与 Hardhat 矩阵 |
| HyperEVM、Mantle、Linea、Scroll、Unichain | 已完成 | 原生币、链上已配置 ERC-20 | 现有通用 EVM 与 Hardhat 矩阵 |
| Berachain | 已完成 | BERA、USDC、USDT0 | Bepolia `80069`；EIP-7702 使用 Prague Hardhat 验证；主网和公开 RPC 默认关闭 |
| Gnosis Chain | 已完成 | XDAI、USDC、USDT | Chiado `10200`；EIP-7702 使用 Prague Hardhat 验证；主网和公开 RPC 默认关闭 |
| Celo | 已完成 | CELO、USDC、USDT | Celo Sepolia `11142220`；官方 RPC 与 Prague Hardhat 均验证 EIP-7702；仅使用 CELO 原生 Gas，CIP-64 Fee Abstraction 未启用 |
| Monad | 已完成 | MON、USDC、USDT0 | Testnet `10143`；官方 RPC 与 Prague Hardhat 均验证 EIP-7702；原生 MON 的 7702 归集因 10 MON reserve 规则保持关闭 |
| World Chain | 已完成 | ETH_WORLD、USDC | Sepolia `4801`；官方 RPC 与 Prague Hardhat 均验证 EIP-7702；官方资产表未列出 USDT |
| Ink | 已完成 | ETH_INK、USDC_E、USDT0 | Sepolia `763373`；官方 RPC 与 Prague Hardhat 均验证 EIP-7702；稳定币名称按官方合约区分 |
| Taiko | 已完成 | ETH_TAIKO | Hoodi `167013`；官方 RPC 与 Prague Hardhat 均验证 EIP-7702；主网实测约 2 秒出块；官方桥未公开可审计的静态稳定币合约清单，暂不配置 USDC／USDT |
| Soneium | 已完成 | ETH_SONEIUM、USDC_E、USDT | Minato `1946`；官方 RPC 与 Prague Hardhat 均验证 EIP-7702；主网两种稳定币均完成全链路测试 |
| Mode | 已完成 | ETH_MODE、USDC、USDT | Sepolia `919`；官方 RPC 与 Prague Hardhat 均验证 EIP-7702；主网两种稳定币均完成全链路测试 |
| Lisk | 已完成 | ETH_LISK、USDC_E | Sepolia `4202`；官方 RPC 与 Prague Hardhat 均验证 EIP-7702；官方审核资产表未列出 USDT |
| Katana | 已完成 | ETH_KATANA、USDC、USDT | Bokuto `737373`；官方 RPC 与 Prague Hardhat 均验证 EIP-7702；主网 Vault Bridge 稳定币均完成全链路测试 |
| MegaETH | 已完成 | ETH_MEGAETH、USDM | Carrot `6343`；官方 RPC 与 Prague Hardhat 均验证 EIP-7702；USDm 按链上实际使用 18 位精度 |

#### 1. Ethereum 生态 L2 / L3

| 网络 | 层级/网络形态 | Gas 资产 | Gas 模式 | 生态/执行环境 | 官方入口 | 状态与备注 |
|---|---|---|---|---|---|---|
| Celo | Ethereum L2（OP Stack） | CELO；协议支持 USDC、USDT、USDm 等白名单币 | 原生 Fee Abstraction，非 Paymaster | Ethereum / EVM | [Docs](https://docs.celo.org/home/protocol/celo-token) | 已完成；2025-03-26 已由独立 L1 迁移为 L2，旧表的 L1/L2 描述已纠正；已接入 Celo Sepolia `11142220` 与主网 `42220`，USDC、USDT 使用 CELO 支付 Gas，CIP-64 稳定币 Gas 暂不启用 |
| Abstract | Ethereum L2（ZK Stack） | ETH | 原生币 | Ethereum / EVM | [Docs](https://docs.abs.xyz/) | 候选；需验证原生账户抽象交易与普通 EOA 路径 |
| Soneium | Ethereum L2（OP Stack） | ETH | 原生币 | Ethereum / EVM | [Docs](https://docs.soneium.org/) | 已完成；主网 `1868`、Minato `1946`，Gas 为 ETH；接入官方列出的 Bridged USDC 与 USDT |
| ZKsync Era | Ethereum L2（ZK Rollup） | ETH | 原生币；支持 Paymaster | Ethereum / EVM | [Docs](https://docs.zksync.io/) | 候选；交易类型和最终性需专项测试 |
| Metis Andromeda | Ethereum L2（Optimistic Rollup） | METIS | 自定义原生 Gas 币 | Ethereum / EVM | [Docs](https://docs.metis.io/andromeda) | 候选；不能按 ETH Gas 的通用 OP 链假设处理 |
| Mode | Ethereum L2（OP Stack） | ETH | 原生币 | Ethereum / EVM | [Docs](https://docs.mode.network/) | 已完成；主网 `34443`、Sepolia `919`，Gas 为 ETH；接入官方列出的 USDC 与 USDT |
| Boba Ethereum | Ethereum L2（Optimistic Rollup） | ETH | 原生币 | Ethereum / EVM | [Docs](https://docs.boba.network/) | 候选；Boba 是多网络产品，接入项必须明确为 Ethereum 主网实例 |
| Arbitrum Nova | Ethereum L2（AnyTrust） | ETH | 原生币 | Ethereum / EVM | [Docs](https://docs.arbitrum.io/launch-arbitrum-chain/partials/config-data-posting-costs) | 候选；AnyTrust DA 与 Arbitrum One 风险模型不同 |
| Taiko | Ethereum L2（Based Rollup） | ETH | 原生币 | Ethereum / EVM | [Docs](https://docs.taiko.xyz/) | 已完成；主网 `167000`、Hoodi `167013`，旧 Hekla 已停用；EIP-7702 已验证 |
| Manta Pacific | Ethereum L2（模块化 Rollup） | ETH | 原生币 | Ethereum / EVM / 模块化 DA | [Docs](https://docs.manta.network/docs/manta-pacific/) | 候选；需核验当前结算与 DA 配置 |
| MegaETH | Ethereum L2 | ETH | 原生币 | Ethereum / MegaEVM | [Docs](https://docs.megaeth.com/) | 已完成；主网 `4326`、Carrot `6343`，EVM block 约 1 秒；官方 tokenlist 仅列 USDm，无 USDC／USDT；生产必须使用 MegaETH RPC 估算 Gas，禁止用标准 EVM 本地模拟值 |
| Lisk | Ethereum L2（OP Stack） | ETH | 原生币 | Ethereum / EVM | [Docs](https://docs.lisk.com/) | 已完成；主网 `1135`、Sepolia `4202`，Gas 为 ETH；接入官方审核的 Bridged USDC，未配置 USDT |
| World Chain | Ethereum L2（OP Stack） | ETH | 原生币；生态支持代付 | Ethereum / EVM | [Docs](https://docs.world.org/world-chain) | 已完成；主网 `480`、Sepolia `4801`，Gas 为 ETH；接入官方 USDC，未配置无官方依据的 USDT |
| Blast | Ethereum L2（Optimistic Rollup） | ETH | 原生币 | Ethereum / EVM | [Docs](https://docs.blast.io/) | 暂缓；主网 `81457` 与 Sepolia `168587773` 的官方 RPC 均未通过 EIP-7702 authorization intrinsic gas 门禁（仍返回 `21000`）；USDC／USDT 经官方桥统一映射为 USDB，不应按独立稳定币接入 |
| ApeChain | Arbitrum Orbit L3 | APE | 自定义原生 Gas 币 | Ethereum / Arbitrum / EVM | [Docs](https://docs.apechain.com/) | 候选；旧表的普通 L2 分类已纠正为 L3 |
| X Layer | Ethereum L2（Polygon CDK ZK） | OKB | 自定义原生 Gas 币 | Ethereum / EVM | [Docs](https://web3.okx.com/xlayer/docs/developer/quick-start/about-x-layer) | 候选 |
| Degen Chain | Base 上的 Arbitrum Orbit L3 | DEGEN | 自定义原生 Gas 币 | Ethereum / Base / EVM | [Docs](https://docs.degen.tips/) | 候选；旧表的普通 L2 分类已纠正为 L3 |
| Robinhood Chain | Ethereum L2（Arbitrum） | ETH | 原生币 | Ethereum / EVM / RWA | [Docs](https://docs.robinhood.com/chain/connecting/) | 候选；主网已上线，不再归入“未确认” |
| Shibarium | Ethereum L2 | BONE | 自定义原生 Gas 币 | Ethereum / EVM / Shiba | [Docs](https://docs.shib.io/) | 候选；Gas 是 BONE，不是 SHI |
| Katana | Ethereum L2（Agglayer CDK OP Stack） | ETH | 原生币 | Ethereum / EVM / DeFi | [Docs](https://docs.katana.network/katana/technical-reference/network-information/) | 已完成；主网 `747474`、Bokuto `737373`，Gas 为 ETH；接入官方 Vault Bridge USDC 与 USDT |
| Ink | Ethereum L2（OP Stack） | ETH | 原生币 | Ethereum / EVM | [Docs](https://docs.inkonchain.com/) | 已完成；主网 `57073`、Sepolia `763373`，Gas 为 ETH；官方资产为 USDC.e 与 USDT0，不按普通 USDC／USDT 混记 |
| Sophon | Ethereum L2 Validium（ZK Stack） | SOPH | 自定义原生 Gas 币 | Ethereum / ZKsync Elastic Chain / EVM | [Docs](https://docs.sophon.xyz/tokenomics/soph) | 候选；DA 使用 Avail，不能等同标准 ZK Rollup |
| Starknet | Ethereum L2（ZK Rollup） | STRK | 自 v0.14.0 起只允许 STRK 付费 | Ethereum / CairoVM | [Docs](https://docs.starknet.io/learn/protocol/strk) | 专项；旧资料中的 ETH/STRK 双 Gas 已过期，地址和交易模型非 EVM |
| Fuel Ignition | Ethereum L2（Optimistic Rollup） | ETH | FuelVM 基础资产 | Ethereum / FuelVM / UTXO | [Docs](https://docs.fuel.network/guides/user-quickstart/) | 专项；不是 EVM，使用 Fuel 地址、UTXO 和 GraphQL RPC |

#### 2. 其他 L2、扩容网络与托管执行环境

| 网络 | 层级/网络形态 | Gas 资产 | Gas 模式 | 生态/执行环境 | 官方入口 | 状态与备注 |
|---|---|---|---|---|---|---|
| opBNB | BNB Smart Chain L2（OP Stack） | BNB | 原生币 | BNB Chain / EVM | [Docs](https://docs.bnbchain.org/bnb-opbnb/) | 候选 |
| Etherlink | Tezos Smart Rollup L2 | XTZ | 原生币 | Tezos / EVM | [Docs](https://docs.etherlink.com/) | 候选；结算层不是 Ethereum |
| Aurora | NEAR 上的 EVM 执行网络 | ETH | ETH 通过 relayer 支付 Gas | NEAR / EVM | [Docs](https://doc.aurora.dev/dev-reference/network-endpoints/) | 候选；旧表写成 NEAR Gas 已纠正 |
| IOTA EVM | IOTA 主网上的 EVM L2 | IOTA | 原生币 | IOTA / EVM | [Official](https://www.iota.org/products/evm) | 候选；与 IOTA Rebased L1 的地址/资产模型分开接入 |
| Merlin Chain | Bitcoin L2 | BTC | BTC 原生 Gas | Bitcoin / EVM | [Official](https://merlinchain.io/) | 候选；跨链 BTC 映射、精度和提现最终性需专项审计 |
| B² Network | Bitcoin L2（ZK Rollup + Bitcoin DA/验证） | BTC | BTC 原生 Gas | Bitcoin / EVM | [Docs](https://docs.bsquared.network/for-developers/basic_information) | 候选 |
| BEVM | Bitcoin L2 | BTC | BTC 原生 Gas | Bitcoin / EVM | [Docs](https://docs.bevm.io/introduction/what-is-bevm) | 候选 |
| DuckChain | TON 对齐的 Arbitrum Orbit 执行层 | TON | TON 原生 Gas，链上为 18 位精度 | TON / EVM / Arbitrum Orbit | [Docs](https://diary.duckchain.io/2.-users-and-developers/2.3-developer-hub/2.3.2-builder-guide/gas) | 专项；TON 在 DuckChain 与 TON 主网精度不同 |
| Neon EVM | 部署在 Solana 上的 EVM 程序 | NEON | Operator 接收 NEON Gas | Solana / EVM | [Docs](https://docs.neonevm.org/docs/about/neon_ecosystem/) | 专项；不是独立 L1，不能沿用标准 EVM 节点假设 |
| Oasis Emerald | Oasis ParaTime 执行环境 | ROSE | 原生币 | Oasis / EVM ParaTime | [Docs](https://docs.oasis.io/build/tools/other-paratimes/emerald/) | 专项；不是 L2，最终性来自 Oasis 共识层 |

#### 3. 独立 EVM L1、侧链与应用链

| 网络 | 层级/网络形态 | Gas 资产 | Gas 模式 | 生态/执行环境 | 官方入口 | 状态与备注 |
|---|---|---|---|---|---|---|
| Cronos Chain | 独立 L1 | CRO | 原生币 | Cosmos SDK / EVM | [Docs](https://docs.cronos.org/) | 候选 |
| Sonic | 独立 L1 | S | 原生币 | EVM | [Docs](https://docs.soniclabs.com/) | 候选；Fantom 的后续主网络 |
| PulseChain | 独立 L1（Ethereum fork） | PLS | 原生币 | EVM | [Official](https://pulsechain.com/) | 候选；生态和节点集中度需额外尽调 |
| Berachain | 独立 L1 | BERA | 原生币 | Cosmos SDK / EVM | [Docs](https://docs.berachain.com/) | 已完成；主网 `80094`、Bepolia `80069`，支持 EIP-7702；稳定币按实际资产接入 USDC 与 USDT0 |
| Gnosis Chain | EVM 侧链 | xDAI | 稳定币作为原生 Gas | Ethereum 生态 / EVM | [Docs](https://docs.gnosischain.com/) | 已完成；主网 `100`、Chiado `10200`，Gas 资产 xDAI，支持 EIP-7702；接入 USDC 与 USDT |
| Canto | 独立 L1 | CANTO | 原生币 | Cosmos SDK / EVM | [Docs](https://docs.canto.io/) | 候选 |
| ZetaChain | 独立 L1 | ZETA | 原生币 | Cosmos SDK / EVM / Omnichain | [Docs](https://www.zetachain.com/docs/) | 候选 |
| Core | 独立 L1 | CORE | 原生币 | Bitcoin-aligned / EVM | [Docs](https://docs.coredao.org/) | 候选；不是 Bitcoin L2 |
| Wanchain | 独立 L1 | WAN | 原生币 | EVM / 跨链 | [Docs](https://docs.wanchain.org/) | 候选 |
| Elastos Smart Chain | Elastos EVM 侧链 | ELA | 原生币 | Elastos / EVM | [Docs](https://docs.elastos.net/) | 专项；需区分 Elastos Mainchain 与 ESC |
| Velas | 独立 L1 | VLX | 原生币 | EVM | [Docs](https://docs.velas.com/) | 候选；旧表的 `VELAS` Gas 符号已纠正为 `VLX` |
| Harmony | 独立 L1 | ONE | 原生币 | EVM / Sharding | [Docs](https://docs.harmony.one/) | 尽调；历史桥安全事件和生态活跃度需评估 |
| ThunderCore | 独立 L1 | TT | 原生币 | EVM | [Docs](https://docs.thundercore.com/) | 候选 |
| KCC | 独立 EVM 链 | KCS | 原生币 | EVM / KuCoin 生态 | [Docs](https://docs.kcc.io/) | 候选 |
| KardiaChain | 独立 L1 | KAI | 原生币 | EVM | [Docs](https://docs.kardiachain.io/) | 候选 |
| Telos EVM | Telos 上的 EVM 执行环境 | TLOS | 原生币 | Antelope / EVM | [Docs](https://docs.telos.net/) | 专项；需明确只接 Telos EVM，不混用原生 Telos 地址 |
| Evmos | 独立 L1 | EVMOS | 原生币 | Cosmos SDK / EVM | [Docs](https://docs.evmos.org/) | 候选 |
| Meter | 独立 L1 | MTR | MTR 支付 Gas；MTRG 用于治理/质押 | EVM | [Docs](https://docs.meter.io/) | 专项；双币模型 |
| EthereumPoW | 独立 PoW fork | ETHW | 原生币 | EVM | [Official](https://ethereumpow.org/) | 尽调；旧表的 `ETH` 已纠正为 `ETHW`，需评估节点和生态持续性 |
| Ethereum Classic | 独立 PoW L1 | ETC | 原生币 | EVM | [Official](https://ethereumclassic.org/) | 候选；确认数和 51% 攻击风险参数需独立设置 |
| Monad | 独立 L1 | MON | 原生币 | EVM | [Docs](https://docs.monad.xyz/) | 已完成；主网 `143`、Testnet `10143`，官方 token list 支持 USDC 与 USDT0；EIP-7702 ERC-20 代付归集可用，原生 MON 归集走普通未委托账户路径 |
| Fuse Network | 独立 EVM 链 | FUSE | 原生币 | EVM / Payments | [Docs](https://docs.fuse.io/) | 候选；旧表的 L2 分类已纠正 |
| Shido Network | 独立 L1 | SHIDO | 原生币；部分资格账户支持 Gasfree | Cosmos SDK / EVM / WASM | [Docs](https://docs.shido.io/untitled/shido/about) | 候选；旧表的 L2 分类已纠正 |
| WEMIX3.0 | 独立 L1 | WEMIX | 原生币 | EVM / Gaming | [Docs](https://docs.wemix.com/) | 候选；旧表的 L2 分类已纠正 |
| Somnia | 独立 L1 | SOMI | 原生币 | EVM / Gaming / Metaverse | [Docs](https://docs.somnia.network/developer/network-info) | 候选；旧表的 Gas 名称已纠正为 `SOMI` |
| BounceBit | 独立 PoS L1 | BB | 原生币 | EVM / BTCFi / CeDeFi | [Docs](https://docs.bouncebit.io/) | 候选；不是 Bitcoin L2 |
| Ronin | EVM 应用链/侧链 | RON | 原生币 | EVM / Gaming | [Docs](https://docs.roninchain.com/) | 候选；旧表的 L2 分类已纠正 |
| XDC Network | 独立 L1 | XDC | 原生币 | EVM / Enterprise / RWA | [Docs](https://docs.xdc.community/) | 候选；旧表的 L2 分类已纠正 |
| Beam | Avalanche L1（原 Subnet） | BEAM | 原生币 | Avalanche / EVM / Gaming | [Docs](https://docs.onbeam.com/service/beam-chain) | 候选；不是 Ethereum L2 |
| Chiliz Chain | 独立 L1 | CHZ | 原生币 | EVM / Sports | [Docs](https://docs.chiliz.com/) | 候选 |
| Conflux | 独立 L1，Core Space + eSpace | CFX | 原生币 | Conflux / EVM eSpace | [Docs](https://doc.confluxnetwork.org/) | 专项；Core Space 与 eSpace 地址及交易模型不同 |
| IoTeX | 独立 L1 | IOTX | 原生币 | EVM / DePIN | [Docs](https://docs.iotex.io/) | 候选 |
| Kaia | 独立 L1 | KAIA | 原生币 | EVM / 亚洲支付生态 | [Docs](https://docs.kaia.io/) | 候选；旧 `KLAY` 名称已迁移为 `KAIA` |
| MAP Protocol / MAPO | 独立 L1 | MAPO | 原生币 | EVM / Omnichain | [Docs](https://docs.mapprotocol.io/) | 候选；名称统一为 MAP Protocol，链上币符号为 MAPO |
| Kite AI | 独立 EVM 链 | KITE | 原生币 | EVM / AI Agent Payments | [Docs](https://docs.gokite.ai/kite-chain/1-getting-started/network-information) | 候选；主网 chain ID 2366，已不再是“仅测试网” |
| Tempo | 独立支付 L1 | 受支持的 USD 稳定币 | 无原生波动币；TIP-20 稳定币直接付费 | EVM / Payments / Stablecoin | [Docs](https://docs.tempo.xyz/protocol/fees) | 专项；钱包、签名器和 Gas 余额判断不能假设单一 native token |
| Stable | 独立支付 L1 | USDT0 | 稳定币作为原生 Gas | EVM / Payments / Stablecoin | [Docs](https://docs.stable.xyz/en/architecture/usdt-specific-features/usdt-as-gas-token) | 专项；v1.2 已由 `gUSDT` 切换为 `USDT0` |
| Plasma | 独立支付 L1 | XPL | 原生 XPL；特定 USDT 转账可由协议 Paymaster 代付 | EVM / Payments / Stablecoin | [Docs](https://www.plasma.to/docs/plasma-chain/network-information/differences-ethereum-and-plasma) | 候选；旧表误判为“仅协议/规范”，现已恢复为网络候选 |
| Story | 独立 L1 | IP | 原生币 | Cosmos/CometBFT + EVM / IP | [Docs](https://docs.story.foundation/introduction) | 候选；不是非 EVM 网络 |
| Energi | 独立 L1 | NRG | 原生币 | EVM | [Docs](https://wiki.energi.world/) | 尽调；需复核当前节点版本和生态活跃度 |
| ONUS Chain | 独立 EVM 链 | ONUS | 原生币 | EVM | [Docs](https://docs.onuschain.io/chain-setup) | 尽调；官方文档存在，但更新频率和节点独立性需评估 |
| Flare | 独立 L1 | FLR | 原生币 | EVM / Data protocols | [Docs](https://dev.flare.network/) | 候选 |

#### 4. Polkadot / Kusama Parachain

| 网络 | 层级/网络形态 | Gas 资产 | Gas 模式 | 生态/执行环境 | 官方入口 | 状态与备注 |
|---|---|---|---|---|---|---|
| Astar Network | Polkadot Parachain | ASTR | 原生币 | Polkadot / Substrate / EVM + WASM | [Docs](https://docs.astar.network/docs/learn/astar/) | 专项；只接入 Astar Polkadot Parachain，不包含 Astar zkEVM，旧重复项已合并 |
| Moonbeam | Polkadot Parachain | GLMR | 原生币 | Polkadot / Substrate / EVM | [Docs](https://docs.moonbeam.network/) | 专项；H160 EVM 与 Substrate 账户/跨链资产需统一映射 |
| Moonriver | Kusama Parachain | MOVR | 原生币 | Kusama / Substrate / EVM | [Docs](https://docs.moonbeam.network/builders/get-started/networks/moonriver/) | 专项；不是 Moonbeam 测试网 |
| peaq | Polkadot Parachain | PEAQ | 原生币 | Polkadot / Substrate / EVM / DePIN | [Docs](https://docs.peaq.network/) | 专项 |

#### 5. Cosmos 生态主权 L1

| 网络 | 层级/网络形态 | Gas 资产 | Gas 模式 | 生态/执行环境 | 官方入口 | 状态与备注 |
|---|---|---|---|---|---|---|
| Sei | 独立 L1 | SEI | 原生币 | Cosmos SDK / EVM | [Docs](https://docs.sei.io/) | 候选；需明确 EVM 地址与 Cosmos 地址映射 |
| Kava | 独立 L1 | KAVA | 原生币 | Cosmos SDK / EVM | [Docs](https://docs.kava.io/) | 候选；Cosmos Co-Chain 与 EVM Co-Chain 需分别测试 |
| Injective | 独立 L1 | INJ | 原生币 | Cosmos SDK / WASM / MultiVM | [Docs](https://docs.injective.network/) | 专项；不能只按 EVM 链处理 |
| Osmosis | 独立 L1 | OSMO | 原生币 | Cosmos SDK / CosmWasm | [Docs](https://docs.osmosis.zone/) | 专项；非 EVM，需 Cosmos SDK 适配 |

#### 6. 其他非 EVM L1 / DLT

| 网络 | 层级/网络形态 | Gas/手续费资产 | 手续费模式 | 生态/执行环境 | 官方入口 | 状态与备注 |
|---|---|---|---|---|---|---|
| Stellar | 独立 L1 | XLM | 基础费 + 最低余额 | Stellar / Soroban | [Docs](https://developers.stellar.org/) | 专项；Memo、资产 issuer、最低余额和 Soroban 资源费均需支持 |
| Zcash | 独立 PoW L1 | ZEC | 原生币 | UTXO / Shielded | [Docs](https://zcash.readthedocs.io/) | 专项；透明与屏蔽地址、Viewing Key 和扫描成本需先定范围 |
| XRP Ledger | 独立 L1 | XRP | 销毁式基础费 + 账户储备 | XRPL | [Docs](https://xrpl.org/docs.html) | 专项；Destination Tag、Trust Line、Issued Currency 不能按普通账户链处理 |
| Hedera | 公共 Hashgraph DLT | HBAR | 费用以 USD 定价、以 HBAR 扣除 | Hedera / EVM services | [Docs](https://docs.hedera.com/) | 专项；账户 ID、别名地址、Token Service 和 EVM 地址并存 |
| Venom | 独立异步多链网络 | VENOM | 原生币 | TVM / Workchains | [Docs](https://docs.venom.foundation/) | 专项；非 EVM，地址和异步消息模型独立实现 |

#### 7. 低成熟度或资料质量不足，先尽调

| 网络 | 当前可确认信息 | Gas 资产 | 官方入口 | 结论/前置条件 |
|---|---|---|---|---|
| Vector Smart Chain | 项目宣称独立 EVM L1 | VSG | [Official](https://vectorsmartgas.com/) | 尽调；缺少稳定、完整的官方开发文档和节点运维资料，不进入近期排期 |
| Zedxion Smart Chain | 存在 EVM 浏览器与简略文档 | ZEDXION（需链上复核符号/精度） | [Docs](https://docs.zedscan.net/) | 尽调；文档不足，先核验主网 RPC、节点实现、创世配置和持续出块 |
| Krown Network | 官网宣称 PoS L1 已上线 | KROWN | [Official](https://krown.network/) | 尽调；需补齐可审计的开发文档、客户端源码、节点与网络参数 |
| Onyx / XCN Ledger / Goliath | 官方资料同时存在 Base 上的 Orbit L3 与新的 Goliath 网络描述 | XCN | [Onyx Docs](https://docs.onyx.org/readme/about-onyx) / [Goliath Docs](https://docs.goliath.net/developer-guide/getting-started) | 暂停排期；必须先确定目标是旧 L3 还是 Goliath，并核验主网 chain ID；Goliath 文档还存在 EVM 内部 8 位、JSON-RPC 18 位的特殊精度说明 |
| Apertum | Avalanche 基础设施上的 EVM L1 | APTM | [Official](https://apertum.io/developers) | 尽调；需验证 Avalanche L1 节点、验证者集合、原生币精度和归档能力 |
| Anubis Chain | 新上线的隐私 EVM L1；浏览器显示 DAI 支付 Gas | DAI | [Official](https://anubischain.ai/index.html) | 尽调；隐私交易、普通 EVM 交易边界及稳定币 Gas 实现需要源码和节点级验证 |

#### 8. 暂停或不再新增接入

| 网络 | 原分类 | 当前结论 | 官方依据/备注 |
|---|---|---|---|
| Fantom Opera | 独立 L1 | 不新增接入，优先 Sonic | [迁移说明](https://docs.soniclabs.com/migration/overview)：Opera 仍运行，但后续开发已转向 Sonic |
| Dogechain | Dogecoin 侧链 | 暂停 | 官方资料长期未形成可持续的节点、最终性和运维保证；历史 Gas 为 wDOGE，恢复评估前先证明主网持续性 |
| zkLink Nova | 聚合 zkEVM L3 | 暂停 | [Official](https://zk.link/)；当前持续运营、官方节点和长期维护信息不足，不能仅凭旧发布材料接入 |
| Godwoken | Nervos L2 | 暂停 | [Nervos 报告](https://www.nervos.org/assets/pdfs/Nervos_Foundation_Annual_Report_2023.pdf) 已说明原团队解散；即使社区链仍运行也不满足近期接入条件 |
| Juchain | 未确认 | 暂停 | 未找到可验证的官方开发文档、主网参数和持续运维入口；避免与同名资产/营销页面混淆 |

#### 9. 不属于“链接入”的协议或资产

| 名称 | 类型 | 正确归属 | 官方入口 |
|---|---|---|---|
| Chainlink | 去中心化预言机网络/跨链基础设施 | 按具体宿主链集成合约与节点服务，不作为独立充值链 | [Docs](https://docs.chain.link/) |
| RLUSD | 稳定币资产 | 分别作为 XRP Ledger Issued Currency、Ethereum ERC-20 等资产接入 | [Docs](https://docs.ripple.com/products/stablecoin/developer-resources/rlusd-on-the-xrpl) |
| Aave | 多链借贷协议 | 属于 DeFi 协议集成，不是钱包底层链；其部署网络可反向用于链优先级参考 | [Official deployments](https://aave.com/help/aave-101/accessing-aave) |

#### 10. 建议接入顺序

1. 先接标准 EVM 且只有单一原生 Gas 资产的活跃主网，复用现有账户链扫描、确认、充值和提现能力。
2. 再接自定义 Gas 的 EVM L2/L3，以及 `xDAI`、`USDT0`、`BTC`、`TON` 等非 ETH Gas 网络，逐链校验精度和费用估算。
3. 然后接 Celo、Tempo、Stable、Plasma、Meter 等 Fee Abstraction、稳定币 Gas 或双币模型网络，禁止用“native balance 即 Gas balance”的统一假设。
4. 再接 Neon、Aurora、Oasis Emerald、Polkadot Parachain、Conflux 双空间等特殊执行环境。
5. 最后接 Starknet、Fuel、Cosmos SDK、Stellar、XRPL、Hedera、Zcash、Venom 等非标准地址或非 EVM 交易模型。
6. `尽调` 与 `暂停` 表中的网络不得进入开发排期，除非先补齐官方主网参数、客户端源码、节点/归档方案、最终性模型和持续运维证据。
