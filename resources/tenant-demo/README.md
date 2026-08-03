# Surprising Wallet Tenant Demo

这是一个独立的模拟交易所租户应用，不引用钱包服务内部 Java 类，只通过公开 Custody API 和 Webhook 验证真实租户流程。

当前流程包括：

- 用户注册、登录、会话和退出登录；
- 用户按链申请充值地址，支持地址版本轮换；
- 充值 Webhook HMAC 校验、重放幂等和用户账本入账；
- 提现前冻结余额，等待钱包回调后确认扣款或失败解冻；
- 多个提现订单共享一个 EIP-7702 批量交易 txid 时分别结算；
- 用户只能查看自己的地址、余额、流水和提现记录。

## 本地启动

要求 Node.js 20 或更高版本。Node.js 22 使用内置 `node:sqlite`；Node.js 20 使用可选的 `sqlite3` 驱动。数据默认保存于 `data/tenant-demo.sqlite3`，不会连接钱包服务的业务数据库。

```bash
cd resources/tenant-demo
npm install
npm test
npm start
```

默认监听 `127.0.0.1:3001`。可以通过 `TENANT_DEMO_PORT` 和 `TENANT_DEMO_SQLITE_PATH` 调整端口及 SQLite 文件位置。

## 接入钱包 API

生产或远程部署时设置 `TENANT_DEMO_SETUP_TOKEN`，然后用部署密钥写入钱包 API 配置：

```bash
curl -X PUT http://127.0.0.1:3001/api/config \
  -H 'Content-Type: application/json' \
  -H 'X-Tenant-Demo-Setup-Token: <setup-token>' \
  -d '{
    "walletBaseUrl":"http://127.0.0.1:8002",
    "walletKeyId":"<tenant-api-key>",
    "walletApiSecret":"<tenant-api-secret>",
    "webhookSecret":"<webhook-signing-secret>"
  }'
```

配置完成后，钱包 Webhook 地址必须指向：

```text
https://<tenant-domain>/webhooks/custody
```

API Secret、Webhook Secret 和 setup token 只放在服务器环境文件或密钥管理系统中，不写入 Git。

## 验证命令

验证正在运行的 Demo 和钱包公开 API：

```bash
DEMO_BASE_URL=http://127.0.0.1:3001 \
TEST_CHAIN=ETH \
npm run verify:running
```

创建固定账号及测试账号。固定账号可通过 `TEST_FIXED_EMAIL`、`TEST_FIXED_PASSWORD` 指定；脚本不会打印密码：

```bash
TEST_FIXED_EMAIL='602884291@qq.com' \
TEST_FIXED_PASSWORD='<fixed-password>' \
TEST_USER_PASSWORD='<test-password>' \
TEST_USER_COUNT=40 \
TEST_CHAIN=ETH \
npm run provision:test-users
```

仅验证租户的并发账务、重复充值回调和共享 txid 提现回调：

```bash
DEMO_BASE_URL=http://127.0.0.1:3001 \
STRESS_CHAIN=ETH STRESS_ASSET=ETH STRESS_USERS=40 \
STRESS_WEBHOOK_SECRET='<webhook-secret>' \
npm run test:stress
```

该脚本使用签名的模拟回调验证租户账务边界；真实链上充值、钱包签名、广播和归集必须再通过已部署的钱包服务及开发链验收。

持续为一组账号轮换充值地址，使钱包开发水龙头可以发现新地址。不同账号密码可用 `email=password` 形式传入：

```bash
TEST_USER_CREDENTIALS='602884291@qq.com=<fixed-password>,user-1@example.test=<test-password>' \
CONTINUOUS_CHAIN=ETH CONTINUOUS_MIN_DELAY_MS=15000 CONTINUOUS_MAX_DELAY_MS=90000 \
npm run test:continuous-recharge
```

设置 `CONTINUOUS_MAX_CYCLES` 可让测试在有限轮次后退出；不设置或设为 `0` 时持续运行。

## API 流程

1. `POST /api/auth/register` 或 `POST /api/auth/login` 获取 HttpOnly 会话 Cookie；
2. `GET /api/chains` 查看租户已开通链；
3. `POST /api/me/addresses` 调用钱包地址 API；
4. 钱包向 `/webhooks/custody` 发送 `DEPOSIT.CONFIRMED`，Demo 给对应 `subject` 入账；
5. `POST /api/me/withdrawals` 冻结余额并调用钱包提现 API；
6. 钱包发送 `WITHDRAWAL.BROADCAST`、`WITHDRAWAL.CONFIRMED` 或 `WITHDRAWAL.FAILED`，Demo 更新提现状态及账本。

钱包内部的充值扫描、签名、广播和归集仍属于 wallet-api/wallet-service；tenant-demo 不直接访问钱包数据库，也不模拟钱包内部归集状态。

## 安全边界

- SQLite 开启 WAL、外键和事务串行化，金额使用十进制定点字符串，不使用浮点数；
- 充值按 `chain:txHash:logIndex` 幂等，提现按订单及事件幂等；
- 登录失败按来源地址限速；会话仅保存哈希；
- `/api/config` 和 `/api/admin/snapshot` 必须提供 setup token；
- 不提交私钥、助记词、真实 token、RPC 密钥、生产配置或 SQLite 数据文件。
