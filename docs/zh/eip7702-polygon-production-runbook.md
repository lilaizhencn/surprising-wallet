# Polygon EIP-7702 批量归集生产上线手册

本文是 [EIP-7702 免 Gas 批量归集实施方案](./eip7702-collection.md) 的 Polygon PoS 专项手册。范围仅为标准
ERC-20 归集；充值地址可以保持 `0 POL`，由 Polygon 链级 Relayer 支付外层交易 Gas。原生 POL 归集、提现和资产兑换不走本流程。

Polygon Bhilai hardfork 已启用 Pectra EIPs，PIP-61 明确包含 EIP-7702。项目网络是 Amoy `80002` 和 Mainnet `137`。
当前仓库验收状态（2026-07-22）：

- 项目配置的 Amoy PublicNode RPC 成功估算带有效 authorization list 的 type-4；
- Polygon 官方 RPC 列表中的 `https://polygon-amoy.drpc.org` 虽然不报错，但只返回普通 21,000 Gas，未计入 authorization，
  因此判定为不兼容并排除；
- chainId 80002 的 Hardhat Prague 已通过 3 个零 POL Authority 的真实 Web3j type-4；
- PostgreSQL 两租户五地址生产路径通过，每租户独立批次、txid 和一次 Gas 结算；
- 原生币内部符号已从旧 `MATIC` 直接收敛为官方当前 `POL`，没有保留双符号兼容层；
- 尚未完成有资金的公开 Amoy E2E、第二个独立兼容 RPC 和第三方合约审计。

因此 Amoy 最多录入 `SHADOW`，Mainnet 必须保持 `DISABLED`。

## 1. 资金和租户准备

Authority 不需要预存 POL。只需：部署账户持有少量测试 POL；链级 Relayer 持有 POL 并有余额告警；每个租户具有自己的
`ACTIVE custody_gas_account`、POL 账本可用额和租户热钱包。Relayer 可以作为链级广播设施共享，但 Token recipient、Gas usage、
batch、item 和账本必须全部带同一个 `tenant_id`。

禁止把部署私钥、Relayer 私钥、助记词、RPC key 或生产数据库凭据写入仓库、命令行、日志或部署 JSON。

## 2. RPC 能力门禁

对项目当前 Amoy RPC 执行：

```bash
mvn -pl backendservices/wallet-parent/wallet-service -am \
  -Dtest=Evm7702RpcCapabilityIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Devm.7702.rpc-capability.enabled=true \
  -Devm.7702.rpc-url=https://polygon-amoy-bor-rpc.publicnode.com \
  -Devm.7702.chain-id=80002 \
  test
```

测试核对 chainId，生成有效 authorization tuple，并要求 estimate 严格大于普通转账的 `21000`。只要 RPC 返回成功却没有增加
authorization intrinsic gas，也视为不支持。每个 primary/backup 必须逐个通过；当前 dRPC 已失败，不能作为备份。找到第二个
独立兼容供应商并通过前，不允许 `ACTIVE`。

## 3. 本地合约和 chainId 80002 验证

```bash
cd evm-fork
npm ci
npm run compile
npm run test:7702
HARDHAT_CHAIN_ID=80002 npm run node
```

另一个终端部署：

```bash
cd evm-fork
export EVM_CHAIN=POLYGON
export EVM_NETWORK=local
export EIP7702_ADMIN_ADDRESS=0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266
export EIP7702_RELAYER_ADDRESS=0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266
npm run deploy:7702
```

Hardhat 公开账户只允许本地。脚本排他创建 `deployments/POLYGON-EIP7702-local.json`；已有文件时先归档，禁止覆盖证据。
核对 chainId、admin、relayer、合约地址和 runtime code hash，然后执行：

```bash
export EIP7702_DEPLOYMENT_FILE="$PWD/deployments/POLYGON-EIP7702-local.json"
EVM_VERIFY_RPC_URL=http://127.0.0.1:8545 npm run verify:7702
```

## 4. 真实 type-4 和生产路径

在仓库根目录依次执行：

```bash
mvn -pl backendservices/wallet-parent/wallet-service -am \
  -Dtest=Evm7702Type4IntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Devm.7702.enabled=true \
  -Devm.7702.chain-id=80002 \
  -Devm.7702.collector='<COLLECTOR_ADDRESS>' \
  -Devm.7702.delegate='<DELEGATE_ADDRESS>' \
  test

mvn -pl backendservices/wallet-parent/wallet-server -am \
  -Dtest=Evm7702ProductionFlowIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Devm.7702.production.enabled=true \
  -Devm.7702.test.chain=POLYGON \
  -Devm.7702.test.chain-id=80002 \
  -Devm.7702.collector='<COLLECTOR_ADDRESS>' \
  -Devm.7702.delegate='<DELEGATE_ADDRESS>' \
  test
```

验收必须包括：3 个零 POL Authority 共用一个真实 type-4 txid；两租户五地址互不混批；调度器发现 `POLYGON/local`；code hash
错误不广播；unknown 广播只重放相同 raw transaction；每租户只结算一条 `COLLECTION_BATCH`；Gas account 和 ledger 的原生符号
都是 `POL`。

## 5. 部署 Amoy 并双 RPC 验证

只给部署账户和 Relayer 获取测试 POL，不给 Authority 补 Gas：

```bash
cd evm-fork
export EVM_DEPLOY_RPC_URL='<COMPATIBLE_AMOY_RPC>'
export EVM_DEPLOYER_PRIVATE_KEY='<CONTROLLED_TESTNET_DEPLOYER_PRIVATE_KEY>'
export EVM_CHAIN=POLYGON
export EVM_NETWORK=amoy
export EIP7702_ADMIN_ADDRESS='<AMOY_ADMIN_MULTISIG>'
export EIP7702_RELAYER_ADDRESS='<AMOY_RELAYER_ADDRESS>'
npx hardhat run scripts/deploy-eip7702-collection.js --network deployment
unset EVM_DEPLOYER_PRIVATE_KEY
```

```bash
export EIP7702_DEPLOYMENT_FILE="$PWD/deployments/POLYGON-EIP7702-amoy.json"
EVM_VERIFY_RPC_URL='https://polygon-amoy-bor-rpc.publicnode.com' npm run verify:7702
EVM_VERIFY_RPC_URL='<SECOND_INDEPENDENT_COMPATIBLE_RPC>' npm run verify:7702
```

两边 chainId 必须为 `80002`，code hash 必须一致；随后在 Amoy PolygonScan 验证源码与编译参数，并由 admin 多签复核 Relayer
allowlist。dRPC 当前不能用于部署、估算、广播或灾备。

## 6. 数据库 SHADOW 配置

先确认 Relayer 可由 wallet-server 根密钥派生：

```sql
SELECT id, address, derivation_path, wallet_role, enabled
FROM chain_address
WHERE chain = 'POLYGON'
  AND asset_symbol = 'POL'
  AND user_id = 0
  AND biz = 0
  AND address_index = 0
  AND wallet_role = 'DEPOSIT';
```

结果必须恰好一行并等于合约 allowlist 地址。录入配置：

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
    gen_random_uuid(), 'POLYGON', 'amoy', 80002, 1,
    '<DELEGATE_ADDRESS>', '<DELEGATE_CODE_HASH>',
    '<COLLECTOR_ADDRESS>', '<COLLECTOR_CODE_HASH>',
    '<PAYOUT_DELEGATE_ADDRESS>', '<PAYOUT_DELEGATE_CODE_HASH>',
    <RELAYER_CHAIN_ADDRESS_ID>, '<RELAYER_ADDRESS>', 'SHADOW',
    10, 5000000, 0.5000, 1.2000, 900, 64, false, false, 3000, 10);
COMMIT;
```

`64` 与当前 profile 一致，上线前由风控按当前 Polygon 最终性重新确认。既有地址需回填 `evm_7702_account`；所有 Polygon
`chain_profile.native_symbol`、`chain_asset`、`chain_address.asset_symbol`、Gas account 和 ledger 必须只出现 `POL`。发现旧
`MATIC` 数据时停止上线并在开发库直接修正，不能双读或双写。

## 7. 有资金 Amoy E2E

单个内部租户、标准无转账税 ERC-20：

1. 三个 Authority 收到小额 Token，POL 余额保持 0。
2. 短窗口 `SHADOW -> ACTIVE`，核对首次 type-4 的 txid、item events、精确 Transfer、operation nonce 和一次 Gas 结算。
3. 同一 Authority 再次入账，核对持久 delegation 和不带新 authorization 的 type-2 后续批次。
4. 两个独立兼容 RPC 都完成 estimate、send、getTransaction、receipt 和 logs。
5. 保存 txid、receipt、client version、code hash 和账务对账证据，完成后恢复 SHADOW。

没有第二个兼容 RPC或上述 E2E 证据时不得长期 ACTIVE。

## 8. Gas、Mainnet 和回滚

Polygon profile 使用 `eip1559`。worker 读取网络 Gas 价格生成 max fee/priority fee，预留上限，并只按 receipt 的
`gasUsed * effectiveGasPrice` 结算。批处理节省的是重复外层交易开销，不降低网络 Gas Price；首次 authorization 另有 intrinsic
cost，必须以实际 receipt 比较。

Mainnet 只能在 Amoy 灰度至少 7 天、独立审计和变更审批后开始：重跑所有 RPC 门禁并核对 chainId `137`，使用独立 Mainnet
admin/Relayer 重新部署，双 RPC/code hash/PolygonScan 双人复核，先 `SHADOW`，再单租户小额短窗口 ACTIVE。

暂停只影响 Polygon：

```sql
BEGIN;
UPDATE evm_7702_config
SET status = 'PAUSED', updated_at = now()
WHERE chain = 'POLYGON' AND network = '<NETWORK>' AND status = 'ACTIVE';
COMMIT;
```

PAUSED 后继续确认/恢复已广播和 unknown 批次。全部终态并完成 Gas 对账后才可回旧流程；旧流程需要给地址补 POL。合约缺陷通过
新版本 Collector/Delegate 灰度，禁止覆盖旧合约或删除审计数据。

## 9. 官方依据

- [Polygon Bhilai hardfork 与 EIP-7702](https://polygon.technology/blog/first-milestone-to-gigagas-1000-tps-with-bhilai-hardfork)
- [Polygon PoS RPC 与 Amoy chainId](https://docs.polygon.technology/pos/reference/rpc-endpoints)
- [PIP-63 Bhilai activation blocks](https://forum.polygon.technology/t/pip-63-bhilai-hardfork/20872)
