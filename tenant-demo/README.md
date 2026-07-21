# Surprising Wallet Tenant Demo

这是一个独立的模拟交易所租户，不引用钱包服务内部 Java 类。它只通过公开 Custody API 和 Webhook 集成，用于逐链验收真实租户流程。

当前功能：

- 创建交易所内部用户，并用用户 `externalId` 作为钱包 API 的 `subject`；
- 为用户生成/轮换各链充值地址；
- 校验 Webhook 时间戳和 HMAC-SHA256 签名，响应验证 Challenge；
- 按 Event ID 幂等处理充值、提现广播、确认和失败回调；
- 使用十进制定点算法维护用户可用/冻结资产，不使用浮点数；
- 发起提现前冻结用户余额，API 失败或链上失败时解冻，确认后完成扣款；
- 对比交易所用户账本与钱包租户总资产，展示地址、流水、提现和回调明细。

## 启动

要求 Node.js 22.5 或更高版本，无第三方 NPM 依赖。

```bash
cd tenant-demo
npm test
npm start
```

钱包、Demo、API Key 和 Webhook 均配置完成后，可在另一个终端执行在线验收：

```bash
TEST_CHAIN=APTOS npm run verify:running
```

该脚本会通过 Demo 创建一个独立用户，验证地址幂等、地址轮换、已开通链和租户资产 API。后续逐链测试都复用同一入口。

访问 <http://127.0.0.1:9300>。默认 Webhook 地址：

```text
http://127.0.0.1:9300/webhooks/custody
```

在租户 Console 中完成以下设置：

1. 开通待测试链并生成该链归集/Gas 地址；
2. 创建一个 API Key，将 Key ID 和只展示一次的 Secret 填入 Demo“连接配置”；
3. 创建上面的 Webhook 地址；
4. 将创建 Webhook 时只展示一次的 `whsec_...` 填入 Demo；
5. 在 Console 验证并启用 Webhook。

Demo 数据保存在 `data/tenant-demo.db`。API Secret 和 Webhook Secret 仅保存在此开发机的本地数据库，该文件已被 Git 忽略。不要使用生产凭证。

## 环境参数

| 参数 | 默认值 | 说明 |
|---|---|---|
| `TENANT_DEMO_PORT` | `9300` | Demo HTTP 端口 |
| `TENANT_DEMO_DB` | `tenant-demo/data/tenant-demo.db` | SQLite 文件路径，测试可使用 `:memory:` |

## 资金核对规则

用户充值确认后，Demo 增加用户可用余额。发起提现时，同额资金从可用转入冻结；`WITHDRAWAL.CONFIRMED` 后冻结余额扣除，`WITHDRAWAL.FAILED` 后退回可用余额。所有 Webhook 使用事件 ID 幂等，余额和流水在同一个 SQLite 事务内更新。

钱包 `/custody/api/v1/assets` 是租户链上总资产，Demo 资产是交易所内部用户负债。逐链验收报告会分别核对两者与 Gas/手续费/归集差额，不将二者错误地视为永远相等。
