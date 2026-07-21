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
| APTOS | testnet | USDC、USDT（FA） | 进行中 | API 鉴权、Webhook 验证、APT/USDC/USDT 配置、地址幂等及轮换已通过；缺真实资金充值、提现、归集和对账 |
| ARBITRUM | sepolia | USDC、USDT | 待测试 | - |
| AVAX_C | fuji | USDC、USDT | 待测试 | - |
| BASE | sepolia | USDC、USDT | 待测试 | - |
| BCH | regtest 优先 | 无 | 待测试 | 仓库已有 regtest 基础设施 |
| BNB | testnet / Hardhat | USDC、USDT | 待测试 | - |
| BTC | regtest | 无 | 待测试 | 仓库已有 regtest 基础设施 |
| DOGE | regtest | 无 | 待测试 | 仓库已有 regtest 基础设施 |
| DOT | westend / 本地 runtime | 待核对 | 待测试 | Profile 当前关闭 |
| ETH | Hardhat / sepolia | USDC、USDT | 待测试 | 仓库已有 Hardhat 工程 |
| HYPERCORE | testnet | HYPE | 待测试 | - |
| HYPEREVM | testnet | USDC | 待测试 | - |
| LINEA | sepolia / Hardhat | USDC | 待测试 | - |
| LTC | regtest 优先 | 无 | 待测试 | 仓库已有 regtest 基础设施 |
| MANTLE | sepolia / Hardhat | 无 | 待测试 | - |
| NEAR | testnet / sandbox | 待核对 | 待测试 | Profile 当前关闭 |
| OPTIMISM | sepolia / Hardhat | USDC、USDT | 待测试 | - |
| POLYGON | amoy / Hardhat | USDC、USDT | 待测试 | - |
| SCROLL | sepolia / Hardhat | USDC | 待测试 | - |
| SOLANA | devnet / test-validator | USDC、USDT | 待测试 | - |
| SUI | testnet / localnet | USDC | 待测试 | - |
| TON | testnet | USDC、USDT | 待测试 | - |
| TRON | nile / local private chain | 当前无启用 Token | 待测试 | - |
| UNICHAIN | sepolia / Hardhat | USDC | 待测试 | - |
| XMR | regtest | 无 | 待测试 | Profile 当前关闭，仓库已有 regtest 配置 |
| XRP | testnet | USDC | 待测试 | - |

## Aptos 当前证据

- Demo 专用 API Key 已通过正式 Console 服务创建；
- Demo Webhook 已完成 Challenge 验证并处于 `ACTIVE`；
- `exchange-user-10001` 已通过公开 API 生成 Aptos Testnet 地址；
- 在线验收脚本验证同一 `subject + addressVersion` 幂等，以及 `addressVersion=1` 的地址轮换；
- 公开链接口返回原生 APT、FA USDC、FA USDT；
- Webhook HMAC、事件幂等、充值记账、提现冻结/确认/失败解冻已通过 Demo 自动化测试；
- 测试账户仍缺 APT Gas 与 FA 测试币，因此尚未产生真实链上充值、提现和归集交易。
