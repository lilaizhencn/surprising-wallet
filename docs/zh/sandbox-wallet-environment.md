# 非主网沙盒钱包运行方案

本文描述 TokDou 钱包在非主网环境下的目标运行形态。目标不是只验证地址生成，而是让注册用户可以完成接近真实交易所钱包的完整闭环：

1. 用户注册并登录钱包。
2. 用户获得链上充值地址。
3. 用户点击领取测试币。
4. 系统从沙盒水龙头钱包向用户地址发起链上转账。
5. 扫链任务发现充值并入账。
6. 用户发起提现，提现地址仅允许本项目内地址。
7. 定时任务执行冻结、双签、广播和确认。
8. 归集任务把用户地址上的链上余额归集到系统热钱包。
9. 站内划转只允许系统内注册用户之间发生。

## 环境原则

- 不连接主网资产，不使用主网私钥。
- 余额入账必须来自扫链，不允许领取测试币接口直接改用户余额。
- 提现必须经过现有冻结、签名、广播、确认流程。
- 归集不影响用户账面余额，只移动链上 UTXO 或账户余额到系统热钱包。
- 所有链上 RPC 仅允许内网访问或受信任服务访问。
- 这套环境用于 test2/sandbox，不作为生产主网环境。

## 推荐机器分工

| 机器 | 建议规格 | 职责 |
|---|---:|---|
| 钱包服务机 | 现有 Windows 机器 | `wallet-server`、`wallet-sig1`、`wallet-sig2` |
| 数据机 | 现有 Linux 机器 | PostgreSQL、Redis |
| 沙盒链机器 | 2C4G，系统盘 40G-80G，建议 4G-8G swap | 本地 regtest/dev 链、自动挖块、水龙头辅助进程 |

2C2G 可以短期验证，但同时跑 BTC/LTC/DOGE/BCH regtest 和多条 EVM 本地链会很紧。建议直接使用 2C4G。

## 链运行矩阵

### 必须本地运行的链

| 链 | 环境 | 原因 |
|---|---|---|
| BTC | regtest | 可控发币、挖块、确认数、UTXO 归集 |
| LTC | regtest | 可控发币、挖块、确认数、UTXO 归集 |
| DOGE | regtest | 可控发币、挖块、确认数、UTXO 归集 |
| BCH | regtest | 可控发币、挖块、确认数、UTXO 归集 |

项目已有脚本：

```bash
scripts/regtest/all-chain-regtest.sh init
scripts/regtest/all-chain-regtest.sh status
scripts/regtest/bitcoinlike-regtest.sh mine 1 btc ltc doge bch
```

长期运行时需要把 regtest RPC 从仅本机访问调整为沙盒链机器内网可访问，并通过安全组只允许钱包服务机访问。

建议端口：

| 链 | RPC 地址 |
|---|---|
| BTC | `http://<sandbox-chain-private-ip>:18444` |
| LTC | `http://<sandbox-chain-private-ip>:19443` |
| DOGE | `http://<sandbox-chain-private-ip>:22555` |
| BCH | `http://<sandbox-chain-private-ip>:18443` |

### 建议本地运行的 EVM 沙盒链

EVM 不建议长期使用项目里的 Hardhat fork 测试脚本，因为该脚本是一条链一条链地临时启动，适合自动化测试，不适合长期钱包服务。

沙盒环境建议使用 Anvil 或长期 Hardhat node，为每条 EVM 链启动一个本地 RPC，并使用测试网 chainId 以匹配现有配置：

| 链 | 本地端口 | chainId | 原生币 |
|---|---:|---:|---|
| ETH | 18545 | 11155111 | ETH |
| BNB | 18546 | 97 | BNB |
| POLYGON | 18547 | 80002 | MATIC |
| ARBITRUM | 18548 | 421614 | ETH_ARB |
| OPTIMISM | 18549 | 11155420 | ETH_OP |
| BASE | 18550 | 84532 | ETH_BASE |
| AVAX_C | 18551 | 43113 | AVAX_C |

每条本地 EVM 链需要部署 mock ERC20：

- USDT，6 位精度
- USDC，6 位精度

部署完成后，把合约地址写入 `token_config` 和 `chain_asset`。水龙头钱包持有原生币和 mock token，用户点击领取后通过真实链上交易转给用户充值地址，再由扫链任务入账。

### 不建议本地启动节点的链

以下链先使用官方或公共测试网/devnet。它们本地节点成本高，或者当前项目没有本地 regtest 节点脚本。

| 链 | 环境 | 说明 |
|---|---|---|
| TRON | Nile | 需要 fullnode、solidity、event/indexer RPC |
| SOLANA | devnet | 需要测试 SOL 和 SPL mint |
| TON | testnet | 需要 TON Center/TonAPI，建议配置 API key |
| APTOS | testnet | 使用 Aptos Labs fullnode；没有程序化 faucet，需要预充测试币 |
| SUI | testnet | 需要 fullnode 和 faucet |

这些链也必须走真实链上交易。水龙头钱包需要提前准备测试币或由测试网 faucet 补充余额。

## 数据库配置

沙盒环境的默认数据库配置直接收敛到唯一初始化 SQL：

```text
docs/db/surprising-wallet-init-pgsql.sql
```

项目不再新增独立 SQL 升级或 cutover 文件。该 init SQL 负责：

1. 开启全局开关：
   - `global.all.enabled`
   - `global.scan.enabled`
   - `global.withdraw.enabled`
   - `global.collection.enabled`
   - `global.transfer.enabled`
2. BTC/LTC/DOGE/BCH：
   - 关闭 testnet/mainnet profile。
   - 开启 regtest profile。
   - 设置 regtest RPC 到沙盒链机器内网地址。
3. EVM：
   - 开启本地 dev profile。
   - 设置本地 RPC、chainId、gas policy。
   - 写入 mock USDT/USDC 合约地址。
4. TRON/SOLANA/TON/APTOS/SUI：
   - 开启测试网/devnet profile。
   - 设置 RPC、faucet、indexer。
5. 初始化或校验系统热钱包地址：
   - `user_id=0`
   - `biz=0`
   - `address_index=0`
   - `wallet_role=HOT`

所有链的充值、提现、归集和划转能力都以 `chain_profile` 和 `wallet_system_config` 为准。

## 后端新增能力

### 领取测试币

新增水龙头请求表：

```text
wallet_faucet_request
```

建议字段：

| 字段 | 说明 |
|---|---|
| `id` | 主键 |
| `user_id` | 领取用户 |
| `chain` | 链 |
| `asset_symbol` | 币种 |
| `amount` | 领取数量 |
| `to_address` | 用户充值地址 |
| `status` | `PENDING/SENT/CONFIRMED/FAILED` |
| `tx_hash` | 链上交易 |
| `error_message` | 失败原因 |
| `created_at/updated_at` | 时间 |

新增接口：

```http
POST /wallet/v1/app/faucet/claim
```

请求参数：

```json
{
  "chain": "BTC",
  "assetSymbol": "BTC",
  "addressIndex": 0
}
```

处理规则：

- 必须登录。
- 必须是沙盒/test2 环境。
- 必须先存在用户充值地址。
- 同一用户、同一链、同一币种要有限频。
- 接口只创建或触发链上发币，不直接增加 `ledger_balance`。
- 入账必须等待扫链任务发现交易。

### 提现地址限制

沙盒环境提现地址只允许本项目地址：

- 地址必须存在于 `chain_address`。
- 地址必须属于当前链和当前币种。
- 允许用户提现到自己的其他地址，也允许提现到其他注册用户地址。
- 不允许提现到外部未知地址。

该限制只用于 sandbox/test2。未来主网生产环境可以通过单独开关决定是否允许外部地址。

### 站内划转限制

站内划转只允许系统内注册用户：

- 前端输入邮箱或用户 ID。
- 后端解析为 `wallet_user.id`。
- 不允许输入链上地址进行站内划转。
- 账本扣加必须在同一个数据库事务中完成。

### 自动挖块

BTC/LTC/DOGE/BCH regtest 必须自动挖块，否则确认数不会增长。

建议新增一个轻量定时任务或系统服务：

```bash
scripts/regtest/bitcoinlike-regtest.sh mine 1 btc ltc doge bch
```

频率建议：

| 场景 | 频率 |
|---|---|
| 开发联调 | 10 秒 1 次 |
| 稳定沙盒 | 30 秒 1 次 |
| 压测 | 根据测试脚本临时提高 |

### EVM 本地水龙头

EVM 本地链启动后需要：

1. 预置 faucet private key。
2. faucet 地址持有原生币。
3. 部署 USDT/USDC mock token。
4. 给 faucet 地址 mint 足够 token。
5. 用户领取时发起原生币或 ERC20 转账。
6. 扫链后入账。

### 外部测试网水龙头

TRON/SOLANA/TON/APTOS/SUI 使用测试网时：

- 水龙头钱包必须提前有测试币。
- 如果链官方 faucet 不稳定，后台只记录 `FAILED`，不直接补账。
- token 领取必须确认测试网 token 合约/mint/object 已配置。

## 前端新增能力

登录后的钱包页增加：

1. 资产列表每个币种展示链信息。
2. 每个币种按钮：
   - 充值
   - 提现
   - 划转
   - 领取测试币
3. 领取测试币弹窗：
   - 选择链。
   - 选择币种。
   - 展示目标充值地址。
   - 展示领取限制和等待扫链提示。
4. 提现页：
   - 地址输入时校验是否为本项目地址。
   - 二次确认。
   - 明确提示 sandbox 只允许项目内地址。
5. 划转页：
   - 只允许输入注册邮箱或用户 ID。
   - 不允许输入链上地址。

## 完整测试流程

### 充值和入账

1. 用户 A 注册并登录。
2. 用户 A 获取 BTC 充值地址。
3. 用户 A 点击领取 BTC。
4. 系统从 BTC regtest funder 发币到用户 A 地址。
5. 自动挖块任务产生确认。
6. BTC 扫链任务发现充值。
7. 用户 A BTC 账面余额增加。

### 提现

1. 用户 B 注册并登录。
2. 用户 B 获取 BTC 地址。
3. 用户 A 提现 BTC 到用户 B 的 BTC 地址。
4. 后端校验提现地址存在于 `chain_address`。
5. 系统冻结用户 A 可用余额。
6. 签名服务完成双签。
7. 广播任务提交链上交易。
8. 自动挖块产生确认。
9. 扫链确认提现状态。
10. 用户 B 地址收到链上充值后由扫链入账。

### 归集

1. 用户 A/B 地址存在可归集余额。
2. 归集任务根据链和阈值生成归集交易。
3. 签名服务完成签名。
4. 广播到链上。
5. 扫链确认。
6. 热钱包地址链上余额增加。
7. 用户账面余额不因归集减少。

### 站内划转

1. 用户 A 输入用户 B 邮箱。
2. 后端解析用户 B。
3. 同一事务扣减用户 A、增加用户 B。
4. 不产生链上交易。

## 分阶段落地

### 第一阶段：UTXO 沙盒闭环

- 跑 BTC/LTC/DOGE/BCH regtest。
- 配置自动挖块。
- 新增领取测试币接口。
- 新增提现地址白名单校验。
- 跑通 BTC/LTC/DOGE/BCH 的领取、扫链、提现、归集。

### 第二阶段：EVM 本地沙盒

- 为 ETH/BNB/POLYGON/ARBITRUM/OPTIMISM/BASE/AVAX_C 启动本地 RPC。
- 部署 mock USDT/USDC。
- 配置 token 地址。
- 跑通原生币和 ERC20 的领取、扫链、提现、归集。

### 第三阶段：外部测试网账户链

- 配置 TRON/SOLANA/TON/APTOS/SUI 测试网 RPC。
- 准备测试网水龙头钱包。
- 跑通原生币领取、扫链、提现。
- 再逐步补 token/JETTON/SPL 等测试资产。

### 第四阶段：前端体验完善

- 资产列表增加领取测试币。
- 提现地址实时校验。
- 划转只允许注册用户。
- 增加沙盒环境提示。

## 验收标准

沙盒环境完成后，至少满足：

- 新用户可以注册、登录。
- 新用户可以生成每条开放链的充值地址。
- 用户领取测试币后，必须由扫链入账。
- 用户不能提现到系统外地址。
- 用户可以提现到其他注册用户的链上地址。
- 提现必须经过冻结、签名、广播、确认。
- 归集任务可以把链上余额归集到系统热钱包。
- 站内划转只允许注册用户之间发生。
- 关闭某条链开关后，前端不能继续对该链执行充值/提现/领取。
