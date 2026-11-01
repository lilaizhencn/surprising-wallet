# AGENTS.md

Surprising Wallet 涉及链上资产和钱包账户，所有改动必须保守、可审计、可回滚。

## 项目边界

- 这是钱包和链相关项目，包含 Java 后端模块、currency SDK、EVM fork / Hardhat 工程。
- 不要把 exchange 后端测试默认依赖 wallet，除非任务明确涉及充值、提现、链上到账或钱包账务。
- 不提交私钥、助记词、真实 token、RPC 密钥、生产配置。

## 模块职责与分层约束

### wallet-api（Spring MVC 单体应用）

`wallet-api` 是唯一承载钱包业务的 Spring Boot MVC 应用，合并原 `wallet-service` 的业务、领域、数据访问和链集成代码。
合并不改变原 API Web 层职责：Controller、Job 和 Web 配置仍是应用入口与调度入口，业务逻辑通过内部 Service 委托完成。

| 层次 | 职责与约束 | 典型位置 |
|---|---|---|
| Web 层 | REST 参数校验、委托、HTTP 异常映射、认证过滤和 Web DTO；保持原有 Controller/Job 行为 | `controller/**`、`job/**`、`exception/**`、`filter/**`、Web `model/**` |
| 应用层 | 业务用例、工作流编排、运行时开关和定时任务实际调用的 Service | `service/**` |
| 领域与链集成层 | 领域模型、链适配器、RPC 客户端、链上协调器 | `chain/**`、`account/coordinator/**`、`custody/gateway/**`、业务 `model/**` |
| 持久化层 | PostgreSQL/JDBC 仓储、账本和链配置数据访问 | `repository/**` |
| 应用配置层 | Web、安全、Jackson、数据源、链 RPC 和钱包运行时配置 | `config/**` |

Controller 只负责请求边界和委托，Job 只负责 `@Scheduled` 调度编排；业务流程、链操作和数据访问必须位于内部应用/领域/基础设施层。
业务层不得反向依赖 Controller 或 Job，不得把 Servlet API、Web 异常或 HTTP 状态映射带入领域和持久化代码。

### Repository 单表约束

- 每个 `@Repository` 类只允许对应一张数据库表，类名和表名必须一一对应；禁止在同一个 Repository 中混合访问多个业务表。
- 禁止在 Service、Controller、Job、Gateway 或 Coordinator 中注入 `JdbcTemplate`、拼接 SQL 或执行数据库查询。
- 跨表业务查询必须由 Service 分别调用对应的单表 Repository，再在内存中完成组合、过滤和业务判定；禁止通过 JOIN、子查询或 `FROM` 多表查询绕过边界。
- 一个事务可以由 Service 编排多个单表 Repository 的写操作，但事务边界和业务规则必须留在 Service。
- 新增或拆分表时，必须同时新增对应单表 Repository、架构边界测试，并更新本文件和架构文档。

### 依赖方向

```
wallet-api → common, chain-sdks
common → chain-sdks
独立 wallet-mpc-signer → cb-mpc / Monero 原生协议（mTLS，不访问钱包 PostgreSQL）
```

- `wallet-api` 内部按 MVC 分层协作，不再通过 `wallet-service` 模块传递业务类。
- `common` 和 `chain-sdks` 不得依赖 `wallet-api`；独立原生签名服务不依赖任何 Java 模块，也不访问钱包数据库。
- 项目不再保留 `wallet-service` 模块、POM 或独立构建入口。
- @Scheduled 只允许在 wallet-api 中；原生 MPC 持有者独立运行，不使用 Java 调度。

### job 包划分

```
wallet-api/job/
├── deposit/       ← 充值扫描（UTXO 链逐块扫描 + 交易写入）
├── withdraw/      ← 提现（UTXO 提现+归集合一批处理、独立确认、签名恢复、费率、RBF、广播）
├── custody/       ← 托管维护（Gas 对账、Webhook 派发、安全清理、提现对账、资产找回）
├── collection/    ← 归集（Account-Chain 通用归集 + EVM EIP-7702 批量归集/提现）
└── devfaucet/     ← 开发环境水龙头
```

### 线程池

唯一可执行 JAR 为 wallet-api，固定承载 API 与业务调度。钱包签名由三个独立原生 MPC 持有者完成；Java 只配置 mTLS 客户端，不配置钱包 Seed 或本地私钥。每类 `@Scheduled` 必须配置独立线程池：

- **wallet-api**：按 job 类别分池，`SchedulingConfig.java` 中定义 `@Bean ThreadPoolTaskScheduler`（custody/evm7702/account/deposit/withdraw 各一个）。业务 Service 不直接声明 `@Scheduled`。

禁止所有 `@Scheduled` 共用一个默认线程池。

### 命名约定

- Job 类名不要 `Abstract` 前缀：使用 `UtxoDepositScanJob`（不是 `AbstractUtxoDepositScanJob`）、`BtcUtxoBatchJob`。
- 类名体现职责：UTXO 批处理包含提现+归集，用 `*UtxoBatchJob`（不是 `*WithdrawJob`）。
- Account-Chain 通用 job 使用简短模式：`AccountChain{DepositScan,Collection,Monero,WithdrawalProcess,WithdrawalConfirm}Job`。

### MVC 入口边界

- `wallet-api/job/**` 只负责调度、节流、防并发和异常隔离；选币、状态流转、队列消费、RPC 调用和审计必须由 `service/**` 承担。
- `wallet-api/controller/**` 只负责 HTTP 参数校验、身份上下文和响应映射，不得直接依赖 `repository/**`。
- Service 使用构造器注入；可选基础设施依赖使用 `Optional<T>` 表达，不使用可变字段注入。

## SaaS 改造原则

- 项目当前处于开发阶段，以 SaaS 多租户系统改造为唯一目标，不考虑历史版本兼容性。
- 修改功能时直接收敛到目标设计；不要为了兼容旧实现增加 `if/else`、双读、双写、旧接口兜底或临时适配层。
- 旧接口、旧表结构和旧实现不符合目标架构时，直接重构或删除，不保留并行实现。
- 优先简化项目复杂度，消除重复代码、隐式规则、配置分叉和不必要的抽象。
- 保持模块职责、依赖方向、命名和数据流清晰；功能必须完整闭环，不保留半实现状态。
- 以租户隔离为系统边界，业务数据、配置、权限、密钥、钱包账户和审计记录必须明确归属租户。
- 统一权限、状态、错误、接口和配置模型，资金操作继续满足幂等、审计、对账和追踪要求。
- 开发阶段允许直接调整接口和数据库结构；除非任务明确要求，否则不新增兼容迁移路径。
- SaaS 重构不得降低资金与链上安全要求，涉及敏感数据和余额变化时仍需保守、可审计、可回滚。

## 资金和链上安全

- 充值入账与区块重组由 `DepositService` 显式编排；`CustodyDepositProjectionService` 是同事务中的必需步骤，不得恢复可选观察者或空回调列表。充值记录、余额、租户投影、赤字恢复及事件持久化必须一起提交或回滚。
- 链业务直接注入对应单表仓储，不恢复统一链数据门面。`CollectionPlanningService` 计算可归集金额与租户归集地址，`UtxoSelectionService` 在调用方事务内校验归属并选择/锁定输入，`WithdrawalClaimService` 检查重组赤字后领取提现，`EvmBatchOwnershipService` 防止批次与单笔流程重复确认。账户序号由单表原子 UPSERT 预留，不依赖转发层事务。
- 已观察充值的入账、补确认和投影不受新任务开关或租户关闭链影响；这些开关只控制新业务入口。充值缺失托管地址归属时整笔回滚，不得静默跳过投影后保留余额变化。

- `WithdrawalSettlementService` 统一确认租户提现：订单归属、状态与交易哈希校验、冻结扣款和条件确认必须在同一事务内完成；竞争或状态变更失败时回滚扣款，不恢复无租户结算入口。
- `CustodyEventService` 统一持久化领域事件和 Webhook 投递记录，与调用方事务一起提交或回滚；API 来源才生成 Webhook，Console 来源仍记录事件。
- 充值、提现、归集、手续费、链上确认、重放、幂等、回调都要有明确审计路径。
- 任何余额变化都要能通过流水对账。
- 链上交易要区分 pending、confirmed、failed、reorg 相关状态。
- 不要用浮点数处理金额，优先使用整数最小单位或 BigDecimal。

## 架构图与数据库基线同步（强制）

每次修改代码后都必须做文档和数据库基线影响判断；存在影响时，同一批改动必须同步对应文件，不允许把同步工作留到后续任务。

### 架构图同步

- 修改模块职责、模块依赖方向、包分层、Job 分类、线程池、Controller/Service/Repository/Gateway 边界时，必须同步：
  - `resources/docs/zh/architecture.md`
  - `resources/docs/assets/architecture-diagram.svg`
- 修改充值、确认、入账、提现、归集、Gas、签名、Webhook、重组或租户映射等核心数据流时，必须同步：
  - `resources/docs/zh/system-code-flow.md`
  - `resources/docs/assets/system-code-flow-diagram.svg`
- 图、图对应的文字说明和实际代码必须表达同一条依赖关系、调用方向和状态流；不得只修改 Markdown 或只修改 SVG。

### init-sql 同步

- `resources/docs/db/surprising-wallet-init-pgsql.sql` 是从空数据库初始化当前目标架构的唯一数据库基线。
- 新增、删除或修改表、字段、类型、默认值、索引、唯一约束、外键、状态约束、序列、初始化配置或种子数据时，必须在同一批改动中同步 init-sql。
- 代码开始读取或写入新的表、字段、状态或配置项之前，init-sql 必须已经包含对应定义。
- 删除代码路径或废弃数据模型时，应直接从 init-sql 移除不再属于目标架构的结构，不保留历史兼容结构。
- 文档中的表结构、配置示例和链/Token/RPC 初始化数据必须与 init-sql 一致。

### 完成条件

- 受影响但未同步架构图文或 init-sql 的代码改动视为未完成，不得提交。
- 如果确认本次改动不影响模块/流程架构和数据库基线，可以不修改上述文件，但交付说明中必须明确写出影响判断结果。
- 代码验证前先完成同步；验证发现结构或流程变化时，必须回到对应图文和 init-sql 补齐后再提交。

## 验证

- Java 模块优先使用 `mvn -pl <module> -am test` 或 `mvn test`。
- `evm-fork` 相关改动优先检查 Hardhat 脚本和本地链。
- 生产相关配置变更必须说明风险和回滚方式。
- 数据库开发与测试强制使用开发机已经安装并运行在 `127.0.0.1:5432` 的 PostgreSQL 18；禁止通过 Docker、Testcontainers、嵌入式数据库或额外进程启动任何独立数据库实例。
- 代码、测试脚本、文档和接入示例均不得创建或演示独立数据库实例；数据库示例必须复用上述本机 PostgreSQL 18，并使用隔离的临时测试数据库。
- 并发测试需要隔离数据时，只能在上述同一个本机 PostgreSQL 18 实例内创建名称明确的临时测试数据库，并在测试结束后删除；禁止重置或清空开发库、生产库及任何非测试库。

## 提交

- 通过验证后 commit and push。
- 不提交 `.idea/`、`logs/`、本地 key 文件、链数据目录、构建产物。

## PostgreSQL queue infrastructure

- PostgreSQL 18 + PGMQ 1.11.1 is the sole general middleware dependency.
- `common.queue.PgmqClient` is an extension adapter for PGMQ-owned queue/archive tables, not a business Repository. It must never query wallet business tables.
- `ChainFeeRateRepository` maps only to `chain_fee_rate`; network fee quotes are shared network metadata.
- Initial UTXO signing enqueue, its durable request receipt on `chain_signing_transaction`, order state and UTXO locks share one transaction; no separate wallet Outbox table or dispatcher. The receipt survives queue/archive cleanup.
- Queue handlers live in Services. Jobs only schedule. Claim receipts are fenced by `read_ct`; processing, next-stage enqueue and archive share a transaction. `QueueWorker.drainRemote` runs external work after its own committed intent, then fences completion in a short transaction. A pending completion persists its observation and delays the same message without archiving; it never authorizes a new payment or releases funds.
- The Java API owns queue processing; native MPC holders never connect to the wallet database. Business queue headers carry tenant ownership; replay records actor and reason.

## 独立 MPC 签名节点（开发分支）

- `resources/infra/mpc-signer` 为独立原生签名节点工程，包含底层 2-of-3 协议、加密文件存储和 mTLS HTTPS JSON 节点入口；当前已接入全部 28 条链的签名入口，不进入现有自动部署。当前代码最终 28/28 个链组本地链/测试网验收已通过，HyperCore 原一笔 1 USDC 转账已按原文重播、确认并结算；各链实测范围见 `resources/infra/mpc-signer/README.md`。
- cb-mpc 固定版本与 patched OpenSSL 配套构建，只调用公共高层 API；每个节点只能保存本方份额，不向 Java 钱包或其他节点返回份额，不重建完整私钥。
- `common.signing.MpcClient` 为共享单节点传输客户端，校验 TLS 1.3、CA、主机名、SPKI、返回请求身份与签名。`MpcSigningService` 编排持久化请求；地址与普通 EVM 业务入口已接入。节点 BUSY 是受理前拒绝，Java 先收集所有结果、再按 SPKI 顺序申请节点，遇 BUSY 或不可达不占用更高身份节点；仍需收取可达节点上的已完成结果。调用异常不能作为未签名或解冻资金的依据。
- `WalletMpc{Key,KeyEpoch,SignRequest,SignAttempt}Repository` 分别只访问 `wallet_mpc_key`、`wallet_mpc_key_epoch`、`wallet_mpc_sign_request`、`wallet_mpc_sign_attempt`。钱包只持有公钥、不可变交易意图、节点状态及绑定身份的加密结果，不持有份额。签名预约必须加入调用方资金/nonce/UTXO 事务，提交后才可调用节点；结果落库后才能使用。未知请求只允许保留原交易意图并记录操作者和原因后重放。
- `CustodyAddressService` 新地址创建先提交租户主题坐标，再通过 `MpcSigningService` 在事务外建钥，最后发布 `chain_address.mpc_key_id`、托管地址、EVM 投影与审计。`CustodyGasService` 的系统地址发布、Gas 账户和审计共用最后一个事务。禁止将创建入口包在调用方事务中；已存在的地址直接复用。MPC 地址的租户、密钥、坐标及地址身份不可修改。XMR 使用独立原生协议。
- `chain.signing.MpcNodes` 在 API 模式加载 `sw.wallet.mpc` 的三节点 SPKI/HTTPS 地址与 mTLS 通信身份，统一关闭客户端。通信证书私钥不属于钱包签名份额；禁止回退到 Java Seed 建钥。节点操作未就绪映射为可重试的 `MPC_PENDING`，不得发布未激活地址。
- 普通 EVM 提现、归集和找回签名分为 `EvmSubmissionService.reserve`（nonce / 固定信封 / MPC 请求事务）、事务外签名、`commit`（所选签名的 raw / 业务哈希事务）。签名字节交给链 RPC（包括 L1 费用预言机）前必须已保存 raw 与业务哈希。无哈希不能证明无签名；恢复为未签名重试前必须锁定业务行并确认没有 MPC 受理凭据。匹配哈希的节点受理或已知交易才把 evm_tx 从 PREPARED 推进 SENT。找回异常必须在业务锁下核对受理凭据，已有意图但无哈希时保留 EXECUTING。EIP-7702 另按下文内层 / 中继 MPC 编排执行，不允许本地凭证签名。
- XMR 经用户确认使用 Monero 原生 2-of-3 协议；其余当前接入链使用 cb-mpc 对应算法。底层密码学测试不能替代逐链交易、广播、确认、幂等及账务验证。
- 原生底层验证使用 CMake/CTest；构建输出、上游依赖源码、临时证书和密钥保存在仓库外。数据库验证仍遵守本机 PostgreSQL 18 约束。

## Cloudflare tenant-demo

- `resources/tenant-demo` 是用户授权部署到 Cloudflare 的独立租户演示应用；其 SQLite-backed Durable Object 是租户自身账本，不是钱包 PostgreSQL 数据库。
- Demo 的存储验收使用隔离的 Cloudflare 测试 Worker / 对象；不启动本机 SQLite、Docker 或独立数据库进程。钱包 Java 数据库测试继续遵循本机 PostgreSQL 18 约束。
- Demo 不直接访问钱包数据库；钱包 PostgreSQL init-sql 不包含 Demo 私有表。Demo 表定义以 `src/store.js` 为准。
- 自动发布限于 master 的 Demo 目录与对应工作流变更；测试失败不得部署正式 Worker。

- UTXO 广播使用 `UtxoBroadcastService.prepare` 提交签名字节与业务哈希后才在事务外调用链 RPC；完成观察在 `QueueWorker.drainRemote` 的 read_ct 事务中与事件和归档一起提交。未知结果提交 BROADCAST_UNKNOWN 并延迟同一消息，不能归档后丢失唯一重播入口；租约仍被占用时也必须保留消息。签名使用下述 `UtxoMpcSigningService`。

## MPC UTXO 签名边界（开发分支）

- UTXO 订单条件领取、输入锁、父签名请求、每输入固定 MPC 意图、`wallet_mpc_sign` 入队及父请求去重凭据同事务提交。
- `UtxoMpcSigningService` 只使用联合公钥构造 BTC/LTC P2WPKH 与 DOGE/BCH P2PKH；网络签名在事务外，完成后的 SIGN_DONE 入队和原消息归档受 read_ct 隔离。
- 广播前必须从已持久化的 MPC 原文和所选签名重新组装并比较 raw；签名错误消息不能作为释放 UTXO 或冻结本金的依据。未知保留原意图与消息，禁止重新选币。
- BTC 归集与 RBF 按实际输出脚本和最大单签长度计算费用；各链实际业务与 EIP-7702 分别验收；单项通过不代替全链最终验证。

## MPC TRON 签名边界（开发分支）

- TRON 出账入口只能使用联合公钥与已提交的 MPC 意图；TAPOS、有效期、付款、TRC20 合约/精度和业务 memo 一经预约不得更换。
- memo 绑定租户、业务类型和业务编号；不同业务即使时间与金额相同，也必须有不同的交易标识。
- `TronSubmissionService` 在事务外签名，在短事务内提交原始 protobuf / 业务哈希；`TronExecutionReceipt` 复核固化执行结果，TRC20 必须有精确 Transfer 证明。
- 回执事实、实际费用、订单与冻结账本在同一事务结算；只有明确失败执行回执可以退款。异常或过期且未查明的交易保留待核对，禁止重建付款。
- 持久领取方法统一使用 Durable 命名；TRON / EVM 未知恢复先于新任务开关，归集异常不能回退已发送或终态。

## XRP MPC 交易边界（开发分支）

XRP Payment / TrustSet 通过 XrpSubmissionService 在业务锁和联合密钥锁内同事务预留 account_sequence 与不可变二进制、MPC 请求。事务外联合签名；所选 DER 组装的 raw 与业务哈希先提交，再查询或广播相同字节。超时、过期和未知结果保留原意图，只有无意图且无哈希的业务可重新领取。未知恢复先于新任务开关。

XrpExecutionReceipt 只接受 validated、原签名字节和哈希匹配、精确 delivered_amount 的成功付款，或明确 tec 失败。链事实、实际费用和订单/冻结本金在同一事务结算。xrp_transaction.raw_payload 保留所选签名二进制，确认观察不能覆盖或回退终态。

发行货币地址准备使用租户 Gas 账户、租户目标联合密钥和每资产固定 FUNDING 请求，分别记录激活与 TrustSet。按目标地址持有数据库租约，已受理步骤优先恢复后再观察准备金与信任线；租约使用每次调用独立 owner，防止同进程重入。XRP/USDT/USDC 的三组实际 MPC 与 xrpld 正式业务已通过验收。

XRP 地址准备的 Gas 资金与手续费由 ADDRESS_PREPARATION 使用记录关联不可变 MPC 请求：预约时同事务冻结 Gas 余额；validated 时实际支出、使用记录、借方流水和链事实同事务结算。激活转给目标的准备金和手续费缓冲计入准备支出，目标 TrustSet 不重复扣 Gas 账户；Gas 地址自身 TrustSet 只扣实际手续费。明确失败只收取实际链费。失败提现 Gas 对账按链读取已确认失败费用。

## HyperCore MPC 边界（开发分支）

HyperCoreSubmissionService 从租户业务行读取付款信息，联合密钥、毫秒 nonce、网络签名域、现货 token 的 name:tokenId 与精度和固定 EIP-712 意图同事务预约。事务外联合签名，选定的 /exchange JSON 与本地 action ID 先提交，再发网络请求；不使用代理钱包或 vault。每条 action 显式绑定 tenant_id 与原 MPC request_id。API 成功响应仅代表已接受；资金结算必须另存官方 userNonFundingLedgerUpdates 原始 spotTransfer，复核原签名 owner、精确 nonce、双方地址、币种、金额、零额外费用及真实链上 hash。重试前先查询原记录，丢响应后再次查询；匹配成功可恢复原单且不伪造 API 响应。无原记录、查询截断、冲突或无法核对的费用均保留未知与冻结本金。action ID 是本地操作标识，并非链上交易哈希。

恢复先于新任务开关，只能使用原请求、nonce 和所选签名；没有 MPC 意图和 action ID 的业务才可重新领取。未知结果不能退款；网络缺失成功回执且重复 nonce 被拒绝时继续等待对账。编码已通过独立 Python EIP-712 参考摘要，三组测试网正式业务与原文恢复已通过。

HyperCore 托管账本由 spotClearinghouseState 现货余额支撑，因此钱包的 USDC 与 HYPE 均使用 spotSend，禁止用永续 usdSend 消费现货入账。USDC 从网络元数据中选择 canonical USDC token ID，保留钱包 6 位精度；HYPE 同时核对配置 token ID 与网络 8 位精度。usdSend 只作为编码组件覆盖，不属于当前钱包出账入口。链级金额/费用必须通过真实测试网验证；新账户激活费用按下一段的入口约束处理。

HyperCore 付款签名前在事务外检查收款地址 userRole，仅已激活普通账户/子账户可进入预约；预约在业务锁下核对同一收款地址，防止检查后更换地址。首次激活的额外 quote-token 费用不属于这笔现货付款，禁止在未冻结相应费用时通过提现/归集隐式激活新地址；租户归集/Gas 地址需预先激活。未知原意图不会因开关关闭而丢弃。

## Solana MPC 业务边界

SolanaSubmissionService 在租户业务行锁下验证固定付款、完整 legacy message、有效期及业务 Memo v2，联合公钥按 UUID 顺序加锁。原生 SOL 延续源地址付费；SPL 使用租户 Gas 地址与源所有者的两个签名槽（同公钥时合并）。所有槽与初始意图同事务提交，事务外签名；所选完整 Base64 交易与业务 signature 先提交，再发 RPC。父请求固定为源签名槽，Gas 槽必须绑定完全相同的原文；部分完成或未知不得更换 blockhash、付款或解冻。

SolanaTransactionService 只负责公开数据准备、已知 signature 查询、同字节广播和确认网络读取；SolanaAddressService 仅计算公开 ATA，地址发布统一由 CustodyAddressService 完成。移除未接入生产调用的 Seed 地址创建、按派生索引发送、独立冻结提现和 Mint 部署便利接口，测试部署代币改由明确的本地测试注资者处理。

SolanaExecutionReceipt 复核所选 raw、signature、确认 slot、执行结果、SOL 接收增量或 SPL 的 mint/精度/所有者/来源与目的 ATA 原子余额差。Gas 费用包含实际付费账户消耗的 ATA 准备金；链事实、费用、单据和冻结本金同事务结算。明确已确认失败仅收取实际手续费并释放本金；未知或过期保留原意图。恢复先于新任务开关，不能回退 SENT 或终态。三组真实 MPC/Agave 的 SOL、USDT、USDC 正式业务提现、充值重放、丢失响应恢复、精确本金结算、归集、真实已确认失败退款及 Gas 实际租金/手续费核对均已通过。

Solana 充值来源过滤同时包含托管所有者和其 Mint ATA，内部归集不得再次计作外部充值；联合地址角色与密钥身份保持不可变。

## Aptos MPC 业务边界

AptosSubmissionService 在租户业务和联合密钥锁内同事务预留 account_sequence、完整固定 APT / primary FA 原文、Gas 上限、有效期和 MPC 请求。事务外签名；选定的 legacy Ed25519 BCS 对应 JSON 与本地交易哈希先提交，再查询哈希或广播相同原文。已受理的请求不随新任务或资产关闭改变，部分完成、过期、RPC 未知均保留原意图；只有无签名意图且无哈希的业务才可重新领取。

AptosTransactionService 只准备公开链参数并调度签名、广播、恢复和确认读取；移除未被生产入口调用的 Seed 按索引发送、通用函数/合约发布和独立冻结提现接口。链回执复核 BCS/哈希/公钥签名、完整付款字段、提交版本和主 FA store 精确收付事件；回执、实际净费、业务单据和冻结本金在一个事务内提交。明确链上失败只释放一次本金，未知不能退款。归集目标须属于同租户的已发布联合地址；未知恢复先于新任务开关。

aptos_transaction 新增 fee_octas，保存 FeeStatement 的 gas_used × gas_unit_price 减 storage_fee_refund_octas 后的实际费用；确认失败也保留费用。raw_payload 保持最初选定 JSON，终态不能被广播观察回退。对应单表仓储仍为 AptosTransactionRepository；唯一 init-sql 已同步。三组真实 MPC 与官方 Aptos localnet 的 APT、USDT、USDC 正式业务提现、充值/重放、丢失响应恢复、归集、真实 Move 失败退款及实际净费用/受控原生余额核对已通过。

- TON MPC 编码阶段由 MpcTonTransactionCodec 公开构造 V4R2 / TEP-74；wallet_id 同时绑定网络签名域与合约地址，seqno=0 附同一公开 StateInit。发送固定 mode=3，不能把可跳过 action 的零出站成功当成付款；业务已接入固定意图 / BOC 先持久化和内部消息回执校验，完整链上业务验收已通过，不以外部消息处理成功代替付款完成。

- TON RPC 使用 ChainRpcNodeService 的 provider 限流配置；Jetton 地址和 owner/master/code 使用 Master / Wallet 的公开 getter 解析，禁止按固定钱包代码猜测地址。

- TON 本地链验收只运行 genesis 和 TON HTTP API，禁止启动辅助 HTTP faucet / indexer 数据库；测试注资仅用隔离 genesis 预置账户，密钥文件留在仓库外并限制为所有者可读。

- TON 链事实由 TonTransactionRepository 单表维护：raw_payload 保留首个签名 BOC，execution_receipt 分存实际交易/内部消息回执；CONFIRMED / FAILED 不被迟到观察回退，已确认失败仍保留实际费用。TonSubmissionService 预留业务归属 / seqno / 意图，事务外 MPC，BOC/hash 先提交后广播；未知恢复先于新任务开关，实际回执 / 费用与结算同事务，三组真实 MPC 的 TON / USDT / USDC 提现、充值、原文恢复、归集、明确失败退款和费用验收已通过。

- TON 地址服务不得生成 Seed 钱包；Jetton 注册继承已发布 owner 的租户 / 账户 / MPC key，并用 publishMpc 防止身份覆盖。充值只接受原始内部消息与成功 Jetton internal_transfer，校验配置 Master / owner；通知、反弹和内部运营转账不入账，不依赖辅助索引器。

- TON Jetton 来源 VM 拒绝只能在 owner 序号已消费、来源 aborted 且没有代币出站、唯一反弹命令与实际 owner 收款证据齐全时退款，并记录附加 TON 减实际反弹净额的费用；目标 Wallet 失败或证据不全继续对账。

- SUI 使用 SuiSubmissionService 同事务固定对象意图和 MPC 请求，密钥锁下串行接纳同 sender；拒绝新业务复用已有 digest。已选 BCS / witness / digest 提交后才能广播，未知结果复用原对象恢复。sui_transaction 的 gas_used 收敛为有符号 fee_mist，execution_receipt 单独保存原始 protobuf；单表仓储仍为 SuiTransactionRepository。Gas 负净费仅 SUI 可用，以 CREDIT 审计；零费释放预留。原始成功证明驱动充值和强制租户投影，已观察充值补确认先于新扫描开关。三组真实 SUI / 本地 USDT / USDC 的正式业务、恢复、归集、已提交 Gas 不足失败退款及存储退款净费对账已通过。init-sql 与架构图文同步。

- NEAR 使用 NearSubmissionService 同事务预留 nonce、SIGNING 记录和 MPC 请求，按 sender 跨资产串行；已选原文/hash 提交后广播；send_tx 等待 EXECUTED_OPTIMISTIC 核对 hash，最终结算另等 FINAL，不能假设 INCLUDED 带交易实体。未知恢复先于新入口开关，不换 nonce，过期未知继续核对。near_transaction.fee_yocto 与 execution_receipt 保存 24 位实际支出和 FINAL/header 原始证明；单表仓储为 NearTransactionRepository。成功 FT 额外支付 1 yoctoNEAR，明确失败闭包须完成签名方退款。正式入口、净额扫描、三组九笔提现、三类归集、1 yocto 入账/提现、丢失响应恢复、真实转账及 transfer_call 退款和精确费用对账已通过；当前全链验收范围见原生服务 README。
- NEAR 充值按 FINAL 成功事件净额计入，ft_transfer_call 退款必须扣除；不得只读取请求金额。停用内部地址仍过滤，已观察充值补确认先于新扫描开关。未知历史区块不得直接越过，须以实际 header.prev_height 证明跳块。
- 金额基线统一精确保存 24 位小数：原 numeric(78,18) 的 29 个金额/阈值列改为 numeric(84,24)，保留原 60 位整数范围；已有 24 位列与 USD 报价不变。Gas 金额、提现容量和 Token 阈值校验同步，禁止把 NEAR 的 1 yoctoNEAR 舍入。63 项金额等检查、70 项 NEAR 入口等检查及真实正式业务已通过；金额/SUI/NEAR/提现/Gas/入口综合回归 147 项已通过，无失败或跳过。金额验证使用本机 PostgreSQL 18 隔离临时库。init-sql 与架构图文必须同步。

- DOT 的资金业务（DOT/USDT/USDC、充值、余额、提现、归集、Gas 和确认）统一 Asset Hub，使用同一 nonce 域；正式配置只需 Asset Hub rpc + 公共 runtime，不恢复 Relay 资金路径或 asset_rpc 分叉。公共 SCALE 组件可独立验证 Relay，但不进入托管业务。polkadot_transaction 只由 PolkadotTransactionRepository 访问，租户请求/nonce/admission 同事务，选定原文与业务 hash 在 RPC 前提交。
- DOT 签名明确为 V4 / transactionExtensionsByVersion[0]；禁止使用 Metadata V16 的所有管线并集，禁止将 V1 的签名/PGAS 等非空扩展当作无效项。活动扩展的 SCALE/implicit 类型必须已审查，费用资产固定 None，未知项拒绝。原生转账 keepAlive，Token 可清空资产账户但保留原生 ED/费用和 system nonce，回执拒绝发送方 KilledAccount。Gas 补充由租户自有 MPC Gas 地址出资，使用 FUNDING 请求和既有预留/结算流水。回复丢失/过期不生成替代意图，实际费用缺失不得回退估算；充值过滤内部地址（含停用）和费用 Deposit/Endowed。当前 Asset Hub 目标已通过真实三组业务、自动 Gas 入账/补充、全额归集和费用对齐验收；当前全链验收范围见原生服务 README。

## Cardano native MPC integration (in progress)

- Public RPC is native cardano-node UTxORPC v1beta, pinned in wallet-api/src/main/proto/utxorpc. No auxiliary indexer database. FetchBlock requires hash and supports hash-only resolution of the native slot/height; FollowTip must establish its exact slot/hash intersection and reject gaps/rollback before advancing.
- Public construction uses one immutable original UTxO snapshot and current Conway parameters. Verify exact inputs, all-asset conservation, minimum ADA, size/fee/TTL and one external MPC witness. Original proofs bind header/body commitments and selected bytes; canonical membership comes from the native node, not a full PoS light client.
- CardanoTransactionRepository accesses only cardano_transaction. CardanoOwnedOutputRepository accesses only cardano_owned_output (wallet output history, not a full-chain index). Both bind tenant ownership by composite foreign key. Preserve first wire/proof; known zero expense is not missing expense.
- Reuse chain_scan_height.genesis_hash and chain_scan_block.block_slot for native cursor identity. Services must atomically coordinate cursor, owned outputs, mandatory tenant deposit projection and any explicit reorg rollback. Capture the initial genesis/tip before first ADA address publication; publish its anchor in the address transaction without network calls inside that transaction.
- Public component acceptance has passed; custody workflows, raw recovery, TTL proof/refund, Gas and all-pair business acceptance remain in progress. Do not declare ADA complete from codec or storage tests.

- CardanoSubmissionService owns transaction boundaries for immutable request/body-hash admission, sender input serialization, selected wire/business binding and depth-qualified settlement. No network MPC inside its reservation transaction. Retain accepted confirmation policy; reject snapshots older than the sender's latest terminal height. Token external minimum ADA is native expense; internal collection minimum ADA is not. Entry/scanner replacement and formal native business acceptance remain pending.

- CustodyAddressService prepares the native Cardano anchor outside the transaction before DKG; publishPrepared persists the anchor with the first public ADA address, including Gas addresses. Existing address retries do not prepare an anchor again. CardanoScanAnchorService follows verified parent hashes to a depth-qualified ancestor and verifies native intersection. Its publish method must join the address transaction and make no RPC calls.

- CardanoTransactionService and CardanoDepositScanner use only native public RPC and external MPC. Capture the confirmed search origin before the live input snapshot. Each confirmed block atomically posts custody deposits, owned output references, original headers and cursor. Reorg reconciliation precedes new-scan gates; select an exact stored intersection, reverse deposits and rewind outputs/headers/cursor together. Do not treat a submission error as a failed payment or invent a same-height replacement hash. Gas reconciliation requires actual native expense. Formal native business acceptance passed (206.44s, all pairs/native/tokens/Gas/expiry/collection/fee conservation); full all-chain work remains incomplete.

- Cardano init-sql RPC seeds are disabled native gRPC TLS placeholders. Configure a real genesis-matched endpoint and explicit authentication before enabling; no Blockfrost endpoint or implicit project-id authentication remains.

## XMR native multisig integration (in progress)

- Use official native Monero 2-of-3, never generic Ed25519. Keep private view/spend material, wallet files and encrypted passwords inside independent native nodes. Per-tenant root wallet and per-custody-address major>0/minor0 account preserve input/change scope. Wait for actual unlock; -37 is not failed-payment proof.
- Loopback RPC uses owner-only 0600 Digest credentials; unauthenticated RPC is limited to explicit regtest fixtures. MD5-sess authenticates the exact HTTP path with persistent connections and nonce counting. Verify actual daemon nettype/genesis and public address network/account type. Never regenerate a registered wallet whose private files are missing.
- SIGN does not broadcast. Both participants independently validate original real-ring input ownership against native output snapshots, exact recipient, amount, fee ceiling, ring/unlock policy and original-address change; agree before cosigning and persist identical intent-bound final artifacts. Java must collect two identical SPKI-authenticated results and encrypt the original artifact with authenticated participants; re-read verifies the binding. Generic replay must not create replacement Monero inputs or sessions.
- MONERO_RELAY uses one original completed-artifact holder, accepts only original SIGN session/intent/hash references, and cannot accept caller-supplied replacement txsets. Query the original hash before/after relay. Unknown remains INDETERMINATE. MONERO_OBSERVE uses two registered nodes, key-image synchronization, exact canonical anchor, stable view checks and identical public output/balance/transaction observations. Absent transaction is not refund proof.
- Public key binding is 64 bytes (public spend + public view), with a separate checked address; Java uses CryptoNote block-base58/Keccak/network/public-point validation. init-sql uses the exact MONERO_NATIVE_2_OF_3 protocol name, replacing the unused placeholder, with 128-hex public keys and no old alias.
- MPC_MONERO_BIN_DIR enables native component acceptance; MPC_JAVA_TEST_CLASSPATH makes actual Java clients drive all positive native operations. Latest actual Java/native components passed 4/4 (196.74s, XMR186.95s), including all quorums/offline third party/single-holder relay/restart/replay/original-block confirmation/conservation and negative guards. Cross-chain persistence reports cover234 checks without remaining skips after owned-Hardhat supplementation. These do not complete XMR wallet address/scan/Gas/collection/business and interrupted-native-signature recovery integration, which remain pending.

## HyperCore 真实账本扫描与资金验收（测试网通过）

真实现货充值使用 userNonFundingLedgerUpdates 的 send（destinationDex=spot）与 spotTransfer；保留原 hash 与公开 ledger 明细，不从余额差额制造充值。HyperCoreLedgerCursorRepository 只访问 hypercore_ledger_cursor，按 (tenant_id, network, chain_address_id, asset_symbol) 保存包含边界时间戳的独立 USDC/HYPE 游标。公开 API 读取在事务外；充值、账本、必需租户投影和游标同事务。500 条保守分页阈值遇到无法推进的同时间戳边界时停止，不跳过记录；同名 Token 元数据歧义时拒绝猜测。旧快照仓储与表已删除，init-sql 同步当前字段、主键和外键。

自有发送方必须能关联原 tenant/request/nonce、不可变签名意图和业务单。内部归集跳过二次客户充值，普通提现到托管客户正常入账一次；未知自有转出阻止游标推进。原生地址身份保持不变，HYPE 使用同一地址的独立资产游标。已观察入账使用完整网络 Token 配置与持久地址归属，不受 Token/地址停用或新入口开关影响。API 时间不冒充区块高度/区块哈希。

验收复用 checkout 外已保存的 source/collector 原生 key、SPKI、AES 与同一本机 PG18 的明确命名测试库，不重建已充值身份。此前批次的 USDC/HYPE 三组正式提现、原 nonce 丢回复恢复、三组归集和托管客户实际收款/重复扫描已有 21 笔确认记录；每组实际停止第三个原生进程。当前最终批次 21 个业务意图、21 次 MPC 签名和 21 笔链上确认均已完成（15 笔提现、6 笔归集）；原 `hypercore-funded-usdc-final-20261101-source-2` 保留原 nonce、原签名字节和唯一签名尝试，原文重播后取得规范回执，并由正常业务流程完成一次结算，冻结本金归零。确认后恢复及重复确认不再次广播或扣款。全部原生与 Java 验收进程已结束，资金测试库与原身份保留供对账。

HYPE 测试资金准备已通过：0.07 HYPE 买单实付 11.7264 模拟 USDC，实际 HYPE 费用 0.000049，净收 0.069951；重跑原订单未重复购币/记账。测试辅助模块使用可选 hypercore_test_funding.py 与官方 SDK 0.24.0，在测试网以原生 AB 签名提交最多 15 模拟 USDC 的固定 IOC 买单。原订单、nonce、会话和签名先保存；按官方 cloid/oid、逐笔成交和实际费用核对后，测试账本的 USDC 支出、净 HYPE 收入与审计流水一起幂等提交。该辅助流程未增加生产下单接口。hypercore_funded_test.py 的 baseline/recovery/collection/owned-withdrawal 使用固定业务引用，procurement 只做一次 AB 资金准备，hype-withdrawal/hype-collection/hype-owned-withdrawal 使用三组正式业务；重跑复用原请求，不重复付款或购币。


XMR 原生地址发布已接入：Java 的 MoneroDaemonClient 只通过 purpose=daemon 读取 get_info/规范区块头，复核实际 nettype、离线 fakechain 和 genesis；Digest 编码由 MoneroDigestAuth 与开发资金客户端共用，Java 不调用原生签名节点的私有钱包 RPC。MoneroScanAnchorService 在事务外准备已确认起点；MpcSigningService 先预约每租户唯一 XMR_ROOT，再以原 rootKeyId 和明确网络/genesis 预约 XMR 地址账户，三方结果一致后激活。CustodyAddressService 将原生子账户公钥地址、XMR_NATIVE 扫描锚点/原始头、租户归属与审计在同一事务发布，Gas 地址复用该路径；失败重试保留原根/子账户身份，不重新建钥。地址阶段已通过真实 Java/三原生节点验收：公开 daemon Digest、根与子账户建钥、客户/Gas 地址发布、发布回滚后复用原身份，以及三个签名节点和三个私有钱包重启后复用原根/地址并创建新子账户；重启后共四个地址，三方私有钱包账户一致。定向 Java 回归 42 项通过，无失败或跳过。该结论仅覆盖地址发布；完整钱包业务及原签名材料恢复的验收范围见下文对应章节。

该阶段复用 wallet_mpc_key / wallet_mpc_key_epoch / chain_scan_height / chain_scan_block，不新增私钥字段或业务表。init-sql 同步现有 daemon 用途说明及默认关闭的 stagenet/mainnet daemon 配置；旧 Java wallet-rpc 付款路径已在完整 XMR 业务切换中移除；私有钱包 RPC 仅由原生持有者访问。

XMR 扫描事实边界已补充并完成真实 Java/原生联调：MONERO_OBSERVE 保留输出的原交易 outputIndex、globalIndex、公开输出键、实际确认数、coinbase、unlockTime、spent/frozen/unlocked；每个 owned output 均核对 daemon 原交易的输出位置/键/全局序号及原规范块包含关系。普通交易使用 tx_hashes，矿工交易使用 miner_tx_hash 与原 gen.height；Java 独立检查位置唯一、事实类型、锚点与确认数。未解锁输出继续返回事实，不因过滤而丢失；是否可入账由钱包扫描流程显式判定。更新后的真实 Java/原生 CTest 4/4 通过（197.89 秒，XMR 188.55 秒），新增 6 项 Java 边界测试通过；包括外部充值 1 个确认未解锁、13 个确认原输出解锁、原输出序号/全局序号核对、矿工交易独立包含证明，及三组两方付款/广播/恢复回归。该边界修改不新增表、字段或初始化配置，钱包正式扫描和出款的当前实现见下文。

Java 钱包观察入口已接入并完成实际节点联调：MpcSigningService.observeMonero 在事务外根据租户原 XMR 密钥和已激活 epoch 核对公开地址、network/genesis 与原参与方，向两个原生持有者读取绑定规范锚点的同一结果；返回公开请求/结果供扫描审计，不生成付款意图或签名。已暂停租户仍允许查询其已有密钥，便于已观察资金完成确认/重组处理。调用方不得在数据库事务中发起该 RPC。49 项定向 Java 回归通过，无失败或跳过；真实 Java/原生节点验证了暂停租户读取原密钥、禁止事务内 RPC、两方一致结果，以及三个签名节点/私有钱包全部重启后仍读取原账户。正式付款、归集与原签名材料恢复的当前实现见下文。

XMR 正式充值扫描已改为原生两方输出事实并完成真实验收：MoneroDepositScanner 不再调用 Java wallet-rpc，而是通过 MpcSigningService.observeMonero 核对规范锚点、原交易 outputIndex/globalIndex 与公开输出键；原生节点从同一租户多方钱包的已确认自有付款记录识别 sourceAddress/change，找零不记作充值。每批原始区块头、充值、强制租户投影、账本、事件与 XMR_NATIVE 游标同事务提交；事务内重新核对游标原哈希和已发布地址集合，防止重组或新增地址竞争导致跳扫。

锁定输出保留实际确认数，DepositService 以明确的 unlocked 准入条件延后入账；待确认行继续保存原输出键/序号和金额，解除锁定或冻结后按原身份重读并只入账一次。confirmObserved/reconcileObserved 依据已发布密钥的原网络配置运行，不依赖新扫描、链、租户或地址开关；串行 Monero 工作流在启用 profile 筛选之前维护已观察资金。规范链交点、充值冲正、租户赤字/事件和游标回退共用事务，找不到有界交点时停止。

该阶段复用 deposit_record 与现有扫描头/游标，CONFIRMING 保存真实深度和原输出审计，不新增表、字段、状态或初始化配置，因此 init-sql 无变化。已删除依赖单钱包私钥 RPC 和非隔离数据库默认值的旧 MoneroRegtestFullFlowIntegrationTest，替换为隔离 PG18 原子边界测试和真实独立节点/原生钱包扫描验收。本段记录扫描阶段；正式付款及原签名材料恢复分别按下文专项验收核对。

XMR 锁定充值的成熟度刷新按 deposit_record.updated_at/id 取最久未复核的 500 行；未解锁输出在保留原事实后更新时间，使后续已成熟充值也能进入复核批次。该查询仍属于 deposit_record 单表仓储，不改变其他链的既有待确认查询，不新增字段或索引。待确认并不要求确认数继续增加，解除本地冻结也可按同一原输出入账。

XMR 充值侧真实验收通过：run-monero-flow.sh 使用官方 Monero 0.18.5.4、三个独立签名节点与原生私有钱包、仅 daemon 用途的 Java RPC 路由和现有本机 PG18 内的隔离测试库。AB/AC/BC 每组均实际停止第三方 signer/wallet，验证 1 个确认保留锁定输出、13 个确认在关闭链/租户/地址后按原输出入账、重复确认幂等、同一交易向两个托管地址付款的原输出序号不冲突、Gas 原生资金自动投影。强制租户投影失败时本金/充值/投影整体回滚，重试仅记一次。两份原生钱包总余额均与账本的累计 3 XMR 一致。

随后在同一离线 fakechain 回退最后 13 个块、清除原测试付款并挖出 14 个替代块；真实规范块哈希变化触发原 Gas 充值一次冲正，账本与两份原生钱包总余额均回到 2 XMR，第三次入账保留 REORGED 审计。最新原生 CTest 4/4 通过（202.40 秒，XMR 191.63 秒），覆盖自有找零来源标记与三组原签名/广播恢复；Java 相关回归 76 项通过，新增成熟度公平轮询后最新 25 项复验通过（12 项原子充值/重组、7 项公开事实、6 项架构），均无失败或跳过。该结论仅覆盖充值侧；正式付款和原签名材料恢复分别见下文对应章节。

XMR Java 原签名广播桥接已接入并完成真实验收：MoneroSigningPayload 固定 network/genesis、源/目的、12 位精确金额、客户手续费、原生费用上限与确认策略，PAYMENT 原文使用排序 JSON 和字符串原子金额。MoneroSignedPayment 从已加密持久保存、经两个原参与方认证的签名材料重新验证原签名会话/意图、实际费用和 artifact 摘要，仅公开选择身份，不复制原 txset。

MpcSigningService.selectedMonero/relayMonero 只读取已完成的原请求和加密结果；调用方预期哈希必须相同。MONERO_RELAY 只引用原 SIGN 会话/意图/哈希，可在任一原材料持有者上执行；两名原持有者以外的节点不接收广播请求，部分离线或未知时保留原交易。广播 RPC 禁止运行在数据库事务内，恢复不会新建签名尝试或重新生成付款。业务调用方仍须先绑定原签名选择与业务哈希，再调用广播桥接。

本轮桥接验收已通过：跨 Java 进程保留同一受保护的协调器 AES 密钥，三组真实双节点 SIGN → 数据库加密结果 → 全部原生进程/私有钱包重启 → 仅一名原签名持有者在线广播 → 两方查询原规范块与本金/费用 → 另一名原持有者重放原哈希。该测试是签名持久化与广播桥接，不能代替提现/归集业务预约、Gas 资金准备和结算验收。无表/字段/状态/初始化配置变更，init-sql 无影响；正式 MoneroSubmission 业务和原签名材料恢复见下文；缺失原材料继续保留未知。

签名桥接已实测协调器未收取结果场景：先持久预约原请求并由 Java 受理到两名节点，Java 在收取签名结果之前退出；原生节点完成原会话后全部重启，新的 Java 进程仅按原请求收取既有两份结果、加密保存并读取同一选择。它验证的是协调器未收到已完成结果的恢复，不宣称可自动恢复所有原生签名内部中断阶段。

Java 原签名桥接最新定向回归 32 项通过（含 7 项付款策略/选择边界），无失败或跳过。三组真实 Java/原生/PG18 签名持久化、同 AES 协调器重启、两方结果绑定、仅一名原持有者广播和另一名原持有者确认后重放均通过；每笔始终只有一条原 SIGN 尝试。实际受控 5 XMR，外付 3 XMR，实际费用 0.0054252 XMR，原生源余额 1.9945748 XMR，整数原子单位严格守恒。协调器未收取已完成结果的补验也保持原会话、原哈希和原请求。该结论是持久签名/广播桥接，正式提现/归集/Gas 业务及原签名材料恢复分别见下文；缺失原材料不自动恢复。

## XMR 原生出款业务当前实现与验收

MoneroTransactionService 编排正式提现、归集、手续费资金准备与已受理交易恢复；MoneroSubmissionService 负责租户业务行和联合密钥的锁定、不可变 MPC 请求、原签名选择、广播屏障与结算。Java 托管路径仅使用 purpose=daemon 的公开链 RPC；原生私有钱包操作留在独立签名节点。旧 Java MoneroWalletRpcClient / MoneroAddressService 已移除，普通 Seed 地址入口拒绝创建 XMR 地址。启动时 XMR 只要求 daemon 路由。

monero_transaction 单表仓储按 tenant_id/request_id 绑定 wallet_mpc_sign_request；selected_attempt_id 通过复合外键绑定同一租户原请求的签名尝试。原会话、artifact 摘要、交易哈希和实际原子费用一旦选择即不可修改；原 txset 只保存在已加密的 MPC 结果中。SIGNING → PREPARED → SENT → CONFIRMED；原签名选择和提现/归集业务哈希在同一事务提交后，才允许事务外 MONERO_RELAY。未知结果保留原请求和冻结本金；已受理意图不释放为未签名重试，不生成替代付款。pending 查询按更新时间公平轮询，既有付款恢复与确认先于链启用条件。

主付款意图先持久化，再按主业务的固定引用预约租户 MPC Gas 补款。补款只补手续费及正找零预算，不补客户本金；已存在补款优先恢复，原生资金未解锁时等待，不重复补款。补款金额加费用上限先预留，确认后只把实际链费作为费用结算，仍在租户钱包内的补款本金不重复计费。正式提现确认使用原请求的网络、密钥、哈希和确认策略；两名原生持有者给出一致规范交易事实后，确认凭据、本金结算和业务状态共用事务。自有来源收款必须关联原请求：内部归集/补款不重复记客户充值，普通提现到托管客户仍记一次充值，原地址找零继续跳过。

本轮已修改 init-sql 的 monero_transaction 目标结构、租户/请求/签名尝试外键、唯一约束和不可变触发器，并移除旧托管 wallet-rpc 初始化路由；开发资金 faucet 路由保留为测试资金来源。最新业务切换已完成编译与 114 项定向 Java 回归（wallet-api 107、common 7），无失败、错误或跳过，其中 13 项使用隔离 PG18 验证出款原子边界。真实 Java/原生/PG18 完整业务验收已通过：AB/AC/BC 每组实际停止第三方签名节点及私有钱包，提现、归集、丢失广播回复、协调器/原持有者重启、关闭链入口后的原请求恢复、原规范确认和只结算一次均通过；普通提现到自有地址新增一次充值，归集/补款与找零不重复充值。共 8 笔业务付款和 1 笔补款，9 笔规范确认，待处理为零，每请求仅一条原签名尝试。初始受控 10 XMR，外付 4.5873412 XMR，全部实际链费 0.0170664 XMR，余额 5.3955924 XMR，与两份原生钱包一致。补款仅一笔，实际 Gas 费用 0.0018084 XMR，Gas 账本为 4.9981916 XMR，预留完整释放，补款本金不重复费用化。测试库和原生进程已清理。此前充值/解锁/重组实测与持久签名/广播桥接实测各自成立，不能替代这次业务切换验收；原签名材料恢复与缺失材料的边界见下文；当前全链验收范围见原生服务 README。

XMR 多输入审核已补充原输入集合的规范比较：官方 Monero 0.18.5.4 的联合签名重建入口会重新按 key image 排序 sources（[官方源码](https://github.com/monero-project/monero/blob/v0.18.5.4/src/multisig/multisig_tx_builder_ringct.cpp#L806)）。原生适配器仍在签名前后分别验证每个输入的所有权、公开键/承诺、全局序号、金额、未花费与解锁状态，再只对 sources 数组排序后比较完整描述；收款人、找零、费用、金额、ring、unlock、extra、weight 等字段均保留严格比较。未放宽原输入集合，也不重新选币或生成替代付款。新增原生策略测试先证实旧比较拒绝同一输入集合的重排，再覆盖输入替换/删除/重复及付款字段变化；原生策略与真实 Monero 协议组件 2/2 回归已通过（153.38 秒）；完整业务重跑已通过，包括连续三组付款后再补款的多输入主付款；原生签名中途退出后的恢复遵守下文原签名材料约束。此次原生摘要规范化不改变数据库字段或初始化配置。

## XMR 原签名材料恢复与未知状态验收

仅 MONERO_NATIVE_2_OF_3 的原 SIGN 可在 INTERRUPTED/INDETERMINATE 后再次提交同一描述符；仍使用同一租户、key/epoch、session、意图和两个原参与方，不新增签名尝试。其余 cb-mpc 算法的未知签名不得重新提交。恢复获得运行回执时仍保留已观察的未知状态，直到两个一致完成结果被验证，不把未知签名降级为普通签名中。原生 SIGN 首次 RPC 前先将原尝试 PENDING → RUNNING 提交为调度屏障，避免节点已受理而协调器未保存观察结果就退出的空窗；该标记不是节点受理确认。MpcSigningService.hasStartedMoneroSignature 根据原尝试状态判断是否已进入签名；一旦进入，MoneroTransactionService 只恢复原材料，不再依据余额重新补款或准备输入。

原生节点在签名前把选定输入的公开所有权/锁定事实与已批准描述一并加密持久化。恢复优先读取本机原 artifact 或原 cosigned txset；原发起者可经固定 SPKI 的内部 /v1/monero-recovery-material 向原第二签名者读取既存材料。该内部接口只允许原 online[0] 调用原 online[1]，必须与后者已保存的完整 SIGN 请求逐字一致；协调器、第三方持有者、其他租户/key/session 或变更策略均拒绝。第二签名者必须保留自己的原 RPC 结果，不能靠发起者声称其已签名。

恢复只打开原账户并使用 describe_transfer，按本机加密保存的原输入事实重新检查交易及完整描述；不刷新/导出签名随机材料，不调用 transfer/sign_multisig，不选择输入、不广播。原材料不足时继续未知，冻结本金保留，不替换付款。恢复成功写入独立的 recovered-result 加密不可变记录，查询优先读它；旧 INDETERMINATE 结果及原请求保留，不覆盖审计。Java 收取两个一致的原会话结果后，继续既有选择、业务哈希绑定、广播和规范确认流程。

本轮未新增表、字段、业务状态或配置项；init-sql 无额外影响。最新恢复代码已完成编译和 119 项定向回归（wallet-api 112、common 7），无失败、错误或跳过，其中 18 项 PG18 用例覆盖出款/恢复边界，并在首次节点访问前验证签名阶段屏障已提交。真实 Java/三原生节点/官方钱包中断验收已通过：AB 从原 cosigned 材料及原未知结果恢复，AC 从原 artifact 恢复，BC 从一方完成、另一方结果写入失败的原会话恢复；旧结果字节不被覆盖，每次仍是一条原签名尝试。每组实际停止第三方并重启原持有者/私有钱包和协调器，单原持有者广播、原区块确认及另一原持有者重放通过。内部读取的协调器、第三方、反向持有者和变更描述符均被拒绝。恢复期间原 transfer/sign_multisig/export/import 调用次数不增加。

原材料缺失的两类实际中断也已验证：构造回复返回但本机审核未落盘，以及钱包已签名但 RPC 回复和 cosigned 材料未落盘。两类均保持原请求/原尝试 INDETERMINATE，不伪造恢复结果，不重新签名或广播。后者不能自动重建已经丢失的签名材料；保持冻结并核对/恢复原材料，禁止以重签、恢复旧随机数缓存或手工退款代替原交易核对。完整验收初始受控 9 XMR，真实外付 3 XMR，实际费用 0.0054252 XMR，三个独立钱包余额均为 5.9945748 XMR；原观察接口负责重启后的钱包打开与事实读取，测试不提取私有存储中的口令或密钥。

run-monero-flow.sh 现在分别在现有本机 PG18 的三个隔离测试库中运行充值/重组、正式出款/补款和原签名材料恢复/未知保留，结束删除自身测试库及临时链/钱包进程。原材料缺失后的运维核对、备份与恢复说明见 `resources/infra/mpc-signer/OPERATIONS.md`；本机冷恢复及 Linux 发布包验证已通过，当前 28 链最终验收已通过，尚未部署生产。


## EIP-7702 原批次领取凭据

- evm_collection_batch / evm_withdrawal_batch 各自的 claim_payload 保存完整公开原 Batch，与条目及业务领取同事务写入；不包含私钥或 RPC 认证信息。
- 对应单表 Repository 负责原快照和 LOCKED/SIGNING 查询；Coordinator.claimed / listSigning 不依赖当前启用配置重建原批次。正式 MPC 签名与调度按这些原始事实恢复，不按当前配置重新领取。
- init-sql 的非空/JSON 身份约束与不可变触发器禁止篡改原租户、批次、金额/资产上下文、地址和版本；不得删除快照或用重新领取替代原请求恢复。


## EIP-7702 MPC 签名编排

- Evm7702SigningService 使用原 claim_payload、wallet_mpc_sign_request / attempt 和既有批次状态；原准备数据、内层意图、原 relayer nonce 与 SIGNING 一起提交，禁止签名事务内执行节点或链 RPC。
- 内层签名属于业务租户；中继签名属于配置中继的明确 MPC 密钥租户，引用显式绑定原业务租户/批次。配置禁止空归属或无 MPC key 的 Seed 中继。
- 中继数据必须从原内层完成结果独立重建；中继意图和 Gas 先提交，选定原文/哈希/批次项/恢复凭据先提交，再广播。未知签名不能走未签名解冻或重新领取。
- 两类批次领取共用链/网络事务锁和未终态检查。恢复先于新业务开关，并从原快照读取配置；本地 Prague 正式业务 AB/AC/BC 已通过 36 笔交易和 30 张订单，仍不得将组件、数据库替身或本地链结果外推为生产 L2 / 全链 / 部署恢复验收。


## NEAR Token 注册

- NEP-145 只允许原业务收款人与原配置合约的 registration_only 注册，付款来自同租户 MPC Gas 账户。原业务、FUNDING 意图、Gas nonce、待完成链记录及 Gas 冻结一起受理，RPC/签名在事务外。
- 原注册请求阻止无签名恢复重新领取；原文及哈希先于广播，必须按原请求恢复与结算，不能以当前已注册代替原支出证明。NearStorageReceipt 校验完整 FINAL/全回执/规范头和原付款人，押金退款与 Gas 退款分开计算。
- 注册实际费用与最终状态使用现有 near_transaction / wallet_mpc_sign_request / custody_gas_usage；无新增表和仓储映射。只有注册及 Gas 结算完成后才签原 Token 付款；明确失败退款才可原子释放原业务本金，未知不释放。

- NEAR 已受理 RPC 必须用原 payload.network 显式绑定；不依赖当前 enabled profile，也不回退到其他网络。当前启用网络只用于新工作入口。

- 固定 NEP-141 模板必须执行退款 Promise；已注册退全额、新注册退超额。退款日志不能替代完整 FINAL 退款回执，不得为模板漏退放宽 Gas 费用核算。

- 自动注册 AB/AC/BC 的 21 笔本地实际交易已有通过记录；回执回归保留 12 笔实际注册和旧模板缺失退款的公开证据。此结果不代表全链最终回归或部署恢复完成。


## MPC 份额刷新

- REFRESH 只适用于 cb-mpc 三种曲线算法；XMR 保留独立原生多方协议。原租户、公钥、地址、三名持有者顺序及原 source/target epoch 不变；重复运维调用不得产生下一轮刷新。
- MpcSigningService 在原 key 锁下检查没有未完成 SIGN，原刷新请求、actor/reason 与 REFRESHING 一起提交，事务外访问节点。原生接受刷新先持久化旧版本 fence，禁止新旧版本混签；完整的新份额准备检查只限三名持有者的 mTLS 身份。
- 三份绑定原公钥的完成结果齐备后，才原子激活新 epoch、退役旧 epoch 并恢复新业务。已完成旧 SIGN 可读，新的旧版本 SIGN 拒绝。未知/不完整私有刷新不回滚旧签名，也不重新执行随机协议；保持原仪式与冻结。
- wallet_mpc_key_epoch 的 refresh_actor / refresh_reason 保存不可变刷新审计；仍由 WalletMpcKeyEpochRepository 只访问该表，其余三张 MPC 表维持各自单表仓储。init-sql、架构及流程图文必须同步版本约束与核心流程。

- 刷新实际验收已覆盖三算法原生 6 次刷新/27 签名、Java/PG18 接入 3 次刷新/18 签名、26 项定向检查和最终 CTest 6/6；保留缺失私有协议状态时冻结的明确边界。此结论不替代部署恢复或全链最终验收。


## 独立 MPC 签名架构

- Maven 只有 chain-sdks、common、wallet-api；wallet-api 是唯一 Java 应用。签名节点使用 resources/infra/mpc-signer 的 C++ 可执行程序，三名持有者分别保存自己的加密份额；XMR 使用已批准的 Monero 原生 2-of-3 协议。
- Java 不保留 walletSeed、BIP32 私有根、本地钱包私钥签名、全局默认热钱包或旧签名模式。mTLS 客户端私钥仅用于传输身份，不是钱包签名密钥。
- 所有客户、Gas 和中继地址经明确租户的 CustodyAddressService/MpcSigningService 发布，原公钥、key、租户和地址不可变；地址校验与公开编码不依赖私钥服务。
- UTXO 只保留 wallet_mpc_sign → wallet_sign_done 的签名队列阶段；其余提现、事件、RBF 队列继续使用既有 PGMQ 事务和 read_ct 栅栏。原意图、原文、未知状态和资金约束不得因模块清理改变。
- 钱包配置概览使用 mpcConfigured 描述独立签名客户端配置；不恢复 Seed 配置兼容字段。systemd Java 入口为 surprising-wallet.service；原生节点单独部署。

- 旧签名路径清理后已有 SDK 8 / PGMQ 12 / API 101 项定向检查及实际可执行 JAR 两轮无 Seed 启动通过；构建必须 clean 清除旧组件 class。本机完整冷恢复已通过；当前 28 链最终实际回归已通过；提交前仍须核对对应验收材料。
