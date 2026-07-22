# Arbitrum EIP-7702 批量归集生产上线手册

本文是 [EIP-7702 免 Gas 批量归集实施方案](./eip7702-collection.md) 的 Arbitrum 专项手册。范围仅为标准 ERC-20
归集；充值地址可以保持 `0 ETH`，由 Arbitrum 链级 Relayer 支付外层交易全部费用。原生 ETH 归集、提现和资产兑换不走本流程。

ArbOS 40 Callisto 引入 EIP-7702；当前 Arbitrum One 已运行在其后的 ArbOS 51 Dia，后者修复了 7702 委托到预编译地址时的
协议行为差异。项目网络为 Arbitrum Sepolia `421614` 和 Arbitrum One `42161`。当前仓库验收状态（2026-07-22）：

- 官方 Arbitrum Sepolia RPC 与项目 PublicNode 均通过有效 authorization list 的严格 `eth_estimateGas` 门禁；
- chainId 421614 的 Hardhat Prague 已通过 3 个零 ETH Authority 的真实 Web3j type-4；
- PostgreSQL 两租户五地址生产路径通过，每租户独立批次、txid、Gas 预留和一次结算；
- Nitro 的父链数据费已按 receipt 的 `gasUsedForL1` 从总 gas 中拆分审计，没有重复收费；
- 尚未完成有资金的公开 Arbitrum Sepolia E2E、两个生产级 RPC 和第三方合约审计。

因此 Arbitrum Sepolia 最多录入 `SHADOW`，Arbitrum One 必须保持 `DISABLED`。

## 1. 资金和租户准备

Authority 不需要预存 ETH。只给部署账户准备少量测试 ETH，并给链级 Relayer 保持足够的 Arbitrum ETH。每个租户必须有自己的
`ACTIVE custody_gas_account`、`ETH_ARB` 账本可用额和租户热钱包。Relayer 可以作为链级广播设施共享，但 recipient、Gas usage、
batch、item 和 ledger 必须全部绑定同一个 `tenant_id`。

部署私钥、Relayer 私钥、助记词、RPC key 和数据库凭据只能进入密钥系统或运行时 secret，禁止写入仓库、命令行、日志或
deployment JSON。生产 admin 使用多签并与 Relayer 分离。

## 2. RPC 与费用字段门禁

官方 RPC：

```bash
mvn -pl backendservices/wallet-parent/wallet-service -am \
  -Dtest=Evm7702RpcCapabilityIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Devm.7702.rpc-capability.enabled=true \
  -Devm.7702.rpc-url=https://sepolia-rollup.arbitrum.io/rpc \
  -Devm.7702.chain-id=421614 \
  test
```

项目 PublicNode：

```bash
mvn -pl backendservices/wallet-parent/wallet-service -am \
  -Dtest=Evm7702RpcCapabilityIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Devm.7702.rpc-capability.enabled=true \
  -Devm.7702.rpc-url=https://arbitrum-sepolia-rpc.publicnode.com \
  -Devm.7702.chain-id=421614 \
  test
```

测试必须核对 chainId，生成有效 authorization tuple，并要求 estimate 严格大于 `21000`。生产不能依赖无 SLA 的公开 RPC；
为 primary/backup 采购两个独立供应商并逐个通过 estimate、send、receipt 和 logs。

再执行只读费用门禁，确认 Nitro receipt 暴露正数 `gasUsedForL1`：

```bash
mvn -pl backendservices/wallet-parent/wallet-server -am \
  -Dtest=Evm7702ArbitrumFeeIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Devm.7702.arbitrum-fee.enabled=true \
  -Devm.7702.rpc-url=https://sepolia-rollup.arbitrum.io/rpc \
  -Devm.7702.chain-id=421614 \
  test
```

任何非本地 Arbitrum receipt 缺少 `gasUsedForL1` 都停止确认并告警，不能把字段缺失当 0。

## 3. 本地合约和 chainId 421614 验证

```bash
cd evm-fork
npm ci
npm run compile
npm run test:7702
HARDHAT_CHAIN_ID=421614 npm run node
```

另一个终端：

```bash
cd evm-fork
export EVM_CHAIN=ARBITRUM
export EVM_NETWORK=local
export EIP7702_ADMIN_ADDRESS=0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266
export EIP7702_RELAYER_ADDRESS=0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266
npm run deploy:7702
export EIP7702_DEPLOYMENT_FILE="$PWD/deployments/ARBITRUM-EIP7702-local.json"
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
  -Devm.7702.chain-id=421614 \
  -Devm.7702.collector='<COLLECTOR_ADDRESS>' \
  -Devm.7702.delegate='<DELEGATE_ADDRESS>' \
  test

mvn -pl backendservices/wallet-parent/wallet-server -am \
  -Dtest=Evm7702ProductionFlowIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Devm.7702.production.enabled=true \
  -Devm.7702.test.chain=ARBITRUM \
  -Devm.7702.test.chain-id=421614 \
  -Devm.7702.collector='<COLLECTOR_ADDRESS>' \
  -Devm.7702.delegate='<DELEGATE_ADDRESS>' \
  test
```

验收必须包括：3 个零 ETH Authority 共用一个真实 type-4 txid；两租户五地址互不混批；code hash 错误不广播；unknown 广播
只重放同一份加密 raw transaction；每租户只结算一条 `COLLECTION_BATCH`；本地没有 Nitro 扩展字段时仅因 network=`local`
允许父链费为 0。

## 5. Arbitrum 费用模型

Nitro 与 OP Stack 不同：父链数据费被转换为 child-chain gas 并直接烘焙进 `gasUsed`。因此：

```text
l1Gas    = receipt.gasUsedForL1
l2Gas    = receipt.gasUsed - l1Gas
l1Fee    = l1Gas * receipt.effectiveGasPrice
l2Fee    = l2Gas * receipt.effectiveGasPrice
totalFee = l1Fee + l2Fee
         = receipt.gasUsed * receipt.effectiveGasPrice
```

`eth_estimateGas` 已同时包含执行 Gas 和父链数据 buffer，所以广播前继续按 `gasLimit * maxFeePerGas` 预留，不再额外加一次 L1
fee。确认时把两部分分别写入 `l1_fee_atomic` 和 `l2_fee_atomic`，`operator_fee_atomic=0`，并校验
`total_fee_atomic = l1_fee_atomic + l2_fee_atomic`。如果像 Base 一样额外叠加 receipt L1 字段，会造成重复扣费。

ArbOS 51 把实际执行 gas cap 与可见 block gas limit 分开。批次仍受 `evm_7702_config.max_batch_gas`、估算安全系数和合约 100
item 硬上限约束，不能根据 RPC 返回的巨大 block gas limit 放大批次。

## 6. 部署 Arbitrum Sepolia

只给部署账户和 Relayer 获取测试 ETH，不给 Authority 补 Gas：

```bash
cd evm-fork
export EVM_DEPLOY_RPC_URL='<DEDICATED_ARBITRUM_SEPOLIA_RPC>'
export EVM_DEPLOYER_PRIVATE_KEY='<CONTROLLED_TESTNET_DEPLOYER_PRIVATE_KEY>'
export EVM_CHAIN=ARBITRUM
export EVM_NETWORK=sepolia
export EIP7702_ADMIN_ADDRESS='<ARBITRUM_SEPOLIA_ADMIN_MULTISIG>'
export EIP7702_RELAYER_ADDRESS='<ARBITRUM_SEPOLIA_RELAYER_ADDRESS>'
npx hardhat run scripts/deploy-eip7702-collection.js --network deployment
unset EVM_DEPLOYER_PRIVATE_KEY
```

用 primary/backup 分别运行 `verify:7702`，两边 chainId 必须为 `421614`、code hash 必须一致；随后在 Arbiscan 验证源码与
编译参数，并由 admin 多签复核 Relayer allowlist。

## 7. 数据库 SHADOW 配置

先确认链级 Relayer 可由 wallet-server 密钥派生，结果恰好一行且等于合约 allowlist：

```sql
SELECT id, address, derivation_path, wallet_role, enabled
FROM chain_address
WHERE chain = 'ARBITRUM' AND asset_symbol = 'ETH_ARB'
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
    gen_random_uuid(), 'ARBITRUM', 'sepolia', 421614, 1,
    '<DELEGATE_ADDRESS>', '<DELEGATE_CODE_HASH>',
    '<COLLECTOR_ADDRESS>', '<COLLECTOR_CODE_HASH>',
    '<PAYOUT_DELEGATE_ADDRESS>', '<PAYOUT_DELEGATE_CODE_HASH>',
    <RELAYER_CHAIN_ADDRESS_ID>, '<RELAYER_ADDRESS>', 'SHADOW',
    10, 5000000, 0.5000, 1.2000, 900, 40, false, false, 3000, 10);
COMMIT;
```

既有充值地址回填 `evm_7702_account`。Arbitrum 原生币内部符号在 profile、asset、address、Gas account 和 ledger 中统一为
`ETH_ARB`。`40` 是当前项目确认参数，不代表 Ethereum 父链最终性；上线审批必须明确业务需要的 L2、批次发布和父链最终性层级。

## 8. 有资金 E2E、Mainnet 与回滚

有资金 E2E 必须验证：三个 Authority 的 ETH 始终为 0；首次 type-4 和后续 type-2 都成功；primary/backup 都能全流程；
`gasUsedForL1` 拆分恒等式、Gas usage 和租户 ledger 完全一致；保存 txid、receipt、Nitro/ArbOS 版本、code hash 和对账证据。
完成后恢复 `SHADOW`。

Arbitrum One 只能在 Sepolia 灰度至少 7 天、独立审计和变更审批后开始。重跑 chainId `42161` 的所有门禁，用独立 Mainnet
admin/Relayer 部署，双 RPC/code hash/Arbiscan 双人复核，先 `SHADOW`，再单租户小额短窗口 `ACTIVE`。

暂停：

```sql
BEGIN;
UPDATE evm_7702_config
SET status = 'PAUSED', updated_at = now()
WHERE chain = 'ARBITRUM' AND network = '<NETWORK>' AND status = 'ACTIVE';
COMMIT;
```

PAUSED 后继续恢复和确认已广播/unknown 批次。全部终态并完成费用对账后才可回旧流程；旧流程需要给地址补 Arbitrum ETH。

## 9. 官方依据

- [ArbOS 40 Callisto 与 EIP-7702](https://docs.arbitrum.io/run-arbitrum-node/arbos-releases/arbos40)
- [ArbOS 51 Dia 激活与 EIP-7702 修复](https://docs.arbitrum.io/run-arbitrum-node/arbos-releases/arbos51)
- [Arbitrum chainId、官方 RPC 与当前 Gas 参数](https://docs.arbitrum.io/for-devs/dev-tools-and-resources/chain-info)
- [Nitro Gas 与父链数据费用](https://docs.arbitrum.io/how-arbitrum-works/deep-dives/gas-and-fees)
- [Arbitrum 单一费用与 L1 buffer 估算](https://docs.arbitrum.io/arbitrum-essentials/how-to-estimate-gas)
