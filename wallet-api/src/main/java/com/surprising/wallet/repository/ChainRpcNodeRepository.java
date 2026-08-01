package com.surprising.wallet.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * chain_rpc_node 单表仓储。
 */
@Repository
public class ChainRpcNodeRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造 RPC 节点仓储。 */
    public ChainRpcNodeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 查询指定链和网络的 RPC 节点。 */
    public List<Map<String, Object>> listByChain(String chain, String network) {
        return jdbc.queryForList("""
                select id, chain, network, environment, node_label, purpose, connection_type,
                       rpc_url, auth_type, auth_header_name, api_key, api_key_ref,
                       username, username_ref, password, password_ref,
                       priority, min_request_interval_ms, enabled, renewal_due_at, remark,
                       last_checked_at, last_latency_ms, last_http_status, last_error,
                       created_at, updated_at
                  from chain_rpc_node
                 where chain = ? and network = ?
                 order by environment, purpose, priority, id
                """, chain, network);
    }

    /** 查询全部 RPC 节点概览字段。 */
    public List<Map<String, Object>> listOverview() {
        return jdbc.queryForList("""
                select chain, network, environment, enabled
                  from chain_rpc_node
                 order by chain, network, environment, priority, id
                """);
    }

    /** 查询指定节点。 */
    public Map<String, Object> findById(long id, String chain, String network) {
        return jdbc.queryForMap("""
                select id, environment, node_label, purpose, connection_type, rpc_url,
                       auth_type, auth_header_name, api_key, username, password, priority,
                       min_request_interval_ms, enabled, renewal_due_at, remark
                  from chain_rpc_node
                 where id = ? and chain = ? and network = ?
                """, id, chain, network);
    }

    /** 将 RPC 节点迁移到新的链或网络名称。 */
    public int moveChain(String currentChain, String currentNetwork,
                         String targetChain, String targetNetwork) {
        return jdbc.update("""
                update chain_rpc_node
                   set chain = ?, network = ?, updated_at = now()
                 where chain = ? and network = ?
                """, targetChain, targetNetwork, currentChain, currentNetwork);
    }

    /** 查询链启用校验所需的节点。 */
    public List<Map<String, Object>> listEnabledForPurpose(
            String chain, String network, String environment, String purpose) {
        return jdbc.queryForList("""
                select environment, node_label, purpose, connection_type, rpc_url, auth_type,
                       auth_header_name, api_key, username, password, priority,
                       min_request_interval_ms, enabled, renewal_due_at, remark
                  from chain_rpc_node
                 where upper(chain) = upper(?) and lower(network) = lower(?)
                   and lower(environment) = lower(?) and lower(purpose) = lower(?) and enabled = true
                """, chain, network, environment, purpose);
    }

    /** 查询 token 合约校验所需的首个 JSON-RPC 节点。 */
    public List<Map<String, Object>> listTokenValidationNodes(
            String chain, String network, String environment) {
        return jdbc.queryForList("""
                select id, environment, node_label, purpose, connection_type, rpc_url,
                       auth_type, auth_header_name, api_key, username, password, priority,
                       min_request_interval_ms, enabled, renewal_due_at, remark
                  from chain_rpc_node
                 where upper(chain) = upper(?) and lower(network) = lower(?)
                   and lower(environment) = lower(?) and enabled = true
                   and connection_type ilike '%JSON_RPC%' and rpc_url ~* '^https?://'
                 order by case when lower(purpose) = 'rpc' then 0
                               when lower(purpose) = 'scan' then 1 else 2 end,
                          priority, id
                 limit 1
                """, chain, network, environment);
    }

    /** 判断指定用途是否还有其他启用节点。 */
    public long countEnabledAlternatives(String chain, String network, String environment,
                                         String purpose, long excludedId) {
        Long count = jdbc.queryForObject("""
                select count(*) from chain_rpc_node
                 where upper(chain) = upper(?) and lower(network) = lower(?)
                   and lower(environment) = lower(?) and lower(purpose) = lower(?)
                   and enabled = true and id <> ?
                """, Long.class, chain, network, environment, purpose, excludedId);
        return count == null ? 0 : count;
    }

    /** 创建 RPC 节点并返回主键。 */
    public long insert(String chain, String network, String environment, String nodeLabel,
                       String purpose, String connectionType, String rpcUrl, String authType,
                       String authHeaderName, String apiKey, String username, String password,
                       int priority, int minRequestIntervalMs, boolean enabled,
                       Instant renewalDueAt, String remark) {
        Long id = jdbc.queryForObject("""
                insert into chain_rpc_node(
                    chain, network, environment, node_label, purpose, connection_type, rpc_url,
                    auth_type, auth_header_name, api_key, username, password, priority,
                    min_request_interval_ms, enabled, renewal_due_at, remark, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now()) returning id
                """, Long.class, chain, network, environment, nodeLabel, purpose, connectionType,
                rpcUrl, authType, authHeaderName, apiKey, username, password, priority,
                minRequestIntervalMs, enabled, renewalDueAt, remark);
        return id;
    }

    /** 更新 RPC 节点。 */
    public int update(long id, String chain, String network, String environment, String nodeLabel,
                      String purpose, String connectionType, String rpcUrl, String authType,
                      String authHeaderName, String apiKey, String username, String password,
                      int priority, int minRequestIntervalMs, boolean enabled,
                      Instant renewalDueAt, String remark) {
        return jdbc.update("""
                update chain_rpc_node
                   set environment = ?, node_label = ?, purpose = ?, connection_type = ?,
                       rpc_url = ?, auth_type = ?, auth_header_name = ?, api_key = ?, username = ?,
                       password = ?, priority = ?, min_request_interval_ms = ?, enabled = ?,
                       renewal_due_at = ?, remark = ?, updated_at = now()
                 where id = ? and chain = ? and network = ?
                """, environment, nodeLabel, purpose, connectionType, rpcUrl, authType,
                authHeaderName, apiKey, username, password, priority, minRequestIntervalMs,
                enabled, renewalDueAt, remark, id, chain, network);
    }

    /** 删除 RPC 节点。 */
    public int delete(long id, String chain, String network) {
        return jdbc.update("delete from chain_rpc_node where id = ? and chain = ? and network = ?",
                id, chain, network);
    }

    /** 更新 RPC 节点探测结果。 */
    public int updateProbe(long nodeId, Instant checkedAt, long latencyMs,
                           Integer statusCode, String error) {
        return jdbc.update("""
                update chain_rpc_node
                   set last_checked_at = ?, last_latency_ms = ?, last_http_status = ?, last_error = ?
                 where id = ?
                """, Timestamp.from(checkedAt), latencyMs, statusCode, error, nodeId);
    }
}
