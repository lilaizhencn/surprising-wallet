# Surprising Wallet 架构

![当前模块与信任边界](../assets/architecture-diagram.svg)

## 应用与依赖

钱包只有一个 Java Spring Boot MVC 应用 `wallet-api`。Maven 模块依赖为 `wallet-api → common, chain-sdks`、`common → chain-sdks`。`common` 和 SDK 不反向依赖应用。

三个独立的 `wallet-mpc-signer` C++ 进程通过固定身份的 mTLS 接收钱包签名请求；每个持有者只保存自己的加密份额和持久协议记录，不连接钱包 PostgreSQL。Java 负责业务意图、租户、金额、nonce/UTXO、状态和账务；原生节点负责密码学协议和本机份额。Java 不加载钱包私钥、Seed 或 BIP32 私有根。mTLS 身份私钥与钱包签名密钥用途分开。

| 位置 | 职责 |
|---|---|
| `wallet-api/controller`、`filter`、`exception` | HTTP 校验、身份、响应和异常映射 |
| `wallet-api/job/{deposit,withdraw,custody,collection,devfaucet}` | 调度、节流、防并发与异常隔离 |
| `wallet-api/service` | 业务用例、事务与资金状态编排 |
| `wallet-api/chain`、`coordinator`、`gateway` | 链 RPC、固定交易编码、回执核验和链流程协调 |
| `wallet-api/repository` | 一个 Repository 对应一张业务表 |
| `wallet-api/config`、`bootstrap/ApiComponents` | MVC、数据源、运行时和唯一 API 应用组件 |
| `common/queue` | PGMQ 扩展表适配；不查询钱包业务表 |
| `resources/infra/mpc-signer` | 原生三方协议、mTLS、独立持有者记录和 XMR 桥接 |

Controller 不直接依赖仓储；Job 不执行资金或链流程。Service 使用构造器注入，通过单表 Repository 组合跨表用例，不写 SQL。事务由应用服务编排。

## 租户与地址

`CustodyAddressService` 接受明确租户和派生主体，在持久化坐标下恢复或创建原 MPC key。`MpcSigningService` 只有取得三份一致公钥的持久完成结果，才能激活并发布地址。XMR 根和子账户由三名独立持有者的官方钱包 RPC 协同建立，Java 只接收公开绑定。

`chain_address` 绑定不可变的 tenant/key/公开地址与主体；客户、Gas、归集和平台中继均有明确归属。地址的公开编码由 `MpcPublicAddressCodec` 和链编码器完成。`ChainAddressRuntime` 只校验公开地址；`BitcoinLikeChainRuntime` 负责公开地址校验、区块观察和已选交易广播。租户地址发布使用统一托管服务。

`MpcNodes` 配置三个不同的持有者身份和 HTTPS 端点，校验 CA、客户端证书及传输私钥。配置概览使用 `mpcConfigured`。应用没有 Java 签名模式切换，`wallet-api` 固定运行 Web/API 和业务调度。

## 签名与恢复

四张表分别由同名单表仓储访问：`wallet_mpc_key`、`wallet_mpc_key_epoch`、`wallet_mpc_sign_request`、`wallet_mpc_sign_attempt`。它们保存租户、公钥、版本、不可变业务载荷、尝试和加密的绑定结果，不保存钱包私钥或份额。

受理 SIGN 时，原 key 锁、余额/nonce/UTXO 和原请求一起提交；远程 MPC 在事务外执行。结果独立验签，原文和本地交易哈希先提交，再查询费用或广播。广播回复丢失仍恢复原请求、原字节和原哈希，不能重建同一资金业务。已知失败、未知、确认与重组有各自状态和审计路径。

原生节点对原会话和记录使用不可覆盖的 AES-GCM 存储。节点证书 SPKI、租户、key、版本、参与方和消息上下文固定；未知签名不被当作没有签名。XMR 使用原两名持有者的原审核及 cosigned/artifact 材料恢复；缺失材料时保留未知和冻结，具体边界见原生服务运行说明。

### 份额刷新

运维以原 sourceEpoch、三名原持有者及不可变 actor/reason 受理 REFRESH。没有未完成 SIGN 才能把 key 置为 REFRESHING。原生接受前先持久化旧版本 fence；新份额另写新版本，旧文件保留。持有者专用 `/v1/refresh-prepared` 核对三份实际持久准备，随后写 ready/完成结果。只有三份绑定原公钥的完成结果齐备，Java 才同事务激活新 epoch、退役旧 epoch 并恢复 ACTIVE。

新份额已持久时可恢复原请求的公开就绪检查，不重新执行随机刷新。缺少私有协议材料时继续冻结。版本为 1–1000000 的整数。`wallet_mpc_key_epoch.refresh_actor/refresh_reason` 与请求不可变；公钥和已发布地址不变。

## 账务、队列与调度

- PostgreSQL 18 + PGMQ 1.11.1 是唯一通用中间件。`surprising-wallet-init-pgsql.sql` 是空库目标基线；测试只使用既有本机实例内的自有临时库。
- UTXO 签名阶段为 `wallet_mpc_sign → wallet_sign_done`。提现、充值事件、提现事件和 RBF 使用各自既有队列。初次请求、`chain_signing_transaction` 的持久入队凭据、订单与输入锁一起提交；没有额外钱包 Outbox。
- 队列处理在 Service；`read_ct` 栅栏保护领取。处理、下一阶段入队与归档一起提交。原生 MPC 持有者不访问数据库或队列。
- `DepositService` 必须和 `CustodyDepositProjectionService` 在同事务提交充值、余额、租户投影、赤字恢复和事件；地址归属缺失整笔回滚。
- `WithdrawalSettlementService` 同事务校验订单归属/状态/哈希、扣除冻结额和条件确认；竞争失败回滚扣款。
- `CustodyEventService` 和资金事务一起持久化事件及 API 来源 Webhook 投递；Console 来源仍有事件。
- `custodyTaskScheduler`、`evm7702TaskScheduler`、`accountTaskScheduler`、`depositTaskScheduler`、`withdrawTaskScheduler` 分池；Webhook 使用独立执行器。业务 Service 不声明 Scheduled。

## 特殊链流程

| 流程 | 架构约束 |
|---|---|
| UTXO | 固定批次、原输入/金额、租户锁定和每输入 MPC 意图；外部签名组装与独立核验后才提交已选原文 |
| 普通 EVM 与账户链 | 原网络/引用/发送者、nonce、费用上限和固定载荷持久化；恢复使用原元数据 |
| EIP-7702 | 原批次准备、内层 AUTH/OP 与中继 nonce 先受理；中继 MPC 意图及原租户 Gas 先提交，选定 type-4/type-2 原文后广播 |
| NEAR NEP-145 | 同租户 MPC Gas 只签固定原收款人的 storage_deposit；完整 FINAL/receipt/header 区分押金退款与 Gas 退款，注册/Gas 结算后才能原 Token 付款 |
| XMR | Monero 原生 2-of-3；私有钱包及随机材料留在原持有者，本金解锁、原 txset、费用和规范区块独立核对 |
| HyperCore | 固定 owner、网络和动作；真实余额变动与账本核对，不以认证成功替代转账成功 |

## 部署与验证状态

Java 服务单元为 `surprising-wallet.service`，原生三名持有者分别部署。回滚程序版本必须保留最新数据库、份额和原请求/结果，不能用回退资金或随机材料快照替代版本回滚。备份、隔离恢复与未知处理见 [MPC 运行说明](../../infra/mpc-signer/OPERATIONS.md)。

各链实际验收、原始公开证明摘要及命令集中记录在 [MPC 服务说明](../../infra/mpc-signer/README.md)。EIP-7702、NEAR 自动注册、XMR 和 cb-mpc 刷新已有各自实际通过记录；这些记录不能外推为当前整批改动完成。

本轮移除旧签名模块、Seed、全局派生入口和本地钱包签名。架构及基线已同步；清理后的生产编译/打包及 121 项定向检查通过，实际可执行 JAR 在隔离工作目录、无钱包 Seed、真实三方节点配置下完成两轮 HTTP health/启动校验和有序关闭。最终全链回归仍待验证。完整部署恢复演练及所有 28 条链的最终通过是整体交付条件。
