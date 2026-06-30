# Surprising Wallet

交易所级多链钱包后端。当前运行时以 DB Asset Model 为中心：

- `chain_profile` 负责链路由与网络元数据
- `chain_asset` 负责链原生资产
- `token_config` 负责 token 运行时配置
- `ledger_balance` 负责用户与系统余额
- `contract_deployment_order` 负责 EVM/TRON/NEAR/Solana/Aptos/Sui/Polkadot Asset Hub/TON Jetton/NFT Collection 合约/资产部署订单、手续费、交易和回执状态

中文文档位于 [`docs/zh`](docs/zh)，英文文档位于 [`docs/en`](docs/en)。

[English README](README.md)

## 技术支持

如果遇到麻烦或需要技术支持，可以通过以下方式联系：

[![Email](https://img.shields.io/badge/Email-business%40tokdou.com-4285F4?style=flat-square&logo=google&logoColor=white)](mailto:business@tokdou.com)
[![Telegram](https://img.shields.io/badge/Telegram-%40SurprisingApp-26A5E4?style=flat-square&logo=telegram&logoColor=white)](https://t.me/SurprisingApp)

## 当前范围

支持的链族：

| 链族 | 链 | 运行模型 |
|---|---|---|
| Bitcoin-like UTXO | BTC, LTC, DOGE, BCH | UTXO、本地 regtest、2-of-3 签名 |
| EVM | Ethereum, BNB, Polygon, Arbitrum, Optimism, Base, Avalanche, HyperEVM, Mantle, Linea, Scroll, Unichain | 账户模型、ERC20、ERC20/ERC721 合约部署、Hardhat fork 测试 |
| TRON | TRON Nile/mainnet profile | 账户模型、TRC20、TRC20/TRC721 合约部署 |
| NEAR | NEAR testnet/mainnet profile | 隐式账户、NEP-141、NEP-141/NEP-171 合约部署 |
| Solana | SOLANA devnet/mainnet profile | SOL、SPL token、SPL Token/NFT mint 模板部署 |
| Aptos | APTOS testnet/mainnet profile | APT、Coin/FA 资源、Aptos Coin/单供应量资产 Move 模板部署 |
| Sui | SUI testnet/mainnet profile | Sui Coin 对象、Coin/NFT Move 模板部署、运行时 Sui CLI 编译 |
| Polkadot | DOT Westend/mainnet profile | DOT、Asset Hub assets、Asset Hub Token/Single Asset 部署 |
| TON | TON testnet/mainnet profile | TON、Jetton、Jetton/NFT Collection 部署、ton4j StateInit 消息 |

如果希望项目支持新的链，请提交 [GitHub Issue](https://github.com/lilaizhencn/surprising-wallet/issues)，并说明链名称、目标网络、原生资产、token 模型、可用 RPC/indexer，以及需要支持的充值、提现、归集、划转或合约部署流程。

## 快速启动

依赖环境：

- JDK 21
- Maven 3.8+
- PostgreSQL 14+
- Redis 6+
- Docker，用于 BTC/LTC/DOGE/BCH 本地 regtest
- Node.js 18+，用于 EVM fork 测试和 Polkadot runtime helper
- Aptos CLI，用于 Aptos Coin/单供应量资产 Move 模板编译和发布预览
- Sui CLI，用于 Sui Coin/NFT Move 模板编译和发布预览

创建数据库：

```bash
psql -U postgres -c "create user wallet with password 'wallet123';"
psql -U postgres -c "create database wallet owner wallet;"
psql -U postgres -d wallet -c "grant all on schema public to wallet;"
```

初始化全新测试库：

```bash
psql -U wallet -d wallet -f docs/db/surprising-wallet-init-pgsql.sql
```

初始化 SQL 已包含 `chain_profile`、`chain_rpc_node`、`wallet_system_config` 和 `wallet_public_key`。链开关、扫描起点、扫描批量、RPC 节点和 wallet-server 三个 public key 都从数据库读取。

部署前必须按环境提前确认已扫描区块高度。全新系统通常把 `chain_scan_height` 设置到当前最新安全块附近，再从后续新区块开始扫描；只有明确需要补历史充值时，才把扫描高度往前调。不要让新部署环境从创世块或很早的历史高度开始扫，否则会造成启动追块时间过长、RPC 配额被快速消耗，甚至遗漏真实测试窗口。

构建：

```bash
mvn clean install -DskipTests
```

从仓库根目录启动服务：

```bash
mvn -pl backendservices/wallet-sig1 -am spring-boot:run
mvn -pl backendservices/wallet-sig2 -am spring-boot:run
mvn -pl backendservices/wallet-parent/wallet-server -am spring-boot:run
```

关键运行密钥：

```bash
export SW_DB_PASSWORD='<PostgreSQL 密码>'
export SW_SIG1_MASTER_KEY='<第一签 BIP32 tprv>'
export SW_SIG2_MASTER_KEY='<第二签 BIP32 tprv>'
export SW_ED25519_SEED='<32 字节 Ed25519 seed，hex 或 base64>'
export SW_WALLET_ADMIN_USERNAME='<钱包后台配置账号>'
export SW_WALLET_ADMIN_PASSWORD='<钱包后台配置密码>'
```

生产环境中第三个 BIP32 私钥根应离线保存，wallet-server 只配置三组公钥。
wallet-server 三组公钥配置在 `wallet_public_key`，不是 YAML/env。

### 各链密钥材料使用

项目有意把各链收敛到少量根密钥材料：

| 密钥材料 | 存放位置 | 用途 |
|---|---|---|
| `wallet_public_key` slots 1/2/3 | PostgreSQL | wallet-server 启动必需的 BIP32 公钥根，用于确定性地址校验 |
| `SW_SIG1_MASTER_KEY` | 环境变量/KMS | 第一签在线服务使用的 BIP32 私钥根 |
| `SW_SIG2_MASTER_KEY` | 环境变量/KMS | 第二签，以及单签 account-chain 花费使用的 BIP32 私钥根 |
| `SW_ED25519_SEED` | 环境变量/KMS | Ed25519 类链统一使用的 master seed |
| XMR wallet-rpc 钱包 | monero-wallet-rpc 所在机器 | 独立的 Monero 钱包 seed/cache/password，不走 BIP32/Ed25519 |

| 链 | 地址生成来源 | 花费/签名来源 | 说明 |
|---|---|---|---|
| BTC、LTC、DOGE、BCH | `wallet_public_key` slots 1/2/3 | `SW_SIG1_MASTER_KEY` + `SW_SIG2_MASTER_KEY` | Bitcoin-like 2-of-3 流程；第 3 组私钥根离线保存用于恢复 |
| ETH、BNB、POLYGON、ARBITRUM、OPTIMISM、BASE、AVAX_C、HYPEREVM、MANTLE、LINEA、SCROLL、UNICHAIN | `wallet_public_key` slot 2 | `SW_SIG2_MASTER_KEY` | EVM secp256k1 地址；统一使用 BIP44 coin type 60 |
| TRON | `wallet_public_key` slot 2 | `SW_SIG2_MASTER_KEY` | secp256k1 key 转换为 TRON 地址和签名格式 |
| XRP | `wallet_public_key` slot 2 | `SW_SIG2_MASTER_KEY` | XRP Ledger secp256k1 classic address，BIP44 coin type 144 |
| HYPERCORE | `wallet_public_key` slot 2 | `SW_SIG2_MASTER_KEY` | Hyperliquid API 签名复用 EVM 风格 secp256k1 account root |
| SOLANA、TON、APTOS、SUI、ADA、DOT、NEAR | `SW_ED25519_SEED` | `SW_ED25519_SEED` | 同一个 Ed25519 root，按链使用独立 SLIP-0010 path；不存入 `wallet_public_key` |
| XMR | monero-wallet-rpc subaddress | monero-wallet-rpc 钱包 | 需要一起备份 Monero wallet seed、cache 文件和密码 |

## 合约部署

钱包用户可以在已启用的 EVM 系列链、TRON、NEAR、Solana、Aptos、Sui、Polkadot 和 TON 上部署合约或运行时资产。后端不接收任意 Solidity/Move/JS
源码，只开放固定的安全模板和参数：

面向用户的流程是：登录钱包后选择链和模板，获取独立的合约部署地址，用户自行向该地址充值原生 gas 币，预览后端生成的模板源码、构造参数和费用估算，二次确认后广播部署交易，最后在部署记录里查看状态、交易哈希、实际费用和合约地址。后端不接收用户提交的任意合约源码，只接收项目内固定模板允许的参数。

| 链族 | 页面模板 | 部署结果 | 额外运行要求 |
|---|---|---|---|
| EVM | ERC20、ERC721 | OpenZeppelin 风格 Solidity 合约 | 对应链已启用 EVM JSON-RPC |
| TRON | TRC20、TRC721 | TRON 兼容 Solidity 合约 | 已启用 Nile/mainnet RPC 和 fee limit |
| NEAR | NEP-141、NEP-171 | 部署到用户隐式账户的 Wasm 合约 | 已启用 NEAR RPC，Wasm 模板随项目打包 |
| Solana | SPL Token、SPL NFT mint | SPL mint 和 owner ATA | 已启用 Solana RPC |
| Aptos | Coin、单供应量资产 | 从部署地址发布 Move package | wallet-server 可执行 Aptos CLI |
| Sui | Coin、NFT Collection | 从部署地址发布 Move package | wallet-server 可执行 Sui CLI |
| Polkadot Asset Hub | Fungible asset、单供应量资产 | `pallet-assets` asset id 和 metadata | 本地 Polkadot runtime helper 加 Asset Hub WebSocket RPC |
| TON | Jetton、NFT Collection | 确定性的 StateInit 合约 | 已启用 TON RPC/API key |

- `TokDouERC20`：基于 OpenZeppelin 风格的 ERC20，支持 owner、cap、pause、
  burn、permit、decimals、initial supply、max supply 和可选 owner mint。
- `TokDouERC721`：基于 OpenZeppelin 风格的 ERC721，支持 owner、enumerable、
  URI storage、pause、burn、owner mint、base URI 和 max supply。
- `TokDouTRC20`：基于 OpenZeppelin 风格、面向 TRON 编译的 TRC20，支持
  owner、cap、pause、burn、permit、decimals、initial supply、max supply 和可选 owner mint。
- `TokDouTRC721`：基于 OpenZeppelin 风格、面向 TRON 编译的 TRC721，支持
  owner、enumerable、URI storage、pause、burn、owner mint、base URI 和 max supply。
- `TokDouNep141`：基于 near-sdk-js 的 NEP-141 Wasm 模板，支持 FT core、
  storage management、metadata 和 owner 初始供应量。
- `TokDouNep171`：基于 near-sdk-js 的 NEP-171 Wasm 模板，支持 NFT core、
  metadata、enumeration、approval 和 owner mint。
- `TokDouSplToken`：Solana 标准 SPL Token mint 创建流程，支持 mint 租金预留、
  owner ATA 创建、初始供应量、可选 owner mint authority，以及撤销 freeze authority。
- `TokDouSplNft`：Solana 单供应量 SPL mint 创建流程，decimals 固定为 0，
  token 发给 owner ATA，并撤销 mint/freeze authority；当前版本不写入 Metaplex metadata。
- `TokDouAptosCoin`：Aptos Move Coin<T> package，使用 `managed_coin`，支持 owner 初始供应量、
  可选 owner mint 和模块内 max supply 检查。
- `TokDouAptosNft`：Aptos 单供应量 Coin<T> 资产，decimals 固定为 0；当前版本不写入 Aptos Digital Asset metadata。
- `TokDouSuiCoin`：Sui Move Coin 模板，使用 Coin Registry，支持 owner 初始供应量、
  通过 `MintAuthority` 可选 owner mint，并在模块内约束 max supply。
- `TokDouSuiNft`：Sui Move NFT 模板，支持 Collection 对象、owner mint、base URI、
  mint 事件和 max supply。
- `TokDouAssetHubToken`：Polkadot Asset Hub `pallet-assets` fungible asset 创建流程，支持确定性 asset id、metadata、初始供应量和 issuer role。
- `TokDouAssetHubAsset`：Polkadot Asset Hub 单供应量资产，decimals 为 0、supply 为 1，当前版本不写入 NFT metadata。
- `TokDouJetton`：TON TEP-74 Jetton minter 部署流程，使用 ton4j，支持 off-chain metadata URI、owner 初始发行和 owner admin。
- `TokDouNftCollection`：TON TEP-62 NFT Collection 部署流程，使用 ton4j，支持 collection metadata URI、base item URI 和 owner admin。

合约部署地址和普通充值地址隔离。用户选择链后，wallet-server 使用原有确定性
派生规则生成地址，但写入 `wallet_role=CONTRACT_DEPLOYER`。用户自行给这个地址
充值原生 gas 币，扫描任务可以给它入账；普通提现、普通资产列表和归集继续只处理
`DEPOSIT` 地址。预览接口会校验参数、估算 EVM gas、应用 TRON `fee_limit`、为 NEAR 预留 gas 与 Wasm 存储质押、为 Solana 预留 mint/ATA 租金与签名费、编译 Aptos Move package 并预留 max gas，或编译 Sui Move 模板并预留固定 Sui publish gas budget；Polkadot 会检查 Asset Hub asset id 可用性并预留 DOT 运行时费用，TON 会检查确定性合约地址状态并预留 StateInit 部署余额，同时检查账本余额和链上余额。
Polkadot 部署使用 `services/polkadot-runtime-service` 和 `chain_rpc_node` 中 purpose=`asset_rpc` 的 Asset Hub WebSocket RPC，创建标准 `pallet-assets` 资产，不上传 Wasm 合约。部署成功后，交易、回执、手续费和状态写入 `contract_deployment_order`。
TON 部署使用 ton4j 的 Jetton 与 NFT Collection builder 创建确定性 StateInit 消息，不需要额外编译器。TON 原生币扫描会包含 `wallet_role=CONTRACT_DEPLOYER` 地址，方便用户先给部署地址充值 gas；Jetton 入账扫描仍然只处理普通 `DEPOSIT` 地址。

页面和接口建议按下面顺序调用：

| 步骤 | 接口 | 说明 |
|---|---|---|
| 加载可选项 | `GET /wallet/v1/app/contracts/templates` | 返回支持的链、模板、bytecode hash、功能点和风险提示 |
| 获取部署 gas 地址 | `POST /wallet/v1/app/contracts/deployer-address` | 请求体 `{ "chain": "BASE", "forceNew": false }`，返回地址、二维码、派生路径和角色 |
| 预览 | `POST /wallet/v1/app/contracts/preview` | 校验参数、渲染源码/构造参数、检查账本和链上余额，返回 `readyToDeploy` |
| 确认部署 | `POST /wallet/v1/app/contracts/deploy` | 请求体和预览一致，但必须带 `"confirmed": true`，广播前会冻结原生 gas 余额 |
| 查看记录 | `GET /wallet/v1/app/contracts/orders?limit=20` | 刷新待确认订单，返回 tx、合约地址、费用、状态和错误信息 |

ERC20 类请求示例：

```json
{
  "chain": "BASE",
  "templateType": "ERC20",
  "name": "TokDou Test USD",
  "symbol": "TUSDC",
  "decimals": 6,
  "initialSupply": "1000000",
  "maxSupply": "100000000",
  "mintable": true,
  "ownerAddress": "0x...",
  "confirmed": true
}
```

ERC721 类模板使用 `templateType: "ERC721"`，设置 `maxSupply`，在支持 metadata URI 的链上填写 `baseUri`。如果不传 `ownerAddress`，默认使用合约部署地址作为 owner。Aptos、Polkadot 和 TON 当前要求 owner 必须等于部署地址，这样发布包、资产 owner 或 StateInit admin 都由同一个派生密钥控制。

订单状态说明：

| 状态 | 含义 |
|---|---|
| `PREVIEW` | 仅预览接口返回，不写入订单表 |
| `WAITING_FOR_FUNDS` | 部署时无法冻结足够的原生 gas 余额 |
| `SIGNING` | 订单已创建，正在签名或广播 |
| `SENT` | 交易已广播，等待刷新确认 |
| `CONFIRMED` | 链上确认成功，已记录实际费用和合约地址 |
| `FAILED` | 校验、广播或链上执行失败；若失败发生在确认前，会释放未使用的锁定余额 |

部署出的合约不会自动写入 `token_config`，也不会自动进入钱包 token 列表。

运行边界：

- 部署 gas 由用户自行充值到 `CONTRACT_DEPLOYER` 地址，系统热钱包不会自动给部署地址补 gas。
- 合约部署地址使用单独的确定性 index 区间，并写入 `wallet_role=CONTRACT_DEPLOYER`，普通充值、提现和归集可以和 `DEPOSIT` 地址区分。
- 前端应先展示预览，再让用户二次确认。预览会返回生成源码、ABI、构造/初始化参数、费用估算、安全提示和警告。
- 固定模板包含 pause、mint、burn、cap、max supply 等 owner 能力时，页面需要明确展示，因为这些能力会影响用户对 token 的信任判断。
- 生产库只应启用已经确认 RPC、原生 gas 扫描、确认规则、CLI/helper 依赖都可用的链。

Sui 部署要求 wallet-server 所在机器可以执行 Sui CLI。默认命令为 `sui`；如果可执行文件不在 PATH，
用 `sw.wallet.contract.sui.cli` 指定路径，编译超时时间由 `sw.wallet.contract.sui.timeout-seconds` 控制。

Solana 部署复用现有 Solana RPC 和 `solanaj` SPL 指令，只创建标准 token mint，不接收任意 Solana program 源码，因此不需要额外编译工具链。

Aptos 部署要求 wallet-server 所在机器可以执行 Aptos CLI。默认命令为 `aptos`；如果可执行文件不在 PATH，
用 `sw.wallet.contract.aptos.cli` 指定路径，编译超时时间由 `sw.wallet.contract.aptos.timeout-seconds` 控制。

## TokDou 前端接入

TokDou 钱包页面只是本项目的演示/参考 UI。钱包后端本身不依赖这个前端即可运行；实际接入时，通常应根据自己的产品自行开发 Web、iOS、Android 或桌面应用，并调用本项目提供的钱包 API。

当前 `tokdou.com/wallet` 页面主要用于让人直观了解注册、登录、充值地址、资产、提现、划转、测试币和合约部署等流程。它不是主网 RPC 的生产钱包页面，应视为 regtest/test 网络演示环境。

`~/Desktop/surprising/tokdou` 的钱包页面演示接入配置：

| 场景 | API Base |
|---|---|
| `npm run dev` | `http://localhost:8002` |
| build/部署 | `https://api.tokdou.com` |
| 临时覆盖 | `VITE_WALLET_API_BASE=https://... npm run dev` |

新增后端接口：

| 接口 | 用途 |
|---|---|
| `GET /wallet/v1/dashboard` | 钱包项目总览、总开关、链配置、RPC、token、地址、余额、交易记录、架构/测试/DB 文档 |
| `GET /wallet/v1/dashboard/address-transactions` | 查询地址充值/提现/归集记录 |
| `POST /wallet/v1/admin/login` | 钱包后台配置登录，HTTP Basic Auth |
| `GET /wallet/v1/admin/config` | 查询可管理配置表 |
| `PATCH /wallet/v1/admin/config/{table}/{id}` | 白名单字段更新配置 |
| `GET /wallet/v1/app/contracts/templates` | 查询支持部署的 EVM/TRON/NEAR/Solana/Aptos/Sui/Polkadot/TON 链和 ERC20/ERC721/TRC20/TRC721/NEP-141/NEP-171/SPL Token/SPL NFT/Aptos Coin/Aptos Asset/Sui Coin/Sui NFT/Asset Hub/Jetton/NFT Collection 模板 |
| `POST /wallet/v1/app/contracts/deployer-address` | 获取或生成用户合约部署地址 |
| `POST /wallet/v1/app/contracts/preview` | 校验合约参数并估算部署 gas |
| `POST /wallet/v1/app/contracts/deploy` | 签名并广播合约创建交易 |
| `GET /wallet/v1/app/contracts/orders` | 查询用户合约部署记录 |

后台配置账号密码来自 `SW_WALLET_ADMIN_USERNAME`、`SW_WALLET_ADMIN_PASSWORD`，生产环境必须覆盖默认值。

## 测试环境

查看测试矩阵：

```bash
scripts/regtest/all-chain-regtest.sh matrix
```

运行账户链 DB-only 测试：

```bash
scripts/regtest/all-chain-regtest.sh test-db
```

运行 BTC/LTC/DOGE/BCH 本地 regtest：

```bash
scripts/regtest/all-chain-regtest.sh init
scripts/regtest/all-chain-regtest.sh test-utxo
```

运行 EVM fork 测试：

```bash
cd evm-fork
npm install
cd ..
scripts/regtest/all-chain-regtest.sh test-evm
```

BNB 与 Polygon 的公共 fork RPC 不再作为脚本默认值。若必须覆盖这两条链，需要配置经过验证、支持当前区块 fork 的私有或 archive-capable RPC，例如：

```bash
BNB_RPC_URL='https://...' POLYGON_RPC_URL='https://...' REQUIRE_ALL_EVM_FORKS=true scripts/regtest/all-chain-regtest.sh test-evm
```

外部 live/devnet 花费测试需要测试钱包有余额，并依赖 RPC/faucet 可用：

```bash
RUN_LIVE=true RUN_LIVE_SPENDING=true scripts/regtest/all-chain-regtest.sh test-all
```

## 文档

| 主题 | 英文 | 中文 |
|---|---|---|
| 文档索引 | [docs/README.md](docs/README.md) | [docs/README_CN.md](docs/README_CN.md) |
| 启动与测试 | [docs/en/startup-and-testing.md](docs/en/startup-and-testing.md) | [docs/zh/startup-and-testing.md](docs/zh/startup-and-testing.md) |
| 数据库 | [docs/en/database.md](docs/en/database.md) | [docs/zh/database.md](docs/zh/database.md) |
| 架构 | [docs/en/architecture.md](docs/en/architecture.md) | [docs/zh/architecture.md](docs/zh/architecture.md) |
| 运行流程 | [docs/en/system-code-flow.md](docs/en/system-code-flow.md) | [docs/zh/system-code-flow.md](docs/zh/system-code-flow.md) |
| Regtest 脚本 | [docs/en/scripts-and-regtest.md](docs/en/scripts-and-regtest.md) | [docs/zh/scripts-and-regtest.md](docs/zh/scripts-and-regtest.md) |
| EVM fork | [docs/en/evm-fork-testing.md](docs/en/evm-fork-testing.md) | [docs/zh/evm-fork-testing.md](docs/zh/evm-fork-testing.md) |
| 基础设施 | [docs/en/infra.md](docs/en/infra.md) | [docs/zh/infra.md](docs/zh/infra.md) |

## 仓库结构

```text
backendservices/                 Java 后端服务
currency-sdks/                   链 SDK 与钱包公共库
docs/                            文档、SQL schema、文档图片
docs/db/                         数据库初始化脚本与历史备份
evm-fork/                        Hardhat fork 运行环境，因脚本/测试引用保留在根目录
infra/                           Docker 与 mock coin 基础设施
scripts/                         Regtest 与测试辅助脚本，因测试引用保留在根目录
tools/contract-compiler/          Solidity 固定模板编译工作区
```
