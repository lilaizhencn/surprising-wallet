# 独立审批部署说明

源码、统一回归和实际服务验证的范围见 [审批验收记录](../../docs/zh/approval-validation.md)。这些材料用于独立主机部署和审阅；尚未部署生产，目标主机的 OS 隔离、备份恢复及各启用网络配置仍须按实际环境演练。

## 身份与进程

Wallet、独立审批和三个 MPC 持有者分别使用独立操作系统身份、数据库角色或客户端证书。独立审批进程不加载 Wallet Bean、不持有份额，也不具备向原生持有者提交协议的权限。

| 持有者 | 私有材料 | 可执行操作 |
| --- | --- | --- |
| Wallet API | 自己的受限 DB 凭证、审批 mTLS 客户端证书、Wallet 原生客户端证书 | 钱包业务；申请独立授权；按原证明调度协议 |
| 独立审批 | 自己的受限 DB 凭证、独立 Ed25519 issuer 私钥、独立登录密码记录、原生只读客户端证书 | 审核、规则、最终 wire 校验、签发证明；原生 READ_RESULT |
| MPC holder 0/1/2 | 各自份额、各自存储加密密钥与 TLS 私钥、独立 issuer 公钥 | 验证并持久化证明后执行授权的原协议 |
| 公共页面代理 | 公共站点 TLS server 私钥、审批后端 CA 公钥 | 静态页面和浏览器 API 代理；不能调用服务授权接口 |
| 离线安装/恢复人员 | 各 NOLOGIN owner 的受控维护权限、分别保管的备份恢复材料 | 初始化、凭证登记、审计与恢复，不用于业务进程 |

不能复用以上 TLS 私钥/SPKI，也不能把独立 issuer 私钥、独立客户端凭证或份额目录交给 Wallet 部署账号。数据库 owner 和 runtime 分离；runtime 不得是 superuser、数据库 owner、CREATEDB/CREATEROLE/BYPASSRLS 角色或其他角色的成员。

## 数据库与角色

唯一结构基线仍是 `resources/docs/db/surprising-wallet-init-pgsql.sql`，只用于全新目标数据库。`database-roles.sql` 只调整当前数据库的 owner/权限，不创建数据库、表、数据库服务或容器；它不是第二份结构基线。开发和测试始终使用 `127.0.0.1:5432` 已有 PostgreSQL 18，在隔离命名临时库中演练。

维护人员在指定目标库初始化后审阅并执行 `database-roles.sql`。可通过 psql 变量选择四个不同的角色名，默认是 `sw_wallet_owner`、`sw_approval_owner`、`sw_wallet_runtime`、`sw_approval_runtime`。脚本撤销 PUBLIC 对应用表/函数以及 PGMQ 的访问，再分别授予 Wallet public/PGMQ 访问和独立审批 approval 访问；该操作需要专用钱包数据库，不能不经审阅套用到其他应用共用的数据库。LOGIN 密码通过主机上的 psql `\password` 交互设置，不写入 SQL、命令参数或仓库。

`DatabaseRoleIsolation` 在两个可执行应用启动时检查真实有效权限。独立审批必须无法读取/写入 approval 之外的应用关系，不能执行外部可调用的 SECURITY DEFINER 函数或创建应用对象；Wallet 必须完全无法访问 approval schema/关系。两者都检查 PostgreSQL 17 及以上、非管理/非数据库 owner 角色和无角色成员资格；Wallet 还检查 PGMQ 的队列与租约接口。未满足即启动失败，没有生产绕过开关。应用只读系统目录，不在启动时更改授权。权限判断使用 [PostgreSQL 权限查询函数](https://www.postgresql.org/docs/17/functions-info.html)；拒绝角色成员资格也覆盖 [SET ROLE 所提供的切换能力](https://www.postgresql.org/docs/17/role-membership.html)。

## 配置、证书与网络

`surprising-wallet-approval.service` 使用独立 `wallet-approval` OS 用户，只读自己的配置/issuer/PKI，日志目录单独可写，并隐藏 Wallet/native 目录。环境文件和全部私钥必须为该用户独有的普通 0600 文件；不要把密码或私钥放在 Java 命令行。创建日志目录后再启用单元；提交的文件只有公开示例，不包含真实材料。

独立服务的环境变量见 `wallet-approval/src/main/resources/application.yaml`。公共浏览器 origin 和私网服务 origin 可不同；服务证书 SAN 必须匹配 Wallet 实际连接的私网名称，Wallet 固定其 SPKI。`client-auth: want` 允许浏览器连接；服务 API 仍只接受真实 TLS client certificate 与明确的 SPKI→tenant allowlist，不信任转发证书头。首次独立管理员只经可信主机登记；服务客户端没有邀请、重置、激活或恢复身份的入口。

公共 Nginx 示例拒绝 `/approval/service/`，并以验证证书的 HTTPS 连接浏览器 API 后端，代理上没有任何已登记的服务身份。Wallet 通过私网直连服务 API，网络 ACL 只允许指定来源。浏览器前端用构建时固定的 `VITE_APPROVAL_ORIGIN` 指向独立站点，不能从 URL 参数选择审批目标；独立页面使用自己的 Cookie、Origin/CSRF 和轮询。

审批主机独立安装资产/genesis/精度/issuer 或 contract、费用预算、只读节点与 operating rules。例子在 `wallet-approval/src/main/resources/*example.yaml`。RPC 头文件是 0600 普通文件，Wallet 请求不能传 URL、凭证、余额或替换规则。原生只读证书须在三台节点分别安装为 READ_RESULT-only，不能拥有 SIGN、MONERO_RELAY、CREATE_KEY 或 REFRESH。issuer 私钥仅在独立审批主机；三台节点和 Wallet 只安装对应公钥。

## 停用、故障、回滚与恢复

Wallet 子账号停用或移除 APPROVER 角色时，账号事务持久化递增撤销代次。独立任务使用原 mTLS 发送单向撤销；独立服务以永久 `approval.member_revocation` 保存最高代次，即使审核员尚未登记也保留。新的通知在同一事务撤销旧审批会话、清空激活能力、停用审核员并审计。重复/旧通知不能重复停用后续已明确恢复的身份。恢复必须由独立管理员核实本人及 Wallet 状态，明确确认当前撤销代次，再重新邀请/设置独立密码；Wallet 启用、改密码或延迟通知都不能自动恢复。

这是持久重试的跨进程传播，不是跨域原子提交。独立服务不可用时可能存在传播延迟；Wallet 在仍有本租户待传撤销时阻止申请新证明。已签发的证明/有效历史审批不被伪称为撤回；原证明继续保留以完成原交易恢复。监控待传通知的数量、最早时间和新授权暂停，发生失败时修复网络/凭证并重试原代次，不重置计数器。

回滚先暂停新出款/归集入口，继续保留到账、原交易确认与恢复能力。保留当前 Wallet/独立审批数据库、完整审计/计划/版本/证明/输入占用及三个原生节点的 admission/session 目录；未知交易不退款、不换 nonce/inputs，也不释放永久费用曝光。仅回滚到仍支持当前证明与持久模型的已验证产物，不能启用无证明 SIGN 或覆盖已资助的旧节点数据。全新基线不提供旧表兼容迁移，不能将 init-sql 应用于已有资金库。

分别备份 Wallet、独立审批、各节点加密份额与它们各自的恢复材料，避免同一人或同一存储包拿到足够份额和全部解密材料。恢复演练须校验原 tenant/key/epoch/public address、MPC admission、业务绑定、nonce/输入与链上结果；未经对账不得根据数据库旧快照重发付款。数据库权限与 HTTPS 已纳入隔离环境验收入口；systemd/代理的实际主机隔离和生产备份恢复仍须在目标环境演练。

## 统一验收入口

`resources/scripts/regtest/run-approval-db-tests.sh -fae clean` 使用本机 PostgreSQL 18 的隔离临时库运行 Java reactor 全量回归，包括真实 PGMQ 与 nonce 入场/回收约束；链节点联网验收仍需另行配置对应节点，不能把环境条件跳过视为链上通过。`resources/scripts/regtest/run-approval-https.sh` 为实际可执行包创建四个互异的临时 owner/runtime 角色，运行真实 HTTPS、mTLS、独立审核和原生签名验收，并在结束时删除本次测试库与本次角色。凭证通过私有环境/stdin 传递，Wallet probe 使用 Wallet runtime，审批进程使用 approval runtime，不以管理员账号绕过启动隔离检查。共享 protobuf 由 SDK 提供，两端使用相同父级管理的 Java runtime，避免迁移后生成类与运行时不一致。
