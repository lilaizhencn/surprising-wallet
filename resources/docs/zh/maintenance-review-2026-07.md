# 可维护性审查与收敛记录

初审：2026-07；本轮源码复核：2026-10-24。

## 审查结论

本轮的主要维护成本来自通用仓储门面、重复模型转换、配置职责混杂、重复 RPC，以及业务状态在多层之间传递。判断依据是每一层是否拥有明确的业务规则、事务、协议或持久化职责。仅有一个实现的接口、单纯改名转发、为测试保留的第二条生产路径优先删除。

以下记录本轮确认的 22 项问题及收敛方式。它是本轮范围清单，不表示项目不存在其他技术债务。

| # | 发现 | 当前收敛方式 | 主要验证入口 |
|---|---|---|---|
| 1 | 可执行链元数据和路由分叉，存在空适配器及报价框架 | 共用数据库 profile 与 ChainType，28 条链进入实际运行时；移除无消费者框架 | BlockchainRuntimeServiceTest、RpcClientRoutingTest |
| 2 | ChainJdbcRepository / CustodyRepository 通过大量方法统一转发 | 删除总门面；现有用例直接依赖单表仓储，实际跨表规则留在用例 | ArchitectureBoundaryTest、CustodyOperationsIntegrationTest |
| 3 | 充值观察者只有单一实现，却允许缺失或跳过投影 | DepositService 明确调用必需的租户投影；充值、账本、重组及事件同事务 | CustodyDepositProjectionIntegrationTest、CustodyDepositFinalityIntegrationTest |
| 4 | Map → DTO → Map 及相同 ResultSet 的重复映射 | 仓储返回类型行；复用已有订单、地址和配置模型；仅保留真实 JSON 与查找索引 Map | EvmBatchRowsIntegrationTest、ChainProfileRepositoryIntegrationTest |
| 5 | UTXO 签名模块装载无消费者的 EVM / TRON 签名器，反射查找实现 | 模块只装载实际 UTXO 签名器；构造器 List 注入并校验重复链 | UtxoSigningCryptoTest、UtxoSigningPipelineIntegrationTest |
| 6 | 签名消息以可变 JSON 保存并多次转换 | 使用不可变 UTXO 请求、部分签名及结果契约；保留请求身份与历史尝试 | UtxoSigningPipelineIntegrationTest、BtcCollectionRbfConcurrencyIntegrationTest |
| 7 | API 地址校验与运行时规则重复 | API 使用实际运行时校验；保留链与网络校验规则 | CustodyWithdrawalAddressValidationTest、各链地址测试 |
| 8 | 同数据库 PGMQ 之前又维护钱包 Outbox 和派发层 | 同事务写签名记录的持久去重凭据及 PGMQ；删除多余 Outbox | UtxoSigningSubmissionIntegrationTest、UtxoSigningPipelineIntegrationTest |
| 9 | 薄转发接口、费率服务以及仅供测试使用的生产构造路径 | 删除无职责层；TRON 客户端及七个 Ed25519 服务只保留正常依赖构造器 | AccountChainRequiredRuntimeTest、TronTridentClientTest、Ed25519KeyServicesTest |
| 10 | Token 标准及金额门槛双列、双写和 COALESCE 优先级 | 一个按网络定位的 TokenConfiguration 与唯一 SQL 基线 | TokenConfigurationIntegrationTest、CustodySchemaContractTest |
| 11 | 先截取有限行，再做地址 / Gas 搜索和分页 | 在单表 SQL 中先按完整租户范围过滤计数，再分页及批量补充 | CustodyOperationalQueriesIntegrationTest、CustodyAddressAllocationIntegrationTest |
| 12 | UTXO runtime 与 TransactionService 循环依赖，7702 业务放在仓储，多次转发扫描 | 删除循环及 DTO 往返；实际协调器拥有批次事务；扫描 Job 直接调用 DepositWorkflow；TRON 共用真实发送逻辑 | BitcoinLikeUnifiedUtxoRuntimeMigrationTest、Evm7702ProductionFlowIntegrationTest、AccountChainDepositSchedulingTest、TronAccountChainServiceTest |
| 13 | WalletConfigManagementService 混合链 / RPC / Token 维护，CSV 与 Map 装饰重复 | 删除混合门面，入口直接调用三个具体配置用例；共用真实身份保护规则 | CustodyControlPlaneQueriesIntegrationTest、WalletConfigManagementContractTest |
| 14 | 7702 两种工作流重复组装 RPC 与回执类型 | 共用具体 Evm7702RpcClient 与扩展回执；业务流程继续独立 | Evm7702RpcClientTest、本地 7702 流程 |
| 15 | 新任务关闭后，已经观察到的充值及签名尝试停止确认 | 确认、投影、重组与新任务开关分开；缺失必需托管归属整笔回滚 | UtxoDepositWorkflowIntegrationTest、CustodyDepositProjectionIntegrationTest |
| 16 | UNKNOWN 恢复先取全局窗口，再过滤链 / 网络 | 先查询所属链、网络及状态的父批次，再对尝试应用冷却、排序和 LIMIT | EvmUnknownAttemptRecoveryIntegrationTest |
| 17 | 结算跨多层反复传币种、账户和扣款金额快照 | 四个身份参数进入事务；锁定所属订单后推导扣款事实与客户费用 | WithdrawalSettlementIntegrationTest、UtxoSigningPipelineIntegrationTest |
| 18 | 运行时、后台配置及概览各自解释开关 | WalletRuntimeConfigService 共用定义与有效状态判断；上下文诊断留在各自入口 | RuntimeConfigurationIntegrationTest、WalletConfigOverviewServiceTest |
| 19 | 汇总与配置详情逐项查询、重复全量读取和嵌套遍历 | 按租户 / 链批量查询与类型键组合；链列表、概览和详情的查询次数受回归约束 | CustodyControlPlaneQueriesIntegrationTest、CustodyOperationalQueriesIntegrationTest |
| 20 | 模板注释重复名字，掩盖有效业务说明 | 删除 2,155 处模板 Javadoc；保留金额单位、事务、锁、重组及恢复说明 | 76310a3 的可执行 token 等价审计、ArchitectureBoundaryTest |
| 21 | 看板、租户链、水龙头及 HyperCore 仓储门面混合业务与转发 | 删除门面；现有用例拥有策略，HyperCoreDepositService 仅保留快照入账事务 | CustodyControlPlaneQueriesIntegrationTest、HyperCoreLocalFullFlowIntegrationTest |
| 22 | Console 回归 fixture 仍使用旧数组响应及旧显示名称 | 更新分页 fixture 和等待方式；按实际缓存行为验证重置 | surprising-wallet-web：37 项测试及 TypeScript 检查 |

## 有意保留的边界

- Controller / Job → Service 是 HTTP 与调度边界；单表 Repository 是明确的数据访问边界，均有实际职责。
- 发送、归集和各自确认具有不同的领取、费用和状态规则。四处显式链分派继续保留；不增加通用 handler、能力接口或回调注册表。
- Monero 继续串行执行扫描与资金流程；其余账户链扫描共用到期检查，UTXO 使用独立任务。
- 结算、投影、事件、Gas 及批次协调器保留事务、行锁、幂等与租户校验职责。
- TRON 只共享签名、广播及交易记录；归集仍先写 SENT 再关闭客户端。关闭失败时保留已知哈希。
- Ed25519 服务只接受 WalletKeyMaterialProvider；测试种子留在测试 fixture。生产种子校验、角色限制及链 / 租户派生路径保持原规则。

## 验证与基线

前一轮（截至 4e37438）已完成以下验证（2026-10-24）：

- Java 五模块 clean 编译、全部单测及所选本地 PostgreSQL 集成测试：629 项，625 通过、0 失败、0 错误，4 项原有条件跳过（1 个 UTXO 迁移场景及 3 个 live 场景）。
- 工作流 / TRON 针对性测试 30 项、七链密钥及相关地址 / 签名测试 65 项均通过；这些用例已包含于上面的回归总数。
- 正常 Spring 事务代理下的本地 EIP-7702 完整资金流程通过。
- BASE、HyperEVM 本地原生币与 ERC20 真实发送、回执、确认及重放流程通过；这不等于公网节点可用性验证。
- 100 用户 / 4 链：200 次充值尝试只入账 100 笔，100 笔提现确认，200 次 Webhook 投递完成，包含失败重试。
- Console 6 个文件的 37 项测试及 TypeScript 检查通过；原有两处用户源文件改动保持不变。
- 22 项范围逐条复核，28 项当前源码 / 用户文件保留断言通过；架构边界测试验证单表仓储与薄入口，业务 Service 无 JDBC。
- SVG XML 与高度边界有效；CodeGraph 无待处理变更；隔离测试数据库及本地 EVM 进程已清理。

验证使用现有本机 PostgreSQL 18 / PGMQ，临时库由各自测试脚本创建并删除，没有启动独立数据库。

本轮早先删除旧 Outbox、冗余字段及调整金额门槛精度时，已同步唯一数据库初始化基线。最后一组扫描归属、TRON 复用和密钥构造器清理没有 SQL 或新状态变更；四份架构 / 核心流程图文已同步。没有新增兼容迁移或通用工作流框架。


## 第二轮：七项遗留收敛

- TRON 原始 SDK 私有化：两种转账构建、常量调用、能量估算及部署都按实际节点限流。原生币和部署分别读取 Solidity / Full 参考块，再沿用 SDK 的本地构建与有效期规则；不嵌套占用同一 provider 配额。签名为本地操作，广播直接调用客户端。
- 删除只有测试消费者的 AptosAddressService / SuiAddressService；探针装配正常运行时与派生组件，原生币和 Token 共用原生 owner 记录，外部测试账户保留原派生参数与角色。
- 删除七个无消费者的 Map 查询；托管地址复用唯一行映射，归集金额与在途状态返回类型化聚合，链地址返回已有记录，余额资格查询只返回所需账户。
- 固定索引地址创建读取一次 profile 并直接返回持久化记录，去掉简化 Address 往返。Bitcoin-like 共用本次 profile、资产精度校验与网络参数，保留脚本、多签和索引规则；租户绑定、托管投影和审计仍在原事务。
- 全局开关统一批量读取，包含自定义任务键并保持其缺省语义。每个资金动作重新校验，没有跨轮缓存。
- 删除恒真的 isConfigured 与六个无消费者包装；概览根据实际 API 所需密钥材料报告可用性，签名角色限制继续由材料提供者强制执行。
- 删除两处 7702 网络自比较，保留真实链族、配置版本、批次身份和确认规则。

第二轮验证（2026-10-24）：

- 五模块 clean 编译、全部单测与所选本机 PostgreSQL 集成回归共 647 项：643 通过，0 失败、0 错误，4 项原有条件跳过。包括迁移后的 Aptos / Sui 本地数据库用例。
- 针对性单测 83 项、数据库专项 20 项通过；这些用例已包含于上述回归。七链真实派生分别验证一次 profile / 一次地址查询，重放不重新派生，投影失败整体回滚；聚合用例覆盖 2^180 金额、18 位小数、在途排除、FAILED 金额排除、排序与租户条件。
- TRON 通过真实 SDK 与本地 gRPC 服务验证节点调用、原生币载荷、TRC20 ABI / 费限、签名、部署和查询。三个新用例在旧源码上均为断言失败、无执行错误，在修复代码上通过；同时验证同一 provider 只有一个配额时没有嵌套占用。
- 正常 Spring 事务代理下的本地 7702 资金流程，以及 BASE / HyperEVM 的原生币与 ERC20 发送、回执、结算和重放均通过。
- 100 用户 / 4 链：200 次充值尝试只入账 100 笔，100 笔提现确认，200 次 Webhook 投递完成，包含 47 次重试。
- 最新脚本入口另外补验 Aptos / Sui 两项本地探针：2 通过、无跳过。探针复用调用方初始化的隔离库，不重置同库已部署 Token；Custody runner 先加载唯一基线，并传递对应测试开关，Sui runner 同步隔离库环境变量。
- 七项范围逐条复核，23 项源码 / 用户文件保留断言通过。六个受影响仓储的 22 处 JDBC 写入表达式与改动前 AST 完全一致。SVG XML 与高度边界有效，运行脚本语法检查通过。

四份架构 / 核心流程图文已同步。读取查询有投影收敛、首条余额资格限定和全局自定义任务键读取调整；数据库结构、状态、初始化配置及唯一 SQL 基线没有变化。验证限于本地链与节点模拟，不代表公网可用性验证。
