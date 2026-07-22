# ETH EIP-7702 批量归集生产上线手册

本文对应仓库当前实现，范围仅为 **Ethereum ERC-20 归集**。充值地址可以保持 `0 ETH`，由链级 Relayer 支付外层交易
Gas；每个租户的 Token 只能归集到该租户自己的 `custody_gas_account` 绑定地址。原生 ETH 归集、提现和资产兑换不走本流程。

主网启用前必须满足：合约独立审计通过、Sepolia 灰度通过、生产 RPC 明确支持 type `0x04`、双人复核部署地址与 code hash。
任何一项缺失时，`evm_7702_config.status` 必须保持 `SHADOW` 或 `PAUSED`，不得设为 `ACTIVE`。

## 1. 已实现的生产数据流

1. 充值扫描确认 ERC-20 到账并记入 `deposit_record`、`ledger_balance`。
2. `AccountChainWorkflowService` 创建带 `tenant_id`、`custody_address_id` 的 `collection_record`。
3. ETH 网络存在 `ACTIVE` 的 7702 配置时，旧的逐地址 Token 广播不会执行。
4. `Evm7702CollectionRepository` 使用 `FOR UPDATE SKIP LOCKED`，按
   `tenant + chain + token + tenant hot wallet` 抢占一批；跨租户 item 无法进入同一批。
5. 每个 Authority 对受限 `CollectionRequest` 签 EIP-712。未委托的 Authority 同时签 authorization；已经指向同版本
   Delegate 的 Authority 读取链上 `operationNonce`，不重复授权。
6. Relayer 签一笔 type-4（批内含首次授权）或 type-2（全部已经授权）外层交易。完整已签名 raw transaction 在广播前
   AES-GCM 加密写入 outbox，租户 Gas 账本同时预留最大费用。
7. Collector 逐项调用 Authority；单项失败不回滚全批。Delegate 只允许精确 token、精确 recipient、精确 amount、deadline、
   nonce 和 Collector 调用者，不提供任意 `execute`、`approve` 或 `delegatecall`。
8. 达到确认数后，服务核验 canonical block、Collector 事件、逐项身份、精确 ERC-20 `Transfer` 和实际 Gas，一次性更新批次、
   collection、Authority 投影和租户 Gas 账本。

合约源文件是 `evm-fork/contracts/Eip7702Collection.sol`；Java 入口是
`Evm7702CollectionWorkflowService`。一批只有一个外层 txHash，每个 item 通过 `item_index + log_index` 独立审计。

## 2. 运行前准备

需要以下软件和基础设施：

- Java 21、Maven、Node.js、npm；
- PostgreSQL，目标 SaaS schema 由 `docs/db/surprising-wallet-init-pgsql.sql` 定义；
- 两个独立 Ethereum RPC，至少一个用于发送，另一个用于人工 code hash 复核；
- Collector admin 多签地址；
- ETH Relayer 地址及其在 `chain_address` 中可由 wallet-server 根密钥派生的记录；
- 每个租户各自的 `custody_gas_account`、租户热钱包和足够的 Gas 账本可用额；
- 生产 `custody.security.secret-master-key`，用于加密 raw transaction outbox。

禁止把部署私钥、Relayer 私钥、助记词、RPC key 或数据库密码写入仓库、部署 JSON、命令历史和工单。部署脚本支持环境变量
私钥是为了本地/受控流水线；主网应由硬件钱包或受控远程签名器执行等价部署并保留签名审计。

`surprising-wallet-init-pgsql.sql` 是完整基线，会删除并重建对象。它只可用于新环境或可丢弃的本地测试库，绝不能直接在已有
生产库运行。项目当前处于 SaaS 开发阶段，正式环境应从该目标 schema 建新库、校验数据后切换；若以后已有生产数据，再单独
编写并演练迁移，不允许临时删表上线。

## 3. 第一步：本地合约编译和单元测试

在仓库根目录执行：

```bash
cd evm-fork
npm ci
npm run compile
npm run test:7702
```

必须看到 `2 passing`。测试覆盖三个零 ETH EOA 在一个批次中归集、单项失败隔离和未授权 Relayer 拒绝。失败时停止，不部署。

## 4. 第二步：启动 Prague 本地链并部署

终端 A：

```bash
cd evm-fork
HARDHAT_CHAIN_ID=31337 npm run node
```

终端 B 先设置本地 admin 和 Relayer。下面的 admin 是 Hardhat 第一个公开测试账户，只允许本地使用；Relayer 应填本次本地测试
实际使用的地址：

```bash
cd evm-fork
export EVM_CHAIN=ETH
export EVM_NETWORK=local
export EIP7702_ADMIN_ADDRESS=0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266
export EIP7702_RELAYER_ADDRESS='<LOCAL_RELAYER_ADDRESS>'
npm run deploy:7702
```

脚本会排他创建 `deployments/ETH-EIP7702-local.json`，如果文件已经存在会拒绝覆盖。需要重新部署时先把旧文件移动到审计归档
目录，再执行；不要覆盖旧证据。输出必须包含：`chainId=31337`、Collector/Delegate 地址、两个 runtime code hash、admin、Relayer。

本地 JSON 被 `.gitignore` 排除。测试网和主网部署记录应进入受控发布工件，包含交易哈希、区块号、编译器、optimizer、源码
验证链接和双人复核结果。

## 5. 第三步：验证真实 Web3j type-4

将部署 JSON 中两个地址代入：

```bash
cd ..
mvn -pl :wallet-service -am \
  -Dtest=Evm7702Type4IntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Devm.7702.enabled=true \
  -Devm.7702.collector='<COLLECTOR_ADDRESS>' \
  -Devm.7702.delegate='<DELEGATE_ADDRESS>' \
  test
```

该测试不是 `hardhat_setCode` 模拟：它生成三个随机 Authority，地址 ETH 余额始终为零，铸造 MockERC20，使用 Web3j 生成真实
authorization list 和 type-4 raw transaction，在同一笔交易里首次委托并归集。必须 `BUILD SUCCESS`。

## 6. 第四步：验证 PostgreSQL、两租户和完整状态机

通过统一脚本复用本机 PostgreSQL 18。脚本只会在 `127.0.0.1:5432` 内创建
`surprising_wallet_test_*` 临时库，执行结束后自动删除，不会启动独立数据库实例：

```bash
CUSTODY_DB_TESTS=Evm7702ProductionFlowIntegrationTest \
scripts/regtest/run-custody-db-tests.sh \
  -Devm.7702.production.enabled=true \
  -Devm.7702.collector='<COLLECTOR_ADDRESS>' \
  -Devm.7702.delegate='<DELEGATE_ADDRESS>'
```

当前测试的固定验收事实是：

- 租户 A 三个充值地址、租户 B 两个充值地址，共五笔真实 MockERC20 充值；
- 五个 Authority 从创建到归集结束都保持 `0 ETH`；
- A 和 B 分别生成一个批次、一个 txHash，互不混批；
- 模拟一次签名前 code hash 校验失败，未广播 item 安全回到 `RETRYABLE`，修复后进入新批次；
- 模拟一次“链已接收但应用未收到广播结果”，恢复任务根据原 txHash 找回交易，不创建新 nonce；
- 五个 item 全部确认并激活 delegation；
- 每个租户只产生一条 `COLLECTION_BATCH` Gas usage，按 receipt 实际费用结算；
- 加密 outbox 不包含明文 raw transaction。

## 7. 第五步：部署到 Sepolia

先在受控终端注入 Sepolia RPC 和一次性部署签名器。不要把真实值写进 shell 脚本：

```bash
cd evm-fork
export EVM_DEPLOY_RPC_URL='<SEPOLIA_RPC_URL>'
export EVM_DEPLOYER_PRIVATE_KEY='<ONE_TIME_TESTNET_DEPLOYER_PRIVATE_KEY>'
export EVM_CHAIN=ETH
export EVM_NETWORK=sepolia
export EIP7702_ADMIN_ADDRESS='<SEPOLIA_ADMIN_MULTISIG>'
export EIP7702_RELAYER_ADDRESS='<SEPOLIA_RELAYER_ADDRESS>'
npx hardhat run scripts/deploy-eip7702-collection.js --network deployment
unset EVM_DEPLOYER_PRIVATE_KEY
```

部署后用只读验证脚本分别连接两个独立 RPC：

```bash
export EIP7702_DEPLOYMENT_FILE="$PWD/deployments/ETH-EIP7702-sepolia.json"
EVM_VERIFY_RPC_URL='<SEPOLIA_RPC_A>' npm run verify:7702
EVM_VERIFY_RPC_URL='<SEPOLIA_RPC_B>' npm run verify:7702
```

`chain-id` 必须是 `11155111`；两个 RPC 得出的每个 code hash 必须相同，并等于部署 JSON。随后在 Etherscan 验证源码和编译
参数。任何不一致都停止，不能录入配置。

用 admin 多签确认 `relayerAllowed(<SEPOLIA_RELAYER_ADDRESS>) == true`。需要换 Relayer 时，先添加新 Relayer并确认服务已经切换，
最后再调用 `setRelayer(old,false)`；不能先删除仍在广播的 Relayer。

## 8. 第六步：准备数据库配置

### 8.1 Relayer 记录

第一阶段允许复用已经由 wallet-server 验证过派生路径的 ETH 平台地址作为链级 Relayer。先只读检查，结果必须恰好一行：

```sql
SELECT id, address, derivation_path, wallet_role, enabled
FROM chain_address
WHERE chain = 'ETH'
  AND asset_symbol = 'ETH'
  AND user_id = 0
  AND biz = 0
  AND address_index = 0
  AND wallet_role = 'DEPOSIT';
```

如果没有或地址与部署时 Relayer 不同，停止上线；不能手写一个与根密钥派生不一致的地址。后续增加专用 Relayer 开户命令后，
应迁移到独立 `EIP7702_RELAYER` 派生路径。链级 Relayer是共享广播基础设施，不接收租户 Token；租户费用仍按 batch 的
`tenant_id` 从各自 `custody_gas_account` 预留和结算。

### 8.2 录入 SHADOW

使用 `psql` 变量可避免把地址散落在 SQL 文件。以下语句是真实目标 schema，先录入 `SHADOW`：

```sql
\set chain_id 11155111
\set version 1
\set delegate_address '<DELEGATE_ADDRESS>'
\set delegate_code_hash '<DELEGATE_CODE_HASH>'
\set collector_address '<COLLECTOR_ADDRESS>'
\set collector_code_hash '<COLLECTOR_CODE_HASH>'
\set relayer_chain_address_id '<CHAIN_ADDRESS_ID>'
\set relayer_address '<RELAYER_ADDRESS>'

BEGIN;

INSERT INTO evm_7702_config(
    id, chain, network, chain_id, version,
    delegate_address, delegate_code_hash,
    collector_address, collector_code_hash,
    payout_delegate_address, payout_delegate_code_hash,
    relayer_chain_address_id, relayer_address, status,
    max_batch_items, max_batch_gas, block_gas_ratio,
    gas_limit_multiplier, signature_ttl_seconds, required_confirmations,
    native_collection_enabled, batch_withdrawal_enabled,
    withdrawal_max_wait_ms, withdrawal_max_batch_items)
VALUES (
    gen_random_uuid(), 'ETH', 'sepolia', :chain_id, :version,
    :'delegate_address', :'delegate_code_hash',
    :'collector_address', :'collector_code_hash',
    :'payout_delegate_address', :'payout_delegate_code_hash',
    :relayer_chain_address_id, :'relayer_address', 'SHADOW',
    10, 5000000, 0.5000, 1.2000, 900, 12, false, false, 3000, 10);

COMMIT;
```

初始每批 `10` 个地址，不直接使用合约上限 `100`。`max_batch_gas`、`block_gas_ratio` 和 multiplier 三道上限会共同限制交易；
Sepolia 收集至少 100 个批次数据后才评估增大。

### 8.3 回填既有 ETH 充值地址投影

新开户会自动创建 `evm_7702_account`。启用前用下面的幂等 SQL 回填目标 SaaS 库中已经存在的租户 ETH 充值地址；没有投影的
地址不会进入 7702 批次：

```sql
INSERT INTO evm_7702_account(
    id, tenant_id, custody_address_id, chain, network, authority_address)
SELECT gen_random_uuid(), ca.tenant_id, ca.id, ca.chain, ca.network, ca.address
FROM custody_address ca
JOIN chain_address cha
  ON cha.tenant_id = ca.tenant_id AND cha.id = ca.chain_address_id
WHERE ca.chain = 'ETH'
  AND ca.network = '<NETWORK>'
  AND ca.status = 'ACTIVE'
  AND cha.enabled = true
  AND cha.wallet_role = 'DEPOSIT'
ON CONFLICT (tenant_id, custody_address_id, chain) DO NOTHING;
```

回填后核对每行 `authority_address = custody_address.address = chain_address.address`；不相等时停止上线并修复开户数据，不能自动选
任意一个地址。

### 8.4 每个租户的硬门禁

准备灰度租户时，下列查询都必须满足：租户热钱包属于同一 tenant，Gas account 为 `ACTIVE`，余额足以覆盖预留：

```sql
SELECT ga.tenant_id, ga.chain, ga.network, ga.status,
       ca.id AS custody_address_id, ca.address AS tenant_hot_wallet,
       ga.available_balance, ga.reserved_balance
FROM custody_gas_account ga
JOIN custody_address ca
  ON ca.tenant_id = ga.tenant_id AND ca.id = ga.custody_address_id
WHERE ga.tenant_id = '<TENANT_UUID>'
  AND ga.chain = 'ETH';
```

同一链上不同租户可以共用 Relayer，但不能共用 `custody_address_id` 或 Token destination。系统在 claim、item、Gas usage 和
completion 的所有更新中都带 `tenant_id`。

## 9. 第七步：Sepolia 灰度和启用

`SHADOW` 只表示配置已登记，当前 worker 不会签名或广播。先完成部署/code hash/RPC/Relayer/租户检查，然后只选择一个内部灰度
租户和一个标准 ERC-20 测试 Token。确保其他租户没有待归集候选，再在事务中启用：

```sql
BEGIN;
UPDATE evm_7702_config
SET status = 'ACTIVE', updated_at = now()
WHERE chain = 'ETH' AND network = 'sepolia' AND version = 1 AND status = 'SHADOW';
COMMIT;
```

创建三笔小额真实充值，充值地址不要转 ETH。逐项核验：一个批次一个 txHash；Authority ETH 余额为零；Token 全额进入灰度租户
热钱包；item 与 collection 全部 `CONFIRMED`；Gas usage 为 `SETTLED`；授权后地址 code 为
`0xef0100 + delegateAddress`。至少运行 7 天且满足第 10 节指标，再申请主网。

主网重复“独立部署 → 双 RPC code hash → Etherscan 验证 → SHADOW → 单租户小额 ACTIVE”全部步骤。主网 chainId 是 `1`，
确认数使用生产风控值，不照抄 Sepolia。测试网合约、admin、Relayer 和数据库配置不得复用到主网。

## 10. 运行监控和每日对账

### 10.1 批次积压和失败

```sql
SELECT chain, network, status, count(*) AS batches,
       min(created_at) AS oldest, max(updated_at) AS last_update
FROM evm_collection_batch
WHERE created_at > now() - interval '24 hours'
GROUP BY chain, network, status
ORDER BY chain, network, status;
```

`SIGNING` 超过 5 分钟、`BROADCAST_UNKNOWN` 超过 2 分钟、`SUBMITTED/CONFIRMING` 超过目标链确认窗口必须告警。

```sql
SELECT b.tenant_id, b.id, b.status, b.canonical_tx_hash,
       a.tx_hash, a.status AS attempt_status,
       a.rebroadcast_count, a.last_rebroadcast_at,
       b.error_code, b.error_message, b.updated_at
FROM evm_collection_batch b
LEFT JOIN evm_collection_batch_attempt a
  ON a.tenant_id = b.tenant_id AND a.batch_id = b.id
WHERE b.status IN ('SIGNING', 'BROADCAST_UNKNOWN', 'SUBMITTED', 'CONFIRMING')
ORDER BY b.updated_at;
```

### 10.2 租户隔离断言

以下查询必须永远返回 `0`：

```sql
SELECT count(*)
FROM evm_collection_batch_item i
JOIN evm_collection_batch b ON b.id = i.batch_id
JOIN collection_record cr ON cr.id = i.collection_record_id
JOIN custody_address ca ON ca.id = i.custody_address_id
WHERE i.tenant_id <> b.tenant_id
   OR i.tenant_id <> cr.tenant_id
   OR i.tenant_id <> ca.tenant_id;
```

### 10.3 Gas 对账

```sql
SELECT b.tenant_id, b.id, b.actual_fee,
       g.status, g.reserved_amount, g.actual_amount, g.reference_no
FROM evm_collection_batch b
JOIN custody_gas_usage g
  ON g.tenant_id = b.tenant_id
 AND g.operation_type = 'COLLECTION_BATCH'
 AND g.operation_id = b.id
WHERE b.status IN ('CONFIRMED', 'PARTIAL_FAILED', 'FAILED')
  AND (g.status <> 'SETTLED' OR g.actual_amount IS DISTINCT FROM b.actual_fee);
```

结果必须为空。链上 receipt 的 `gasUsed * effectiveGasPrice`、batch `actual_fee`、Gas usage `actual_amount` 应每日汇总对账。

### 10.4 合约和 delegation

每次发布和每日定时从两个 RPC 读取 Collector/Delegate runtime code hash，与 `evm_7702_config` 比较。对已激活账户抽样读取 code，
必须精确等于 `0xef0100 + 当前 delegate_address`；未知 code 会被 worker 拒绝，不得自动覆盖。

## 11. 暂停、故障恢复和人工处置

### 11.1 立即停止新批次

```sql
BEGIN;
UPDATE evm_7702_config
SET status = 'PAUSED', updated_at = now()
WHERE chain = 'ETH' AND network = '<NETWORK>' AND status = 'ACTIVE';
COMMIT;
```

`PAUSED` 后不再 claim 新候选，但服务仍处理 `BROADCAST_UNKNOWN`、`SUBMITTED` 和 `CONFIRMING`，直到链上结果完成并结算。不要停
wallet-server，除非它本身是事故源。

### 11.2 BROADCAST_UNKNOWN

广播前，系统已经把 txHash、nonce、Gas 参数、calldata hash 和加密 raw transaction 原子保存。恢复规则固定为：

1. 按原 txHash 查询 receipt 和 mempool transaction；存在则转 `SUBMITTED`。
2. 不存在则解密 outbox，重新计算 txHash；不一致时标记 `OUTBOX_HASH_MISMATCH` 并人工处置。
3. 一致时只重发完全相同的 raw transaction；不重签、不加价、不换 nonce。
4. RPC 报错后再次按 txHash 查询；仍不存在则保留 UNKNOWN，30 秒后再尝试。

严禁人工把该 batch 释放回候选或新建同 nonce 交易，否则可能双花/重复归集。

### 11.3 Relayer 余额不足

先 `PAUSED`，给 Relayer 补 ETH并核对链上余额、租户 Gas 账本和 pending nonce。只允许在没有不确定 nonce 的情况下恢复
`ACTIVE`。Authority 不需要也不应该补 ETH。

### 11.4 部分失败

Collector 的 best-effort 批次可能为 `PARTIAL_FAILED`。成功 item 永不重放；失败 item 的 `collection_record` 回到 `RETRYING`，
后续新批次使用新的 operation nonce 和签名。必须先看 `error_hash`、Token 余额、deadline、delegation code，再决定是否让它重试。

## 12. 回滚方案

### 12.1 应用回滚

1. 将配置改为 `PAUSED`。
2. 等待所有已广播/未知批次进入终态；未终态时禁止回滚数据库或切旧归集器。
3. 导出 batch、item、attempt、collection、Gas usage 和 receipt 对账结果。
4. 回滚 wallet-server 版本，但保留新表和历史数据只读，不能删除审计证据。

### 12.2 回到旧逐地址 ERC-20 归集

旧流程要求每个充值地址持有 ETH。只有在 7702 批次全部终结后，才可保持 `PAUSED` 并恢复旧 worker；随后按旧流程给待归集地址
补 Gas。不要同时运行两条 ERC-20 归集路径。

已经 delegation 的 EOA 仍保留原地址和私钥。当前 Delegate 没有任意转账入口，只有配置 Collector 可以执行精确归集，因此暂停后
不会被外部任意调用转走 Token。不要自毁或覆盖旧合约；历史 code 必须保留用于审计。

### 12.3 合约版本回滚/升级

发现合约问题时先 `PAUSED`，admin 多签撤销受影响 Relayer。修复必须部署新的 Collector + Delegate，配置 `version + 1`，重新走
SHADOW 和灰度；不能原地修改 v1。Authority 在下一次安全批次中签新 authorization，指向新 Delegate。旧版本在所有 pending
批次终结前不得撤销其 Relayer。

## 13. 发布验收清单

- [ ] Hardhat 合约测试通过；
- [ ] Web3j 真实 type-4 集成测试通过；
- [ ] PostgreSQL 两租户五地址生产路径测试通过；
- [ ] 独立审计无未解决高危/严重问题；
- [ ] chainId、Collector/Delegate 地址和双 RPC code hash 双人复核；
- [ ] admin 是多签，Relayer allowlist 正确；
- [ ] Relayer 派生地址与 `chain_address` 精确匹配且余额告警已配置；
- [ ] 每个灰度租户热钱包和 Gas account 都归属同一 tenant；
- [ ] 配置先 SHADOW，只有变更窗口内才转 ACTIVE；
- [ ] 批次积压、unknown、失败率、Gas 偏差、Relayer 余额、code hash、reorg 告警可用；
- [ ] 暂停 SQL、恢复规则、旧流程回退和联系人已经演练；
- [ ] 主网部署私钥/RPC key/数据库凭据未进入 Git、日志或发布工件。

## 14. 本地回归命令

代码变更后至少执行：

```bash
cd evm-fork
npm run compile
npm run test:7702

cd ..
mvn -pl :wallet-service -am test
mvn -pl :wallet-server -am test
git diff --check
```

生产路径测试默认跳过，必须按第 4 至第 6 节显式启用。ETH 第一阶段通过全部门禁后再提交和推送；其他 EVM 链必须按
`eip7702-collection.md` 的逐链矩阵独立部署、独立验证、独立测试和独立提交，不能因为代码族相同直接启用。
