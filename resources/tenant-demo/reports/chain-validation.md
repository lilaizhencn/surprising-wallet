# 全链与 SaaS 托管验收报告

更新时间：2026-07-22

代码分支：`master`

数据库：本机 PostgreSQL 18，`127.0.0.1:5432`

## 结论

当前数据库配置的 27 个链标识及其已启用 Token 已完成开发环境验收。逐链资金流、租户隔离、充值重放幂等、提现、归集、Gas、余额与流水对账均通过；随后执行的多链并发系统测试也通过。

本报告是开发环境验收，不等同于主网容量证明。测试使用各链适合的本地节点、官方测试网络或本地 API 仿真；没有使用真实生产密钥和主网资金。

## 验收方法

测试按两层执行，避免为每条链重复实现相同的 SaaS 测试逻辑：

1. 链适配层逐链验证原生币及数据库中该链全部启用 Token，包括地址、充值、重复扫描、提现、归集、Gas/手续费、确认和资金对账。
2. 通用 SaaS 层验证租户开链、公开 API、地址版本、API Key、Webhook、Demo 用户账本、回调重试和租户隔离。该层不包含链特有分支，并另外在 ETH、APTOS 真实资金闭环及 ETH/TRON/SOLANA/SUI 并发负载中交叉验证。

所有测试脚本只使用电脑上已安装的 PostgreSQL 18。脚本可以在该实例内创建临时逻辑测试库，结束后立即删除；没有启动 Docker PostgreSQL、Testcontainers、SQLite、嵌入式数据库或其他独立数据库服务。

## 逐链结果

| 链 | 验收环境 | 已验证资产 | 结果 |
|---|---|---|---|
| BTC | 本地 regtest | BTC | 通过 |
| LTC | 本地 regtest | LTC | 通过 |
| DOGE | 本地 regtest | DOGE | 通过 |
| BCH | 本地 regtest | BCH | 通过 |
| XMR | 本地 `monerod` regtest + `monero-wallet-rpc` | XMR | 通过 |
| ETH | 本地 Hardhat，chainId 11155111 | ETH、USDC、USDT | 通过 |
| BNB | 本地 Hardhat，chainId 97 | BNB、USDC、USDT | 通过 |
| POLYGON | 本地 Hardhat，chainId 80002 | POL、USDC、USDT | 通过 |
| ARBITRUM | 本地 Hardhat，chainId 421614 | ETH_ARB、USDC、USDT | 通过 |
| OPTIMISM | 本地 Hardhat，chainId 11155420 | ETH_OP、USDC、USDT | 通过 |
| BASE | 本地 Hardhat，chainId 84532 | ETH_BASE、USDC、USDT | 通过 |
| AVAX_C | 本地 Hardhat，chainId 43113 | AVAX_C、USDC、USDT | 通过 |
| HYPEREVM | 本地 Hardhat，chainId 998 | HYPE、USDC | 通过 |
| LINEA | 本地 Hardhat，chainId 59141 | ETH_LINEA、USDC | 通过 |
| MANTLE | 本地 Hardhat，chainId 5003 | MNT | 通过 |
| SCROLL | 本地 Hardhat，chainId 534351 | ETH_SCROLL、USDC | 通过 |
| UNICHAIN | 本地 Hardhat，chainId 1301 | ETH_UNICHAIN、USDC | 通过 |
| TRON | 本地 TRE 私链 | TRX、USDC、USDT | 通过 |
| SOLANA | 本地 validator | SOL、USDC、USDT | 通过 |
| SUI | 本地 Sui 节点 + gRPC | SUI、USDC | 通过 |
| TON | MyLocalTon | TON、USDC、USDT | 通过 |
| APTOS | 本地 localnet，官方 testnet 元数据校验 | APT、FA USDC、FA USDT | 通过 |
| NEAR | 官方 sandbox | NEAR、NEP-141 USDC、USDT | 通过 |
| DOT | 本地 Polkadot Relay + Asset Hub | DOT、USDC、USDT | 通过 |
| ADA | 本地 Yaci devnet | ADA、原生资产 USDC、USDT | 通过 |
| XRP | XRPL Testnet | XRP、Issued Currency USDC、USDT | 通过 |
| HYPERCORE | 官方 testnet 元数据 + 本地 API 仿真 | Core USDC、HIP-1 HYPE | 通过 |

### 逐链共同断言

- 地址派生可重复，同一用户的地址规则与地址版本符合当前设计；EVM 兼容链共享同一用户地址。
- 外部充值只入账一次，重复扫描和重复事件不会重复增加用户负债。
- 提现经过创建、冻结、签名/广播、确认和最终扣款，最终锁定余额为零。
- 归集进入租户控制地址，不把内部归集误识别为新的用户充值。
- 原生币和已配置 Token 使用精确定点数或 `BigDecimal`，没有浮点金额计算。
- 所有测试订单进入终态，账本没有负的可用、锁定或总余额。
- 用户账本负债与用户地址及租户热地址的受控链上资产按币种完成对账；网络费单独保留审计路径。

## 关键修复

- 所有链地址记录保留显式 `tenant_id`，充值、提现、归集和查询均按租户隔离。
- 内部归集识别按同租户、同链、同交易和归集目标判断，Cardano 一笔多资产交易携带的 ADA 不再被二次入账。
- NEAR、DOT、ADA、XRP、TON、SOLANA、SUI、XMR 的测试夹具移除无租户兼容入口，直接验证当前 SaaS 模型。
- APTOS 只保留 Fungible Asset 路径，删除 `APTOS_COIN` 与旧 MUSD 逻辑，APT、FA USDC、FA USDT 全流程通过。
- HYPERCORE nonce 改为数据库原子预留，32 路同地址并发没有重复 nonce；首次余额快照先建立锁定行，16 路并发只入账一次。
- EVM 逐链入口改为覆盖数据库实际配置的 12 条链，而不是旧的 7 链说明。
- XRP Testnet 补齐 USDT 配置和 issued-currency 全流程。
- 所有本地测试固定连接已安装的 PostgreSQL 18，并在 `AGENTS.md` 中作为强制约束。

## SaaS 与租户 Demo

`tenant-demo` 是独立 Node.js 应用，不引用钱包后端 Java 内部类，只通过公开 Custody API 和 Webhook 工作。已验证：

- 平台创建租户、租户管理员登录、开通/关闭链；未开通链不能调用链操作 API。
- API Key 创建后默认拥有完整租户 API 能力，不再保留权限选择和权限分支。
- 同一 `subject + addressVersion` 幂等；增加 `addressVersion` 可以为用户轮换地址。
- Webhook 无需选择订阅事件，API 创建地址关联的充值、提现状态都会投递。
- Demo 校验 Webhook 时间戳及 HMAC-SHA256，按事件 ID 幂等维护用户可用和冻结余额。
- ETH 与 APTOS 已通过钱包后端、公开 API、Demo、回调服务和本地链同时启动的真实 SaaS 资金闭环。
- 通用租户数据库测试覆盖租户开链、地址共享/轮换、资产聚合、回调失败筛选、单条手动重试、批量重试和租户数据隔离。

## 多链并发系统测试

最终测试同时运行以下任务：

- TRON 本地链：TRX、USDC、USDT 完整资金流；
- NEAR sandbox：NEAR、USDC、USDT 完整资金流；
- Cardano Yaci devnet：ADA、USDC、USDT 完整资金流；
- 1000 用户、ETH/TRON/SOLANA/SUI 四链账务和 Webhook 并发负载。

结果：

| 指标 | 结果 |
|---|---:|
| 模拟用户 | 1000 |
| 并发线程 | 32 |
| Webhook worker | 12 |
| 充值扫描尝试 | 2000 |
| 实际入账充值 | 1000 |
| 确认提现 | 1000 |
| 最终送达 Webhook | 2000 |
| 自动重试 | 366 |
| 充值阶段耗时 | 57.174 秒 |
| 提现阶段耗时 | 17.502 秒 |
| Webhook 阶段耗时 | 92.626 秒 |
| 充值扫描速率 | 35 次/秒 |
| 提现确认速率 | 57 笔/秒 |
| Webhook 请求速率 | 26 次/秒 |

回调服务器会故意让部分首次请求失败。测试确认首个重试间隔至少为 29 秒，对应配置的 30 秒指数退避；随后把到期时间推进并完成全部重试。最终结果为：

- 1000 个用户各只收到一次充值入账；
- 1000 笔提现全部确认；
- 2000 条回调全部送达，签名错误数为 0；
- 信用流水 1000 笔、借记流水 1000 笔；
- 汇总可用余额等于充值总额减提现总额；
- 负余额、锁定残留、非终态提现和资金差额均为 0。

这些速率来自一台开发电脑同时进行四套 Maven 构建、三个本地链节点和数据库负载时的结果，只用于发现并发正确性问题，不应直接作为生产容量承诺。

## 可重复执行入口

```bash
# 查看完整链矩阵
scripts/regtest/all-chain-regtest.sh matrix

# 单链入口示例
scripts/regtest/all-chain-regtest.sh test-aptos
scripts/regtest/all-chain-regtest.sh test-evm
scripts/regtest/all-chain-regtest.sh test-hypercore

# 本机 PostgreSQL 18 托管数据库测试
scripts/regtest/run-custody-db-tests.sh

# 最终多链并发系统测试
scripts/regtest/run-multichain-system-test.sh
```

## 清理与审计

- 本轮创建的 TRON、NEAR、Cardano 节点均已停止并删除。
- `surprising_wallet_test_*` 临时逻辑数据库均已删除。
- 没有残留测试 Maven/Surefire 进程。
- 未提交私钥、助记词、真实 Token、RPC 密钥或生产配置。
- 测试失败时保留了错误日志；通过后临时构建目录和测试日志按脚本规则清理。
