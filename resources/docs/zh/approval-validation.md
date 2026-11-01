# 独立审批与全链签名授权验收

日期：2026-11-02。范围为 `feat/tenant-withdrawal-approval` 的独立审批、Wallet 接入、原生持有者授权入口及前端；没有部署生产服务，没有使用已有资金库或覆盖已资助的 MPC 节点。

## 已实现的授权边界

当前 `ChainType` 的 28 条链均有明确的付款校验路径，未知用途、未知编码及缺少独立配置均拒绝签发证明。支持范围是项目已有的固定交易类型，不是任意合约调用。

| 链族 | 独立校验及关键覆盖 |
| --- | --- |
| 13 条 EVM 链 | 原生币/ERC-20 规范 wire、发送者、chain ID、nonce、费用模型；L1/operator 额外费用按独立配置预留 |
| BTC/LTC/DOGE/BCH | 每个真实原输出、完整付款集、同租户找零、固定输入、逐输入摘要、整批一次费用；BTC RBF 另需显式有界权限 |
| Aptos | 经典 sequence、legacy Ed25519、规范 APT/primary FA、有效期及 Gas |
| SOL/TON/TRON/XRP/NEAR | 各自规范消息、Token/精度、业务标识、发送者、独立引用/余额/费用事实；SOL 全部签名人及独立 payer |
| Sui/Cardano/DOT | 实际对象/原 CBOR UTxO/runtime metadata、归属、版本、金额守恒及费用 |
| HyperCore | 已激活收款人、固定 spotSend、实际 wireToken、签名网络及时间域 |
| XMR | 原生 Monero 2-of-3 固定付款策略；原 artifact/会话的独立 relay 授权，不换输入另造付款 |
| EIP-7702 | 同租户 authority/relayer、固定完整批次、可信代码/执行者、实际内层签名与最终 type-2/type-4；提现及多来源归集均覆盖 |
| 自动操作 | 主机安装的来源/treasury、真实余额与父业务、金额/次数/间隔/永久费用额度；默认拒绝；NEP-145 最低押金及 XRP 激活/NoRipple TrustSet 单独校验 |

Wallet 子账号管理不能生成独立审核密码。独立审核支持阈值、不同审核人、版本化策略、轮询和当前撤销代次确认。外部 API、幂等收据、查询过滤及 Webhook 始终使用普通 `FROZEN` 投影，不泄露待审状态。

证明绑定原 tenant/key/epoch、业务、版本、签名槽、native session、参与者和消息。独立事务提交证明后，Wallet 先提交原证明与审计，持有者再验签并持久化 admission 后执行协议。缺失/伪造/替换/首次过期的证明不能签名。原证明不续期；已接纳原会话可恢复。无授权退款需要新鲜的独立终态收据和全部本地栅栏一致，未知或已授权交易继续冻结。

## 本轮验证

| 检查 | 结果 |
| --- | --- |
| SDK 全量 | 58 项通过，0 跳过 |
| common 全量 | 30 项通过，0 跳过 |
| Wallet 全量（真实本机 PostgreSQL 18/PGMQ） | 1050 项：1021 通过，29 条件跳过，0 失败/错误 |
| 独立审批全部测试 | 119 项通过，0 跳过；包括 55 项真实 PostgreSQL 工作流/并发/授权测试 |
| 前端 | 12 个文件、59 项通过；TypeScript 与 Vite 生产构建通过 |
| 原生持有者 | 7/7 CTest 通过，包括 admission 后崩溃、原证明过期恢复、拒绝伪造证明、加密存储和 Monero 策略 |
| Java→原生证明 | 实际 Java issuer 规范证明被两名原生持有者接受并产生可验签结果 |
| 实际 HTTPS / mTLS / 四-Core | 最终产物验收 PASS，进程退出 0；含真实撤销与独立恢复 |
| 最终打包 | 五个 reactor 模块成功；两个可执行包使用同一 SDK/common 产物 |

Java 全量入口为 `resources/scripts/regtest/run-approval-db-tests.sh -fae clean`。该次 SDK/common/Wallet 全量通过；独立审批新增 NEAR 测试中的 getter 编译错误修正后，使用以下命令完整执行独立审批全部测试并成功打包五模块。修正仅涉及测试，不修改此前已通过的 Wallet/SDK/common 生产源码。

```sh
bash resources/scripts/regtest/run-approval-db-tests.sh \
  '-Dtest=Approval*Test,*WithdrawalVerifierTest,AccountChainWithdrawalVerifiersTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

原生使用独立的 approval 构建目录，避免覆盖此前已资助节点的可执行文件；运行 `ctest --test-dir <approval-build> --output-on-failure`。前端使用 `pnpm test` 和 `pnpm build`。Shell/Python 语法、两张架构 SVG XML 和两个仓库的 `git diff --check` 也纳入检查。

### 实际服务与广播入口

`resources/scripts/regtest/run-approval-https.sh` 使用本机 PostgreSQL 18 的自有临时库及四个互异的临时 owner/runtime 角色，运行真实可执行服务。Wallet 和独立审批分别通过真实启动权限检查，使用不同凭证；测试结束删除本次库、角色、节点和 Core 容器。

该入口覆盖真实 TLS/mTLS、独立登录/Origin/CSRF/双人审核、三持有者 DKG/refresh 独立观察、Wallet 原证明及审计先于 native dispatch、一名 holder 离线的 EVM/Aptos 签名，以及四条 Bitcoin 类链的真实两输入/两付款批次：PGMQ→授权→MPC→原文/哈希提交→广播→故意丢回复→完全相同原文重试→规范区块确认→本金只结算一次。成员撤销用真实 mTLS 验证旧会话/密码失效、旧代次恢复被拒和独立确认恢复后重复通知不再次停用。

最终执行结果为 `PASS`，进程退出 0；本次临时库、四个临时角色及全部自有 Core 容器已清理。以下是本次 regtest 的公开交易哈希（不是公共测试网浏览器交易）：

| 链 | 原批次交易哈希 |
| --- | --- |
| BTC | `1632c7a88d922acc3a5b8682c7217a6333f33b663c3796d72f451ba4505b2f4b` |
| LTC | `14ce6524ce32d11694c713e7c533868b9b588a25932d81b8c2160a4c09daf09c` |
| DOGE | `c803b82fd1400cef1ae097c48e92b112f4a28eead4dc8c4bc5112b21c5d2bbaf` |
| BCH | `bcbe54bd4c4e7c59809377523e1142538a94c30d6c9398de5ee6a5b22294ba27` |

最终受测产物 SHA-256（本地产物，不提交 JAR、二进制或 dist）：

| 产物 | SHA-256 |
| --- | --- |
| wallet-api.jar | `f04253c3297a400f2882a30a815ff478811a7c6837e0fcf0062c56345178a0b4` |
| wallet-approval.jar | `ad021f7458604e70fb03f6832796892bc78b929b6f5d70baf351c80441d2bbac` |
| wallet-mpc-signer | `3fa9baca87cd47764d559b17c4612ebe08f9a47ddd90998906a25f3689bfdc23` |

## 验证范围与部署边界

逐链规范消息与独立事实用例不是实网广播证明。本轮实际广播入口覆盖 BTC/LTC/DOGE/BCH regtest，实际独立审核加原生签名覆盖普通 EVM/Aptos；其余账户链及 7702 的本轮证明为规范 wire/签名语义、数据库工作流和原生入口的分层验证。未把 29 项条件跳过或此前 MPC 28 链验收计为当前独立审批的实网成功。BTC 替换授权已验证版本/输入/付款/费用不变约束，四-Core 入口不证明 RBF 替换广播。

启用某个网络前，须在独立主机安装并核验对应资产、RPC、费用模型、角色和 operating rules；7702 中继必须与业务属于同一租户。已经授权但原命令过期、部分完成或结果未知的交易保留原绑定及冻结约束，只按原材料恢复或规范链上结果对账；没有通用“超时退款/换 nonce 重付”入口。

部署、角色、证书、issuer、只读 native 身份、监控及回滚见 [独立审批部署说明](../../infra/approval/README.md)。回滚保留当前数据库、审计、原证明、nonce/输入占用和各节点 admission/session；不把旧资金快照恢复当作程序回滚。新结构仅使用同步后的唯一 init-sql 空库基线，不对已有资金库执行该脚本。

本批改动影响独立服务职责、共享 SDK 分层、签名/撤销/终止核心流程和数据库约束；已同步 `AGENTS.md`、架构/核心流程 Markdown 与两张 SVG、唯一 init-sql、单表仓储架构测试和 OpenAPI。本轮最后的 7702 槽序号修正与补充测试不新增表或改变上述依赖方向。
