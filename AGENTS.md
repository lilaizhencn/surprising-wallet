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
| Web 层 | REST 参数校验、委托、HTTP 异常映射、认证过滤和 Web DTO；保持原有 Controller/Job 行为 | `custody/controller/**`、`job/**`、`custody/exception/**`、`custody/filter/**`、Web `model/**` |
| 应用层 | 业务用例、工作流编排、运行时开关和定时任务实际调用的 Service | `service/**` |
| 领域与链集成层 | 领域模型、链适配器、RPC 客户端、链上协调器和观察者 | `chain/**`、`account/coordinator/**`、`custody/gateway/**`、`custody/observer/**`、业务 `model/**` |
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
wallet-sig1 → common, chain-sdks
wallet-sig2 → common, chain-sdks
```

- `wallet-api` 内部按 MVC 分层协作，不再通过 `wallet-service` 模块传递业务类。
- `common` 和 `chain-sdks` 不得依赖 `wallet-api`；签名服务也不得依赖 `wallet-api`。
- 项目不再保留 `wallet-service` 模块、POM 或独立构建入口。
- @Scheduled 只允许在 wallet-api（及 wallet-sig1 / wallet-sig2）中。

### job 包划分

```
wallet-api/job/
├── deposit/       ← 充值扫描（UTXO 链逐块扫描 + 交易写入）
├── withdraw/      ← 提现（UTXO 提现+归集合一批处理、签名恢复、费率、RBF、广播）
├── custody/       ← 托管维护（Gas 对账、Webhook 派发、安全清理、提现对账、资产找回）
├── collection/    ← 归集（Account-Chain 通用归集 + EVM EIP-7702 批量归集/提现）
└── devfaucet/     ← 开发环境水龙头
```

### 线程池

每个包含 `@Scheduled` 的 jar 必须配置独立线程池：

- **wallet-api**：按 job 类别分池，`SchedulingConfig.java` 中定义 `@Bean ThreadPoolTaskScheduler`（custody/evm7702/account/deposit/withdraw 各一个）。业务 Service 不直接声明 `@Scheduled`。
- **wallet-sig1 / wallet-sig2**：通过 `spring.task.scheduling.pool.size` + `thread-name-prefix` 配置，或显式 `@Bean TaskScheduler`。

禁止所有 `@Scheduled` 共用一个默认线程池。

### 命名约定

- Job 类名不要 `Abstract` 前缀：使用 `UtxoDepositScanJob`（不是 `AbstractUtxoDepositScanJob`）、`BtcUtxoBatchJob`。
- 类名体现职责：UTXO 批处理包含提现+归集，用 `*UtxoBatchJob`（不是 `*WithdrawJob`）。
- Account-Chain 通用 job 使用简短模式：`AccountChain{DepositScan,Collection,Monero,WithdrawalProcess,WithdrawalConfirm}Job`。

### MVC 入口边界

- `wallet-api/job/**` 只负责调度、节流、防并发和异常隔离；选币、状态流转、队列消费、RPC 调用和审计必须由 `service/**` 承担。
- `wallet-api/custody/controller/**` 只负责 HTTP 参数校验、身份上下文和响应映射，不得直接依赖 `repository/**`。
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
