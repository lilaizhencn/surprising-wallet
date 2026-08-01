package com.surprising.wallet.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * wallet_system_config 单表仓储。
 */
@Repository
public class WalletSystemConfigRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造全局配置仓储。 */
    public WalletSystemConfigRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 查询全局任务开关。 */
    public List<Map<String, Object>> listGlobalSwitches() {
        return jdbc.queryForList("""
                select config_key, config_value, enabled
                  from wallet_system_config
                 where config_key in (
                    'global.all.enabled', 'global.scan.enabled',
                    'global.withdraw.enabled', 'global.collection.enabled',
                    'global.transfer.enabled')
                """);
    }

    /** 查询全部系统配置。 */
    public List<Map<String, Object>> listAll() {
        return jdbc.queryForList("""
                select config_key, config_value, enabled
                  from wallet_system_config
                """);
    }

    /** 写入布尔类型系统配置。 */
    public void upsertBoolean(String key, boolean value) {
        jdbc.update("""
                insert into wallet_system_config(
                    config_key, config_value, value_type, enabled, remark)
                values (?, ?, 'boolean', true,
                        'Managed by the platform wallet configuration console')
                on conflict (config_key) do update set
                    config_value = excluded.config_value,
                    value_type = 'boolean', enabled = true, updated_at = now()
                """, key, Boolean.toString(value));
    }
}
