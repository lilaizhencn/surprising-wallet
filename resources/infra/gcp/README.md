# 钱包 MPC 的 Google Cloud KMS 部署

目标方案保留三个独立原生持有者及原 MPC 协议。每个持有者的 32 字节存储密钥（DEK）由自己的 KMS 对称密钥（KEK）保护；仅密文进入节点配置和备份，节点启动时直接在 C++ 进程内解包。Java API 不读取持有者 DEK，也不代签。

## 已选资源

- 项目：`surprising-ae591`。
- 地域：美国 `us-central1`；密钥环：`surprising-wallet-mpc`。
- 软件 AES-256 KMS 密钥：`holder-1-storage`、`holder-2-storage`、`holder-3-storage`。
- 专属账号：`wallet-mpc-holder-{1,2,3}@surprising-ae591.iam.gserviceaccount.com`。
- 每个账号只在自己的 CryptoKey 上拥有 `roles/cloudkms.cryptoKeyDecrypter`，不生成服务账号 JSON 私钥。

创建/复核资源的可重复入口：

```bash
WALLET_GCP_PROJECT=surprising-ae591 bash resources/infra/gcp/provision-kms.sh
```

脚本不创建 VM，不附加账号到现有 VM，不部署钱包，不生成份额或变更现有存储。三个软件版本的基础费用为 $0.18/月，加解密按 $0.03/万次计费；新增版本另计，详见 [KMS 定价](https://cloud.google.com/kms/pricing)。

## 为一个持有者包裹存储密钥

先准备本节点正式 mTLS 公钥证书。工具读取公开证书，计算 SPKI SHA256，并把该身份放入 KMS AAD。证书续期须保持原 SPKI；更换身份需要专门规划，不能随意替换证书私钥。

仅为全新、空存储的节点生成 DEK：

```bash
python3 resources/infra/gcp/wrap-storage-key.py \
  --key-name projects/surprising-ae591/locations/us-central1/keyRings/surprising-wallet-mpc/cryptoKeys/holder-1-storage \
  --certificate /etc/surprising-wallet-mpc/node.pem \
  --output /etc/surprising-wallet-mpc/storage-key-envelope.json
```

工具用本机 gcloud 当前身份调用 KMS Encrypt，检查请求验证标记及返回密文 CRC32C；只输出密文，拒绝覆盖已有输出。包装工具是一次性管理操作，Python/TLS 内存不保证所有临时副本及时清零，因此须在受控管理机上运行、禁用交换空间/休眠；它不承担运行时密钥保管。

已有 store 必须保留原 DEK。在相同命令中显式增加 `--existing-key-file /受保护路径/原storage.key`，只读取当前用户持有的 0600、32 字节普通文件；不修改该文件。不能为原数据运行随机生成模式。先验证新配置能读回原记录并完成重启/原会话恢复，再按备份与销毁流程处理旧明文。

把输出 JSON 对象作为 `node.json` 的 `storageKey`。只保存 `kmsKeyName`、`wrappedKey`、`caBundleFile`；不保存原 DEK、令牌或服务账号私钥。原 `encryptionKeyFile` 配置应删除。生产 Linux 的 CA bundle 通常为 `/etc/ssl/certs/ca-certificates.crt`。

## 运行权限

三台 GCE 持有者 VM 分别附加自己的服务账号，OAuth scope 设为 `cloud-platform`，由 IAM 限定实际权限。程序固定从 GCE metadata 取得短期令牌；不执行 gcloud，不接受命令行/环境变量密钥，不提供静态凭据回退。KMS HTTPS 校验系统 CA 和主机名，不重定向；调用失败、CRC32C/长度错误或锁页失败均停止启动。

每个持有者配置独立磁盘/身份/网络访问；不要把三者放在共享广泛凭据的 VM 上。节点间及审批接口仍使用现有 mTLS/SPKI/授权证明。Linux unit 关闭 core dump 并设置 64 KiB MEMLOCK；禁用宿主 swap/休眠，限制调试/ptrace 权限。KMS DEK 在启动后缓存，撤权不会立即停止已经运行的节点签名。

## 恢复与轮换

保留每方原 wrappedKey、原加密 store、原身份及可解密的 KMS 历史版本。KEK 轮换后旧密文仍需要旧版本；不能先销毁旧版本。重新包裹原 DEK 不改变已有 store；随机换 DEK 会使旧 store 无法解密。

回滚程序时保留当前数据库、原会话与协议记录，不恢复过时资金/随机数快照。旧程序要求明文文件，因此 KMS 接入后的运行回滚须使用支持本配置的已验证版本，或通过受控恢复流程处理，不能静默恢复文件模式。

## 验证范围

`wallet_mpc_kms_test` 覆盖完整性校验、拒绝明文配置、失败关闭和原加密记录重开。其 `--live` 模式仅编译到测试程序，从 stdin 接收短期测试凭据，不安装到 Runtime 包；可用于真实 GCP KMS 验收。管理机如使用本地系统 HTTP 代理，验收程序支持仅测试编译的 loopback 代理；正式节点无此配置入口。

真实云端验收（仅生成合成 DEK，不读取任何现有钱包材料）：

```bash
python3 resources/infra/gcp/verify-kms.py \
  --project surprising-ae591 \
  --binary /绝对路径/build/wallet_mpc_kms_test \
  --ca-bundle /etc/ssl/certs/ca-certificates.crt
```

该入口复核三方逐密钥 IAM 和无项目级授权，验证原密文两次解包/加密记录重开，并实际请求错误 SPKI、错误 KMS key、无效令牌三种拒绝路径。macOS 管理机可将 CA bundle 换成 `/etc/ssl/cert.pem`。

`wallet_mpc_fixture_node` 仅用于合成密钥的 MPC/审批/刷新回归，不安装。`Cluster` 自动选择同目录测试程序；Runtime 包的隔离夹具须显式指定 `WALLET_MPC_TEST_NODE_BINARY`。真实 GCE metadata 身份与正式持有者部署仍需在目标 VM 上验收；本机 gcloud 登录不代表已经部署。

## 本次验证记录（2026-11-02）

- 真实 GCP：三个美国软件 KMS 密钥均为 ENABLED，逐密钥 IAM 仅授权各自持有者解密，无持有者项目级授权。
- 真实 KMS：三把密钥均完成密文解包、CRC32C 校验、同密钥重开原加密记录；错误 SPKI、错误 KMS key 和无效令牌均被拒绝。仅使用合成材料。
- CMake 构建成功；CTest 10/10，通过三算法 MPC、存储、独立审批、崩溃恢复、份额刷新、Monero 原生与 KMS/正式启动拒绝测试。
- 包裹工具 CLI：公开证书 SPKI 绑定、0600 密文、拒绝覆盖、原存储密钥包裹及不安全权限拒绝均通过，输出可被原生加载器实际解密。
- Runtime install 只包含正式程序、样例、文档和 unit；未包含测试密钥入口、测试程序及本地代理测试代码。
- 架构图文和启动流程已同步；未变更数据库 schema、业务表、状态或 init-sql，未运行数据库重置。
- 尚未创建/部署 GCE 持有者 VM，未迁移任何现有钱包 DEK/份额。GCE metadata 身份、Linux 发布与实际节点激活仍须在目标主机验收；当前结果不能当作生产部署完成。
