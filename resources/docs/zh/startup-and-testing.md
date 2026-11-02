# 启动与测试教程


本文说明如何配置项目、启动本地服务，以及如何运行各类测试环境。

## 1. 环境依赖

需要安装：

- JDK 27
- Maven 3.8+
- PostgreSQL 17 及以上；本地集成测试使用现有 PostgreSQL 18
- PGMQ 扩展，须支持 headers、read_ct、archive 和 set_vt（Supabase 当前项目提供的 1.5.1 包含这些接口）
- Docker，用于 BTC/LTC/DOGE/BCH 本地 regtest 节点
- Node.js 20.19+，用于 Console 和 EVM fork 工具

检查版本：

```bash
java -version
mvn -version
psql --version
psql -h 127.0.0.1 -p 5432 -d postgres -c "SELECT name, default_version FROM pg_available_extensions WHERE name = 'pgmq';"
docker --version
node --version
npm --version
```

## 2. 数据库初始化

创建数据库和用户：

```bash
psql -U postgres -c "create user wallet with password 'wallet123';"
psql -U postgres -c "create database wallet owner wallet;"
psql -U postgres -d wallet -c "grant all on schema public to wallet;"
```

用唯一初始化文件初始化全新本地测试库：

```bash
psql -U wallet -d wallet -f resources/docs/db/surprising-wallet-init-pgsql.sql
```

注意：

- `resources/docs/db/surprising-wallet-init-pgsql.sql` 是唯一 DB 初始化文件，从当前本地 DB schema 导出，并包含静态链/token 配置种子数据。
- 它只用于全新本地库，包含重置表的行为。
- 不要在生产或共享环境执行 destructive SQL。

## 3. PGMQ

在自建 PostgreSQL 主机安装扩展文件，再执行唯一初始化 SQL；Supabase 等托管实例通过可用扩展安装，不执行主机安装命令。无需额外消息服务。

```bash
git clone --depth 1 --branch v1.11.1 https://github.com/pgmq/pgmq.git /tmp/wallet-pgmq
make -C /tmp/wallet-pgmq/pgmq-extension
make -C /tmp/wallet-pgmq/pgmq-extension install
```

三个应用通过 `SW_DB_URL` 连接同一个数据库。签名服务使用独立数据库账号，只授予对应 PGMQ 队列权限。

## 4. 构建

在仓库根目录执行：

```bash
mvn clean install -DskipTests
```

快速编译检查：

```bash
mvn -pl wallet-api -am test -DskipTests
```

## 5. 密钥与运行配置

只有 wallet-api 产出可执行 Java JAR，负责 API 与分类业务调度。三个独立原生持有者运行 cb-mpc；XMR 使用 Monero 原生多方协议。Java 只处理公钥、固定摘要和返回结果，不配置钱包 Seed 或本地钱包私钥。

API 必须配置 `SW_MPC_CA_FILE`、`SW_MPC_CLIENT_CERTIFICATE_FILE`、`SW_MPC_CLIENT_PRIVATE_KEY_FILE`，以及三组 `SW_MPC_NODE_{1,2,3}_ID` / `_URL`。ID 为节点证书的 SHA256 SPKI，URL 为 HTTPS origin。传输私钥用于 mTLS 身份认证；每个持有者的签名份额与存储加密密钥分别保管。完整节点配置与恢复边界见[独立签名节点运行说明](../../infra/mpc-signer/OPERATIONS.md)。

```bash
mvn -pl wallet-api -am clean package
java -jar wallet-api/target/wallet-api-1.0.0-SNAPSHOT.jar
```

持有者不连接钱包数据库或 PGMQ。API 数据库连接数可通过 `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` 调整；各持有者使用自己的配置、操作系统身份和存储目录。

wallet-api 常用环境变量：

```bash
export SW_HTTP_PORT='8002'
export SW_DB_URL='jdbc:postgresql://127.0.0.1:5432/wallet'
export SW_DB_USERNAME='wallet'
export SW_DB_PASSWORD='<PostgreSQL 密码>'
export SW_APP_ENV='dev'
export SW_WALLET_ADMIN_USERNAME='<钱包后台配置账号>'
export SW_WALLET_ADMIN_PASSWORD='<钱包后台配置密码>'
export SW_CUSTODY_SECRET_MASTER_KEY='<32 字节 Base64 或 64 位十六进制密钥>'
export SW_CUSTODY_PLATFORM_ADMIN_EMAIL='<初始平台管理员邮箱>'
export SW_CUSTODY_PLATFORM_ADMIN_PASSWORD='<初始平台管理员密码>'
export SW_CUSTODY_CORS_ORIGINS='https://console.example.com'
```

`SW_CUSTODY_SECRET_MASTER_KEY` 为必填项，用于加密保存 API/Webhook Secret。只有数据库中
不存在平台管理员时才会引导创建；以后修改环境变量密码不会覆盖已有账户。

链运行配置不再通过 YAML/env 配置：

| 配置 | 来源 |
|---|---|
| 全局扫描/提现/归集/划转开关 | `wallet_system_config` |
| 单链扫描/提现/归集/划转开关 | `chain_profile.scan_enabled/withdraw_enabled/collection_enabled/transfer_enabled` |
| 单链扫描起始高度与单轮扫描上限 | `chain_profile.scan_start_height/scan_max_blocks_per_run` |
| 单链扫描批量 | `chain_profile.scan_batch_size` |
| 链网络、确认数、链 ID、gas policy | `chain_profile` |
| RPC/fullnode/indexer/faucet 节点 | `chain_rpc_node` |
| MPC 通信身份与节点 | 环境变量注入 `sw.wallet.mpc` |
| 租户钱包地址与联合公钥 | `chain_address.mpc_key_id` 与同租户 `wallet_mpc_key` / `wallet_mpc_key_epoch` |

部署前必须按环境提前确认 scanner checkpoint。全新系统通常把 `chain_scan_height.best_height/safe_height` 设置到当前最新安全块附近，让服务只扫描部署后的新区块；如果要补历史充值，再按业务窗口把高度往前调。不要从创世块或很早的历史高度开始扫，这会让服务长时间追块并消耗大量 RPC 配额。

当前运行时代码已接入 `global.all.enabled`、扫描、提现和归集开关。`transfer_enabled` 保留给后续内部划转入口；新增划转入口时必须调用 `WalletRuntimeConfigService.requireTaskEnabled(chain, TASK_TRANSFER, ...)`，不要把该开关套用到创建地址或提现流程。

TokDou 钱包页面读取 wallet-api：

| 场景 | API Base |
|---|---|
| `npm run dev` | `http://localhost:8002` |
| build/部署 | `https://api.tokdou.com` |
| 临时覆盖 | `VITE_WALLET_API_BASE=https://... npm run dev` |

## 6. 应用配置

主要配置文件：

| 文件 | 用途 |
|---|---|
| `wallet-api/src/main/resources/application.yaml` | wallet-api 唯一配置，包含数据库、PostgreSQL / PGMQ、密钥、调度及业务参数 |

项目不再使用 `application-{profile}.yaml`。每个属性旁均有用途和配置说明；修改运行环境时更新环境变量并重启对应进程，持有者身份与已发布密钥的参与方必须一致。

本地必配项：

- PostgreSQL URL、用户名、密码
- `chain_profile` 中每条启用链只能启用一个 network
- 启用链至少有一个匹配当前 `sw.app.env.name` 的 `chain_rpc_node`
- 启用的 `chain_rpc_node` 必须配置真实 RPC URL 和认证信息；启动时会拒绝 `CHANGE_ME`、`YOUR_*`、`REPLACE_ME` 等占位符
- `sw.wallet.mpc` 已配置三名持有者身份、HTTPS 地址与 mTLS 文件
- 租户地址通过 DKG 发布并绑定原 MPC 密钥；恢复时保留原密钥、版本与节点身份
- 钱包后台配置页使用的 `SW_WALLET_ADMIN_USERNAME`、`SW_WALLET_ADMIN_PASSWORD`

启动校验会打印每条链的网络、任务开关、扫描起点、扫描批量和 RPC 节点数量，并检查三名 MPC 持有者与客户端配置。配置不完整，或启用的 RPC 节点仍包含占位符 URL 或认证信息，都会直接启动失败。节点暂时不可达时，原请求按持久化事实恢复，不能换钥或临时使用本地私钥签名。

## 7. 启动服务

分别启动三个配置好的原生持有者：

```bash
/absolute/path/wallet-mpc-signer /absolute/path/node-config.json
```

随后从仓库根目录启动 API：

```bash
mvn -pl wallet-api -am spring-boot:run
```

默认端口：

| 服务 | 端口 |
|---|---:|
| `wallet-api` | 8002 |
| 原生 MPC 持有者 | 由各自 `node-config.json` 配置，限制网络访问 |

## 8. 测试矩阵

查看支持的测试环境：

```bash
resources/scripts/regtest/all-chain-regtest.sh matrix
```

覆盖范围：

| 命令 | 覆盖 |
|---|---|
| `test-db` | SOL/TON/APTOS/SUI/DOGE 和 UTXO 运行状态的 DB-only scanner/ledger/flow 测试 |
| `test-utxo` | BTC/LTC/DOGE/BCH 本地 regtest、并发、广播测试 |
| `test-xmr` | XMR 本地 wallet-rpc regtest 充值、提现、归集和幂等测试 |
| `test-evm` | 核心 EVM 链的 fork 测试；HyperEVM、Linea、Scroll、Unichain 等新增 EVM 链通过 DB profile 复用共享 EVM 路径 |
| `test-live` | 外部 testnet 连通性测试，以及可选花费测试 |
| `test-all` | UTXO、EVM、DB 测试，以及可选 live 测试 |

当前自动化测试的边界：

- `test-utxo` 是本地 BTC/LTC/DOGE/BCH regtest 的相邻业务回归入口；MPC 实际签名须另运行独立三方节点探针。
- `test-xmr` 会启动 XMR regtest wallet-rpc，创建真实子地址，充值并扫描入账，广播项目内提现，验证扫描幂等，并确认归集流程。
- `test-evm` 会在 Hardhat fork 中模拟 EVM 原生币/ERC20 充值、提现、归集和确认流程。
- `test-live` 默认验证外部 testnet/devnet 连通性，不能替代 MPC 实际花费验收。
- MPC 的正式业务、EIP-7702、NEAR 自动注册、XMR、份额刷新和完整 JAR 无 Seed 启动入口见[原生服务验收清单](../../infra/mpc-signer/README.md)。必须分别核对实际节点签名、广播、入账与恢复证明；本机冷备份恢复已通过，Linux 发布产物验证也已通过，当前分支最终 28/28 链组矩阵已通过，验收范围为本地链/测试网。

## 9. 运行 DB-only 测试

```bash
resources/scripts/regtest/all-chain-regtest.sh test-db
```

这些测试不需要本地区块链节点，但需要本地 PostgreSQL。

使用本机 PostgreSQL 18 中自动创建并清理的临时数据库，验证托管充值投影和事务回滚边界：

```bash
resources/scripts/regtest/run-custody-db-tests.sh
```

脚本不会启动独立数据库实例，只会复用 `127.0.0.1:5432`，且拒绝 PostgreSQL 18
以外的服务。测试会创建并回滚自己的租户和地址数据。它会验证已禁用的托管地址收到
确认充值后，租户充值投影、托管账本和持久化事件仍在同一事务中更新；
任一观察器失败时，原始充值记录和余额也会一起回滚。

## 10. 运行本地 UTXO 和 XMR Regtest

启动本地节点：

```bash
resources/scripts/regtest/all-chain-regtest.sh init
resources/scripts/regtest/all-chain-regtest.sh status
```

运行完整本地 UTXO 流程：

```bash
resources/scripts/regtest/all-chain-regtest.sh test-utxo
```

运行 XMR wallet-rpc 流程：

```bash
resources/scripts/regtest/all-chain-regtest.sh test-xmr
```

调整广播压力：

```bash
BITCOINLIKE_BROADCAST_DEPOSITS=80 \
BITCOINLIKE_BROADCAST_WITHDRAWALS=40 \
resources/scripts/regtest/all-chain-regtest.sh test-utxo
```

停止或重置节点：

```bash
resources/scripts/regtest/all-chain-regtest.sh stop
resources/scripts/regtest/all-chain-regtest.sh reset
```

## 11. 运行 EVM Fork 测试

安装依赖：

```bash
cd resources/infra/evm-fork
npm install
cd ..
```

运行：

```bash
resources/scripts/regtest/all-chain-regtest.sh test-evm
```

只跑部分链：

```bash
CHAIN_FILTER=ETH,BASE resources/scripts/regtest/all-chain-regtest.sh test-evm
```

## 12. 运行外部 Live 测试

只跑连通性：

```bash
RUN_LIVE=true resources/scripts/regtest/all-chain-regtest.sh test-all
```

连通性加花费测试：

```bash
RUN_LIVE=true RUN_LIVE_SPENDING=true resources/scripts/regtest/all-chain-regtest.sh test-all
```

live 花费测试需要测试钱包有余额，并且 RPC/faucet 可用：

- TRON Nile 需要测试 TRX/TRC20 余额。
- SOL devnet 如果 `requestAirdrop` 限流，需要手动充值。
- TON full flow 要求派生 owner 地址至少有 1 testnet TON。
- Aptos testnet 使用 Aptos Labs fullnode；testnet 没有程序化 faucet，需要提前给系统 faucet 或热钱包充值测试币。
- Sui testnet faucet 有限流，token 测试还需要 `SUI_MOCK_COIN_TYPE`。

## 13. 常见问题

PostgreSQL 权限错误：

```bash
psql -U postgres -d wallet -c "grant all on schema public to wallet;"
```

指定测试不存在导致失败：

```bash
mvn ... -Dsurefire.failIfNoSpecifiedTests=false
```

EVM fork 端口被占用：

```bash
lsof -ti tcp:8545 | xargs kill
```

外部 RPC 限流：

- 通过 `chain_rpc_node.rpc_url` 或 `api_key` 切换私有 RPC。
- 等待 faucet/RPC 冷却后重试。
- CI 优先使用 DB-only 测试。

## PGMQ 队列运行与验证

wallet-api 使用 `SW_DB_URL` 与 `SW_DB_USERNAME/SW_DB_PASSWORD`。原生持有者没有数据库账号。
由 DBA 创建应用 LOGIN 角色并安全配置密码；初始化基线由数据库所有者执行。
下面是 API 的队列授权，业务表授权按独立部署策略配置：

```sql
-- wallet-api role; business-table grants follow the existing application deployment policy.
GRANT USAGE ON SCHEMA pgmq TO wallet;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA pgmq TO wallet;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA pgmq TO wallet;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA pgmq TO wallet;
GRANT SELECT, INSERT, UPDATE ON public.chain_fee_rate TO wallet;
```

使用普通持久化队列。队列包含 `wallet_mpc_sign`、`wallet_sign_done`、
`wallet_withdraw`、`wallet_rbf`、`wallet_deposit_event`、`wallet_withdraw_event`，每个队列都有对应 `_dead` 死信队列。
队列 JSON headers 必须携带 `tenant_id`。外部业务消费充值/提现事件时，也必须按租户限定查询条件和数据库权限；
不要向租户应用开放平台队列账号。签名请求和结果使用有类型的协议携带 tenantId 与 requestId；消费者校验租户 header，API 广播端同时校验数据库请求 UUID。签名服务不接收或修改数据库状态及 RBF 历史。

`wallet_deposit_event` 使用 `UtxoDepositNotification(tenantId, chain, network, assetSymbol, output)`；output 包含独立的 `txHash`、int `vout`、地址、金额、区块高度/哈希和确认数。它是可重复的链上观察通知；实际入账以托管事件和账本为准，不使用旧 currency 编号或拼接 outpoint 的 DTO。

消费先以 300 秒可见性超时领取，再开启处理事务并锁定该消息，事务提交时同时归档消息与投递下一阶段。
处理中的行锁会阻止其他消费者重复领取；崩溃会释放锁并回滚，消息超时后恢复。领取后尚未开始处理就已超时的旧消费者不能确认消息。
RPC 不属于数据库事务，交易广播仍用交易 ID 幂等和 BROADCAST_UNKNOWN 对账。不要因数据库回滚重新构造另一笔提现交易。
失败按指数退避（最大 900 秒），第 20 次失败进入死信；归档和死信保留租户 headers，日志仅记录消息 ID、次数和异常类型。

数据库所有者或单独授权的运维账号可在确认业务状态后重放死信：

```sql
SELECT public.wallet_replay_dead('wallet_mpc_sign', 123, 'Verified original MPC request after repair');
```

同一死信 ID 重复执行不会重复入队；原消息归档，新消息记录原因、数据库操作者、时间和原死信 ID。
成功归档不自动回放。通过 `pgmq.metrics_all()` 监控队列数量和消息年龄，并按审计保留策略清理归档。

验证只使用本机现有 PostgreSQL 18，测试自动创建并删除隔离数据库：

```bash
SW_TEST_PGMQ=true mvn -pl common -am test -Dtest=PgmqIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
```

部署时先安装指定扩展并初始化新的数据库，再配置 API 和三个独立持有者；不要在有资金数据的库运行初始化基线。
程序回滚保留最新数据库、队列、份额与协议记录，只替换能识别当前 REFRESH fence/epoch 的兼容程序。数据库与份额的灾难恢复必须核对同一备份边界，不能恢复旧随机材料后继续签名。
队列增加数据库写入、WAL 和归档存储负载，应监控连接池、vacuum 和磁盘占用。

每轮新签名请求（含 RBF）带唯一 requestId，广播前与数据库当前请求校验；旧请求的死信回放不得覆盖新交易。

## 阿里云单 JAR 发布

构建与运行均使用 Java 27，Lombok 1.18.48 已支持该版本。GitHub Actions 只执行不依赖数据库/链节点的单元测试；完整数据库集成测试在开发机既有 PostgreSQL 18 的隔离测试库执行。Java unit 固定为 `surprising-wallet.service`；构建与程序激活入口见根目录 README 及 `scripts/deploy/backend-deploy.sh`。原生持有者另行部署，当前分支尚未执行生产发布。

新实例必须通过环境变量提供 `SW_CUSTODY_SECRET_MASTER_KEY`，没有内置默认加密密钥。开发水龙头默认关闭；如需启用，必须同时补齐其资金地址与节点配置。

部署回滚只覆盖兼容的程序和 systemd unit，保留最新数据库、协议记录和环境密钥；已有地址继续绑定原联合公钥与原持有者。
