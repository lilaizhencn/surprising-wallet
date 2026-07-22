package com.surprising.wallet.jobs.devfaucet;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
@ConditionalOnProperty(prefix = "sw.wallet.dev-faucet", name = "enabled", havingValue = "true")
class DevFaucetRepository {
    private final JdbcTemplate jdbc;

    DevFaucetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<Candidate> discover(int limit) {
        return jdbc.query("""
                select candidate.tenant_id, candidate.custody_address_id, candidate.chain,
                       candidate.network, candidate.asset_symbol, candidate.purpose,
                       candidate.address, candidate.contract_address, candidate.decimals
                  from (
                    select c.tenant_id, c.id as custody_address_id, c.chain, c.network,
                           native.symbol as asset_symbol,
                           case when gas.id is null then 'CUSTOMER_DEPOSIT'
                                else 'TENANT_GAS' end as purpose,
                           c.address, cast(null as varchar) as contract_address,
                           native.decimals
                      from custody_address c
                      join custody_tenant tenant
                        on tenant.id = c.tenant_id and tenant.status = 'ACTIVE'
                      join custody_tenant_chain tenant_chain
                        on tenant_chain.tenant_id = c.tenant_id
                       and tenant_chain.chain = c.chain
                       and tenant_chain.status = 'ACTIVE'
                      join chain_profile profile
                        on profile.chain = c.chain
                       and lower(profile.network) = lower(c.network)
                       and profile.enabled = true and profile.scan_enabled = true
                      join chain_asset native
                        on native.chain = c.chain and native.native_asset = true
                       and native.active = true
                      left join custody_gas_account gas
                        on gas.tenant_id = c.tenant_id
                       and gas.custody_address_id = c.id
                       and gas.status = 'ACTIVE'
                     where c.status = 'ACTIVE'
                       and c.chain in ('BTC', 'ETH')
                       and ((c.source = 'API' and gas.id is null) or gas.id is not null)
                    union all
                    select c.tenant_id, c.id, c.chain, c.network, token.symbol,
                           'CUSTOMER_DEPOSIT', c.address, token.contract_address,
                           token.decimals
                      from custody_address c
                      join custody_tenant tenant
                        on tenant.id = c.tenant_id and tenant.status = 'ACTIVE'
                      join custody_tenant_chain tenant_chain
                        on tenant_chain.tenant_id = c.tenant_id
                       and tenant_chain.chain = c.chain
                       and tenant_chain.status = 'ACTIVE'
                      join chain_profile profile
                        on profile.chain = c.chain
                       and lower(profile.network) = lower(c.network)
                       and profile.enabled = true and profile.scan_enabled = true
                      join token_config token
                        on token.chain = c.chain and token.symbol in ('USDT', 'USDC')
                       and token.enabled = true
                       and (token.network is null
                            or lower(token.network) = lower(profile.network))
                      join chain_asset asset
                        on asset.chain = token.chain and asset.symbol = token.symbol
                       and asset.active = true and asset.native_asset = false
                       and lower(asset.contract_address) = lower(token.contract_address)
                     where c.status = 'ACTIVE' and c.source = 'API' and c.chain = 'ETH'
                       and not exists (
                           select 1 from custody_gas_account gas
                            where gas.tenant_id = c.tenant_id
                              and gas.custody_address_id = c.id
                       )
                  ) candidate
                 where not exists (
                     select 1 from custody_dev_faucet_funding funding
                      where funding.custody_address_id = candidate.custody_address_id
                        and funding.asset_symbol = candidate.asset_symbol
                        and funding.purpose = candidate.purpose
                 )
                 order by candidate.custody_address_id, candidate.asset_symbol
                 limit ?
                """, (rs, rowNum) -> new Candidate(
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("custody_address_id", UUID.class),
                rs.getString("chain"),
                rs.getString("network"),
                rs.getString("asset_symbol"),
                rs.getString("purpose"),
                rs.getString("address"),
                rs.getString("contract_address"),
                rs.getInt("decimals")), limit);
    }

    boolean create(Candidate candidate, BigDecimal amount) {
        return jdbc.update("""
                insert into custody_dev_faucet_funding(
                    id, tenant_id, custody_address_id, chain, network, asset_symbol,
                    purpose, address, contract_address, decimals, requested_amount)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (custody_address_id, asset_symbol, purpose) do nothing
                """, UUID.randomUUID(), candidate.tenantId(), candidate.custodyAddressId(),
                candidate.chain(), candidate.network(), candidate.assetSymbol(),
                candidate.purpose(), candidate.address(), candidate.contractAddress(),
                candidate.decimals(), amount) == 1;
    }

    List<DevFaucetFunding> due(int limit, int maxAttempts) {
        return jdbc.query("""
                select id, tenant_id, custody_address_id, chain, network, asset_symbol,
                       purpose, address, contract_address, decimals, requested_amount, attempts
                  from custody_dev_faucet_funding
                 where status in ('PENDING', 'FAILED')
                   and attempts < ? and next_attempt_at <= now()
                 order by created_at, id
                 limit ?
                """, (rs, rowNum) -> new DevFaucetFunding(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("custody_address_id", UUID.class),
                rs.getString("chain"),
                rs.getString("network"),
                rs.getString("asset_symbol"),
                rs.getString("purpose"),
                rs.getString("address"),
                rs.getString("contract_address"),
                rs.getInt("decimals"),
                rs.getBigDecimal("requested_amount"),
                rs.getInt("attempts")), maxAttempts, limit);
    }

    boolean markSending(UUID id) {
        return jdbc.update("""
                update custody_dev_faucet_funding
                   set status = 'SENDING', attempts = attempts + 1,
                       last_error = null, updated_at = now()
                 where id = ? and status in ('PENDING', 'FAILED')
                """, id) == 1;
    }

    void markSent(UUID id, String txHash) {
        jdbc.update("""
                update custody_dev_faucet_funding
                   set status = 'SENT', tx_hash = ?, sent_at = now(), updated_at = now()
                 where id = ? and status = 'SENDING'
                """, txHash, id);
    }

    void markFailed(UUID id, String error, Duration retryDelay) {
        jdbc.update("""
                update custody_dev_faucet_funding
                   set status = 'FAILED', last_error = ?, next_attempt_at = ?, updated_at = now()
                 where id = ? and status = 'SENDING'
                """, truncate(error), Timestamp.from(Instant.now().plus(retryDelay)), id);
    }

    void markUnknown(UUID id, String error) {
        jdbc.update("""
                update custody_dev_faucet_funding
                   set status = 'UNKNOWN', last_error = ?, updated_at = now()
                 where id = ? and status = 'SENDING'
                """, truncate(error), id);
    }

    int recoverStaleSending(Duration age) {
        return jdbc.update("""
                update custody_dev_faucet_funding
                   set status = 'UNKNOWN',
                       last_error = coalesce(last_error,
                           'worker stopped while RPC outcome was unknown'),
                       updated_at = now()
                 where status = 'SENDING' and updated_at < ?
                """, Timestamp.from(Instant.now().minus(age)));
    }

    int reconcileConfirmed() {
        return jdbc.update("""
                update custody_dev_faucet_funding funding
                   set status = 'CONFIRMED', confirmed_at = deposit.credited_at,
                       updated_at = now()
                  from custody_deposit deposit
                 where funding.status = 'SENT'
                   and deposit.tenant_id = funding.tenant_id
                   and deposit.custody_address_id = funding.custody_address_id
                   and deposit.chain = funding.chain
                   and deposit.asset_symbol = funding.asset_symbol
                   and lower(deposit.tx_hash) = lower(funding.tx_hash)
                   and deposit.status = 'CONFIRMED'
                """);
    }

    private static String truncate(String value) {
        String safe = value == null ? "unknown error" : value;
        return safe.length() <= 1000 ? safe : safe.substring(0, 1000);
    }

    record Candidate(
            UUID tenantId,
            UUID custodyAddressId,
            String chain,
            String network,
            String assetSymbol,
            String purpose,
            String address,
            String contractAddress,
            int decimals
    ) {
    }
}
