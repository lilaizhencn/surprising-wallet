# 逐链全流程验收记录

更新时间：2026-07-21

## 通过标准

每条链只有同时满足以下条件才可以标记为“通过”：

1. 适合该链的本地节点、Devnet 或官方测试网络可重复启动和停止；
2. 平台配置、RPC、租户开通、归集/Gas 地址和 Gas 充值正确；
3. API 地址生成幂等、地址版本轮换正确，EVM 地址共享规则正确；
4. 外部充值原生币后，扫链、确认、租户投影、Webhook、Demo 用户入账和重放幂等正确；
5. Demo 用户提现经过余额冻结、广播、确认回调和最终扣款，失败时可安全解冻；
6. 归集、手续费、租户总资产、用户负债、地址列表和流水能完整对账；
7. 该链每个已支持 Token 分别完成充值、提现、归集和资产聚合验证；
8. pending、confirmed、failed、重试、重复事件以及该链适用的 reorg/回滚场景通过；
9. 相关自动化回归测试通过，节点停止后没有遗留测试任务或未决资金状态。

## 当前矩阵

“基础接入”只代表配置和地址 API 等非资金链路通过，不代表整条链验收通过。

| 链 | 验收网络 | Token 范围 | 当前状态 | 已有证据 / 待完成 |
|---|---|---|---|---|
| ADA | preprod | 待核对 | 待测试 | Profile 当前关闭 |
| APTOS | testnet 配置 + localnet 资金流 | USDC、USDT（FA） | 进行中 | 官方 Testnet 配置、API/Webhook、地址幂等已通过；USDC/USDT 本地真实链上充值、提现、归集和对账已通过；待补 APT 与 Demo 实际回调资金流 |
| ARBITRUM | sepolia 配置 + Hardhat | USDC、USDT | 钱包资金流通过 | 原生币与全部 Token 全流程、恢复、nonce、对账通过；待租户 API/Webhook 实际资金流 |
| AVAX_C | fuji 配置 + Hardhat | USDC、USDT | 钱包资金流通过 | 原生币与全部 Token 全流程、恢复、nonce、对账通过；待租户 API/Webhook 实际资金流 |
| BASE | sepolia 配置 + Hardhat | USDC、USDT | 钱包资金流通过 | 原生币与全部 Token 全流程、恢复、nonce、对账通过；待租户 API/Webhook 实际资金流 |
| BCH | regtest | 无 | 钱包资金流通过 | 全新链充值、提现、归集、幂等、并发、防双花和批量广播通过；待租户 API/Webhook 实际资金流 |
| BNB | testnet 配置 + Hardhat | USDC、USDT | 钱包资金流通过 | 原生币与全部 Token 全流程、恢复、nonce、对账通过；待租户 API/Webhook 实际资金流 |
| BTC | regtest | 无 | 钱包资金流通过 | 全新链充值、提现、归集、幂等、并发、防双花和批量广播通过；待租户 API/Webhook 实际资金流 |
| DOGE | regtest | 无 | 钱包资金流通过 | 全新链充值、提现、归集、幂等、并发、防双花和批量广播通过；待租户 API/Webhook 实际资金流 |
| DOT | westend / 本地 runtime | 待核对 | 待测试 | Profile 当前关闭 |
| ETH | sepolia 配置 + Hardhat | USDC、USDT | 钱包资金流通过 | 原生币与全部 Token 全流程、恢复、nonce、对账通过；待租户 API/Webhook 实际资金流 |
| HYPERCORE | testnet | HYPE | 待测试 | - |
| HYPEREVM | testnet 配置 + Hardhat | USDC | 钱包资金流通过 | HYPE/USDC 全流程、恢复、nonce、对账通过；待租户 API/Webhook 实际资金流 |
| LINEA | sepolia 配置 + Hardhat | USDC | 钱包资金流通过 | ETH_LINEA/USDC 全流程、恢复、nonce、对账通过；待租户 API/Webhook 实际资金流 |
| LTC | regtest | 无 | 钱包资金流通过 | 全新链充值、提现、归集、幂等、并发、防双花和批量广播通过；待租户 API/Webhook 实际资金流 |
| MANTLE | sepolia 配置 + Hardhat | 无 | 钱包资金流通过 | 原生 MNT 全流程、恢复、nonce、对账通过；待租户 API/Webhook 实际资金流 |
| NEAR | testnet / sandbox | 待核对 | 待测试 | Profile 当前关闭 |
| OPTIMISM | sepolia 配置 + Hardhat | USDC、USDT | 钱包资金流通过 | 原生币与全部 Token 全流程、恢复、nonce、对账通过；待租户 API/Webhook 实际资金流 |
| POLYGON | amoy 配置 + Hardhat | USDC、USDT | 钱包资金流通过 | 原生币与全部 Token 全流程、恢复、nonce、对账通过；待租户 API/Webhook 实际资金流 |
| SCROLL | sepolia 配置 + Hardhat | USDC | 钱包资金流通过 | ETH_SCROLL/USDC 全流程、恢复、nonce、对账通过；待租户 API/Webhook 实际资金流 |
| SOLANA | devnet / test-validator | USDC、USDT | 待测试 | - |
| SUI | testnet / localnet | USDC | 待测试 | - |
| TON | testnet | USDC、USDT | 待测试 | - |
| TRON | nile / local private chain | 当前无启用 Token | 待测试 | - |
| UNICHAIN | sepolia 配置 + Hardhat | USDC | 钱包资金流通过 | ETH_UNICHAIN/USDC 全流程、恢复、nonce、对账通过；待租户 API/Webhook 实际资金流 |
| XMR | regtest | 无 | 待测试 | Profile 当前关闭，仓库已有 regtest 配置 |
| XRP | testnet | USDC | 待测试 | - |

## Aptos 当前证据

- Demo 专用 API Key 已通过正式 Console 服务创建；
- Demo Webhook 已完成 Challenge 验证并处于 `ACTIVE`；
- `exchange-user-10001` 已通过公开 API 生成 Aptos Testnet 地址；
- 在线验收脚本验证同一 `subject + addressVersion` 幂等，以及 `addressVersion=1` 的地址轮换；
- 公开链接口返回原生 APT、FA USDC、FA USDT；
- Webhook HMAC、事件幂等、充值记账、提现冻结/确认/失败解冻已通过 Demo 自动化测试；
- 官方 Testnet USDC 元数据验证通过；USDT 使用明确标记为测试用途的 FA 地址；
- 公共 Testnet Faucet 需要外部账户凭证，因此资金广播验收使用隔离 Aptos Localnet；
- Localnet 部署的 USDC、USDT 均为 Aptos FA、6 位精度，不包含 MUSD 或 `APTOS_COIN`；
- USDC、USDT 分别完成真实链上充值、扫描入账、重复扫描幂等、提现确认、归集确认；
- 隔离库中的用户账本与平台控制地址链上余额逐币相等，锁定余额和负余额均为 0；
- `infra/aptos/run-fa-flow.sh` 可从空 Localnet 和临时数据库重复执行并自动清理；
- Aptos 整链仍需补 APT 原生币资金流，以及通过正式 API/Webhook 驱动 Demo 的实际回调验收。

## EVM 当前证据

- `evm-fork/scripts/run-local-matrix.sh` 从空临时数据库和全新节点开始逐链执行；
- 12 条启用 EVM 链全部通过，节点逐链停止，临时数据库与日志自动清理；
- 双 Token、单 Token 和无 Token 配置都按数据库实际启用范围执行；
- 原生币及每个 Token 完成充值、重复扫描幂等、提现、归集、广播失败恢复和中断恢复；
- 各用户数据库余额与链上余额一致，nonce 与实际广播次数一致；
- 所有订单进入终态，没有负数可用、锁定或总余额；
- 修复 `evm_tx` 与 `tron_tx` upsert 冲突更新时交叉引用错误表名的问题；
- 仍需让租户 Demo 通过正式 API/Webhook 驱动 EVM 实际资金流，完成 SaaS 层验收。

## Bitcoin-like 当前证据

- BTC、LTC、DOGE、BCH 分别从创世 regtest 状态启动，测试完一条立即停止后再启动下一条；
- 每条链完成真实充值、6 确认、重复入账幂等、提现、UTXO 锁定、归集及余额检查；
- 每条链完成 32 路并发入账、并发冻结、UTXO 防重复锁、提现/归集任务抢占；
- 每条链完成 8 笔并发充值和 4 笔并发提现广播，全部产生真实 txid；
- 所有账本均保持非负，临时 PostgreSQL 数据库已删除，4 个节点均处于停止状态；
- 修复 regtest profile 切换顺序，确保启用 regtest 前关闭同链已有测试网络；
- 仍需让租户 Demo 通过正式 API/Webhook 驱动 UTXO 实际资金流，完成 SaaS 层验收。
