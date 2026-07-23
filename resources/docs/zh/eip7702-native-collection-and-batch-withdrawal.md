# EIP-7702 原生币归集与批量提币实施手册

本文描述当前代码已经实现的 EVM EIP-7702 资金流程，包括原生币/标准 ERC-20 批量归集、原生币/标准 ERC-20 批量提币、租户隔离、Gas 结算、失败恢复、部署、启用、暂停、回滚和验收。

协议原理、支持链核验结果和各链官方依据见 [EIP-7702 免 Gas 批量归集实施方案](eip7702-collection.md)。逐链上线前仍必须执行 RPC 能力探测，不能只根据链名启用。

## 1. 当前结论

- 原生币归集已支持：`token = 0x0000000000000000000000000000000000000000` 表示 ETH、BNB、POL 等链原生币。
- 标准 ERC-20 归集已支持。扣税币、rebase 币和返回值异常的代币会因“收款方实际增量不等于请求金额”而失败，不会误入账。
- 支持 7702 的链可批量提币。一个批次只包含同一租户、同一链、同一热钱包、同一资产的订单。
- 一个批次只有一个链上 tx hash；每笔业务提币使用 `tx_hash + item_index + log_index` 唯一定位。
- Relayer 支付外层交易 Gas。充值地址和热钱包不需要为了执行 7702 调用而额外预存原生币，但热钱包必须真实持有要提现的资产。
- 首次使用时由 Relayer 在 type-4 交易中携带 Authority authorization；后续使用 type-2 交易调用已委托的 EOA。
- 配置切到 `PAUSED` 或关闭批量开关后，已广播批次仍由 7702 worker 确认；旧确认器会按订单检查批次归属，不会把“外层 receipt 成功”误当成所有 item 成功。

## 2. EIP-7702 解决的问题

普通 EOA 只能签一笔转账，不能在自己的地址上下文内执行批处理、逐项隔离和自定义授权。EIP-7702 允许 EOA 临时或持续指向一份 delegate code，因此系统可以：

1. 让多个充值 EOA 在同一笔 Relayer 交易中把资产归集到租户热钱包；
2. 让一个租户热钱包在一笔交易中向多个收款地址提币；
3. 由 Relayer 支付外层 Gas，避免先给每个充值地址发送 ETH；
4. 在合约中执行 operation nonce、截止时间、签名、逐项结果和重放保护。

7702 本身不提供批处理业务、账本、租户隔离、失败重试或对账。这些能力由本项目的合约、Java worker 和 PostgreSQL 状态机共同实现。

## 3. 组件和安全边界

### 3.1 合约

- `resources/infra/evm-fork/contracts/Eip7702Collection.sol`
  - `Eip7702CollectionDelegate`：充值 EOA 的受限归集逻辑；
  - `Eip7702BatchCollector`：Relayer 调用的批量入口；
  - 仅允许把指定原生币/Token 转到签名请求中的收款地址；
  - 没有任意 `call`、`approve`、`delegatecall` 或升级入口；
  - payable `receive()` 保证委托后的充值地址仍可接收原生币。
- `resources/infra/evm-fork/contracts/Eip7702Payout.sol`
  - `Eip7702PayoutDelegate`：租户热钱包的受限批量提币逻辑；
  - 只有部署时固化的 Relayer 可以执行；
  - 每个 item 在隔离子调用中执行，一个失败不会阻塞后续 item；
  - payable `receive()` 保证委托后的热钱包仍可接收归集资金。

### 3.2 Java worker

- `Evm7702CollectionWorkflowService`：原生币和 Token 归集；
- `Evm7702WithdrawalWorkflowService`：批量提币；
- `Evm7702CollectionCoordinator` / `Evm7702WithdrawalCoordinator`：在数据库事务中原子完成 nonce 预留、加密 outbox、Gas 预留和账本结算；
- `Evm7702ReceiptParser` / `Evm7702PayoutReceiptParser`：严格验证合约事件和 ERC-20 `Transfer` 日志。

### 3.3 数据库

- `evm_7702_config`：链/网络/版本配置和功能开关；
- `evm_7702_account`：充值 Authority 委托投影；
- `evm_7702_payout_account`：租户热钱包委托投影；
- `evm_collection_batch*`：归集批次、item、加密签名交易 outbox；
- `evm_withdrawal_batch*`：提币批次、item、加密签名交易 outbox；
- `custody_gas_usage`：`COLLECTION_BATCH` 和 `WITHDRAWAL_BATCH` Gas 账务。

所有 batch 和 item 都带 `tenant_id`，外键使用复合租户键。批量提币不会跨租户、跨热钱包或跨资产合并。

## 4. 完整执行流程

### 4.1 原生币/Token 归集

1. 扫链确认充值并记入 `deposit_record` 和租户账本。
2. 创建带 `tenant_id`、`custody_address_id` 的 `collection_record`。
3. 7702 repository 按 `tenant + chain + asset + hot_wallet` 锁定待归集记录。
4. 原生币读取 `eth_getBalance`；Token 调用 `balanceOf`。
5. 首次归集把每个充值 EOA 的 authorization 放进同一个 type-4 交易；后续已委托地址不重复授权。
6. Authority 对包含 batch、item、资产、收款方、金额、operation nonce 和 deadline 的 EIP-712 请求签名。
7. 在提交 RPC 前，把完整已签名 raw transaction 加密写入 outbox，并原子预留 Relayer nonce 和批次 Gas。
8. receipt 达到确认数且所在区块仍为 canonical 后，逐项解析事件；Token 还必须有且只有一条完全匹配的 `Transfer` 日志。
9. 逐项更新 collection 状态，结算一次批次 Gas。

### 4.2 批量提币

1. API/Console 创建 `custody_withdrawal` 和 `withdrawal_order`，冻结客户资产，并从租户链级 Gas 账户预留单笔 Gas；
   客户充值地址不需要持有原生币。
2. worker 等待 `withdrawal_max_wait_ms`，或发现同组已有两笔订单后立即组批。
3. 按 `tenant + chain + asset + hot_wallet` 锁定最多 `withdrawal_max_batch_items` 笔订单。
4. 检查租户热钱包真实链上余额足以覆盖批次总额。
5. 热钱包首次使用时携带 payout delegate authorization；后续读取热钱包 `operationNonce()`。
6. 热钱包密钥对完整 payout batch 做 EIP-712 签名，Relayer 只负责提交，不能修改收款方或金额。
7. 数据库事务内：
   - 原子预留 Relayer nonce；
   - 加密保存完整 raw transaction；
   - 释放原有每笔 `WITHDRAWAL` Gas 预留；
   - 创建一个 `WITHDRAWAL_BATCH` Gas 预留。
8. 广播后，批次内所有订单保存同一个 tx hash。
9. receipt 达到确认数后按 `PayoutItemResult` 逐项处理：
   - 成功 item 扣减冻结账本并确认为 `CONFIRMED`；
   - 失败 item 没有资产移动，进入 `RETRYING`；
   - 连续失败 3 次后释放冻结资产并标记 `FAILED`；
   - 外层交易整体 revert 时，所有 item 按同一重试规则处理。
10. `CustodyWithdrawalReconciliationJob` 把 order 状态同步到 custody withdrawal 和 webhook event。

## 5. 一步步部署

### 第 1 步：准备环境

```bash
cd resources/infra/evm-fork
npm ci
npx hardhat compile
npx hardhat test test/eip7702-collection.test.js test/eip7702-payout.test.js
```

节点必须已经激活 EIP-7702，并支持：

- type `0x04`；
- `authorizationList` 的 `eth_estimateGas`；
- `eth_sendRawTransaction` 提交 type-4；
- 完整 receipt 日志；
- `pending` transaction count。

### 第 2 步：确定两个管理地址

- `EIP7702_ADMIN_ADDRESS`：Collector 管理员；
- `EIP7702_RELAYER_ADDRESS`：后端能从 `chain_address` 派生私钥的 Relayer 地址。

不要把私钥写入仓库或部署 JSON。

### 第 3 步：部署三个合约

```bash
export EIP7702_ADMIN_ADDRESS='0x...'
export EIP7702_RELAYER_ADDRESS='0x...'
export EVM_CHAIN='ETH'
export EVM_NETWORK='sepolia'
export EVM_DEPLOY_RPC_URL='https://...'
export EVM_DEPLOYER_PRIVATE_KEY='仅在安全部署环境注入'

npx hardhat run scripts/deploy-eip7702-collection.js --network deployment
```

脚本部署 Collector、Collection Delegate、Payout Delegate，并输出 schema version 2 的地址和 runtime code hash。

### 第 4 步：双 RPC 校验部署

```bash
export EIP7702_DEPLOYMENT_FILE="$PWD/deployments/ETH-EIP7702-sepolia.json"
npx hardhat run scripts/verify-eip7702-deployment.js --network deployment
```

至少使用两个独立 RPC 重复校验。任一 code hash 不一致都不能启用。

### 第 5 步：初始化数据库

新环境直接使用：

```bash
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 \
  -f docs/db/surprising-wallet-init-pgsql.sql
```

项目尚未上线，不保留旧结构兼容层。已有开发库应重建或执行等价的明确 DDL，不采用双读/双写。

### 第 6 步：写入 SHADOW 配置

```sql
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
    gen_random_uuid(), 'ETH', 'sepolia', 11155111, 1,
    '<COLLECTION_DELEGATE>', '<COLLECTION_DELEGATE_CODE_HASH>',
    '<COLLECTOR>', '<COLLECTOR_CODE_HASH>',
    '<PAYOUT_DELEGATE>', '<PAYOUT_DELEGATE_CODE_HASH>',
    <RELAYER_CHAIN_ADDRESS_ID>, '<RELAYER_ADDRESS>', 'SHADOW',
    20, 5000000, 0.3000,
    1.2000, 900, 2,
    false, false, 3000, 20);
```

`relayer_chain_address_id` 必须对应后端可派生、地址与 `relayer_address` 完全一致的链地址记录。

### 第 7 步：执行真实验收

本地 Hardhat Prague + PostgreSQL 测试覆盖：

- ETH、USDT、USDC 真实链上充值，并由 `EvmDepositScanner` 扫描、
  `CustodyDepositCreditObserver` 生成租户充值记录、账本流水和事件；
- 委托前后继续接收原生币；
- 多充值地址 ETH、USDT、USDC 批量归集；
- 通过 `CustodyWithdrawalService` 的 API 幂等业务路径创建提现；
- 多笔 USDT、USDC 提币分别共享一个 tx hash；
- 只有 Token、没有 ETH 的客户充值地址也能正常申请提现，网络费始终由租户链级 Gas 账户预留和结算；
- 原生币某个 item 失败、其他 item 继续成功；
- 修复收款条件后只重试失败订单；
- 广播响应丢失后解密并重发完全相同的 raw transaction；
- 两租户不交叉；
- Gas 预留释放和批次结算；
- 重复确认幂等。

生产测试网还要重复相同场景，不能只跑 `eth_call`。

### 第 8 步：分功能启用

先启用 Token 归集，再启用原生币归集，最后启用批量提币：

```sql
UPDATE evm_7702_config
   SET status = 'ACTIVE', updated_at = now()
 WHERE chain = 'ETH' AND network = 'sepolia' AND version = 1;

UPDATE evm_7702_config
   SET native_collection_enabled = true, updated_at = now()
 WHERE chain = 'ETH' AND network = 'sepolia' AND version = 1;

UPDATE evm_7702_config
   SET batch_withdrawal_enabled = true, updated_at = now()
 WHERE chain = 'ETH' AND network = 'sepolia' AND version = 1;
```

每一步都要先观察至少一个完整确认和对账周期，再进行下一步。

## 6. 暂停和回滚

停止创建新的原生币 7702 归集：

```sql
UPDATE evm_7702_config
   SET native_collection_enabled = false, updated_at = now()
 WHERE chain = 'ETH' AND network = 'sepolia' AND status = 'ACTIVE';
```

停止创建新的批量提币，但继续确认已广播批次：

```sql
UPDATE evm_7702_config
   SET batch_withdrawal_enabled = false, updated_at = now()
 WHERE chain = 'ETH' AND network = 'sepolia' AND status = 'ACTIVE';
```

暂停整个版本：

```sql
UPDATE evm_7702_config
   SET status = 'PAUSED', updated_at = now()
 WHERE chain = 'ETH' AND network = 'sepolia' AND status = 'ACTIVE';
```

暂停不会取消链上已经提交的交易，也不能删除 outbox。`BROADCAST_UNKNOWN`、`SUBMITTED`、`CONFIRMING` 必须继续恢复和确认。禁止因 RPC 超时直接生成新 nonce 的替代交易。

链上委托不会因数据库开关自动撤销。若要撤销 Authority code，必须建设并执行单独的、经过审计的 7702 清空/替换 authorization 流程；当前回滚以停止新批次和完成在途对账为主。

## 7. 监控与对账

至少监控以下指标：

- `BROADCAST_UNKNOWN` 数量和最老年龄；
- `SUBMITTED/CONFIRMING` 超过预期确认时间的批次；
- `PARTIAL_FAILED/FAILED/MANUAL_REVIEW` 数量；
- outbox hash mismatch；
- configured code hash mismatch；
- Relayer pending nonce 与数据库 nonce 差异；
- payout item 成功数 + 失败数是否等于 item 总数；
- batch Gas 实际费用与 `custody_gas_usage.actual_amount` 是否相等；
- 同一订单是否出现多个活跃 batch item；
- tenant_id 是否在 batch、item、order、custody record、Gas usage 间一致。

常用查询：

```sql
SELECT chain, network, status, count(*), min(updated_at) AS oldest
  FROM evm_withdrawal_batch
 WHERE status IN ('BROADCAST_UNKNOWN', 'SUBMITTED', 'CONFIRMING',
                  'PARTIAL_FAILED', 'FAILED', 'MANUAL_REVIEW')
 GROUP BY chain, network, status;

SELECT b.tenant_id, b.id, b.canonical_tx_hash, b.status,
       b.item_count, count(i.*) AS persisted_items,
       count(*) FILTER (WHERE i.status = 'CONFIRMED') AS confirmed_items,
       count(*) FILTER (WHERE i.status IN ('RETRYABLE', 'FAILED')) AS failed_items
  FROM evm_withdrawal_batch b
  JOIN evm_withdrawal_batch_item i
    ON i.tenant_id = b.tenant_id AND i.batch_id = b.id
 GROUP BY b.tenant_id, b.id;

SELECT b.tenant_id, b.id, b.actual_fee, g.actual_amount, b.canonical_tx_hash
  FROM evm_withdrawal_batch b
  JOIN custody_gas_usage g
    ON g.tenant_id = b.tenant_id
   AND g.operation_type = 'WITHDRAWAL_BATCH'
   AND g.operation_id = b.id
 WHERE b.status IN ('CONFIRMED', 'PARTIAL_FAILED', 'FAILED')
   AND (g.status <> 'SETTLED' OR g.actual_amount IS DISTINCT FROM b.actual_fee);
```

## 8. 使用后的优势和限制

优势：

- 不再给每个充值地址先打一笔 ETH；
- 多个地址归集共享一个外层交易，固定开销被摊薄；
- 多笔提币共享一个 tx hash，减少 nonce、广播和确认请求；
- 单个 item 失败不阻塞同批其他提币；
- raw transaction 先落加密 outbox，RPC 不确定响应可安全重放；
- 租户、订单、链上 item 和 Gas 费用可以完整审计。

限制：

- 批处理通常降低“每笔平均 Gas”，但总 Gas 不会按笔数线性消失；首次 authorization 也有额外成本；
- 不支持 7702 的链继续使用单笔交易、单 tx hash 逻辑；
- 当前只接受标准原生币和行为可验证的 ERC-20；
- 委托后的 EOA 接收原生币会执行 delegate 的 payable `receive()`。某些只提供 2300 stipend 的旧合约付款方式可能不兼容，接入链必须专项测试；
- 一笔批次不能跨租户、跨资产或跨热钱包；
- EIP-7702 不是跨链桥，不能把一条链上的资产直接变成另一条链资产。

## 9. 后续优化点

1. 按实时 Gas、队列长度和 SLA 动态调整等待窗口与批次大小；
2. 增加批次管理、人工复核和只重试失败 item 的 Console 页面；
3. 增加双 RPC receipt/canonical block 交叉核验；
4. 增加 delegate 撤销/轮换状态机和硬件签名/KMS 策略；
5. 为不同代币维护链上模拟和兼容性白名单；
6. 对跨资产提现使用多个独立批次并在业务层关联，不扩大合约任意调用权限；
7. 把 OP Stack、Arbitrum L1 fee 估算和 receipt 费用解析抽成共享、可独立测试的模块。
