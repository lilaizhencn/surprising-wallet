# 启动与测试教程


本文说明如何配置项目、启动本地服务，以及如何运行各类测试环境。

## 1. 环境依赖

需要安装：

- JDK 27
- Maven 3.8+
- PostgreSQL 18
- PGMQ 1.11.1 扩展（安装在现有 PostgreSQL 18 上）
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

在现有 PostgreSQL 18 所在主机安装扩展文件，再执行唯一初始化 SQL。无需额外消息服务。

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

只有 wallet-api 产出可执行 JAR，默认 api。通过 `--sw.wallet.mode=all|api|sig1|sig2` 选择模式。all 共享数据源、各签名阶段独立调度池；sig1/sig2 强制关闭 HTTP。源码签名模块作为普通依赖打包。

| 模式 | 必需密钥环境变量（前缀 SW_WALLET_） |
|---|---|
| all | SIG1_SEED、SIG2_SEED、ED25519_SEED、RECOVERY_PUBLIC_ROOT |
| api | SIG2_SEED、ED25519_SEED、SIG1_PUBLIC_ROOT、RECOVERY_PUBLIC_ROOT |
| sig1 | SIG1_SEED、SIG2_PUBLIC_ROOT、RECOVERY_PUBLIC_ROOT |
| sig2 | SIG2_SEED |

Seed 为 Base64 编码的 32 字节。PUBLIC_ROOT 为对应 BIP32 根的扩展公钥（xpub/tpub），不是普通公钥十六进制，也不能是扩展私钥。恢复 Seed 始终离线，由离线工具导出公钥；三组多签公钥必须不同。沿用已有钱包时必须从原 Seed 导出相同公钥，不能重新生成。api 仍为账户链签名，保留 sig2 和 Ed25519 私钥。all 不提供两方私钥的进程隔离。配置变更需要重启。

```bash
mvn -pl wallet-api -am clean package
java -jar wallet-api/target/wallet-api-1.0.0-SNAPSHOT.jar --sw.wallet.mode=all
```

拆分部署时分别运行相同 JAR 的 api、sig1、sig2 模式，三个进程使用同一数据库；不要同时启用 all 和拆分服务。各进程使用独立环境文件，只配置本方密钥。可通过 `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` 调整 API/all 连接数，独立签名模式使用 `SW_WALLET_SIGNING_DB_POOL_SIZE`（默认 4）。

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
| 当前模式密钥 | 环境变量注入 `sw.wallet.keys` |
| 每链默认热提钱包 | `chain_address` 中原生资产 `user_id=0/biz=0/address_index=0/wallet_role=DEPOSIT` |

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

项目不再使用 `application-{profile}.yaml`。每个属性旁均有用途和配置说明；修改运行环境时更新环境变量并重启对应进程，签名公钥必须与 API 派生地址使用的公钥一致。

本地必配项：

- PostgreSQL URL、用户名、密码
- `chain_profile` 中每条启用链只能启用一个 network
- 启用链至少有一个匹配当前 `sw.app.env.name` 的 `chain_rpc_node`
- 启用的 `chain_rpc_node` 必须配置真实 RPC URL 和认证信息；启动时会拒绝 `CHANGE_ME`、`YOUR_*`、`REPLACE_ME` 等占位符
- `sw.wallet.keys` 已配置当前模式所需的 Seed 和扩展公钥
- 每条启用链必须且只能有一条默认热提钱包地址：`chain_address` 原生资产、`user_id=0`、`biz=0`、`address_index=0`、`wallet_role=DEPOSIT`
- wallet-api、sig1、sig2 使用同一套 Keyset 配置后再启动
- 钱包后台配置页使用的 `SW_WALLET_ADMIN_USERNAME`、`SW_WALLET_ADMIN_PASSWORD`

启动校验会打印每条链的网络、任务开关、扫描起点、扫描批量和 RPC 节点数量。wallet-api 会从配置的 Keyset 推导每条启用链的 `0/0/0` 默认热提地址并和 `chain_address` 比对；Seed 缺失/非法，或地址缺失、重复、path 不一致都会直接启动失败。启用的 RPC 节点如果仍包含占位符 URL 或认证信息，也会直接启动失败。

## 7. 启动服务

从仓库根目录开三个终端。

终端 1：

```bash
mvn -pl wallet-sig1 -am spring-boot:run
```

终端 2：

```bash
mvn -pl wallet-sig2 -am spring-boot:run
```

终端 3：

```bash
mvn -pl wallet-api -am spring-boot:run
```

默认端口：

| 服务 | 端口 |
|---|---:|
| `wallet-api` | 8002 |
| `wallet-sig1` | 8004 |
| `wallet-sig2` | 8081 |

## 8. 测试矩阵

查看支持的测试环境：

```bash
scripts/regtest/all-chain-regtest.sh matrix
```

覆盖范围：

| 命令 | 覆盖 |
|---|---|
| `test-db` | SOL/TON/APTOS/SUI/DOGE 和 UTXO 运行状态的 DB-only scanner/ledger/flow 测试 |
| `test-utxo` | BTC/LTC/DOGE/BCH 本地 regtest、并发、广播测试 |
| `test-xmr` | XMR 本地 wallet-rpc regtest 充值、提现、归集和幂等测试 |
| `test-evm` | 核心 EVM 链的 fork 测试；HyperEVM、Mantle、Linea、Scroll、Unichain 等新增 EVM 链通过 DB profile 复用共享 EVM 路径 |
| `test-live` | 外部 testnet 连通性测试，以及可选花费测试 |
| `test-all` | UTXO、EVM、DB 测试，以及可选 live 测试 |

当前自动化测试的边界：

- `test-utxo` 会在本地 BTC/LTC/DOGE/BCH regtest 中模拟地址创建、充值扫描入账、归集、提现、UTXO 锁定/选择、两次签名、广播和确认。
- `test-xmr` 会启动 XMR regtest wallet-rpc，创建真实子地址，充值并扫描入账，广播项目内提现，验证扫描幂等，并确认归集流程。
- `test-evm` 会在 Hardhat fork 中模拟 EVM 原生币/ERC20 充值、提现、归集和确认流程。
- `test-live` 默认验证外部 testnet/devnet 连通性；真实花费广播需要 `RUN_LIVE_SPENDING=true`、测试币余额、签名私钥和 Ed25519 seed。
- 生产级全链端到端演练还需要同时启动 `wallet-sig1`、`wallet-sig2`、`wallet-api`，并由前端或 API 触发真实业务请求。

## 9. 运行 DB-only 测试

```bash
scripts/regtest/all-chain-regtest.sh test-db
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
scripts/regtest/all-chain-regtest.sh init
scripts/regtest/all-chain-regtest.sh status
```

运行完整本地 UTXO 流程：

```bash
scripts/regtest/all-chain-regtest.sh test-utxo
```

运行 XMR wallet-rpc 流程：

```bash
scripts/regtest/all-chain-regtest.sh test-xmr
```

调整广播压力：

```bash
BITCOINLIKE_BROADCAST_DEPOSITS=80 \
BITCOINLIKE_BROADCAST_WITHDRAWALS=40 \
scripts/regtest/all-chain-regtest.sh test-utxo
```

停止或重置节点：

```bash
scripts/regtest/all-chain-regtest.sh stop
scripts/regtest/all-chain-regtest.sh reset
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
scripts/regtest/all-chain-regtest.sh test-evm
```

只跑部分链：

```bash
CHAIN_FILTER=ETH,BASE scripts/regtest/all-chain-regtest.sh test-evm
```

## 12. 运行外部 Live 测试

只跑连通性：

```bash
RUN_LIVE=true scripts/regtest/all-chain-regtest.sh test-all
```

连通性加花费测试：

```bash
RUN_LIVE=true RUN_LIVE_SPENDING=true scripts/regtest/all-chain-regtest.sh test-all
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

`SW_DB_URL` 在三个应用中必须指向同一个 PostgreSQL 数据库。wallet-api 使用
`SW_DB_USERNAME/SW_DB_PASSWORD`，签名服务分别使用
`SW_SIG1_DB_USERNAME/SW_SIG1_DB_PASSWORD` 和 `SW_SIG2_DB_USERNAME/SW_SIG2_DB_PASSWORD`。
由 DBA 创建独立 LOGIN 角色并安全配置密码；初始化基线由数据库所有者执行。
下面的授权在角色创建后执行，签名服务无需业务表权限：

```sql
-- wallet-api role; business-table grants follow the existing application deployment policy.
GRANT USAGE ON SCHEMA pgmq TO wallet;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA pgmq TO wallet;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA pgmq TO wallet;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA pgmq TO wallet;
GRANT SELECT, INSERT, UPDATE ON public.chain_fee_rate TO wallet;
GRANT USAGE ON SCHEMA pgmq TO wallet_sig1, wallet_sig2;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA pgmq TO wallet_sig1, wallet_sig2;
GRANT SELECT, UPDATE, DELETE ON pgmq.q_wallet_sign_first TO wallet_sig1;
GRANT INSERT, SELECT (msg_id) ON pgmq.a_wallet_sign_first TO wallet_sig1;
GRANT INSERT, SELECT (msg_id) ON pgmq.q_wallet_sign_second, pgmq.q_wallet_sign_done,
    pgmq.q_wallet_sign_first_dead TO wallet_sig1;
GRANT USAGE, SELECT ON SEQUENCE pgmq.q_wallet_sign_second_msg_id_seq,
    pgmq.q_wallet_sign_done_msg_id_seq, pgmq.q_wallet_sign_first_dead_msg_id_seq TO wallet_sig1;
GRANT SELECT, UPDATE, DELETE ON pgmq.q_wallet_sign_second TO wallet_sig2;
GRANT INSERT, SELECT (msg_id) ON pgmq.a_wallet_sign_second TO wallet_sig2;
GRANT INSERT, SELECT (msg_id) ON pgmq.q_wallet_sign_done, pgmq.q_wallet_sign_second_dead TO wallet_sig2;
GRANT USAGE, SELECT ON SEQUENCE pgmq.q_wallet_sign_done_msg_id_seq,
    pgmq.q_wallet_sign_second_dead_msg_id_seq TO wallet_sig2;
```

使用普通持久化队列。队列包含 `wallet_sign_first`、`wallet_sign_second`、`wallet_sign_done`、
`wallet_withdraw`、`wallet_rbf`、`wallet_deposit_event`、`wallet_withdraw_event`，每个队列都有对应 `_dead` 死信队列。
队列 JSON headers 必须携带 `tenant_id`。外部业务消费充值/提现事件时，也必须按租户限定查询条件和数据库权限；
不要向租户应用开放平台队列账号。签名 payload 的 signature JSON 同时包含 tenantId，消费者校验两者一致。

消费先以 300 秒可见性超时领取，再开启处理事务并锁定该消息，事务提交时同时归档消息与投递下一阶段。
处理中的行锁会阻止其他消费者重复领取；崩溃会释放锁并回滚，消息超时后恢复。领取后尚未开始处理就已超时的旧消费者不能确认消息。
RPC 不属于数据库事务，交易广播仍用交易 ID 幂等和 BROADCAST_UNKNOWN 对账。不要因数据库回滚重新构造另一笔提现交易。
失败按指数退避（最大 900 秒），第 20 次失败进入死信；归档和死信保留租户 headers，日志仅记录消息 ID、次数和异常类型。

数据库所有者或单独授权的运维账号可在确认业务状态后重放死信：

```sql
SELECT public.wallet_replay_dead('wallet_sign_first', 123, 'Verified signing configuration after repair');
```

同一死信 ID 重复执行不会重复入队；原消息归档，新消息记录原因、数据库操作者、时间和原死信 ID。
成功归档不自动回放。通过 `pgmq.metrics_all()` 监控队列数量和消息年龄，并按审计保留策略清理归档。

验证只使用本机现有 PostgreSQL 18，测试自动创建并删除隔离数据库：

```bash
SW_TEST_PGMQ=true mvn -pl common -am test -Dtest=PgmqIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
```

部署时先安装指定扩展并初始化新的数据库，再同步部署三个服务。现有开发数据库可重建；不要在有资金数据的库运行初始化基线。
如需回滚，先停止生产者和消费者，保留新数据库快照、未完成队列和归档，再恢复已备份的代码与数据库；不能只回滚 JAR。
队列增加数据库写入、WAL 和归档存储负载，应监控连接池、vacuum 和磁盘占用。

每轮新签名请求（含 RBF）带唯一 signingRequestId，广播前与数据库当前请求校验；旧请求的死信回放不得覆盖新交易。

## 阿里云单 JAR 发布

构建与运行均使用 Java 27，Lombok 1.18.48 已支持该版本。GitHub Actions 只执行不依赖数据库/链节点的单元测试；完整数据库集成测试在开发机既有 PostgreSQL 18 的隔离测试库执行。服务端仅接收已构建的校验发布包，固定使用 `surprising-wallet-all.service`。不再在服务器上拉取代码或编译，不使用旧 `backend-activate.sh` 自动迁移入口。

新实例必须通过环境变量提供 `SW_CUSTODY_SECRET_MASTER_KEY`，没有内置默认加密密钥。开发水龙头默认关闭；如需启用，必须同时补齐其资金地址与节点配置。ZKSYNC 的链配置、RPC 和 USDC 默认共同关闭，避免初始化后启动校验失败。

部署回滚只覆盖 JAR 和 systemd unit，不回滚数据库或环境密钥。新部署使用新的钱包根密钥时，不能用它接管旧钱包地址。
