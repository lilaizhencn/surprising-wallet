# Base EIP-7702 批量归集生产上线手册

本文是 [EIP-7702 免 Gas 批量归集实施方案](eip7702-collection.md) 的 Base 专项手册。范围仅为标准 ERC-20
归集；充值地址可以保持 `0 ETH`，由 Base 链级 Relayer 支付外层交易全部网络费。原生 ETH 归集、提现和资产兑换不走本流程。

Base Isthmus 已启用 EIP-7702。项目网络为 Base Sepolia `84532` 和 Base Mainnet `8453`。当前仓库验收状态
（2026-07-22）：

- Base 官方 Sepolia RPC 与项目 PublicNode RPC 均通过有效 authorization list 的严格 `eth_estimateGas` 门禁；
- chainId 84532 的 Hardhat Prague 已通过 3 个零 ETH Authority 的真实 Web3j type-4；
- PostgreSQL 两租户五地址生产路径通过，每租户独立批次、txid、Gas 预留和结算；
- OP Stack 的执行费、L1 数据费和 operator fee 已分别保存原子单位并按总额结算；
- 预留阶段使用签名后的原始交易向 GasPriceOracle 查询 L1 数据费，并查询 gas limit 对应的 operator fee；
- 尚未完成有资金的公开 Base Sepolia E2E、第二家生产级 RPC 供应商和第三方合约审计。

因此 Base Sepolia 最多录入 `SHADOW`，Mainnet 必须保持 `DISABLED`。

## 1. 资金、权限和租户准备

Authority 不需要预存 ETH。部署账户只负责部署；链级 Relayer 持有 Base ETH 并支付执行费、L1 数据费和 operator fee。每个租户
必须有自己的 `ACTIVE custody_gas_account`、`ETH_BASE` 可用账本余额和租户热钱包。Relayer 可以作为链级广播设施共享，但
Token recipient、Gas usage、batch、item 和账本必须全部绑定同一个 `tenant_id`。

部署私钥、Relayer 私钥、助记词、RPC key 和数据库凭据只能进入密钥系统或运行时 secret，禁止写入仓库、命令行历史、日志或
deployment JSON。Relayer 与 admin 分离；生产 admin 使用多签。

## 2. RPC 能力门禁

官方 RPC：

```bash
mvn -pl wallet-service -am \
  -Dtest=Evm7702RpcCapabilityIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Devm.7702.rpc-capability.enabled=true \
  -Devm.7702.rpc-url=https://sepolia.base.org \
  -Devm.7702.chain-id=84532 \
  test
```

项目当前 RPC：

```bash
mvn -pl wallet-service -am \
  -Dtest=Evm7702RpcCapabilityIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Devm.7702.rpc-capability.enabled=true \
  -Devm.7702.rpc-url=https://base-sepolia-rpc.publicnode.com \
  -Devm.7702.chain-id=84532 \
  test
```

测试必须核对 chainId，生成有效 authorization tuple，并要求 estimate 严格大于普通转账的 `21000`。生产不能依赖 Base
公开 RPC；为 primary/backup 采购两个独立供应商并逐个通过 estimate、send、receipt、logs 和 GasPriceOracle 门禁。

再执行只读费用门禁，确认 receipt 暴露 `l1Fee`：

```bash
mvn -pl wallet-api -am \
  -Dtest=Evm7702OpStackFeeIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Devm.7702.op-stack-fee.enabled=true \
  -Devm.7702.rpc-url=https://sepolia.base.org \
  -Devm.7702.chain-id=84532 \
  test
```

## 3. 本地合约和 chainId 84532 验证

```bash
cd evm-fork
npm ci
npm run compile
npm run test:7702
HARDHAT_CHAIN_ID=84532 npm run node
```

另一个终端部署：

```bash
cd evm-fork
export EVM_CHAIN=BASE
export EVM_NETWORK=local
export EIP7702_ADMIN_ADDRESS=0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266
export EIP7702_RELAYER_ADDRESS=0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266
npm run deploy:7702
export EIP7702_DEPLOYMENT_FILE="$PWD/deployments/BASE-EIP7702-local.json"
EVM_VERIFY_RPC_URL=http://127.0.0.1:8545 npm run verify:7702
```

Hardhat 公开账户只允许本地。部署脚本排他创建证据文件，已有文件时先归档，禁止静默覆盖。逐项核对 chainId、admin、relayer、
合约地址和 runtime code hash。

## 4. 真实 type-4 和数据库生产路径

```bash
mvn -pl wallet-service -am \
  -Dtest=Evm7702Type4IntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Devm.7702.enabled=true \
  -Devm.7702.chain-id=84532 \
  -Devm.7702.collector='<COLLECTOR_ADDRESS>' \
  -Devm.7702.delegate='<DELEGATE_ADDRESS>' \
  test

mvn -pl wallet-api -am \
  -Dtest=Evm7702ProductionFlowIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Devm.7702.production.enabled=true \
  -Devm.7702.test.chain=BASE \
  -Devm.7702.test.chain-id=84532 \
  -Devm.7702.collector='<COLLECTOR_ADDRESS>' \
  -Devm.7702.delegate='<DELEGATE_ADDRESS>' \
  test
```

验收必须包括：3 个零 ETH Authority 共用一个真实 type-4 txid；两租户五地址互不混批；code hash 错误不广播；unknown 广播
只重放同一份加密 raw transaction；每租户只产生一条 `COLLECTION_BATCH` Gas usage；本地链的 L1/operator fee 为 0，且
`total_fee_atomic = l2_fee_atomic`。

## 5. Base 的完整费用模型

Base 总费用不是单纯的 `gasUsed * effectiveGasPrice`：

```text
executionFee = gasUsed * effectiveGasPrice
totalFee     = executionFee + l1Fee + operatorFee
```

- 签名后、写入 outbox 前，用预部署 `0x420000000000000000000000000000000000000F` 的
  `getL1Fee(signedRawTransaction)` 查询 L1 数据费；
- 同一预部署的 `getOperatorFee(gasLimit)` 给出 operator fee 上限估计；
- 上述两项与 `gasLimit * maxFeePerGas` 一起预留，任一非本地查询失败都禁止广播；
- 确认时从 canonical receipt 读取精确 `l1Fee`；receipt 表明 operator 参数非零时，在该 receipt block 调用
  `getOperatorFee(gasUsed)`，避免跨 Isthmus/Jovian 公式硬编码；
- `evm_collection_batch` 分别保存 `l2_fee_atomic`、`l1_fee_atomic`、`operator_fee_atomic`、`total_fee_atomic` 和十进制
  `actual_fee`；`custody_gas_usage` 只按 total fee 结算一次。

少占时现有账务会补扣，余额不足进入 `OVERDUE`，但不会回滚已经完成的链上归集。监控必须分别展示四个原子费用字段，不能把
L1 fee 藏进执行 Gas。

## 6. 部署 Base Sepolia 并双 RPC 验证

只给部署账户和 Relayer 获取测试 ETH，不给 Authority 补 Gas：

```bash
cd evm-fork
export EVM_DEPLOY_RPC_URL='<DEDICATED_BASE_SEPOLIA_RPC>'
export EVM_DEPLOYER_PRIVATE_KEY='<CONTROLLED_TESTNET_DEPLOYER_PRIVATE_KEY>'
export EVM_CHAIN=BASE
export EVM_NETWORK=sepolia
export EIP7702_ADMIN_ADDRESS='<BASE_SEPOLIA_ADMIN_MULTISIG>'
export EIP7702_RELAYER_ADDRESS='<BASE_SEPOLIA_RELAYER_ADDRESS>'
npx hardhat run scripts/deploy-eip7702-collection.js --network deployment
unset EVM_DEPLOYER_PRIVATE_KEY
```

```bash
export EIP7702_DEPLOYMENT_FILE="$PWD/deployments/BASE-EIP7702-sepolia.json"
EVM_VERIFY_RPC_URL='<PRIMARY_BASE_SEPOLIA_RPC>' npm run verify:7702
EVM_VERIFY_RPC_URL='<BACKUP_BASE_SEPOLIA_RPC>' npm run verify:7702
```

两边 chainId 必须为 `84532`，code hash 必须一致；随后在 BaseScan 验证源码与编译参数，并由 admin 多签复核 Relayer allowlist。

## 7. 数据库 SHADOW 配置

先确认链级 Relayer 可由 wallet-api 密钥派生，且结果恰好一行并等于合约 allowlist：

```sql
SELECT id, address, derivation_path, wallet_role, enabled
FROM chain_address
WHERE chain = 'BASE' AND asset_symbol = 'ETH_BASE'
  AND user_id = 0 AND biz = 0 AND address_index = 0
  AND wallet_role = 'DEPOSIT';
```

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
    gen_random_uuid(), 'BASE', 'sepolia', 84532, 1,
    '<DELEGATE_ADDRESS>', '<DELEGATE_CODE_HASH>',
    '<COLLECTOR_ADDRESS>', '<COLLECTOR_CODE_HASH>',
    '<PAYOUT_DELEGATE_ADDRESS>', '<PAYOUT_DELEGATE_CODE_HASH>',
    <RELAYER_CHAIN_ADDRESS_ID>, '<RELAYER_ADDRESS>', 'SHADOW',
    10, 5000000, 0.5000, 1.2000, 900, 40, false, false, 3000, 10);
COMMIT;
```

既有充值地址需回填 `evm_7702_account`。`chain_profile.native_symbol`、`chain_asset`、`chain_address.asset_symbol`、Gas account
和 ledger 的 Base 原生币内部符号必须统一为 `ETH_BASE`。

## 8. 有资金 Base Sepolia E2E

1. 三个 Authority 收到小额标准 ERC-20，Base ETH 余额保持 0。
2. 短窗口 `SHADOW -> ACTIVE`，核对首次 type-4 的 txid、授权、item event、精确 Transfer 和一次 total fee 结算。
3. 同一 Authority 再次入账，核对持久 delegation 与不带新 authorization 的 type-2 后续批次。
4. 核对预留值包含三段费用，receipt 总额与四个审计字段、Gas usage、租户 ledger 完全一致。
5. primary/backup 都完成 estimate、send、receipt、logs、`getL1Fee`、`getOperatorFee`。
6. 保存 txid、receipt、client version、code hash 和对账证据，完成后恢复 `SHADOW`。

缺少第二个独立 RPC 或上述 funded E2E 证据时不得长期 `ACTIVE`。

## 9. Mainnet、监控和回滚

Base Mainnet 只能在 Sepolia 灰度至少 7 天、独立审计和变更审批后开始。重新核对 chainId `8453`，用独立 Mainnet
admin/Relayer 重新部署，双 RPC/code hash/BaseScan 双人复核，先 `SHADOW`，再单租户小额短窗口 `ACTIVE`。

监控至少包含：Relayer ETH 余额、执行/L1/operator/total fee、费用预留偏差、batch/item 成功率、receipt 缺字段、GasPriceOracle
调用失败、RPC 分歧、pending 时长、unknown 广播、reorg、账务 `OVERDUE`。

暂停只影响 Base：

```sql
BEGIN;
UPDATE evm_7702_config
SET status = 'PAUSED', updated_at = now()
WHERE chain = 'BASE' AND network = '<NETWORK>' AND status = 'ACTIVE';
COMMIT;
```

PAUSED 后继续恢复和确认已广播/unknown 批次。全部终态并完成费用对账后才可回旧流程；旧流程需要给充值地址补 Base ETH。
合约缺陷通过新版本 Collector/Delegate 灰度，禁止覆盖旧合约或删除审计数据。

## 10. 官方依据

- [Base Isthmus 与 EIP-7702 激活时间](https://docs.base.org/base-chain/specs/upgrades/isthmus/overview)
- [Base 网络、chainId 与公开 RPC](https://docs.base.org/base-chain/quickstart/connecting-to-base)
- [Base 网络费用](https://docs.base.org/base-chain/network-information/network-fees)
- [OP Stack 交易费用组成](https://docs.optimism.io/op-stack/transactions/fees)
- [OP geth receipt 扩展字段](https://docs.optimism.io/node-operators/reference/op-geth-json-rpc)
- [Isthmus GasPriceOracle 与 operator fee](https://specs.optimism.io/protocol/isthmus/predeploys.html)
