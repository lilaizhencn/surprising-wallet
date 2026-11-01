# 独立 MPC 签名节点

当前分支已完成独立 MPC 接入与本地链/测试网验收。地址创建、普通 EVM、UTXO、账户链、XMR 原生多方交易、EIP-7702、NEAR 自动注册和份额刷新已有下文所述实测。旧 Java 私钥路径已移除；Linux 发布包与本机冷恢复已通过。当前代码最终 28/28 链组验收已通过，尚未部署生产。部署、备份和未知交易边界见[运行与恢复说明](OPERATIONS.md)。

原生程序可通过 CMake Runtime install 和 `package-release.sh` 打包，包含按文件校验和、平台/架构/依赖版本清单、公开配置样例与独立 Linux systemd unit；安装步骤见运行说明。发布包不包含实际配置或秘密。此处新增的是已有独立节点的安装产物，没有新增业务模块、核心数据流或数据库结构，架构图与 init-sql 无需新增定义。

完整本机冷备份恢复入口为 `resources/scripts/regtest/run-mpc-backup-restore.sh`，需提供 `MPC_RELEASE_ARCHIVE` 和固定官方版本 `MPC_MONERO_BIN_DIR`。它使用同一个既有 PG18 中两个独立临时库，恢复后核对全部表内容与序列，删除原测试目录与源测试库，再在新目录启动原身份持有者、私有钱包和另一个 Java JVM。覆盖三曲线 epoch2、原加密结果、原已完成未领取签名、旧版本拒签及 AB/AC/BC 的 XMR 支付。该入口已通过实际验收：276 个原文件的字节/权限与 143 项表/序列状态完全一致；恢复后原目录和源临时库已删除。另一个 Java JVM 读回 9 个加密结果、领取 3 个原未领取签名并完成 9 次新 epoch2 签名；三组 XMR 付款均已确认，原不可变协议记录保留。其范围是同主机的冷目录恢复，不证明跨主机故障域隔离。公开证明 SHA256 为 `70beabbf648a3c53107adff7d1f0459faa90c958a77fb736102629b78f0bf250`。

## 已落地的代码边界

`wallet_mpc_engine` 封装 cb-mpc 公共高层接口，固定 2-of-3：

- `generate`：三方参与 DKG，各进程只返回本方份额。
- `public_key`：读取联合公钥。
- `sign`：任意两名已登记参与方协作，结果仅返回给本次执行的第一个参与方。
- `refresh`：三方刷新份额，公钥保持不变。

支持 ECDSA/secp256k1、Ed25519 和 BIP340。`KeyShare` 禁止复制；通信、授权、持久化、版本切换由节点调用方承担。协议调用只使用 `include/cbmpc/api` 与公共 core 类型，不调用内部协议或重建私钥。

`EncryptedStore` 为本方材料提供 AES-256-GCM 加密文件存储。记录 ID 与调用方提供的租户/密钥/版本/算法/节点元数据加入认证上下文；记录不可覆盖，同内容重复写入幂等，冲突拒绝。临时文件同步后用硬链接原子发布，再同步目录；读取与写入互斥，不能在目录同步完成前对外暴露新结果。目录文件锁阻止两个节点共用同一份额目录；目录和文件分别限制为本用户的 0700/0600，拒绝符号链接。加密密钥由节点外部配置提供，不写入份额目录。

`wallet-mpc-signer` 为独立节点入口，采用 mTLS HTTPS JSON，降低 Java 接入与运行依赖成本。配置固定三个节点的 SPKI SHA256 身份、地址以及客户端证书到租户/操作的明确授权。协议通道把请求摘要、发送者身份和顺序号绑定到会话；接收限额与超时在节点内处理。每个进程同时只执行一个 cb-mpc 协议，以符合上游线程安全边界。

当前节点接口为 `POST /v1/sessions`、`POST /v1/results` 与节点间专用的 `POST /v1/messages`；XMR 原签名材料恢复另有受原参与方约束的内部接口。节点支持初始建钥、签名和三方 REFRESH；刷新通过持有者专用 `/v1/refresh-prepared`、持久 fence/ready 及 Java 原子版本切换完成。已受理请求和结果加密持久化，重启后未完成的执行返回 `INTERRUPTED`，不能自动作为无签名失败处理。签名执行异常返回 `INDETERMINATE`。XMR 仅能按下文规则恢复原有材料；其余算法不能重启未知签名。当前服务用于隔离验收，不得用于真实资金。

Java `common.signing.MpcClient` 是单节点客户端：固定 TLS 1.3、CA/主机名校验、握手阶段 SPKI pinning，拒绝重定向；请求绑定租户/密钥/版本/会话和规范化意图摘要，限制响应大小与调用时间，并用 Java 独立验签。它只加载通信证书私钥，不加载钱包私钥或份额。`MpcSigningService` 编排钱包持久化，地址和交易业务使用远程持有者。客户端异常表示调用结果未知，调用方必须先对账原会话。

钱包四张表分别保存 key、epoch、固定业务签名请求与每次节点尝试，每表有独立 Repository。签名请求在调用方资金/nonce/UTXO 事务中预约，提交后才查询或受理节点；签名先以绑定租户、请求、会话、密钥、版本、公钥及原文的加密封装落库，再供业务读取。数据库拒绝修改已受理的意图、参与方、公钥和已完成结果。未知状态不解冻；显式重放保留原文和尝试历史，记录操作者及原因。

地址创建入口已改为提交租户主题坐标、事务外 DKG、最后事务发布联合公钥地址；系统 Gas 地址与 Gas 账户在最后一个事务中一起发布。链地址持有 `mpc_key_id`，租户外键和不可变约束禁止更换已发布的密钥身份。入口没有 Seed 派生回退，旧签名模块与 Java 钱包私钥路径已清理。各链交易的实际验收范围见清单，当前 28/28 链组验收范围见下文清单。

Java API 通过 `SW_MPC_CA_FILE`、`SW_MPC_CLIENT_CERTIFICATE_FILE`、`SW_MPC_CLIENT_PRIVATE_KEY_FILE` 配置 mTLS 通信身份，三个节点分别使用 `SW_MPC_NODE_{1,2,3}_ID`（SHA256 SPKI）与 `SW_MPC_NODE_{1,2,3}_URL`（HTTPS origin）。可设置 `SW_MPC_CALL_TIMEOUT` / `SW_MPC_OPERATION_TIMEOUT`，默认分别为 5 / 30 秒。配置缺失时 API 启动失败；节点暂不可达时保留持久会话并返回可重试状态。

`MpcEvmTransactionCodec` 在 Java 中只做 unsigned RLP、Keccak、外部签名验签、low-S 规范化和 recovery ID / 签名交易组装，不接受私钥。普通 EVM 入口采用 reserve/sign/commit 三阶段；7702 批次与其余链交易的实际业务验收范围见后文。

依赖固定为 cb-mpc `0b716706f998633912ee2be9b9bf8909aed66088`。必须使用该版本要求的 patched OpenSSL 3.6.4，不允许静默替换为系统 libcrypto。

## 本机底层验证

需要 Git、CMake、C++17 编译器、Python 3、curl、make 和 Perl。可用下面的脚本在仓库外下载、校验并构建固定依赖，再执行原生测试。OpenSSL 采用上游要求的固定源包与曲线符号补丁，保留独立节点通信所需的 TLS；不替换系统 OpenSSL，不启动数据库。

```bash
bash resources/infra/mpc-signer/build-and-test.sh
```

`MPC_BUILD_DIR` 默认为 `/tmp/surprising-wallet-mpc-build`，`MPC_BUILD_JOBS` 默认为 4。`MPC_CMAKE`、`MPC_CTEST` 可指定工具路径。已有匹配依赖时可设置 `CBMPC_SOURCE_DIR`、`CBMPC_OPENSSL_ROOT`，或直接运行：

```bash
cmake -S resources/infra/mpc-signer -B /tmp/surprising-wallet-mpc-build \
  -DCBMPC_SOURCE_DIR=/path/to/pinned/cb-mpc \
  -DCBMPC_OPENSSL_ROOT=/path/to/patched/openssl
cmake --build /tmp/surprising-wallet-mpc-build --parallel 4
ctest --test-dir /tmp/surprising-wallet-mpc-build --output-on-failure
```

只验证底层而不构建 HTTPS 节点时，可设置 `-DWALLET_MPC_BUILD_SERVER=OFF`；完整节点需要同一 patched OpenSSL 安装中的 `libssl.a` 与 `libcrypto.a`。HTTP 与 JSON 头文件固定版本并校验 SHA256，下载至构建目录。

测试使用进程内传输执行真实密码学协议，检查三种算法的 AB/AC/BC 组合、刷新前后签名、公钥一致性、消息篡改和无效参与方。它不能证明网络隔离、部署安全、恢复持久性或任何链的广播/确认流程。

独立的存储测试检查重新加载、并发重复写入、冲突拒绝、上下文隔离、密文篡改、错误密钥、权限与符号链接拒绝；不创建数据库。

`mpc_independent_nodes` 创建临时证书并运行三个独立进程，覆盖三种算法的全部双节点组合、独立验签、重启后份额使用、重复受理、身份/租户/角色拒绝、乱序/错误上下文消息拒绝以及中断会话隔离。设置 `MPC_JAVA_TEST_CLASSPATH` 为编译后的 common 测试类、生产类及运行依赖 classpath 时，同一夹具额外运行 `MpcClientNativeProbe`，对 Java 客户端的全部算法/组合验签，并验证错误 SPKI 在提交 HTTP 请求前即被拒绝。未设置时，该 CTest 只覆盖原生节点。

完整的 Java ↔ 原生三节点验证入口如下。它先编译并测试 common/SDK，从实际 Maven 测试报告取得 reactor classpath，再强制执行 Java 联调探针；此验证仍不代表钱包业务或全链验收完成。

```bash
bash resources/infra/mpc-signer/test-java-client.sh
```

钱包持久化及真实三节点的业务与组件联调入口如下，需要已安装的 Anvil。它只在本机 PostgreSQL 18 中创建并清理 `surprising_wallet_test_mpc_*` 临时库，先运行持久化、仓储边界、公钥地址与 EVM 编码测试，再强制运行钱包 Service 探针。覆盖 secp256k1 与 Ed25519 的全部双节点组合、DKG 持久化、公钥复用、重复预约、已有节点结果对账、应用对象重建后的密文读取与独立验签；另用真实网络中断验证未知状态不回退、记录操作者和原因后换另一组节点重放相同原文。联合公钥编码覆盖 27 条 cb-mpc 目标链，BTC/LTC 为 P2WPKH、DOGE/BCH 为 P2PKH，TON 仅传入公钥。

EVM 探针使用真实 legacy、2930 与 1559 转账载荷，经持久化签名后组装并广播至隔离的本地 Anvil，检查 receipt、实际到账与重复广播；Ed25519 路径使用明确标记的组件载荷。Anvil 只监听临时 loopback 端口，与签名节点一起在测试结束时退出，不创建数据库。这些测试仍不代表正式提现/归集业务闭环、13 个 EVM 网络费用规则或全部链的验收完成。

同一真实三节点探针调用正式 `CustodyAddressService`，覆盖 27 条非 XMR 链的建钥和地址发布、13 条 EVM 链公钥复用、Gas 发布审计失败时整体回滚，以及节点不可用时的持久 DKG 会话恢复。一般数据库用例只替换 DKG 传输，其余编排和仓储保持实际实现；这些替身用例不能证明 MPC 密码学或链交易验收。

```bash
bash resources/infra/mpc-signer/test-wallet-service.sh
```

## 全链接入清单

| 当前链 | 目标签名实现 | 当前链级验收 |
|---|---|---|
| ETH, BNB, POLYGON, ARBITRUM, OPTIMISM, BASE, AVAX_C, HYPEREVM, LINEA, SCROLL, UNICHAIN, X_LAYER, ROBINHOOD_CHAIN | cb-mpc ECDSA | 普通交易的真实 MPC 本地 13 链矩阵及正式 7702 三组业务通过；本地证明不包含生产 L2 费用预言机 |
| BTC, LTC, DOGE, BCH | cb-mpc ECDSA；联合单公钥交易 | 三组节点实际 Core regtest 提现/账务通过；BTC RBF 与双输入归集通过 |
| TRON | cb-mpc ECDSA；TRX/TRC20 | 三组节点实际 TRE TRX/USDT/USDC、归集、失败退款通过 |
| XRP | cb-mpc ECDSA；Payment/TrustSet | 三组节点实际 xrpld XRP/USDT/USDC、激活/TrustSet、归集、失败退款、Gas 对账通过 |
| HYPERCORE | cb-mpc ECDSA；用户签名 EIP-712 | 本轮 21 个业务请求均完成 MPC 签名、规范确认和一次结算，覆盖 USDC/HYPE 三组提现、归集、托管收款和丢回复恢复；原未知转账已按原文重播后确认，冻结归零 |
| SOLANA | cb-mpc Ed25519；SPL 含租户 Gas 联合签名 | 三组节点实际 Agave SOL/USDT/USDC、充值重放、响应丢失恢复、归集、失败退款、Gas 实际支出通过 |
| APTOS | cb-mpc Ed25519；固定 BCS 与 legacy authenticator | 三组节点实际 Aptos localnet APT/USDT/USDC、充值重放、原文恢复、归集、失败退款、实际净费用通过 |
| TON | cb-mpc Ed25519 V4R2 / TEP-74 | 三组真实 MPC 正式提现、充值、归集、原文恢复、明确失败退款和实际费用已通过 |
| SUI | cb-mpc Ed25519 | 三组 SUI / 本地 USDT / USDC 正式业务、恢复、归集、真实失败及存储净费验收通过 |
| ADA | cb-mpc Ed25519 | 三组实际 Cardano 业务、原文恢复、归集、充值及费用对账通过 |
| DOT | cb-mpc Ed25519 | 三组实际中继链/Asset Hub 业务、原文恢复、归集、充值及费用对账通过 |
| NEAR | cb-mpc Ed25519 | 三组实际 NEAR/USDT/USDC 业务与 24 位精确费用通过；自动 NEP-145 注册/退款及后续付款 21 笔本地交易通过 |
| XMR | 经用户确认采用 Monero 原生 2-of-3 多方协议 | 统一回归通过：三组实际充值/重组、提现/归集/补款及原材料恢复；缺失原签名材料时保持未知和冻结 |

2026-11-01 当前代码最终 28/28 个链组本地链/测试网矩阵全部通过；正式 EIP-7702、NEAR 自动存储注册、完整 XMR、网络份额版本切换、旧签名路径清理、Linux 发布包与同机冷恢复均有独立通过记录。HyperCore 的 `final-20261101` 批次共 21 笔规范确认（15 笔提现、6 笔归集），每请求仅一条原签名尝试。原未知 1 USDC 转账保留 nonce `1793500142474` 和全部原签名字节，原文重播后取得交易 `0xafb6cc1db47d443fb130042d1aa2fd010600e4034f706311537f777073711e2a`，区块 `756720381` 执行无错误；正常业务流程已持久化回执、完成一次结算，冻结归零。随后 AB/AC/BC 正式恢复回归均未再次发出付款，受控 USDC/HYPE 实际余额与账本合计一致。重复 nonce 报错与暂未找到流水仍不能单独作为成功或退款依据。不得以原单签仍可运行、只签测试摘要或跳过测试作为某链已支持 MPC 的证据。

数据库继续只使用本机 `127.0.0.1:5432` PostgreSQL 18 的隔离临时测试库。原生节点不访问钱包数据库；Java 编排所需四张元数据表、索引、复合外键与不可变触发器已同步唯一 init-sql，不存储私钥份额。

普通 EVM 业务探针使用正式地址、nonce、固定交易请求及冻结账务，通过真实三节点签名后广播到隔离 Anvil，覆盖 legacy 和 EIP-1559，故意丢失首次已接收广播的响应，并检查恢复不会重复广播或结算。所有携带有效交易字节的 RPC 发出前，探针检查 raw、MPC 结果和业务哈希已经提交；此业务探针与上述组件验签分开，不代表其他网络或全部链验收完成。

本地 EVM 矩阵 `resources/infra/evm-fork/scripts/run-local-matrix.sh` 现在由 `with-test-nodes.py` 启动真实三节点，向子进程提供临时的 `SW_TEST_MPC_DIRECTORY`。正式账户交易 Service 测试从联合公钥地址发起原生币和 ERC-20 提现，不再使用本地派生私钥。三个节点、临时证书与份额随命令退出清理，数据库仍复用本机 PostgreSQL 18 的隔离临时库。Hardhat 的本地模式不包含各 L2 的生产费用预言机，相关费用模型继续由专门用例验证；矩阵通过不等于真实网络费用验收完成。

UTXO 联合公钥编码组件提供固定 unsigned 交易、逐输入摘要、外部 DER 验签与普通 witness/scriptSig 组装，覆盖 BTC/LTC P2WPKH、DOGE/BCH P2PKH 与 BCH FORKID。正式 PGMQ 队列、广播与本金结算已接入该组件，四条链的实际业务验收范围见上表；单独编码测试仍不能替代链级验收。

节点每次只执行一个协议，忙时返回 BUSY，且不写受理凭据。钱包先查询全部参与方结果，再按 SPKI 全局顺序申请缺席节点；遇 BUSY 或不可达时不占用后续节点，已完成结果仍从可达节点收取。真实并发建钥用例与单节点 BUSY/重启后无受理凭据的用例覆盖这一边界；BUSY 不作为资金可释放的证明，网络异常继续保留未知状态。

BTC、LTC、DOGE、BCH 的真实本地链验收复用已运行的隔离 Core regtest。对每条链设置 `MPC_<CHAIN>_TEST_RPC_URL`、`MPC_<CHAIN>_TEST_RPC_USER`、`MPC_<CHAIN>_TEST_RPC_PASSWORD` 与 `MPC_<CHAIN>_TEST_WALLET`，运行上述 `test-wallet-service.sh`；未设置的链不计为通过。四条链均覆盖真实 MPC AB/AC/BC 签名、共识确认、收款与找零、正式 PGMQ 签名和广播流程、丢失广播回复后的同字节重放，以及冻结本金只结算一次。DOGE 节点不支持 `testmempoolaccept`，以实际广播和挖块确认验签；其余三条链同时检查 mempool 验收。BTC 另覆盖 RBF 队列加费替换和双输入归集。全链最终验收状态以独立验收记录为准。

UTXO queue cutover uses `wallet_mpc_sign` in the API worker. Per-input immutable intents commit with original input locks, order claims and the enqueue receipt. Native calls run outside PostgreSQL transactions. SIGN_DONE publication and archive are read_ct-fenced; before RPC the API rebuilds the selected transaction from persisted verified signatures. Error messages cannot release admitted input locks or principal. All-chain business acceptance remains in progress.

The native wallet probe optionally accepts `MPC_{BTC,LTC,DOGE,BCH}_TEST_RPC_{URL,USER,PASSWORD}` and `MPC_{BTC,LTC,DOGE,BCH}_TEST_WALLET` for isolated loopback regtest nodes. It verifies each chain with actual AB/AC/BC signatures, consensus-accepted transactions, formal MPC signing/PGMQ/broadcast phases, deliberately lost broadcast replies, identical-byte retries, mined outputs and exactly-once frozen principal settlement. Litecoin regtest must initialize its first MWEB peg-in before activation at height 432 (the upstream [issue #807](https://github.com/litecoin-project/litecoin/issues/807)); `resources/scripts/regtest/litecoin-regtest.sh init` prepares it using only its own test funder. Core RPC is a test transport bridge; DKG and signing are actual native node calls. BTC additionally exercises its RBF queue with the same outpoints and payment. Optional external integrations remain separate from unit-test pass counts.

BTC acceptance also includes a two-input sweep to a distinct joint-key collector: two durable input signature slots, exact input-minus-fee single output, no extra change, real mined inputs and idempotent collection confirmation. The initial collection request is a fixture; it does not claim end-to-end deposit scanning or collection planning coverage.

TRON acceptance can add an isolated loopback TRE node with `MPC_TRON_TEST_HTTP_URL`, `MPC_TRON_TEST_FULL_NODE`, `MPC_TRON_TEST_SOLIDITY_NODE` and a compiled MockERC20 JSON artifact at `MPC_TRON_TEST_TOKEN_ARTIFACT`. The probe uses actual native MPC for all custody signatures; local development funder credentials are used only to fund and deploy fixture contracts. TRE 2.x uses a single Java process and can enable one-second blocks with its loopback `/tre` `tre_blockTime` method; stale instamine timestamps otherwise expire SDK-prepared transfers. It checks raw/hash persistence, deliberate lost replies, execution logs, recipient deltas and frozen-principal settlement. Full TRON native acceptance is under validation.

XRP 本地节点验收使用 `MPC_XRP_TEST_RPC_URL` 指定独立的 loopback 官方 standalone xrpld。原生探针实际创建租户 MPC Gas 账户，执行源/目标账户激活与两种发行货币 TrustSet，覆盖三组节点的 XRP/USDT/USDC 付款及丢失响应恢复、发行货币归集、真实 validated tec 失败后本金释放和实际费用。确认只认原签名字节、哈希及精确 delivered_amount。Gas 账务预留与不可变意图同事务；实际准备支出、使用记录、流水和链事实同事务，探针最后核对 Gas 账本与实际链上 XRP 余额完全相等。公共 standalone genesis 账户只用于测试注资与发行资产。

HyperCore 托管账本由 spotClearinghouseState 现货余额支撑，因此钱包的 USDC 与 HYPE 均使用 spotSend，禁止用永续 usdSend 消费现货入账。USDC 从网络元数据中选择 canonical USDC token ID，保留钱包 6 位精度；HYPE 同时核对配置 token ID 与网络 8 位精度。usdSend 只作为编码组件覆盖，不属于当前钱包出账入口。新账户的首次转入存在激活费用，正式链级金额/费用验收仍需测试网验证，不以 HTTP 替身代替。

HyperCore 付款签名前在事务外检查收款地址 userRole，仅已激活普通账户/子账户可进入预约；预约在业务锁下核对同一收款地址，防止检查后更换地址。首次激活的额外 quote-token 费用不属于这笔现货付款，禁止在未冻结相应费用时通过提现/归集隐式激活新地址；租户归集/Gas 地址需预先激活。未知原意图不会因开关关闭而丢弃。

`MPC_HYPERCORE_TESTNET_URL=https://api.hyperliquid-testnet.xyz` 启用官方测试网认证探针。它先确认联合地址尚未激活，使用三组真实 MPC 签名提交未充值账户的请求，并要求服务器从签名中恢复出的地址与联合公钥地址完全一致；请求和结果未知状态保持持久化，测试账本本金保持冻结。该探针不验证真实资金转移、到账或激活费用，不能据此声称 HyperCore 的资金验收已经完成。

Solana 公钥编码组件采用 legacy message，按 header 规定的签名者顺序组装外部 Ed25519 签名；Java 不构造伪私钥 Account。`MPC_SOLANA_TEST_RPC_URL` 可指定独立 loopback Agave 节点，验证三组真实 MPC 的原生 SOL message、已确认 raw、精确到账和重复重播；正式业务探针进一步覆盖 SOL/USDT/USDC、两名联合所有者的签名槽、真实充值扫描与幂等投影、原文先提交再 RPC、丢失响应后关闭新任务/资产仍可恢复、一次性本金结算、归集和真实失败退款；实际 Gas 支出包含新 ATA 租金。不同付款必须有稳定且不同的业务 Memo，避免相同 blockhash/付款的消息去重。

Solanaj 1.28.0 的 MemoProgram 常量指向旧 Memo1，实际 Agave 3.1.15 仿真报 ProgramFailedToComplete。MPC 公钥组件显式使用 Solana 官方 Memo v2 地址 `MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr`；业务签名路径也使用该固定 Memo v2。

Solana 验收复用独立管理的官方 Agave test-validator（本次版本 3.1.15），通过 `resources/scripts/regtest/run-solana-flow.sh` 运行；默认 loopback RPC `http://127.0.0.1:28899` 可由 `MPC_SOLANA_TEST_RPC_URL` 覆盖。脚本只创建现有本机 PostgreSQL 18 的临时库，不启动钱包数据库或使用 Seed。Mint/注资者是本地测试 fixture，所有钱包付款签名均来自实际三节点 MPC。

Aptos 验收可通过 `resources/infra/aptos/run-fa-flow.sh` 复用独立 loopback localnet 与仓库外测试 FA profile（详见 `resources/infra/aptos/fa-test/README.md`）。官方 Aptos CLI 9.4.0 和固定框架 fixture 已实际运行，三个节点的 APT/USDT/USDC 提现、丢失响应恢复、充值重放、归集和真实 Move 失败退款均通过。提交前持久 selected JSON/BCS hash；回执按规范 32 字节地址与精确 Metadata.inner ABI 对象复核付款。aptos_transaction.fee_octas 保存扣除存储退款后的实际净费，探针核对业务源/归集地址原生余额与外部支付、全部实际净费之和。普通 PG 用例使用明确的本地 Ed25519 替身，不能替代真实节点测试。

Aptos 原生验收按规范的 32 字节地址核对发送者：实际 REST 回执可能省略前导零，仍必须同时匹配原始序号、签名和交易哈希。该规范化与生产回执校验一致，不更改交易或账务流程。

TON payments use fixed send mode 3 (fees separately + eligible action errors skipped), preserving seqno consumption for skipped sends. A successful external transaction with zero outgoing messages is never counted as a paid transfer. TON component acceptance is available with `MPC_TON_TEST_RPC_URL` (isolated loopback) and `MPC_TON_TEST_FUNDER_KEY_FILE` (absolute protected 32-byte seed file for that genesis fixture only). The actual three MPC quorum pairs deploy a public V4R2 StateInit with the first native payment, verify exact principal delivery and replay the same BOC. The requested component also deploys local mock USDT/USDC and executes all three MPC quorums through real TEP-74 source/target wallets and notification emission and actual sender excess refund traces. Formal business settlement is verified by the mandatory `ton-business-passed` acceptance marker. TON signing uses wallet_id 698983191 on mainnet, XOR bit31 on testnet, XOR bit30 on regtest; both contract identity and signed body bind this domain. `tests/ton_reference.cjs` independently verifies public-only native/TEP-74 vectors with @ton/core 0.63.1 and @ton/ton 16.2.2; install those packages outside the repository.

TON acceptance uses only the isolated genesis ledger and TON HTTP API; do not run the auxiliary HTTP faucet or an indexer database. Copy that genesis container's `/usr/share/data/faucet-highload.pk` into an owner-only temporary file outside the repository, set `MPC_TON_TEST_FUNDER_KEY_FILE`, and run `resources/scripts/regtest/run-ton-flow.sh`. This test funding key belongs only to the explicitly created local genesis; wallet owner keys remain three-party MPC shares. Grant messages are sent once with unique highload query IDs, avoiding repeated faucet top-ups during balance/fee assertions.

TON component acceptance has passed with actual AB/AC/BC native TON and mock USDT/USDC: fixed V4R2 StateInit, selected external message and internal delivery hashes, exact native/token credits, actual forwarding/storage/excess net fees, and same-wire replay. `TonExecutionReceipt` is used against each live success trace. Actual mode-3 insufficient-balance execution additionally verifies skipped-send failure proof, exact fee, consumed seqno and replay rejection. Formal wallet withdrawal/collection intent, BOC/hash commit, durable recovery and atomic success/skipped-send settlement have been implemented; 74 unit / PostgreSQL / architecture / entrypoint checks pass without skips. Actual AB/AC/BC TON/USDT/USDC production withdrawals, real deposit/replay, lost replies and recovery after task/asset disable, collection, skipped-send and source-abort refunds and exact source native expense reconciliation pass. Public owner/Jetton registration and raw-cell deposit validation have replaced the Seed/indexer paths. Target-wallet failures without an exact restoration proof remain held for reconciliation. Local TON observations can arrive about 28 seconds after submission; fixture waits are bounded at 60 seconds per hop, and the combined native probe has a 600-second Java / 660-second CTest budget when TON is requested.

## SUI MPC 业务与对象恢复

SUI 使用独立 cb-mpc 节点的 Ed25519 联合密钥。Java 固定付款、类型、Gas、Coin 对象 ID/version/digest、网络和完整 genesis checkpoint ID，计算 Blake2b Intent 摘要；外部签名验签通过后封装 97 字节 witness，并独立重建交易 digest。Coin<T> 使用带 T 的类型检查和精确 SplitCoins，不按 RPC 声称的余额直接转出整个对象。

SuiSubmissionService 锁定租户业务与 active 联合密钥，在同一事务提交 SIGNING 对象记录和 MPC 意图。每个 sender 同时只接纳一笔未完成业务，且新业务不得复用任何已有 digest。远程 MPC 在事务外执行；选定 BCS / witness / digest 与业务哈希先持久化，再调用 gRPC。丢失响应只查询或重放原文，不重新选择对象；恢复先于新任务和 Token 开关。

SuiExecutionReceipt 读取原始 ExecutedTransaction protobuf，复核选定 BCS、witness、digest、最终 checkpoint、执行结果以及双方精确余额变化。sui_transaction 的 raw_payload 保持最初选定原文，execution_receipt 单独保留证明；有符号 fee_mist 取 computationCost + storageCost - storageRebate，成功和明确失败均保留实际净费，终态不能被迟到广播观察覆盖。明确执行失败不支付本金，冻结本金只退款一次。

custody_gas_usage 仅对 SUI 允许负 actual_amount。存储退款超过支出时，Gas 预留释放并以 NETWORK_FEE CREDIT 记录净返还；已知零费释放预留，零费使用记录仍作为审计事实，不能退回预估费。其他链禁止负费。原 gas_used 字段已收敛为 fee_mist，新增 execution_receipt JSON 对象约束及 pending sender 索引；单表仓储仍为 SuiTransactionRepository，唯一 init-sql 已同步。

充值扫描读取原始成功证明中的类型、checkpoint 和正向余额变化，使用已发布 native MPC 地址识别配置的 Coin 资产；内部 sender、失败执行和 canonical 重放不入账。DepositService 的记录、余额和强制租户投影仍同事务提交。已观察充值通过 confirmObserved 补确认，AccountChainDepositWorkflow 在新扫描开关判断前调用，不重新依赖 Token 或地址入口开关。

实际验收使用隔离 genesis 的官方 Sui 1.74.1 validator/fullnode 和 loopback faucet，不启动辅助 indexer、GraphQL 或数据库。MPC_SUI_TEST_COIN_FIXTURE 提供本地六位精度 USDT / USDC Move 夹具，管理员 keystore 留在仓库外，Java 钱包只持有 MPC 公钥。三组 AB/AC/BC 的 SUI / USDT / USDC 业务提现、真实充值/重放、广播前原文提交、丢失响应恢复、关闭新业务开关后的确认、三类归集、真实 InsufficientGas 退款和存储返还后净费用/受控余额核对已通过，完整 native CTest 为 77.91 秒。独立 @mysten/sui 2.38.0 向量复核编码；公开原始成功与失败证明用于对抗测试。链上验证预算为 600 秒，生产 RPC 超时不变。

Sui gRPC 读取节点必须保留扫描与重扫所需历史。隔离夹具的 `fullnode.yaml` 使用 `authority-store-pruning-config` 下的 `num-epochs-to-retain: 2` 和 `num-epochs-to-retain-for-checkpoints: 2`；零对象历史保留会使已扫描检查点的 gRPC 查询返回 `NOT_FOUND`，即使 JSON-RPC 仍能返回检查点摘要。实际遇到该情况后，保留原 DB、genesis 和密钥调整测试节点配置并重启，完整 SUI/USDT/USDC 验收重新通过。钱包收到该 `NOT_FOUND` 错误时不会跨过缺失检查点推进扫描游标；生产保留期须覆盖实际扫描与维护窗口，见 [Sui 官方数据管理说明](https://docs.sui.io/operators/data-management/managing-data)。此处仅调整隔离节点夹具与文档，没有生产模块、核心流程或数据库基线变化。


## NEAR MPC 交易、业务恢复与金额精度

MpcNearTransactionCodec 固定隐式 sender 公钥、nonce、收款账户、u128 原子金额、参考区块、有效高度及完整 genesis 身份。原生 Transfer 或 NEP-141 ft_transfer 的 Borsh 经 SHA-256 形成 Ed25519 消息；Java 只验签和封装外部 witness。合约、精确参数、memo、Gas 和 1 yoctoNEAR 附加款都绑定在已选原文。NearTransactionService 只查询公开访问密钥与 RPC，不持有 Seed；测试管理员 keystore 留在仓库外。

NearSubmissionService 锁定租户业务和 active 联合密钥，同事务原子预留 account_sequence、SIGNING 链记录和 MPC 意图。同一 sender 的未完成意图跨资产串行接纳，避免较高 nonce 使旧意图失效。远程签名在事务外执行；首个 Borsh/witness/hash 与业务哈希提交后才发送。恢复先于新任务/Token 开关，仅查询或广播同一原文。send_tx 使用 EXECUTED_OPTIMISTIC 等待可核对的交易 hash；INCLUDED 在实际节点上可能只返回状态，不得据此误判身份。最终结算仍单独等待 FINAL。knownTransaction 使用 wait_until=NONE 区分明确 UNKNOWN_TRANSACTION；超时不等于未广播。参考区块过期且无法查到原交易时保持待核对，不换 nonce 或重签。

NearExecutionReceipt 校验原始 FINAL 中的选定 Borsh/hash/witness、唯一 Action、主回执账户、精确 NEP-141 事件和已完成系统退款闭包；异步 Promise、无关动作或缺失退款不能结算。完整闭包的 block hash 对应原始 header，按最大执行高度累计额外确认数。near_transaction.raw_payload 保留首个原文，execution_receipt 分存 FINAL 和 header 证明，fee_yocto 保存实际原生支出；既有证明和终态不受迟到广播覆盖。成功 Token 的支出为所有 outcome tokens_burnt 加保留的 1 yoctoNEAR；失败时附加款已退回，只剩实际 outcome 费用。链终态和冻结本金扣减/退款同事务提交，Gas 对账读取 24 位实际支出，不回退估算。

充值按 FINAL 成功回执中的 NEP-141 transfer/mint/burn 事件计算每个地址的净变化，ft_transfer_call 的已完成退款会减少可入账金额；请求参数不构成到账证明。原生充值只接受已成功的直接 Transfer/CreateAccount/AddKey 批次。已停用的内部付款地址仍过滤，canonical 重放不重复入账，DepositService 的记录、余额和强制租户投影同事务提交。已观察充值补确认先于新扫描开关；未知历史区块不直接推进游标，只有后续 header.prev_height 证明跳块才跨过。

实际 FT 证明的原生支出 1282350138824700000001 yoctoNEAR 比 outcome 费用多 1。PostgreSQL 回归复现原 18 位列把 1 yoctoNEAR 充值/归集金额舍为 0，并导致提现金额约束失败。唯一 init-sql 中原 numeric(78,18) 的 29 个金额/阈值字段统一为 numeric(84,24)，仍保留原有 60 位整数容量；已有 24 位字段和 USD 报价不变。Gas 金额校验、提现容量和 Token 阈值同步到 24 位，各链原子精度仍严格校验。新增 fee_yocto、execution_receipt、JSON/费用约束及 pending sender 索引，对应单表仓储仍是 NearTransactionRepository；当前空库基线直接收敛，不增加兼容读写。

独立 near-api-js 7.4.0 向量与官方 near-sandbox 2.13.4（协议 86）组件覆盖三组真实 MPC 的 NEAR/本地六位 USDT/USDC、1 yoctoNEAR 转账、FINAL、精确余额和原文重放；不存在账户、FT 余额不足与 Gas 耗尽三类已提交失败均实际退款并只扣一次费用，完整组件 75.78 秒。金额、提现、Gas、编码、回执与边界共 63 项检查已通过，无跳过。随后 70 项正式入口/真实 PostgreSQL/回执/净额扫描/架构与调度检查通过，无失败或跳过。真实正式业务 CTest 为 133.61 秒：AB/AC/BC 三组的 NEAR/USDT/USDC 共九笔提现、1 yoctoNEAR 入账和提现、每笔丢失回复后关闭新任务/Token 的原文恢复、三类归集、原生不存在账户与 Token Gas 耗尽退款、真实 transfer_call 失败回调后的全额退款均已通过。受控 source+collector 原生余额扣除对外本金，与所有实际费用（含成功 FT 的 1 yocto）精确一致；内部和 canonical 重放不重复入账。金额/SUI/NEAR/提现/Gas/入口综合回归 147 项已通过，无失败或跳过，耗时 2 分 50 秒；当前整体验收范围见全链接入清单。

RPC 语义按 [NEAR Transactions](https://docs.near.org/api/rpc/transactions) 与 [Block / Chunk](https://docs.near.org/api/rpc/block-chunk)；FINAL 包括退款完成，区块可按 hash 读取。验收使用 MPC_NEAR_TEST_RPC_URL / MPC_NEAR_TEST_FIXTURE 的隔离本地共识和官方外部 SDK 注资夹具。run-near-flow.sh 复用该账本和保护的描述符，调用统一 MPC 验收；不再启动带固定钱包 Seed 的 genesis 脚本。正式验收要求 near-business-passed 标记，包含真实 transfer_call 全额退款，Java 上限为 600 秒。

## DOT MPC 公共 SCALE 组件与后续业务验收

MpcPolkadotTransactionCodec 是无私钥的 V4 SCALE 原生/Assets 付款编码器，支持余额 transferKeepAlive / transferAllowDeath 和 Asset Hub transferKeepAlive / transfer。只接受 AccountId32、Compact<u32> 资产 ID、Compact<u128> 正数本金、受支持的交易扩展及有界 mortal era；固定联合公钥、nonce、完整 genesis/ref block hash、运行时 spec/transaction version、方法索引和 keep-alive 选择。未知交易扩展拒绝。Ed25519 外部 witness 独立验签后封装 MultiSignature，并核对完整 extrinsic 和 Blake2b256 hash。

中继链与 Asset Hub 的 genesis、nonce 及最终状态独立；role 由链路绑定，不能仅用 SS58 前缀识别网络。当前 Asset Hub 元数据的 ChargeAssetTxPayment 比中继链多一个手续费资产字段；编码固定为 None（原生币支付），不把业务 Token 的 assetId 当作手续费币，也不启用未经审查的费用兑换。CheckMetadataHash mode=0 / None。MpcPublicAddressCodec 的 DOT 地址改用公共编码器做 SS58 公钥/校验和处理，不需实例化 Seed 服务。

验收环境使用官方 v1.25.0-7a8daa68e18 镜像：隔离的 Rococo development 中继链与 Westend Asset Hub development；只持久化区块链账本，不启动钱包数据库。两个实际运行时均声明 12 位测试原生精度，主网 DOT 的 10 位精度须按配置/元数据处理，不能沿用测试网络的金额常量。官方 @polkadot/api16.5.6 / util-crypto14.0.3 从实际公开元数据生成五组独立向量，包含 u128 最大值、u32 nonce/资产边界与两种 SS58 前缀长度；私钥仅为一次性参考签名生成，未保存。MPC_DOT_TEST_FIXTURE 驱动官方隔离共识中的三组真实 MPC 组件，单独检查中继链原生币、Asset Hub 原生 Gas/USDT/USDC、最终支付与费用事件、物理余额和原文重放；要求 dot-codec-passed 标记，Java 超时为 600 秒。真实组件 325.18 秒通过：AB/AC/BC 共十二笔中继链原生币及 Asset Hub 原生币/USDT/USDC，最终事件、本金、物理原生余额扣费和同原文重放全部核对通过。公开原始证明已保存用于后续对抗测试；这不构成正式业务已迁移的声明。测试管理员只在独立 Node CLI 中读取仓库外受保护文件。

当前公共编码、独立向量、公共地址与架构边界 11 项检查已通过；运行时 helper 已删除私钥签名/开发注资/Token 发行接口，改为公共元数据、费用估计、外部原文广播和最终原始证明；生产 Java 已通过 PolkadotSubmissionService 预约原网络、nonce、era 和签名意图，持久化选定原文后广播；正式提现、归集、原文恢复、最终事件与费用及充值扫描均已完成真实节点验收。公共 helper 对历史事件使用执行区块父状态的 registry，并排除费用 Deposit/Endowed 事件。polkadot_transaction 与对应单表 Repository、架构边界检查和唯一 init-sql 已同步。

协议依据为 [Polkadot SDK extrinsic encoding](https://paritytech.github.io/polkadot-sdk/master/polkadot_sdk_docs/reference_docs/extrinsic_encoding/index.html) 与 [Polkadot protocol specification](https://spec.polkadot.network/assets/files/Polkadot_Protocol_Spec-1931296056ed03dd221ec8bb954c8f27.pdf)；当前实现固定 V4，签名原文不超过 256 字节时直接签名，超长按协议 Blake2b256。

## Cardano 原生 RPC / MPC 交易接入（业务实链验收通过）

CardanoTransactionService 和 CardanoDepositScanner 已统一使用原生 cardano-node UTxORPC v1beta，移除旧 Blockfrost 业务客户端及私钥交易验收入口。协议固定在 cardano-rpc 11.2.0.0 / cardano-api 70cb17639e8e704bcf06969ca2a42ed45ca5d680，保留 Apache-2.0 许可证。ChainRpcNodeService 继续负责公开 RPC 的配置、认证与故障切换；不增加索引服务或索引数据库。FetchBlock 可按 hash 解析原始 slot/height；完整引用保留并核对 slot/height/hash。hash-only 响应的辅助 timestamp 在原生 11.1.3 中不可靠，客户端将其清除，金融有效期使用原始 slot。

地址发布之前，CardanoScanAnchorService 在事务外读取 genesis，沿已验证原始 parentHash 回溯已确认祖先，并检查 FollowTip 交点；DKG 之后，在地址事务内同时发布锚点和公开地址，Gas 系统地址也走同一路径。地址投影失败回滚锚点和公开地址，完成的 DKG 保留以便相同身份重试。已经发布的地址幂等读取不再次准备锚点。当前原始证明解析支持 Conway，锚点构成明确的部署边界，不从旧时代或收款之后的 tip 开始扫描。

CardanoPublicTransactionBuilder 用同一公开 UTxO 快照、当前参数和联合公钥构建未签名 CBOR。MpcCardanoTransactionCodec 校验精确本金、policy/name、原始输入、全部资产守恒、找零、最低 ADA、交易/Value 大小、单 witness 费用及 TTL；Blake2b-256(body) 交外部 Ed25519 MPC，Java 只验签和追加 witness。扫描起点是读取输入快照之前取得的已确认祖先，其 slot 不得晚于原交易 validFrom。原生金额使用六位精度。

CardanoSubmissionService 在租户业务行锁和联合密钥锁内，原子持久化请求、已知 body hash 与 sender 待处理记录。所有资产共用一个 sender 的未完成输入域，快照起点不能早于该 sender 最近终态高度。远程 MPC 不持有数据库事务；首个 witness CBOR 和业务 hash 必须在 RPC 前提交。恢复先于新任务和 Token 开关，始终使用原请求和原文；重复广播被拒绝、响应丢失和超时均不能直接退款。新签名前检查本金、收款最低 ADA、剩余资产最低 ADA 与费用预算；不足部分由租户自身 MPC Gas 地址的固定 FUNDING 意图补充，不生成 Java 私钥。

CardanoExecutionReceipt 保留原始 header/body/witness 字节切片，核对 Praos header 哈希、slot/height、四段 body 长度与承诺、原付款 witness、有效期和实际费。承诺依据 [官方 hashAlonzoSegWits](https://cardano-ledger.cardano.intersectmbo.org/cardano-ledger-alonzo/src/Cardano.Ledger.Alonzo.BlockBody.Internal.html)。规范链来自原生 FollowTip，不能视作完整 PoS 轻客户端。确认从持久交点连续、有界地搜索；只有达到受理深度的原始付款证明，或完整搜索越过 TTL 且所有原始输入仍未花费的证明，才结算成功或零费失败。链事实、冻结扣款/退款和业务终态同事务提交；重复结算不再次动账。

cardano_transaction / CardanoTransactionRepository 仅存对应单表：请求租户复合外键、固定 hash/首个原文、CAS 搜索点、不可替换证明及实际 fee_lovelace/native_expense_lovelace。cardano_owned_output / CardanoOwnedOutputRepository 仅保留钱包输出引用、租户/地址、原始输出字节及区块身份，用于识别已消费的内部输入，不复制全链数据。复用 chain_scan_height.genesis_hash 与 chain_scan_block.block_slot，新增两张表和两列已同步唯一 init-sql；本轮入口切换没有新增结构，init-sql 中原 Blockfrost 节点种子已替换为禁用的原生 gRPC TLS 占位配置；启用前填写实际原生端点和显式认证，不保留专属认证兜底。

充值按已确认原始区块逐块处理，原始输出索引加资产序号作为幂等标识，ADA 固定序号零，其他资产按 policy/name 排序，停用和未配置资产仍占序号，避免配置变化重排。失败 Plutus 交易只处理实际 collateral return，忽略未创建的普通输出；输出字节保留原片段。内部输入用精确 txHash:index 识别，包括已停用地址；内部找零、归集和补充不入账，明确提现到另一个托管充值地址允许收款入账。外部源地址未知时保留空值与实际输入引用，不伪造来源。

每个区块的充值记录、余额、强制租户投影、钱包输出历史、原始区块头和游标同事务提交。既有充值重组核对在新扫描开关之前执行：FollowTip 从存储头中选择精确公共 slot/hash，冲回公共点之后的充值、处理赤字、删除孤块输出/头并回退游标，同事务完成。replacementBlockHash 留空并审计真实公共点，不把祖先 hash 冒充同高度替代块；超过一千个已存交点则停止并要求人工核对，不静默重置。

Gas 对账读取原始证明的实际 native expense，不回退估计。Token 对外提现的支出包含最低 ADA 与链费；内部归集只计链费；FUNDING 使用既有 reservePreparation/settlePreparation。明确零费保留审计记录。原生归集按实际剩余资产最低 ADA 和费用预算保留余额，Token 可全额归集，所有链上付款均由联合密钥签署。

公开组件、持久化、地址发布和正式业务的三组真实 MPC 已完成本阶段验收。实际验收复用官方 11.1.3 隔离测试账本和仓库外 0600 描述符 MPC_CARDANO_TEST_FIXTURE；管理员签名文件只留在受保护测试节点卷内，Java 仅获取公开结果。请求的实际业务验收必须同时产生 cardano-codec-passed 与 cardano-business-passed。

真实正式业务验收已通过：统一 native CTest 为 206.44 秒，AB/AC/BC 九笔 ADA/USDT/USDC 提现包含最小 Token 原子单位、每笔真实回复丢失后关闭新任务/Token 的原文恢复、相同原文重放；租户 Gas 由原生扫描自动入账并使用真实 MPC FUNDING 补充。提现到另一个托管地址只增加一次收款投影，内部找零/补充/归集不重复入账。另有一笔实际 MPC 付款延迟越过 TTL，被节点拒绝后通过完整规范链搜索和原输入未花费证明完成零费退款。三类真实归集中 Token 全额到零，ADA 保留最低余额与费用。受控初始加追加资金 220000000 lovelace，外付 99996186，最终受控余额 119997635，差额精确等于全部实际链费 6179 lovelace。新增扫描/RPC/架构定向回归 26 项通过，包含 12 项真实 PG18 事务测试，无失败或跳过；完整相关回归 162 项通过，无失败或跳过，耗时 2 分 41 秒，覆盖 Cardano、充值/地址、Gas、工作流、启动配置与认证。Cardano 业务验收通过不代表整个全链目标完成。

## XMR 原生多方协议与地址验收

XMR 经用户确认使用官方 Monero 0.18.5.4 原生 2-of-3；原生协议留在独立签名节点，不能使用普通 Ed25519 签名替代。每租户一个 root 多方钱包，每托管地址对应 major > 0 / minor 0 的独立账户，账户内选币和找零保持同一公开地址。生产须等待实际解锁，原生 -37 不能被解释为付款失败或触发重复补款。

`src/monero.cpp` 连接本机 loopback wallet-rpc，私有 view/spend 材料、钱包文件和加密保存的钱包口令留在节点边界。Digest 凭据必须为本机所有者 0600 普通文件；无认证仅限显式 regtest 夹具。适配器支持 MD5-sess、nonce 计数、持久连接及与实际 HTTP 路径绑定的摘要认证。三个建钥方须同意 daemon nettype、genesis、账户和地址；regtest 必须是 offline fakechain。子账户按三方数量上界补齐未发布空位后分配；已注册钱包文件缺失时停止，不重新生成。

SIGN 使用不可变租户/密钥/会话和 PAYMENT 策略。两方刷新并交换 multisig info，指定方在所选账户内构造 `do_not_relay` 交易；两方分别核对真实 ring 输入的公开输出键、global index、金额和账户作用域，以及精确收款、解锁时间、ring size、费用上限和原地址找零。审核摘要一致后联合签名，再核对最终描述，固定同一 txset、哈希、费用和策略摘要并加密持久化。SIGN 不广播；最终 opaque txset 仍由原生私有查看密钥加密。

`MONERO_RELAY` 只接收已完成 SIGN 的会话、原意图摘要和固定哈希，由任一原签名材料持有者读取本地加密 artifact 后广播。调用方不能注入替代 txset。广播前后查询原哈希，未知保持 INDETERMINATE；已确认重放返回原交易。`MONERO_OBSERVE` 由两个注册节点执行，绑定规范区块高度/哈希，刷新私有钱包并同步 key-image 信息，返回公开 owned output 事实、账户余额和指定交易的原规范块包含证明。查询前后须为同一链视图，两方须同意同一结果摘要。缺失交易只是观察结果，不是退款证明。两个操作各有明确的客户端授权。

Java `MpcClient` 支持上述原生协议，独立核对公开 spend/view key 与地址、不可变请求摘要、精确收款/金额/费用/找零、区块锚点和 uint64 金额。`MoneroPublicAddressCodec` 校验 CryptoNote 分块 Base58、Keccak、网络和规范公钥点，不接触私有查看密钥。原生 SIGN 须取得两个相同且经各自 SPKI 客户端认证的结果，随后加密保存原 artifact 与已认证参与方，读取时再次校验。未知 Monero 签名禁止通过普通重放产生新交易；须保留原会话和输入，原生中断阶段仅恢复原材料，完整业务和缺失材料保护的实际验收见下文。

设置 `MPC_MONERO_BIN_DIR` 启用 `mpc_monero_native`；再设置 `MPC_JAVA_TEST_CLASSPATH` 时，全部正向操作由实际 Java 客户端驱动。最新 Java/原生联调 4/4 通过，196.74 秒（XMR 186.95 秒）：AB/AC/BC 第三方 signer/wallet-rpc 离线、三方建钥与地址分配、两方签名、一名持有者广播、固定结果重启恢复与重放、原区块确认、两方账户查询，以及篡改哈希/金额、缺少第二份认证结果、错误锚点/网络/凭据/私有文件等检查。25 XMR 减去 3 XMR 外付和 0.0054252 XMR 实际费用，余额为 21.9945748 XMR。

本批同步 init-sql 的算法约束为实际 wire 名 `MONERO_NATIVE_2_OF_3`，替换未使用的旧占位名，保留 128 字符公开 spend/view key 约束。跨链 PG18/业务回归共 234 项，通过独立 Hardhat 节点补跑 EVM 条件用例后，最新选定报告均无失败、错误或跳过；数据库夹具与真实 MPC 组件分别验证。这一阶段仅验证原生协议和地址；后续充值、正式出款及原材料恢复的实际验收见下文，当前整体验收范围见全链接入清单。

通用签名密文的参与方绑定另经实际钱包业务回归验证：PG18 + 三个独立原生节点 + 本地 EVM / BTC / LTC / DOGE / BCH，66.16 秒通过。涵盖 27 条链地址建钥、原子 Gas 发布、待完成 DKG 恢复、普通 legacy/1559 提现，以及四条 UTXO 链 AB/AC/BC、正式 PGMQ 签名、原文/哈希提交后广播、丢响应同字节恢复和本金一次结算。此回归不涵盖 XMR 钱包业务和 EIP-7702，各自验收见对应章节。

## HyperCore 真实账本扫描与资金验收

真实现货充值使用 userNonFundingLedgerUpdates 的 send（destinationDex=spot）与 spotTransfer；保留原 hash 与公开 ledger 明细，不从余额差额制造充值。HyperCoreLedgerCursorRepository 只访问 hypercore_ledger_cursor，按 (tenant_id, network, chain_address_id, asset_symbol) 保存包含边界时间戳的独立 USDC/HYPE 游标。公开 API 读取在事务外；充值、账本、必需租户投影和游标同事务。500 条保守分页阈值遇到无法推进的同时间戳边界时停止，不跳过记录；同名 Token 元数据歧义时拒绝猜测。旧快照仓储与表已删除，init-sql 同步当前字段、主键和外键。

自有发送方必须能关联原 tenant/request/nonce、不可变签名意图和业务单。内部归集跳过二次客户充值，普通提现到托管客户正常入账一次；未知自有转出阻止游标推进。原生地址身份保持不变，HYPE 使用同一地址的独立资产游标。已观察入账使用完整网络 Token 配置与持久地址归属，不受 Token/地址停用或新入口开关影响。API 时间不冒充区块高度/区块哈希。

验收复用 checkout 外已保存的 source/collector 原生 key、SPKI、AES 与同一本机 PG18 的明确命名测试库，不重建已充值身份。USDC 三组正式提现、全部真实 API 回复丢失后的原 nonce 恢复、三组实际归集与内部重扫不重复入账已通过；每组实际停止第三个原生进程。干净构建的跨链回归 291 项通过，无失败、错误或跳过，含自有本地 Hardhat 的真实丢广播回复条件用例。USDC/HYPE 各三组提现、归集和托管客户实际收款/重复扫描通过，共 21 笔已确认业务（15 笔提现、6 笔归集），9 条真实充值（6 条 USDC、3 条 HYPE）。USDC 的实际丢回复原 nonce 恢复已通过；每笔确认后恢复不再发出付款。此次 HyperCore 验收不代表整个全链目标完成。

HYPE 测试资金准备已通过：0.07 HYPE 买单实付 11.7264 模拟 USDC，实际 HYPE 费用 0.000049，净收 0.069951；重跑原订单未重复购币/记账。测试辅助模块使用可选 hypercore_test_funding.py 与官方 SDK 0.24.0，在测试网以原生 AB 签名提交最多 15 模拟 USDC 的固定 IOC 买单。原订单、nonce、会话和签名先保存；按官方 cloid/oid、逐笔成交和实际费用核对后，测试账本的 USDC 支出、净 HYPE 收入与审计流水一起幂等提交。该辅助流程未增加生产下单接口。hypercore_funded_test.py 的业务引用绑定显式测试批次和原付款角色；同一批次/角色/阶段的重试复用原请求，新批次用于实际新转账验收。procurement 保留原 AB 资金准备记录，HYPE 与 USDC 业务均由三组正式签名组合验收，不覆盖或重建已充值身份。


XMR 原生地址发布已接入：Java 的 MoneroDaemonClient 只通过 purpose=daemon 读取 get_info/规范区块头，复核实际 nettype、离线 fakechain 和 genesis；Digest 编码由 MoneroDigestAuth 与开发资金客户端共用，Java 不调用原生签名节点的私有钱包 RPC。MoneroScanAnchorService 在事务外准备已确认起点；MpcSigningService 先预约每租户唯一 XMR_ROOT，再以原 rootKeyId 和明确网络/genesis 预约 XMR 地址账户，三方结果一致后激活。CustodyAddressService 将原生子账户公钥地址、XMR_NATIVE 扫描锚点/原始头、租户归属与审计在同一事务发布，Gas 地址复用该路径；失败重试保留原根/子账户身份，不重新建钥。地址阶段已通过真实 Java/三原生节点验收：公开 daemon Digest、根与子账户建钥、客户/Gas 地址发布、发布回滚后复用原身份，以及三个签名节点和三个私有钱包重启后复用原根/地址并创建新子账户；重启后共四个地址，三方私有钱包账户一致。定向 Java 回归 42 项通过，无失败或跳过。该段结论仅覆盖地址发布；后续 SIGN/扫描/提现/归集和中断恢复的完整通过结果见下文。

该阶段复用 wallet_mpc_key / wallet_mpc_key_epoch / chain_scan_height / chain_scan_block，不新增私钥字段或业务表。init-sql 同步现有 daemon 用途说明及默认关闭的 stagenet/mainnet daemon 配置；旧 Java wallet-rpc 付款路径已在完整业务切换和旧签名清理中移除；Java 只访问公开 daemon 与独立原生签名服务。

XMR 扫描事实边界已补充并完成真实 Java/原生联调：MONERO_OBSERVE 保留输出的原交易 outputIndex、globalIndex、公开输出键、实际确认数、coinbase、unlockTime、spent/frozen/unlocked；每个 owned output 均核对 daemon 原交易的输出位置/键/全局序号及原规范块包含关系。普通交易使用 tx_hashes，矿工交易使用 miner_tx_hash 与原 gen.height；Java 独立检查位置唯一、事实类型、锚点与确认数。未解锁输出继续返回事实，不因过滤而丢失；是否可入账将在钱包扫描流程中显式判定。更新后的真实 Java/原生 CTest 4/4 通过（197.89 秒，XMR 188.55 秒），新增 6 项 Java 边界测试通过；包括外部充值 1 个确认未解锁、13 个确认原输出解锁、原输出序号/全局序号核对、矿工交易独立包含证明，及三组两方付款/广播/恢复回归。该观察边界修改不新增表、字段或初始化配置；正式扫描与出款的后续验收另述。

Java 钱包观察入口已接入并完成实际节点联调：MpcSigningService.observeMonero 在事务外根据租户原 XMR 密钥和已激活 epoch 核对公开地址、network/genesis 与原参与方，向两个原生持有者读取绑定规范锚点的同一结果；返回公开请求/结果供扫描审计，不生成付款意图或签名。已暂停租户仍允许查询其已有密钥，便于已观察资金完成确认/重组处理。调用方不得在数据库事务中发起该 RPC。49 项定向 Java 回归通过，无失败或跳过；真实 Java/原生节点验证了暂停租户读取原密钥、禁止事务内 RPC、两方一致结果，以及三个签名节点/私有钱包全部重启后仍读取原账户。该次验收只覆盖观察入口，正式付款、归集与原材料恢复的后续验收见下文。

XMR 正式充值扫描已改为原生两方输出事实并完成真实验收：MoneroDepositScanner 不再调用 Java wallet-rpc，而是通过 MpcSigningService.observeMonero 核对规范锚点、原交易 outputIndex/globalIndex 与公开输出键；原生节点从同一租户多方钱包的已确认自有付款记录识别 sourceAddress/change，找零不记作充值。每批原始区块头、充值、强制租户投影、账本、事件与 XMR_NATIVE 游标同事务提交；事务内重新核对游标原哈希和已发布地址集合，防止重组或新增地址竞争导致跳扫。

锁定输出保留实际确认数，DepositService 以明确的 unlocked 准入条件延后入账；待确认行继续保存原输出键/序号和金额，解除锁定或冻结后按原身份重读并只入账一次。confirmObserved/reconcileObserved 依据已发布密钥的原网络配置运行，不依赖新扫描、链、租户或地址开关；串行 Monero 工作流在启用 profile 筛选之前维护已观察资金。规范链交点、充值冲正、租户赤字/事件和游标回退共用事务，找不到有界交点时停止。

该阶段复用 deposit_record 与现有扫描头/游标，CONFIRMING 保存真实深度和原输出审计，不新增表、字段、状态或初始化配置，因此 init-sql 无变化。已删除依赖单钱包私钥 RPC 和非隔离数据库默认值的旧 MoneroRegtestFullFlowIntegrationTest，替换为隔离 PG18 原子边界测试和真实独立节点/原生钱包扫描验收。本段仅描述充值侧验收；原生付款签名、广播、提现/归集和中断恢复的完整通过记录见后续业务章节。

XMR 锁定充值的成熟度刷新按 deposit_record.updated_at/id 取最久未复核的 500 行；未解锁输出在保留原事实后更新时间，使后续已成熟充值也能进入复核批次。该查询仍属于 deposit_record 单表仓储，不改变其他链的既有待确认查询，不新增字段或索引。待确认并不要求确认数继续增加，解除本地冻结也可按同一原输出入账。

XMR 充值侧真实验收通过：run-monero-flow.sh 使用官方 Monero 0.18.5.4、三个独立签名节点与原生私有钱包、仅 daemon 用途的 Java RPC 路由和现有本机 PG18 内的隔离测试库。AB/AC/BC 每组均实际停止第三方 signer/wallet，验证 1 个确认保留锁定输出、13 个确认在关闭链/租户/地址后按原输出入账、重复确认幂等、同一交易向两个托管地址付款的原输出序号不冲突、Gas 原生资金自动投影。强制租户投影失败时本金/充值/投影整体回滚，重试仅记一次。两份原生钱包总余额均与账本的累计 3 XMR 一致。

随后在同一离线 fakechain 回退最后 13 个块、清除原测试付款并挖出 14 个替代块；真实规范块哈希变化触发原 Gas 充值一次冲正，账本与两份原生钱包总余额均回到 2 XMR，第三次入账保留 REORGED 审计。最新原生 CTest 4/4 通过（202.40 秒，XMR 191.63 秒），覆盖自有找零来源标记与三组原签名/广播恢复；Java 相关回归 76 项通过，新增成熟度公平轮询后最新 25 项复验通过（12 项原子充值/重组、7 项公开事实、6 项架构），均无失败或跳过。该结论仅覆盖充值侧；原生付款/提现/归集和中断恢复的实际验收另述。

独立重跑充值侧验收：设置 MPC_SIGNER_BIN 指向本分支编译的 wallet-mpc-signer，MPC_MONERO_BIN_DIR 指向官方 0.18.5.4 二进制目录，然后执行 bash resources/scripts/regtest/run-monero-flow.sh。默认先编译/测试 Java；测试调用方可传入刚验证完成的 MPC_WALLET_TEST_CLASSPATH。脚本不依赖 Docker，不读取或重置开发库，结束删除自身临时测试库及链/钱包进程。入口依次运行充值/重组、正式出款/Gas 和原签名恢复三个独立场景，只有全部完成才输出完整流程通过。

XMR Java 原签名广播桥接已接入并完成真实验收：MoneroSigningPayload 固定 network/genesis、源/目的、12 位精确金额、客户手续费、原生费用上限与确认策略，PAYMENT 原文使用排序 JSON 和字符串原子金额。MoneroSignedPayment 从已加密持久保存、经两个原参与方认证的签名材料重新验证原签名会话/意图、实际费用和 artifact 摘要，仅公开选择身份，不复制原 txset。

MpcSigningService.selectedMonero/relayMonero 只读取已完成的原请求和加密结果；调用方预期哈希必须相同。MONERO_RELAY 只引用原 SIGN 会话/意图/哈希，可在任一原材料持有者上执行；两名原持有者以外的节点不接收广播请求，部分离线或未知时保留原交易。广播 RPC 禁止运行在数据库事务内，恢复不会新建签名尝试或重新生成付款。业务调用方仍须先绑定原签名选择与业务哈希，再调用广播桥接。

本轮桥接验收已通过：跨 Java 进程保留同一受保护的协调器 AES 密钥，三组真实双节点 SIGN → 数据库加密结果 → 全部原生进程/私有钱包重启 → 仅一名原签名持有者在线广播 → 两方查询原规范块与本金/费用 → 另一名原持有者重放原哈希。该测试是签名持久化与广播桥接，不能代替提现/归集业务预约、Gas 资金准备和结算验收。无表/字段/状态/初始化配置变更，init-sql 无影响；正式 MoneroSubmission 业务与原签名中断恢复由后续独立真实流程验收；不能以本段桥接通过代替这些场景。

签名桥接已实测协调器未收取结果场景：先持久预约原请求并由 Java 受理到两名节点，Java 在收取签名结果之前退出；原生节点完成原会话后全部重启，新的 Java 进程仅按原请求收取既有两份结果、加密保存并读取同一选择。它验证的是协调器未收到已完成结果的恢复，不宣称可自动恢复所有原生签名内部中断阶段。

Java 原签名桥接最新定向回归 32 项通过（含 7 项付款策略/选择边界），无失败或跳过。三组真实 Java/原生/PG18 签名持久化、同 AES 协调器重启、两方结果绑定、仅一名原持有者广播和另一名原持有者确认后重放均通过；每笔始终只有一条原 SIGN 尝试。实际受控 5 XMR，外付 3 XMR，实际费用 0.0054252 XMR，原生源余额 1.9945748 XMR，整数原子单位严格守恒。协调器未收取已完成结果的补验也保持原会话、原哈希和原请求。该结论只覆盖持久签名/广播桥接；正式业务和可恢复材料边界的后续验收见下文。

## XMR 原生出款业务当前实现与验收

MoneroTransactionService 编排正式提现、归集、手续费资金准备与已受理交易恢复；MoneroSubmissionService 负责租户业务行和联合密钥的锁定、不可变 MPC 请求、原签名选择、广播屏障与结算。Java 托管路径仅使用 purpose=daemon 的公开链 RPC；原生私有钱包操作留在独立签名节点。旧 Java MoneroWalletRpcClient / MoneroAddressService 已移除，普通 Seed 地址入口拒绝创建 XMR 地址。启动时 XMR 只要求 daemon 路由。

monero_transaction 单表仓储按 tenant_id/request_id 绑定 wallet_mpc_sign_request；selected_attempt_id 通过复合外键绑定同一租户原请求的签名尝试。原会话、artifact 摘要、交易哈希和实际原子费用一旦选择即不可修改；原 txset 只保存在已加密的 MPC 结果中。SIGNING → PREPARED → SENT → CONFIRMED；原签名选择和提现/归集业务哈希在同一事务提交后，才允许事务外 MONERO_RELAY。未知结果保留原请求和冻结本金；已受理意图不释放为未签名重试，不生成替代付款。pending 查询按更新时间公平轮询，既有付款恢复与确认先于链启用条件。

主付款意图先持久化，再按主业务的固定引用预约租户 MPC Gas 补款。补款只补手续费及正找零预算，不补客户本金；已存在补款优先恢复，原生资金未解锁时等待，不重复补款。补款金额加费用上限先预留，确认后只把实际链费作为费用结算，仍在租户钱包内的补款本金不重复计费。正式提现确认使用原请求的网络、密钥、哈希和确认策略；两名原生持有者给出一致规范交易事实后，确认凭据、本金结算和业务状态共用事务。自有来源收款必须关联原请求：内部归集/补款不重复记客户充值，普通提现到托管客户仍记一次充值，原地址找零继续跳过。

本轮已修改 init-sql 的 monero_transaction 目标结构、租户/请求/签名尝试外键、唯一约束和不可变触发器，并移除旧托管 wallet-rpc 初始化路由；开发资金 faucet 路由保留为测试资金来源。最新业务切换已完成编译与 114 项定向 Java 回归（wallet-api 107、common 7），无失败、错误或跳过，其中 13 项使用隔离 PG18 验证出款原子边界。真实 Java/原生/PG18 完整业务验收已通过：AB/AC/BC 每组实际停止第三方签名节点及私有钱包，提现、归集、丢失广播回复、协调器/原持有者重启、关闭链入口后的原请求恢复、原规范确认和只结算一次均通过；普通提现到自有地址新增一次充值，归集/补款与找零不重复充值。共 8 笔业务付款和 1 笔补款，9 笔规范确认，待处理为零，每请求仅一条原签名尝试。初始受控 10 XMR，外付 4.5873412 XMR，全部实际链费 0.0170664 XMR，余额 5.3955924 XMR，与两份原生钱包一致。补款仅一笔，实际 Gas 费用 0.0018084 XMR，Gas 账本为 4.9981916 XMR，预留完整释放，补款本金不重复费用化。测试库和原生进程已清理。此前充值/解锁/重组实测与持久签名/广播桥接实测各自成立，不能替代这次业务切换验收；原材料中断恢复另有后续验收；材料缺失的情况继续保留未知，当前整体验收范围见全链接入清单。

XMR 多输入审核已补充原输入集合的规范比较：官方 Monero 0.18.5.4 的联合签名重建入口会重新按 key image 排序 sources（[官方源码](https://github.com/monero-project/monero/blob/v0.18.5.4/src/multisig/multisig_tx_builder_ringct.cpp#L806)）。原生适配器仍在签名前后分别验证每个输入的所有权、公开键/承诺、全局序号、金额、未花费与解锁状态，再只对 sources 数组排序后比较完整描述；收款人、找零、费用、金额、ring、unlock、extra、weight 等字段均保留严格比较。未放宽原输入集合，也不重新选币或生成替代付款。新增原生策略测试先证实旧比较拒绝同一输入集合的重排，再覆盖输入替换/删除/重复及付款字段变化；原生策略与真实 Monero 协议组件 2/2 回归已通过（153.38 秒）；完整业务重跑已通过，包括连续三组付款后再补款的多输入主付款；原生签名中途退出的恢复与材料不足时的未知保留已由下节实际验收。此次原生摘要规范化不改变数据库字段或初始化配置。

## XMR 原签名材料恢复与未知状态验收

仅 MONERO_NATIVE_2_OF_3 的原 SIGN 可在 INTERRUPTED/INDETERMINATE 后再次提交同一描述符；仍使用同一租户、key/epoch、session、意图和两个原参与方，不新增签名尝试。其余 cb-mpc 算法的未知签名不得重新提交。恢复获得运行回执时仍保留已观察的未知状态，直到两个一致完成结果被验证，不把未知签名降级为普通签名中。原生 SIGN 首次 RPC 前先将原尝试 PENDING → RUNNING 提交为调度屏障，避免节点已受理而协调器未保存观察结果就退出的空窗；该标记不是节点受理确认。MpcSigningService.hasStartedMoneroSignature 根据原尝试状态判断是否已进入签名；一旦进入，MoneroTransactionService 只恢复原材料，不再依据余额重新补款或准备输入。

原生节点在签名前把选定输入的公开所有权/锁定事实与已批准描述一并加密持久化。恢复优先读取本机原 artifact 或原 cosigned txset；原发起者可经固定 SPKI 的内部 /v1/monero-recovery-material 向原第二签名者读取既存材料。该内部接口只允许原 online[0] 调用原 online[1]，必须与后者已保存的完整 SIGN 请求逐字一致；协调器、第三方持有者、其他租户/key/session 或变更策略均拒绝。第二签名者必须保留自己的原 RPC 结果，不能靠发起者声称其已签名。

恢复只打开原账户并使用 describe_transfer，按本机加密保存的原输入事实重新检查交易及完整描述；不刷新/导出签名随机材料，不调用 transfer/sign_multisig，不选择输入、不广播。原材料不足时继续未知，冻结本金保留，不替换付款。恢复成功写入独立的 recovered-result 加密不可变记录，查询优先读它；旧 INDETERMINATE 结果及原请求保留，不覆盖审计。Java 收取两个一致的原会话结果后，继续既有选择、业务哈希绑定、广播和规范确认流程。

本轮未新增表、字段、业务状态或配置项；init-sql 无额外影响。最新恢复代码已完成编译和 119 项定向回归（wallet-api 112、common 7），无失败、错误或跳过，其中 18 项 PG18 用例覆盖出款/恢复边界，并在首次节点访问前验证签名阶段屏障已提交。真实 Java/三原生节点/官方钱包中断验收已通过：AB 从原 cosigned 材料及原未知结果恢复，AC 从原 artifact 恢复，BC 从一方完成、另一方结果写入失败的原会话恢复；旧结果字节不被覆盖，每次仍是一条原签名尝试。每组实际停止第三方并重启原持有者/私有钱包和协调器，单原持有者广播、原区块确认及另一原持有者重放通过。内部读取的协调器、第三方、反向持有者和变更描述符均被拒绝。恢复期间原 transfer/sign_multisig/export/import 调用次数不增加。

原材料缺失的两类实际中断也已验证：构造回复返回但本机审核未落盘，以及钱包已签名但 RPC 回复和 cosigned 材料未落盘。两类均保持原请求/原尝试 INDETERMINATE，不伪造恢复结果，不重新签名或广播。后者不能自动重建已经丢失的签名材料；保持冻结并核对/恢复原材料，禁止以重签、恢复旧随机数缓存或手工退款代替原交易核对。完整验收初始受控 9 XMR，真实外付 3 XMR，实际费用 0.0054252 XMR，三个独立钱包余额均为 5.9945748 XMR；原观察接口负责重启后的钱包打开与事实读取，测试不提取私有存储中的口令或密钥。

run-monero-flow.sh 现在分别在现有本机 PG18 的三个隔离测试库中运行充值/重组、正式出款/补款和原签名材料恢复/未知保留，结束删除自身测试库及临时链/钱包进程。[运行与恢复说明](OPERATIONS.md) 记录原材料缺失后的核对和备份边界；Linux 发布产物和完整同机冷恢复已完成独立验收，范围见下文；HyperCore 原未决转账使整个全链最终验收仍未关闭。

统一入口已在同一代码和原生二进制上完整通过：充值/真实替代链重组、9 笔正式出款与补款、3 类原 SIGN 材料恢复和 2 类缺失材料保留未知，均要求三组双节点组合及实际停止第三方。完整业务净余额为 5.3955924 XMR，中断恢复夹具净余额为 5.9945748 XMR，分别与其实际外付和全部费用一致。三个自有 PG18 测试库和全部临时原生进程均已清理；底层全套 CTest 另有 5/5 通过。此 Monero 结论不外推到 cb-mpc 份额刷新（该独立验收见下文）、跨机器备份恢复或其他链的剩余业务。

## EIP-7702 外部 MPC 签名编码组件

MpcEvm7702Codec 提供绑定具体链、原 authority/delegate 与 uint64 nonce 的授权摘要和外部签名组装，重用既有 EIP-712 归集/提现摘要；中继 unsigned type-4 保留原内层授权，已有委托时使用 type-2。授权 nonce 必须小于 2^64 - 1，拒绝跨链通用授权、零 delegate、重复 authority 和不规范的外部授权。规则按 [EIP-7702](https://eips.ethereum.org/EIPS/eip-7702)；支付组件不提供任意委托撤销入口。

SDK MpcEvmTransactionCodec 的既有 DER 验签、low-S 和恢复 ID 逻辑抽出 signatureData，供交易和 EIP-7702 内层签名共用。它不接收私钥，旧交易组装继续使用相同验签逻辑。组件测试使用明确的本地 ECDSA 替身，另有 ethers 独立 RLP 摘要向量；不得将其计作 MPC 密码学或正式钱包业务验收。

真实组件入口为 `tests/evm7702_codec_test.py /path/to/wallet-mpc-signer`，需要从刚通过的 Maven 报告设置 MPC_WALLET_TEST_CLASSPATH，并安装 evm-fork 的固定 npm 依赖与 Anvil。它启动隔离 Prague 节点、实际部署现有合约，分别停止第三签名节点，要求 AB/AC/BC 完成授权、EIP-712 请求、中继三类真实 MPC 签名；每组各执行 type-4 激活和 type-2 复用的归集/提现，共 12 笔原生币交易，检查精确本金、实际费用和同字节重放。该组件已实际通过；三组共外付 0.12 个测试原生币，中继实际费用合计 0.001343680114792748，与链上余额完全一致。定向 Java 回归 31 项通过，无失败、错误或跳过（wallet-api 29、SDK 2）；包括既有交易组装、授权规则、持久化和架构边界。测试库、原生节点与 Anvil 已清理。

上述独立编码组件不承担正式 Evm7702CollectionWorkflowService / Evm7702WithdrawalWorkflowService 的持久预约与恢复；正式工作流已另行接入 MPC，见下文。上述编码组件本身只提供公开编码/验签及隔离探针，不改变模块依赖、调度、正式核心业务数据流、仓储或数据库结构。后续原批次领取凭据的数据库与架构变化另述如下。


## EIP-7702 原批次领取与恢复读取

两种正式批次现在于原领取事务内持久化完整公开 Batch，写入对应单表的 claim_payload JSONB，与条目和业务领取一起提交。原租户、UUID、链/网络、资产、金额、配置版本、期限、地址与密钥 ID 保留；无私钥或 RPC 认证信息。数据库要求对象及原身份匹配，并禁止更换原快照和批次资金身份。Coordinator.claimed / listSigning 提供原 LOCKED/SIGNING 批次读取，不依赖当前启用配置重新组装条目。

本机 PG18 定向回归 54 项已通过，无失败、错误或跳过；新增 6 项覆盖重建应用对象后的原快照读取、租户隔离、关闭新业务后的读取、原事实不可篡改及非法 JSON/归属拒绝。原未知广播恢复、Gas 账务和编码/架构检查同时通过。唯一 init-sql、AGENTS、架构及流程图文已同步；测试库已清理。

正式业务的内层/中继 MPC 预约、事务外签名、固定期限与 nonce、广播前原文及 Gas 提交和恢复调度已接通；下文记录独立真实业务验收。平台中继必须拥有明确的 MPC 密钥租户，不能保留原空归属 Seed 路径；租户内层请求与平台中继请求需各自按其密钥归属保存，并显式绑定原业务租户/批次。


正式 EIP-7702 工作流代码现已接通 Evm7702SigningService，使用原批次/固定准备数据和已有 MPC 元数据表：内层请求与 SIGNING 先提交 → 事务外 MPC → unsigned 费用估算 → 中继意图与 Gas 同事务 → 事务外中继 MPC → 原文/哈希/恢复凭据提交 → 广播。平台中继具有明确密钥租户，业务引用含原业务租户/批次。原数据独立重建后方可预约中继签名；恢复不受新业务开关影响。62 项首轮定向检查与后续 25 项恢复/费用/架构检查通过，真实批量业务另以三组完整原生节点场景验收。

正式业务验收入口为 `MPC_SIGNER_BIN=/path/to/wallet-mpc-signer bash resources/infra/evm-fork/scripts/run-local-7702-flow.sh`。入口通过 `tests/evm7702_business_test.py` 为 AB/AC/BC 分别创建独立原生节点、Prague 节点及本机 PostgreSQL 18 临时测试库；每组全部真实 DKG 完成后实际停止第三签名进程。`Evm7702ProductionFlowIntegrationTest` 的中继和客户地址均发布真实租户 MPC 密钥，资金和合约部署只使用隔离节点的公开水龙头账户，不再生成 Java 钱包 Seed 或本地签名。

每组场景要求原生币、USDT、USDC 两租户共 12 笔正式归集/提现交易、10 张提现订单最终结算，以及部分条目失败重试、幂等创建、Gas 预留和实际费用。真实 RPC 门在转发广播前读取已提交的原业务、加密原文、选定 MPC 结果及 Gas 预留；归集和提现各丢弃一次真实已受理广播的 HTTP 回复，并核对丢弃前的受理哈希。随后重启两名原持有人并核对原生持久文件未变，重建 Java 应用服务，在链、资产、地址和批次配置停用期间恢复原单；不得新增 MPC 意图或 attempt。额外收款场景将真实提现打入另一租户的 MPC 客户地址并重扫三次，要求归属正确且只入账一次；全部业务区块重复扫描、已完成 Job 重放不得增加内部归集入账、费用或签名。最后核对中继实际余额/nonce、冻结本金、租户手续费流水和原生币/Token 余额。只有三组 Maven 与完整公开结果全部通过才生成 `MPC_EVM_7702_BUSINESS_PROOF`（默认 `/tmp/surprising-wallet-mpc-7702-business-public.json`）。2026-11-01 实际三组均通过：36 笔交易、30 张提现订单、111 个原始 MPC 签名请求，6 次已受理广播丢回复及 6 轮双持有人重启。中继费用合计 0.005579980791405284 个本地 ETH，精确匹配中继余额减少及租户手续费流水。公开摘要 SHA-256 为 `1597847d23cfd48ecfb51a48df158fefe2045b6cca4df0539c2019c80beacc68`。Java 应用服务进行了重建，此结果不宣称整个 JVM 进程重启、生产 L2 费用网络、完整备份恢复或全链最终验收完成；旧本地签名助手与 Seed 中继启动器已在全局清理中移除。本轮改动为隔离验收夹具和测试工具，不新增生产模块、业务流程或数据库定义，现有架构图文及 init-sql 无额外结构影响。


## NEAR NEP-145 租户 MPC Gas 注册（三组实际业务通过）

NearTransactionService 在新 Token 提现/归集前查询 storage_balance_of；未注册时只准备固定 storage_deposit(account_id=原收款人, registration_only=true)。NearRegistrationService 在原业务行锁下，用原租户 Gas 账户的公开联合密钥预约 FUNDING / storage-registration 意图，原业务引用经哈希固定，同时保存原业务、收款人、Token 合约和精度。Gas nonce、near_transaction SIGNING 记录、MPC 意图与 ADDRESS_PREPARATION 冻结同事务提交；远程签名在事务外执行，选定原文及本地哈希提交后才能广播。不使用全局热钱包或私钥，不提供任意合约调用签名。注册押金和最大手续费分别限制为 0.1 NEAR，Gas 限制 30 TGas；报价使用原参考块 gas_price 的 100 TGas 预算，超过边界不受理新签名。

注册结果必须有 FINAL 及完整 outcome、EXPERIMENTAL_receipt 与对应规范区块头。NearStorageReceipt 复核原交易/签名/唯一注册 Action、严格有界且完整的回执依赖图和原 Gas 付款人；合约押金退款、失败后的系统余额退款与系统 Gas 退款分别校验。租户实际支出是 tokens_burnt 合计加附加押金、减已完成的押金退款，不能再减 Gas 退款。证明保存在既有 near_transaction.execution_receipt，首个 raw 和终态不替换；实际 24 位费用与 Gas 结算一起提交。注册成功且 Gas 已结算后，NearSubmissionService 再独立核对原注册证明，才能受理原 Token 付款。最终失败且押金已完整退款时，只收实际手续费，并在同事务结束无付款签名的原业务、释放冻结本金；未知、缺少退款、过期或费用超储备时保留原请求与冻结。

已有注册意图必须先恢复原签名/原文与费用，不因当前 storage_balance_of 已非空而跳过对账。原付款尚无 MPC 请求时，恢复调度也能恢复注册并继续原业务；releaseUnsigned 发现注册凭据即拒绝重新领取。停用链、Token 或公开地址不能改写已受理注册的原合约、收款人或引用。此次新增应用服务与公开固定编码/回执检查，仍复用单表仓储、原 MPC/链记录及 Gas 表；无新增表、字段或状态，init-sql 仅同步 fee_yocto 注释。定向数据库边界测试使用明确密码学替身；另在官方 nearcore 2.13.4 本地节点以实际三方 MPC 完成 AB/AC/BC 验收，每组真正停掉第三节点，共 12 笔注册与 9 笔后续 Token 付款。每组包含注册受理后丢回复、原两名持有人重启及应用服务重建、停用后恢复、Gas 自注册归集、抢先注册后的全额退款、旧报价导致的失败及本金只释放一次。该结果不代表整个 JVM、独立主机备份恢复、生产网络或全链最终验收完成。

固定 NEP-141 部署模板显式执行 NEP-145 退款 Promise（near-sdk-js 2.0.0 的 build）：已注册账户退还全部附加押金，新账户退还超过最低存储额的部分。near-contract-standards 2.0.0 原实现只创建 Promise，退款日志并不代表已发出退款。钱包始终按完整链上回执核算实际支出；固定模板已在新测试合约实际验证超额/全额退款，三组正式 MPC 注册和后续业务均通过；原账本与旧合约历史保留。

协议依据：[NEP-145](https://github.com/near/NEPs/blob/master/neps/nep-0145.md) 的 registration_only 退款规则，以及 [本地 nearcore 2.13.4 的退款构造](https://github.com/near/nearcore/blob/44f7ae6cd7ef08bab604e20a473bf77e35d4c993/core/primitives/src/receipt.rs#L456) 对余额和 Gas 退款身份的区分。

真实注册业务入口：设置 `MPC_SIGNER_BIN` 与保护的 `MPC_NEAR_TEST_FIXTURE`，运行 `bash resources/scripts/regtest/run-near-registration-flow.sh`。入口复用本机 PostgreSQL 18 的隔离临时测试库、已保存的本地 NEAR genesis，逐组运行 AB/AC/BC 并实际停掉第三节点；全部 DKG 先完成。`MpcNearRegistrationNativeProbe` 在原注册回复丢失后重建应用服务并重启两名原持有人，保留原生材料；关闭新任务/Token/地址后仍必须恢复原注册和付款。每组还要求 Gas 自注册作为归集收款人、外部管理员抢先注册触发真实全额押金退款，以及明确标识的旧报价故障注入触发真实合约失败退款。每组 4 笔注册及 3 笔后续 Token 付款，要求全部原始回执、实际 Gas 余额/账本、冻结本金和内部重扫均一致；仅在三组完整通过后生成 `/tmp/surprising-wallet-mpc-near-storage-public.json`。2026-11-01 三组完整通过，共 21 个唯一交易哈希，注册与付款实际支出合计 0.037104038576614500000009 NEAR，匹配 Gas 账本与源/归集地址实际余额；公开结果 SHA-256 为 `ac28ea21b311d17ce5f0a918aa93e1bda0ae119c66ddd9bf45be6bfa2a896b40`。测试资源保存全部 12 笔实际注册回执和旧模板“日志退款但未转账”的原始回归证据，NearStorageReceiptTest 覆盖原签名/动作、完整回执、退款身份、全额失败退款及图闭包篡改；新增 7 项实际回执回归与相邻编码/付款回执/架构检查共 26 项定向测试通过。此入口未生成 Java 钱包 Seed，不更换已有 NEAR 管理员、genesis 或任何已充值 HyperCore/ADA 身份；新增内容为隔离验收工具，没有额外生产架构或数据库结构影响。


NEAR 已受理交易和注册的 RPC 使用 NearRpcClient.forNetwork 绑定原 payload.network；该不可变视图共享 HTTP 传输，只查询该链/网络定义与该网络 RPC，不依赖 enabled profile，也不选择其他停用网络作为后备。新工作仍由当前启用 profile 和运行时开关控制，网络与 genesis 继续按原意图验证。真实回复丢失后的停用恢复测试发现了原 RPC 客户端隐式查询 enabled profile 的缺口，因此在此处显式绑定；新增 loopback 路由检查同时覆盖“原网络停用、另一网络启用”和“无绑定时禁止猜测网络”。此变更没有新增配置字段、状态或数据库定义。


## MPC 网络份额刷新（三算法与实际 Java 流程通过）

内部运维调用 MpcSigningService.reserveRefresh 时必须提供原 sourceEpoch、固定三名持有者、actor 和 reason。原版本使重复调用继续同一刷新，包括已经完成后的重试；不创建下一轮刷新。服务锁定原租户密钥，拒绝存在未完成 SIGN 的密钥，并在同事务保存原 REFRESH 描述符、审计人与原因、REFRESHING 状态。原公钥和链地址不变，旧份额和签名结果保留；刷新期间不受理新签名或地址发布。XMR 继续使用独立的 Monero 原生协议，不进入 cb-mpc 刷新。

原生 REFRESH 使用原三方与其顺序，从 epoch N 刷新到 N+1。接受前先持久化原版本 fence；已完成的原 SIGN 可读回原结果，新的旧版本 SIGN 不再受理。新份额和公开元数据分别写入新版本记录，原文件不覆盖。三个节点通过仅限持有者 mTLS 身份访问的 /v1/refresh-prepared 检查原请求、版本、相同公钥和实际持久份额；协调器不能访问此内部端点。只有三名持有者都已准备，节点才写 ready 和完成结果。签名只允许没有后续 fence 的已就绪版本，不能用缺失结果猜测刷新成功。

advanceRefresh 在事务外对账原请求。只有三份完成结果都绑定原版本和原公钥，才在同一事务激活新 epoch、退役旧 epoch，并切回 ACTIVE。重启后可以恢复已持久的新份额并重复公开就绪检查，不重新执行随机刷新协议；若私有刷新协议中断且缺少完整的新份额/元数据，保持 REFRESHING，保留原请求和 fence，不自动回退旧签名或另建仪式。旧备份不能替代缺失的原协议事实。内部恢复调用继续使用原 targetEpoch；不因租户停用而丢弃已受理刷新。

持久化继续使用四个现有单表仓储；wallet_mpc_key_epoch 新增 refresh_actor 和 refresh_reason，并同步 init-sql 的版本范围、刷新审计和终态不可变约束。没有新模块、业务表或调度线程池。原生三算法各完成两次刷新，三个 epoch 的 AB/AC/BC 共 27 个独立验签通过；另有实际 Java/PG18/三方节点完成三次刷新与 18 个签名，26 项定向检查以及最终 6 项 CTest 全部通过。JSON 版本必须为范围内整数，小数及溢出输入的拒绝已通过先失败再修正的回归验证。该结论不包含缺失私有协议材料自动重建、整个 JVM 重启或独立主机备份恢复。


真实 Java/PG18 刷新入口：设置 MPC_SIGNER_BIN，运行 bash resources/scripts/regtest/run-mpc-refresh-flow.sh。脚本只在既有本机 PostgreSQL 18 中创建和清理自身临时测试库；源码编译与定向检查后启动原生三方节点。MpcRefreshNativeProbe 用实际 Java mTLS 客户端完成三种算法的原始签名、刷新和新版本签名，每个两方组合都实际停止第三节点；刷新完成回复的丢失只在真实客户端返回后注入。随后实际重启原三名持有者、核对原文件摘要不变、重建应用服务，在租户 SUSPENDED 时恢复原 targetEpoch 并原子切换，保留原 SIGN 的加密结果与公开地址。2026-11-01 完整接入探针通过：三算法、18 个签名、三轮原三名持有者重启，以及原完成回复丢失、租户停用恢复和旧 SIGN 加密结果读取。公开接入结果 SHA-256 为 `ad06f8d93fd210f37e9b72848d28cd8b129371ace695648d92b27e77aa58c99b`；最终原生刷新矩阵 SHA-256 为 `672eef1b672f8d9134bcd0329578f556be54762c5a5cb2a80157716d04d9d315`。此验收不宣称整个 JVM 或独立主机备份恢复。


## Java 旧签名路径清理（121 项检查与真实无 Seed 启动通过）

Maven 当前为 chain-sdks、common、wallet-api；Java 只运行 API 与业务调度，三个原生签名持有者独立运行。旧 Seed、私有根、本地钱包签名、全局地址生成和两段签名模式已从生产源码移除。公钥编码、地址校验、固定摘要、已选交易记录、PGMQ 领取栅栏及资金状态继续保留。UTXO 签名阶段为 wallet_mpc_sign → wallet_sign_done；原生持有者不接入数据库。

应用启动及配置概览核对 MPC mTLS 客户端，概览字段为 mpcConfigured。部署入口使用 surprising-wallet.service；配置需要原三名持有者身份及 mTLS 文件。部署脚本源码已同步目标数据库前提，不执行数据库初始化或重置；本轮未执行部署。程序回滚必须保留最新账务及协议记录，并使用能识别当前 REFRESH fence/版本的签名程序，不能回退到不识别该状态的旧签名程序。

旧本地 Seed/多签派生测试随移除的接口退役；地址编码、校验、队列并发与回执栅栏测试保留到当前路径。EIP-7702 编码/RPC 测试的本地夹具签名只在 src/test 中，正式业务验收继续使用实际 MPC。清理后先执行 clean 构建，清除已删除组件的旧 class；SDK 8 项、PGMQ 12 项和 API 101 项定向检查全部通过，没有失败或跳过。最终全链矩阵的当前状态见本页清单。


完整应用无 Seed 验收入口：`resources/scripts/regtest/run-mpc-seedless-startup.sh`，需设置 `MPC_SIGNER_BIN`、`MPC_WALLET_JAR` 与原本地 `MPC_NEAR_TEST_FIXTURE`。它检查实际可执行 JAR 和 common 依赖中没有旧签名模块/私钥组件，再用真实三名原生持有者启动完整 API JVM 两轮，核对 HTTP health、启动校验和有序关闭。钱包 Seed 环境变量全部移除；仅配置独立 MPC 的传输身份和账务结果加密密钥。仅在同一个本机 PG18 内使用自有临时测试库，并关闭所有新链业务；NEAR 原节点只读验证原 genesis，不改链历史。此流程验证启动和依赖，不替代链上转账或备份恢复验收。隔离外部 Spring 配置及工作目录的最终启动验收已通过，两轮真实 JVM 均 health UP、完成启动校验且有序退出；没有自动创建钱包 key。公开证明 SHA-256 为 `06df3fec8a39278527fb2d04c4e34a2443d5ebfd248f611cf0f71936974a0756`，实际 JAR SHA-256 为 `bffde838cd0e3ab8fa899bcb161844d6295d301f6cdaea5b7de07c576df1920f`。

## Linux 原生发布产物验收（通过）

已在隔离 Ubuntu24.04 / Linux x86_64 中从当前原生源码编译固定依赖与节点，6 项 CTest 全部通过（含官方 Monero0.18.5.4），随后打包并验证全部文件摘要、systemd unit 静态检查和解包后二进制的实际三方节点三算法/AB/AC/BC 流程。构建容器没有数据库，结束后已删除。发布归档 SHA256 为 `41dbec17ad4717813db45a8d2aabb9a4748a0c0fcf971cb97b950105f5b39e15`；公开验收证明 SHA256 为 `f1b102fae39a9f4396a022a21579114c34bc7a7b7382a591143ba898d6875f7c`。

该包的实际符号要求至少为 GLIBC2.38、GLIBCXX3.4.32 与 CXXABI1.3.13；目标机须满足运行库要求，其他发行版应在其目标环境重新构建。cb-mpc 与 patched OpenSSL 静态链接，运行时没有构建目录或系统 libssl/libcrypto 依赖。本结论不是生产部署，也不替代最终28链 Java 业务回归。

XRP 和 TRON 的旧私钥验收入口已改为真实 MPC：`resources/scripts/regtest/run-xrp-flow.sh` 要求 `MPC_SIGNER_BIN` 与 `MPC_XRP_TEST_RPC_URL`；`run-tron-flow.sh` 要求 `MPC_SIGNER_BIN` 及 `MPC_TRON_TEST_HTTP_URL`、`MPC_TRON_TEST_FULL_NODE`、`MPC_TRON_TEST_SOLIDITY_NODE`、`MPC_TRON_TEST_TOKEN_ARTIFACT`。两者复用已有隔离链节点，只在本机 PG18 创建/清理自己的测试库，先运行相邻回归，再强制执行已配置链的原生业务探针；不自动重置链数据或生成替代资金身份。本轮只改变验收脚本和说明，不改变生产模块、核心数据流或数据库基线。

HyperCore 的当前正式入口为 `resources/scripts/regtest/run-hypercore-flow.sh`，必填 `MPC_SIGNER_BIN`、原 `MPC_HYPERCORE_ACCEPTANCE_ROOT`、已激活的 `MPC_HYPERCORE_RECIPIENT`、稳定的 `MPC_HYPERCORE_FUNDED_RUN_ID`、`MPC_HYPERCORE_FUNDED_PAYER_ROLE=source|collector` 与 `MPC_HYPERCORE_FUNDED_PHASE`。编译/单测仅使用独立的 `surprising_wallet_test_hypercore_build_*`，随后恢复或复用原已充值账本；原节点、密钥、已选会话和账本不重置。付款角色只允许原 source/collector，两者的内部转账可用于验收余额调配。相同批次、角色和阶段保持原业务引用，不能为未知付款临时换批次。退出时备份最新账本并清理编译库；保留资金验收库，全部阶段完成并核对快照后再清理。资金快照必须符合当前目标 schema；入口不自动执行兼容迁移。此改动仅涉及验收脚本和探针，不改变生产数据流或数据库基线。


## Independent approval authorization (integration in progress)

Every node now requires `authorizationPublicKeys` (one to three independently managed raw Ed25519 public keys, lowercase hex). The authority private key must never be deployed with Wallet Java or any MPC holder. `SIGN` and `MONERO_RELAY` require an `authorization` object alongside the eight existing request fields; lifecycle/read-only operations do not use a payment authorization.

The proof has `payloadHex` and `signatureHex`. Signed bytes are ASCII `SW-MPC-AUTHORIZATION-V1\n` followed by canonical JSON payload bytes. Claims bind version, authorization/request IDs, tenant, key, epoch, session, operation, algorithm, ordered online parties, SHA-256 of raw message bytes, chain, network, immutable business hash and purpose. Issued/expiry times are integer Unix seconds with at most 300 seconds validity. Each holder verifies independently, then persists authorization ID and original-session binding before creating the cryptographic request. Repeating the original admitted session recovers with its exact original proof, including after expiry; a fresh session needs a fresh independently validated grant. Capacity rejection consumes no grant.

This native admission check is only one boundary. Independent final transaction parsing, approval identity enrollment, Wallet workflow integration and complete end-to-end acceptance are still in progress. The fixture authority private key used by Python tests is exclusively a local test oracle and must not be used as a production authorization service.
