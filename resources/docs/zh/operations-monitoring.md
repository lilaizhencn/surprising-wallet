# 日志与运行监控

## 日志保留

| 日志 | 策略 |
|---|---|
| 应用 INFO/WARN | `logs/info.wallet-all.log`；每天或达到 20 MB 切分，gzip；归档最多 14 天、512 MB |
| 应用 ERROR | `logs/error.wallet-all.log`；每天或达到 20 MB 切分，gzip；归档最多 30 天、128 MB |
| systemd journal（包括应用标准输出和 PostgreSQL） | 最多 14 天、200 MB；运行时最多 50 MB；预留 1 GB 磁盘 |
| Nginx | 每天或超过 20 MB 轮转，最多 14 份、14 天，压缩 |
| 发布日志 | 每天或超过 10 MB 轮转，最多 14 份、14 天，压缩 |

Logback 的容量限制针对归档，当前写入文件及异步清理期间会有额外占用。应用启动时也清理过期归档。新策略只管理新的归档文件名；升级前已有历史文件应先检查、备份，再按实际保留要求处理，不批量删除未知文件。Nginx 和发布日志由每小时运行的 logrotate 检查；不会操作应用当前日志。发布日志使用 copytruncate，复制与截断之间可能丢失少量行；资金审计以数据库流水为准。

参考：[Logback 滚动文件配置](https://logback.qos.ch/manual/appenders-rolling.html)。

## 监控部署

`scripts/ops/wallet_monitor.py` 只使用 Python 标准库和系统现有命令。主机上由 systemd 每分钟启动一次，无常驻代理、额外数据库或指标服务。探针不加载钱包密钥文件，不读租户业务记录，不改变队列或余额；PostgreSQL 查询启用只读事务和 3 秒语句超时。

全部监控独立部署在钱包所在的新主机，通过独立的 systemd 进程执行：

- 检查钱包、PostgreSQL、Nginx、证书续期/日志轮转/监控定时器。
- 检查本机健康和 readiness、公网 HTTPS、主机资源、数据库连接/锁/长事务、PGMQ 积压和死信。
- 统计最近 5 分钟的应用/数据库 ERROR、内核 OOM、Nginx 5xx，检查应用日志体积及源站证书有效期。
- 每天北京时间 09:00 发送资源与状态摘要。

本机探针直接访问 Telegram API，不经过旧服务器。它可以在钱包 JVM 或 PostgreSQL 故障时继续告警；如果整台主机断电或完全断网，探针也无法发送消息。当前部署没有第二台探针主机。

告警沿用运维 Telegram Bot 和目标聊天；配置仅在服务器 `/etc/surprising-wallet-monitor/monitor.env`，权限 `root:root 0600`。不使用 `getUpdates`，可与现有监控共用发送 Bot。告警不发送日志原文、API Key、地址、账号、余额或数据库查询内容。

### 通知格式

告警、恢复、持续提醒、监控程序异常和每日摘要全部使用中文文本，显示服务器、北京时间和检查说明。资源告警包含当前数值与阈值；日报按行展示资源、接口、服务、数据库和队列状态。未获取的指标明确显示“未获取”，不填充为零。JSON 仅用于本机状态文件和调试输出，不发送到 Telegram。

示例（数值仅供说明）：

```text
【钱包监控 · 状态通知】
服务器：阿里云钱包（43.110.44.53）
时间：09-29 16:00:00（北京时间）

• 告警：内存不足：可用 90 MiB，告警阈值 200 MiB
```

### 阈值

| 检查 | 默认告警条件 |
|---|---|
| 服务 / HTTP / 检查器异常 | 连续 2 次失败 |
| CPU / 负载 / I/O 等待 | CPU >90%、1 分钟负载 >核数×1.5、I/O 等待 >20%，连续 3 次 |
| 内存 | 可用 <200 MiB 连续 3 次；<100 MiB 立即 |
| Swap | 使用 >512 MiB，连续 3 次 |
| 磁盘 / inode | 使用 >80% 或剩余 <3 GiB，连续 2 次；使用 >90% 立即；inode 剩余 <20% 连续 2 次 |
| PostgreSQL | 连接数达到上限 80%；阻塞查询 >30 秒；事务 >5 分钟；连续 2 次 |
| PGMQ | 队列消息 >1000 或最旧消息 >5 分钟，连续 2 次；死信非空立即 |
| 日志 | 5 分钟 ERROR/FATAL ≥5；5xx ≥5 且占比 ≥10%，立即；应用日志 >1 GiB 连续 2 次 |
| OOM / 自动重启 / 主机重启 | 检测到即通知 |
| 源站 TLS | 剩余有效期 <14 天即通知 |

恢复连续确认 2 次后通知；同一持续故障每小时提醒。通知按轮次合并；Telegram 发送失败不会将告警标记为已发送，下一轮重试。短暂故障在投递成功前已消失时会用中文补发“曾出现异常，现已恢复”。探针自身失败/超时由独立 systemd OnFailure 单元通知。Telegram 不可用或本机完全断网时不能保证即时告警，日志中会记录投递失败。

日志统计每次最多读取 2000 条应用/数据库 journal 记录、1000 条内核记录和 Nginx 当前/上个文件末尾各 2 MiB，避免日志风暴耗尽内存。高流量下这些统计是采样窗口，不是精确请求指标。

### 安装

将仓库对应文件复制到目标主机的受控目录，先按 `resources/infra/monitor/monitor.env.example` 创建服务器密钥配置，填写实际 Bot、聊天、标签和公网健康 URL。安装命令在新钱包主机执行：

```bash
sudo bash scripts/ops/install-monitor.sh
```

安装器会备份原文件到 `/var/backups/wallet-monitor/<timestamp>/`。基础设施脚本通过此安装器独立发布；普通应用 CI 发布不会替换监控密钥或停止探针。Python 测试不依赖数据库：

```bash
python3 -m unittest discover -s scripts/ops/tests -v
```

状态文件位于 `/var/lib/surprising-wallet-monitor/`，以原子替换方式保存；文件锁防止巡检与日报重叠修改状态。只保存资源统计、告警状态和时间，不保存 Telegram 凭据。每次巡检覆盖 `last-report.json`，不会无限追加指标。

```bash
systemctl status wallet-monitor.timer wallet-monitor-summary.timer
systemctl start wallet-monitor.service
journalctl -u wallet-monitor.service -n 30
cat /var/lib/surprising-wallet-monitor/last-report.json
```

### 回滚与影响

停止 `wallet-monitor.timer` 和 `wallet-monitor-summary.timer`，等待当前探针结束；按备份恢复之前的脚本、单元和日志配置，然后 `systemctl daemon-reload`，恢复原定时器状态。应用日志策略随 JAR 回滚。缩短日志保留期可能清理过期归档，回滚配置无法找回已清理日志。监控无自动重启/杀进程/清理队列行为；不会更改钱包运行开关。

本次仅调整日志和运维监控，架构图文已说明监控依赖；业务数据流、表结构和 init-sql 无变化。
