# Surprising Wallet 核心代码流程

![当前业务与签名数据流](../assets/system-code-flow-diagram.svg)

## 入口和租户

Controller 校验请求与身份后委托应用 Service；Job 只调度。所有地址、Gas、中继、余额、订单、签名意图和事件都有明确租户。仓储只访问各自业务表，跨表事务由 Service 编排。金额使用最小单位整数或 BigDecimal。

## 地址发布

1. `CustodyAddressService` 校验租户、主体和链配置，固定租户派生坐标。
2. `MpcSigningService` 保存原 key/epoch 建钥描述符；事务外调用三名持有者。
3. 三份公开结果绑定同一租户/key/版本/参与方且公钥一致后，激活 key。
4. `MpcPublicAddressCodec` 仅用联合公钥编码；在原 key 锁下发布 tenant/key/address。重复受理继续原 key，不另建替代地址。
5. Gas 地址沿同一租户地址发布流程创建；XMR 由三名原持有者协同建立原根和子账户。

## 充值、确认和重组

Scanner 观察规范区块与交易；账户链还核对完整成功事件/回执，不能仅凭方法参数入账。已知内部归集、退款和重复日志不得重复计入客户余额。`DepositService` 明确编排 `CustodyDepositProjectionService`：充值记录、流水、余额、租户投影、赤字恢复和持久事件同事务提交，缺失地址归属整笔回滚。

未达确认数保留 pending；后续补确认不受新业务开关或租户关闭链影响。发生重组时核对原规范块，撤销投影或记录赤字，并在赤字未恢复前阻止新提现领取。已观察事实不能因入口停用而丢弃。

## 出款和归集的共同签名边界

1. 原业务行锁下核对租户、状态、原发送者和金额；预留余额、nonce/UTXO、费用及固定载荷。
2. 同一事务创建不可变 MPC 请求及当前 attempt。原 key 必须 ACTIVE，版本与请求一致；刷新中的 key 不受理新 SIGN。
3. 提交后，在事务外向原参与方发起真实 MPC；返回结果按原公钥、原 preimage 和接收方独立验签，并加密保存绑定结果。
4. 从原业务载荷独立重建交易，校验选定签名后提交原文和本地哈希；之后才调用费用 RPC 或广播。
5. 回复丢失保留原请求和已选字节；查询、必要时重放原文，不能替换 nonce、输入、参与方或同业务意图。
6. 规范终态回执确认实际效果、净支出与费用；订单归属/状态/哈希、冻结扣款、条件确认及事件同事务提交，竞争失败全部回滚。
7. 只有明确的链上失败和适用的完整退款证明才能结束并释放资金；未知、缺失材料或不完整回执继续保留原约束。

`CollectionPlanningService` 计算可归集金额及租户目标；`UtxoSelectionService` 在调用方事务中核对并锁定输入；`WithdrawalClaimService` 检查重组赤字后领取订单；`EvmBatchOwnershipService` 防止批次与单笔重复结算。

## UTXO 与 PGMQ

UTXO 批处理同时编排提现与归集。原订单、输入锁、`chain_signing_transaction` 的初次入队持久凭据及 `wallet_mpc_sign` 入队一起提交；凭据在队列/归档清理后保留。

MPC 签名 Service 在事务外调用持有者，完成后按 `read_ct` 栅栏提交原结果及 `wallet_sign_done` 消息、归档原消息。广播器核对原请求及独立重建的选定交易，再广播；RBF 继续绑定原业务与明确的新替换意图。签名节点不连接 PGMQ 或钱包数据库。

## EIP-7702

原批次领取锁、固定准备数据、中继 nonce、内层 AUTH/OP 意图和 SIGNING 状态一起提交。业务授权密钥属于原业务租户；平台中继密钥有明确的中继租户，引用同时绑定原业务 tenant/batch。事务外 MPC 内层签名后独立重建载荷和 unsigned 费用估算；中继 MPC 意图与原租户 Gas 先提交，选定外层 type-4/type-2 原文及哈希后广播。两类 Job 共用链/网络领取锁。

丢回复或停用新任务时优先恢复原批次快照、原签名及哈希，不重新领取，不使用本地 Credentials。部分条目失败按原批次结果处理，成功条目不能再次扣本金或手续费。

## NEAR 自动存储注册

Token 收款人未注册时，原业务锁下以同租户 MPC Gas 受理固定 `storage_deposit(account_id=原收款人,registration_only=true)`；Gas nonce、FUNDING 意图、待完成链记录及 ADDRESS_PREPARATION 冻结一起提交。事务外签名，选定 raw/hash 提交后广播。

完整 FINAL、全 receipt 图及规范 header 分别校验合约押金退款、系统余额退款和 Gas 退款；实际支出为 tokens_burnt 合计加附加押金、减已完成押金退款，不能再次减 Gas 退款。注册及 Gas 结算完成后才受理原 Token 付款；明确失败且押金完整退回时，费用结算和原业务结束/本金释放同事务完成。已有注册意图不能被当前已注册状态跳过。

固定 NEP-141 模板显式 build 退款 Promise；退款日志不能替代实际回执。恢复 RPC 绑定原 payload.network/genesis，停用不改写原意图。

## XMR 与 HyperCore

XMR 使用 Monero 原生 2-of-3。三名持有者各自保留私有钱包，Java 保存公开地址、原支付策略及已核验结果。扫描核对原输出位置、解锁和规范区块；出款/归集核对原 txset、实际费用和找零。恢复只使用原两名持有者的原审核、artifact/cosigned 材料；材料不足保持未知，不重新 transfer/sign 或导出随机材料。

HyperCore 以原 owner、网络、动作及固定金额签名；真实入金、出款和归集按链上余额及账本核对。测试网认证通过不是转账通过，最终矩阵必须使用原已保存且有资金的 MPC 身份。

## 份额刷新

原 sourceEpoch、三名持有者、actor/reason 受理刷新；key 锁下先确认没有未完成 SIGN，原 REFRESH 与 REFRESHING 同事务提交。原生接受先写旧版本 fence，新份额另写 N+1；持有者专用 mTLS 就绪端点核对三份实际持久材料，之后才写 ready 和原完成结果。

Java 事务外恢复原 targetEpoch，只有三份同公钥绑定结果才同事务激活 N+1、退役 N 并恢复 ACTIVE。已完成旧 SIGN 仍可读，新的旧版本 SIGN 拒绝。新份额已完整持久时只重做公开就绪检查；缺失私有协议材料时保持冻结，不回退旧签名或换仪式。公钥及公开地址不变。

## 事件、回调和运维

`CustodyEventService` 随业务事务写事件；API 来源生成 Webhook 投递记录，Console 来源仍记录事件。Webhook 由独立执行器派发。费用、重放、原请求恢复和结算都保留审计路径，任何余额变化可通过流水对账。

每类 Job 使用独立线程池。Java 只运行 API 与业务调度，原生三名签名持有者分别部署。版本回滚保留最新业务状态和协议记录；材料备份、隔离恢复及未知处理见 [运行说明](../../infra/mpc-signer/OPERATIONS.md)。

各链实际验证结果与命令见 [MPC 服务说明](../../infra/mpc-signer/README.md)。当前清理后的干净构建、121 项定向检查及隔离工作目录中两轮真实可执行 JAR 无 Seed 启动已通过；完整备份恢复与所有链最终业务回归通过后才能提交和推送。
