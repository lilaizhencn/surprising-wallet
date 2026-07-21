# 多租户托管钱包

[English](../en/multi-tenant-custody.md)

Surprising Wallet 是托管钱包基础设施，不管理租户自己的客户、用户名、商户、订单和内部账务规则。

## 隔离模型

所有对外托管查询都从认证凭证确定数据范围：

| 凭证 | 租户范围来源 |
|---|---|
| 平台 Console 会话 | 平台管理员，只能操作平台控制面 |
| 租户 Console 会话 | 租户用户和会话中保存的 `tenant_id` |
| API Key | 加密 API 凭证中保存的 `tenant_id` |

客户端不能提交一个可被信任的 `tenantId`。服务从凭证解析租户，并在地址、充提、事件、
投递记录和审计查询中强制带上该租户范围。

## 租户用户与地址分配

租户请求一条链的新充值地址时，提交链代码 `chainId` 和租户定义的账户标识 `subject`。服务从该链当前唯一启用的
`chain_profile` 自动确定网络：

```http
POST /custody/api/v1/addresses
X-Custody-Key: swk_...
X-Custody-Timestamp: 1784486400
X-Custody-Nonce: 2FSvJwQp1QdwLk2B
X-Custody-Signature: ...
Content-Type: application/json

{
  "chainId": "ETH",
  "subject": "user_10086",
  "addressVersion": 0
}
```

`chainId` 是链代码，例如 `ETH`、`BTC`、`SOLANA`，不是 EVM 数值 chain ID。`subject`
可以是用户、商户或系统账户的稳定业务标识。`addressVersion` 是租户管理的地址版本，省略时为
`0`。`(tenant, chain, subject, addressVersion)` 是业务幂等键；相同参数重复请求返回原地址，
不需要额外的幂等请求头。需要给用户更换地址时，把版本递增为 `1`、`2` 等即可。旧版本地址
不会删除或停止监听，迟到充值仍会识别并记到同一个 `subject`。同一租户使用相同 `subject` 和
`addressVersion` 请求任意 EVM 链时，因为统一使用 `coinType=60` 和相同派生坐标，所以返回同一个
地址。调用方应保存返回的地址 ID、地址和版本。

Console 可以不经过租户 API 手动创建地址，也可以修改地址的标签、元数据和启用状态。停用地址
仍然继续监控，并继续计入租户资产总览，保证迟到充值和已有资金不会消失。

### 租户地址派生

每个租户在创建时从 `custody_derivation_namespace_seq` 获得唯一的
`tenantNamespace`。服务把 `(tenantId, subject)` 稳定映射为从 1 开始分配的内部
`derivationSubject`；相同租户的相同 subject 始终使用同一个派生编号，不同租户互不共享。
secp256k1/Bitcoin-like 地址使用以下非 hardened、自定义 BIP44 形态路径：

```text
m / 44 / coinType / tenantNamespace / derivationSubject / childIndex
```

Ed25519 地址使用 SLIP-0010 hardened 路径：

```text
m / 44' / coinType' / tenantNamespace' / derivationSubject' / childIndex'
```

其中 EVM 链统一使用 `coinType=60`；其他 secp256k1/Bitcoin-like 链使用
`chain_profile.bip44_coin_type`；Ed25519 链使用代码中固定的 SLIP-0044 coin type。
普通用户的 `addressVersion` 会映射到派生路径的内部 `childIndex`；版本不是钱包写死的固定槽位。
同一租户、链、subject 和版本只允许一条地址记录，数据库事务级锁和唯一约束共同保证并发请求
返回同一个地址。`childIndex` 是内部派生信息，不在租户地址 API 中暴露。
`__sw_` 前缀由钱包内部系统账户保留，API 和普通 Console 地址不能使用。

每个租户在每条已开通链上可从资产总览生成一个系统归集地址。该地址使用钱包保留的
`derivationSubject`，并固定为 `childIndex=1`。同一租户的所有 EVM 链共用
`__sw_collection__:evm` 派生主体，因此地址完全相同；非 EVM 链仍使用链级派生主体。
同一租户和链重复生成会返回同一个地址。
地址收到并确认的原生币同时作为该链链上操作的 Gas 资金，资产总览始终显示全部已开通链，
包括尚未生成地址或余额为零的链。

Monero 不使用 BIP44；地址由 wallet RPC 创建 subaddress，并记录
`monero-wallet-rpc:m/0/{subaddressIndex}`。

创建地址不发送 Webhook。充值确认后才发送：

```json
{
  "id": "5f2034d1-39f0-4c07-83b0-e0d8dfb8ed48",
  "type": "DEPOSIT.CONFIRMED",
  "createdAt": "2026-07-20T01:00:00Z",
  "data": {
    "depositId": "13d45f9e-d8a1-4fae-a591-75b62dad5df4",
    "subject": "user_10086",
    "chain": "ETH",
    "asset": "USDT",
    "address": "0x...",
    "amount": 100.000000,
    "txHash": "0x...",
    "logIndex": 0,
    "confirmations": 20
  }
}
```

租户消费该事件，可直接用 `subject` 映射到自己的客户并入账。

## 资产口径

`ledger_balance` 是余额事实来源。租户资产总览会找出每个客户托管地址对应的全部 account ID，
包括 token 账户和已停用地址，然后按 `chain + asset_symbol` 聚合。Gas station 专用地址和余额
单独展示，不计入客户资产：

- `availableBalance`
- `lockedBalance`
- `totalBalance`
- 托管地址数量

充值依靠链交易唯一键只入账一次。创建提现时，现有钱包账本会原子检查并冻结选中地址账户的资金。
Custody 提现记录持续跟踪底层订单的签名、广播、确认和失败状态。提现目标是经过链适配器校验的
外部地址，不要求先由本钱包服务创建；必须预先准备 Token 账户的链仍保留各自的准备校验。

## API 认证

API Key 默认可以调用全部租户 API，不需要选择权限。Secret 只展示一次，并用
`SW_CUSTODY_SECRET_MASTER_KEY` 加密保存。

规范请求串：

```text
timestampSeconds + "\n" +
nonce + "\n" +
upper(method) + "\n" +
requestPathAndRawQuery + "\n" +
hexLowercase(SHA-256(rawBodyBytes))
```

`X-Custody-Signature` 使用无 Padding 的 Base64URL：

```text
HMAC-SHA256(apiSecret, canonicalRequest)
```

服务端校验时间窗口，并在窗口内永久拒绝重复 Nonce。创建地址和提现还必须传
`Idempotency-Key`。提现幂等键永久有效；用同一键提交不同内容会被拒绝。
提现请求还必须明确发送 `"confirmed": true`；Console 会先展示独立确认页，再提交该字段。
参数校验和下游业务失败统一返回带可操作提示的 JSON 错误结构，签名错误不会回显凭证或签名材料。

租户打开 IP 白名单后，每个 API 请求都必须来自已启用的 IPv4/IPv6 CIDR。应用使用直连 HTTP
对端地址，因此反向代理必须建立可信对端边界；不要在没有可信代理配置时接受客户端随意提交的
转发头。

## Webhook

Webhook 不需要选择订阅事件。每个通过 API 创建的托管地址所产生的以下事件，都会自动投递到该
租户所有已验证并启用的 Webhook 端点。Console 手动创建地址产生的事件只保留内部审计，不发送
租户业务回调：

- `DEPOSIT.CONFIRMED`
- `WITHDRAWAL.CREATED`
- `WITHDRAWAL.BROADCAST`
- `WITHDRAWAL.BROADCAST_UNKNOWN`
- `WITHDRAWAL.CONFIRMED`
- `WITHDRAWAL.FAILED`

端点必须先通过验证。服务发送带 `data.challenge` 的 `WEBHOOK.VERIFICATION`，端点应返回：

```json
{ "challenge": "<原值>" }
```

每次投递携带：

```text
X-Custody-Event-Id
X-Custody-Event-Type
X-Custody-Timestamp
X-Custody-Signature: v1=<base64url-hmac>
```

签名内容为：

```text
timestamp + "." + 原始请求 Body
```

接收方应先验签，再按事件 ID 幂等处理，并拒绝过期时间戳。事件与投递记录在同一个数据库事务
中持久化。Worker 使用 `SKIP LOCKED` 抢占任务、指数退避重试并限制响应/错误保存长度，失败记录
可以在 Console 手动重试。

自动重试从 30 秒开始，采用带确定性抖动的指数退避，支持服务端返回的合法 `Retry-After`，
最多自动尝试 10 次，单次等待上限为 6 小时。手动重试会开启新的重试周期，但不会删除此前记录。
Console 可以查看每次尝试的触发方式、HTTP 结果、耗时、错误、下次重试时间、周期和累计次数。
Worker 租约过期会形成明确的恢复记录，尝试隔离会阻止迟到的旧 Worker 覆盖较新的投递结果。

## 租户开通与 Gas station

Console 开通检查包含 6 步：

1. 创建 API Key，并妥善保存只显示一次的 Secret；
2. 开启 IP 白名单并至少配置一条 CIDR；
3. 创建并验证 Webhook；
4. 为业务网络创建 Gas station 账户；
5. 向 Gas 地址充值原生币并等待确认；
6. 至少创建一个客户地址。

Gas station 账户是租户在对应网络上的原生币专用地址。确认后的充值通过现有链上归集流程进入
热钱包，同时形成可审计的租户预付 Gas 余额。Gas 地址、Gas 充值和 Gas 余额不会混入客户地址、
客户充值或客户资产总览。

创建托管提现时，系统会在同一事务内冻结客户请求提现的金额和对应网络的一笔保守 Gas 预留额，
不会再从客户地址重复扣一笔网络费。提现失败、拒绝或取消会释放 Gas；提现确认后，
能取得链上手续费的适配器按链上记录结算，
否则按保守配置额结算。每次预留、释放、结算和交易哈希都保留在 Gas 使用历史中。

如果实际手续费超过已充值余额，该记录会变成 `OVERDUE`，对应网络暂停接受新提现。
租户补充原生币并确认到账后，对账任务会自动结清欠费并恢复处理。

## Console 能力

平台管理员：

- 创建隔离租户和首个租户管理员；
- 按名称、Slug 和状态搜索及分页查看租户；
- 进入租户详情，统一查看基本配置、开通进度、管理员、客户资产和地址、Gas
  储备、API Key、IP 白名单、Webhook、近期充提与审计活动；
- 修改租户名称和报表币种；Slug 与派生命名空间保持不可变；
- 在不读取或替换密码的前提下，解除租户管理员的临时登录锁定；
- 暂停或重新启用租户。暂停会立即阻断签名 API，并注销该租户全部活动
  Console 会话；重新启用不会恢复已经注销的会话。

租户管理员：

- 查看聚合资产；
- 按开通检查完成配置，并为每条业务网络预充值 Gas；
- 创建、搜索、标记、停用和重新启用地址；
- 查询充值和提现记录；
- 从 Console 创建提现；
- 创建/撤销拥有完整租户 API 访问能力的 API Key；
- 开启和配置 IP 白名单；
- 创建、验证、停用和重新启用 Webhook；
- 查看完整 Webhook 尝试历史，并手动重试失败投递；
- 查看租户审计日志。

## 生产要求

- 生产环境必须生成独立的 BIP32、Ed25519 和签名材料，不能复用测试 seed。
- `SW_CUSTODY_SECRET_MASTER_KEY`、签名根密钥、数据库/RPC 凭证和管理员密码必须存放在密钥系统。
- 通过 TLS 提供服务，Custody/Console CORS 只允许正式 Console 域名。
- 钱包和签名网络保持私有。Webhook 出网使用白名单代理或防火墙。应用 DNS 检查只能降低 SSRF
  风险，不能替代网络层出网控制。
- PostgreSQL 开启持续备份和时间点恢复。Custody 状态以 PostgreSQL 为事实来源；Redis 只承担
  运行协调/缓存。
- 监控 Webhook 积压和失败、提现对账延迟、扫链延迟、账本异常、RPC 健康、数据库饱和度和签名服务。
