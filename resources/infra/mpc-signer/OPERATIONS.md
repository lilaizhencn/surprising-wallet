# 独立签名节点运行与恢复说明

本说明对应当前开发分支，供隔离验收和恢复核对使用。EIP-7702 与 NEAR 自动存储注册已有三组本地正式 MPC 业务通过记录；网络份额版本切换已有三算法本地实际验收；旧签名模块已清理并通过 121 项检查和真实无 Seed 启动；本机完整冷恢复演练已通过下文所述验收，Linux 发布产物验证已通过下文所述检查，当前代码最终 28/28 个链组本地链/测试网验收全部通过；HyperCore 本轮 21 笔均已确认，原未知 1 USDC 转账按原文重播后取得规范回执，并经正常业务流程一次结算、冻结归零。当前版本尚未部署生产。算法测试、原进程重启或某一条链通过，均不代表灾难恢复和全链验收通过。

## 运行边界

三个 `wallet-mpc-signer` 分别持有自己的份额和存储密钥；Java 通过 mTLS 调用，不加载钱包私钥或份额。每个节点应有独立的操作系统身份、存储和访问控制，协调器只能使用授权租户与操作。当前测试夹具在同一机器运行独立进程，不能作为生产故障域隔离的证明。

节点入口是 `wallet-mpc-signer /absolute/path/node-config.json`。原生程序读取以下配置，完整可运行的隔离示例由 `tests/node_test.py` 生成，不能把它生成的证书或密钥用于真实资金。

| 配置 | 含义与恢复要求 |
|---|---|
| `bindHost` / `port` | 签名节点监听地址；限制为授权的协调器和持有者可访问的网络 |
| `certificateFile` / `privateKeyFile` / `caFile` | 节点 mTLS 身份与信任根；证书 SPKI 是持有者身份的一部分 |
| `encryptionKeyFile` | 该节点独有的 32 字节存储密钥，必须为当前用户的普通文件，权限 0600 |
| `storeDirectory` | 本节点完整的加密记录目录，必须属于当前用户，权限 0700；记录不可覆盖 |
| `peers` | 固定的三个 SPKI 身份、主机及端口；保留原登记顺序与身份 |
| `clients` | 以协调器证书 SPKI 为键，明确列出允许的租户 UUID 和操作，不使用隐式通配授权 |
| `sessionTimeoutSeconds` | 允许 5–120 秒，默认 30 秒；超时表示结果未知，不是付款失败证明 |

Java 的 mTLS 文件使用 `SW_MPC_CA_FILE`、`SW_MPC_CLIENT_CERTIFICATE_FILE`、`SW_MPC_CLIENT_PRIVATE_KEY_FILE`；三个持有者配置为 `SW_MPC_NODE_{1,2,3}_ID` / `_URL`。部署时还须保留 Java 对已选签名结果加密使用的现有应用密钥 `SW_CUSTODY_SECRET_MASTER_KEY`；仅恢复 PostgreSQL 不能解密原结果。

## 原生发布包与 Linux 安装

先用 `build-and-test.sh` 构建并完成底层验收，然后在没有验收进程使用该构建目录时打包：

```bash
MPC_BUILD_DIR=/absolute/path/to/native-build \
  bash resources/infra/mpc-signer/package-release.sh /absolute/path/new-native-release.tar.gz
```

脚本重新构建节点目标，经 CMake 安装明确列出的二进制、配置样例、运行说明与 unit，再生成文件 SHA256 清单和平台/架构/依赖版本记录。输出仅包含公开程序与样例，不读取节点实际配置、份额、TLS 私钥或存储密钥。发布包必须在目标 Linux 架构构建；macOS 构建产物只能用于对应平台验收。清单记录工作区是否有未提交改动，不代表已经通过全链验收。

每台持有者主机建立独立 `wallet-mpc` 系统用户，把解包后的公开程序安装到 `/opt/surprising-wallet-mpc/releases/<release>/`，由 root 管理，并令 `current` 指向该目录。将包内 `share/wallet-mpc-signer/surprising-wallet-mpc.service` 安装到 systemd，私有配置放在 `/etc/surprising-wallet-mpc/node.json`。systemd 为该用户建立 0700 的 `/var/lib/surprising-wallet-mpc`；份额目录及外部 32 字节存储密钥仍须满足前述归属和权限要求。

`config/node.example.json` 只列默认 cb-mpc 权限，所有 `REPLACE_WITH_*` 必须替换。样例默认监听 loopback；跨主机部署须选择受保护的内网监听地址，确认服务器证书 SAN、固定三个 peers 的原顺序、原 SPKI 和可达端口。只给指定租户授权；REFRESH 及 Monero 操作应按需显式增加。XMR 的 `monero` 配置按上一节补充，并单独部署同主机的官方私有钱包进程；本 unit 不启动数据库、Monero daemon 或 wallet-rpc。

激活前先核对包的校验和与配置，再在隔离环境完成健康、原密钥读取及原请求恢复验证。升级只替换公开程序和 unit，保持当前数据及所有秘密；回滚目标必须认识当前 epoch/fence。当前分支的打包和恢复入口用于验收，不会自动激活生产主机。

XMR 每个持有者另运行自己的官方 `monero-wallet-rpc` 和本机 daemon 连接。节点 `monero` 配置包含 `network`、`walletRpcUrl`、`walletCredentialFile`、`daemonRpcUrl`，需要 daemon 认证时增加 `daemonCredentialFile`。当前原生适配器只接受 `http://127.0.0.1:端口` RPC，不把私有钱包 RPC 暴露给 Java 或其他持有者。认证 JSON 为含 `username` / `password` 的当前用户普通文件，权限 0600；私有钱包认证必须开启，`allowInsecureTestRpc` 仅用于 regtest。钱包口令由原生节点产生并保存在加密存储，运维不应提取它来绕过签名服务。

## 必须成套保存的材料

| 范围 | 内容 |
|---|---|
| 每个持有者，分别保管 | 完整 `storeDirectory`、对应的外部存储密钥、节点配置、原 SPKI 通信身份及信任根 |
| 每个 XMR 持有者，分别保管 | 官方私有钱包目录、钱包 keys/cache、ringdb 与 RPC 配置/认证文件；和该持有者的加密记录配套 |
| Java 协调器 | 钱包 PostgreSQL 的事务一致备份、应用结果加密密钥、通信身份、链/租户配置与程序版本 |
| 公开核对清单 | Git/二进制和依赖摘要、备份时间、节点 SPKI、租户/key/epoch、公钥地址、未完成 request/attempt/session 和已选交易哈希；不含私钥、份额、口令或原始 RPC 正文 |

各持有者的备份和解密能力继续分开管理，避免一次获取两份持有者秘密。存储密钥和对应密文分开保管；日志、Git、工单及公开验收 JSON 不存秘密。份额文件、钱包文件和数据库记录不能按各自不同时间任意拼合。

本地 Monero regtest 恢复必须保留原链历史，并核对备份前后的高度和 tip hash。官方 `monerod --help` 及[参数说明](https://docs.getmonero.org/interacting/monerod-reference/)说明，`--keep-fakechain` 用于阻止 fakechain 模式在启动时删除已有数据库；隔离恢复夹具显式设置该参数。只核对 genesis 相同不足以证明恢复了原交易历史。

备份前停止接纳新资金业务，等待已接纳流程达到可解释的持久状态；未完成或未知记录必须原样保留。停止对应签名节点和私有钱包进程后再制作一致快照，记录各组件时间和摘要。不能只复制 `key-*`：原请求、审核事实、cosigned/artifact、完成结果与恢复结果同样决定原交易是否可以安全继续。

本轮还完成了另一个目录与第二临时数据库中的完整冷恢复，原目录与源测试库已删除后再继续原请求；此结果不证明跨机器故障域隔离。缺失私有材料不能自动重建，落后快照不得直接用于继续签名。发现快照可能落后于实际签名时，只能先隔离恢复副本并核对，不能直接启用签名。旧快照可能缺少已经受理的会话和已使用的签名材料，恢复旧快照后重新发起 SIGN 不是安全回滚。

## 原请求与未知交易处理

先从钱包持久记录确认同一租户、业务、key/epoch、原 request/attempt/session、参与方、公钥、意图和已选哈希，再使用原配置恢复进程。不得为一个未决业务创建新请求、替换 nonce/输入、重新建钥或删除旧结果。链上查不到交易或余额暂未变化都不能证明没有生成或广播签名。

| 已有事实 | 可以采取的动作 |
|---|---|
| 一个或两个持有者已有原完成结果 | 由钱包正常对账原会话；验证两个一致的绑定结果，再继续原交易选择/广播/确认 |
| 原始交易字节与业务哈希已提交，广播回复丢失 | 按该链正式恢复流程查询，必要时仅重放原字节；本金和费用按规范终态只结算一次 |
| cb-mpc SIGN 返回 `INTERRUPTED` / `INDETERMINATE` | 保留原未知记录与资金约束，按现有受审计的恢复边界处理；不能把原未知 SIGN 当作未执行而再次提交 |
| 节点 `BUSY` 或网络超时 | 先查原持久回执；不据此解冻本金，也不越过参与方顺序继续占用其他节点 |

XMR 还有受限的原材料恢复：原参与方保留了本机加密审核事实，并且存在原 artifact 或原第二签名者的 cosigned txset 时，可以恢复同一 SIGN 描述符。原发起者仅能经内部 `/v1/monero-recovery-material` 读取原第二签名者的材料；协调器、第三方和反向调用均被拒绝。恢复期间只核对原材料，不再次调用 `transfer` / `sign_multisig` 或重新导出/导入随机材料；结果另写入不可变 `recovered-result-*`，旧结果保留。

如果本机审核事实未落盘，或钱包已签名但回复和 cosigned 材料均已丢失，当前实现不能自动重建它。原请求与原尝试继续为 `INDETERMINATE`，本金保持冻结，保存全部记录供核对或恢复原材料。不得手工改为失败、退款、更换参与方重签，或恢复旧钱包/随机数缓存后尝试签名。该边界已有两类真实中断验收，不能把安全保留未知写成自动恢复成功。

XMR 只有原两名 SIGN 持有者可广播已选交易。实际验收已验证由其中一名持有者广播、另一名重放同一材料及规范区块确认；第三方不可接管原广播。已观察业务的恢复继续使用原网络和已发布密钥，不受新业务开关关闭影响。

## 隔离验证与发布条件

底层回归使用 `build-and-test.sh` 或 README 中的 CMake/CTest 命令。Java 客户端与钱包业务分别使用 `test-java-client.sh` / `test-wallet-service.sh`；链业务必须另检查真实链探针，不能用底层验签通过替代。

完整 XMR 验收入口是 `resources/scripts/regtest/run-monero-flow.sh`，需设置 `MPC_SIGNER_BIN` 和指向固定官方 Monero 0.18.5.4 的 `MPC_MONERO_BIN_DIR`。它依次运行充值/解锁/真实重组、正式出款/归集/固定 Gas 补款、原材料中断恢复与缺失材料保留未知。数据库仅使用已有 `127.0.0.1:5432` PostgreSQL 18 中三个明确命名的临时测试库，退出时清理自身资源。验收期间不修改或重编译其正在使用的 Java 类或原生二进制。

当前版本全部链最终矩阵已通过；最终发布前仍须核对验收材料和提交前审计；本机冷备份恢复和网络份额版本切换已有专项验收。核对数据库基线、架构图文及敏感材料不入库后，才进入提交和发布。程序回滚也必须保留最新持久请求、结果和业务记录，不能以回退数据库或份额快照代替版本回滚。

本说明和 README 状态修正不新增模块、接口、核心数据流、表字段或配置项；无需额外调整架构 SVG 或 init-sql。


## 份额刷新与原版本恢复（三算法本地完整流程通过）

内部运维入口为 MpcSigningService.reserveRefresh(tenant,keyId,sourceEpoch,parties,actor,reason)，随后在事务外以原 targetEpoch=sourceEpoch+1 调用 advanceRefresh。原 sourceEpoch 是重复调用身份的一部分，完成后重试不会发起下一轮。协调器证书须显式授权 REFRESH；仅授权 CREATE_KEY/SIGN 的证书不能刷新。原生内部 /v1/refresh-prepared 只接受原三名持有者的 mTLS 身份，返回公开准备状态，不返回份额。

刷新前必须处理完原 key 的所有未完成 SIGN。开始后原 key 保持 REFRESHING，不能发布地址或受理新 SIGN；持久 fence 使接受刷新的原生节点拒绝新的旧版本签名。保留原份额、元数据、fence、ready、request、result 和 recovered-result 全部记录。三份原公钥完成结果齐备后，Java 原子切换版本和旧 epoch 终态。

若新份额与元数据均已持久化，重启后只恢复同一 REFRESH 和公开就绪检查；不重新执行随机刷新。若私有协议中断且缺少完整新份额或元数据，保持原冻结及请求供恢复原材料；不能通过删除 fence、换 session、回退快照或把 key 改回 ACTIVE 继续旧签名。当前没有“缺失私有协议状态自动重建”的能力。原 source/target 与 actor/reason 不得改写。实际验收入口为 CTest mpc_network_refresh；Java/PG18 与真实三方节点的完整接入已通过；当前 28 链最终回归也已通过，不能以单项验收宣称整体生产交付。

Java/PG18 与实际三方节点的完整验收入口为 `MPC_SIGNER_BIN=/absolute/path/wallet-mpc-signer bash resources/scripts/regtest/run-mpc-refresh-flow.sh`。它复用本机 PostgreSQL 18，只创建并清理自身临时测试库；18 个签名按三算法和刷新前后 AB/AC/BC 逐一核对，并测试真实完成回复丢失、三轮原持有者重启、租户停用后的原请求恢复。完整接入与 26 项定向检查已通过，最终 6 项 CTest 全部通过；该客户端故障注入不伪造签名或节点完成结果。


## 当前 Java 部署入口（清理后构建与启动通过）

Java 只运行 surprising-wallet.service，不选择 API/签名混合模式。删除钱包 Seed 与本地私钥配置后，服务必须具备三个独立 MPC 端点/身份以及 CA、客户端证书和传输私钥。此次配置变更的风险是缺失 MPC 配置会阻止启动或阻止新签名；部署前先在隔离环境核对配置和数据库基线，不可临时恢复本地私钥兜底。

回滚只替换兼容同一持久协议状态的程序，保留当前数据库和持有者记录。禁止回退到不能识别当前 REFRESH fence/epoch 的签名程序，也禁止恢复旧随机材料快照继续 SIGN。此次更新部署脚本和 unit 源码并完成本机冷备份恢复，没有执行生产部署；跨机器故障域隔离不在本机演练证明范围内。

清理后已执行干净构建，并核对可执行 JAR 与 common 包中没有旧签名模块及 Seed 提供者。121 项定向检查与隔离工作目录、只读取 JAR 内配置的两轮完整 API JVM 启动通过。该结果不是生产部署或跨机器备份恢复证明；本轮没有执行部署。

## 本机完整冷恢复验收（通过）

入口为 `MPC_RELEASE_ARCHIVE=/absolute/path/release.tar.gz MPC_MONERO_BIN_DIR=/absolute/path/official-monero-0.18.5.4 bash resources/scripts/regtest/run-mpc-backup-restore.sh`。仅复用本机 PG18，创建并清理自有两个临时测试库。26 项相邻 Java 检查通过；恢复使用原 mTLS SPKI、各方 AES 密钥、所有加密协议记录、Monero 私有钱包、应用结果加密密钥和事务一致数据库 dump。三个持有者、协调器、网络及测试外部付款人分别形成受保护归档；这只是隔离夹具，生产备份仍须分开保管秘密与解密能力。

实际恢复核对 276 个文件的字节与权限、143 项数据库表/序列状态；随后删除原测试目录与源测试库。另一个 Java JVM 读取 9 个原加密签名、领取 3 个原已完成但未领取的签名，再以原 epoch2 在 AB/AC/BC 完成 9 个新签名。三个算法的旧 epoch 新 SIGN 均被拒绝，191 个原不可变协议文件保持摘要不变，错误应用加密密钥无法解密原结果。

Monero daemon 的原高度与 tip 保持一致；恢复后先领取原未领取 SIGN、广播并确认原哈希，再以其余两个组合发出并确认新付款。三次分别停掉第三持有者及其私有 wallet-rpc，Java 验证原账户、本金和实际费用。公开证明 SHA256 为 `70beabbf648a3c53107adff7d1f0459faa90c958a77fb736102629b78f0bf250`。此结论是同主机冷目录恢复与独立 Java JVM 探针，不是生产发布、完整 Boot 应用业务恢复或跨主机故障域隔离证明。该验收工具和安装产物不新增业务表、生产配置项或核心数据流，架构图与 init-sql 无新增影响。

## Linux 发布包验收结果

在隔离 Ubuntu24.04 / x86_64 上构建的发布包通过全部6项原生 CTest（139.77秒，Monero118.70秒）、包内容摘要核对、systemd unit 静态验证，以及从归档解出的程序再次进行真实三进程三算法/全两方组合验收。构建器已删除，没有启动数据库或激活生产服务。归档 SHA256 为 `41dbec17ad4717813db45a8d2aabb9a4748a0c0fcf971cb97b950105f5b39e15`。

该验收平台为 glibc2.39，实际 ELF 所需最高符号为 GLIBC2.38、GLIBCXX3.4.32、CXXABI1.3.13。部署机需满足这些运行库条件，或在目标发行版重新构建。cb-mpc、patched OpenSSL 均静态链接，动态依赖不指向构建目录，也不加载系统 libssl/libcrypto。该验收二进制构建于本分支提交前的工作区，manifest 保留当时的 dirty 记录；运行时代码与当前验收版本一致，归档是验收产物，不是已经批准的生产版本。原节点配置、秘密和份额不包含在包中。
