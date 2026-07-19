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

租户先在自己的系统创建用户，然后请求该用户的 ETH 地址：

```http
POST /custody/api/v1/addresses
Idempotency-Key: address-user-10086-eth
X-Custody-Key: swk_...
X-Custody-Timestamp: 1784486400
X-Custody-Nonce: 2FSvJwQp1QdwLk2B
X-Custody-Signature: ...
Content-Type: application/json

{
  "chain": "ETH",
  "externalReference": "user_10086",
  "label": "主充值地址",
  "metadata": {
    "customerTier": "business"
  }
}
```

`externalReference` 对钱包是不透明的，可以是客户 ID、商户 ID、账户 ID 或租户自己的其他分配键。

永久分配键是：

```text
tenant_id + chain + external_reference
```

并发和重复请求都会返回同一地址。不同租户可以使用相同引用而不会共享数据。不传引用时，
每个成功请求都会分配一个新地址。

Console 可以不经过租户 API 手动创建地址，也可以修改地址的标签、元数据和启用状态。停用地址
仍然继续监控，并继续计入租户资产总览，保证迟到充值和已有资金不会消失。

创建地址不发送 Webhook。充值确认后才发送：

```json
{
  "id": "5f2034d1-39f0-4c07-83b0-e0d8dfb8ed48",
  "type": "DEPOSIT.CONFIRMED",
  "createdAt": "2026-07-20T01:00:00Z",
  "data": {
    "depositId": "13d45f9e-d8a1-4fae-a591-75b62dad5df4",
    "externalReference": "user_10086",
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

租户消费该事件，在自己的用户账本中给 `user_10086` 入账。

## 资产口径

`ledger_balance` 是余额事实来源。租户资产总览会找出每个托管地址对应的全部 account ID，
包括 token 账户和已停用地址，然后按 `chain + asset_symbol` 聚合：

- `availableBalance`
- `lockedBalance`
- `totalBalance`
- 托管地址数量

充值依靠链交易唯一键只入账一次。创建提现时，现有钱包账本会原子检查并冻结选中地址账户的资金。
Custody 提现记录持续跟踪底层订单的签名、广播、确认和失败状态。

## API 认证

API Key 使用明确的 scope，Secret 只展示一次，并用
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

租户打开 IP 白名单后，每个 API 请求都必须来自已启用的 IPv4/IPv6 CIDR。应用使用直连 HTTP
对端地址，因此反向代理必须建立可信对端边界；不要在没有可信代理配置时接受客户端随意提交的
转发头。

## Webhook

支持的事件：

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

## Console 能力

平台管理员：

- 创建隔离租户和首个租户管理员；
- 查看租户活动数量；
- 暂停或重新启用租户。

租户管理员：

- 查看聚合资产；
- 创建、搜索、标记、停用和重新启用地址；
- 查询充值和提现记录；
- 从 Console 创建提现；
- 创建/撤销 API Key 并配置 Scope；
- 开启和配置 IP 白名单；
- 创建、验证、停用和重新启用 Webhook；
- 查看并重试 Webhook 投递；
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
