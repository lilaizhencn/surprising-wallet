# BNB EIP-7702 批量归集生产上线手册

本文是 [EIP-7702 免 Gas 批量归集实施方案](./eip7702-collection.md) 的 BNB Smart Chain 专项操作手册。
范围仅为 BSC 上标准 BEP-20/ERC-20 Token 归集；充值地址可以保持 `0 BNB`，由 BNB 链级 Relayer 支付外层交易 Gas。
原生 BNB 归集、提现和资产兑换不走本流程。

BNB Chain 的 Pascal 升级已在 BSC Testnet（2025-02-25）和 Mainnet（2025-03-20）激活，包含状态为
`Enabled` 的 BEP-441（EIP-7702）。项目中的 chainId 分别是 Testnet `97`、Mainnet `56`。链支持不代表任意 RPC、Relayer、
Token 或本项目配置已经可生产使用。

当前仓库验收状态（2026-07-22）：

- BSC Testnet 官方 RPC 已成功执行带有效 authorization list 的 `eth_estimateGas`；
- chainId 97 的 Hardhat Prague 节点已通过真实 Web3j type-4：3 个零 BNB Authority、1 笔交易、3 个 item；
- chainId 97 已通过 PostgreSQL 两租户五地址生产路径：每租户独立批次和 txid、Gas 各结算一次；
- 尚未使用有资金的 tBNB Relayer 在公开 BSC Testnet 广播完整交易，也尚未完成第三方合约审计。

因此 BNB Testnet 目前最多录入 `SHADOW`，不得直接 `ACTIVE`；BNB Mainnet 必须保持 `DISABLED`。

## 1. 资金、资产和密钥准备

不需要向每个充值地址预存 BNB。需要准备的是：

1. 部署账户：仅部署 Collector/Delegate 时持有少量 tBNB/BNB；生产建议使用受控部署签名器。
2. 链级 Relayer：持有 tBNB/BNB，支付每个批次的外层 type-4/type-2 Gas；配置余额和低余额告警。
3. 租户 Gas 账本：每个租户自己的 `custody_gas_account` 必须为 `ACTIVE` 且可用额足够；链上 Relayer 可以共享，账务不能共享。
4. 租户热钱包：每个租户的 Token 只能归集到自己 Gas account 绑定的 `custody_address`。
5. Authority：用户充值地址只需要已有待归集 Token，不需要 BNB。

部署私钥、Relayer 私钥、助记词、RPC key 和生产数据库凭据禁止写入仓库、`.env`、命令参数、日志或部署 JSON。

## 2. 先验证目标 RPC 的 7702 能力

在仓库根目录对 BSC Testnet 官方 RPC 执行无状态 estimate 门禁：

```bash
mvn -pl backendservices/wallet-parent/wallet-service -am \
  -Dtest=Evm7702RpcCapabilityIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Devm.7702.rpc-capability.enabled=true \
  -Devm.7702.rpc-url=https://bsc-testnet.bnbchain.org \
  -Devm.7702.chain-id=97 \
  test
```

测试会核对 `eth_chainId=97`，生成随机 Authority、Delegate 和有效 authorization tuple，再用最新区块 proposer 作为 estimate
的 funded sender。成功标准是 RPC 返回的 Gas 大于 `21000`。它不广播交易、不消耗 tBNB，只证明该 RPC 当前能够解析和估算
type-4；不能替代第 8 节的有资金公开测试网 E2E。

生产 RPC 池里的 primary、backup 必须逐个跑同一门禁。网络升级或供应商切换后必须重跑；失败节点不能进入 7702 RPC 池。

## 3. 编译并测试合约

```bash
cd evm-fork
npm ci
npm run compile
npm run test:7702
```

必须看到 `2 passing`。合约源文件为 `contracts/Eip7702Collection.sol`：Collector 只允许 allowlist Relayer；Delegate 只允许
指定 Collector 执行带 Authority EIP-712 签名的精确 Token、recipient、amount 和 nonce，不提供任意 `execute`、`approve` 或
`delegatecall`。

## 4. 在 chainId 97 本地链部署

终端 A：

```bash
cd evm-fork
HARDHAT_CHAIN_ID=97 npm run node
```

终端 B 使用 Hardhat 公开测试账户；这些地址和私钥只允许本地使用：

```bash
cd evm-fork
export EVM_CHAIN=BNB
export EVM_NETWORK=local
export EIP7702_ADMIN_ADDRESS=0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266
export EIP7702_RELAYER_ADDRESS=0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266
npm run deploy:7702
```

脚本排他创建 `deployments/BNB-EIP7702-local.json`，已有文件时拒绝覆盖。重新部署前应把旧文件移动到审计归档目录。
输出必须核对 `chain=BNB`、`network=local`、`chainId=97`、admin、relayer、两个合约地址和两个 runtime code hash。

使用部署记录验证本地链上的 runtime code：

```bash
export EIP7702_DEPLOYMENT_FILE="$PWD/deployments/BNB-EIP7702-local.json"
EVM_VERIFY_RPC_URL=http://127.0.0.1:8545 npm run verify:7702
```

## 5. 验证真实 Web3j type-4

回到仓库根目录，把部署 JSON 中地址代入：

```bash
mvn -pl backendservices/wallet-parent/wallet-service -am \
  -Dtest=Evm7702Type4IntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Devm.7702.enabled=true \
  -Devm.7702.chain-id=97 \
  -Devm.7702.collector='<COLLECTOR_ADDRESS>' \
  -Devm.7702.delegate='<DELEGATE_ADDRESS>' \
  test
```

该测试不使用 `hardhat_setCode`：三个随机 Authority 始终为 `0 BNB`，Relayer 广播一个带三条 authorization 的真实 type-4。
必须核验一个 txid、三个成功 item event、Token 全额进入 recipient、Authority code 为 `0xef0100 + delegate`，且三个
operation nonce 都为 `1`。

## 6. 验证数据库、两租户和生产状态机

通过统一脚本复用本机 PostgreSQL 18。脚本只会在 `127.0.0.1:5432` 内创建
`surprising_wallet_test_*` 临时库，执行结束后自动删除，不会启动独立数据库实例：

```bash
CUSTODY_DB_TESTS=Evm7702ProductionFlowIntegrationTest \
scripts/regtest/run-custody-db-tests.sh \
  -Devm.7702.production.enabled=true \
  -Devm.7702.test.chain=BNB \
  -Devm.7702.test.chain-id=97 \
  -Devm.7702.collector='<COLLECTOR_ADDRESS>' \
  -Devm.7702.delegate='<DELEGATE_ADDRESS>'
```

固定验收事实：租户 A 三地址、租户 B 两地址；所有 Authority 为 `0 BNB`；每租户独立批次和 txid；不存在跨租户 item；
code hash 错误时不广播；广播结果未知时复用同一 raw transaction 和 nonce；五个 item 确认后每租户只产生一条
`COLLECTION_BATCH` Gas 结算；调度器能从数据库发现 `BNB/local` ACTIVE runtime target。

## 7. 部署到公开 BSC Testnet

先从官方 faucet 获取最小测试资金，只给部署账户和 Relayer，禁止给 Authority 补 tBNB。通过受控 Secret 注入私钥：

```bash
cd evm-fork
export EVM_DEPLOY_RPC_URL='https://bsc-testnet.bnbchain.org'
export EVM_DEPLOYER_PRIVATE_KEY='<CONTROLLED_TESTNET_DEPLOYER_PRIVATE_KEY>'
export EVM_CHAIN=BNB
export EVM_NETWORK=testnet
export EIP7702_ADMIN_ADDRESS='<TESTNET_ADMIN_MULTISIG>'
export EIP7702_RELAYER_ADDRESS='<TESTNET_RELAYER_ADDRESS>'
npx hardhat run scripts/deploy-eip7702-collection.js --network deployment
unset EVM_DEPLOYER_PRIVATE_KEY
```

分别连接两个独立 RPC 验证同一份部署记录：

```bash
export EIP7702_DEPLOYMENT_FILE="$PWD/deployments/BNB-EIP7702-testnet.json"
EVM_VERIFY_RPC_URL='https://bsc-testnet.bnbchain.org' npm run verify:7702
EVM_VERIFY_RPC_URL='<INDEPENDENT_BSC_TESTNET_RPC>' npm run verify:7702
```

两个结果的 chainId 必须都是 `97`，Collector/Delegate code hash 必须完全相同。随后在 BscScan 验证源码和编译参数，并由
admin 多签确认测试网 Relayer 已在 allowlist。

## 8. 数据库先录入 SHADOW

先确认 Relayer 是 wallet-server 能从受控根密钥派生的 BNB 链地址：

```sql
SELECT id, address, derivation_path, wallet_role, enabled
FROM chain_address
WHERE chain = 'BNB'
  AND asset_symbol = 'BNB'
  AND user_id = 0
  AND biz = 0
  AND address_index = 0
  AND wallet_role = 'DEPOSIT';
```

结果必须恰好一行并与合约 allowlist 地址一致。然后录入测试网配置：

```sql
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
    gen_random_uuid(), 'BNB', 'testnet', 97, 1,
    '<DELEGATE_ADDRESS>', '<DELEGATE_CODE_HASH>',
    '<COLLECTOR_ADDRESS>', '<COLLECTOR_CODE_HASH>',
    '<PAYOUT_DELEGATE_ADDRESS>', '<PAYOUT_DELEGATE_CODE_HASH>',
    <RELAYER_CHAIN_ADDRESS_ID>, '<RELAYER_ADDRESS>', 'SHADOW',
    10, 5000000, 0.5000, 1.2000, 900, 20, false, false, 3000, 10);

COMMIT;
```

不要直接照抄确认数上线；这里的 `20` 与当前 BNB profile 一致，生产前由风控根据最终性、重组窗口和业务金额复核。
既有 BNB 充值地址还要按通用手册回填 `evm_7702_account`，并逐行核对 tenant、custody address、chain address 和 Authority。

## 9. 有资金公开测试网 E2E 后才允许 ACTIVE

选择单个内部租户和标准无转账税 BEP-20 Token，完成以下全部步骤：

1. 三个新 Authority 分别收到小额 Token，链上 BNB 余额保持 `0`。
2. 临时把 BNB Testnet 配置从 `SHADOW` 改为 `ACTIVE`，观察首次 type-4 批次。
3. 核对一个 canonical txid、三个 item event、精确 `Transfer`、租户热钱包增量和一次 Gas 结算。
4. 再向同一 Authority 入账 Token，验证后续不带新 authorization 的 type-2 批次和 operation nonce 递增。
5. primary/backup RPC 都完成 estimate、send、getTransaction、receipt 和 log 查询。
6. 保存 txid、区块、receipt、client version、合约地址/code hash 和数据库对账结果，然后立即恢复 `SHADOW`。

该 E2E 未完成前不得长期开启 ACTIVE。本地测试和只读 estimate 都不能替代这一门禁。

## 10. BNB Gas 策略

项目 BNB profile 当前是 `legacy-gas-price`，但 type-4 信封本身包含 `maxPriorityFeePerGas` 和 `maxFeePerGas`。worker 从
`eth_gasPrice` 得到基准，生成 type-4 fee 字段，并始终以 receipt 的 `gasUsed * effectiveGasPrice` 结算。上线前必须在目标 RPC
验证 BSC 最低 Gas Price/Tip 规则；不能把 Ethereum 的固定费率直接照搬到 BSC。

批处理不会降低网络 Gas Price。节省来自多个地址共用一个外层交易的固定开销；首次授权每个空 Authority 还会增加协议规定的
authorization intrinsic cost。以公开测试网和灰度主网的实际 receipt 比较旧流程，禁止承诺固定节省百分比。

## 11. Mainnet 上线顺序

只有 Testnet 有资金 E2E、至少 7 天稳定灰度、独立合约审计和变更审批全部通过，才执行：

1. 对 BSC Mainnet 每个候选 RPC 重跑能力门禁并核对 chainId `56`。
2. 使用 Mainnet 独立 admin 多签、Relayer 和部署签名器重新部署，不能复用 Testnet 地址或密钥。
3. 双 RPC code hash、BscScan 源码、Relayer allowlist 双人复核。
4. 数据库录入 `BNB/mainnet/56` 的 `SHADOW`，主网 profile 仍保持禁用。
5. 单内部租户、小额、短窗口切 `ACTIVE`；对账后扩大租户，不一次性全量开启。

## 12. 暂停和回滚

异常时只暂停 BNB 目标，不影响 ETH 或其他 EVM 网络：

```sql
BEGIN;
UPDATE evm_7702_config
SET status = 'PAUSED', updated_at = now()
WHERE chain = 'BNB' AND network = '<NETWORK>' AND status = 'ACTIVE';
COMMIT;
```

`PAUSED` 停止新批次，但 worker 仍恢复和确认 `BROADCAST_UNKNOWN`、`SUBMITTED`、`CONFIRMING`。必须等所有已广播批次终态并完成
Gas 对账后，才可恢复旧的逐地址归集；旧流程需要给地址补 BNB。已经 delegation 的地址不需要清除 code，当前 Delegate 没有任意
转账入口。合约缺陷采用新版本 Collector/Delegate + 新配置灰度，禁止原地覆盖 v1。

上线监控至少包括：Relayer BNB 余额、pending nonce、批次积压、unknown、部分失败、receipt canonicality、Gas 预估偏差、租户 Gas
账本偏差、Collector/Delegate 双 RPC code hash 和未知 delegation code。

## 13. 官方依据

- [BNB Chain Pascal 升级说明](https://docs.bnbchain.org/announce/pascal-bsc/)
- [BEP-441：Implement EIP-7702](https://github.com/bnb-chain/BEPs/blob/master/BEPs/BEP-441.md)
- [BNB Chain JSON-RPC Endpoint](https://docs.bnbchain.org/bnb-smart-chain/developers/json_rpc/json-rpc-endpoint/)
