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

运行模型覆盖 27 条链、14 个链族：

| 链族 | 链 |
|------|-----|
| Bitcoin-like UTXO | BTC, LTC, DOGE, BCH |
| EVM | ETH, BNB, POLYGON |
| EVM L2 | ARBITRUM, OPTIMISM, BASE, AVAX_C, MANTLE, LINEA, SCROLL, UNICHAIN, HyperEVM |
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
1. web 端租户也要显示 gas 充值记录。，其他计划：oracle 语言机 集成，MPC方案集成，跨链桥集成，为每一个链建一份文档，介绍其生态，技术 活跃度 开发者社区 类库等等，尤其是 java 的
2. 启用链页面 生成地址 语义不准确，应当改为生成热提钱包，还要提示用户，这个地址就是 gas 地址，非 7702的地址会给充值地址转 gas，7702的默认使用此地址的原生币作为 gas。另外检查当前项目 7702的开启后，是否每次都进行了授权，这个好像是授权一次就可以吧。
3. web 端页面感觉响应卡顿，前端需要优化，系统需要简化部署，所有链的主流 tokens 都要配置好 支持管理员后台添加 tokens，验证合约和各项参数，动态化的。 检查已经集成的链 已经扫描到的记录，当有新的块产生时会不会继续更新已经扫描到的记录并且是否会更新状态 比如 tron
4. 接入 lisk 链及其 tokens方案 并启用 usdt usdc 如果有的话，还要配置 regtest 运行方案，调研页面部署合约 java 后端如何做，为 regtest 开发环境运行提供标准化的文档包括各个链如何启动，如何产生币，如何模拟真实生产环境。
5. robbinhood chain 接入
6. aave 接入
7. thorchain
8. okx 的链 x layer？
9. layer zero 调研
10. stellar xlm
11. chainlink link
12. zcash zec
13. cronos CRO
14. ripple usd RLUSD
15. Aster ASTER
16. SONIC chain
17. pulse chain
18. etherlink
19. bera chain
20. pulse chain
21. abstract chain
22. celo
23. hedera hashgraph
24. gnosis
25. soneium
26. ronin
27. world chain
28. blast
29. zksync
30. XDC NETWORK
31. STORY
32. KAIA
33. BOUNCEBIT
34. Metis
35. kava
36. moonriver
37. ApeChain
38. Beam
39. Chiliz chain
40. Conflux
41. IOTA evm
42. mode
43. merlin chain
44. Venom
45. Moonbeam
46. opBNB
47. fantom
48. Iotex
49. Canto
50. DuckChain
51. Astar
52. Aurora
53. Sei v2
54. Zeta
55. Core
56. Polygon zkEvm
57. ONUS
58. dogeChain evm
59. Wan chain
60. Boba Network
61. Shido Network
62. X layer
63. Energi
64. Shibarium
65. Elastos
66. velas
67. Atritrum Nova
68. Oasis Emerald
69. Harmony
70. ThunderCore
71. KCC
72. MAP Protocol
73. Neon Evm
74. Fuse
75. Degen Chain
76. KardiaChain
77. Telos
78. Taiko
79. b2 network
80. Manta Pacific
81. Sei network
82. Injective
83. GodWaken
84. wemix
85. evmos
86. Meter
87. vector smart chain
88. sophon
89. Apertum
90. peaq
91. zedxion smart chain
92. osmosis
93. starknet
94. flare Network
95. ethereumPoW
96. ethereumClassic
97. Fuel network
98. plasma
99. Juchain
100. somnia
101. monad
102. katana
103. ink
104. MegaEth
105. bevm
106. zklink nova
107. krown
108. kiteAi
109. tempo
110. Anubis
111. onyx
112. stable
