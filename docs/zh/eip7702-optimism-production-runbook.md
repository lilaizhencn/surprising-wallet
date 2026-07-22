# Optimism EIP-7702 批量归集生产上线手册

本文是 [EIP-7702 免 Gas 批量归集实施方案](./eip7702-collection.md) 的 OP Mainnet 专项手册。范围仅为标准 ERC-20
归集；充值地址可以保持 `0 ETH`，由 Optimism 链级 Relayer 支付外层交易全部网络费。原生 ETH 归集、提现和资产兑换不走本流程。

OP Stack Isthmus 引入 EIP-7702；当前 OP Sepolia 和 OP Mainnet 已进一步激活 Jovian。项目网络为 OP Sepolia `11155420` 和
OP Mainnet `10`。当前仓库验收状态（2026-07-22）：

- OP 官方 Sepolia RPC 与项目 PublicNode 均通过有效 authorization list 的严格 `eth_estimateGas` 门禁；
- chainId 11155420 的 Hardhat Prague 已通过 3 个零 ETH Authority 的真实 Web3j type-4；
- PostgreSQL 两租户五地址生产路径通过，每租户独立批次、txid、Gas 预留和一次结算；
- 执行费、L1 数据费和 operator fee 使用 GasPriceOracle 预留并按 canonical receipt 分项结算；
- 尚未完成有资金的公开 OP Sepolia E2E、两个生产级 RPC 和第三方合约审计。

因此 OP Sepolia 最多录入 `SHADOW`，OP Mainnet 必须保持 `DISABLED`。

## 1. 资金和权限准备

Authority 不需要预存 ETH。部署账户只负责部署；链级 Relayer 持有 OP ETH 并支付执行、L1 数据及 operator fee。每个租户必须有
自己的 `ACTIVE custody_gas_account`、`ETH_OP` 账本可用额和租户热钱包。Relayer 可以作为链级设施共享，但 recipient、Gas
usage、batch、item 和 ledger 必须全部绑定同一个 `tenant_id`。

部署私钥、Relayer 私钥、助记词、RPC key 和数据库凭据只能进入密钥系统或运行时 secret，禁止写入仓库、命令行、日志或
deployment JSON。生产 admin 使用多签并与 Relayer 分离。

## 2. RPC 与完整费用门禁

官方 RPC：

```bash
mvn -pl backendservices/wallet-parent/wallet-service -am \
  -Dtest=Evm7702RpcCapabilityIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Devm.7702.rpc-capability.enabled=true \
  -Devm.7702.rpc-url=https://sepolia.optimism.io \
  -Devm.7702.chain-id=11155420 \
  test
```

项目 PublicNode：

```bash
mvn -pl backendservices/wallet-parent/wallet-service -am \
  -Dtest=Evm7702RpcCapabilityIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Devm.7702.rpc-capability.enabled=true \
  -Devm.7702.rpc-url=https://optimism-sepolia-rpc.publicnode.com \
  -Devm.7702.chain-id=11155420 \
  test
```

测试必须核对 chainId，生成有效 authorization tuple，并要求 estimate 严格大于 `21000`。官方公共 RPC 限流且不适合生产；
为 primary/backup 采购两个独立供应商并逐个通过 estimate、send、receipt、logs 和 GasPriceOracle。

只读费用门禁：

```bash
mvn -pl backendservices/wallet-parent/wallet-server -am \
  -Dtest=Evm7702OpStackFeeIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Devm.7702.op-stack-fee.enabled=true \
  -Devm.7702.rpc-url=https://sepolia.optimism.io \
  -Devm.7702.chain-id=11155420 \
  test
```

该测试读取真实 receipt 的 `l1Fee`，并使用签名后的 type-2 原始交易验证预部署 GasPriceOracle 的 `getL1Fee` 与
`getOperatorFee`。任何非本地查询失败或 receipt 缺少 `l1Fee` 都禁止广播/确认。

## 3. 本地合约和 chainId 11155420 验证

```bash
cd evm-fork
npm ci
npm run compile
npm run test:7702
HARDHAT_CHAIN_ID=11155420 npm run node
```

另一个终端：

```bash
cd evm-fork
export EVM_CHAIN=OPTIMISM
export EVM_NETWORK=local
export EIP7702_ADMIN_ADDRESS=0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266
export EIP7702_RELAYER_ADDRESS=0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266
npm run deploy:7702
export EIP7702_DEPLOYMENT_FILE="$PWD/deployments/OPTIMISM-EIP7702-local.json"
EVM_VERIFY_RPC_URL=http://127.0.0.1:8545 npm run verify:7702
```

Hardhat 公开账户只允许本地。部署文件排他创建；已有文件先归档，禁止覆盖证据。逐项核对 chainId、admin、relayer、合约地址和
runtime code hash。

## 4. 真实 type-4 和生产路径

```bash
mvn -pl backendservices/wallet-parent/wallet-service -am \
  -Dtest=Evm7702Type4IntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Devm.7702.enabled=true \
  -Devm.7702.chain-id=11155420 \
  -Devm.7702.collector='<COLLECTOR_ADDRESS>' \
  -Devm.7702.delegate='<DELEGATE_ADDRESS>' \
  test

mvn -pl backendservices/wallet-parent/wallet-server -am \
  -Dtest=Evm7702ProductionFlowIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Devm.7702.production.enabled=true \
  -Devm.7702.test.chain=OPTIMISM \
  -Devm.7702.test.chain-id=11155420 \
  -Devm.7702.collector='<COLLECTOR_ADDRESS>' \
  -Devm.7702.delegate='<DELEGATE_ADDRESS>' \
  test
```

验收必须包括：3 个零 ETH Authority 共用一个真实 type-4 txid；两租户五地址互不混批；code hash 错误不广播；unknown 广播
只重放同一份加密 raw transaction；每租户只结算一条 `COLLECTION_BATCH`；本地 L1/operator fee 为 0 且 total=l2。

## 5. OP Stack 完整费用模型

```text
executionFee = gasUsed * effectiveGasPrice
totalFee     = executionFee + receipt.l1Fee + operatorFee
```

- 签名后、写入 outbox 前，向 `0x420000000000000000000000000000000000000F` 调用
  `getL1Fee(signedRawTransaction)` 与 `getOperatorFee(gasLimit)`；
- 两项估值与 `gasLimit * maxFeePerGas` 一起预留，非本地查询失败不广播；
- 确认时读取 canonical receipt 的精确 `l1Fee`；receipt 的 operator scalar/constant 非零时，在该 receipt block 调用
  `getOperatorFee(gasUsed)`，不在 Java 中硬编码 Isthmus/Jovian 公式；
- 分别保存 `l2_fee_atomic`、`l1_fee_atomic`、`operator_fee_atomic`、`total_fee_atomic`，Gas usage 只按 total 结算一次；
- 多占退回、少占补扣，余额不足进入 `OVERDUE`，但不能回滚已经完成的链上归集。

## 6. 部署 OP Sepolia

只给部署账户和 Relayer 获取测试 ETH，不给 Authority 补 Gas：

```bash
cd evm-fork
export EVM_DEPLOY_RPC_URL='<DEDICATED_OP_SEPOLIA_RPC>'
export EVM_DEPLOYER_PRIVATE_KEY='<CONTROLLED_TESTNET_DEPLOYER_PRIVATE_KEY>'
export EVM_CHAIN=OPTIMISM
export EVM_NETWORK=sepolia
export EIP7702_ADMIN_ADDRESS='<OP_SEPOLIA_ADMIN_MULTISIG>'
export EIP7702_RELAYER_ADDRESS='<OP_SEPOLIA_RELAYER_ADDRESS>'
npx hardhat run scripts/deploy-eip7702-collection.js --network deployment
unset EVM_DEPLOYER_PRIVATE_KEY
```

用 primary/backup 分别运行 `verify:7702`，两边 chainId 必须为 `11155420`、code hash 必须一致；随后在 OP Explorer 验证源码与
编译参数，并由 admin 多签复核 Relayer allowlist。

## 7. 数据库 SHADOW 配置

先确认链级 Relayer 可由 wallet-server 密钥派生，结果恰好一行且等于合约 allowlist：

```sql
SELECT id, address, derivation_path, wallet_role, enabled
FROM chain_address
WHERE chain = 'OPTIMISM' AND asset_symbol = 'ETH_OP'
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
    gen_random_uuid(), 'OPTIMISM', 'sepolia', 11155420, 1,
    '<DELEGATE_ADDRESS>', '<DELEGATE_CODE_HASH>',
    '<COLLECTOR_ADDRESS>', '<COLLECTOR_CODE_HASH>',
    '<PAYOUT_DELEGATE_ADDRESS>', '<PAYOUT_DELEGATE_CODE_HASH>',
    <RELAYER_CHAIN_ADDRESS_ID>, '<RELAYER_ADDRESS>', 'SHADOW',
    10, 5000000, 0.5000, 1.2000, 900, 40, false, false, 3000, 10);
COMMIT;
```

既有充值地址回填 `evm_7702_account`。OP 原生币内部符号在 profile、asset、address、Gas account 和 ledger 中统一为 `ETH_OP`。

## 8. 有资金 E2E、Mainnet 与回滚

有资金 E2E 必须验证：三个 Authority 的 ETH 始终为 0；首次 type-4 和后续 type-2 都成功；primary/backup 都能全流程；
预留包含执行/L1/operator 三段费用；receipt 四段审计字段、Gas usage 和租户 ledger 完全一致；保存 txid、receipt、op-geth
版本、code hash 和对账证据。完成后恢复 `SHADOW`。

OP Mainnet 只能在 Sepolia 灰度至少 7 天、独立审计和变更审批后开始。重跑 chainId `10` 的所有门禁，用独立 Mainnet
admin/Relayer 部署，双 RPC/code hash/Explorer 双人复核，先 `SHADOW`，再单租户小额短窗口 `ACTIVE`。

暂停：

```sql
BEGIN;
UPDATE evm_7702_config
SET status = 'PAUSED', updated_at = now()
WHERE chain = 'OPTIMISM' AND network = '<NETWORK>' AND status = 'ACTIVE';
COMMIT;
```

PAUSED 后继续恢复和确认已广播/unknown 批次。全部终态并完成费用对账后才可回旧流程；旧流程需要给地址补 OP ETH。

## 9. 官方依据

- [OP Mainnet/OP Sepolia chainId 与 RPC](https://docs.optimism.io/op-mainnet/network-information/connecting-to-op)
- [OP Stack 网络升级激活表](https://docs.optimism.io/op-stack/protocol/network-upgrades)
- [Jovian 激活与费用相关变化](https://docs.optimism.io/notices/archive/upgrade-17)
- [OP Stack 交易费用组成](https://docs.optimism.io/op-stack/transactions/fees)
- [OP geth receipt 扩展字段](https://docs.optimism.io/node-operators/reference/op-geth-json-rpc)
- [GasPriceOracle](https://docs.optimism.io/app-developers/tools/data/oracles)
