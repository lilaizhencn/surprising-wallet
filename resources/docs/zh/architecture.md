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

充提历史由 `CustodyTransactionQueryService` 直接编排单表仓储，Console、公开 API 和平台租户详情共用有类型的查询结果。搜索关联地址/交易哈希时先解析同租户主键，再由充提单表仓储执行完整筛选、独立计数与分页，只批量补充当前页的地址和订单。列表与总数使用同一只读快照，不再经过总仓储门面、逐条补查或固定读取 500 条。

## 发布部署

GitHub Actions 使用 JDK 27 / Lombok 1.18.48 构建并执行单元测试，随后通过固定主机公钥、专用受限 SSH 密钥上传带摘要的单 JAR 发布包。阿里云主机只运行 all 模式，不执行 Maven 构建。上传接收器校验摘要和文件名单，以文件锁串行发布；切换失败时恢复旧 JAR 和 systemd unit。部署不执行初始化 SQL，不替换密钥或数据库；旧服务器的发布入口停用。

新主机 PostgreSQL 18 + PGMQ 1.11.1 与应用共机运行，数据库和应用 HTTP 绑定 loopback，公网入口由 Nginx 提供。640 MiB 堆上限配合有限数据库连接池，适用于低负载起步；开链数量仍需按实际负载控制。

运行监控独立于钱包 JVM：systemd 每分钟启动 Python 只读探针，读取主机资源、服务、日志、PostgreSQL/PGMQ 指标及本机/公网健康结果，通过 Telegram 发送故障、恢复和日报。全部监控部署在钱包所在主机，直接向 Telegram 发送通知，不依赖旧服务器；没有额外中间件，也不处理业务队列。整机断电或断网时本机探针无法发送告警。日志有时间与容量限制，详见[日志与运行监控](operations-monitoring.md)。

## 运行模型

`wallet-api` 是唯一可执行 JAR，包含两个普通签名库；默认 `api`。

| `sw.wallet.mode` | 加载组件 | HTTP | 数据源 |
|---|---|---|---|
| `all` | API、链任务、第一签、第二签 | 开启 | 一个共享池 |
| `api` | API、链任务、广播 | 开启 | API 账号 |
| `sig1` | 第一签 | 关闭 | 第一签账号 |
| `sig2` | 第二签 | 关闭 | 第二签账号 |

bootstrap 包按模式显式扫描组件；签名实现由 FirstSignerRegistry / SecondSignerRegistry 通过构造器列表注入，按原生链建立不可变索引并拒绝重复注册。两个模块仅处理 BTC/BCH/LTC/DOGE；EVM/TRON 等账户链继续由 API 内已有链服务签名。第一签和第二签分别绑定单线程 `sig1TaskScheduler`、`sig2TaskScheduler`，API 保持按 Job 类别分池。签名密钥通过限定名称注入，第二签不保留静态私钥或 HTTP 签名入口。所有模式仍通过 PGMQ 推进阶段；合并进程不改变消息重试、幂等和审计路径。


四条 Bitcoin-like 链的签名只依赖必需的密钥与公钥派生组件，无字段注入、ObjectMapper、固定测试网络或测试专用密钥分支。请求中的 Asset 保存数据库链网络，API 地址运行时和签名模块共用 SDK 的 BitcoinLikeNetwork；LTC regtest 使用 rltc 地址。输入金额是唯一合计来源，精确转换最小单位，手续费和汇总检查溢出，产生找零时必须提供目标地址。

运行时资产来源：

| 表 | 作用 |
|---|---|
| `chain_profile` | 链 key、链族、启用网络、确认策略、扫描/提现/归集/划转开关、扫描起始高度、BIP44 coin type |
| `chain_rpc_node` | 每条链的 RPC/fullnode/indexer/faucet 节点、环境标签、优先级、认证和备注 |
| `wallet_system_config` | 全局扫描/提现/归集/划转总开关 |
| `chain_asset` | 原生资产和链内资产定义 |
| `token_config` | token 合约/配置、decimals、归集/提现策略 |
| `ledger_balance` | 按链隔离的用户/系统余额状态 |
| `custody_*` | 租户、凭证、地址分配、充提投影、Webhook、幂等和审计控制面状态 |

应用通过 `chain + symbol` 或 `chain + contract` 解析资产，然后把 runtime asset 传入 scanner、withdraw、collection 和 signing 流程。

`BlockchainRuntimeService` 从启用的 `chain_profile` 解析链元数据，直接调用 `BitcoinLikeChainRuntime`（BTC/BCH/LTC/DOGE）或 `ChainAddressRuntime`（其余链）的地址生成与校验。ADA 的 UTXO 模型由 Cardano 引擎处理，不进入 Bitcoin-like 引擎。账户链扫描、提现和归集由现有业务工作流调用各链实现。项目不再保留通用 BlockchainAdapter、能力注册表或无人调用的转账报价框架；28 条链使用同一套数据库元数据进入租户链列表和开关管理。

运行时配置由 ChainProfileRepository、ChainAssetRepository、TokenConfigRepository 和 ChainRpcNodeRepository 直接映射为有类型的模型；同一张表的运行时查询共用一份映射。Bitcoin-like 使用与其他链相同的 AccountChainProfile 配置，不再重复定义配置类或按网络二次查询。BlockchainRuntimeService 直接依赖配置和资产单表仓储；ChainRpcNodeService 直接依赖 RPC 单表仓储。管理页面需要的字段概览保留为独立查询投影，不再用于重建运行时模型。

`token_config` 的网络为必填非空字段，标准和充提门槛分别只保留 `standard`、`min_deposit`、`min_withdraw`。`TokenConfigRepository` 统一映射为 `TokenConfiguration`；Console、租户资产、Gas/提现和批次流程直接读取类型字段，运行时 Token 定义保留网络身份。运行时查询显式传入已启用链的网络，不做空网络通配或跨网络选择；主键、网络和启用条件直接在单表 SQL 中筛选。初始化基线直接保存完整的 Token 和链资产配置及稳定 ID，不通过旧字段补齐、双写或多次种子更新构造目标状态。

提现创建复用同一运行时地址校验，先验证格式、校验和及 UTXO 网络，再创建订单和冻结余额。Console 根据 Token 配置显示资产明细；链列表契约不再返回闲置报价能力字段。

API 的 `PubKeyConfig` 只通过正常构造器接收角色密钥提供者，不包含测试根公钥、测试构造器或默认测试网络。Bitcoin-like 地址输出为不可变的 `AddressMetadata`；派生使用显式网络和既有非硬化路径 `m/44/coinType/biz/userId/index`，2-of-3 脚本与签名端保持一致。热钱包与充值地址使用 SDK 的同一网络定义，未知网络直接拒绝。

`BitcoinLikeChainRuntime` 读取区块和交易事实，不再回调 `TransactionService` 或持有账务结算依赖。`BitcoinLikeDepositScanService` 按数据库游标取得完整 `UtxoBlock`，交给 `UtxoDepositService` 原子完成 canonical 区块、UTXO、充值、租户投影、PGMQ 通知与游标更新；任一 RPC 交易读取失败时不推进区块。交易哈希和 int 输出序号独立传递，不经过旧 DTO 或字符串解析。`UtxoConfirmationJob` 使用提现调度池，调用 `BitcoinLikeConfirmationService` 观察在途充值与提现；新扫描开关关闭仍继续确认。补确认与重组统一按 canonical 区块、UTXO、充值/账本的顺序加锁。内部提现队列由 `QueuedUtxoWithdrawalService` 直接编排订单与冻结，原混合事务服务和无效果的余额汇总转发均已删除。


充值和重组的事务由 `DepositService` 编排，所有链扫描器直接调用该用例。确认入账时，DepositRecordRepository 的受租户约束状态更新直接返回记录 ID 与入账代次；随后更新账本并调用必需的 CustodyDepositProjectionService，投影、流水、赤字抵扣、事件和 API 来源 Webhook 与余额一起提交或回滚。投影服务要求已有事务，不再使用 Observer 接口、ObjectProvider 或空列表构造路径。SQL 只位于对应单表仓储；地址归属通过同租户派生坐标与地址版本在应用层组合，优先匹配精确地址。

EvmDepositScanner 直接读取链配置、地址、Token 和扫描记录单表仓储，直接调用 DepositService 入账，不再经过通用数据门面。它只有一个构造器；RPC 发现、HTTP 工厂及 WalletRuntimeConfigService 均为必需依赖。测试节点和确认数在测试夹具/测试数据库中配置，生产类不保留固定 RPC、固定确认数或跳过扫描开关的分支。运行开关服务直接依赖 ChainProfileRepository 与 WalletSystemConfigRepository，系统配置按 key 查询并返回有类型的 Setting。

## 模块

| 模块 | 职责 |
|---|---|
| `wallet-api` | Spring MVC 单体应用：Custody/Console REST API、Servlet/Cookie 与 HTTP 异常映射、Job 调度、业务 Service、链领域与持久化模型、链引擎（Bitcoin-like/EVM/TRON/Solana/TON/Aptos/Sui/XRP/Cardano/Polkadot/NEAR/Monero/HyperEVM/HyperCore）、充值、账本、提现、归集、Gas、Webhook 和启动校验 |
| `wallet-sig1` | BTC-like 2-of-3 第一签服务：对 BTC、BCH、LTC、DOGE 提现交易生成部分签名，轮询 PostgreSQL / PGMQ 队列；处理中任务可恢复 |
| `wallet-sig2` | 第二签服务：完成最终签名并入 PGMQ 广播队列，由 wallet-api 广播；处理中任务可恢复 |
| `common` | 无 Web 耦合、至少被两个上层模块使用的共享契约和 PGMQ 基础设施：运行时链/资产契约、不可变 UTXO 签名请求/部分签名/结果契约、钱包密钥配置与加载、通用常量 |
| `chain-sdks` | 与业务和数据库无关的链 SDK：BitcoinJ 网络参数、Bitcoin-like RPC DTO、多签地址、SegWit 交易、UTXO 选择、BIP32、SLIP-0010 Ed25519 派生与签名、TRON gRPC/Protobuf/ECKey |

所有模块的 parent POM 为根目录 `pom.xml`，继承 Spring Boot starter parent，以 Java 27 作为统一编译和运行基线，并提供统一的版本和依赖管理。

模块依赖遵循 `wallet-api -> wallet-sig1, wallet-sig2, common, chain-sdks`，签名服务分别直接依赖共享库和链 SDK。`wallet-api`
内部采用 MVC 分层：Servlet 请求、Cookie 读写和 HTTP 状态映射只存在于 Web 层的 Controller、Filter
和异常处理器；Controller 只做参数校验和响应映射，Job 只负责调度、节流、防并发和异常隔离，选币、状态流转、队列消费、RPC 调用和审计由业务 Service 承担。
Web 层包固定为 `controller/`、`job/`、`config/`、`exception/`、`filter/` 和 `model/`；认证过滤器及其
Servlet 请求包装器不得放入 `config/`，充值扫描使用的检查点模型归入 `chain/model/`。链上网关统一位于
`com.surprising.wallet.gateway`，链操作协调器统一位于 `com.surprising.wallet.coordinator`，二者不得放回
`service/` 包；协调器使用 `@Component`，业务工作流才使用 `@Service`。
所有应用 Service（包括工作流和实现类）统一位于 `com.surprising.wallet.service`，使用 `service/**` 单层包，
不再按业务域拆分 `account/service`、`custody/service` 或 `devfaucet/service`，也不保留 `impl` 子包。
所有业务域的 PostgreSQL/JDBC Repository 统一位于 `com.surprising.wallet.repository`，业务包下不再保留
`account/repository`、`custody/repository`、`deposit/repository` 或其他分散的 Repository 子包。
Service 和 Job 统一使用构造器注入；可选基础设施依赖以 `Optional<T>` 表达。
`CustodyAddressService` 直接编排地址、主题派生、链地址、Gas 账户、账本和审计单表仓储。地址分配在原事务内解析一次主题并持有锁；同租户主题/版本幂等、链地址归属、EVM 委托投影和审计共同提交或回滚。EIP-7702 账户单表仓储为正常构造器依赖，不再通过测试构造器或 Optional 跳过投影。托管地址行由 `CustodyAddressRepository` 唯一映射为 `CustodyAddressRecord`，不经总仓储二次重建。
地址列表、总数与搜索使用同一单表筛选条件，排除本租户所有 Gas 地址后再分页。平台租户详情复用地址服务的内部查询。可提现地址按租户/链/主题读取，再批量组合链地址和账本余额；不逐页扫描全租户或逐条查询余额。Gas 历史直接按租户和账户/地址筛选分页；账户身份查询不再加载账本余额。Gas 使用明细直接返回有类型的公开查询字段，不在总仓储中复制和重命名 Map。
APTOS、ADA、NEAR、DOT、SOLANA、SUI、TON、XRP 的扫描器和交易服务必须通过构造器接收运行时开关服务，不再通过字段注入或空依赖跳过校验。Solana 扫描器的 ATA 地址服务和 TON 扫描器的索引器同样是必需依赖；TON 网络始终读取数据库 profile，不保留默认测试网络或测试专用构造器。单元测试提供明确替身，数据库链路测试使用实际开关服务。
`CustodyEventService` 显式编排领域事件、启用端点和 Webhook 投递记录，加入调用方事务；独立调用时也原子提交或回滚。充值投影直接依赖该服务和审计单表仓储，不再经过 `CustodyRepository`。提现创建、状态同步和资产找回共用同一事件入口，保持 API 来源才生成 Webhook 的规则。事件按业务键去重，投递按端点与事件的已有唯一键去重，重复调用不重置投递状态。托管控制面的事件发布使用必需构造器依赖，不提供测试专用或兼容构造分支。
`common.queue` 提供无 Web 依赖的 PGMQ JDBC 适配器和消费事务执行器，唯一启动入口显式导入配置。
业务 Service 通过适配器使用队列，不直接编写 SQL；`ChainFeeRateRepository` 只访问 `chain_fee_rate`。
EVM 普通交易和两个 EIP-7702 协调器直接注入 `EvmNonceRepository`。Nonce 预留通过一条 `INSERT ... ON CONFLICT ... RETURNING` 完成，独立调用也可并发分配，加入已有事务时可随签名/Gas 失败回滚；不再经通用门面传递，也不做 BigInteger/long 往返转换。输入沿用 uint256 校验且不返回范围外值；生产代码不保留覆盖已预留计数的通用 upsert 或测试 DTO。
RPC 客户端直接注入 `ChainProfileRepository` 与 `ChainRpcNodeService`，不再经过 `ChainJdbcRepository` 转发链配置。节点选择、故障切换、认证和 provider 限流只有一条路径；本地测试通过配置仓储提供节点，生产代码不保留固定测试端点或空依赖构造分支。`ChainRpcNodeService` 的环境名和并发上限由唯一构造器注入。

`WithdrawalSettlementService.confirm` 只接收租户、链、订单号和交易哈希。事务内锁定所属订单，校验状态与哈希，再从订单读取币种、扣款账户及金额加客户手续费；不接受调用方重复传来的扣款事实，也不从其他账户回退。缺少扣款账户时拒绝结算。冻结扣款、订单条件确认一起提交或回滚；并发重复确认在订单行锁后读取终态并返回无变化。各链交易服务、UTXO 结算、账户链工作流和 EVM 批量提现协调器直接调用该用例。链服务只保留 RPC 确认或内部充值识别实际需要的参数，已删除 EVM、Aptos、Monero 的无租户确认重载和账户链资产服务中的扣款参数拼装。

各模块分别声明自身实际使用的 starter，避免依赖传递造成的隐式可用。

链配置、链地址、交易、账本、订单和扫描记录由调用方直接注入各自的单表仓储，不经过统一链数据门面。跨表规则由明确用例处理：`CollectionPlanningService` 计算充值减已归集金额、抑制在途重复归集并解析租户归集地址；`UtxoSelectionService` 在调用方事务内完成租户输入选择和锁定；`WithdrawalClaimService` 以重组赤字检查和条件更新领取租户提现；`EvmBatchOwnershipService` 检查批次接管状态，批次身份始终使用 UUID。`AccountSequenceRepository.reserve` 用单条 UPSERT RETURNING 预留序号，独立调用也不会重复分配，加入业务事务时随外层回滚。测试使用实际仓储/用例的正常构造器，不继承生产数据门面或在生产代码中保留测试装配入口。

Repository 采用“单表一仓储”约束：每个 `@Repository` 只访问一个数据库表，Repository 名称与表职责一一对应。
Console 认证由 `CustodyAuthService` 直接调用用户、租户、会话和审计单表仓储，登录事务由该服务持有。
用户、租户和有效会话事实在认证服务内组合，生成包含当前状态的认证记录，不再引入独立查询中间层。
登录、会话访问和平台管理员初始化不再经过 `CustodyRepository`、`CustodySecurityRepository`。
托管业务记录位于 `model/custody`，调用方不依赖总仓储的嵌套类型。用户、租户、会话、API Key 和 IP 规则由对应单表仓储映射为有类型记录；认证服务不再解析数据库 Map、UUID 或 Timestamp。
`CustodyApiKeyService` 直接编排密钥、租户、IP 规则、nonce 和审计仓储；`CustodySecurityService` 直接处理 IP 规则、租户开关和审计。用户列表和密钥列表采用不含秘密字段的专用投影。`CustodyTenantService` 在原事务内直接撤销租户会话、条件解锁管理员并写审计；安全维护服务直接清理 nonce、会话和幂等键。租户基础响应直接复用 `TenantRecord`，不再逐字段重建相同 DTO。`CustodySecurityRepository` 已删除，不保留安全域转发或无效的密钥版本/过期时间兼容重载。API Key 与 Webhook 表不再保存未使用的 `secret_version` 列，密文格式自身的版本标记是唯一解密版本来源。
`CustodyRepository` 与 `CustodyAssetDashboardRepository` 已删除。租户写操作由 `CustodyTenantService` 编排租户、管理员、会话和审计；控制器直接调用 `CustodyTenantQueryService` 获取租户分页、详情与上手清单。Gas 身份、地址和原生币余额由 `CustodyGasService` 批量组合；资产持仓、配置展示与价格由 `CustodyAssetDashboardService` 负责。审计、幂等与提现投影创建直接依赖各自单表仓储，不恢复通用转发门面。租户链展示仅批量组合 Gas 定义与托管地址，不依赖 Gas 服务，避免 Gas → 地址 → 租户链 → Gas 的环。Service 不持有 `JdbcTemplate`、不执行 SQL；单表仓储禁止 JOIN、跨表子查询或同时读写多张业务表。

租户页先在数据库过滤和分页，再按本页租户批量统计地址、Gas 和用户，列表与总数共用字面搜索条件。查询服务用只读一致快照组合结果。账户身份采用链与区分大小写的账户号，Gas 排除不跨链传播；已观察持仓在链或资产关闭后仍可见，看板配置视图单独遵守平台与租户启用规则。价格和赤字仓储直接返回领域记录，不再逐层复制相同字段。此重构复用数据库基线，无新增表、字段、状态或兼容迁移。

`com.surprising.wallet.chain.model`、业务数据库记录和转账请求模型属于 `wallet-api` 内部领域层。
Bitcoin-like RPC DTO 与 Ed25519 链枚举、派生结果和 SLIP-0010 密钥提供者位于 `chain-sdks`；`common`
不直接声明 BitcoinJ 或 EdDSA，也不再通过全局 `Constants.NET_PARAMS` 暴露链 SDK 类型。数据库/JDBC
模型不得进入 `chain-sdks`。

签名协议为 `UtxoSigningTask(request) → UtxoPartialSigningTask(partial) → UtxoSigningResult`。输入自身包含 outpoint、金额、地址及派生坐标，不传平行 UTXO/地址数组。签名实现返回值或抛出异常，不修改业务实体或 JSON；只有队列与数据库边界使用 Jackson。第二签从原始交易计算真实费用并核对输入身份。

`chain_signing_transaction` 分别保存有类型的 request、signed_transaction 和完整 attempts 快照；链、租户、请求 UUID、业务类型及当前哈希由 JSON 生成数据库列，唯一键、索引及租户外键直接使用生成列，避免手工同步两份身份。API 内的 UtxoSigningTransaction 管理生命周期，签名服务不接收数据库状态或历史。UtxoBroadcastService 在同一签名行锁下校验结果身份，持久化签名事实后广播；初次广播 UNKNOWN 也保留哈希并继续确认。SIGN_DONE 队列的持久化结果在失败重试期间保留原始签名，禁止释放任何已有已签尝试的输入。

批处理、请求提交、RBF、广播、确认和内部归集识别直接调用 ChainSigningTransactionRepository，总仓储不再转发签名表方法。RBF 保存完整请求与签名快照；归集原尝试确认时恢复对应金额与矿工费。提现 RBF 保留客户支付金额与客户手续费，确认前在订单行锁下核对实际上链请求的用户、币种、收款地址、金额与客户手续费，匹配后标记 CONFIRMING，再由 WithdrawalSettlementService 按订单扣款。签名行、订单、UTXO 与余额更新在同一事务中提交或回滚，终态不可重开。此收敛复用现有表、字段和约束，不修改数据库基线。

## 调度与运行时开关

- wallet-api 的每个 `@Scheduled` 入口都通过 `wallet_task_lease` 获取数据库租约；租约按任务名唯一，执行期间由心跳续租，实例故障后由其他实例接管。租约只负责调度所有权，业务数据仍由各自的事务和单表 Repository 维护。
- 独立 sig1/sig2 模式使用独立账号连接同一个 PostgreSQL，仅访问授权的 PGMQ 队列。消费按消息领取，处理事务持有该消息行锁；read_ct 校验阻止旧消费者确认新领取的消息。
- UTXO 批处理通过 `UtxoSigningSubmissionService` 锁定持久化签名请求，校验租户和请求版本，再将 PGMQ 入队及 `chain_signing_transaction.enqueued_signing_request_id` 去重凭据与订单、UTXO 状态一起提交。无需 Outbox 表或派发任务；消息归档后凭据仍阻止同一请求重复投递。消费事务原子完成业务状态更新、下一阶段入队和当前消息归档。异常回滚，指数退避重试；第 20 次失败进入死信队列。
- 提现领取按 `tenant_id` 轮转，Webhook 领取按租户公平排序；任何租户的慢 RPC、慢回调或积压都不能长期饿死其他租户。幂等键、状态机、锁定余额、失败释放和链上对账共同构成资金闭环。
- EIP-7702 归集与提现恢复先按链、网络和 `BROADCAST_UNKNOWN` 状态读取批次主键，再在尝试单表中限定这些主键、检查冷却时间并排序分页。批次全局主键及租户/批次复合外键保证返回尝试保留原租户归属；无目标批次时不读取全局 UNKNOWN 窗口。
- Account-Chain 调度器每秒做轻量到期检查，UTXO 调度器每 5 秒检查；只有达到该链扫描周期时才访问 RPC。
- 扫描周期由 `WalletRuntimeConfigService` 集中维护，快速链为 2-5 秒，ETH 为 12 秒，ADA 为 20 秒，DOGE/LTC/BTC/BCH 为 15/30/60/60 秒，未知新链默认 10 秒。
- 每次实际执行都实时读取 PostgreSQL 中的 `global.all.enabled`、全局任务开关和 `chain_profile` 链级任务开关，后台修改无需重启。
- 开关关闭时阻止新的扫描、提现构建、归集和转账动作；已广播交易的确认、对账与回调继续运行，避免资金状态永久停留在中间态。
- 已观察充值通过独立确认用例继续入账；必需的托管投影不再次检查新扫描开关或租户链开通状态。找不到同租户托管地址时抛错，充值记录、余额、投影和事件全部回滚。
- XMR 使用独立串行全流程任务，不再由通用 Account-Chain 扫描任务重复执行。

## 链族

当前支持 28 条链，其中 EVM 链 13 条、非 EVM 链 15 条；架构图和下表列出全部支持链。

| 链族 | 链 | 本地测试支持 | live/testnet 支持 |
|---|---|---|---|
| Bitcoin-like UTXO | BTC, LTC, DOGE, BCH | Docker regtest 节点 | 外部 RPC 配置 |
| EVM | ETH, BNB, POLYGON | Hardhat fork | Sepolia、BSC testnet、Amoy |
| EVM L2 / L3 | ARBITRUM, OPTIMISM, BASE, AVAX_C, X_LAYER, ROBINHOOD_CHAIN | Hardhat fork | Sepolia L2、Avalanche Fuji、X Layer Testnet、Robinhood Chain Testnet |
| EVM L2 (新增) | LINEA, SCROLL, UNICHAIN, HyperEVM | Hardhat fork | Linea Sepolia、Scroll Sepolia、Unichain Sepolia、HyperEVM testnet |
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

密钥通过环境变量注入唯一 application.yaml，按模式加载本方 Seed 与必要的扩展公钥。恢复私钥离线保存，运行时只配置 recovery-public-root。api 保留账户链使用的 sig2 和 Ed25519 私钥；sig1 / sig2 各自只要求本方 Seed。all 同一进程持有两方私钥，不提供进程级密钥隔离。Bitcoin-like 链使用其中三组 BIP32 root：

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

wallet-api 启动时会先校验当前模式所需的 `sw.wallet.keys`，再检查 `chain_profile`、`chain_rpc_node`、默认热提钱包和 `wallet_system_config`。密钥配置缺失或非法会直接启动失败；校验通过后，默认热提钱包会通过代码推导并与 `chain_address` 比对，缺失或不一致同样会启动失败。同一链同一时刻只能启用一个网络；非生产环境可以同时保存 devnet/testnet profile 并切换启用，生产环境只允许启用生产网络。启用 profile 必须至少有一个匹配当前环境的 RPC 节点。校验结果会按链打印状态，缺失配置或关闭开关会输出 WARN。

## 运行目录

`resources/` 下集中存放 infra（EVM fork、Polkadot sidecar、regtest、Move 合约、systemd 服务）、docs（文档、SQL）和 scripts（测试启动脚本）。

### PostgreSQL 队列与费用报价

PGMQ 扩展管理 `pgmq.q_wallet_*`、对应归档表和死信队列。`common.queue.PgmqClient` 只访问扩展对象，不访问业务表。
`ChainFeeRateRepository` 对应 `chain_fee_rate`（链、整数费率、更新时间），由费率更新、提现和 RBF 服务直接访问，提供网络报价。
队列消费由 `WithdrawalQueueService`、`FirstSigningService`、`SecondSigningService` 和 `RbfBumpService` 编排；Job 仅调度。
已移除独立失败重试和超时重投签名 Job，PGMQ 未确认消息在可见性超时后恢复。
队列归档用于审计；运维使用 `wallet_replay_dead(queue, id, reason)` 重放死信，并记录数据库操作者和原因。
PGMQ 的消息行锁与 read_ct 校验保证领取和确认安全，链上 RPC 仍须依赖交易 ID 幂等及未知结果对账。

## Cloudflare 租户 Demo

`resources/tenant-demo` 是独立租户应用，部署为 Cloudflare Worker + Static Assets + SQLite-backed Durable Object。每个部署固定对应一个钱包租户，浏览器不能选择租户对象。用户、会话、余额和流水保存在该对象内；仅通过 HTTPS Custody API / HMAC Webhook 与阿里云钱包协作，不访问钱包数据库。API 密钥、Webhook 密钥及管理令牌使用 Worker Secrets。master 分支的 Demo 目录变更触发 GitHub Actions，先验证独立测试 Worker，再部署正式 Worker；不会触发钱包后端发布。

LTC regtest 地址与网络标识依据 [Litecoin Core v0.21.4 CRegTestParams](https://github.com/litecoin-project/litecoin/blob/v0.21.4/src/chainparams.cpp#L251)。SDK 只用于钱包地址与交易，不执行 Litecoin SPV 验证。


EIP-7702 归集与提现工作流共用 `Evm7702RpcClient`，由其选择当前链网络的 RPC 节点、构造 Gas 估算信封并读取合约、代码哈希和回执。普通 EVM 与两个批量工作流共用 `EvmTransactionReceipt` 的标准字段、L1 费用及 operator 费用字段；密钥转为 EVM 凭证由现有 `AccountSecp256k1KeyService` 负责。工作流直接调用链客户端和资产服务，取消同名私有转发方法，批次领取、广播恢复和账务事务边界沿用各自用例。

Webhook 由现有 `CustodyWebhookService` 直接编排端点、投递、尝试、事件和审计单表仓储，不再经过总仓储。具体 `gateway/CustodyWebhookHttpClient` 负责 URL 策略、UTF-8 签名、超时和响应上限；没有传输接口或测试专用构造器。投递器只保留带指定执行器的正常构造器。验证端点时先在事务外完成 HTTP，随后用短事务条件激活并写审计；投递领取和回执提交各有独立事务边界。

Webhook 单表仓储返回类型记录。端点公开投影不含密文；投递查询在单表分页后批量补充同租户事件类型与业务标识，平台租户详情复用该查询。领取按租户公平选择并以单条 UPDATE RETURNING 返回递增后的计数；全局尝试编号约束结果提交，即使同一工作线程重领，旧任务也不能写回。投递状态、尝试回执和端点成功时间一起提交或回滚。无效的 `verification_token_hash` 列和随机令牌生成已删除，端点创建保存真实 `created_by`；终态允许空重试时间，待处理状态仍由数据库约束要求有效时间。


提现对账只推进托管投影，不将旧快照回写链订单，也不再次结算客户冻结余额。投影按租户、提现 UUID 和原状态条件更新；同一事务记录已结算的 `amount + fee` 为 `WITHDRAWAL / DEBIT` 流水，以托管提现 UUID 为幂等参考，再处理 Gas 与事件。Gas 结算路径统一记录 Gas 地址和原生币费用，投影不再抢先写入提现 Token 的重复手续费流水。流水或事件失败时投影一起回滚；并发或过期投影无写入副作用。此处沿用现有流水表及唯一约束，不新增数据库结构。


Gas 资金变更由 `CustodyGasAccountingService` 直接编排单表仓储，拥有预留、释放、结算的事务；不通过总仓储。预留先锁定同租户的提现或 7702 批次行，从操作记录读取链和业务号，再检查已有收据及 Gas 账户。相同预留重放复用收据，金额不同或已结束的预留不能重新冻结。Gas 账户锁串行化逾期检查与结算，余额、使用状态和原生币费用流水一起提交。`CustodyGasReconciliationService` 分页读取当前订单及链费用，未变化的预留不能阻塞后续记录；OVERDUE 使用已保存的实际费用，超时但未确认的预留继续冻结。已观察费用读取原生资产保存的精度，不受新业务启用开关影响。金额同时满足余额表 18 位小数和回执表 54 位整数的精度上限。以上使用原有表、状态、唯一键和主键，不修改数据库基线。


提现状态投影由现有 `CustodyWithdrawalReconciliationService` 直接编排；入口只传租户和提现 ID，短事务中锁投影并读取当前所属订单，以订单事实写扣款、Gas 与事件。固定大小的 UUID 游标分页与同租户订单批量状态比较防止旧的未变化记录挡住后续提现；每条投影独立失败隔离。旧状态快照模型及其总仓储传递路径删除。

## EIP-7702 批次业务归属

`Evm7702CollectionRepository` 和 `Evm7702WithdrawalRepository` 已删除，不保留多表业务仓储或内部手工创建的仓储。两个现有 Coordinator 直接编排单表仓储，负责领取、签名持久化、提交、广播未知、恢复错误、回执与未广播失败的事务；Nonce、Gas 和逐笔提现结算加入相同事务。批次与签名尝试的 UNKNOWN 更新必须一起提交或回滚。签名前的新配置与中继路径由 `Evm7702ConfigurationService` 显式校验；配置单表统一映射 `Configuration`，两个工作流复用该记录，历史确认只读取原配置版本，不要求中继仍启用。

工作流直接持有批次、批次项、签名尝试和配置仓储，拥有运行目标、未知尝试与待确认查询；先筛选父批次再对尝试排序和限额。运行目标携带已读取的账户链配置，避免再次查询相同配置；待确认页按版本复用配置读取。归集项身份与重播尝试由单表仓储直接返回类型投影。地址创建直接写 EIP-7702 账户投影。测试通过正常 Spring 事务代理调用协调器与提现创建服务；数据库注入失败验证跨表回滚。本批复用现有表、字段、状态与约束，不修改数据库基线。

## 租户链、水龙头与 HyperCore 的实际业务归属

租户链由现有 `CustodyTenantChainService` 直接负责平台与租户开关、资产权限和审计事务。列表以一致快照分别批量读取链配置、租户状态、资产、Token、7702 配置和 Gas 地址，直接生成公开视图；没有中间 ChainRecord / TokenRecord 或逐链 7702 查询。Token 按链与网络匹配，提现继续要求租户链开启、平台提现与转账同时开启、资产及当前网络 Token 启用。

开发水龙头由现有 `DevFaucetService` 直接发现候选、创建任务、发送、恢复及对账；BTC / ETH 原生币、ETH 的 USDT / USDC、API 客户地址与 Gas 地址规则不变。租户链和已创建补币记录批量读取，任务创建唯一键与条件领取仍防止重复发送。金额生成器为正常注入组件，生产服务只有一个必需构造器，没有手工组装仓储的测试分支。

HyperCore 扫描器直接写代币与现货元数据，交易服务直接读写操作记录。`HyperCoreDepositService` 仅拥有快照行锁、正差额和充值入账的事务；快照与充值 / 账本 / 租户投影共同提交或回滚。三个旧业务仓储门面均删除，单表 SQL 留在各表仓储；复用已有数据库表、字段和约束，初始化基线无变化。

全局任务策略由现有 `WalletRuntimeConfigService` 唯一解释：一个全局开关快照、缺失配置默认开启、停用配置按关闭处理，以及平台 / 任务 / 链的生效条件。配置管理与概览注入该服务，复用同一类型值；各自保留 Token 归集、密钥、RPC 和生产环境诊断。该服务仅依赖链配置与系统配置单表仓储，不增加策略框架或缓存；关闭新任务仍不抑制已有链上事实的确认与入账。复用现有五个配置键，数据库基线不变。

配置表的单表读取统一返回类型模型：链档案和 RPC 节点复用运行时模型及唯一 ResultSet 映射，资产复用既有类型；配置概览直接使用这些结果，不再复制四组内部行 DTO。RPC 概览只读公开元数据，统计不读取认证内容。链列表批量组合 Token 列表与 RPC 分组计数，Token 列表先筛选再批量组合资产和包含禁用网络的链档案；详情复用已读取的链、RPC 和 Token，列表与详情在只读一致快照内组合。List 不再转 CSV 再拆回 List。链、网络和资产使用类型键，各查询原有的大小写、启用状态及历史精度条件保持在对应单表仓储。列表与详情的只读组合不改变资金事务边界。

配置写入按职责直接调用 `WalletChainConfigService`、`WalletRpcConfigService` 和 `WalletTokenConfigService`，Controller 不再经过综合配置服务转发。链服务负责网络启用和链详情组合；RPC 服务负责凭据、探测和必需用途；Token 服务负责合约校验及资产配置同步。链详情把已读取的 profile 交给 RPC / Token 服务组合响应，沿用同一快照事务及 5 次查询上限。Token 写入直接读取类型配置行，不先构建展示模型。

链与 Token 共用 `WalletConfigIdentityGuard` 判断五类地址、余额及交易记录是否已经锁定身份字段；这项业务约束不进入仓储。每个配置写用例仍将配置变更、资产同步及审计放在自己的事务内。平台配置审计查询归入已有概览服务。各入口保持平台管理员权限，RPC 响应继续仅暴露凭据是否配置。复用现有表、状态和配置键。`chain_asset.min_transfer` / `min_withdraw` 与账本一致按资产主单位保存，数据库基线改为 `numeric(78,18)`，避免小数门槛被舍入成整数；单表仓储参数同时收敛为 `BigDecimal`。不新增迁移或兼容分支。
