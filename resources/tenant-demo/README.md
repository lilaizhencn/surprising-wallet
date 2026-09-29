# Surprising Wallet Tenant Demo

独立模拟交易所租户应用：页面由 Cloudflare Static Assets 托管，API / Webhook 由 Worker 接收，用户、会话、余额和流水保存在 SQLite-backed Durable Object。钱包后端继续运行于阿里云，Demo 仅调用公开 Custody API，不访问钱包数据库。

## 部署

需要 Node.js 22+、Cloudflare 账号及 `cf auth login` 授权。

```bash
cd resources/tenant-demo
npm ci
npm test
npm run build
npm run deploy
```

使用固定版本的 `cf` 和 Vite 插件；配置入口为 `cloudflare.config.ts`。正式域名是 `https://tenant-demo.tokdou.com`，钱包地址为 `https://custody-api.tokdou.com`。一个部署对应一个钱包租户，对象名称固定，不能由客户端指定。

部署前必须设置 Worker Secrets：`WALLET_KEY_ID`、`WALLET_API_SECRET`、`WEBHOOK_SECRET`、`TENANT_DEMO_SETUP_TOKEN`。可以使用 `cf deploy --secrets-file <私有 JSON 文件>` 原子发布代码与密钥；该文件不得放入仓库。后续部署保留现有 Secrets。

`npm run bootstrap:tenant` 可在环境变量中提供 `WALLET_BASE_URL`、`DEMO_BASE_URL`、`PLATFORM_ADMIN_EMAIL`、`PLATFORM_ADMIN_PASSWORD`、`TENANT_SLUG`、`TENANT_ADMIN_EMAIL`、`TENANT_ADMIN_PASSWORD`、`TEST_CHAIN` 后创建新钱包租户，创建 API Key / Webhook，上传 Worker Secrets 并部署，随后验证并启用 Webhook。平台凭据只在本机引导过程使用，不上传 Cloudflare。不再提供 HTTP 写密钥接口。

本次部署使用全新演示数据，不迁移旧服务器 SQLite。钱包租户为 `cloudflare-tenant-demo`，开通平台现有的 APTOS testnet；本次没有转入 Gas 或发起链上充值提现。后续发布不会清空对象存储；不要变更对象类、命名空间或固定对象名称来发布普通代码变更。

## 自动部署

`.github/workflows/deploy-tenant-demo.yml` 在推送 `master` 且以下路径有变更时执行：

- `resources/tenant-demo/**`
- `.github/workflows/deploy-tenant-demo.yml`

本地保存文件不会自动发布，必须 commit / push。流程执行 `npm ci`、无数据库单元测试、独立测试 Worker 部署、云端账务与 HTTP 测试，然后发布正式 Worker 并检查页面、健康、配置、登录及真实钱包签名 API。同组部署串行执行，避免覆盖。后端钱包工作流不会因 Demo 目录改动触发。

GitHub Secrets：`TENANT_DEMO_CLOUDFLARE_API_TOKEN`（专用部署凭据）、`TENANT_DEMO_TEST_TOKEN`（测试 Worker 访问令牌）、`TENANT_DEMO_SMOKE_USER`（专用无资金检查账号的 JSON：email / password）。部署凭据仅授权目标账号的 Worker 操作及目标 zone 的路由操作；不保存本机 OAuth 会话。业务密钥保留在 Cloudflare Worker Secrets。

## 验证

```bash
npm test
npx cf deploy --mode test
TENANT_DEMO_TEST_URL=https://tenant-demo-tests.lilaizhencn.workers.dev \
TENANT_DEMO_TEST_TOKEN='<测试 Worker 令牌>' npm run test:remote
node scripts/check-deployment.js https://tenant-demo.tokdou.com
```

账务测试在 Cloudflare 独立 `tenant-demo-tests` Worker / TestRunner 命名空间执行，每个用例使用新对象并在 finally 清空其测试存储。不启动本机 SQLite、Docker、PostgreSQL 或链进程。测试使用合成回调及钱包 HTTP stub，不动真实链上资金。正式 Worker 不包含测试入口和清库功能。

## 业务与安全

- 注册、登录、退出和 HttpOnly / Secure / SameSite 会话；密码使用 scrypt，会话令牌以 SHA-256 摘要存储。
- 按链申请、轮换充值地址；仅查看自己的余额、流水、地址和提现。
- Webhook 使用 HMAC、时间窗口及事件幂等；余额与流水通过 Durable Object storage.transaction 原子提交。
- 提现先冻结，钱包明确拒绝才释放；超时、5xx 或不明确结果进入 PENDING_REVIEW，保留冻结等待回调或人工核对。
- 对象内 API / 回调请求串行执行；出站请求 15 秒超时，禁止携带认证跟随重定向。
- 登录失败计数保存在对象存储中；跨源写请求被拒绝。API 管理快照需要 setup token。
- 金额使用十进制定点字符串；日志不打印凭据、请求体和钱包敏感响应。

链扫描、签名、广播和归集仍属于 wallet-api，Cloudflare 不保管链上签名私钥。

## 回滚与数据库边界

CI 的单元测试或云端账务测试失败会阻止正式发布。发布阶段或发布后检查失败时，先核对实际 Worker 版本和路由状态：CLI 可能已经应用部分配置，不保证自动恢复。需要回滚时，使用 Cloudflare 控制台回滚到上一 Worker 版本。代码回滚不回滚余额或流水，不能删除或重建正式 Durable Object。数据恢复应使用对象存储恢复能力，并先核对期间钱包回调。

Demo 私有表定义在 `src/store.js`；钱包 PostgreSQL 表、状态和种子配置未改变，因此本次不修改 `surprising-wallet-init-pgsql.sql`。架构及核心流程图文已同步 Cloudflare 边界。
