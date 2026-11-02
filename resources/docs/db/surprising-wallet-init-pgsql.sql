--
-- PostgreSQL database dump
--


-- Dumped from database version 18.4 (Homebrew)
-- Dumped by pg_dump version 18.4 (Homebrew)
--
-- Canonical initialization baseline for a new development or test database.
-- Schema, chain/token configuration and safety defaults are maintained here.

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

-- Validate the queue API before the destructive fresh-database baseline begins.
DO $$ BEGIN
    IF current_setting('server_version_num')::integer < 170000 THEN
        RAISE EXCEPTION 'wallet database requires PostgreSQL 17 or newer';
    END IF;
END $$;
CREATE EXTENSION IF NOT EXISTS pgmq;
DO $$ BEGIN
    IF to_regprocedure('pgmq.create(text)') IS NULL
       OR to_regprocedure('pgmq.send(text,jsonb,jsonb)') IS NULL
       OR to_regprocedure('pgmq.read(text,integer,integer,jsonb)') IS NULL
       OR to_regprocedure('pgmq.archive(text,bigint)') IS NULL
       OR to_regprocedure('pgmq.set_vt(text,bigint,integer)') IS NULL
       OR NOT EXISTS (
           SELECT 1 FROM pg_attribute
           WHERE attrelid = (SELECT typrelid FROM pg_type WHERE oid = to_regtype('pgmq.message_record'))
             AND attname IN ('msg_id','read_ct','message','headers')
           GROUP BY attrelid HAVING count(*) = 4
       ) THEN
        RAISE EXCEPTION 'wallet database requires PGMQ queue headers and lease APIs';
    END IF;
END $$;

DROP TABLE IF EXISTS public.wallet_withdrawal_approval;

ALTER TABLE IF EXISTS public.chain_address DROP CONSTRAINT IF EXISTS chain_address_mpc_key_fkey;
ALTER TABLE IF EXISTS public.hypercore_action_record DROP CONSTRAINT IF EXISTS hypercore_action_record_request_fk;
DROP TABLE IF EXISTS public.hypercore_ledger_cursor;
DROP TABLE IF EXISTS public.cardano_owned_output;
DROP TABLE IF EXISTS public.cardano_transaction;
DROP TABLE IF EXISTS public.polkadot_transaction;
ALTER TABLE IF EXISTS public.monero_transaction DROP CONSTRAINT IF EXISTS monero_transaction_request_fk;
ALTER TABLE IF EXISTS public.monero_transaction DROP CONSTRAINT IF EXISTS monero_transaction_attempt_fk;
DROP TABLE IF EXISTS public.wallet_mpc_relay, public.wallet_mpc_sign_attempt, public.wallet_mpc_sign_request,
    public.wallet_mpc_key_epoch, public.wallet_mpc_key;
DROP TABLE IF EXISTS public.evm_withdrawal_batch_attempt;
DROP TABLE IF EXISTS public.evm_withdrawal_batch_item;
DROP TABLE IF EXISTS public.evm_withdrawal_batch;
DROP TABLE IF EXISTS public.evm_7702_payout_account;
DROP TABLE IF EXISTS public.evm_collection_batch_attempt;
DROP TABLE IF EXISTS public.evm_collection_batch_item;
DROP TABLE IF EXISTS public.evm_collection_batch;
DROP TABLE IF EXISTS public.evm_7702_account;
DROP TABLE IF EXISTS public.evm_7702_config;
DROP TABLE IF EXISTS public.chain_fee_rate;
DROP TABLE IF EXISTS public.wallet_task_lease;
ALTER TABLE IF EXISTS ONLY public.collection_record DROP CONSTRAINT IF EXISTS collection_record_custody_fk;
ALTER TABLE IF EXISTS ONLY public.collection_record DROP CONSTRAINT IF EXISTS collection_record_tenant_fk;
ALTER TABLE IF EXISTS ONLY public.collection_record DROP CONSTRAINT IF EXISTS collection_record_tenant_id_key;

ALTER TABLE IF EXISTS ONLY public.withdrawal_order DROP CONSTRAINT IF EXISTS withdrawal_order_tenant_id_fkey;
ALTER TABLE IF EXISTS ONLY public.ledger_balance DROP CONSTRAINT IF EXISTS ledger_balance_tenant_id_fkey;
ALTER TABLE IF EXISTS ONLY public.chain_signing_transaction DROP CONSTRAINT IF EXISTS chain_signing_transaction_tenant_fkey;
ALTER TABLE IF EXISTS ONLY public.deposit_record DROP CONSTRAINT IF EXISTS deposit_record_tenant_id_fkey;
ALTER TABLE IF EXISTS ONLY public.custody_asset_recovery DROP CONSTRAINT IF EXISTS custody_asset_recovery_tenant_id_fkey;
ALTER TABLE IF EXISTS ONLY public.custody_asset_recovery DROP CONSTRAINT IF EXISTS custody_asset_recovery_address_fk;
ALTER TABLE IF EXISTS ONLY public.custody_reorg_deficit DROP CONSTRAINT IF EXISTS custody_reorg_deficit_tenant_id_fkey;
ALTER TABLE IF EXISTS ONLY public.custody_reorg_deficit DROP CONSTRAINT IF EXISTS custody_reorg_deficit_address_fk;
ALTER TABLE IF EXISTS ONLY public.custody_reorg_deficit DROP CONSTRAINT IF EXISTS custody_reorg_deficit_deposit_record_fkey;
ALTER TABLE IF EXISTS ONLY public.custody_withdrawal DROP CONSTRAINT IF EXISTS custody_withdrawal_tenant_id_fkey;
ALTER TABLE IF EXISTS ONLY public.custody_withdrawal DROP CONSTRAINT IF EXISTS custody_withdrawal_order_fk;
ALTER TABLE IF EXISTS ONLY public.custody_withdrawal DROP CONSTRAINT IF EXISTS custody_withdrawal_custody_address_id_fkey;
ALTER TABLE IF EXISTS ONLY public.custody_withdrawal DROP CONSTRAINT IF EXISTS custody_withdrawal_address_fk;
ALTER TABLE IF EXISTS ONLY public.custody_webhook_endpoint DROP CONSTRAINT IF EXISTS custody_webhook_endpoint_tenant_id_fkey;
ALTER TABLE IF EXISTS ONLY public.custody_webhook_endpoint DROP CONSTRAINT IF EXISTS custody_webhook_endpoint_creator_fk;
ALTER TABLE IF EXISTS ONLY public.custody_webhook_endpoint DROP CONSTRAINT IF EXISTS custody_webhook_endpoint_created_by_fkey;
ALTER TABLE IF EXISTS ONLY public.custody_webhook_delivery DROP CONSTRAINT IF EXISTS custody_webhook_delivery_tenant_id_fkey;
ALTER TABLE IF EXISTS ONLY public.custody_webhook_delivery DROP CONSTRAINT IF EXISTS custody_webhook_delivery_event_id_fkey;
ALTER TABLE IF EXISTS ONLY public.custody_webhook_delivery DROP CONSTRAINT IF EXISTS custody_webhook_delivery_event_fk;
ALTER TABLE IF EXISTS ONLY public.custody_webhook_delivery DROP CONSTRAINT IF EXISTS custody_webhook_delivery_endpoint_id_fkey;
ALTER TABLE IF EXISTS ONLY public.custody_webhook_delivery DROP CONSTRAINT IF EXISTS custody_webhook_delivery_endpoint_fk;
ALTER TABLE IF EXISTS ONLY public.custody_webhook_delivery_attempt DROP CONSTRAINT IF EXISTS custody_webhook_delivery_attempt_tenant_id_fkey;
ALTER TABLE IF EXISTS ONLY public.custody_webhook_delivery_attempt DROP CONSTRAINT IF EXISTS custody_webhook_delivery_attempt_delivery_id_fkey;
ALTER TABLE IF EXISTS ONLY public.custody_webhook_delivery_attempt DROP CONSTRAINT IF EXISTS custody_webhook_attempt_delivery_fk;
ALTER TABLE IF EXISTS ONLY public.custody_tenant_user DROP CONSTRAINT IF EXISTS custody_tenant_user_tenant_id_fkey;
ALTER TABLE IF EXISTS ONLY public.custody_tenant_chain DROP CONSTRAINT IF EXISTS custody_tenant_chain_tenant_fk;
ALTER TABLE IF EXISTS ONLY public.custody_tenant_chain DROP CONSTRAINT IF EXISTS custody_tenant_chain_opened_by_fk;
ALTER TABLE IF EXISTS ONLY public.custody_tenant_chain DROP CONSTRAINT IF EXISTS custody_tenant_chain_closed_by_fk;
ALTER TABLE IF EXISTS ONLY public.custody_session DROP CONSTRAINT IF EXISTS custody_session_tenant_user_id_fkey;
ALTER TABLE IF EXISTS ONLY public.custody_session DROP CONSTRAINT IF EXISTS custody_session_tenant_user_fk;
ALTER TABLE IF EXISTS ONLY public.custody_session DROP CONSTRAINT IF EXISTS custody_session_tenant_id_fkey;
ALTER TABLE IF EXISTS ONLY public.custody_ledger_entry DROP CONSTRAINT IF EXISTS custody_ledger_entry_tenant_id_fkey;
ALTER TABLE IF EXISTS ONLY public.custody_ledger_entry DROP CONSTRAINT IF EXISTS custody_ledger_entry_custody_address_id_fkey;
ALTER TABLE IF EXISTS ONLY public.custody_ledger_entry DROP CONSTRAINT IF EXISTS custody_ledger_entry_address_fk;
ALTER TABLE IF EXISTS ONLY public.custody_ip_rule DROP CONSTRAINT IF EXISTS custody_ip_rule_tenant_id_fkey;
ALTER TABLE IF EXISTS ONLY public.custody_ip_rule DROP CONSTRAINT IF EXISTS custody_ip_rule_creator_fk;
ALTER TABLE IF EXISTS ONLY public.custody_ip_rule DROP CONSTRAINT IF EXISTS custody_ip_rule_created_by_fkey;
ALTER TABLE IF EXISTS ONLY public.custody_idempotency_key DROP CONSTRAINT IF EXISTS custody_idempotency_key_tenant_id_fkey;
ALTER TABLE IF EXISTS ONLY public.custody_gas_usage DROP CONSTRAINT IF EXISTS custody_gas_usage_withdrawal_fk;
ALTER TABLE IF EXISTS ONLY public.custody_gas_usage DROP CONSTRAINT IF EXISTS custody_gas_usage_tenant_id_fkey;
ALTER TABLE IF EXISTS ONLY public.custody_gas_usage DROP CONSTRAINT IF EXISTS custody_gas_usage_gas_account_id_fkey;
ALTER TABLE IF EXISTS ONLY public.custody_gas_usage DROP CONSTRAINT IF EXISTS custody_gas_usage_custody_withdrawal_id_fkey;
ALTER TABLE IF EXISTS ONLY public.custody_gas_usage DROP CONSTRAINT IF EXISTS custody_gas_usage_account_fk;
ALTER TABLE IF EXISTS ONLY public.custody_gas_account DROP CONSTRAINT IF EXISTS custody_gas_account_tenant_id_fkey;
ALTER TABLE IF EXISTS ONLY public.custody_gas_account DROP CONSTRAINT IF EXISTS custody_gas_account_custody_address_id_fkey;
ALTER TABLE IF EXISTS ONLY public.custody_gas_account DROP CONSTRAINT IF EXISTS custody_gas_account_creator_fk;
ALTER TABLE IF EXISTS ONLY public.custody_gas_account DROP CONSTRAINT IF EXISTS custody_gas_account_created_by_fkey;
ALTER TABLE IF EXISTS ONLY public.custody_gas_account DROP CONSTRAINT IF EXISTS custody_gas_account_address_fk;
ALTER TABLE IF EXISTS ONLY public.custody_event DROP CONSTRAINT IF EXISTS custody_event_tenant_id_fkey;
ALTER TABLE IF EXISTS ONLY public.custody_derivation_subject DROP CONSTRAINT IF EXISTS custody_derivation_subject_tenant_id_fkey;
ALTER TABLE IF EXISTS ONLY public.custody_dev_faucet_funding DROP CONSTRAINT IF EXISTS custody_dev_faucet_funding_tenant_id_fkey;
ALTER TABLE IF EXISTS ONLY public.custody_dev_faucet_funding DROP CONSTRAINT IF EXISTS custody_dev_faucet_funding_address_fk;
ALTER TABLE IF EXISTS ONLY public.custody_deposit DROP CONSTRAINT IF EXISTS custody_deposit_tenant_id_fkey;
ALTER TABLE IF EXISTS ONLY public.custody_deposit DROP CONSTRAINT IF EXISTS custody_deposit_record_fk;
ALTER TABLE IF EXISTS ONLY public.custody_deposit DROP CONSTRAINT IF EXISTS custody_deposit_deposit_record_id_fkey;
ALTER TABLE IF EXISTS ONLY public.custody_deposit DROP CONSTRAINT IF EXISTS custody_deposit_custody_address_id_fkey;
ALTER TABLE IF EXISTS ONLY public.custody_deposit DROP CONSTRAINT IF EXISTS custody_deposit_address_fk;
ALTER TABLE IF EXISTS ONLY public.custody_audit_log DROP CONSTRAINT IF EXISTS custody_audit_log_tenant_id_fkey;
ALTER TABLE IF EXISTS ONLY public.custody_api_nonce DROP CONSTRAINT IF EXISTS custody_api_nonce_key_id_fkey;
ALTER TABLE IF EXISTS ONLY public.custody_api_key DROP CONSTRAINT IF EXISTS custody_api_key_tenant_id_fkey;
ALTER TABLE IF EXISTS ONLY public.custody_api_key DROP CONSTRAINT IF EXISTS custody_api_key_creator_fk;
ALTER TABLE IF EXISTS ONLY public.custody_api_key DROP CONSTRAINT IF EXISTS custody_api_key_created_by_fkey;
ALTER TABLE IF EXISTS ONLY public.custody_address DROP CONSTRAINT IF EXISTS custody_address_tenant_id_fkey;
ALTER TABLE IF EXISTS ONLY public.custody_address DROP CONSTRAINT IF EXISTS custody_address_creator_fk;
ALTER TABLE IF EXISTS ONLY public.custody_address DROP CONSTRAINT IF EXISTS custody_address_created_by_fkey;
ALTER TABLE IF EXISTS ONLY public.custody_address DROP CONSTRAINT IF EXISTS custody_address_chain_address_tenant_fk;
ALTER TABLE IF EXISTS ONLY public.custody_address DROP CONSTRAINT IF EXISTS custody_address_chain_address_id_fkey;
ALTER TABLE IF EXISTS ONLY public.chain_address DROP CONSTRAINT IF EXISTS chain_address_tenant_id_fkey;
DROP INDEX IF EXISTS public.withdrawal_review_audit_user_time_idx;
DROP INDEX IF EXISTS public.withdrawal_review_audit_order_idx;
DROP INDEX IF EXISTS public.withdrawal_review_audit_admin_time_idx;
DROP INDEX IF EXISTS public.token_config_one_enabled_network_per_asset_idx;
DROP INDEX IF EXISTS public.token_config_chain_network_symbol_key;
DROP INDEX IF EXISTS public.token_config_chain_network_contract_address_key;
DROP INDEX IF EXISTS public.idx_wallet_user_session_user;
DROP INDEX IF EXISTS public.idx_wallet_transfer_order_user;
DROP INDEX IF EXISTS public.idx_wallet_transfer_order_to_recent;
DROP INDEX IF EXISTS public.idx_wallet_transfer_order_from_recent;
DROP INDEX IF EXISTS public.idx_utxo_record_spendable;
DROP INDEX IF EXISTS public.idx_utxo_record_address;
DROP INDEX IF EXISTS public.idx_hypercore_token_metadata_name;
DROP INDEX IF EXISTS public.idx_contract_deployment_order_user_recent;
DROP INDEX IF EXISTS public.idx_contract_deployment_order_user;
DROP INDEX IF EXISTS public.idx_chain_signing_transaction_tx_id;
DROP INDEX IF EXISTS public.idx_chain_signing_transaction_status;
DROP INDEX IF EXISTS public.idx_chain_address_scan;
DROP INDEX IF EXISTS public.idx_chain_address_owner;
DROP INDEX IF EXISTS public.idx_chain_address_collection_candidates;
DROP INDEX IF EXISTS public.idx_collection_record_collection_balance;
DROP INDEX IF EXISTS public.idx_custody_address_collection_candidates;
DROP INDEX IF EXISTS public.idx_deposit_record_collection_balance;
DROP INDEX IF EXISTS public.custody_withdrawal_tenant_time_idx;
DROP INDEX IF EXISTS public.custody_withdrawal_external_reference_key;
DROP INDEX IF EXISTS public.custody_withdrawal_subject_idx;
DROP INDEX IF EXISTS public.ledger_balance_pool_sources_idx;
DROP INDEX IF EXISTS public.custody_withdrawal_idempotency_key;
DROP INDEX IF EXISTS public.custody_webhook_endpoint_active_idx;
DROP INDEX IF EXISTS public.custody_webhook_delivery_tenant_time_idx;
DROP INDEX IF EXISTS public.custody_webhook_delivery_due_idx;
DROP INDEX IF EXISTS public.custody_webhook_attempt_tenant_time_idx;
DROP INDEX IF EXISTS public.custody_webhook_attempt_delivery_time_idx;
DROP INDEX IF EXISTS public.custody_tenant_user_email_key;
DROP INDEX IF EXISTS public.custody_tenant_chain_status_idx;
DROP INDEX IF EXISTS public.custody_session_active_idx;
DROP INDEX IF EXISTS public.custody_ledger_entry_tenant_asset_time_idx;
DROP INDEX IF EXISTS public.custody_ip_rule_enabled_idx;
DROP INDEX IF EXISTS public.custody_idempotency_expiry_idx;
DROP INDEX IF EXISTS public.custody_gas_usage_pending_idx;
DROP INDEX IF EXISTS public.custody_gas_usage_account_time_idx;
DROP INDEX IF EXISTS public.custody_gas_account_tenant_status_idx;
DROP INDEX IF EXISTS public.custody_event_tenant_time_idx;
DROP INDEX IF EXISTS public.custody_event_pending_idx;
DROP INDEX IF EXISTS public.custody_dev_faucet_funding_tenant_time_idx;
DROP INDEX IF EXISTS public.custody_dev_faucet_funding_due_idx;
DROP INDEX IF EXISTS public.custody_deposit_tenant_time_idx;
DROP INDEX IF EXISTS public.custody_audit_log_tenant_time_idx;
DROP INDEX IF EXISTS public.custody_api_nonce_expiry_idx;
DROP INDEX IF EXISTS public.custody_api_key_tenant_idx;
DROP INDEX IF EXISTS public.custody_address_tenant_subject_idx;
DROP INDEX IF EXISTS public.custody_address_tenant_chain_subject_key;
DROP INDEX IF EXISTS public.custody_address_tenant_chain_subject_version_key;
DROP INDEX IF EXISTS public.custody_address_tenant_reference_idx;
DROP INDEX IF EXISTS public.custody_address_tenant_created_idx;
DROP INDEX IF EXISTS public.custody_address_lookup_idx;
DROP INDEX IF EXISTS public.custody_address_derivation_key;
DROP INDEX IF EXISTS public.chain_profile_one_enabled_network_idx;
ALTER TABLE IF EXISTS ONLY public.xrp_transaction DROP CONSTRAINT IF EXISTS xrp_transaction_pkey;
ALTER TABLE IF EXISTS ONLY public.xrp_transaction DROP CONSTRAINT IF EXISTS xrp_transaction_chain_tx_hash_key;
ALTER TABLE IF EXISTS ONLY public.withdrawal_review_audit DROP CONSTRAINT IF EXISTS withdrawal_review_audit_pkey;
ALTER TABLE IF EXISTS ONLY public.withdrawal_order DROP CONSTRAINT IF EXISTS withdrawal_order_tenant_id_key;
ALTER TABLE IF EXISTS ONLY public.withdrawal_order DROP CONSTRAINT IF EXISTS withdrawal_order_pkey;
ALTER TABLE IF EXISTS ONLY public.wallet_user_session DROP CONSTRAINT IF EXISTS wallet_user_session_token_hash_key;
ALTER TABLE IF EXISTS ONLY public.wallet_user_session DROP CONSTRAINT IF EXISTS wallet_user_session_pkey;
ALTER TABLE IF EXISTS ONLY public.wallet_user DROP CONSTRAINT IF EXISTS wallet_user_pkey;
ALTER TABLE IF EXISTS ONLY public.wallet_user DROP CONSTRAINT IF EXISTS wallet_user_email_key;
ALTER TABLE IF EXISTS ONLY public.wallet_transfer_order DROP CONSTRAINT IF EXISTS wallet_transfer_order_transfer_no_key;
ALTER TABLE IF EXISTS ONLY public.wallet_transfer_order DROP CONSTRAINT IF EXISTS wallet_transfer_order_pkey;
ALTER TABLE IF EXISTS ONLY public.wallet_system_config DROP CONSTRAINT IF EXISTS wallet_system_config_pkey;
ALTER TABLE IF EXISTS ONLY public.wallet_public_key DROP CONSTRAINT IF EXISTS wallet_public_key_pkey;
ALTER TABLE IF EXISTS ONLY public.utxo_record DROP CONSTRAINT IF EXISTS utxo_record_pkey;
ALTER TABLE IF EXISTS ONLY public.utxo_record DROP CONSTRAINT IF EXISTS utxo_record_chain_tx_hash_vout_key;
ALTER TABLE IF EXISTS ONLY public.withdrawal_order DROP CONSTRAINT IF EXISTS uq_withdrawal_order_chain_order_no;
ALTER TABLE IF EXISTS ONLY public.collection_record DROP CONSTRAINT IF EXISTS uq_collection_record_chain_collection_no;
ALTER TABLE IF EXISTS ONLY public.tron_tx DROP CONSTRAINT IF EXISTS tron_tx_pkey;
ALTER TABLE IF EXISTS ONLY public.tron_tx DROP CONSTRAINT IF EXISTS tron_tx_chain_tx_hash_key;
ALTER TABLE IF EXISTS ONLY public.tron_transaction DROP CONSTRAINT IF EXISTS tron_transaction_pkey;
ALTER TABLE IF EXISTS ONLY public.tron_transaction DROP CONSTRAINT IF EXISTS tron_transaction_chain_tx_hash_key;
ALTER TABLE IF EXISTS ONLY public.tron_token_transfer DROP CONSTRAINT IF EXISTS tron_token_transfer_pkey;
ALTER TABLE IF EXISTS ONLY public.tron_token_transfer DROP CONSTRAINT IF EXISTS tron_token_transfer_chain_tx_hash_log_index_key;
ALTER TABLE IF EXISTS ONLY public.ton_transaction DROP CONSTRAINT IF EXISTS ton_transaction_pkey;
ALTER TABLE IF EXISTS ONLY public.ton_transaction DROP CONSTRAINT IF EXISTS ton_transaction_chain_tx_hash_key;
ALTER TABLE IF EXISTS ONLY public.token_registry DROP CONSTRAINT IF EXISTS token_registry_pkey;
ALTER TABLE IF EXISTS ONLY public.token_registry DROP CONSTRAINT IF EXISTS token_registry_chain_symbol_key;
ALTER TABLE IF EXISTS ONLY public.token_registry DROP CONSTRAINT IF EXISTS token_registry_chain_contract_address_key;
ALTER TABLE IF EXISTS ONLY public.token_config DROP CONSTRAINT IF EXISTS token_config_pkey;
ALTER TABLE IF EXISTS ONLY public.sui_transaction DROP CONSTRAINT IF EXISTS sui_transaction_pkey;
ALTER TABLE IF EXISTS ONLY public.sui_transaction DROP CONSTRAINT IF EXISTS sui_transaction_chain_tx_digest_key;
ALTER TABLE IF EXISTS ONLY public.sol_transaction DROP CONSTRAINT IF EXISTS sol_transaction_pkey;
ALTER TABLE IF EXISTS ONLY public.sol_transaction DROP CONSTRAINT IF EXISTS sol_transaction_chain_signature_key;
ALTER TABLE IF EXISTS ONLY public.near_transaction DROP CONSTRAINT IF EXISTS near_transaction_pkey;
ALTER TABLE IF EXISTS ONLY public.near_transaction DROP CONSTRAINT IF EXISTS near_transaction_chain_tx_hash_action_index_key;
ALTER TABLE IF EXISTS ONLY public.monero_transaction DROP CONSTRAINT IF EXISTS monero_transaction_pkey;
ALTER TABLE IF EXISTS ONLY public.monero_transaction DROP CONSTRAINT IF EXISTS monero_transaction_tenant_request_key;
ALTER TABLE IF EXISTS ONLY public.ledger_balance DROP CONSTRAINT IF EXISTS ledger_balance_tenant_id_key;
ALTER TABLE IF EXISTS ONLY public.ledger_balance DROP CONSTRAINT IF EXISTS ledger_balance_pkey;
ALTER TABLE IF EXISTS ONLY public.ledger_balance DROP CONSTRAINT IF EXISTS ledger_balance_chain_asset_symbol_account_id_key;
ALTER TABLE IF EXISTS ONLY public.hypercore_token_metadata DROP CONSTRAINT IF EXISTS hypercore_token_metadata_pkey;
ALTER TABLE IF EXISTS ONLY public.hypercore_spot_asset DROP CONSTRAINT IF EXISTS hypercore_spot_asset_pkey;
ALTER TABLE IF EXISTS ONLY public.hypercore_action_record DROP CONSTRAINT IF EXISTS hypercore_action_record_pkey;
ALTER TABLE IF EXISTS ONLY public.hot_wallet_address DROP CONSTRAINT IF EXISTS hot_wallet_address_pkey;
ALTER TABLE IF EXISTS ONLY public.hot_wallet_address DROP CONSTRAINT IF EXISTS hot_wallet_address_chain_asset_symbol_wallet_role_key;
ALTER TABLE IF EXISTS ONLY public.gas_topup_task DROP CONSTRAINT IF EXISTS gas_topup_task_task_no_key;
ALTER TABLE IF EXISTS ONLY public.gas_topup_task DROP CONSTRAINT IF EXISTS gas_topup_task_pkey;
ALTER TABLE IF EXISTS ONLY public.gas_topup_task DROP CONSTRAINT IF EXISTS gas_topup_task_chain_target_address_status_key;
ALTER TABLE IF EXISTS ONLY public.evm_tx DROP CONSTRAINT IF EXISTS evm_tx_pkey;
ALTER TABLE IF EXISTS ONLY public.evm_tx DROP CONSTRAINT IF EXISTS evm_tx_chain_tx_hash_key;
ALTER TABLE IF EXISTS ONLY public.evm_transaction DROP CONSTRAINT IF EXISTS evm_transaction_pkey;
ALTER TABLE IF EXISTS ONLY public.evm_transaction DROP CONSTRAINT IF EXISTS evm_transaction_chain_tx_hash_key;
ALTER TABLE IF EXISTS ONLY public.evm_token_transfer DROP CONSTRAINT IF EXISTS evm_token_transfer_pkey;
ALTER TABLE IF EXISTS ONLY public.evm_token_transfer DROP CONSTRAINT IF EXISTS evm_token_transfer_chain_tx_hash_log_index_key;
ALTER TABLE IF EXISTS ONLY public.evm_nonce DROP CONSTRAINT IF EXISTS evm_nonce_pkey;
ALTER TABLE IF EXISTS ONLY public.evm_nonce DROP CONSTRAINT IF EXISTS evm_nonce_chain_address_key;
ALTER TABLE IF EXISTS ONLY public.deposit_record DROP CONSTRAINT IF EXISTS deposit_record_tenant_id_key;
ALTER TABLE IF EXISTS ONLY public.deposit_record DROP CONSTRAINT IF EXISTS deposit_record_pkey;
ALTER TABLE IF EXISTS ONLY public.deposit_record DROP CONSTRAINT IF EXISTS deposit_record_chain_tx_hash_log_index_key;
ALTER TABLE IF EXISTS ONLY public.custody_withdrawal DROP CONSTRAINT IF EXISTS custody_withdrawal_tenant_order_key;
ALTER TABLE IF EXISTS ONLY public.custody_withdrawal DROP CONSTRAINT IF EXISTS custody_withdrawal_tenant_id_key;
ALTER TABLE IF EXISTS ONLY public.custody_withdrawal DROP CONSTRAINT IF EXISTS custody_withdrawal_pkey;
ALTER TABLE IF EXISTS ONLY public.custody_webhook_endpoint DROP CONSTRAINT IF EXISTS custody_webhook_endpoint_tenant_url_key;
ALTER TABLE IF EXISTS ONLY public.custody_webhook_endpoint DROP CONSTRAINT IF EXISTS custody_webhook_endpoint_tenant_id_key;
ALTER TABLE IF EXISTS ONLY public.custody_webhook_endpoint DROP CONSTRAINT IF EXISTS custody_webhook_endpoint_pkey;
ALTER TABLE IF EXISTS ONLY public.custody_webhook_delivery DROP CONSTRAINT IF EXISTS custody_webhook_delivery_tenant_id_key;
ALTER TABLE IF EXISTS ONLY public.custody_webhook_delivery DROP CONSTRAINT IF EXISTS custody_webhook_delivery_pkey;
ALTER TABLE IF EXISTS ONLY public.custody_webhook_delivery DROP CONSTRAINT IF EXISTS custody_webhook_delivery_event_key;
ALTER TABLE IF EXISTS ONLY public.custody_webhook_delivery_attempt DROP CONSTRAINT IF EXISTS custody_webhook_delivery_attempt_pkey;
ALTER TABLE IF EXISTS ONLY public.custody_webhook_delivery_attempt DROP CONSTRAINT IF EXISTS custody_webhook_attempt_number_key;
ALTER TABLE IF EXISTS ONLY public.custody_tenant_user DROP CONSTRAINT IF EXISTS custody_tenant_user_tenant_id_key;
ALTER TABLE IF EXISTS ONLY public.custody_tenant_user DROP CONSTRAINT IF EXISTS custody_tenant_user_pkey;
ALTER TABLE IF EXISTS ONLY public.custody_tenant DROP CONSTRAINT IF EXISTS custody_tenant_slug_key;
ALTER TABLE IF EXISTS ONLY public.custody_tenant DROP CONSTRAINT IF EXISTS custody_tenant_pkey;
ALTER TABLE IF EXISTS ONLY public.custody_tenant DROP CONSTRAINT IF EXISTS custody_tenant_derivation_namespace_key;
ALTER TABLE IF EXISTS ONLY public.custody_tenant_chain DROP CONSTRAINT IF EXISTS custody_tenant_chain_pkey;
ALTER TABLE IF EXISTS ONLY public.custody_session DROP CONSTRAINT IF EXISTS custody_session_token_hash_key;
ALTER TABLE IF EXISTS ONLY public.custody_session DROP CONSTRAINT IF EXISTS custody_session_pkey;
ALTER TABLE IF EXISTS ONLY public.custody_ledger_entry DROP CONSTRAINT IF EXISTS custody_ledger_entry_reference_key;
ALTER TABLE IF EXISTS ONLY public.custody_ledger_entry DROP CONSTRAINT IF EXISTS custody_ledger_entry_pkey;
ALTER TABLE IF EXISTS ONLY public.custody_ip_rule DROP CONSTRAINT IF EXISTS custody_ip_rule_tenant_cidr_key;
ALTER TABLE IF EXISTS ONLY public.custody_ip_rule DROP CONSTRAINT IF EXISTS custody_ip_rule_pkey;
ALTER TABLE IF EXISTS ONLY public.custody_idempotency_key DROP CONSTRAINT IF EXISTS custody_idempotency_key_pkey;
ALTER TABLE IF EXISTS ONLY public.custody_gas_usage DROP CONSTRAINT IF EXISTS custody_gas_usage_withdrawal_key;
ALTER TABLE IF EXISTS ONLY public.custody_gas_usage DROP CONSTRAINT IF EXISTS custody_gas_usage_tenant_order_key;
ALTER TABLE IF EXISTS ONLY public.custody_gas_usage DROP CONSTRAINT IF EXISTS custody_gas_usage_pkey;
ALTER TABLE IF EXISTS ONLY public.custody_gas_account DROP CONSTRAINT IF EXISTS custody_gas_account_tenant_id_key;
ALTER TABLE IF EXISTS ONLY public.custody_gas_account DROP CONSTRAINT IF EXISTS custody_gas_account_tenant_chain_key;
ALTER TABLE IF EXISTS ONLY public.custody_gas_account DROP CONSTRAINT IF EXISTS custody_gas_account_pkey;
ALTER TABLE IF EXISTS ONLY public.custody_gas_account DROP CONSTRAINT IF EXISTS custody_gas_account_address_key;
ALTER TABLE IF EXISTS ONLY public.custody_event DROP CONSTRAINT IF EXISTS custody_event_tenant_id_key;
ALTER TABLE IF EXISTS ONLY public.custody_event DROP CONSTRAINT IF EXISTS custody_event_pkey;
ALTER TABLE IF EXISTS ONLY public.custody_event DROP CONSTRAINT IF EXISTS custody_event_business_key;
ALTER TABLE IF EXISTS ONLY public.custody_derivation_subject DROP CONSTRAINT IF EXISTS custody_derivation_subject_pkey;
ALTER TABLE IF EXISTS ONLY public.custody_derivation_subject DROP CONSTRAINT IF EXISTS custody_derivation_subject_path_key;
ALTER TABLE IF EXISTS ONLY public.custody_dev_faucet_funding DROP CONSTRAINT IF EXISTS custody_dev_faucet_funding_address_asset_key;
ALTER TABLE IF EXISTS ONLY public.custody_dev_faucet_funding DROP CONSTRAINT IF EXISTS custody_dev_faucet_funding_pkey;
ALTER TABLE IF EXISTS ONLY public.custody_deposit DROP CONSTRAINT IF EXISTS custody_deposit_tenant_record_key;
ALTER TABLE IF EXISTS ONLY public.custody_deposit DROP CONSTRAINT IF EXISTS custody_deposit_pkey;
ALTER TABLE IF EXISTS ONLY public.custody_audit_log DROP CONSTRAINT IF EXISTS custody_audit_log_pkey;
ALTER TABLE IF EXISTS ONLY public.custody_asset_price DROP CONSTRAINT IF EXISTS custody_asset_price_pkey;
ALTER TABLE IF EXISTS ONLY public.custody_api_nonce DROP CONSTRAINT IF EXISTS custody_api_nonce_pkey;
ALTER TABLE IF EXISTS ONLY public.custody_api_key DROP CONSTRAINT IF EXISTS custody_api_key_pkey;
ALTER TABLE IF EXISTS ONLY public.custody_api_key DROP CONSTRAINT IF EXISTS custody_api_key_key_id_key;
ALTER TABLE IF EXISTS ONLY public.custody_address DROP CONSTRAINT IF EXISTS custody_address_tenant_id_key;
ALTER TABLE IF EXISTS ONLY public.custody_address DROP CONSTRAINT IF EXISTS custody_address_pkey;
ALTER TABLE IF EXISTS ONLY public.custody_address DROP CONSTRAINT IF EXISTS custody_address_chain_address_key;
ALTER TABLE IF EXISTS ONLY public.contract_deployment_order DROP CONSTRAINT IF EXISTS contract_deployment_order_pkey;
ALTER TABLE IF EXISTS ONLY public.contract_deployment_order DROP CONSTRAINT IF EXISTS contract_deployment_order_chain_order_no_key;
ALTER TABLE IF EXISTS ONLY public.collection_record DROP CONSTRAINT IF EXISTS collection_record_pkey;
ALTER TABLE IF EXISTS ONLY public.collection_record DROP CONSTRAINT IF EXISTS collection_record_chain_asset_symbol_from_address_to_addres_key;
ALTER TABLE IF EXISTS ONLY public.chain_signing_transaction DROP CONSTRAINT IF EXISTS chain_signing_transaction_pkey;
ALTER TABLE IF EXISTS ONLY public.chain_signing_transaction DROP CONSTRAINT IF EXISTS chain_signing_transaction_chain_business_type_business_no_key;
ALTER TABLE IF EXISTS ONLY public.chain_scan_height DROP CONSTRAINT IF EXISTS chain_scan_height_pkey;
ALTER TABLE IF EXISTS ONLY public.chain_scan_height DROP CONSTRAINT IF EXISTS chain_scan_height_chain_scanner_name_key;
ALTER TABLE IF EXISTS ONLY public.chain_rpc_node DROP CONSTRAINT IF EXISTS chain_rpc_node_unique_label;
ALTER TABLE IF EXISTS ONLY public.chain_rpc_node DROP CONSTRAINT IF EXISTS chain_rpc_node_pkey;
ALTER TABLE IF EXISTS ONLY public.chain_profile DROP CONSTRAINT IF EXISTS chain_profile_runtime_currency_id_network_key;
ALTER TABLE IF EXISTS ONLY public.chain_profile DROP CONSTRAINT IF EXISTS chain_profile_pkey;
ALTER TABLE IF EXISTS ONLY public.chain_profile DROP CONSTRAINT IF EXISTS chain_profile_chain_network_key;
ALTER TABLE IF EXISTS ONLY public.chain_asset DROP CONSTRAINT IF EXISTS chain_asset_pkey;
ALTER TABLE IF EXISTS ONLY public.chain_asset DROP CONSTRAINT IF EXISTS chain_asset_chain_symbol_key;
ALTER TABLE IF EXISTS ONLY public.chain_address DROP CONSTRAINT IF EXISTS chain_address_tenant_id_key;
ALTER TABLE IF EXISTS ONLY public.chain_address DROP CONSTRAINT IF EXISTS chain_address_pkey;
ALTER TABLE IF EXISTS ONLY public.chain_address DROP CONSTRAINT IF EXISTS chain_address_chain_asset_symbol_user_id_biz_address_index__key;
ALTER TABLE IF EXISTS ONLY public.chain_address DROP CONSTRAINT IF EXISTS chain_address_chain_asset_symbol_address_key;
ALTER TABLE IF EXISTS ONLY public.aptos_transaction DROP CONSTRAINT IF EXISTS aptos_transaction_pkey;
ALTER TABLE IF EXISTS ONLY public.aptos_transaction DROP CONSTRAINT IF EXISTS aptos_transaction_chain_tx_hash_key;
ALTER TABLE IF EXISTS ONLY public.account_sequence DROP CONSTRAINT IF EXISTS account_sequence_pkey;
ALTER TABLE IF EXISTS ONLY public.account_sequence DROP CONSTRAINT IF EXISTS account_sequence_chain_address_key;
ALTER TABLE IF EXISTS public.xrp_transaction ALTER COLUMN id DROP DEFAULT;
ALTER TABLE IF EXISTS public.withdrawal_order ALTER COLUMN id DROP DEFAULT;
ALTER TABLE IF EXISTS public.utxo_record ALTER COLUMN id DROP DEFAULT;
ALTER TABLE IF EXISTS public.tron_tx ALTER COLUMN id DROP DEFAULT;
ALTER TABLE IF EXISTS public.tron_transaction ALTER COLUMN id DROP DEFAULT;
ALTER TABLE IF EXISTS public.tron_token_transfer ALTER COLUMN id DROP DEFAULT;
ALTER TABLE IF EXISTS public.ton_transaction ALTER COLUMN id DROP DEFAULT;
ALTER TABLE IF EXISTS public.token_registry ALTER COLUMN id DROP DEFAULT;
ALTER TABLE IF EXISTS public.token_config ALTER COLUMN id DROP DEFAULT;
ALTER TABLE IF EXISTS public.sui_transaction ALTER COLUMN id DROP DEFAULT;
ALTER TABLE IF EXISTS public.sol_transaction ALTER COLUMN id DROP DEFAULT;
ALTER TABLE IF EXISTS public.near_transaction ALTER COLUMN id DROP DEFAULT;
ALTER TABLE IF EXISTS public.monero_transaction ALTER COLUMN id DROP DEFAULT;
ALTER TABLE IF EXISTS public.ledger_balance ALTER COLUMN id DROP DEFAULT;
ALTER TABLE IF EXISTS public.hot_wallet_address ALTER COLUMN id DROP DEFAULT;
ALTER TABLE IF EXISTS public.gas_topup_task ALTER COLUMN id DROP DEFAULT;
ALTER TABLE IF EXISTS public.evm_tx ALTER COLUMN id DROP DEFAULT;
ALTER TABLE IF EXISTS public.evm_transaction ALTER COLUMN id DROP DEFAULT;
ALTER TABLE IF EXISTS public.evm_token_transfer ALTER COLUMN id DROP DEFAULT;
ALTER TABLE IF EXISTS public.evm_nonce ALTER COLUMN id DROP DEFAULT;
ALTER TABLE IF EXISTS public.deposit_record ALTER COLUMN id DROP DEFAULT;
ALTER TABLE IF EXISTS public.collection_record ALTER COLUMN id DROP DEFAULT;
ALTER TABLE IF EXISTS public.chain_scan_height ALTER COLUMN id DROP DEFAULT;
ALTER TABLE IF EXISTS public.chain_rpc_node ALTER COLUMN id DROP DEFAULT;
ALTER TABLE IF EXISTS public.chain_profile ALTER COLUMN id DROP DEFAULT;
ALTER TABLE IF EXISTS public.chain_asset ALTER COLUMN id DROP DEFAULT;
ALTER TABLE IF EXISTS public.chain_address ALTER COLUMN id DROP DEFAULT;
ALTER TABLE IF EXISTS public.aptos_transaction ALTER COLUMN id DROP DEFAULT;
ALTER TABLE IF EXISTS public.account_sequence ALTER COLUMN id DROP DEFAULT;
DROP SEQUENCE IF EXISTS public.xrp_transaction_id_seq;
DROP TABLE IF EXISTS public.xrp_transaction;
DROP TABLE IF EXISTS public.withdrawal_review_audit;
DROP SEQUENCE IF EXISTS public.withdrawal_order_id_seq;
DROP TABLE IF EXISTS public.withdrawal_order;
DROP TABLE IF EXISTS public.wallet_user_session;
DROP TABLE IF EXISTS public.wallet_user;
DROP TABLE IF EXISTS public.wallet_transfer_order;
DROP TABLE IF EXISTS public.wallet_system_config;
DROP TABLE IF EXISTS public.wallet_public_key;
DROP SEQUENCE IF EXISTS public.utxo_record_id_seq;
DROP TABLE IF EXISTS public.utxo_record;
DROP SEQUENCE IF EXISTS public.tron_tx_id_seq;
DROP TABLE IF EXISTS public.tron_tx;
DROP SEQUENCE IF EXISTS public.tron_transaction_id_seq;
DROP TABLE IF EXISTS public.tron_transaction;
DROP SEQUENCE IF EXISTS public.tron_token_transfer_id_seq;
DROP TABLE IF EXISTS public.tron_token_transfer;
DROP SEQUENCE IF EXISTS public.ton_transaction_id_seq;
DROP TABLE IF EXISTS public.ton_transaction;
DROP SEQUENCE IF EXISTS public.token_registry_id_seq;
DROP TABLE IF EXISTS public.token_registry;
DROP SEQUENCE IF EXISTS public.token_config_id_seq;
DROP TABLE IF EXISTS public.token_config;
DROP SEQUENCE IF EXISTS public.sui_transaction_id_seq;
DROP TABLE IF EXISTS public.sui_transaction;
DROP SEQUENCE IF EXISTS public.sol_transaction_id_seq;
DROP TABLE IF EXISTS public.sol_transaction;
DROP SEQUENCE IF EXISTS public.near_transaction_id_seq;
DROP TABLE IF EXISTS public.near_transaction;
DROP SEQUENCE IF EXISTS public.monero_transaction_id_seq;
DROP TABLE IF EXISTS public.monero_transaction;
DROP SEQUENCE IF EXISTS public.ledger_balance_id_seq;
DROP TABLE IF EXISTS public.ledger_balance;
DROP TABLE IF EXISTS public.hypercore_token_metadata;
DROP TABLE IF EXISTS public.hypercore_spot_asset;
DROP TABLE IF EXISTS public.hypercore_action_record;
DROP SEQUENCE IF EXISTS public.hot_wallet_address_id_seq;
DROP TABLE IF EXISTS public.hot_wallet_address;
DROP SEQUENCE IF EXISTS public.gas_topup_task_id_seq;
DROP TABLE IF EXISTS public.gas_topup_task;
DROP SEQUENCE IF EXISTS public.evm_tx_id_seq;
DROP TABLE IF EXISTS public.evm_tx;
DROP SEQUENCE IF EXISTS public.evm_transaction_id_seq;
DROP TABLE IF EXISTS public.evm_transaction;
DROP SEQUENCE IF EXISTS public.evm_token_transfer_id_seq;
DROP TABLE IF EXISTS public.evm_token_transfer;
DROP SEQUENCE IF EXISTS public.evm_nonce_id_seq;
DROP TABLE IF EXISTS public.evm_nonce;
DROP SEQUENCE IF EXISTS public.deposit_record_id_seq;
DROP TABLE IF EXISTS public.chain_scan_block;
DROP TABLE IF EXISTS public.custody_asset_recovery;
DROP TABLE IF EXISTS public.custody_reorg_deficit;
DROP TABLE IF EXISTS public.deposit_record;
DROP TABLE IF EXISTS public.custody_dev_faucet_funding;
DROP TABLE IF EXISTS public.custody_withdrawal;
DROP TABLE IF EXISTS public.custody_webhook_endpoint;
DROP TABLE IF EXISTS public.custody_webhook_delivery_attempt;
DROP TABLE IF EXISTS public.custody_webhook_delivery;
DROP TABLE IF EXISTS public.custody_tenant_user;
DROP TABLE IF EXISTS public.custody_tenant_chain;
DROP TABLE IF EXISTS public.custody_tenant;
DROP TABLE IF EXISTS public.custody_session;
DROP TABLE IF EXISTS public.custody_ledger_entry;
DROP TABLE IF EXISTS public.custody_ip_rule;
DROP TABLE IF EXISTS public.custody_idempotency_key;
DROP TABLE IF EXISTS public.custody_gas_usage;
DROP TABLE IF EXISTS public.custody_gas_account;
DROP TABLE IF EXISTS public.custody_event;
DROP TABLE IF EXISTS public.custody_derivation_subject;
DROP SEQUENCE IF EXISTS public.custody_derivation_subject_index_seq;
DROP SEQUENCE IF EXISTS public.custody_derivation_namespace_seq;
DROP TABLE IF EXISTS public.custody_deposit;
DROP TABLE IF EXISTS public.custody_audit_log;
DROP TABLE IF EXISTS public.custody_asset_price;
DROP TABLE IF EXISTS public.custody_api_nonce;
DROP TABLE IF EXISTS public.custody_api_key;
DROP TABLE IF EXISTS public.custody_address;
DROP SEQUENCE IF EXISTS public.custody_derivation_subject_seq;
DROP TABLE IF EXISTS public.contract_deployment_order;
DROP SEQUENCE IF EXISTS public.collection_record_id_seq;
DROP TABLE IF EXISTS public.collection_record;
DROP TABLE IF EXISTS public.chain_signing_transaction;
DROP SEQUENCE IF EXISTS public.chain_scan_height_id_seq;
DROP TABLE IF EXISTS public.chain_scan_height;
DROP SEQUENCE IF EXISTS public.chain_rpc_node_id_seq;
DROP TABLE IF EXISTS public.chain_rpc_node;
DROP SEQUENCE IF EXISTS public.chain_profile_id_seq;
DROP TABLE IF EXISTS public.chain_profile;
DROP SEQUENCE IF EXISTS public.chain_asset_id_seq;
DROP TABLE IF EXISTS public.chain_asset;
DROP SEQUENCE IF EXISTS public.chain_address_id_seq;
DROP TABLE IF EXISTS public.chain_address;
DROP SEQUENCE IF EXISTS public.aptos_transaction_id_seq;
DROP TABLE IF EXISTS public.aptos_transaction;
DROP SEQUENCE IF EXISTS public.account_sequence_id_seq;
DROP TABLE IF EXISTS public.account_sequence;
SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: account_sequence; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.account_sequence (
    id bigint NOT NULL,
    chain character varying(32) NOT NULL,
    address character varying(160) NOT NULL,
    chain_sequence bigint DEFAULT 0 NOT NULL,
    next_sequence bigint DEFAULT 0 NOT NULL,
    pending_owner varchar(320),
    pending_sequence bigint,
    CHECK (chain_sequence>=0 AND next_sequence>=0),
    CHECK ((pending_owner IS NULL AND pending_sequence IS NULL) OR (pending_owner IS NOT NULL AND length(pending_owner)>0 AND pending_sequence IS NOT NULL AND pending_sequence>=0 AND pending_sequence<next_sequence)),
    status character varying(32) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


CREATE UNIQUE INDEX account_sequence_pending_owner_idx ON public.account_sequence(chain,pending_owner) WHERE pending_owner IS NOT NULL;

--
-- Name: account_sequence_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.account_sequence_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: account_sequence_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.account_sequence_id_seq OWNED BY public.account_sequence.id;







--
-- Name: aptos_transaction; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.aptos_transaction (
    id bigint NOT NULL,
    chain character varying(32) DEFAULT 'APTOS'::character varying NOT NULL,
    tx_hash character varying(128) NOT NULL,
    sender character varying(128),
    receiver character varying(128) NOT NULL,
    asset_symbol character varying(32) NOT NULL,
    coin_type character varying(256),
    amount numeric(84,24) DEFAULT 0 NOT NULL,
    gas_used bigint DEFAULT 0 NOT NULL,
    gas_unit_price bigint DEFAULT 0 NOT NULL,
    fee_octas bigint DEFAULT 0 NOT NULL CHECK (fee_octas >= 0),
    version bigint,
    sequence_number bigint,
    confirmations integer DEFAULT 0 NOT NULL,
    status character varying(32) DEFAULT 'CREATED'::character varying NOT NULL,
    raw_payload text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- MPC outgoing raw_payload retains selected signed JSON before RPC. fee_octas is the committed net fee after storage refunds.
-- Name: aptos_transaction_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.aptos_transaction_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- MPC outgoing raw_payload retains selected signed JSON before RPC. fee_octas is the committed net fee after storage refunds.
-- Name: aptos_transaction_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.aptos_transaction_id_seq OWNED BY public.aptos_transaction.id;


--
-- Name: chain_address; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.chain_address (
    id bigint NOT NULL,
    chain character varying(32) NOT NULL,
    asset_symbol character varying(32) NOT NULL,
    account_id character varying(160) NOT NULL,
    user_id bigint NOT NULL,
    biz integer DEFAULT 0 NOT NULL,
    address_index bigint NOT NULL,
    address character varying(160) NOT NULL,
    owner_address character varying(160),
    derivation_path character varying(96) NOT NULL,
    wallet_role character varying(32) DEFAULT 'DEPOSIT'::character varying NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    tenant_id uuid,
    mpc_key_id uuid,
    CONSTRAINT chain_address_mpc_owner_check CHECK (mpc_key_id IS NULL OR tenant_id IS NOT NULL),
    CONSTRAINT chain_address_reserved_hot_wallet_check CHECK (((user_id <> 0) OR (biz <> 0) OR ((address_index = 0) AND ((wallet_role)::text = 'DEPOSIT'::text))))
);


--
-- Name: chain_address_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.chain_address_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: chain_address_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.chain_address_id_seq OWNED BY public.chain_address.id;


--
-- Name: chain_asset; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.chain_asset (
    id bigint NOT NULL,
    chain character varying(32) NOT NULL,
    symbol character varying(32) NOT NULL,
    asset_kind character varying(32) NOT NULL,
    contract_address character varying(128),
    decimals integer DEFAULT 18 NOT NULL,
    native_asset boolean DEFAULT false NOT NULL,
    active boolean DEFAULT true NOT NULL,
    min_transfer numeric(84,24),
    min_withdraw numeric(84,24),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: chain_asset_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.chain_asset_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: chain_asset_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.chain_asset_id_seq OWNED BY public.chain_asset.id;


--
-- Name: chain_profile; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.chain_profile (
    id bigint NOT NULL,
    chain character varying(32) NOT NULL,
    network character varying(32) NOT NULL,
    family character varying(32) NOT NULL,
    runtime_currency_id integer NOT NULL,
    bip44_coin_type integer NOT NULL,
    native_symbol character varying(32) NOT NULL,
    rpc_url character varying(512),
    explorer_url character varying(512),
    deposit_confirmations integer DEFAULT 1 NOT NULL,
    withdraw_confirmations integer DEFAULT 1 NOT NULL,
    default_fee_rate bigint,
    dust_threshold bigint,
    enabled boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    chain_id bigint,
    gas_policy character varying(64),
    fee_model character varying(32) DEFAULT 'standard'::character varying NOT NULL,
    scan_batch_size integer DEFAULT 100,
    scan_enabled boolean DEFAULT false NOT NULL,
    withdraw_enabled boolean DEFAULT false NOT NULL,
    collection_enabled boolean DEFAULT false NOT NULL,
    transfer_enabled boolean DEFAULT false NOT NULL,
    scan_start_height bigint DEFAULT 0 NOT NULL,
    scan_max_blocks_per_run bigint DEFAULT 0 NOT NULL,
    CONSTRAINT chain_profile_fee_model_check CHECK (
        fee_model::text = ANY (ARRAY[
            'standard'::character varying,
            'op-stack'::character varying,
            'arbitrum-nitro'::character varying,
            'scroll'::character varying
        ]::text[])),
    CONSTRAINT chain_profile_evm_gas_policy_check CHECK (
        lower(family::text) <> 'evm'::text
        OR (
            gas_policy IS NOT NULL
            AND gas_policy::text = ANY (ARRAY[
                'legacy-gas-price'::character varying,
                'eip1559'::character varying
            ]::text[])
        ))
);


--
-- Name: chain_profile_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.chain_profile_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: chain_profile_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.chain_profile_id_seq OWNED BY public.chain_profile.id;


--
-- Name: chain_rpc_node; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.chain_rpc_node (
    id bigint NOT NULL,
    chain character varying(32) NOT NULL,
    network character varying(32) NOT NULL,
    environment character varying(32) DEFAULT 'dev'::character varying NOT NULL,
    node_label character varying(64) NOT NULL,
    purpose character varying(32) DEFAULT 'rpc'::character varying NOT NULL,
    connection_type character varying(32) DEFAULT 'HTTP_JSON_RPC'::character varying NOT NULL,
    rpc_url text NOT NULL,
    auth_type character varying(32) DEFAULT 'NONE'::character varying NOT NULL,
    auth_header_name character varying(64),
    api_key_ref character varying(128),
    username_ref character varying(128),
    password_ref character varying(128),
    priority integer DEFAULT 100 NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    renewal_due_at timestamp with time zone,
    remark text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    api_key character varying(1024),
    username character varying(256),
    password character varying(1024),
    last_checked_at timestamp with time zone,
    last_latency_ms bigint,
    last_http_status integer,
    last_error text,
    min_request_interval_ms integer DEFAULT 0 NOT NULL
);


--
-- Name: chain_rpc_node_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.chain_rpc_node_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: chain_rpc_node_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.chain_rpc_node_id_seq OWNED BY public.chain_rpc_node.id;


--
-- Name: chain_scan_height; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.chain_scan_height (
    id bigint NOT NULL,
    chain character varying(32) NOT NULL,
    scanner_name character varying(64) NOT NULL,
    genesis_hash varchar(64) CHECK (genesis_hash IS NULL OR genesis_hash ~ '^[0-9a-f]{64}$'),
    best_height bigint DEFAULT 0 NOT NULL,
    safe_height bigint DEFAULT 0 NOT NULL,
    status character varying(32) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: chain_scan_block; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.chain_scan_block (
    chain character varying(32) NOT NULL,
    scanner_name character varying(64) NOT NULL,
    block_height bigint NOT NULL,
    block_slot bigint CHECK (block_slot IS NULL OR block_slot >= 0),
    block_hash character varying(128) NOT NULL,
    parent_hash character varying(128),
    observed_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chain_scan_block_pkey PRIMARY KEY (chain, scanner_name, block_height)
);


--
-- Name: chain_scan_height_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.chain_scan_height_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: chain_scan_height_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.chain_scan_height_id_seq OWNED BY public.chain_scan_height.id;


--
-- Name: chain_signing_transaction; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.chain_signing_transaction (
    id integer NOT NULL,
    request jsonb NOT NULL,
    chain character varying(32) GENERATED ALWAYS AS (request #>> '{asset,chain}') STORED NOT NULL,
    tenant_id uuid GENERATED ALWAYS AS ((request ->> 'tenantId')::uuid) STORED NOT NULL,
    signing_request_id uuid GENERATED ALWAYS AS ((request ->> 'requestId')::uuid) STORED NOT NULL,
    business_type character varying(32) GENERATED ALWAYS AS (request ->> 'operation') STORED NOT NULL,
    business_no character varying(512) NOT NULL,
    signed_transaction jsonb,
    tx_id character varying(128) GENERATED ALWAYS AS (signed_transaction ->> 'txId') STORED,
    attempts jsonb DEFAULT '[]'::jsonb NOT NULL,
    enqueued_signing_request_id uuid,
    status smallint NOT NULL CHECK (status IN (-1, 0, 1, 2, 3)),
    error_message text,
    create_date timestamp with time zone DEFAULT now() NOT NULL,
    update_date timestamp with time zone DEFAULT now() NOT NULL,
    CHECK (chain IN ('BTC', 'BCH', 'LTC', 'DOGE')),
    CHECK (business_type IN ('WITHDRAWAL', 'COLLECTION')),
    CHECK (jsonb_typeof(attempts) = 'array')
);


--
-- Name: chain_signing_transaction_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.chain_signing_transaction ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.chain_signing_transaction_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: collection_record; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.collection_record (
    durable_submission boolean NOT NULL DEFAULT false,
    id bigint NOT NULL,
    collection_no character varying(96) NOT NULL,
    chain character varying(32) NOT NULL,
    asset_symbol character varying(32) NOT NULL,
    from_address character varying(160) NOT NULL,
    to_address character varying(160) NOT NULL,
    amount numeric(84,24) DEFAULT 0 NOT NULL,
    fee numeric(84,24) DEFAULT 0 NOT NULL,
    tx_hash character varying(128),
    status character varying(32) DEFAULT 'CREATED'::character varying NOT NULL,
    error_message text,
    raw_payload text,
    tenant_id uuid,
    custody_address_id uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT collection_record_tenant_id_key UNIQUE (tenant_id, id)
);


--
-- Name: collection_record_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.collection_record_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: collection_record_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.collection_record_id_seq OWNED BY public.collection_record.id;


--
-- Name: contract_deployment_order; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.contract_deployment_order (
    id bigint NOT NULL,
    order_no character varying(96) NOT NULL,
    user_id bigint NOT NULL,
    chain character varying(32) NOT NULL,
    network character varying(32) NOT NULL,
    template_type character varying(32) NOT NULL,
    contract_name character varying(128) NOT NULL,
    contract_symbol character varying(32) NOT NULL,
    deployer_address character varying(128) NOT NULL,
    account_id character varying(160) NOT NULL,
    owner_address character varying(128) NOT NULL,
    native_symbol character varying(32) NOT NULL,
    status character varying(32) DEFAULT 'CREATED'::character varying NOT NULL,
    parameters_json text NOT NULL,
    constructor_args_json text NOT NULL,
    source_code text NOT NULL,
    abi_json text NOT NULL,
    bytecode_hash character varying(128) NOT NULL,
    deploy_data_hash character varying(128) NOT NULL,
    gas_price_wei character varying(96),
    gas_limit bigint,
    fee_limit numeric(78,24) DEFAULT 0 NOT NULL,
    fee_actual numeric(78,24) DEFAULT 0 NOT NULL,
    nonce bigint,
    tx_hash character varying(128),
    contract_address character varying(128),
    block_height bigint,
    confirmations integer DEFAULT 0 NOT NULL,
    error_message text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: contract_deployment_order_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.contract_deployment_order ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.contract_deployment_order_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: custody_derivation_subject_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.custody_derivation_subject_seq
    AS integer
    START WITH 100000
    INCREMENT BY 1
    MINVALUE 100000
    MAXVALUE 2147483646
    CACHE 64;


--
-- Name: custody_address; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.custody_address (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    chain_address_id bigint NOT NULL,
    chain character varying(32) NOT NULL,
    network character varying(64) NOT NULL,
    address character varying(160) NOT NULL,
    memo character varying(160),
    external_reference character varying(160),
    label character varying(160),
    metadata jsonb DEFAULT '{}'::jsonb NOT NULL,
    source character varying(24) NOT NULL,
    status character varying(24) DEFAULT 'ACTIVE'::character varying NOT NULL,
    derivation_subject integer DEFAULT nextval('public.custody_derivation_subject_seq'::regclass) NOT NULL,
    created_by uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    subject character varying(160) NOT NULL,
    address_version bigint DEFAULT 0 NOT NULL,
    derivation_child bigint NOT NULL,
    CONSTRAINT custody_address_version_check CHECK (((address_version >= 0) AND (address_version <= 2147483647))),
    CONSTRAINT custody_address_source_check CHECK (((source)::text = ANY ((ARRAY['API'::character varying, 'CONSOLE'::character varying])::text[]))),
    CONSTRAINT custody_address_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'DISABLED'::character varying])::text[])))
);


--
-- Name: custody_api_key; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.custody_api_key (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    key_id character varying(64) NOT NULL,
    name character varying(120) NOT NULL,
    secret_ciphertext text NOT NULL,
    status character varying(24) DEFAULT 'ACTIVE'::character varying NOT NULL,
    last_used_at timestamp with time zone,
    last_used_ip inet,
    expires_at timestamp with time zone,
    created_by uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    revoked_at timestamp with time zone,
    CONSTRAINT custody_api_key_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'REVOKED'::character varying])::text[])))
);


--
-- Name: custody_api_nonce; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.custody_api_nonce (
    key_id character varying(64) NOT NULL,
    nonce character varying(128) NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: custody_asset_price; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.custody_asset_price (
    asset_symbol character varying(32) NOT NULL,
    usd_price numeric(38,18) NOT NULL,
    source character varying(80) NOT NULL,
    observed_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT custody_asset_price_symbol_check CHECK (((asset_symbol)::text ~ '^[A-Z][A-Z0-9_]{1,31}$'::text)),
    CONSTRAINT custody_asset_price_value_check CHECK ((usd_price > (0)::numeric))
);


--
-- Name: custody_audit_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.custody_audit_log (
    id uuid NOT NULL,
    tenant_id uuid,
    actor_type character varying(24) NOT NULL,
    actor_id character varying(160) NOT NULL,
    action character varying(96) NOT NULL,
    resource_type character varying(64) NOT NULL,
    resource_id character varying(192),
    source_ip inet,
    details jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT custody_audit_log_actor_check CHECK (((actor_type)::text = ANY ((ARRAY['PLATFORM_USER'::character varying, 'TENANT_USER'::character varying, 'API_KEY'::character varying, 'SYSTEM'::character varying])::text[])))
);


--
-- Name: custody_deposit; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.custody_deposit (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    custody_address_id uuid NOT NULL,
    deposit_record_id bigint NOT NULL,
    chain character varying(32) NOT NULL,
    asset_symbol character varying(32) NOT NULL,
    tx_hash character varying(128) NOT NULL,
    log_index bigint DEFAULT 0 NOT NULL,
    amount numeric(78,24) NOT NULL,
    status character varying(32) NOT NULL,
    credited_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: custody_asset_recovery; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.custody_asset_recovery (
    durable_submission boolean NOT NULL DEFAULT false,
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    custody_address_id uuid,
    actual_chain character varying(32) NOT NULL,
    expected_chain character varying(32),
    asset_symbol character varying(32) NOT NULL,
    token_contract character varying(128),
    token_decimals integer,
    tx_hash character varying(128) NOT NULL,
    log_index bigint DEFAULT 0 NOT NULL,
    requested_log_index bigint,
    destination_address character varying(160) NOT NULL,
    recovery_address character varying(160),
    claimed_amount numeric(78,24),
    verified_amount numeric(78,24),
    block_height bigint,
    block_hash character varying(128),
    confirmations integer DEFAULT 0 NOT NULL,
    status character varying(32) DEFAULT 'SUBMITTED'::character varying NOT NULL,
    verification_details jsonb DEFAULT '{}'::jsonb NOT NULL,
    failure_reason text,
    recovery_tx_hash character varying(128),
    requested_by uuid NOT NULL,
    reviewed_by uuid,
    executed_by uuid,
    approved_at timestamp with time zone,
    executed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT custody_asset_recovery_pkey PRIMARY KEY (id),
    CONSTRAINT custody_asset_recovery_tenant_id_key UNIQUE (tenant_id, id),
    CONSTRAINT custody_asset_recovery_business_key UNIQUE (actual_chain, tx_hash, log_index),
    CONSTRAINT custody_asset_recovery_status_check CHECK (((status)::text = ANY ((ARRAY[
        'SUBMITTED'::character varying, 'VERIFIED'::character varying,
        'APPROVED'::character varying, 'EXECUTING'::character varying,
        'BROADCAST'::character varying, 'RECOVERED'::character varying, 'REJECTED'::character varying,
        'CANCELLED'::character varying])::text[]))),
    CONSTRAINT custody_asset_recovery_decimals_check CHECK (((token_decimals IS NULL) OR ((token_decimals >= 0) AND (token_decimals <= 36)))),
    CONSTRAINT custody_asset_recovery_requested_log_index_check CHECK (((requested_log_index IS NULL) OR (requested_log_index >= 0)))
);


--
-- Name: custody_reorg_deficit; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.custody_reorg_deficit (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    custody_address_id uuid NOT NULL,
    deposit_record_id bigint NOT NULL,
    chain character varying(32) NOT NULL,
    asset_symbol character varying(32) NOT NULL,
    account_id character varying(160) NOT NULL,
    original_amount numeric(78,24) NOT NULL,
    deficit_amount numeric(78,24) NOT NULL,
    recovered_amount numeric(78,24) DEFAULT 0 NOT NULL,
    status character varying(24) DEFAULT 'OPEN'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT custody_reorg_deficit_pkey PRIMARY KEY (id),
    CONSTRAINT custody_reorg_deficit_tenant_id_key UNIQUE (tenant_id, id),
    CONSTRAINT custody_reorg_deficit_deposit_key UNIQUE (tenant_id, deposit_record_id),
    CONSTRAINT custody_reorg_deficit_status_check CHECK (((status)::text = ANY ((ARRAY['OPEN'::character varying, 'RECOVERED'::character varying])::text[]))),
    CONSTRAINT custody_reorg_deficit_amount_check CHECK (((deficit_amount > (0)::numeric) AND (recovered_amount >= (0)::numeric) AND (recovered_amount <= deficit_amount)))
);


--
-- Name: custody_dev_faucet_funding; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.custody_dev_faucet_funding (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    custody_address_id uuid NOT NULL,
    chain character varying(32) NOT NULL,
    network character varying(64) NOT NULL,
    asset_symbol character varying(32) NOT NULL,
    purpose character varying(32) NOT NULL,
    address character varying(160) NOT NULL,
    contract_address character varying(128),
    decimals integer NOT NULL,
    requested_amount numeric(78,24) NOT NULL,
    tx_hash character varying(128),
    status character varying(24) DEFAULT 'PENDING'::character varying NOT NULL,
    attempts integer DEFAULT 0 NOT NULL,
    last_error text,
    next_attempt_at timestamp with time zone DEFAULT now() NOT NULL,
    sent_at timestamp with time zone,
    confirmed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT custody_dev_faucet_amount_check CHECK ((requested_amount > (0)::numeric)),
    CONSTRAINT custody_dev_faucet_attempts_check CHECK ((attempts >= 0)),
    CONSTRAINT custody_dev_faucet_decimals_check CHECK (((decimals >= 0) AND (decimals <= 24))),
    CONSTRAINT custody_dev_faucet_purpose_check CHECK (((purpose)::text = ANY ((ARRAY['CUSTOMER_DEPOSIT'::character varying, 'TENANT_GAS'::character varying])::text[]))),
    CONSTRAINT custody_dev_faucet_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'SENDING'::character varying, 'SENT'::character varying, 'CONFIRMED'::character varying, 'FAILED'::character varying, 'UNKNOWN'::character varying])::text[])))
);


--
-- Name: custody_derivation_namespace_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.custody_derivation_namespace_seq
    AS integer
    START WITH 1000
    INCREMENT BY 1
    MINVALUE 1000
    MAXVALUE 2147483646
    CACHE 16;


--
-- Name: custody_derivation_subject_index_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.custody_derivation_subject_index_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    MAXVALUE 2147483646
    CACHE 64;


--
-- Name: custody_derivation_subject; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.custody_derivation_subject (
    tenant_id uuid NOT NULL,
    subject character varying(160) NOT NULL,
    derivation_subject integer DEFAULT nextval('public.custody_derivation_subject_index_seq'::regclass) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT custody_derivation_subject_value_check CHECK (((subject)::text ~ '^[A-Za-z0-9_][A-Za-z0-9._:-]{0,159}$'::text))
);


--
-- Name: custody_event; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.custody_event (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    event_type character varying(64) NOT NULL,
    aggregate_type character varying(32) NOT NULL,
    aggregate_id character varying(192) NOT NULL,
    payload jsonb NOT NULL,
    status character varying(24) DEFAULT 'PENDING'::character varying NOT NULL,
    occurred_at timestamp with time zone DEFAULT now() NOT NULL,
    published_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT custody_event_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'PUBLISHED'::character varying])::text[])))
);


--
-- Name: custody_gas_account; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.custody_gas_account (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    custody_address_id uuid NOT NULL,
    chain character varying(32) NOT NULL,
    network character varying(64) NOT NULL,
    native_symbol character varying(32) NOT NULL,
    low_balance_threshold numeric(78,24) DEFAULT 0 NOT NULL,
    status character varying(24) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_by uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT custody_gas_account_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'DISABLED'::character varying])::text[]))),
    CONSTRAINT custody_gas_account_threshold_check CHECK ((low_balance_threshold >= (0)::numeric))
);


--
-- Name: custody_gas_usage; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.custody_gas_usage (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    gas_account_id uuid NOT NULL,
    operation_type character varying(32) NOT NULL,
    operation_id uuid NOT NULL,
    reference_no character varying(96) NOT NULL,
    chain character varying(32) NOT NULL,
    native_symbol character varying(32) NOT NULL,
    reserved_amount numeric(78,24) NOT NULL,
    actual_amount numeric(78,24),
    status character varying(24) DEFAULT 'RESERVED'::character varying NOT NULL,
    pricing_source character varying(32) DEFAULT 'CONFIGURED_RESERVE'::character varying NOT NULL,
    tx_hash character varying(128),
    error_message text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    settled_at timestamp with time zone,
    CONSTRAINT custody_gas_usage_actual_check CHECK (actual_amount IS NULL OR actual_amount >= 0 OR chain = 'SUI'),
    CONSTRAINT custody_gas_usage_reserved_check CHECK ((reserved_amount > (0)::numeric)),
    CONSTRAINT custody_gas_usage_operation_type_check CHECK (((operation_type)::text = ANY ((ARRAY['WITHDRAWAL'::character varying, 'COLLECTION_BATCH'::character varying, 'WITHDRAWAL_BATCH'::character varying])::text[]))),
    CONSTRAINT custody_gas_usage_status_check CHECK (((status)::text = ANY ((ARRAY['RESERVED'::character varying, 'SETTLED'::character varying, 'RELEASED'::character varying, 'OVERDUE'::character varying])::text[])))
);


--
-- Name: custody_idempotency_key; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.custody_idempotency_key (
    tenant_id uuid NOT NULL,
    idempotency_key character varying(160) NOT NULL,
    operation character varying(64) NOT NULL,
    request_hash character varying(128) NOT NULL,
    response_status integer,
    response_body jsonb,
    expires_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT custody_withdrawal_receipt_permanent CHECK (operation <> 'WITHDRAWAL.CREATE' OR expires_at IS NULL)
);


--
-- Name: custody_ip_rule; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.custody_ip_rule (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    label character varying(120) NOT NULL,
    cidr inet NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    created_by uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: custody_ledger_entry; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.custody_ledger_entry (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    custody_address_id uuid,
    chain character varying(32) NOT NULL,
    asset_symbol character varying(32) NOT NULL,
    account_id character varying(160) NOT NULL,
    entry_type character varying(32) NOT NULL,
    direction character varying(8) NOT NULL,
    amount numeric(78,24) NOT NULL,
    reference_type character varying(32) NOT NULL,
    reference_id character varying(192) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT custody_ledger_entry_amount_check CHECK ((amount > (0)::numeric)),
    CONSTRAINT custody_ledger_entry_direction_check CHECK (((direction)::text = ANY ((ARRAY['CREDIT'::character varying, 'DEBIT'::character varying])::text[])))
);


--
-- Name: custody_session; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.custody_session (
    id uuid NOT NULL,
    tenant_user_id uuid NOT NULL,
    tenant_id uuid,
    token_hash character varying(128) NOT NULL,
    source_ip inet,
    user_agent character varying(512),
    expires_at timestamp with time zone NOT NULL,
    last_seen_at timestamp with time zone DEFAULT now() NOT NULL,
    revoked_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    security_version bigint NOT NULL DEFAULT 0,
    verified boolean NOT NULL DEFAULT true,
    email_required boolean NOT NULL DEFAULT false,
    email_verified boolean NOT NULL DEFAULT false,
    totp_required boolean NOT NULL DEFAULT false,
    verification_attempts integer NOT NULL DEFAULT 0 CHECK (verification_attempts BETWEEN 0 AND 5),
    email_code_hash varchar(64),
    email_code_expires_at timestamptz,
    email_sent_at timestamptz,
    enrollment_ciphertext text,
    enrollment_expires_at timestamptz
);


--
-- Name: custody_tenant; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.custody_tenant (
    id uuid NOT NULL,
    slug character varying(64) NOT NULL,
    name character varying(160) NOT NULL,
    status character varying(24) DEFAULT 'ACTIVE'::character varying NOT NULL,
    derivation_namespace integer DEFAULT nextval('public.custody_derivation_namespace_seq'::regclass) NOT NULL,
    ip_allowlist_enabled boolean DEFAULT false NOT NULL,
    display_currency character varying(12) DEFAULT 'USD'::character varying NOT NULL,
    metadata jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT custody_tenant_slug_check CHECK (((slug)::text ~ '^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$'::text)),
    CONSTRAINT custody_tenant_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'SUSPENDED'::character varying])::text[])))
);


--
-- Name: custody_tenant_chain; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.custody_tenant_chain (
    tenant_id uuid NOT NULL,
    chain character varying(32) NOT NULL,
    status character varying(24) DEFAULT 'CLOSED'::character varying NOT NULL,
    opened_by uuid,
    opened_at timestamp with time zone,
    closed_by uuid,
    closed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT custody_tenant_chain_lifecycle_check CHECK (((((status)::text = 'ACTIVE'::text) AND (opened_by IS NOT NULL) AND (opened_at IS NOT NULL) AND (closed_at IS NULL)) OR (((status)::text = 'CLOSED'::text) AND (closed_by IS NOT NULL) AND (closed_at IS NOT NULL)))),
    CONSTRAINT custody_tenant_chain_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'CLOSED'::character varying])::text[])))
);


--
-- Name: custody_tenant_user; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.custody_tenant_user (
    id uuid NOT NULL,
    tenant_id uuid,
    email character varying(254) NOT NULL,
    display_name character varying(120) NOT NULL,
    password_hash character varying(512) NOT NULL,
    role character varying(32) NOT NULL,
    status character varying(24) DEFAULT 'ACTIVE'::character varying NOT NULL,
    failed_login_count integer DEFAULT 0 NOT NULL,
    locked_until timestamp with time zone,
    last_login_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    email_verification_required boolean NOT NULL DEFAULT false,
    totp_required boolean NOT NULL DEFAULT false,
    totp_ciphertext text,
    totp_last_step bigint NOT NULL DEFAULT -1,
    security_version bigint NOT NULL DEFAULT 0,
    approval_revocation_sequence bigint NOT NULL DEFAULT 0 CHECK (approval_revocation_sequence BETWEEN 0 AND 1000000000000000),
    approval_revocation_delivered bigint NOT NULL DEFAULT 0 CHECK (approval_revocation_delivered BETWEEN 0 AND approval_revocation_sequence),
    approval_revocation_actor uuid,
    approval_revoked_at timestamptz,
    CHECK ((approval_revocation_sequence=0 AND approval_revocation_actor IS NULL AND approval_revoked_at IS NULL)
        OR (approval_revocation_sequence>0 AND tenant_id IS NOT NULL AND approval_revocation_actor IS NOT NULL AND approval_revoked_at IS NOT NULL)),
    CONSTRAINT custody_tenant_user_role_check CHECK (((role)::text = ANY ((ARRAY['PLATFORM_ADMIN'::character varying, 'TENANT_ADMIN'::character varying, 'OPERATOR'::character varying, 'APPROVER'::character varying, 'VIEWER'::character varying])::text[]))),
    CONSTRAINT custody_tenant_user_scope_check CHECK (((((role)::text = 'PLATFORM_ADMIN'::text) AND (tenant_id IS NULL)) OR (((role)::text <> 'PLATFORM_ADMIN'::text) AND (tenant_id IS NOT NULL)))),
    CONSTRAINT custody_tenant_user_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'DISABLED'::character varying])::text[])))
);


--
-- Name: custody_webhook_delivery; Type: TABLE; Schema: public; Owner: -
--

CREATE INDEX custody_tenant_user_approval_revocation_pending_idx ON public.custody_tenant_user(approval_revoked_at,id)
    WHERE approval_revocation_sequence>approval_revocation_delivered;

CREATE TABLE public.custody_webhook_delivery (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    endpoint_id uuid NOT NULL,
    event_id uuid NOT NULL,
    status character varying(24) DEFAULT 'PENDING'::character varying NOT NULL,
    attempt_count integer DEFAULT 0 NOT NULL,
    next_attempt_at timestamp with time zone DEFAULT now(),
    locked_by character varying(160),
    locked_at timestamp with time zone,
    last_http_status integer,
    last_error text,
    last_response text,
    delivered_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    total_attempt_count integer DEFAULT 0 NOT NULL,
    manual_retry_count integer DEFAULT 0 NOT NULL,
    next_attempt_trigger character varying(24) DEFAULT 'AUTOMATIC'::character varying NOT NULL,
    CONSTRAINT custody_webhook_delivery_schedule_check CHECK (status IN ('DELIVERED', 'FAILED') OR next_attempt_at IS NOT NULL),
    CONSTRAINT custody_webhook_delivery_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'DELIVERING'::character varying, 'DELIVERED'::character varying, 'RETRY'::character varying, 'FAILED'::character varying])::text[])))
);


--
-- Name: custody_webhook_delivery_attempt; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.custody_webhook_delivery_attempt (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    delivery_id uuid NOT NULL,
    attempt_number integer NOT NULL,
    retry_cycle integer DEFAULT 0 NOT NULL,
    trigger character varying(24) NOT NULL,
    status character varying(32) DEFAULT 'IN_PROGRESS'::character varying NOT NULL,
    worker_id character varying(160),
    http_status integer,
    error_message text,
    response_body text,
    next_attempt_at timestamp with time zone,
    started_at timestamp with time zone DEFAULT now() NOT NULL,
    completed_at timestamp with time zone,
    duration_ms bigint,
    CONSTRAINT custody_webhook_attempt_status_check CHECK (((status)::text = ANY ((ARRAY['IN_PROGRESS'::character varying, 'DELIVERED'::character varying, 'RETRY_SCHEDULED'::character varying, 'FAILED'::character varying])::text[]))),
    CONSTRAINT custody_webhook_attempt_trigger_check CHECK (((trigger)::text = ANY ((ARRAY['AUTOMATIC'::character varying, 'MANUAL'::character varying, 'RECOVERY'::character varying])::text[])))
);


--
-- Name: custody_webhook_endpoint; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.custody_webhook_endpoint (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    name character varying(120) NOT NULL,
    url character varying(2048) NOT NULL,
    secret_ciphertext text NOT NULL,
    status character varying(24) DEFAULT 'PENDING_VERIFICATION'::character varying NOT NULL,
    verified_at timestamp with time zone,
    last_delivery_at timestamp with time zone,
    created_by uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT custody_webhook_endpoint_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING_VERIFICATION'::character varying, 'ACTIVE'::character varying, 'DISABLED'::character varying])::text[])))
);


--
-- Name: custody_withdrawal; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.custody_withdrawal (
    id uuid NOT NULL,
    subject character varying(160) NOT NULL,
    funding_source character varying(24) NOT NULL CHECK (funding_source IN ('ADDRESS','TENANT_POOL')),
    tenant_id uuid NOT NULL,
    custody_address_id uuid NOT NULL,
    order_no character varying(96) NOT NULL,
    external_reference character varying(160),
    idempotency_key character varying(160),
    chain character varying(32) NOT NULL,
    asset_symbol character varying(32) NOT NULL,
    to_address character varying(160) NOT NULL,
    amount numeric(78,24) NOT NULL,
    fee numeric(78,24) DEFAULT 0 NOT NULL,
    status character varying(32) NOT NULL,
    created_by_type character varying(24) NOT NULL,
    created_by_id character varying(160),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    withdrawal_order_id bigint NOT NULL,
    CONSTRAINT custody_withdrawal_creator_check CHECK (((created_by_type)::text = ANY ((ARRAY['API_KEY'::character varying, 'CONSOLE'::character varying])::text[])))
);


--
-- Name: deposit_record; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.deposit_record (
    id bigint NOT NULL,
    chain character varying(32) NOT NULL,
    asset_symbol character varying(32) NOT NULL,
    tx_hash character varying(128) NOT NULL,
    log_index bigint DEFAULT 0 NOT NULL,
    from_address character varying(160),
    to_address character varying(160) NOT NULL,
    contract_address character varying(128),
    amount numeric(84,24) NOT NULL,
    block_height bigint NOT NULL,
    block_hash character varying(128),
    confirmations integer DEFAULT 0 NOT NULL,
    status character varying(32) DEFAULT 'DETECTED'::character varying NOT NULL,
    credited boolean DEFAULT false NOT NULL,
    credit_generation integer DEFAULT 0 NOT NULL,
    canonical_status character varying(24) DEFAULT 'CANONICAL'::character varying NOT NULL,
    account_id character varying(160) NOT NULL,
    credited_at timestamp with time zone,
    reorged_at timestamp with time zone,
    reorg_reason text,
    raw_payload text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    tenant_id uuid
    ,CONSTRAINT deposit_record_canonical_status_check CHECK (((canonical_status)::text = ANY ((ARRAY['CANONICAL'::character varying, 'REORGED'::character varying])::text[])))
    ,CONSTRAINT deposit_record_credit_generation_check CHECK ((credit_generation >= 0))
);


--
-- Name: deposit_record_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.deposit_record_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: deposit_record_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.deposit_record_id_seq OWNED BY public.deposit_record.id;


--
-- Name: evm_nonce; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.evm_nonce (
    id bigint NOT NULL,
    chain character varying(32) NOT NULL,
    address character varying(128) NOT NULL,
    chain_nonce numeric(78,0) DEFAULT 0 NOT NULL,
    reserved_nonce numeric(78,0) DEFAULT 0 NOT NULL,
    pending_owner varchar(320),
    pending_nonce numeric(78,0),
    CHECK ((pending_owner IS NULL AND pending_nonce IS NULL) OR (pending_owner IS NOT NULL AND length(pending_owner)>0 AND pending_nonce IS NOT NULL AND pending_nonce>=0 AND pending_nonce<reserved_nonce)),
    status character varying(32) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


CREATE UNIQUE INDEX evm_nonce_pending_owner_idx ON public.evm_nonce(chain,pending_owner) WHERE pending_owner IS NOT NULL;

--
-- Name: evm_nonce_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.evm_nonce_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: evm_nonce_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.evm_nonce_id_seq OWNED BY public.evm_nonce.id;


--
-- Name: evm_token_transfer; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.evm_token_transfer (
    id bigint NOT NULL,
    chain character varying(32) NOT NULL,
    tx_hash character varying(128) NOT NULL,
    log_index bigint NOT NULL,
    token_symbol character varying(32) NOT NULL,
    contract_address character varying(128) NOT NULL,
    from_address character varying(128) NOT NULL,
    to_address character varying(128) NOT NULL,
    amount numeric(84,24) DEFAULT 0 NOT NULL,
    block_height bigint NOT NULL,
    confirmations integer DEFAULT 0 NOT NULL,
    status character varying(32) DEFAULT 'DETECTED'::character varying NOT NULL,
    raw_payload text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: evm_token_transfer_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.evm_token_transfer_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: evm_token_transfer_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.evm_token_transfer_id_seq OWNED BY public.evm_token_transfer.id;


--
-- Name: evm_transaction; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.evm_transaction (
    id bigint NOT NULL,
    chain character varying(32) NOT NULL,
    tx_hash character varying(128) NOT NULL,
    from_address character varying(128) NOT NULL,
    to_address character varying(128) NOT NULL,
    asset_symbol character varying(32) NOT NULL,
    contract_address character varying(128),
    amount numeric(84,24) DEFAULT 0 NOT NULL,
    fee numeric(84,24) DEFAULT 0 NOT NULL,
    nonce bigint,
    block_height bigint,
    confirmations integer DEFAULT 0 NOT NULL,
    status character varying(32) DEFAULT 'CREATED'::character varying NOT NULL,
    raw_payload text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: evm_transaction_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.evm_transaction_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: evm_transaction_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.evm_transaction_id_seq OWNED BY public.evm_transaction.id;


--
-- Name: evm_tx; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.evm_tx (
    id bigint NOT NULL,
    chain character varying(32) NOT NULL,
    tx_hash character varying(128) NOT NULL,
    from_address character varying(128) NOT NULL,
    to_address character varying(128) NOT NULL,
    asset_symbol character varying(32) NOT NULL,
    contract_address character varying(128),
    amount numeric(84,24) DEFAULT 0 NOT NULL,
    fee numeric(84,24) DEFAULT 0 NOT NULL,
    nonce bigint,
    block_height bigint,
    confirmations integer DEFAULT 0 NOT NULL,
    status character varying(32) DEFAULT 'NEW'::character varying NOT NULL,
    raw_payload text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: evm_tx_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.evm_tx_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: evm_tx_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.evm_tx_id_seq OWNED BY public.evm_tx.id;


--
-- Name: gas_topup_task; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.gas_topup_task (
    id bigint NOT NULL,
    task_no character varying(96) NOT NULL,
    chain character varying(32) NOT NULL,
    target_address character varying(160) NOT NULL,
    source_address character varying(160),
    amount numeric(84,24) DEFAULT 0 NOT NULL,
    tx_hash character varying(128),
    status character varying(32) DEFAULT 'CREATED'::character varying NOT NULL,
    reason character varying(256),
    retry_count integer DEFAULT 0 NOT NULL,
    raw_payload text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: gas_topup_task_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.gas_topup_task_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: gas_topup_task_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.gas_topup_task_id_seq OWNED BY public.gas_topup_task.id;


--
-- Name: hot_wallet_address; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.hot_wallet_address (
    id bigint NOT NULL,
    chain character varying(32) NOT NULL,
    asset_symbol character varying(32) NOT NULL,
    address character varying(160) NOT NULL,
    address_index bigint DEFAULT 0 NOT NULL,
    wallet_role character varying(32) DEFAULT 'HOT_WITHDRAW'::character varying NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    kms_key_ref character varying(256),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: hot_wallet_address_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.hot_wallet_address_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: hot_wallet_address_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.hot_wallet_address_id_seq OWNED BY public.hot_wallet_address.id;


--
-- Name: hypercore_action_record; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.hypercore_action_record (
    tenant_id uuid NOT NULL,
    request_id uuid NOT NULL UNIQUE,
    action_id character varying(128) NOT NULL,
    action_type character varying(32) NOT NULL,
    chain character varying(32) DEFAULT 'HYPERCORE'::character varying NOT NULL,
    asset_symbol character varying(32) NOT NULL,
    from_address character varying(160) NOT NULL,
    to_address character varying(160) NOT NULL,
    amount numeric(78,24) DEFAULT 0 NOT NULL,
    nonce bigint NOT NULL,
    request_payload text,
    response_payload text,
    execution_receipt jsonb CHECK (execution_receipt IS NULL OR jsonb_typeof(execution_receipt) = 'object'),
    status character varying(32) DEFAULT 'PREPARED'::character varying NOT NULL,
    error_message text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CHECK (status IN ('PREPARED','UNKNOWN','ACCEPTED')),
    CHECK (execution_receipt IS NULL OR status = 'ACCEPTED')
);
COMMENT ON COLUMN public.hypercore_action_record.request_payload IS 'Immutable selected MPC signed /exchange JSON body, committed before RPC';
COMMENT ON COLUMN public.hypercore_action_record.status IS 'PREPARED, UNKNOWN or ACCEPTED; API acknowledgement or original ledger proof; settlement requires execution_receipt';
COMMENT ON COLUMN public.hypercore_action_record.execution_receipt IS 'Original spotTransfer event: exact signed owner nonce, recipient, asset, amount, zero unreserved fees and real chain hash; API reply alone never settles';



--
-- Name: hypercore_spot_asset; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.hypercore_spot_asset (
    network character varying(32) NOT NULL,
    spot_index integer NOT NULL,
    name character varying(96) NOT NULL,
    base_token_index integer,
    quote_token_index integer,
    is_canonical boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: hypercore_token_metadata; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.hypercore_token_metadata (
    network character varying(32) NOT NULL,
    token_index integer NOT NULL,
    token_id character varying(128) NOT NULL,
    name character varying(64) NOT NULL,
    sz_decimals integer,
    wei_decimals integer,
    is_canonical boolean DEFAULT false NOT NULL,
    evm_contract character varying(160),
    full_name character varying(256),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: ledger_balance; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ledger_balance (
    id bigint NOT NULL,
    chain character varying(32) NOT NULL,
    asset_symbol character varying(32) NOT NULL,
    account_id character varying(128) NOT NULL,
    available_balance numeric(84,24) DEFAULT 0 NOT NULL,
    locked_balance numeric(84,24) DEFAULT 0 NOT NULL,
    total_balance numeric(84,24) DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    tenant_id uuid
);


--
-- Name: ledger_balance_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ledger_balance_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ledger_balance_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ledger_balance_id_seq OWNED BY public.ledger_balance.id;


--
-- Name: monero_transaction; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.monero_transaction (
    id bigint NOT NULL,
    chain varchar(32) NOT NULL DEFAULT 'XMR' CHECK (chain = 'XMR'),
    tenant_id uuid NOT NULL,
    request_id uuid NOT NULL,
    sender varchar(128) NOT NULL,
    tx_hash varchar(64) CHECK (tx_hash IS NULL OR tx_hash ~ '^[0-9a-f]{64}$'),
    selected_attempt_id uuid,
    artifact_hash varchar(64) CHECK (artifact_hash IS NULL OR artifact_hash ~ '^[0-9a-f]{64}$'),
    fee_atomic numeric(20,0) CHECK (fee_atomic IS NULL OR fee_atomic BETWEEN 0 AND 18446744073709551615),
    status varchar(32) NOT NULL DEFAULT 'SIGNING' CHECK (status IN ('SIGNING','PREPARED','SENT','CONFIRMED')),
    block_height bigint CHECK (block_height IS NULL OR block_height >= 0),
    block_hash varchar(64) CHECK (block_hash IS NULL OR block_hash ~ '^[0-9a-f]{64}$'),
    confirmations integer NOT NULL DEFAULT 0 CHECK (confirmations >= 0),
    execution_receipt jsonb CHECK (execution_receipt IS NULL OR jsonb_typeof(execution_receipt) = 'object'),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((status = 'SIGNING') = (tx_hash IS NULL)),
    CHECK ((tx_hash IS NULL) = (selected_attempt_id IS NULL)),
    CHECK ((tx_hash IS NULL) = (artifact_hash IS NULL)),
    CHECK ((tx_hash IS NULL) = (fee_atomic IS NULL)),
    CHECK ((status = 'CONFIRMED') = (execution_receipt IS NOT NULL)),
    CHECK ((status = 'CONFIRMED') = (block_height IS NOT NULL)),
    CHECK ((status = 'CONFIRMED') = (block_hash IS NOT NULL)),
    CHECK ((status = 'CONFIRMED') = (confirmations > 0))
);


--
-- Name: monero_transaction_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.monero_transaction_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: monero_transaction_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.monero_transaction_id_seq OWNED BY public.monero_transaction.id;


--
-- Name: near_transaction; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.near_transaction (
    id bigint NOT NULL,
    chain character varying(32) DEFAULT 'NEAR'::character varying NOT NULL,
    tx_hash character varying(128) NOT NULL,
    action_index bigint DEFAULT 0 NOT NULL,
    sender character varying(128),
    receiver character varying(128) NOT NULL,
    asset_symbol character varying(32) DEFAULT 'NEAR'::character varying NOT NULL,
    amount numeric(78,24) DEFAULT 0 NOT NULL,
    gas_burnt bigint DEFAULT 0 NOT NULL,
    fee_yocto numeric(78,0),
    block_height bigint,
    status character varying(32) DEFAULT 'CREATED'::character varying NOT NULL,
    raw_payload text,
    execution_receipt jsonb,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT near_transaction_fee_check CHECK (fee_yocto IS NULL OR fee_yocto >= 0),
    CONSTRAINT near_transaction_receipt_check CHECK (execution_receipt IS NULL OR jsonb_typeof(execution_receipt) = 'object')
);

CREATE INDEX near_transaction_pending_sender_idx ON public.near_transaction (sender)
    WHERE status IN ('SIGNING','PREPARED','SENT','CONFIRMING');
COMMENT ON COLUMN public.near_transaction.fee_yocto IS 'Exact net native expense: burnt fees plus retained attached deposit; NEP-145 excludes completed deposit refunds and never subtracts Gas refunds twice';



--
-- Name: near_transaction_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.near_transaction_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: near_transaction_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.near_transaction_id_seq OWNED BY public.near_transaction.id;


--
-- Name: sol_transaction; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sol_transaction (
    id bigint NOT NULL,
    chain character varying(32) DEFAULT 'SOLANA'::character varying NOT NULL,
    signature character varying(128) NOT NULL,
    from_address character varying(128),
    to_address character varying(128) NOT NULL,
    asset_symbol character varying(32) NOT NULL,
    mint_address character varying(128),
    amount numeric(84,24) DEFAULT 0 NOT NULL,
    fee_lamports bigint DEFAULT 0 NOT NULL,
    slot bigint,
    confirmations integer DEFAULT 0 NOT NULL,
    status character varying(32) DEFAULT 'CREATED'::character varying NOT NULL,
    raw_payload text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Selected outgoing MPC wire is immutable; incoming scanner facts may contain parsed JSON.
COMMENT ON COLUMN public.sol_transaction.raw_payload IS 'MPC outgoing selected Base64 wire retained across observations; incoming scanner JSON is separate transaction data';
COMMENT ON COLUMN public.sol_transaction.status IS 'CREATED, PREPARED, SENT, CONFIRMING, CONFIRMED or FAILED; confirmed terminal observations are never regressed';
COMMENT ON COLUMN public.sol_transaction.fee_lamports IS 'Confirmed fee-payer spending excluding native payment amount; includes SPL ATA rent when created';

-- Name: sol_transaction_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.sol_transaction_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: sol_transaction_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.sol_transaction_id_seq OWNED BY public.sol_transaction.id;


--
-- Name: sui_transaction; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sui_transaction (
    id bigint NOT NULL,
    chain character varying(32) DEFAULT 'SUI'::character varying NOT NULL,
    tx_digest character varying(128) NOT NULL,
    sender character varying(128),
    receiver character varying(128) NOT NULL,
    asset_symbol character varying(32) NOT NULL,
    coin_type character varying(256),
    amount numeric(84,24) DEFAULT 0 NOT NULL,
    fee_mist bigint DEFAULT 0 NOT NULL,
    checkpoint bigint,
    status character varying(32) DEFAULT 'CREATED'::character varying NOT NULL,
    raw_payload text,
    execution_receipt jsonb CHECK (execution_receipt IS NULL OR jsonb_typeof(execution_receipt) = 'object'),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: sui_transaction_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.sui_transaction_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: sui_transaction_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.sui_transaction_id_seq OWNED BY public.sui_transaction.id;


--
-- Name: token_config; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.token_config (
    id bigint NOT NULL,
    chain character varying(32) NOT NULL,
    symbol character varying(32) NOT NULL,
    standard character varying(32) NOT NULL,
    contract_address character varying(128) NOT NULL,
    decimals integer NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    min_deposit numeric(84,24),
    min_withdraw numeric(84,24),
    collect_enabled boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    network character varying(32) NOT NULL,
    contract_address_base58 character varying(128),
    contract_address_hex character varying(128),
    collect_threshold numeric(84,24),
    gas_strategy character varying(64),
    confirmation_required integer,
    CONSTRAINT token_config_network_nonblank CHECK (btrim(network) <> '')
);


--
-- Name: token_config_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.token_config_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: token_config_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.token_config_id_seq OWNED BY public.token_config.id;


--
-- Name: token_registry; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.token_registry (
    id bigint NOT NULL,
    chain character varying(32) NOT NULL,
    symbol character varying(32) NOT NULL,
    contract_address character varying(128) NOT NULL,
    decimals integer DEFAULT 18 NOT NULL,
    standard character varying(32) NOT NULL,
    native_asset boolean DEFAULT false NOT NULL,
    active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: token_registry_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.token_registry_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: token_registry_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.token_registry_id_seq OWNED BY public.token_registry.id;


--
-- Name: ton_transaction; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ton_transaction (
    id bigint NOT NULL,
    chain character varying(32) DEFAULT 'TON'::character varying NOT NULL,
    tx_hash character varying(128) NOT NULL,
    from_address character varying(160),
    to_address character varying(160) NOT NULL,
    asset_symbol character varying(32) NOT NULL,
    jetton_master character varying(160),
    amount numeric(84,24) DEFAULT 0 NOT NULL,
    fee_nano bigint DEFAULT 0 NOT NULL CHECK (fee_nano >= 0),
    logical_time numeric(78,0),
    confirmations integer DEFAULT 0 NOT NULL,
    status character varying(32) DEFAULT 'CREATED'::character varying NOT NULL,
    raw_payload text,
    execution_receipt jsonb CHECK (execution_receipt IS NULL OR jsonb_typeof(execution_receipt) = 'object'),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: ton_transaction_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ton_transaction_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ton_transaction_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ton_transaction_id_seq OWNED BY public.ton_transaction.id;


--
-- Name: tron_token_transfer; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tron_token_transfer (
    id bigint NOT NULL,
    chain character varying(32) DEFAULT 'TRON'::character varying NOT NULL,
    tx_hash character varying(128) NOT NULL,
    log_index bigint DEFAULT 0 NOT NULL,
    token_symbol character varying(32) NOT NULL,
    contract_address character varying(128) NOT NULL,
    from_address character varying(128) NOT NULL,
    to_address character varying(128) NOT NULL,
    amount numeric(84,24) DEFAULT 0 NOT NULL,
    block_height bigint NOT NULL,
    confirmations integer DEFAULT 0 NOT NULL,
    status character varying(32) DEFAULT 'DETECTED'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: tron_token_transfer_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.tron_token_transfer_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: tron_token_transfer_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.tron_token_transfer_id_seq OWNED BY public.tron_token_transfer.id;


--
-- Name: tron_transaction; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tron_transaction (
    id bigint NOT NULL,
    chain character varying(32) DEFAULT 'TRON'::character varying NOT NULL,
    tx_hash character varying(128) NOT NULL,
    from_address character varying(128) NOT NULL,
    to_address character varying(128) NOT NULL,
    asset_symbol character varying(32) NOT NULL,
    contract_address character varying(128),
    amount numeric(84,24) DEFAULT 0 NOT NULL,
    fee numeric(84,24) DEFAULT 0 NOT NULL,
    block_height bigint,
    confirmations integer DEFAULT 0 NOT NULL,
    status character varying(32) DEFAULT 'CREATED'::character varying NOT NULL,
    raw_payload text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: tron_transaction_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.tron_transaction_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: tron_transaction_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.tron_transaction_id_seq OWNED BY public.tron_transaction.id;


--
-- Name: tron_tx; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tron_tx (
    id bigint NOT NULL,
    chain character varying(32) NOT NULL,
    tx_hash character varying(128) NOT NULL,
    from_address character varying(128) NOT NULL,
    to_address character varying(128) NOT NULL,
    asset_symbol character varying(32) NOT NULL,
    contract_address character varying(128),
    amount numeric(84,24) DEFAULT 0 NOT NULL,
    fee numeric(84,24) DEFAULT 0 NOT NULL,
    block_height bigint,
    confirmations integer DEFAULT 0 NOT NULL,
    status character varying(32) DEFAULT 'NEW'::character varying NOT NULL,
    raw_payload text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: tron_tx_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.tron_tx_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: tron_tx_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.tron_tx_id_seq OWNED BY public.tron_tx.id;


--
-- Name: utxo_record; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.utxo_record (
    id bigint NOT NULL,
    chain character varying(32) NOT NULL,
    asset_symbol character varying(32) NOT NULL,
    tx_hash character varying(128) NOT NULL,
    vout integer NOT NULL,
    address character varying(160) NOT NULL,
    amount numeric(84,24) NOT NULL,
    block_height bigint NOT NULL,
    block_hash character varying(128) NOT NULL,
    confirmations integer DEFAULT 0 NOT NULL,
    state character varying(32) DEFAULT 'AVAILABLE'::character varying NOT NULL,
    lock_ref character varying(128),
    spent_tx_hash character varying(128),
    credited boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: utxo_record_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.utxo_record_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: utxo_record_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.utxo_record_id_seq OWNED BY public.utxo_record.id;


--
-- Name: wallet_public_key; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.wallet_public_key (
    key_slot integer NOT NULL,
    key_role character varying(64) NOT NULL,
    key_type character varying(32) DEFAULT 'BIP32_XPUB'::character varying NOT NULL,
    network character varying(32) DEFAULT 'test'::character varying NOT NULL,
    public_key text NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    remark text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: wallet_system_config; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.wallet_system_config (
    config_key character varying(128) NOT NULL,
    config_value text NOT NULL,
    value_type character varying(32) DEFAULT 'boolean'::character varying NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    remark text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: wallet_transfer_order; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.wallet_transfer_order (
    id bigint NOT NULL,
    transfer_no character varying(96) NOT NULL,
    from_user_id bigint NOT NULL,
    to_user_id bigint NOT NULL,
    chain character varying(32) NOT NULL,
    asset_symbol character varying(32) NOT NULL,
    amount numeric(78,24) NOT NULL,
    from_account_id character varying(160) NOT NULL,
    to_account_id character varying(160) NOT NULL,
    status character varying(32) DEFAULT 'CREATED'::character varying NOT NULL,
    error_message text,
    completed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: wallet_transfer_order_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.wallet_transfer_order ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.wallet_transfer_order_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: wallet_user; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.wallet_user (
    id bigint NOT NULL,
    email character varying(160) NOT NULL,
    password_hash text NOT NULL,
    display_name character varying(64) NOT NULL,
    status character varying(32) DEFAULT 'ACTIVE'::character varying NOT NULL,
    failed_login_count integer DEFAULT 0 NOT NULL,
    locked_until timestamp with time zone,
    last_login_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: wallet_user_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.wallet_user ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.wallet_user_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: wallet_user_session; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.wallet_user_session (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    token_hash character varying(64) NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    revoked_at timestamp with time zone,
    ip_address character varying(64),
    user_agent character varying(300),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    last_seen_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: wallet_user_session_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.wallet_user_session ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.wallet_user_session_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: withdrawal_order; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.withdrawal_order (
    durable_submission boolean NOT NULL DEFAULT false,
    funds_released boolean NOT NULL DEFAULT false,
    id bigint NOT NULL,
    order_no character varying(96) NOT NULL,
    user_id bigint NOT NULL,
    chain character varying(32) NOT NULL,
    asset_symbol character varying(32) NOT NULL,
    from_address character varying(160),
    to_address character varying(160) NOT NULL,
    amount numeric(84,24) NOT NULL,
    fee numeric(84,24) DEFAULT 0 NOT NULL,
    tx_hash character varying(128),
    status character varying(32) DEFAULT 'CREATED'::character varying NOT NULL,
    error_message text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    debit_account_id character varying(160),
    tenant_id uuid
);


--
-- Name: withdrawal_order_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.withdrawal_order_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: withdrawal_order_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.withdrawal_order_id_seq OWNED BY public.withdrawal_order.id;


--
-- Name: withdrawal_review_audit; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.withdrawal_review_audit (
    review_id bigint NOT NULL,
    withdrawal_id bigint,
    order_no character varying(96) NOT NULL,
    user_id bigint NOT NULL,
    chain character varying(32) NOT NULL,
    asset_symbol character varying(32) NOT NULL,
    amount numeric(78,24) NOT NULL,
    fee numeric(78,24) DEFAULT 0 NOT NULL,
    from_address character varying(160),
    debit_account_id character varying(160),
    to_address character varying(160) NOT NULL,
    previous_status character varying(32) NOT NULL,
    next_status character varying(32) NOT NULL,
    decision character varying(32) NOT NULL,
    admin_user_id bigint,
    admin_username character varying(160),
    reason text,
    released_amount numeric(78,24),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT withdrawal_review_audit_decision_check CHECK (((decision)::text = ANY (ARRAY[('APPROVED'::character varying)::text, ('REJECTED'::character varying)::text])))
);


--
-- Name: withdrawal_review_audit_review_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.withdrawal_review_audit ALTER COLUMN review_id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.withdrawal_review_audit_review_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: xrp_transaction; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.xrp_transaction (
    id bigint NOT NULL,
    chain character varying(32) DEFAULT 'XRP'::character varying NOT NULL,
    tx_hash character varying(128) NOT NULL,
    from_address character varying(160),
    to_address character varying(160) NOT NULL,
    asset_symbol character varying(32) NOT NULL,
    issuer_address character varying(160),
    currency_code character varying(64),
    amount numeric(78,24) DEFAULT 0 NOT NULL,
    fee_drops bigint DEFAULT 0 NOT NULL,
    ledger_index bigint,
    sequence_number bigint,
    confirmations integer DEFAULT 0 NOT NULL,
    status character varying(32) DEFAULT 'CREATED'::character varying NOT NULL,
    raw_payload text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- XRP MPC: selected canonical signed binary is immutable; observations retain it.
COMMENT ON COLUMN public.xrp_transaction.raw_payload IS 'Selected signed XRPL binary hex; immutable across RPC observation and recovery';
COMMENT ON COLUMN public.xrp_transaction.status IS 'CREATED, PREPARED, SENT, CONFIRMED or FAILED; validated terminal state is never regressed';

-- Name: xrp_transaction_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.xrp_transaction_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: xrp_transaction_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.xrp_transaction_id_seq OWNED BY public.xrp_transaction.id;


--
-- Name: account_sequence id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.account_sequence ALTER COLUMN id SET DEFAULT nextval('public.account_sequence_id_seq'::regclass);


--
-- Name: aptos_transaction id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.aptos_transaction ALTER COLUMN id SET DEFAULT nextval('public.aptos_transaction_id_seq'::regclass);



--
-- Name: chain_address id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chain_address ALTER COLUMN id SET DEFAULT nextval('public.chain_address_id_seq'::regclass);


--
-- Name: chain_asset id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chain_asset ALTER COLUMN id SET DEFAULT nextval('public.chain_asset_id_seq'::regclass);


--
-- Name: chain_profile id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chain_profile ALTER COLUMN id SET DEFAULT nextval('public.chain_profile_id_seq'::regclass);


--
-- Name: chain_rpc_node id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chain_rpc_node ALTER COLUMN id SET DEFAULT nextval('public.chain_rpc_node_id_seq'::regclass);


--
-- Name: chain_scan_height id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chain_scan_height ALTER COLUMN id SET DEFAULT nextval('public.chain_scan_height_id_seq'::regclass);


--
-- Name: collection_record id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.collection_record ALTER COLUMN id SET DEFAULT nextval('public.collection_record_id_seq'::regclass);


--
-- Name: deposit_record id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.deposit_record ALTER COLUMN id SET DEFAULT nextval('public.deposit_record_id_seq'::regclass);


--
-- Name: evm_nonce id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.evm_nonce ALTER COLUMN id SET DEFAULT nextval('public.evm_nonce_id_seq'::regclass);


--
-- Name: evm_token_transfer id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.evm_token_transfer ALTER COLUMN id SET DEFAULT nextval('public.evm_token_transfer_id_seq'::regclass);


--
-- Name: evm_transaction id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.evm_transaction ALTER COLUMN id SET DEFAULT nextval('public.evm_transaction_id_seq'::regclass);


--
-- Name: evm_tx id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.evm_tx ALTER COLUMN id SET DEFAULT nextval('public.evm_tx_id_seq'::regclass);


--
-- Name: gas_topup_task id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.gas_topup_task ALTER COLUMN id SET DEFAULT nextval('public.gas_topup_task_id_seq'::regclass);


--
-- Name: hot_wallet_address id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.hot_wallet_address ALTER COLUMN id SET DEFAULT nextval('public.hot_wallet_address_id_seq'::regclass);


--
-- Name: ledger_balance id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ledger_balance ALTER COLUMN id SET DEFAULT nextval('public.ledger_balance_id_seq'::regclass);


--
-- Name: monero_transaction id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.monero_transaction ALTER COLUMN id SET DEFAULT nextval('public.monero_transaction_id_seq'::regclass);


--
-- Name: near_transaction id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.near_transaction ALTER COLUMN id SET DEFAULT nextval('public.near_transaction_id_seq'::regclass);


--
-- Name: sol_transaction id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sol_transaction ALTER COLUMN id SET DEFAULT nextval('public.sol_transaction_id_seq'::regclass);


--
-- Name: sui_transaction id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sui_transaction ALTER COLUMN id SET DEFAULT nextval('public.sui_transaction_id_seq'::regclass);


--
-- Name: token_config id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.token_config ALTER COLUMN id SET DEFAULT nextval('public.token_config_id_seq'::regclass);


--
-- Name: token_registry id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.token_registry ALTER COLUMN id SET DEFAULT nextval('public.token_registry_id_seq'::regclass);


--
-- Name: ton_transaction id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ton_transaction ALTER COLUMN id SET DEFAULT nextval('public.ton_transaction_id_seq'::regclass);


--
-- Name: tron_token_transfer id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tron_token_transfer ALTER COLUMN id SET DEFAULT nextval('public.tron_token_transfer_id_seq'::regclass);


--
-- Name: tron_transaction id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tron_transaction ALTER COLUMN id SET DEFAULT nextval('public.tron_transaction_id_seq'::regclass);


--
-- Name: tron_tx id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tron_tx ALTER COLUMN id SET DEFAULT nextval('public.tron_tx_id_seq'::regclass);


--
-- Name: utxo_record id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.utxo_record ALTER COLUMN id SET DEFAULT nextval('public.utxo_record_id_seq'::regclass);


--
-- Name: withdrawal_order id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.withdrawal_order ALTER COLUMN id SET DEFAULT nextval('public.withdrawal_order_id_seq'::regclass);


--
-- Name: xrp_transaction id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.xrp_transaction ALTER COLUMN id SET DEFAULT nextval('public.xrp_transaction_id_seq'::regclass);


--
-- Name: account_sequence account_sequence_chain_address_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.account_sequence
    ADD CONSTRAINT account_sequence_chain_address_key UNIQUE (chain, address);


--
-- Name: account_sequence account_sequence_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.account_sequence
    ADD CONSTRAINT account_sequence_pkey PRIMARY KEY (id);


--
-- Name: aptos_transaction aptos_transaction_chain_tx_hash_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.aptos_transaction
    ADD CONSTRAINT aptos_transaction_chain_tx_hash_key UNIQUE (chain, tx_hash);


--
-- Name: aptos_transaction aptos_transaction_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.aptos_transaction
    ADD CONSTRAINT aptos_transaction_pkey PRIMARY KEY (id);





--
-- Name: chain_address chain_address_chain_asset_symbol_address_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chain_address
    ADD CONSTRAINT chain_address_chain_asset_symbol_address_key UNIQUE (chain, asset_symbol, address);


--
-- Name: chain_address chain_address_chain_asset_symbol_user_id_biz_address_index__key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chain_address
    ADD CONSTRAINT chain_address_chain_asset_symbol_user_id_biz_address_index__key UNIQUE (chain, asset_symbol, user_id, biz, address_index, wallet_role);


--
-- Name: chain_address chain_address_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chain_address
    ADD CONSTRAINT chain_address_pkey PRIMARY KEY (id);


--
-- Name: chain_address chain_address_tenant_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chain_address
    ADD CONSTRAINT chain_address_tenant_id_key UNIQUE (tenant_id, id);


--
-- Name: chain_asset chain_asset_chain_symbol_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chain_asset
    ADD CONSTRAINT chain_asset_chain_symbol_key UNIQUE (chain, symbol);

CREATE UNIQUE INDEX chain_asset_one_active_native_per_chain
    ON public.chain_asset (chain)
    WHERE native_asset = true AND active = true;


--
-- Name: chain_asset chain_asset_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chain_asset
    ADD CONSTRAINT chain_asset_pkey PRIMARY KEY (id);


--
-- Name: chain_profile chain_profile_chain_network_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chain_profile
    ADD CONSTRAINT chain_profile_chain_network_key UNIQUE (chain, network);


--
-- Name: chain_profile chain_profile_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chain_profile
    ADD CONSTRAINT chain_profile_pkey PRIMARY KEY (id);


--
-- Name: chain_profile chain_profile_runtime_currency_id_network_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chain_profile
    ADD CONSTRAINT chain_profile_runtime_currency_id_network_key UNIQUE (runtime_currency_id, network);


--
-- Name: chain_rpc_node chain_rpc_node_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chain_rpc_node
    ADD CONSTRAINT chain_rpc_node_pkey PRIMARY KEY (id);


--
-- Name: chain_rpc_node chain_rpc_node_unique_label; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chain_rpc_node
    ADD CONSTRAINT chain_rpc_node_unique_label UNIQUE (chain, network, environment, purpose, node_label);


--
-- Name: chain_scan_height chain_scan_height_chain_scanner_name_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chain_scan_height
    ADD CONSTRAINT chain_scan_height_chain_scanner_name_key UNIQUE (chain, scanner_name);


--
-- Name: chain_scan_height chain_scan_height_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chain_scan_height
    ADD CONSTRAINT chain_scan_height_pkey PRIMARY KEY (id);


--
-- Name: chain_signing_transaction chain_signing_transaction_chain_business_type_business_no_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chain_signing_transaction
    ADD CONSTRAINT chain_signing_transaction_chain_business_type_business_no_key UNIQUE (chain, business_type, business_no);


--
-- Name: chain_signing_transaction chain_signing_transaction_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chain_signing_transaction
    ADD CONSTRAINT chain_signing_transaction_pkey PRIMARY KEY (id);


--
-- Name: collection_record collection_record_chain_asset_symbol_from_address_to_addres_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.collection_record
    ADD CONSTRAINT collection_record_chain_asset_symbol_from_address_to_addres_key UNIQUE (chain, asset_symbol, from_address, to_address, tx_hash);


--
-- Name: collection_record collection_record_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.collection_record
    ADD CONSTRAINT collection_record_pkey PRIMARY KEY (id);


--
-- Name: contract_deployment_order contract_deployment_order_chain_order_no_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.contract_deployment_order
    ADD CONSTRAINT contract_deployment_order_chain_order_no_key UNIQUE (chain, order_no);


--
-- Name: contract_deployment_order contract_deployment_order_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.contract_deployment_order
    ADD CONSTRAINT contract_deployment_order_pkey PRIMARY KEY (id);


--
-- Name: custody_address custody_address_chain_address_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_address
    ADD CONSTRAINT custody_address_chain_address_key UNIQUE (chain_address_id);


--
-- Name: custody_address custody_address_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_address
    ADD CONSTRAINT custody_address_pkey PRIMARY KEY (id);


--
-- Name: custody_address custody_address_tenant_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_address
    ADD CONSTRAINT custody_address_tenant_id_key UNIQUE (tenant_id, id);


--
-- Name: custody_api_key custody_api_key_key_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_api_key
    ADD CONSTRAINT custody_api_key_key_id_key UNIQUE (key_id);


--
-- Name: custody_api_key custody_api_key_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_api_key
    ADD CONSTRAINT custody_api_key_pkey PRIMARY KEY (id);


--
-- Name: custody_api_nonce custody_api_nonce_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_api_nonce
    ADD CONSTRAINT custody_api_nonce_pkey PRIMARY KEY (key_id, nonce);


--
-- Name: custody_asset_price custody_asset_price_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_asset_price
    ADD CONSTRAINT custody_asset_price_pkey PRIMARY KEY (asset_symbol);


--
-- Name: custody_audit_log custody_audit_log_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_audit_log
    ADD CONSTRAINT custody_audit_log_pkey PRIMARY KEY (id);


--
-- Name: custody_deposit custody_deposit_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_deposit
    ADD CONSTRAINT custody_deposit_pkey PRIMARY KEY (id);


--
-- Name: custody_deposit custody_deposit_tenant_record_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_deposit
    ADD CONSTRAINT custody_deposit_tenant_record_key UNIQUE (tenant_id, deposit_record_id);


--
-- Name: custody_dev_faucet_funding custody_dev_faucet_funding_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_dev_faucet_funding
    ADD CONSTRAINT custody_dev_faucet_funding_pkey PRIMARY KEY (id);


--
-- Name: custody_dev_faucet_funding custody_dev_faucet_funding_address_asset_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_dev_faucet_funding
    ADD CONSTRAINT custody_dev_faucet_funding_address_asset_key UNIQUE (custody_address_id, asset_symbol, purpose);


--
-- Name: custody_derivation_subject custody_derivation_subject_path_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_derivation_subject
    ADD CONSTRAINT custody_derivation_subject_path_key UNIQUE (derivation_subject);


--
-- Name: custody_derivation_subject custody_derivation_subject_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_derivation_subject
    ADD CONSTRAINT custody_derivation_subject_pkey PRIMARY KEY (tenant_id, subject);


--
-- Name: custody_event custody_event_business_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_event
    ADD CONSTRAINT custody_event_business_key UNIQUE (tenant_id, event_type, aggregate_type, aggregate_id);


--
-- Name: custody_event custody_event_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_event
    ADD CONSTRAINT custody_event_pkey PRIMARY KEY (id);


--
-- Name: custody_event custody_event_tenant_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_event
    ADD CONSTRAINT custody_event_tenant_id_key UNIQUE (tenant_id, id);


--
-- Name: custody_gas_account custody_gas_account_address_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_gas_account
    ADD CONSTRAINT custody_gas_account_address_key UNIQUE (custody_address_id);


--
-- Name: custody_gas_account custody_gas_account_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_gas_account
    ADD CONSTRAINT custody_gas_account_pkey PRIMARY KEY (id);


--
-- Name: custody_gas_account custody_gas_account_tenant_chain_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_gas_account
    ADD CONSTRAINT custody_gas_account_tenant_chain_key UNIQUE (tenant_id, chain);


--
-- Name: custody_gas_account custody_gas_account_tenant_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_gas_account
    ADD CONSTRAINT custody_gas_account_tenant_id_key UNIQUE (tenant_id, id);


--
-- Name: custody_gas_usage custody_gas_usage_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_gas_usage
    ADD CONSTRAINT custody_gas_usage_pkey PRIMARY KEY (id);


--
-- Name: custody_gas_usage custody_gas_usage_operation_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_gas_usage
    ADD CONSTRAINT custody_gas_usage_operation_key UNIQUE (tenant_id, operation_type, operation_id);


--
-- Name: custody_idempotency_key custody_idempotency_key_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_idempotency_key
    ADD CONSTRAINT custody_idempotency_key_pkey PRIMARY KEY (tenant_id, idempotency_key, operation);


--
-- Name: custody_ip_rule custody_ip_rule_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_ip_rule
    ADD CONSTRAINT custody_ip_rule_pkey PRIMARY KEY (id);


--
-- Name: custody_ip_rule custody_ip_rule_tenant_cidr_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_ip_rule
    ADD CONSTRAINT custody_ip_rule_tenant_cidr_key UNIQUE (tenant_id, cidr);


--
-- Name: custody_ledger_entry custody_ledger_entry_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_ledger_entry
    ADD CONSTRAINT custody_ledger_entry_pkey PRIMARY KEY (id);


--
-- Name: custody_ledger_entry custody_ledger_entry_reference_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_ledger_entry
    ADD CONSTRAINT custody_ledger_entry_reference_key UNIQUE (tenant_id, entry_type, reference_type, reference_id);


--
-- Name: custody_session custody_session_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_session
    ADD CONSTRAINT custody_session_pkey PRIMARY KEY (id);


--
-- Name: custody_session custody_session_token_hash_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_session
    ADD CONSTRAINT custody_session_token_hash_key UNIQUE (token_hash);


--
-- Name: custody_tenant_chain custody_tenant_chain_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_tenant_chain
    ADD CONSTRAINT custody_tenant_chain_pkey PRIMARY KEY (tenant_id, chain);


--
-- Name: custody_tenant custody_tenant_derivation_namespace_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_tenant
    ADD CONSTRAINT custody_tenant_derivation_namespace_key UNIQUE (derivation_namespace);


--
-- Name: custody_tenant custody_tenant_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_tenant
    ADD CONSTRAINT custody_tenant_pkey PRIMARY KEY (id);


--
-- Name: custody_tenant custody_tenant_slug_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_tenant
    ADD CONSTRAINT custody_tenant_slug_key UNIQUE (slug);


--
-- Name: custody_tenant_user custody_tenant_user_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_tenant_user
    ADD CONSTRAINT custody_tenant_user_pkey PRIMARY KEY (id);


--
-- Name: custody_tenant_user custody_tenant_user_tenant_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_tenant_user
    ADD CONSTRAINT custody_tenant_user_tenant_id_key UNIQUE (tenant_id, id);


--
-- Name: custody_webhook_delivery_attempt custody_webhook_attempt_number_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_webhook_delivery_attempt
    ADD CONSTRAINT custody_webhook_attempt_number_key UNIQUE (delivery_id, attempt_number);


--
-- Name: custody_webhook_delivery_attempt custody_webhook_delivery_attempt_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_webhook_delivery_attempt
    ADD CONSTRAINT custody_webhook_delivery_attempt_pkey PRIMARY KEY (id);


--
-- Name: custody_webhook_delivery custody_webhook_delivery_event_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_webhook_delivery
    ADD CONSTRAINT custody_webhook_delivery_event_key UNIQUE (endpoint_id, event_id);


--
-- Name: custody_webhook_delivery custody_webhook_delivery_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_webhook_delivery
    ADD CONSTRAINT custody_webhook_delivery_pkey PRIMARY KEY (id);


--
-- Name: custody_webhook_delivery custody_webhook_delivery_tenant_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_webhook_delivery
    ADD CONSTRAINT custody_webhook_delivery_tenant_id_key UNIQUE (tenant_id, id);


--
-- Name: custody_webhook_endpoint custody_webhook_endpoint_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_webhook_endpoint
    ADD CONSTRAINT custody_webhook_endpoint_pkey PRIMARY KEY (id);


--
-- Name: custody_webhook_endpoint custody_webhook_endpoint_tenant_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_webhook_endpoint
    ADD CONSTRAINT custody_webhook_endpoint_tenant_id_key UNIQUE (tenant_id, id);


--
-- Name: custody_webhook_endpoint custody_webhook_endpoint_tenant_url_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_webhook_endpoint
    ADD CONSTRAINT custody_webhook_endpoint_tenant_url_key UNIQUE (tenant_id, url);


--
-- Name: custody_withdrawal custody_withdrawal_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_withdrawal
    ADD CONSTRAINT custody_withdrawal_pkey PRIMARY KEY (id);


--
-- Name: custody_withdrawal custody_withdrawal_tenant_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_withdrawal
    ADD CONSTRAINT custody_withdrawal_tenant_id_key UNIQUE (tenant_id, id);


--
-- Name: custody_withdrawal custody_withdrawal_tenant_order_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_withdrawal
    ADD CONSTRAINT custody_withdrawal_tenant_order_key UNIQUE (tenant_id, order_no);


--
-- Name: deposit_record deposit_record_chain_tx_hash_log_index_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.deposit_record
    ADD CONSTRAINT deposit_record_chain_tx_hash_log_index_key UNIQUE (chain, tx_hash, log_index);


--
-- Name: deposit_record deposit_record_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.deposit_record
    ADD CONSTRAINT deposit_record_pkey PRIMARY KEY (id);


--
-- Name: deposit_record deposit_record_tenant_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.deposit_record
    ADD CONSTRAINT deposit_record_tenant_id_key UNIQUE (tenant_id, id);


--
-- Name: evm_nonce evm_nonce_chain_address_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.evm_nonce
    ADD CONSTRAINT evm_nonce_chain_address_key UNIQUE (chain, address);


--
-- Name: evm_nonce evm_nonce_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.evm_nonce
    ADD CONSTRAINT evm_nonce_pkey PRIMARY KEY (id);


--
-- Name: evm_token_transfer evm_token_transfer_chain_tx_hash_log_index_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.evm_token_transfer
    ADD CONSTRAINT evm_token_transfer_chain_tx_hash_log_index_key UNIQUE (chain, tx_hash, log_index);


--
-- Name: evm_token_transfer evm_token_transfer_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.evm_token_transfer
    ADD CONSTRAINT evm_token_transfer_pkey PRIMARY KEY (id);


--
-- Name: evm_transaction evm_transaction_chain_tx_hash_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.evm_transaction
    ADD CONSTRAINT evm_transaction_chain_tx_hash_key UNIQUE (chain, tx_hash);


--
-- Name: evm_transaction evm_transaction_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.evm_transaction
    ADD CONSTRAINT evm_transaction_pkey PRIMARY KEY (id);


--
-- Name: evm_tx evm_tx_chain_tx_hash_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.evm_tx
    ADD CONSTRAINT evm_tx_chain_tx_hash_key UNIQUE (chain, tx_hash);


--
-- Name: evm_tx evm_tx_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.evm_tx
    ADD CONSTRAINT evm_tx_pkey PRIMARY KEY (id);


--
-- Name: gas_topup_task gas_topup_task_chain_target_address_status_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.gas_topup_task
    ADD CONSTRAINT gas_topup_task_chain_target_address_status_key UNIQUE (chain, target_address, status);


--
-- Name: gas_topup_task gas_topup_task_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.gas_topup_task
    ADD CONSTRAINT gas_topup_task_pkey PRIMARY KEY (id);


--
-- Name: gas_topup_task gas_topup_task_task_no_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.gas_topup_task
    ADD CONSTRAINT gas_topup_task_task_no_key UNIQUE (task_no);


--
-- Name: hot_wallet_address hot_wallet_address_chain_asset_symbol_wallet_role_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.hot_wallet_address
    ADD CONSTRAINT hot_wallet_address_chain_asset_symbol_wallet_role_key UNIQUE (chain, asset_symbol, wallet_role);


--
-- Name: hot_wallet_address hot_wallet_address_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.hot_wallet_address
    ADD CONSTRAINT hot_wallet_address_pkey PRIMARY KEY (id);


--
-- Name: hypercore_action_record hypercore_action_record_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.hypercore_action_record
    ADD CONSTRAINT hypercore_action_record_pkey PRIMARY KEY (action_id);


--
-- Name: hypercore_spot_asset hypercore_spot_asset_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.hypercore_spot_asset
    ADD CONSTRAINT hypercore_spot_asset_pkey PRIMARY KEY (network, spot_index);


--
-- Name: hypercore_token_metadata hypercore_token_metadata_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.hypercore_token_metadata
    ADD CONSTRAINT hypercore_token_metadata_pkey PRIMARY KEY (network, token_index);


--
-- Name: ledger_balance ledger_balance_chain_asset_symbol_account_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ledger_balance
    ADD CONSTRAINT ledger_balance_chain_asset_symbol_account_id_key UNIQUE (chain, asset_symbol, account_id);


--
-- Name: ledger_balance ledger_balance_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ledger_balance
    ADD CONSTRAINT ledger_balance_pkey PRIMARY KEY (id);


--
-- Name: ledger_balance ledger_balance_tenant_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ledger_balance
    ADD CONSTRAINT ledger_balance_tenant_id_key UNIQUE (tenant_id, id);


--
-- Name: monero_transaction monero_transaction_tenant_request_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.monero_transaction
    ADD CONSTRAINT monero_transaction_tenant_request_key UNIQUE (tenant_id, request_id);


--
-- Name: monero_transaction monero_transaction_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.monero_transaction
    ADD CONSTRAINT monero_transaction_pkey PRIMARY KEY (id);


--
-- Name: near_transaction near_transaction_chain_tx_hash_action_index_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.near_transaction
    ADD CONSTRAINT near_transaction_chain_tx_hash_action_index_key UNIQUE (chain, tx_hash, action_index);


--
-- Name: near_transaction near_transaction_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.near_transaction
    ADD CONSTRAINT near_transaction_pkey PRIMARY KEY (id);


--
-- Name: sol_transaction sol_transaction_chain_signature_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sol_transaction
    ADD CONSTRAINT sol_transaction_chain_signature_key UNIQUE (chain, signature);


--
-- Name: sol_transaction sol_transaction_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sol_transaction
    ADD CONSTRAINT sol_transaction_pkey PRIMARY KEY (id);


--
-- Name: sui_transaction sui_transaction_chain_tx_digest_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sui_transaction
    ADD CONSTRAINT sui_transaction_chain_tx_digest_key UNIQUE (chain, tx_digest);


--
-- Name: sui_transaction sui_transaction_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sui_transaction
    ADD CONSTRAINT sui_transaction_pkey PRIMARY KEY (id);


--
-- Name: token_config token_config_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.token_config
    ADD CONSTRAINT token_config_pkey PRIMARY KEY (id);


--
-- Name: token_registry token_registry_chain_contract_address_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.token_registry
    ADD CONSTRAINT token_registry_chain_contract_address_key UNIQUE (chain, contract_address);


--
-- Name: token_registry token_registry_chain_symbol_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.token_registry
    ADD CONSTRAINT token_registry_chain_symbol_key UNIQUE (chain, symbol);


--
-- Name: token_registry token_registry_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.token_registry
    ADD CONSTRAINT token_registry_pkey PRIMARY KEY (id);


--
-- Name: ton_transaction ton_transaction_chain_tx_hash_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ton_transaction
    ADD CONSTRAINT ton_transaction_chain_tx_hash_key UNIQUE (chain, tx_hash);


--
-- Name: ton_transaction ton_transaction_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ton_transaction
    ADD CONSTRAINT ton_transaction_pkey PRIMARY KEY (id);


--
-- Name: tron_token_transfer tron_token_transfer_chain_tx_hash_log_index_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tron_token_transfer
    ADD CONSTRAINT tron_token_transfer_chain_tx_hash_log_index_key UNIQUE (chain, tx_hash, log_index);


--
-- Name: tron_token_transfer tron_token_transfer_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tron_token_transfer
    ADD CONSTRAINT tron_token_transfer_pkey PRIMARY KEY (id);


--
-- Name: tron_transaction tron_transaction_chain_tx_hash_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tron_transaction
    ADD CONSTRAINT tron_transaction_chain_tx_hash_key UNIQUE (chain, tx_hash);


--
-- Name: tron_transaction tron_transaction_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tron_transaction
    ADD CONSTRAINT tron_transaction_pkey PRIMARY KEY (id);


--
-- Name: tron_tx tron_tx_chain_tx_hash_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tron_tx
    ADD CONSTRAINT tron_tx_chain_tx_hash_key UNIQUE (chain, tx_hash);


--
-- Name: tron_tx tron_tx_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tron_tx
    ADD CONSTRAINT tron_tx_pkey PRIMARY KEY (id);


--
-- Name: collection_record uq_collection_record_chain_collection_no; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.collection_record
    ADD CONSTRAINT uq_collection_record_chain_collection_no UNIQUE (chain, collection_no);


--
-- Name: withdrawal_order uq_withdrawal_order_chain_order_no; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.withdrawal_order
    ADD CONSTRAINT uq_withdrawal_order_chain_order_no UNIQUE (chain, order_no);


--
-- Name: utxo_record utxo_record_chain_tx_hash_vout_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.utxo_record
    ADD CONSTRAINT utxo_record_chain_tx_hash_vout_key UNIQUE (chain, tx_hash, vout);


--
-- Name: utxo_record utxo_record_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.utxo_record
    ADD CONSTRAINT utxo_record_pkey PRIMARY KEY (id);


--
-- Name: wallet_public_key wallet_public_key_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.wallet_public_key
    ADD CONSTRAINT wallet_public_key_pkey PRIMARY KEY (key_slot);


--
-- Name: wallet_system_config wallet_system_config_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.wallet_system_config
    ADD CONSTRAINT wallet_system_config_pkey PRIMARY KEY (config_key);


--
-- Name: wallet_transfer_order wallet_transfer_order_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.wallet_transfer_order
    ADD CONSTRAINT wallet_transfer_order_pkey PRIMARY KEY (id);


--
-- Name: wallet_transfer_order wallet_transfer_order_transfer_no_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.wallet_transfer_order
    ADD CONSTRAINT wallet_transfer_order_transfer_no_key UNIQUE (transfer_no);


--
-- Name: wallet_user wallet_user_email_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.wallet_user
    ADD CONSTRAINT wallet_user_email_key UNIQUE (email);


--
-- Name: wallet_user wallet_user_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.wallet_user
    ADD CONSTRAINT wallet_user_pkey PRIMARY KEY (id);


--
-- Name: wallet_user_session wallet_user_session_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.wallet_user_session
    ADD CONSTRAINT wallet_user_session_pkey PRIMARY KEY (id);


--
-- Name: wallet_user_session wallet_user_session_token_hash_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.wallet_user_session
    ADD CONSTRAINT wallet_user_session_token_hash_key UNIQUE (token_hash);


--
-- Name: withdrawal_order withdrawal_order_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.withdrawal_order
    ADD CONSTRAINT withdrawal_order_pkey PRIMARY KEY (id);


--
-- Name: withdrawal_order withdrawal_order_tenant_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.withdrawal_order
    ADD CONSTRAINT withdrawal_order_tenant_id_key UNIQUE (tenant_id, id);


--
-- Name: withdrawal_review_audit withdrawal_review_audit_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.withdrawal_review_audit
    ADD CONSTRAINT withdrawal_review_audit_pkey PRIMARY KEY (review_id);


--
-- Name: xrp_transaction xrp_transaction_chain_tx_hash_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.xrp_transaction
    ADD CONSTRAINT xrp_transaction_chain_tx_hash_key UNIQUE (chain, tx_hash);


--
-- Name: xrp_transaction xrp_transaction_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.xrp_transaction
    ADD CONSTRAINT xrp_transaction_pkey PRIMARY KEY (id);


--
-- Name: chain_profile_one_enabled_network_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX chain_profile_one_enabled_network_idx ON public.chain_profile USING btree (upper((chain)::text)) WHERE (enabled = true);


--
-- Name: custody_address_derivation_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX custody_address_derivation_key ON public.custody_address USING btree (tenant_id, chain, derivation_subject, derivation_child);


--
-- Name: custody_address_tenant_chain_subject_version_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX custody_address_tenant_chain_subject_version_key ON public.custody_address USING btree (tenant_id, chain, subject, address_version);


--
-- Name: custody_address_lookup_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX custody_address_lookup_idx ON public.custody_address USING btree (chain, lower((address)::text), status);


--
-- Name: custody_address_tenant_created_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX custody_address_tenant_created_idx ON public.custody_address USING btree (tenant_id, created_at DESC);


--
-- Name: custody_address_tenant_reference_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX custody_address_tenant_reference_idx ON public.custody_address USING btree (tenant_id, external_reference);


--
-- Name: custody_address_tenant_subject_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX custody_address_tenant_subject_idx ON public.custody_address USING btree (tenant_id, subject);


--
-- Name: custody_api_key_tenant_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX custody_api_key_tenant_idx ON public.custody_api_key USING btree (tenant_id, created_at DESC);


--
-- Name: custody_api_nonce_expiry_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX custody_api_nonce_expiry_idx ON public.custody_api_nonce USING btree (expires_at);


--
-- Name: custody_audit_log_tenant_time_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX custody_audit_log_tenant_time_idx ON public.custody_audit_log USING btree (tenant_id, created_at DESC);


--
-- Name: custody_deposit_tenant_time_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX custody_deposit_tenant_time_idx ON public.custody_deposit USING btree (tenant_id, created_at DESC);


--
-- Name: custody_dev_faucet_funding_due_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX custody_dev_faucet_funding_due_idx ON public.custody_dev_faucet_funding USING btree (status, next_attempt_at) WHERE ((status)::text = ANY ((ARRAY['PENDING'::character varying, 'FAILED'::character varying])::text[]));


--
-- Name: custody_dev_faucet_funding_tenant_time_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX custody_dev_faucet_funding_tenant_time_idx ON public.custody_dev_faucet_funding USING btree (tenant_id, created_at DESC);


--
-- Name: custody_event_pending_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX custody_event_pending_idx ON public.custody_event USING btree (status, created_at) WHERE ((status)::text = 'PENDING'::text);


--
-- Name: custody_event_tenant_time_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX custody_event_tenant_time_idx ON public.custody_event USING btree (tenant_id, occurred_at DESC);


--
-- Name: custody_gas_account_tenant_status_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX custody_gas_account_tenant_status_idx ON public.custody_gas_account USING btree (tenant_id, status);


--
-- Name: custody_gas_usage_account_time_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX custody_gas_usage_account_time_idx ON public.custody_gas_usage USING btree (gas_account_id, created_at DESC);


--
-- Name: custody_gas_usage_pending_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX custody_gas_usage_pending_idx ON public.custody_gas_usage USING btree (status, updated_at) WHERE ((status)::text = ANY ((ARRAY['RESERVED'::character varying, 'OVERDUE'::character varying])::text[]));


--
-- Name: custody_idempotency_expiry_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX custody_idempotency_expiry_idx ON public.custody_idempotency_key USING btree (expires_at);


--
-- Name: custody_ip_rule_enabled_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX custody_ip_rule_enabled_idx ON public.custody_ip_rule USING btree (tenant_id, enabled);


--
-- Name: custody_ledger_entry_tenant_asset_time_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX custody_ledger_entry_tenant_asset_time_idx ON public.custody_ledger_entry USING btree (tenant_id, chain, asset_symbol, created_at DESC);


--
-- Name: custody_session_active_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX custody_session_active_idx ON public.custody_session USING btree (token_hash, expires_at) WHERE (revoked_at IS NULL);


--
-- Name: custody_tenant_chain_status_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX custody_tenant_chain_status_idx ON public.custody_tenant_chain USING btree (tenant_id, status, chain);


--
-- Name: custody_tenant_user_email_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX custody_tenant_user_email_key ON public.custody_tenant_user USING btree (lower((email)::text));


--
-- Name: custody_webhook_attempt_delivery_time_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX custody_webhook_attempt_delivery_time_idx ON public.custody_webhook_delivery_attempt USING btree (delivery_id, attempt_number DESC);


--
-- Name: custody_webhook_attempt_tenant_time_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX custody_webhook_attempt_tenant_time_idx ON public.custody_webhook_delivery_attempt USING btree (tenant_id, started_at DESC);


--
-- Name: custody_webhook_delivery_due_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX custody_webhook_delivery_due_idx ON public.custody_webhook_delivery USING btree (status, next_attempt_at) WHERE ((status)::text = ANY ((ARRAY['PENDING'::character varying, 'RETRY'::character varying])::text[]));


--
-- Name: custody_webhook_delivery_tenant_time_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX custody_webhook_delivery_tenant_time_idx ON public.custody_webhook_delivery USING btree (tenant_id, created_at DESC);


--
-- Name: custody_webhook_endpoint_active_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX custody_webhook_endpoint_active_idx ON public.custody_webhook_endpoint USING btree (tenant_id, status);


--
-- Name: custody_withdrawal_idempotency_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX custody_withdrawal_idempotency_key ON public.custody_withdrawal USING btree (tenant_id, idempotency_key) WHERE (idempotency_key IS NOT NULL);
CREATE UNIQUE INDEX custody_withdrawal_external_reference_key ON public.custody_withdrawal (tenant_id, external_reference) WHERE external_reference IS NOT NULL;


--
-- Name: custody_withdrawal_tenant_time_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX custody_withdrawal_tenant_time_idx ON public.custody_withdrawal USING btree (tenant_id, created_at DESC);


--
-- Name: idx_chain_address_owner; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chain_address_owner ON public.chain_address USING btree (chain, owner_address);


--
-- Name: idx_chain_address_scan; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chain_address_scan ON public.chain_address USING btree (chain, asset_symbol, enabled);


CREATE INDEX idx_chain_address_collection_candidates ON public.chain_address USING btree (chain) WHERE ((enabled = true) AND (tenant_id IS NOT NULL) AND ((wallet_role)::text = 'DEPOSIT'::text) AND (user_id <> 0));


CREATE INDEX idx_collection_record_collection_balance ON public.collection_record USING btree (chain, tenant_id, asset_symbol, lower((from_address)::text)) INCLUDE (amount, status) WHERE (tenant_id IS NOT NULL);


CREATE INDEX idx_custody_address_collection_candidates ON public.custody_address USING btree (chain) WHERE ((status)::text = 'ACTIVE'::text);


CREATE INDEX idx_deposit_record_collection_balance ON public.deposit_record USING btree (chain, tenant_id, asset_symbol, lower((to_address)::text)) INCLUDE (amount) WHERE ((tenant_id IS NOT NULL) AND (credited = true));


--
-- Name: idx_chain_signing_transaction_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chain_signing_transaction_status ON public.chain_signing_transaction USING btree (chain, status, update_date);


--
-- Name: idx_chain_signing_transaction_tx_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chain_signing_transaction_tx_id ON public.chain_signing_transaction USING btree (chain, tx_id);


--
-- Name: idx_contract_deployment_order_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_contract_deployment_order_user ON public.contract_deployment_order USING btree (user_id, status, updated_at);


--
-- Name: idx_contract_deployment_order_user_recent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_contract_deployment_order_user_recent ON public.contract_deployment_order USING btree (user_id, id DESC);


--
-- Name: idx_hypercore_token_metadata_name; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_hypercore_token_metadata_name ON public.hypercore_token_metadata USING btree (network, upper((name)::text), is_canonical DESC, token_index);


--
-- Name: idx_utxo_record_address; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_utxo_record_address ON public.utxo_record USING btree (chain, address);


--
-- Name: idx_utxo_record_spendable; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_utxo_record_spendable ON public.utxo_record USING btree (chain, asset_symbol, state, confirmations);


--
-- Name: idx_wallet_transfer_order_from_recent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_wallet_transfer_order_from_recent ON public.wallet_transfer_order USING btree (from_user_id, id DESC);


--
-- Name: idx_wallet_transfer_order_to_recent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_wallet_transfer_order_to_recent ON public.wallet_transfer_order USING btree (to_user_id, id DESC);


--
-- Name: idx_wallet_transfer_order_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_wallet_transfer_order_user ON public.wallet_transfer_order USING btree (from_user_id, to_user_id, updated_at);


--
-- Name: idx_wallet_user_session_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_wallet_user_session_user ON public.wallet_user_session USING btree (user_id, expires_at);


--
-- Name: token_config_chain_network_contract_address_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX token_config_chain_network_contract_address_key ON public.token_config USING btree (chain, network, contract_address);


--
-- Name: token_config_chain_network_symbol_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX token_config_chain_network_symbol_key ON public.token_config USING btree (chain, network, symbol);


--
-- Name: token_config_one_enabled_network_per_asset_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX token_config_one_enabled_network_per_asset_idx ON public.token_config USING btree (upper((chain)::text), upper((symbol)::text)) WHERE (enabled = true);


--
-- Name: withdrawal_review_audit_admin_time_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX withdrawal_review_audit_admin_time_idx ON public.withdrawal_review_audit USING btree (admin_user_id, created_at DESC);


--
-- Name: withdrawal_review_audit_order_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX withdrawal_review_audit_order_idx ON public.withdrawal_review_audit USING btree (chain, order_no, created_at DESC);


--
-- Name: withdrawal_review_audit_user_time_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX withdrawal_review_audit_user_time_idx ON public.withdrawal_review_audit USING btree (user_id, created_at DESC);


--
-- Name: chain_address chain_address_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chain_address
    ADD CONSTRAINT chain_address_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.custody_tenant(id) ON DELETE RESTRICT;


--
-- Name: custody_address custody_address_chain_address_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_address
    ADD CONSTRAINT custody_address_chain_address_id_fkey FOREIGN KEY (chain_address_id) REFERENCES public.chain_address(id) ON DELETE RESTRICT;


--
-- Name: custody_address custody_address_chain_address_tenant_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_address
    ADD CONSTRAINT custody_address_chain_address_tenant_fk FOREIGN KEY (tenant_id, chain_address_id) REFERENCES public.chain_address(tenant_id, id) ON DELETE RESTRICT;


--
-- Name: custody_address custody_address_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_address
    ADD CONSTRAINT custody_address_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.custody_tenant_user(id);


--
-- Name: custody_address custody_address_creator_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_address
    ADD CONSTRAINT custody_address_creator_fk FOREIGN KEY (tenant_id, created_by) REFERENCES public.custody_tenant_user(tenant_id, id) ON DELETE RESTRICT;


--
-- Name: custody_address custody_address_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_address
    ADD CONSTRAINT custody_address_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.custody_tenant(id) ON DELETE RESTRICT;


--
-- Name: custody_api_key custody_api_key_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_api_key
    ADD CONSTRAINT custody_api_key_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.custody_tenant_user(id);


--
-- Name: custody_api_key custody_api_key_creator_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_api_key
    ADD CONSTRAINT custody_api_key_creator_fk FOREIGN KEY (tenant_id, created_by) REFERENCES public.custody_tenant_user(tenant_id, id) ON DELETE RESTRICT;


--
-- Name: custody_api_key custody_api_key_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_api_key
    ADD CONSTRAINT custody_api_key_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.custody_tenant(id) ON DELETE CASCADE;


--
-- Name: custody_api_nonce custody_api_nonce_key_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_api_nonce
    ADD CONSTRAINT custody_api_nonce_key_id_fkey FOREIGN KEY (key_id) REFERENCES public.custody_api_key(key_id) ON DELETE CASCADE;


--
-- Name: custody_audit_log custody_audit_log_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_audit_log
    ADD CONSTRAINT custody_audit_log_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.custody_tenant(id) ON DELETE RESTRICT;


--
-- Name: custody_deposit custody_deposit_address_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_deposit
    ADD CONSTRAINT custody_deposit_address_fk FOREIGN KEY (tenant_id, custody_address_id) REFERENCES public.custody_address(tenant_id, id) ON DELETE RESTRICT;


--
-- Name: custody_deposit custody_deposit_custody_address_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_deposit
    ADD CONSTRAINT custody_deposit_custody_address_id_fkey FOREIGN KEY (custody_address_id) REFERENCES public.custody_address(id) ON DELETE RESTRICT;


--
-- Name: custody_deposit custody_deposit_deposit_record_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_deposit
    ADD CONSTRAINT custody_deposit_deposit_record_id_fkey FOREIGN KEY (deposit_record_id) REFERENCES public.deposit_record(id) ON DELETE RESTRICT;


--
-- Name: custody_deposit custody_deposit_record_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_deposit
    ADD CONSTRAINT custody_deposit_record_fk FOREIGN KEY (tenant_id, deposit_record_id) REFERENCES public.deposit_record(tenant_id, id) ON DELETE RESTRICT;


--
-- Name: custody_deposit custody_deposit_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_deposit
    ADD CONSTRAINT custody_deposit_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.custody_tenant(id) ON DELETE RESTRICT;


--
-- Name: custody_dev_faucet_funding custody_dev_faucet_funding_address_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_dev_faucet_funding
    ADD CONSTRAINT custody_dev_faucet_funding_address_fk FOREIGN KEY (tenant_id, custody_address_id) REFERENCES public.custody_address(tenant_id, id) ON DELETE RESTRICT;


--
-- Name: custody_dev_faucet_funding custody_dev_faucet_funding_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_dev_faucet_funding
    ADD CONSTRAINT custody_dev_faucet_funding_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.custody_tenant(id) ON DELETE RESTRICT;


--
-- Name: custody_derivation_subject custody_derivation_subject_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_derivation_subject
    ADD CONSTRAINT custody_derivation_subject_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.custody_tenant(id) ON DELETE RESTRICT;


--
-- Name: custody_event custody_event_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_event
    ADD CONSTRAINT custody_event_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.custody_tenant(id) ON DELETE RESTRICT;


--
-- Name: custody_gas_account custody_gas_account_address_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_gas_account
    ADD CONSTRAINT custody_gas_account_address_fk FOREIGN KEY (tenant_id, custody_address_id) REFERENCES public.custody_address(tenant_id, id) ON DELETE RESTRICT;


--
-- Name: custody_gas_account custody_gas_account_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_gas_account
    ADD CONSTRAINT custody_gas_account_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.custody_tenant_user(id);


--
-- Name: custody_gas_account custody_gas_account_creator_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_gas_account
    ADD CONSTRAINT custody_gas_account_creator_fk FOREIGN KEY (tenant_id, created_by) REFERENCES public.custody_tenant_user(tenant_id, id) ON DELETE RESTRICT;


--
-- Name: custody_gas_account custody_gas_account_custody_address_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_gas_account
    ADD CONSTRAINT custody_gas_account_custody_address_id_fkey FOREIGN KEY (custody_address_id) REFERENCES public.custody_address(id) ON DELETE RESTRICT;


--
-- Name: custody_gas_account custody_gas_account_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_gas_account
    ADD CONSTRAINT custody_gas_account_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.custody_tenant(id) ON DELETE RESTRICT;


--
-- Name: custody_gas_usage custody_gas_usage_account_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_gas_usage
    ADD CONSTRAINT custody_gas_usage_account_fk FOREIGN KEY (tenant_id, gas_account_id) REFERENCES public.custody_gas_account(tenant_id, id) ON DELETE RESTRICT;


--
-- Name: custody_gas_usage custody_gas_usage_gas_account_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_gas_usage
    ADD CONSTRAINT custody_gas_usage_gas_account_id_fkey FOREIGN KEY (gas_account_id) REFERENCES public.custody_gas_account(id) ON DELETE RESTRICT;


--
-- Name: custody_gas_usage custody_gas_usage_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_gas_usage
    ADD CONSTRAINT custody_gas_usage_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.custody_tenant(id) ON DELETE RESTRICT;


--
-- Name: custody_idempotency_key custody_idempotency_key_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_idempotency_key
    ADD CONSTRAINT custody_idempotency_key_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.custody_tenant(id) ON DELETE CASCADE;


--
-- Name: custody_ip_rule custody_ip_rule_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_ip_rule
    ADD CONSTRAINT custody_ip_rule_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.custody_tenant_user(id);


--
-- Name: custody_ip_rule custody_ip_rule_creator_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_ip_rule
    ADD CONSTRAINT custody_ip_rule_creator_fk FOREIGN KEY (tenant_id, created_by) REFERENCES public.custody_tenant_user(tenant_id, id) ON DELETE RESTRICT;


--
-- Name: custody_ip_rule custody_ip_rule_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_ip_rule
    ADD CONSTRAINT custody_ip_rule_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.custody_tenant(id) ON DELETE CASCADE;


--
-- Name: custody_ledger_entry custody_ledger_entry_address_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_ledger_entry
    ADD CONSTRAINT custody_ledger_entry_address_fk FOREIGN KEY (tenant_id, custody_address_id) REFERENCES public.custody_address(tenant_id, id) ON DELETE RESTRICT;


--
-- Name: custody_ledger_entry custody_ledger_entry_custody_address_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_ledger_entry
    ADD CONSTRAINT custody_ledger_entry_custody_address_id_fkey FOREIGN KEY (custody_address_id) REFERENCES public.custody_address(id) ON DELETE RESTRICT;


--
-- Name: custody_ledger_entry custody_ledger_entry_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_ledger_entry
    ADD CONSTRAINT custody_ledger_entry_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.custody_tenant(id) ON DELETE RESTRICT;


--
-- Name: custody_session custody_session_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_session
    ADD CONSTRAINT custody_session_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.custody_tenant(id) ON DELETE CASCADE;


--
-- Name: custody_session custody_session_tenant_user_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_session
    ADD CONSTRAINT custody_session_tenant_user_fk FOREIGN KEY (tenant_id, tenant_user_id) REFERENCES public.custody_tenant_user(tenant_id, id) ON DELETE CASCADE;


--
-- Name: custody_session custody_session_tenant_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_session
    ADD CONSTRAINT custody_session_tenant_user_id_fkey FOREIGN KEY (tenant_user_id) REFERENCES public.custody_tenant_user(id) ON DELETE CASCADE;


--
-- Name: custody_tenant_chain custody_tenant_chain_closed_by_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_tenant_chain
    ADD CONSTRAINT custody_tenant_chain_closed_by_fk FOREIGN KEY (tenant_id, closed_by) REFERENCES public.custody_tenant_user(tenant_id, id) ON DELETE RESTRICT;


--
-- Name: custody_tenant_chain custody_tenant_chain_opened_by_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_tenant_chain
    ADD CONSTRAINT custody_tenant_chain_opened_by_fk FOREIGN KEY (tenant_id, opened_by) REFERENCES public.custody_tenant_user(tenant_id, id) ON DELETE RESTRICT;


--
-- Name: custody_tenant_chain custody_tenant_chain_tenant_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_tenant_chain
    ADD CONSTRAINT custody_tenant_chain_tenant_fk FOREIGN KEY (tenant_id) REFERENCES public.custody_tenant(id) ON DELETE CASCADE;


--
-- Name: custody_tenant_user custody_tenant_user_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_tenant_user
    ADD CONSTRAINT custody_tenant_user_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.custody_tenant(id);


--
-- Name: custody_webhook_delivery_attempt custody_webhook_attempt_delivery_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_webhook_delivery_attempt
    ADD CONSTRAINT custody_webhook_attempt_delivery_fk FOREIGN KEY (tenant_id, delivery_id) REFERENCES public.custody_webhook_delivery(tenant_id, id) ON DELETE RESTRICT;


--
-- Name: custody_webhook_delivery_attempt custody_webhook_delivery_attempt_delivery_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_webhook_delivery_attempt
    ADD CONSTRAINT custody_webhook_delivery_attempt_delivery_id_fkey FOREIGN KEY (delivery_id) REFERENCES public.custody_webhook_delivery(id) ON DELETE CASCADE;


--
-- Name: custody_webhook_delivery_attempt custody_webhook_delivery_attempt_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_webhook_delivery_attempt
    ADD CONSTRAINT custody_webhook_delivery_attempt_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.custody_tenant(id) ON DELETE RESTRICT;


--
-- Name: custody_webhook_delivery custody_webhook_delivery_endpoint_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_webhook_delivery
    ADD CONSTRAINT custody_webhook_delivery_endpoint_fk FOREIGN KEY (tenant_id, endpoint_id) REFERENCES public.custody_webhook_endpoint(tenant_id, id) ON DELETE RESTRICT;


--
-- Name: custody_webhook_delivery custody_webhook_delivery_endpoint_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_webhook_delivery
    ADD CONSTRAINT custody_webhook_delivery_endpoint_id_fkey FOREIGN KEY (endpoint_id) REFERENCES public.custody_webhook_endpoint(id) ON DELETE CASCADE;


--
-- Name: custody_webhook_delivery custody_webhook_delivery_event_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_webhook_delivery
    ADD CONSTRAINT custody_webhook_delivery_event_fk FOREIGN KEY (tenant_id, event_id) REFERENCES public.custody_event(tenant_id, id) ON DELETE RESTRICT;


--
-- Name: custody_webhook_delivery custody_webhook_delivery_event_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_webhook_delivery
    ADD CONSTRAINT custody_webhook_delivery_event_id_fkey FOREIGN KEY (event_id) REFERENCES public.custody_event(id) ON DELETE CASCADE;


--
-- Name: custody_webhook_delivery custody_webhook_delivery_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_webhook_delivery
    ADD CONSTRAINT custody_webhook_delivery_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.custody_tenant(id) ON DELETE RESTRICT;


--
-- Name: custody_webhook_endpoint custody_webhook_endpoint_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_webhook_endpoint
    ADD CONSTRAINT custody_webhook_endpoint_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.custody_tenant_user(id);


--
-- Name: custody_webhook_endpoint custody_webhook_endpoint_creator_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_webhook_endpoint
    ADD CONSTRAINT custody_webhook_endpoint_creator_fk FOREIGN KEY (tenant_id, created_by) REFERENCES public.custody_tenant_user(tenant_id, id) ON DELETE RESTRICT;


--
-- Name: custody_webhook_endpoint custody_webhook_endpoint_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_webhook_endpoint
    ADD CONSTRAINT custody_webhook_endpoint_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.custody_tenant(id) ON DELETE CASCADE;


--
-- Name: custody_withdrawal custody_withdrawal_address_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_withdrawal
    ADD CONSTRAINT custody_withdrawal_address_fk FOREIGN KEY (tenant_id, custody_address_id) REFERENCES public.custody_address(tenant_id, id) ON DELETE RESTRICT;


--
-- Name: custody_withdrawal custody_withdrawal_custody_address_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_withdrawal
    ADD CONSTRAINT custody_withdrawal_custody_address_id_fkey FOREIGN KEY (custody_address_id) REFERENCES public.custody_address(id) ON DELETE RESTRICT;


--
-- Name: custody_withdrawal custody_withdrawal_order_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_withdrawal
    ADD CONSTRAINT custody_withdrawal_order_fk FOREIGN KEY (tenant_id, withdrawal_order_id) REFERENCES public.withdrawal_order(tenant_id, id) ON DELETE RESTRICT;


--
-- Name: custody_withdrawal custody_withdrawal_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_withdrawal
    ADD CONSTRAINT custody_withdrawal_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.custody_tenant(id) ON DELETE RESTRICT;


--
-- Name: deposit_record deposit_record_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.deposit_record
    ADD CONSTRAINT deposit_record_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.custody_tenant(id) ON DELETE RESTRICT;


--
-- Name: ledger_balance ledger_balance_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ledger_balance
    ADD CONSTRAINT ledger_balance_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.custody_tenant(id) ON DELETE RESTRICT;


--
-- Name: withdrawal_order withdrawal_order_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.withdrawal_order
    ADD CONSTRAINT withdrawal_order_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.custody_tenant(id) ON DELETE RESTRICT;


--
-- EIP-7702 is opt-in per concrete chain/network/version. No network is seeded ACTIVE.
CREATE TABLE public.evm_7702_config (
    id uuid NOT NULL PRIMARY KEY,
    chain character varying(32) NOT NULL,
    network character varying(64) NOT NULL,
    chain_id numeric(78,0) NOT NULL,
    version integer NOT NULL,
    delegate_address character varying(42) NOT NULL,
    delegate_code_hash character varying(66) NOT NULL,
    collector_address character varying(42) NOT NULL,
    collector_code_hash character varying(66) NOT NULL,
    payout_delegate_address character varying(42) NOT NULL,
    payout_delegate_code_hash character varying(66) NOT NULL,
    relayer_chain_address_id bigint NOT NULL,
    relayer_address character varying(42) NOT NULL,
    status character varying(24) DEFAULT 'DISABLED'::character varying NOT NULL,
    max_batch_items integer DEFAULT 20 NOT NULL,
    max_batch_gas bigint DEFAULT 5000000 NOT NULL,
    block_gas_ratio numeric(5,4) DEFAULT 0.3000 NOT NULL,
    gas_limit_multiplier numeric(5,4) DEFAULT 1.2000 NOT NULL,
    signature_ttl_seconds integer DEFAULT 900 NOT NULL,
    required_confirmations integer DEFAULT 2 NOT NULL,
    native_collection_enabled boolean DEFAULT false NOT NULL,
    batch_withdrawal_enabled boolean DEFAULT false NOT NULL,
    withdrawal_max_wait_ms integer DEFAULT 3000 NOT NULL,
    withdrawal_max_batch_items integer DEFAULT 20 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT evm_7702_config_status_check CHECK ((status)::text = ANY ((ARRAY[
        'DISABLED'::character varying, 'SHADOW'::character varying,
        'ACTIVE'::character varying, 'PAUSED'::character varying])::text[])),
    CONSTRAINT evm_7702_config_chain_id_check CHECK (chain_id > 0),
    CONSTRAINT evm_7702_config_version_check CHECK (version > 0),
    CONSTRAINT evm_7702_config_batch_check CHECK (max_batch_items BETWEEN 1 AND 100),
    CONSTRAINT evm_7702_config_gas_check CHECK (max_batch_gas > 0),
    CONSTRAINT evm_7702_config_block_ratio_check CHECK (block_gas_ratio > 0 AND block_gas_ratio <= 0.5),
    CONSTRAINT evm_7702_config_multiplier_check CHECK (gas_limit_multiplier >= 1.0 AND gas_limit_multiplier <= 2.0),
    CONSTRAINT evm_7702_config_ttl_check CHECK (signature_ttl_seconds BETWEEN 30 AND 1800),
    CONSTRAINT evm_7702_config_confirmations_check CHECK (required_confirmations > 0),
    CONSTRAINT evm_7702_config_withdraw_wait_check CHECK (withdrawal_max_wait_ms BETWEEN 0 AND 60000),
    CONSTRAINT evm_7702_config_withdraw_batch_check CHECK (withdrawal_max_batch_items BETWEEN 1 AND 100),
    CONSTRAINT evm_7702_config_network_version_key UNIQUE (chain, network, version),
    CONSTRAINT evm_7702_config_relayer_fk FOREIGN KEY (relayer_chain_address_id)
        REFERENCES public.chain_address(id) ON DELETE RESTRICT
);

CREATE UNIQUE INDEX evm_7702_config_one_active_version
    ON public.evm_7702_config USING btree (chain, network)
    WHERE status = 'ACTIVE';

CREATE UNIQUE INDEX evm_7702_config_one_shadow_version
    ON public.evm_7702_config USING btree (chain, network)
    WHERE status = 'SHADOW';

CREATE TABLE public.evm_7702_account (
    id uuid NOT NULL PRIMARY KEY,
    tenant_id uuid NOT NULL,
    custody_address_id uuid NOT NULL,
    chain character varying(32) NOT NULL,
    network character varying(64) NOT NULL,
    authority_address character varying(42) NOT NULL,
    delegate_address character varying(42),
    delegate_version integer,
    delegation_status character varying(24) DEFAULT 'NOT_DELEGATED'::character varying NOT NULL,
    observed_authority_nonce numeric(78,0),
    observed_operation_nonce numeric(78,0),
    activation_tx_hash character varying(128),
    revocation_tx_hash character varying(128),
    last_code_hash character varying(66),
    last_observed_block bigint,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT evm_7702_account_tenant_authority_key UNIQUE (tenant_id, chain, authority_address),
    CONSTRAINT evm_7702_account_tenant_custody_key UNIQUE (tenant_id, custody_address_id, chain),
    CONSTRAINT evm_7702_account_status_check CHECK ((delegation_status)::text = ANY ((ARRAY[
        'NOT_DELEGATED'::character varying, 'DELEGATING'::character varying,
        'ACTIVE'::character varying, 'REVOKING'::character varying,
        'REVOKED'::character varying, 'UNKNOWN'::character varying,
        'MANUAL_REVIEW'::character varying])::text[])),
    CONSTRAINT evm_7702_account_tenant_fk FOREIGN KEY (tenant_id)
        REFERENCES public.custody_tenant(id) ON DELETE RESTRICT,
    CONSTRAINT evm_7702_account_custody_fk FOREIGN KEY (tenant_id, custody_address_id)
        REFERENCES public.custody_address(tenant_id, id) ON DELETE RESTRICT
);

CREATE TABLE public.evm_7702_payout_account (
    id uuid NOT NULL PRIMARY KEY,
    tenant_id uuid NOT NULL,
    chain character varying(32) NOT NULL,
    network character varying(64) NOT NULL,
    chain_address_id bigint NOT NULL,
    authority_address character varying(42) NOT NULL,
    delegate_address character varying(42),
    delegate_version integer,
    delegation_status character varying(24) DEFAULT 'NOT_DELEGATED'::character varying NOT NULL,
    observed_authority_nonce numeric(78,0),
    observed_operation_nonce numeric(78,0),
    activation_tx_hash character varying(128),
    revocation_tx_hash character varying(128),
    last_code_hash character varying(66),
    last_observed_block bigint,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT evm_7702_payout_account_tenant_chain_key UNIQUE (tenant_id, chain),
    CONSTRAINT evm_7702_payout_account_tenant_authority_key UNIQUE (tenant_id, chain, authority_address),
    CONSTRAINT evm_7702_payout_account_status_check CHECK ((delegation_status)::text = ANY ((ARRAY[
        'NOT_DELEGATED'::character varying, 'DELEGATING'::character varying,
        'ACTIVE'::character varying, 'REVOKING'::character varying,
        'REVOKED'::character varying, 'UNKNOWN'::character varying,
        'MANUAL_REVIEW'::character varying])::text[])),
    CONSTRAINT evm_7702_payout_account_tenant_fk FOREIGN KEY (tenant_id)
        REFERENCES public.custody_tenant(id) ON DELETE RESTRICT,
    CONSTRAINT evm_7702_payout_account_address_fk FOREIGN KEY (tenant_id, chain_address_id)
        REFERENCES public.chain_address(tenant_id, id) ON DELETE RESTRICT
);

CREATE TABLE public.evm_collection_batch (
    id uuid NOT NULL PRIMARY KEY,
    tenant_id uuid NOT NULL,
    chain character varying(32) NOT NULL,
    network character varying(64) NOT NULL,
    asset_symbol character varying(32) NOT NULL,
    token_contract character varying(42) NOT NULL,
    token_decimals integer NOT NULL,
    hot_wallet character varying(42) NOT NULL,
    relayer_address character varying(42) NOT NULL,
    delegate_version integer NOT NULL,
    batch_hash character varying(66) NOT NULL,
    claim_payload jsonb NOT NULL,
    status character varying(32) NOT NULL,
    item_count integer NOT NULL,
    estimated_gas bigint,
    gas_limit bigint,
    max_fee_per_gas numeric(78,0),
    max_priority_fee_per_gas numeric(78,0),
    canonical_tx_hash character varying(128),
    actual_gas_used bigint,
    effective_gas_price numeric(78,0),
    l2_fee_atomic numeric(78,0),
    l1_fee_atomic numeric(78,0),
    operator_fee_atomic numeric(78,0),
    total_fee_atomic numeric(78,0),
    actual_fee numeric(78,24),
    confirmed_block_number bigint,
    confirmed_block_hash character varying(128),
    error_code character varying(64),
    error_message text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    submitted_at timestamp with time zone,
    confirmed_at timestamp with time zone,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT evm_collection_batch_tenant_id_key UNIQUE (tenant_id, id),
    CONSTRAINT evm_collection_batch_hash_key UNIQUE (chain, batch_hash),
    CONSTRAINT evm_collection_batch_item_count_check CHECK (item_count BETWEEN 1 AND 100),
    CONSTRAINT evm_collection_batch_status_check CHECK ((status)::text = ANY ((ARRAY[
        'CREATED'::character varying, 'LOCKED'::character varying,
        'SIMULATED'::character varying, 'SIGNING'::character varying,
        'SUBMITTED'::character varying, 'BROADCAST_UNKNOWN'::character varying,
        'CONFIRMING'::character varying, 'CONFIRMED'::character varying,
        'PARTIAL_FAILED'::character varying, 'FAILED'::character varying,
        'REORGED'::character varying, 'MANUAL_REVIEW'::character varying])::text[])),
    CONSTRAINT evm_collection_batch_tenant_fk FOREIGN KEY (tenant_id)
        REFERENCES public.custody_tenant(id) ON DELETE RESTRICT,
    CONSTRAINT evm_collection_batch_claim_check CHECK (
        jsonb_typeof(claim_payload) = 'object'
        AND claim_payload ?& ARRAY['id','tenantId','chain','network','batchHash']
        AND (claim_payload->>'id') IS NOT DISTINCT FROM id::text
        AND (claim_payload->>'tenantId') IS NOT DISTINCT FROM tenant_id::text
        AND (claim_payload->>'chain') IS NOT DISTINCT FROM chain
        AND (claim_payload->>'network') IS NOT DISTINCT FROM network
        AND (claim_payload->>'batchHash') IS NOT DISTINCT FROM batch_hash)
);

CREATE UNIQUE INDEX evm_collection_batch_tx_key
    ON public.evm_collection_batch USING btree (chain, canonical_tx_hash)
    WHERE canonical_tx_hash IS NOT NULL;

CREATE INDEX evm_collection_batch_work_idx
    ON public.evm_collection_batch USING btree (chain, network, status, updated_at);

CREATE TABLE public.evm_collection_batch_item (
    id uuid NOT NULL PRIMARY KEY,
    tenant_id uuid NOT NULL,
    batch_id uuid NOT NULL,
    item_index integer NOT NULL,
    collection_record_id bigint NOT NULL,
    custody_address_id uuid NOT NULL,
    authority_address character varying(42) NOT NULL,
    token_contract character varying(42) NOT NULL,
    recipient character varying(42) NOT NULL,
    requested_amount_atomic numeric(78,0) NOT NULL,
    actual_received_atomic numeric(78,0),
    authorization_included boolean DEFAULT false NOT NULL,
    authorization_nonce numeric(78,0),
    operation_nonce numeric(78,0) NOT NULL,
    signature_deadline timestamp with time zone NOT NULL,
    call_gas_limit bigint NOT NULL,
    status character varying(32) NOT NULL,
    log_index integer,
    error_code character varying(64),
    error_hash character varying(66),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT evm_collection_batch_item_position_key UNIQUE (batch_id, item_index),
    CONSTRAINT evm_collection_batch_item_authority_key UNIQUE (batch_id, authority_address),
    CONSTRAINT evm_collection_batch_item_amount_check CHECK (requested_amount_atomic > 0),
    CONSTRAINT evm_collection_batch_item_received_check CHECK (actual_received_atomic IS NULL OR actual_received_atomic >= 0),
    CONSTRAINT evm_collection_batch_item_status_check CHECK ((status)::text = ANY ((ARRAY[
        'CREATED'::character varying, 'SIGNED'::character varying,
        'SUBMITTED'::character varying, 'CONFIRMED'::character varying,
        'FAILED'::character varying, 'RETRYABLE'::character varying,
        'REORGED'::character varying, 'MANUAL_REVIEW'::character varying])::text[])),
    CONSTRAINT evm_collection_batch_item_batch_fk FOREIGN KEY (tenant_id, batch_id)
        REFERENCES public.evm_collection_batch(tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT evm_collection_batch_item_custody_fk FOREIGN KEY (tenant_id, custody_address_id)
        REFERENCES public.custody_address(tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT evm_collection_batch_item_collection_fk FOREIGN KEY (tenant_id, collection_record_id)
        REFERENCES public.collection_record(tenant_id, id) ON DELETE RESTRICT
);

CREATE UNIQUE INDEX evm_collection_batch_item_active_collection_key
    ON public.evm_collection_batch_item USING btree (collection_record_id)
    WHERE status IN ('CREATED', 'SIGNED', 'SUBMITTED');

CREATE TABLE public.evm_collection_batch_attempt (
    id uuid NOT NULL PRIMARY KEY,
    tenant_id uuid NOT NULL,
    batch_id uuid NOT NULL,
    attempt_no integer NOT NULL,
    relayer_nonce numeric(78,0) NOT NULL,
    tx_hash character varying(128) NOT NULL,
    max_fee_per_gas numeric(78,0) NOT NULL,
    max_priority_fee_per_gas numeric(78,0) NOT NULL,
    gas_limit bigint NOT NULL,
    rpc_node_id bigint,
    calldata_hash character varying(66) NOT NULL,
    signed_tx_ciphertext text NOT NULL,
    encryption_key_version character varying(64) NOT NULL,
    status character varying(32) NOT NULL,
    error_code character varying(64),
    error_message text,
    replaced_by_tx_hash character varying(128),
    rebroadcast_count integer DEFAULT 0 NOT NULL,
    last_rebroadcast_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    submitted_at timestamp with time zone,
    observed_at timestamp with time zone,
    CONSTRAINT evm_collection_batch_attempt_number_key UNIQUE (batch_id, attempt_no),
    CONSTRAINT evm_collection_batch_attempt_tx_key UNIQUE (tx_hash),
    CONSTRAINT evm_collection_batch_attempt_rebroadcast_count_check CHECK (rebroadcast_count >= 0),
    CONSTRAINT evm_collection_batch_attempt_status_check CHECK ((status)::text = ANY ((ARRAY[
        'CREATED'::character varying, 'SUBMITTED'::character varying,
        'PENDING'::character varying, 'CONFIRMED'::character varying,
        'DROPPED'::character varying, 'REPLACED'::character varying,
        'FAILED'::character varying, 'UNKNOWN'::character varying])::text[])),
    CONSTRAINT evm_collection_batch_attempt_batch_fk FOREIGN KEY (tenant_id, batch_id)
        REFERENCES public.evm_collection_batch(tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT evm_collection_batch_attempt_rpc_fk FOREIGN KEY (rpc_node_id)
        REFERENCES public.chain_rpc_node(id) ON DELETE RESTRICT
);

CREATE TABLE public.evm_withdrawal_batch (
    id uuid NOT NULL PRIMARY KEY,
    tenant_id uuid NOT NULL,
    chain character varying(32) NOT NULL,
    network character varying(64) NOT NULL,
    asset_symbol character varying(32) NOT NULL,
    token_contract character varying(42) NOT NULL,
    token_decimals integer NOT NULL,
    hot_wallet character varying(42) NOT NULL,
    relayer_address character varying(42) NOT NULL,
    delegate_version integer NOT NULL,
    batch_hash character varying(66) NOT NULL,
    claim_payload jsonb NOT NULL,
    status character varying(32) NOT NULL,
    item_count integer NOT NULL,
    authorization_included boolean DEFAULT false NOT NULL,
    authorization_nonce numeric(78,0),
    operation_nonce numeric(78,0),
    signature_deadline timestamp with time zone,
    estimated_gas bigint,
    gas_limit bigint,
    max_fee_per_gas numeric(78,0),
    max_priority_fee_per_gas numeric(78,0),
    canonical_tx_hash character varying(128),
    actual_gas_used bigint,
    effective_gas_price numeric(78,0),
    l2_fee_atomic numeric(78,0),
    l1_fee_atomic numeric(78,0),
    operator_fee_atomic numeric(78,0),
    total_fee_atomic numeric(78,0),
    actual_fee numeric(78,24),
    confirmed_block_number bigint,
    confirmed_block_hash character varying(128),
    error_code character varying(64),
    error_message text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    submitted_at timestamp with time zone,
    confirmed_at timestamp with time zone,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT evm_withdrawal_batch_tenant_id_key UNIQUE (tenant_id, id),
    CONSTRAINT evm_withdrawal_batch_hash_key UNIQUE (chain, batch_hash),
    CONSTRAINT evm_withdrawal_batch_item_count_check CHECK (item_count BETWEEN 1 AND 100),
    CONSTRAINT evm_withdrawal_batch_status_check CHECK ((status)::text = ANY ((ARRAY[
        'LOCKED'::character varying, 'SIGNING'::character varying,
        'SUBMITTED'::character varying, 'BROADCAST_UNKNOWN'::character varying,
        'CONFIRMING'::character varying, 'CONFIRMED'::character varying,
        'PARTIAL_FAILED'::character varying, 'FAILED'::character varying,
        'REORGED'::character varying, 'MANUAL_REVIEW'::character varying])::text[])),
    CONSTRAINT evm_withdrawal_batch_tenant_fk FOREIGN KEY (tenant_id)
        REFERENCES public.custody_tenant(id) ON DELETE RESTRICT,
    CONSTRAINT evm_withdrawal_batch_claim_check CHECK (
        jsonb_typeof(claim_payload) = 'object'
        AND claim_payload ?& ARRAY['id','tenantId','chain','network','batchHash']
        AND (claim_payload->>'id') IS NOT DISTINCT FROM id::text
        AND (claim_payload->>'tenantId') IS NOT DISTINCT FROM tenant_id::text
        AND (claim_payload->>'chain') IS NOT DISTINCT FROM chain
        AND (claim_payload->>'network') IS NOT DISTINCT FROM network
        AND (claim_payload->>'batchHash') IS NOT DISTINCT FROM batch_hash)
);

CREATE UNIQUE INDEX evm_withdrawal_batch_tx_key
    ON public.evm_withdrawal_batch USING btree (chain, canonical_tx_hash)
    WHERE canonical_tx_hash IS NOT NULL;

CREATE INDEX evm_withdrawal_batch_work_idx
    ON public.evm_withdrawal_batch USING btree (chain, network, status, updated_at);

CREATE TABLE public.evm_withdrawal_batch_item (
    id uuid NOT NULL PRIMARY KEY,
    tenant_id uuid NOT NULL,
    batch_id uuid NOT NULL,
    item_index integer NOT NULL,
    withdrawal_order_id bigint NOT NULL,
    custody_withdrawal_id uuid NOT NULL,
    withdrawal_id_hash character varying(66) NOT NULL,
    recipient character varying(42) NOT NULL,
    token_contract character varying(42) NOT NULL,
    requested_amount_atomic numeric(78,0) NOT NULL,
    actual_received_atomic numeric(78,0),
    call_gas_limit bigint NOT NULL,
    status character varying(32) NOT NULL,
    log_index integer,
    error_code character varying(64),
    error_hash character varying(66),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT evm_withdrawal_batch_item_position_key UNIQUE (batch_id, item_index),
    CONSTRAINT evm_withdrawal_batch_item_order_key UNIQUE (batch_id, withdrawal_order_id),
    CONSTRAINT evm_withdrawal_batch_item_hash_key UNIQUE (batch_id, withdrawal_id_hash),
    CONSTRAINT evm_withdrawal_batch_item_amount_check CHECK (requested_amount_atomic > 0),
    CONSTRAINT evm_withdrawal_batch_item_received_check CHECK (actual_received_atomic IS NULL OR actual_received_atomic >= 0),
    CONSTRAINT evm_withdrawal_batch_item_status_check CHECK ((status)::text = ANY ((ARRAY[
        'CREATED'::character varying, 'SIGNED'::character varying,
        'SUBMITTED'::character varying, 'CONFIRMED'::character varying,
        'RETRYABLE'::character varying, 'FAILED'::character varying,
        'REORGED'::character varying, 'MANUAL_REVIEW'::character varying])::text[])),
    CONSTRAINT evm_withdrawal_batch_item_batch_fk FOREIGN KEY (tenant_id, batch_id)
        REFERENCES public.evm_withdrawal_batch(tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT evm_withdrawal_batch_item_order_fk FOREIGN KEY (tenant_id, withdrawal_order_id)
        REFERENCES public.withdrawal_order(tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT evm_withdrawal_batch_item_custody_fk FOREIGN KEY (tenant_id, custody_withdrawal_id)
        REFERENCES public.custody_withdrawal(tenant_id, id) ON DELETE RESTRICT
);

CREATE UNIQUE INDEX evm_withdrawal_batch_item_active_order_key
    ON public.evm_withdrawal_batch_item USING btree (withdrawal_order_id)
    WHERE status IN ('CREATED', 'SIGNED', 'SUBMITTED');

CREATE TABLE public.evm_withdrawal_batch_attempt (
    id uuid NOT NULL PRIMARY KEY,
    tenant_id uuid NOT NULL,
    batch_id uuid NOT NULL,
    attempt_no integer NOT NULL,
    relayer_nonce numeric(78,0) NOT NULL,
    tx_hash character varying(128) NOT NULL,
    max_fee_per_gas numeric(78,0) NOT NULL,
    max_priority_fee_per_gas numeric(78,0) NOT NULL,
    gas_limit bigint NOT NULL,
    calldata_hash character varying(66) NOT NULL,
    signed_tx_ciphertext text NOT NULL,
    encryption_key_version character varying(64) NOT NULL,
    status character varying(32) NOT NULL,
    error_code character varying(64),
    error_message text,
    replaced_by_tx_hash character varying(128),
    rebroadcast_count integer DEFAULT 0 NOT NULL,
    last_rebroadcast_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    submitted_at timestamp with time zone,
    observed_at timestamp with time zone,
    CONSTRAINT evm_withdrawal_batch_attempt_number_key UNIQUE (batch_id, attempt_no),
    CONSTRAINT evm_withdrawal_batch_attempt_tx_key UNIQUE (tx_hash),
    CONSTRAINT evm_withdrawal_batch_attempt_rebroadcast_count_check CHECK (rebroadcast_count >= 0),
    CONSTRAINT evm_withdrawal_batch_attempt_status_check CHECK ((status)::text = ANY ((ARRAY[
        'CREATED'::character varying, 'SUBMITTED'::character varying,
        'PENDING'::character varying, 'CONFIRMED'::character varying,
        'DROPPED'::character varying, 'REPLACED'::character varying,
        'FAILED'::character varying, 'UNKNOWN'::character varying])::text[])),
    CONSTRAINT evm_withdrawal_batch_attempt_batch_fk FOREIGN KEY (tenant_id, batch_id)
        REFERENCES public.evm_withdrawal_batch(tenant_id, id) ON DELETE RESTRICT
);

ALTER TABLE ONLY public.collection_record
    ADD CONSTRAINT collection_record_tenant_fk FOREIGN KEY (tenant_id)
        REFERENCES public.custody_tenant(id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.collection_record
    ADD CONSTRAINT collection_record_custody_fk FOREIGN KEY (tenant_id, custody_address_id)
        REFERENCES public.custody_address(tenant_id, id) ON DELETE RESTRICT;

-- PostgreSQL database dump complete
--




-- Name: custody_asset_recovery custody_asset_recovery_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custody_asset_recovery
    ADD CONSTRAINT custody_asset_recovery_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.custody_tenant(id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.custody_asset_recovery
    ADD CONSTRAINT custody_asset_recovery_address_fk FOREIGN KEY (tenant_id, custody_address_id) REFERENCES public.custody_address(tenant_id, id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.custody_reorg_deficit
    ADD CONSTRAINT custody_reorg_deficit_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.custody_tenant(id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.custody_reorg_deficit
    ADD CONSTRAINT custody_reorg_deficit_address_fk FOREIGN KEY (tenant_id, custody_address_id) REFERENCES public.custody_address(tenant_id, id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.custody_reorg_deficit
    ADD CONSTRAINT custody_reorg_deficit_deposit_record_fkey FOREIGN KEY (tenant_id, deposit_record_id) REFERENCES public.deposit_record(tenant_id, id) ON DELETE RESTRICT;

CREATE INDEX custody_asset_recovery_tenant_status_idx
    ON public.custody_asset_recovery USING btree (tenant_id, status, created_at DESC);

CREATE INDEX custody_asset_recovery_platform_status_idx
    ON public.custody_asset_recovery USING btree (status, created_at DESC);

CREATE INDEX custody_reorg_deficit_account_idx
    ON public.custody_reorg_deficit USING btree (tenant_id, chain, asset_symbol, account_id, status);

CREATE INDEX chain_scan_block_recent_idx
    ON public.chain_scan_block USING btree (chain, scanner_name, block_height DESC);

-- Canonical chain assets, with stable IDs and exact atomic-unit thresholds.
INSERT INTO public.chain_asset (id, chain, symbol, asset_kind, contract_address, decimals, native_asset, active, min_transfer, min_withdraw, created_at, updated_at) VALUES
    (1, 'BTC', 'BTC', 'NATIVE', NULL, 8, true, true, '0', '0', '2026-06-20T15:10:22.823199+08:00', '2026-06-24T18:07:33.46956+08:00'),
    (2, 'ETH', 'ETH', 'NATIVE', NULL, 18, true, true, '0', '0', '2026-06-20T15:10:22.823199+08:00', '2026-06-20T15:10:22.823199+08:00'),
    (3, 'BNB', 'BNB', 'NATIVE', NULL, 18, true, true, '0', '0', '2026-06-20T15:10:22.823199+08:00', '2026-06-20T15:10:22.823199+08:00'),
    (4, 'POLYGON', 'POL', 'NATIVE', NULL, 18, true, true, '0', '0', '2026-06-20T15:10:22.823199+08:00', '2026-06-20T15:10:22.823199+08:00'),
    (5, 'ARBITRUM', 'ETH_ARB', 'NATIVE', NULL, 18, true, true, '0', '0', '2026-06-20T15:10:22.823199+08:00', '2026-06-20T15:10:22.823199+08:00'),
    (6, 'OPTIMISM', 'ETH_OP', 'NATIVE', NULL, 18, true, true, '0', '0', '2026-06-20T15:10:22.823199+08:00', '2026-06-20T15:10:22.823199+08:00'),
    (7, 'BASE', 'ETH_BASE', 'NATIVE', NULL, 18, true, true, '0', '0', '2026-06-20T15:10:22.823199+08:00', '2026-06-20T15:10:22.823199+08:00'),
    (8, 'AVAX_C', 'AVAX_C', 'NATIVE', NULL, 18, true, true, '0', '0', '2026-06-20T15:10:22.823199+08:00', '2026-06-20T15:10:22.823199+08:00'),
    (9, 'TRON', 'TRX', 'NATIVE', NULL, 6, true, true, '0', '0', '2026-06-20T15:10:22.823199+08:00', '2026-06-20T15:10:22.823199+08:00'),
    (10, 'SOLANA', 'SOL', 'NATIVE', NULL, 9, true, true, '0', '0', '2026-06-20T15:10:22.823199+08:00', '2026-06-23T21:01:38.531755+08:00'),
    (11, 'TON', 'TON', 'NATIVE', NULL, 9, true, true, '0', '0', '2026-06-20T15:10:22.823199+08:00', '2026-06-23T21:01:38.590086+08:00'),
    (12, 'LTC', 'LTC', 'NATIVE', NULL, 8, true, true, '0', '0', '2026-06-21T07:45:07.734233+08:00', '2026-06-24T18:07:33.46956+08:00'),
    (17, 'DOGE', 'DOGE', 'NATIVE', NULL, 8, true, true, '0', '0', '2026-06-21T19:13:13.386542+08:00', '2026-06-24T18:07:33.46956+08:00'),
    (20, 'BCH', 'BCH', 'NATIVE', NULL, 8, true, true, '0', '0', '2026-06-21T23:17:57.796638+08:00', '2026-06-24T18:07:33.46956+08:00'),
    (25, 'SOLANA', 'USDT', 'TOKEN', '2VHdVaW1jBGmvU1PdQpvmPnWV5AiAxrmxmUTLvf8951y', 6, false, true, '0', '0', '2026-06-23T15:01:18.906314+08:00', '2026-10-24T04:50:35.378848+08:00'),
    (26, 'SOLANA', 'USDC', 'TOKEN', '4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU', 6, false, true, '0', '0', '2026-06-23T15:01:43.41494+08:00', '2026-10-24T04:50:35.378848+08:00'),
    (34, 'TON', 'USDT', 'JETTON', 'kQCZ5SAA78W_0vA5eSoU23YomxnUwah3KYagqeesNQI5jOXT', 6, false, true, '0', '0', '2026-06-23T21:37:58.688905+08:00', '2026-10-24T04:50:35.378848+08:00'),
    (35, 'TON', 'USDC', 'JETTON', 'kQCzPT6908-8TR862TQo1S43-2kEme8UKRCRSWkaxNLD7H_2', 6, false, true, '0', '0', '2026-06-23T21:38:19.051838+08:00', '2026-10-24T04:50:35.378848+08:00'),
    (44, 'APTOS', 'APT', 'NATIVE', NULL, 8, true, true, '0', '0', '2026-06-23T22:24:58.098309+08:00', '2026-06-23T22:24:58.098309+08:00'),
    (45, 'SUI', 'SUI', 'NATIVE', NULL, 9, true, true, '0', '0', '2026-06-23T23:21:21.429955+08:00', '2026-06-23T23:21:21.429955+08:00'),
    (136, 'XRP', 'XRP', 'NATIVE', NULL, 6, true, true, '0', '0', '2026-06-27T12:10:00+08:00', '2026-06-27T12:10:00+08:00'),
    (138, 'TRON', 'USDT', 'TOKEN', 'TXYZopYRdj2D9XRtbG411XZZ3kM5VkAeBf', 6, false, true, '1', '1', '2026-10-24T04:50:35.378848+08:00', '2026-10-24T04:50:35.378848+08:00'),
    (141, 'OPTIMISM', 'USDT', 'TOKEN', '0xcb4CB0127B079d42b81F53e747b763d206D050f9', 6, false, true, '1', '1', '2026-10-24T04:50:35.378848+08:00', '2026-10-24T04:50:35.378848+08:00'),
    (142, 'BASE', 'USDT', 'TOKEN', '0x210BBd033630e5e611B7922D70b0Caabe64636d9', 6, false, true, '1', '1', '2026-10-24T04:50:35.378848+08:00', '2026-10-24T04:50:35.378848+08:00'),
    (143, 'AVAX_C', 'USDT', 'TOKEN', '0x1B43cbC6879C8237469794F9B8Ed290810e502d9', 6, false, true, '1', '1', '2026-10-24T04:50:35.378848+08:00', '2026-10-24T04:50:35.378848+08:00'),
    (144, 'POLYGON', 'USDT', 'TOKEN', '0xb5F6211f94FCC162D5c8cebba4f656c965577392', 6, false, true, '1', '1', '2026-10-24T04:50:35.378848+08:00', '2026-10-24T04:50:35.378848+08:00'),
    (145, 'ETH', 'USDC', 'TOKEN', '0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238', 6, false, true, '1', '1', '2026-10-24T04:50:35.378848+08:00', '2026-10-24T04:50:35.378848+08:00'),
    (146, 'POLYGON', 'USDC', 'TOKEN', '0x41E94Eb019C0762f9Bfcf9Fb1E58725BfB0e7582', 6, false, true, '1', '1', '2026-10-24T04:50:35.378848+08:00', '2026-10-24T04:50:35.378848+08:00'),
    (147, 'ARBITRUM', 'USDC', 'TOKEN', '0x75faf114eafb1BDbe2F0316DF893fd58CE46AA4d', 6, false, true, '1', '1', '2026-10-24T04:50:35.378848+08:00', '2026-10-24T04:50:35.378848+08:00'),
    (148, 'OPTIMISM', 'USDC', 'TOKEN', '0x5fd84259d66Cd46123540766Be93DFE6D43130D7', 6, false, true, '1', '1', '2026-10-24T04:50:35.378848+08:00', '2026-10-24T04:50:35.378848+08:00'),
    (149, 'BASE', 'USDC', 'TOKEN', '0x036CbD53842c5426634e7929541eC2318f3dCF7e', 6, false, true, '1', '1', '2026-10-24T04:50:35.378848+08:00', '2026-10-24T04:50:35.378848+08:00'),
    (150, 'AVAX_C', 'USDC', 'TOKEN', '0x5425890298aed601595a70AB815c96711a31Bc65', 6, false, true, '1', '1', '2026-10-24T04:50:35.378848+08:00', '2026-10-24T04:50:35.378848+08:00'),
    (151, 'BNB', 'USDC', 'TOKEN', '0x31873b5804bABE258d6ea008f55e08DD00b7d51E', 6, false, true, '1', '1', '2026-10-24T04:50:35.378848+08:00', '2026-10-24T04:50:35.378848+08:00'),
    (152, 'APTOS', 'USDC', 'TOKEN', '0x69091fbab5f7d635ee7ac5098cf0c1efbe31d68fec0f2cd565e8d168daf52832', 6, false, true, '0', '0', '2026-10-24T04:50:35.378848+08:00', '2026-10-24T04:50:35.378848+08:00'),
    (153, 'APTOS', 'USDT', 'TOKEN', '0x6b015ce46a5d15c63c659ebf949c99c3d79dab462db4cf8fb8f609e45229e2cf', 6, false, true, '0', '0', '2026-10-24T04:50:35.378848+08:00', '2026-10-24T04:50:35.378848+08:00'),
    (154, 'SUI', 'USDC', 'TOKEN', '0xa1ec7fc00a6f40db9693ad1415d0c193ad3906494428cf252621037bd7117e29::usdc::USDC', 6, false, true, '0', '0', '2026-10-24T04:50:35.378848+08:00', '2026-10-24T04:50:35.378848+08:00'),
    (155, 'XRP', 'USDC', 'TOKEN', 'rHuGNhqTG32mfmAvWA8hUyWRLV3tCSwKQt:5553444300000000000000000000000000000000', 6, false, true, '0', '0', '2026-10-24T04:50:35.378848+08:00', '2026-10-24T04:50:35.378848+08:00'),
    (156, 'XRP', 'USDT', 'TOKEN', 'rHuGNhqTG32mfmAvWA8hUyWRLV3tCSwKQt:5553445400000000000000000000000000000000', 6, false, true, '0', '0', '2026-10-24T04:50:35.378848+08:00', '2026-10-24T04:50:35.378848+08:00'),
    (157, 'ETH', 'USDT', 'TOKEN', '0x01a6810727db185bbf7f30ec158c3ac8b8112627', 6, false, true, '1', '1', '2026-10-24T04:50:35.378848+08:00', '2026-10-24T04:50:35.378848+08:00'),
    (158, 'ARBITRUM', 'USDT', 'TOKEN', '0xEf54C221Fc94517877F0F40eCd71E0A3866D66C2', 6, false, true, '1', '1', '2026-10-24T04:50:35.378848+08:00', '2026-10-24T04:50:35.378848+08:00'),
    (159, 'BNB', 'USDT', 'TOKEN', '0xE8709025f99dd1B8533FB9b78Ca879Ee4ec7E70a', 6, false, true, '1', '1', '2026-10-24T04:50:35.378848+08:00', '2026-10-24T04:50:35.378848+08:00'),
    (161, 'HYPEREVM', 'HYPE', 'NATIVE', NULL, 18, true, true, '0', '0', '2026-10-24T04:50:35.383293+08:00', '2026-10-24T04:50:35.383293+08:00'),
    (162, 'HYPEREVM', 'USDC', 'ERC20', '0x2B3370eE501B4a559b57D449569354196457D8Ab', 6, false, true, '1', '1', '2026-10-24T04:50:35.383293+08:00', '2026-10-24T04:50:35.383293+08:00'),
    (163, 'LINEA', 'ETH_LINEA', 'NATIVE', NULL, 18, true, true, '0', '0', '2026-10-24T04:50:35.385562+08:00', '2026-10-24T04:50:35.385562+08:00'),
    (164, 'LINEA', 'USDC', 'ERC20', '0xFEce4462D57bD51A6A552365A011b95f0E16d9B7', 6, false, true, '1', '1', '2026-10-24T04:50:35.385562+08:00', '2026-10-24T04:50:35.385562+08:00'),
    (165, 'LINEA', 'USDT', 'ERC20', '0xA219439258ca9da29E9Cc4cE5596924745e12B93', 6, false, false, '1', '1', '2026-10-24T04:50:35.385562+08:00', '2026-10-24T04:50:35.385562+08:00'),
    (166, 'SCROLL', 'ETH_SCROLL', 'NATIVE', NULL, 18, true, true, '0', '0', '2026-10-24T04:50:35.385562+08:00', '2026-10-24T04:50:35.385562+08:00'),
    (167, 'SCROLL', 'USDC', 'ERC20', '0x7878290DB8C4f02bd06E0E249617871c19508bE6', 6, false, true, '1', '1', '2026-10-24T04:50:35.385562+08:00', '2026-10-24T04:50:35.385562+08:00'),
    (168, 'SCROLL', 'USDT', 'ERC20', '0xf55BEC9cafDbE8730f096Aa55dad6D22d44099Df', 6, false, false, '1', '1', '2026-10-24T04:50:35.385562+08:00', '2026-10-24T04:50:35.385562+08:00'),
    (169, 'UNICHAIN', 'ETH_UNICHAIN', 'NATIVE', NULL, 18, true, true, '0', '0', '2026-10-24T04:50:35.385562+08:00', '2026-10-24T04:50:35.385562+08:00'),
    (170, 'UNICHAIN', 'USDC', 'ERC20', '0x31d0220469e10c4E71834a79b1f276d740d3768F', 6, false, true, '1', '1', '2026-10-24T04:50:35.385562+08:00', '2026-10-24T04:50:35.385562+08:00'),
    (171, 'X_LAYER', 'OKB', 'NATIVE', NULL, 18, true, true, '0', '0', '2026-10-24T04:50:35.385562+08:00', '2026-10-24T04:50:35.385562+08:00'),
    (172, 'X_LAYER', 'USDC', 'ERC20', '0x74b7F16337b8972027F6196A17a631aC6dE26d22', 6, false, false, '1', '1', '2026-10-24T04:50:35.385562+08:00', '2026-10-24T04:50:35.385562+08:00'),
    (173, 'X_LAYER', 'USDT', 'ERC20', '0x1E4a5963aBFD975d8c9021ce480b42188849D41d', 6, false, false, '1', '1', '2026-10-24T04:50:35.385562+08:00', '2026-10-24T04:50:35.385562+08:00'),
    (174, 'ROBINHOOD_CHAIN', 'ETH_ROBINHOOD', 'NATIVE', NULL, 18, true, true, '0', '0', '2026-10-24T04:50:35.385562+08:00', '2026-10-24T04:50:35.385562+08:00'),
    (175, 'ROBINHOOD_CHAIN', 'USDG', 'ERC20', '0x5fc5360D0400a0Fd4f2af552ADD042D716F1d168', 6, false, false, '1', '1', '2026-10-24T04:50:35.385562+08:00', '2026-10-24T04:50:35.385562+08:00'),
    (176, 'HYPERCORE', 'USDC', 'HIP1_USDC', NULL, 6, true, true, '1', '1', '2026-10-24T04:50:35.389884+08:00', '2026-10-24T04:50:35.389884+08:00'),
    (177, 'HYPERCORE', 'HYPE', 'HIP1', '0x7317beb7cceed72ef0b346074cc8e7ab', 8, false, true, '0', '0', '2026-10-24T04:50:35.389884+08:00', '2026-10-24T04:50:35.389884+08:00'),
    (178, 'ADA', 'ADA', 'NATIVE', NULL, 6, true, false, '0', '0', '2026-10-24T04:50:35.393104+08:00', '2026-10-24T04:50:35.393104+08:00'),
    (179, 'DOT', 'DOT', 'NATIVE', NULL, 10, true, false, '0', '0', '2026-10-24T04:50:35.393104+08:00', '2026-10-24T04:50:35.393104+08:00'),
    (180, 'NEAR', 'NEAR', 'NATIVE', NULL, 24, true, false, '0', '0', '2026-10-24T04:50:35.393104+08:00', '2026-10-24T04:50:35.393104+08:00'),
    (181, 'XMR', 'XMR', 'NATIVE', NULL, 12, true, false, '0', '0', '2026-10-24T04:50:35.393104+08:00', '2026-10-24T04:50:35.393104+08:00'),
    (182, 'ADA', 'USDC', 'CARDANO_NATIVE_ASSET', 'CHANGE_ME_CARDANO_USDC_POLICY_ID.CHANGE_ME_ASSET_NAME_HEX', 6, false, false, '1', '1', '2026-10-24T04:50:35.393587+08:00', '2026-10-24T04:50:35.393587+08:00'),
    (183, 'ADA', 'USDT', 'CARDANO_NATIVE_ASSET', 'CHANGE_ME_CARDANO_USDT_POLICY_ID.CHANGE_ME_ASSET_NAME_HEX', 6, false, false, '1', '1', '2026-10-24T04:50:35.393587+08:00', '2026-10-24T04:50:35.393587+08:00'),
    (184, 'DOT', 'USDC', 'ASSET_HUB_ASSET', '31337', 6, false, false, '1', '1', '2026-10-24T04:50:35.393587+08:00', '2026-10-24T04:50:35.393587+08:00'),
    (185, 'DOT', 'USDT', 'ASSET_HUB_ASSET', '1984', 6, false, false, '1', '1', '2026-10-24T04:50:35.393587+08:00', '2026-10-24T04:50:35.393587+08:00'),
    (186, 'NEAR', 'USDC', 'NEP141', '3e2210e1184b45b64c8a434c0a7e7b23cc04ea7eb7a6c3c32520d03d4afcb8af', 6, false, false, '1', '1', '2026-10-24T04:50:35.393587+08:00', '2026-10-24T04:50:35.393587+08:00'),
    (187, 'NEAR', 'USDT', 'NEP141', 'CHANGE_ME_USDT_CONTRACT.testnet', 6, false, false, '1', '1', '2026-10-24T04:50:35.393587+08:00', '2026-10-24T04:50:35.393587+08:00');



--
-- Data for Name: chain_profile; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at", "chain_id", "gas_policy", "scan_batch_size", "scan_enabled", "withdraw_enabled", "collection_enabled", "transfer_enabled", "scan_start_height", "scan_max_blocks_per_run") VALUES (81, 'BTC', 'testnet3', 'bitcoin-like', 1, 0, 'BTC', 'https://bitcoin-testnet-rpc.publicnode.com', 'https://mempool.space/testnet/tx/', 1, 6, 10, 546, true, '2026-06-24 14:56:38.262511+08', '2026-06-25 00:11:35.416831+08', NULL, 'utxo', 6, true, true, true, false, 0, 0);
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at", "chain_id", "gas_policy", "scan_batch_size", "scan_enabled", "withdraw_enabled", "collection_enabled", "transfer_enabled", "scan_start_height", "scan_max_blocks_per_run") VALUES (1, 'LTC', 'testnet', 'bitcoin-like', 24, 2, 'LTC', NULL, 'https://litecoinspace.org/testnet/tx/', 1, 6, 2, 1000, true, '2026-06-21 18:09:31.766008+08', '2026-06-25 00:11:35.416831+08', NULL, 'utxo', 6, true, true, true, false, 0, 0);
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at", "chain_id", "gas_policy", "scan_batch_size", "scan_enabled", "withdraw_enabled", "collection_enabled", "transfer_enabled", "scan_start_height", "scan_max_blocks_per_run") VALUES (8, 'DOGE', 'testnet', 'bitcoin-like', 41, 3, 'DOGE', NULL, 'https://doge-testnet-explorer.qed.me/tx/', 6, 12, 1000, 1000000, true, '2026-06-21 19:13:13.385044+08', '2026-06-25 00:11:35.416831+08', NULL, 'utxo', 6, true, true, true, false, 0, 0);
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at", "chain_id", "gas_policy", "scan_batch_size", "scan_enabled", "withdraw_enabled", "collection_enabled", "transfer_enabled", "scan_start_height", "scan_max_blocks_per_run") VALUES (14, 'BCH', 'testnet', 'bitcoin-like', 42, 145, 'BCH', NULL, 'https://tbch.loping.net/tx/', 1, 6, 1, 546, true, '2026-06-21 23:17:57.795865+08', '2026-06-25 00:11:35.416831+08', NULL, 'utxo', 6, true, true, true, false, 0, 0);
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at", "chain_id", "gas_policy", "scan_batch_size", "scan_enabled", "withdraw_enabled", "collection_enabled", "transfer_enabled", "scan_start_height", "scan_max_blocks_per_run") VALUES (43, 'SUI', 'testnet', 'sui', 53, 784, 'SUI', 'fullnode.testnet.sui.io:443', 'https://suiexplorer.com/txblock/', 1, 1, 10000000, 1, true, '2026-06-23 23:21:21.428505+08', '2026-06-25 00:11:35.418443+08', NULL, 'sui', 100, true, true, true, true, 0, 0);
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at", "chain_id", "gas_policy", "scan_batch_size", "scan_enabled", "withdraw_enabled", "collection_enabled", "transfer_enabled", "scan_start_height", "scan_max_blocks_per_run") VALUES (44, 'SUI', 'mainnet', 'sui', 53, 784, 'SUI', NULL, 'https://suiexplorer.com/txblock/', 1, 1, 10000000, 1, false, '2026-06-23 23:21:21.428505+08', '2026-06-25 00:11:35.413224+08', NULL, NULL, 100, false, false, false, false, 0, 0);
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at", "chain_id", "gas_policy", "scan_batch_size", "scan_enabled", "withdraw_enabled", "collection_enabled", "transfer_enabled", "scan_start_height", "scan_max_blocks_per_run") VALUES (97, 'BTC', 'regtest', 'bitcoin-like', 1, 0, 'BTC', NULL, NULL, 6, 6, 1, 546, false, '2026-06-24 17:50:02.98325+08', '2026-06-25 00:11:35.413224+08', NULL, NULL, 100, false, false, false, false, 0, 0);
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at", "chain_id", "gas_policy", "scan_batch_size", "scan_enabled", "withdraw_enabled", "collection_enabled", "transfer_enabled", "scan_start_height", "scan_max_blocks_per_run") VALUES (98, 'LTC', 'regtest', 'bitcoin-like', 24, 2, 'LTC', NULL, NULL, 6, 6, 10, 100000, false, '2026-06-24 17:50:02.98325+08', '2026-06-25 00:11:35.413224+08', NULL, NULL, 100, false, false, false, false, 0, 0);
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at", "chain_id", "gas_policy", "scan_batch_size", "scan_enabled", "withdraw_enabled", "collection_enabled", "transfer_enabled", "scan_start_height", "scan_max_blocks_per_run") VALUES (16, 'DOGE', 'regtest', 'bitcoin-like', 41, 3, 'DOGE', 'http://127.0.0.1:22555', NULL, 6, 6, 1000, 1000000, false, '2026-06-23 10:11:12.947084+08', '2026-06-25 00:11:35.413224+08', NULL, NULL, 100, false, false, false, false, 0, 0);
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at", "chain_id", "gas_policy", "scan_batch_size", "scan_enabled", "withdraw_enabled", "collection_enabled", "transfer_enabled", "scan_start_height", "scan_max_blocks_per_run") VALUES (17, 'BCH', 'regtest', 'bitcoin-like', 42, 145, 'BCH', 'http://127.0.0.1:18443', NULL, 6, 6, 1, 546, false, '2026-06-23 11:36:08.628091+08', '2026-06-25 00:11:35.413224+08', NULL, NULL, 100, false, false, false, false, 0, 0);
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at", "chain_id", "gas_policy", "scan_batch_size", "scan_enabled", "withdraw_enabled", "collection_enabled", "transfer_enabled", "scan_start_height", "scan_max_blocks_per_run") VALUES (82, 'BTC', 'mainnet', 'bitcoin-like', 1, 0, 'BTC', NULL, 'https://mempool.space/tx/', 1, 6, 10, 546, false, '2026-06-24 14:56:38.262511+08', '2026-06-25 00:11:35.413224+08', NULL, NULL, 100, false, false, false, false, 0, 0);
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at", "chain_id", "gas_policy", "scan_batch_size", "scan_enabled", "withdraw_enabled", "collection_enabled", "transfer_enabled", "scan_start_height", "scan_max_blocks_per_run") VALUES (3, 'LTC', 'mainnet', 'bitcoin-like', 24, 2, 'LTC', NULL, 'https://litecoinspace.org/tx/', 1, 6, 2, 1000, false, '2026-06-21 18:32:14.57969+08', '2026-06-25 00:11:35.413224+08', NULL, NULL, 100, false, false, false, false, 0, 0);
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at", "chain_id", "gas_policy", "scan_batch_size", "scan_enabled", "withdraw_enabled", "collection_enabled", "transfer_enabled", "scan_start_height", "scan_max_blocks_per_run") VALUES (9, 'DOGE', 'mainnet', 'bitcoin-like', 41, 3, 'DOGE', NULL, 'https://dogechain.info/tx/', 6, 12, 1000, 1000000, false, '2026-06-21 19:13:13.385893+08', '2026-06-25 00:11:35.413224+08', NULL, NULL, 100, false, false, false, false, 0, 0);
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at", "chain_id", "gas_policy", "scan_batch_size", "scan_enabled", "withdraw_enabled", "collection_enabled", "transfer_enabled", "scan_start_height", "scan_max_blocks_per_run") VALUES (15, 'BCH', 'mainnet', 'bitcoin-like', 42, 145, 'BCH', NULL, 'https://blockchair.com/bitcoin-cash/transaction/', 1, 6, 1, 546, false, '2026-06-21 23:17:57.795865+08', '2026-06-25 00:11:35.413224+08', NULL, NULL, 100, false, false, false, false, 0, 0);
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at", "chain_id", "gas_policy", "scan_batch_size", "scan_enabled", "withdraw_enabled", "collection_enabled", "transfer_enabled", "scan_start_height", "scan_max_blocks_per_run") VALUES (27, 'SOLANA', 'mainnet', 'solana', 50, 501, 'SOL', NULL, 'https://explorer.solana.com/tx/', 32, 32, 5000, 890880, false, '2026-06-23 14:47:05.346909+08', '2026-06-25 00:11:35.413224+08', NULL, NULL, 100, false, false, false, false, 0, 0);
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at", "chain_id", "gas_policy", "scan_batch_size", "scan_enabled", "withdraw_enabled", "collection_enabled", "transfer_enabled", "scan_start_height", "scan_max_blocks_per_run") VALUES (39, 'TON', 'mainnet', 'ton', 51, 607, 'TON', NULL, 'https://tonviewer.com/transaction/', 1, 1, 5000000, 1000000, false, '2026-06-23 21:01:38.587522+08', '2026-06-25 00:11:35.413224+08', NULL, NULL, 100, false, false, false, false, 0, 0);
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at", "chain_id", "gas_policy", "scan_batch_size", "scan_enabled", "withdraw_enabled", "collection_enabled", "transfer_enabled", "scan_start_height", "scan_max_blocks_per_run") VALUES (42, 'APTOS', 'mainnet', 'aptos', 52, 637, 'APT', NULL, 'https://explorer.aptoslabs.com/txn/', 1, 1, 5000000, 1, false, '2026-06-23 22:24:58.095994+08', '2026-06-25 00:11:35.413224+08', NULL, NULL, 100, false, false, false, false, 0, 0);
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at", "chain_id", "gas_policy", "scan_batch_size", "scan_enabled", "withdraw_enabled", "collection_enabled", "transfer_enabled", "scan_start_height", "scan_max_blocks_per_run") VALUES (41, 'APTOS', 'testnet', 'aptos', 52, 637, 'APT', 'https://fullnode.testnet.aptoslabs.com/v1', 'https://explorer.aptoslabs.com/txn/', 1, 1, 5000000, 1, false, '2026-06-23 22:24:58.095994+08', '2026-06-25 00:11:35.413224+08', NULL, NULL, 100, false, false, false, false, 0, 0);
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at", "chain_id", "gas_policy", "scan_batch_size", "scan_enabled", "withdraw_enabled", "collection_enabled", "transfer_enabled", "scan_start_height", "scan_max_blocks_per_run") VALUES (26, 'SOLANA', 'devnet', 'solana', 50, 501, 'SOL', 'https://api.devnet.solana.com', 'https://explorer.solana.com/tx/', 1, 1, 5000, 890880, true, '2026-06-23 14:47:05.346909+08', '2026-06-25 00:11:35.418443+08', NULL, 'solana', 100, true, true, true, true, 0, 0);
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at", "chain_id", "gas_policy", "scan_batch_size", "scan_enabled", "withdraw_enabled", "collection_enabled", "transfer_enabled", "scan_start_height", "scan_max_blocks_per_run") VALUES (38, 'TON', 'testnet', 'ton', 51, 607, 'TON', 'https://testnet.toncenter.com/api/v2', 'https://testnet.tonviewer.com/transaction/', 1, 1, 5000000, 1000000, true, '2026-06-23 21:01:38.587522+08', '2026-06-25 00:11:35.418443+08', NULL, 'ton', 100, true, true, true, true, 0, 0);
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at", "chain_id", "gas_policy", "scan_batch_size", "scan_enabled", "withdraw_enabled", "collection_enabled", "transfer_enabled", "scan_start_height", "scan_max_blocks_per_run") VALUES (40, 'APTOS', 'devnet', 'aptos', 52, 637, 'APT', 'https://fullnode.devnet.aptoslabs.com/v1', 'https://explorer.aptoslabs.com/txn/', 1, 1, 5000000, 1, true, '2026-06-23 22:24:58.095994+08', '2026-06-25 00:11:35.418443+08', NULL, 'aptos', 100, true, true, true, true, 0, 0);
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at", "chain_id", "gas_policy", "scan_batch_size", "scan_enabled", "withdraw_enabled", "collection_enabled", "transfer_enabled", "scan_start_height", "scan_max_blocks_per_run") VALUES (137, 'ETH', 'sepolia', 'evm', 60, 60, 'ETH', 'https://ethereum-sepolia-rpc.publicnode.com', 'https://sepolia.etherscan.io/tx/', 12, 12, 1, 0, true, '2026-06-25 00:11:35.419133+08', '2026-06-25 00:11:35.419133+08', 11155111, 'eip1559', 100, true, true, true, true, 0, 0);
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at", "chain_id", "gas_policy", "scan_batch_size", "scan_enabled", "withdraw_enabled", "collection_enabled", "transfer_enabled", "scan_start_height", "scan_max_blocks_per_run") VALUES (138, 'ETH', 'mainnet', 'evm', 60, 60, 'ETH', 'https://ethereum-rpc.publicnode.com', 'https://etherscan.io/tx/', 24, 24, 1, 0, false, '2026-06-25 00:11:35.419133+08', '2026-06-25 00:11:35.419133+08', 1, 'eip1559', 100, false, false, false, false, 0, 0);
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at", "chain_id", "gas_policy", "scan_batch_size", "scan_enabled", "withdraw_enabled", "collection_enabled", "transfer_enabled", "scan_start_height", "scan_max_blocks_per_run") VALUES (139, 'BNB', 'testnet', 'evm', 714, 60, 'BNB', 'https://bsc-testnet-rpc.publicnode.com', 'https://testnet.bscscan.com/tx/', 20, 20, 1, 0, true, '2026-06-25 00:11:35.419133+08', '2026-06-25 00:11:35.419133+08', 97, 'legacy-gas-price', 200, true, true, true, true, 0, 0);
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at", "chain_id", "gas_policy", "scan_batch_size", "scan_enabled", "withdraw_enabled", "collection_enabled", "transfer_enabled", "scan_start_height", "scan_max_blocks_per_run") VALUES (140, 'BNB', 'mainnet', 'evm', 714, 60, 'BNB', 'https://bsc-rpc.publicnode.com', 'https://bscscan.com/tx/', 20, 20, 1, 0, false, '2026-06-25 00:11:35.419133+08', '2026-06-25 00:11:35.419133+08', 56, 'legacy-gas-price', 200, false, false, false, false, 0, 0);
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at", "chain_id", "gas_policy", "scan_batch_size", "scan_enabled", "withdraw_enabled", "collection_enabled", "transfer_enabled", "scan_start_height", "scan_max_blocks_per_run") VALUES (141, 'POLYGON', 'amoy', 'evm', 966, 60, 'POL', 'https://polygon-amoy-bor-rpc.publicnode.com', 'https://amoy.polygonscan.com/tx/', 64, 64, 1, 0, true, '2026-06-25 00:11:35.419133+08', '2026-06-25 00:11:35.419133+08', 80002, 'eip1559', 300, true, true, true, true, 0, 0);
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at", "chain_id", "gas_policy", "scan_batch_size", "scan_enabled", "withdraw_enabled", "collection_enabled", "transfer_enabled", "scan_start_height", "scan_max_blocks_per_run") VALUES (142, 'POLYGON', 'mainnet', 'evm', 966, 60, 'POL', 'https://polygon-bor-rpc.publicnode.com', 'https://polygonscan.com/tx/', 128, 128, 1, 0, false, '2026-06-25 00:11:35.419133+08', '2026-06-25 00:11:35.419133+08', 137, 'eip1559', 300, false, false, false, false, 0, 0);
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at", "chain_id", "gas_policy", "scan_batch_size", "scan_enabled", "withdraw_enabled", "collection_enabled", "transfer_enabled", "scan_start_height", "scan_max_blocks_per_run") VALUES (143, 'ARBITRUM', 'sepolia', 'evm', 9001, 60, 'ETH_ARB', 'https://arbitrum-sepolia-rpc.publicnode.com', 'https://sepolia.arbiscan.io/tx/', 40, 40, 1, 0, true, '2026-06-25 00:11:35.419133+08', '2026-06-25 00:11:35.419133+08', 421614, 'eip1559', 500, true, true, true, true, 0, 0);
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at", "chain_id", "gas_policy", "scan_batch_size", "scan_enabled", "withdraw_enabled", "collection_enabled", "transfer_enabled", "scan_start_height", "scan_max_blocks_per_run") VALUES (144, 'ARBITRUM', 'mainnet', 'evm', 9001, 60, 'ETH_ARB', 'https://arbitrum-one-rpc.publicnode.com', 'https://arbiscan.io/tx/', 40, 40, 1, 0, false, '2026-06-25 00:11:35.419133+08', '2026-06-25 00:11:35.419133+08', 42161, 'eip1559', 500, false, false, false, false, 0, 0);
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at", "chain_id", "gas_policy", "scan_batch_size", "scan_enabled", "withdraw_enabled", "collection_enabled", "transfer_enabled", "scan_start_height", "scan_max_blocks_per_run") VALUES (145, 'OPTIMISM', 'sepolia', 'evm', 9002, 60, 'ETH_OP', 'https://optimism-sepolia-rpc.publicnode.com', 'https://sepolia-optimism.etherscan.io/tx/', 40, 40, 1, 0, true, '2026-06-25 00:11:35.419133+08', '2026-06-25 00:11:35.419133+08', 11155420, 'eip1559', 500, true, true, true, true, 0, 0);
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at", "chain_id", "gas_policy", "scan_batch_size", "scan_enabled", "withdraw_enabled", "collection_enabled", "transfer_enabled", "scan_start_height", "scan_max_blocks_per_run") VALUES (146, 'OPTIMISM', 'mainnet', 'evm', 9002, 60, 'ETH_OP', 'https://optimism-rpc.publicnode.com', 'https://optimistic.etherscan.io/tx/', 40, 40, 1, 0, false, '2026-06-25 00:11:35.419133+08', '2026-06-25 00:11:35.419133+08', 10, 'eip1559', 500, false, false, false, false, 0, 0);
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at", "chain_id", "gas_policy", "scan_batch_size", "scan_enabled", "withdraw_enabled", "collection_enabled", "transfer_enabled", "scan_start_height", "scan_max_blocks_per_run") VALUES (147, 'BASE', 'sepolia', 'evm', 9003, 60, 'ETH_BASE', 'https://base-sepolia-rpc.publicnode.com', 'https://sepolia.basescan.org/tx/', 40, 40, 1, 0, true, '2026-06-25 00:11:35.419133+08', '2026-06-25 00:11:35.419133+08', 84532, 'eip1559', 500, true, true, true, true, 0, 0);
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at", "chain_id", "gas_policy", "scan_batch_size", "scan_enabled", "withdraw_enabled", "collection_enabled", "transfer_enabled", "scan_start_height", "scan_max_blocks_per_run") VALUES (148, 'BASE', 'mainnet', 'evm', 9003, 60, 'ETH_BASE', 'https://base-rpc.publicnode.com', 'https://basescan.org/tx/', 40, 40, 1, 0, false, '2026-06-25 00:11:35.419133+08', '2026-06-25 00:11:35.419133+08', 8453, 'eip1559', 500, false, false, false, false, 0, 0);
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at", "chain_id", "gas_policy", "scan_batch_size", "scan_enabled", "withdraw_enabled", "collection_enabled", "transfer_enabled", "scan_start_height", "scan_max_blocks_per_run") VALUES (149, 'AVAX_C', 'fuji', 'evm', 9000, 60, 'AVAX_C', 'https://avalanche-fuji-c-chain-rpc.publicnode.com', 'https://testnet.snowtrace.io/tx/', 20, 20, 1, 0, true, '2026-06-25 00:11:35.419133+08', '2026-06-25 00:11:35.419133+08', 43113, 'eip1559', 200, true, true, true, true, 0, 0);
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at", "chain_id", "gas_policy", "scan_batch_size", "scan_enabled", "withdraw_enabled", "collection_enabled", "transfer_enabled", "scan_start_height", "scan_max_blocks_per_run") VALUES (150, 'AVAX_C', 'mainnet', 'evm', 9000, 60, 'AVAX_C', 'https://avalanche-c-chain-rpc.publicnode.com', 'https://snowtrace.io/tx/', 20, 20, 1, 0, false, '2026-06-25 00:11:35.419133+08', '2026-06-25 00:11:35.419133+08', 43114, 'eip1559', 200, false, false, false, false, 0, 0);
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at", "chain_id", "gas_policy", "scan_batch_size", "scan_enabled", "withdraw_enabled", "collection_enabled", "transfer_enabled", "scan_start_height", "scan_max_blocks_per_run") VALUES (151, 'TRON', 'nile', 'tron', 195, 195, 'TRX', 'grpc.nile.trongrid.io:50051', 'https://nile.tronscan.org/#/transaction/', 20, 20, 1, 0, true, '2026-06-25 00:11:35.419133+08', '2026-06-25 00:11:35.419133+08', NULL, 'energy-bandwidth', 100, true, true, true, true, 0, 0);
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at", "chain_id", "gas_policy", "scan_batch_size", "scan_enabled", "withdraw_enabled", "collection_enabled", "transfer_enabled", "scan_start_height", "scan_max_blocks_per_run") VALUES (152, 'TRON', 'mainnet', 'tron', 195, 195, 'TRX', 'grpc.trongrid.io:50051', 'https://tronscan.org/#/transaction/', 20, 20, 1, 0, false, '2026-06-25 00:11:35.419133+08', '2026-06-25 00:11:35.419133+08', NULL, 'energy-bandwidth', 100, false, false, false, false, 0, 0);
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at", "chain_id", "gas_policy", "scan_batch_size", "scan_enabled", "withdraw_enabled", "collection_enabled", "transfer_enabled", "scan_start_height", "scan_max_blocks_per_run") VALUES (153, 'XRP', 'testnet', 'xrp', 144, 144, 'XRP', 'https://s.altnet.rippletest.net:51234/', 'https://testnet.xrpl.org/transactions/', 1, 1, 12, 1, true, '2026-06-27 12:10:00+08', '2026-06-27 12:10:00+08', NULL, 'xrpl', 100, true, true, true, true, 0, 0);
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at", "chain_id", "gas_policy", "scan_batch_size", "scan_enabled", "withdraw_enabled", "collection_enabled", "transfer_enabled", "scan_start_height", "scan_max_blocks_per_run") VALUES (154, 'XRP', 'mainnet', 'xrp', 144, 144, 'XRP', 'https://s1.ripple.com:51234/', 'https://livenet.xrpl.org/transactions/', 3, 3, 12, 1, false, '2026-06-27 12:10:00+08', '2026-06-27 12:10:00+08', NULL, 'xrpl', 100, false, false, false, false, 0, 0);


--
-- Data for Name: chain_rpc_node; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (1, 'BTC', 'testnet3', 'dev', 'publicnode-btc-testnet', 'rpc', 'HTTP_JSON_RPC', 'https://bitcoin-testnet-rpc.publicnode.com', 'NONE', NULL, NULL, NULL, NULL, 10, true, NULL, 'public BTC testnet JSON-RPC', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', NULL, NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (2, 'LTC', 'testnet', 'dev', 'litecoinspace-testnet', 'rpc', 'ESPLORA_HTTP', 'https://litecoinspace.org/testnet/api', 'NONE', NULL, NULL, NULL, NULL, 10, true, NULL, 'Litecoin testnet Esplora API', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', NULL, NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (3, 'DOGE', 'testnet', 'dev', 'tatum-doge-testnet', 'rpc', 'HTTP_JSON_RPC', 'https://dogecoin-testnet.gateway.tatum.io', 'NONE', NULL, NULL, NULL, NULL, 10, true, NULL, 'Dogecoin testnet RPC gateway', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', NULL, NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (4, 'BCH', 'testnet', 'dev', 'tatum-bch-testnet', 'rpc', 'HTTP_JSON_RPC', 'https://bitcoin-cash-testnet.gateway.tatum.io', 'NONE', NULL, NULL, NULL, NULL, 10, true, NULL, 'Bitcoin Cash testnet RPC gateway', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', NULL, NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (5, 'SOLANA', 'devnet', 'dev', 'solana-devnet-public', 'rpc', 'HTTP_JSON_RPC', 'https://api.devnet.solana.com', 'NONE', NULL, NULL, NULL, NULL, 10, true, NULL, 'Solana devnet public RPC', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', NULL, NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password", "min_request_interval_ms") VALUES (6, 'TON', 'testnet', 'dev', 'toncenter-testnet', 'rpc', 'HTTP_JSON', 'https://testnet.toncenter.com/api/v2', 'API_KEY_OPTIONAL', NULL, NULL, NULL, NULL, 10, true, NULL, 'TON Center testnet API, optional API key', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', NULL, NULL, NULL, 1050);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password", "min_request_interval_ms") VALUES (7, 'TON', 'testnet', 'dev', 'tonapi-testnet', 'indexer', 'HTTP_JSON', 'https://testnet.tonapi.io', 'API_KEY_OPTIONAL', NULL, NULL, NULL, NULL, 20, true, NULL, 'TON API testnet indexer, optional API key', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', NULL, NULL, NULL, 1050);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (8, 'APTOS', 'devnet', 'dev', 'aptos-devnet-public', 'rpc', 'HTTP_REST', 'https://fullnode.devnet.aptoslabs.com/v1', 'NONE', NULL, NULL, NULL, NULL, 10, true, NULL, 'Aptos devnet fullnode', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', NULL, NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (9, 'APTOS', 'devnet', 'dev', 'aptos-devnet-faucet', 'faucet', 'HTTP_REST', 'https://faucet.devnet.aptoslabs.com', 'NONE', NULL, NULL, NULL, NULL, 10, true, NULL, 'Aptos devnet faucet', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', NULL, NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (10, 'SUI', 'testnet', 'dev', 'sui-testnet-public', 'rpc', 'GRPC', 'fullnode.testnet.sui.io:443', 'NONE', NULL, NULL, NULL, NULL, 10, true, NULL, 'Sui testnet gRPC fullnode', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', NULL, NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (11, 'SUI', 'testnet', 'dev', 'sui-testnet-faucet', 'faucet', 'HTTP_REST', 'https://faucet.testnet.sui.io/v2/gas', 'NONE', NULL, NULL, NULL, NULL, 10, true, NULL, 'Sui testnet faucet', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', NULL, NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (12, 'ETH', 'sepolia', 'dev', 'publicnode-eth-sepolia', 'rpc', 'HTTP_JSON_RPC', 'https://ethereum-sepolia-rpc.publicnode.com', 'NONE', NULL, NULL, NULL, NULL, 10, true, NULL, 'Ethereum Sepolia public RPC', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', NULL, NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (13, 'BNB', 'testnet', 'dev', 'publicnode-bnb-testnet', 'rpc', 'HTTP_JSON_RPC', 'https://bsc-testnet-rpc.publicnode.com', 'NONE', NULL, NULL, NULL, NULL, 10, true, NULL, 'BNB Chain testnet public RPC', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', NULL, NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (14, 'POLYGON', 'amoy', 'dev', 'publicnode-polygon-amoy', 'rpc', 'HTTP_JSON_RPC', 'https://polygon-amoy-bor-rpc.publicnode.com', 'NONE', NULL, NULL, NULL, NULL, 10, true, NULL, 'Polygon Amoy public RPC', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', NULL, NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (15, 'ARBITRUM', 'sepolia', 'dev', 'publicnode-arbitrum-sepolia', 'rpc', 'HTTP_JSON_RPC', 'https://arbitrum-sepolia-rpc.publicnode.com', 'NONE', NULL, NULL, NULL, NULL, 10, true, NULL, 'Arbitrum Sepolia public RPC', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', NULL, NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (16, 'OPTIMISM', 'sepolia', 'dev', 'publicnode-optimism-sepolia', 'rpc', 'HTTP_JSON_RPC', 'https://optimism-sepolia-rpc.publicnode.com', 'NONE', NULL, NULL, NULL, NULL, 10, true, NULL, 'Optimism Sepolia public RPC', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', NULL, NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (17, 'BASE', 'sepolia', 'dev', 'publicnode-base-sepolia', 'rpc', 'HTTP_JSON_RPC', 'https://base-sepolia-rpc.publicnode.com', 'NONE', NULL, NULL, NULL, NULL, 10, true, NULL, 'Base Sepolia public RPC', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', NULL, NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (18, 'AVAX_C', 'fuji', 'dev', 'publicnode-avax-fuji', 'rpc', 'HTTP_JSON_RPC', 'https://avalanche-fuji-c-chain-rpc.publicnode.com', 'NONE', NULL, NULL, NULL, NULL, 10, true, NULL, 'Avalanche Fuji C-Chain public RPC', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', NULL, NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (19, 'TRON', 'nile', 'dev', 'trongrid-nile-fullnode', 'rpc', 'GRPC', 'grpc.nile.trongrid.io:50051', 'API_KEY_OPTIONAL', NULL, NULL, NULL, NULL, 10, true, NULL, 'TRON Nile fullnode gRPC', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', NULL, NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (20, 'TRON', 'nile', 'dev', 'trongrid-nile-solidity', 'solidity', 'GRPC', 'grpc.nile.trongrid.io:50061', 'API_KEY_OPTIONAL', NULL, NULL, NULL, NULL, 20, true, NULL, 'TRON Nile solidity gRPC', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', NULL, NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (21, 'TRON', 'nile', 'dev', 'trongrid-nile-event', 'event', 'HTTP_JSON', 'https://nile.trongrid.io', 'API_KEY_OPTIONAL', NULL, NULL, NULL, NULL, 30, true, NULL, 'TRON Nile event server', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', NULL, NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (22, 'ETH', 'mainnet', 'prod', 'publicnode-eth-mainnet', 'rpc', 'HTTP_JSON_RPC', 'https://ethereum-rpc.publicnode.com', 'NONE', NULL, NULL, NULL, NULL, 10, true, NULL, 'Ethereum mainnet public RPC', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', NULL, NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (23, 'BNB', 'mainnet', 'prod', 'publicnode-bnb-mainnet', 'rpc', 'HTTP_JSON_RPC', 'https://bsc-rpc.publicnode.com', 'NONE', NULL, NULL, NULL, NULL, 10, true, NULL, 'BNB Chain mainnet public RPC', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', NULL, NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (24, 'POLYGON', 'mainnet', 'prod', 'publicnode-polygon-mainnet', 'rpc', 'HTTP_JSON_RPC', 'https://polygon-bor-rpc.publicnode.com', 'NONE', NULL, NULL, NULL, NULL, 10, true, NULL, 'Polygon mainnet public RPC', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', NULL, NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (25, 'ARBITRUM', 'mainnet', 'prod', 'publicnode-arbitrum-mainnet', 'rpc', 'HTTP_JSON_RPC', 'https://arbitrum-one-rpc.publicnode.com', 'NONE', NULL, NULL, NULL, NULL, 10, true, NULL, 'Arbitrum One public RPC', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', NULL, NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (26, 'OPTIMISM', 'mainnet', 'prod', 'publicnode-optimism-mainnet', 'rpc', 'HTTP_JSON_RPC', 'https://optimism-rpc.publicnode.com', 'NONE', NULL, NULL, NULL, NULL, 10, true, NULL, 'Optimism mainnet public RPC', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', NULL, NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (27, 'BASE', 'mainnet', 'prod', 'publicnode-base-mainnet', 'rpc', 'HTTP_JSON_RPC', 'https://base-rpc.publicnode.com', 'NONE', NULL, NULL, NULL, NULL, 10, true, NULL, 'Base mainnet public RPC', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', NULL, NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (28, 'AVAX_C', 'mainnet', 'prod', 'publicnode-avax-mainnet', 'rpc', 'HTTP_JSON_RPC', 'https://avalanche-c-chain-rpc.publicnode.com', 'NONE', NULL, NULL, NULL, NULL, 10, true, NULL, 'Avalanche C-Chain mainnet public RPC', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', NULL, NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (29, 'TRON', 'mainnet', 'prod', 'trongrid-mainnet-fullnode', 'rpc', 'GRPC', 'grpc.trongrid.io:50051', 'API_KEY_OPTIONAL', NULL, NULL, NULL, NULL, 10, true, NULL, 'TRON mainnet fullnode gRPC', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', NULL, NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (30, 'TRON', 'mainnet', 'prod', 'trongrid-mainnet-solidity', 'solidity', 'GRPC', 'grpc.trongrid.io:50052', 'API_KEY_OPTIONAL', NULL, NULL, NULL, NULL, 20, true, NULL, 'TRON mainnet solidity gRPC', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', NULL, NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (31, 'TRON', 'mainnet', 'prod', 'trongrid-mainnet-event', 'event', 'HTTP_JSON', 'https://api.trongrid.io', 'API_KEY_OPTIONAL', NULL, NULL, NULL, NULL, 30, true, NULL, 'TRON mainnet event API', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', NULL, NULL, NULL);

-- Non-regtest test RPC defaults prefer official/recommended test endpoints.
UPDATE "public"."chain_profile" SET "enabled" = false, "scan_enabled" = false, "withdraw_enabled" = false, "collection_enabled" = false, "transfer_enabled" = false, "updated_at" = "now"() WHERE "id" = 40;
UPDATE "public"."chain_profile" SET "enabled" = true, "scan_enabled" = true, "withdraw_enabled" = true, "collection_enabled" = true, "transfer_enabled" = true, "gas_policy" = 'aptos', "updated_at" = "now"() WHERE "id" = 41;
UPDATE "public"."chain_profile" SET "rpc_url" = 'https://bsc-testnet.bnbchain.org', "updated_at" = "now"() WHERE "id" = 139;
UPDATE "public"."chain_profile" SET "rpc_url" = 'https://polygon-amoy.drpc.org', "updated_at" = "now"() WHERE "id" = 141;
UPDATE "public"."chain_profile" SET "rpc_url" = 'https://sepolia-rollup.arbitrum.io/rpc', "updated_at" = "now"() WHERE "id" = 143;
UPDATE "public"."chain_profile" SET "rpc_url" = 'https://sepolia.optimism.io', "updated_at" = "now"() WHERE "id" = 145;
UPDATE "public"."chain_profile" SET "rpc_url" = 'https://sepolia.base.org', "updated_at" = "now"() WHERE "id" = 147;
UPDATE "public"."chain_profile" SET "rpc_url" = 'https://api.avax-test.network/ext/bc/C/rpc', "updated_at" = "now"() WHERE "id" = 149;

UPDATE "public"."chain_rpc_node" SET "min_request_interval_ms" = 1000 WHERE "chain" IN ('BTC', 'LTC', 'DOGE', 'BCH') AND "environment" = 'dev';
UPDATE "public"."chain_rpc_node" SET "remark" = 'Solana Labs Devnet public RPC; official public endpoint with rate limits.', "min_request_interval_ms" = 300 WHERE "id" = 5;
UPDATE "public"."chain_rpc_node" SET "auth_header_name" = 'X-API-Key', "api_key_ref" = NULL, "remark" = 'TON Center testnet API; no-key is 1 rps, free key allows higher throughput.', "min_request_interval_ms" = 100 WHERE "id" = 6;
UPDATE "public"."chain_rpc_node" SET "auth_type" = 'bearer', "auth_header_name" = 'Authorization', "api_key_ref" = NULL, "remark" = 'TON API testnet indexer; add a TonConsole key before reducing the interval.', "min_request_interval_ms" = 4100 WHERE "id" = 7;
UPDATE "public"."chain_rpc_node" SET "network" = 'testnet', "node_label" = 'aptos-testnet-official', "rpc_url" = 'https://fullnode.testnet.aptoslabs.com/v1', "remark" = 'Aptos Labs testnet fullnode; stable integration network, limited per IP.', "min_request_interval_ms" = 250 WHERE "id" = 8;
UPDATE "public"."chain_rpc_node" SET "network" = 'testnet', "node_label" = 'aptos-testnet-mint-page', "connection_type" = 'HTTP_LINK', "rpc_url" = 'https://aptos.dev/network/faucet', "enabled" = false, "remark" = 'Aptos testnet has no programmatic faucet; pre-fund the system faucet or hot wallet manually.', "min_request_interval_ms" = 0 WHERE "id" = 9;
UPDATE "public"."chain_rpc_node" SET "node_label" = 'sui-testnet-official', "remark" = 'Sui public testnet JSON-RPC; rate limited and scheduled for migration away from public JSON-RPC.', "min_request_interval_ms" = 350 WHERE "id" = 10;
UPDATE "public"."chain_rpc_node" SET "remark" = 'Sui testnet faucet API.', "min_request_interval_ms" = 1000 WHERE "id" = 11;
UPDATE "public"."chain_rpc_node" SET "remark" = 'Ethereum Sepolia has no Ethereum Foundation hosted public JSON-RPC; PublicNode is the default external test endpoint.', "min_request_interval_ms" = 500 WHERE "id" = 12;
UPDATE "public"."chain_rpc_node" SET "node_label" = 'bnbchain-testnet-official', "rpc_url" = 'https://bsc-testnet.bnbchain.org', "remark" = 'Official BNB Chain testnet RPC; documented public rate limit applies.', "min_request_interval_ms" = 100 WHERE "id" = 13;
UPDATE "public"."chain_rpc_node" SET "node_label" = 'polygon-amoy-official-drpc', "rpc_url" = 'https://polygon-amoy.drpc.org', "remark" = 'Polygon official docs Amoy endpoint via dRPC.', "min_request_interval_ms" = 250 WHERE "id" = 14;
UPDATE "public"."chain_rpc_node" SET "node_label" = 'arbitrum-sepolia-official', "rpc_url" = 'https://sepolia-rollup.arbitrum.io/rpc', "remark" = 'Official Arbitrum Sepolia RPC; no uptime, latency or rate-limit guarantees.', "min_request_interval_ms" = 250 WHERE "id" = 15;
UPDATE "public"."chain_rpc_node" SET "node_label" = 'optimism-sepolia-official', "rpc_url" = 'https://sepolia.optimism.io', "remark" = 'Official OP Sepolia public RPC; rate limited and no WebSocket.', "min_request_interval_ms" = 250 WHERE "id" = 16;
UPDATE "public"."chain_rpc_node" SET "node_label" = 'base-sepolia-official', "rpc_url" = 'https://sepolia.base.org', "remark" = 'Official Base Sepolia public RPC; rate limited and not for production traffic.', "min_request_interval_ms" = 250 WHERE "id" = 17;
UPDATE "public"."chain_rpc_node" SET "node_label" = 'avalanche-fuji-official', "rpc_url" = 'https://api.avax-test.network/ext/bc/C/rpc', "remark" = 'Official Avalanche Fuji C-Chain public RPC.', "min_request_interval_ms" = 250 WHERE "id" = 18;
UPDATE "public"."chain_rpc_node" SET "auth_header_name" = 'TRON-PRO-API-KEY', "api_key_ref" = NULL, "remark" = 'Official TRON Nile fullnode gRPC.', "min_request_interval_ms" = 250 WHERE "id" = 19;
UPDATE "public"."chain_rpc_node" SET "auth_header_name" = 'TRON-PRO-API-KEY', "api_key_ref" = NULL, "remark" = 'Official TRON Nile solidity gRPC.', "min_request_interval_ms" = 250 WHERE "id" = 20;
UPDATE "public"."chain_rpc_node" SET "auth_header_name" = 'TRON-PRO-API-KEY', "api_key_ref" = NULL, "remark" = 'Official TRON Nile event server.', "min_request_interval_ms" = 250 WHERE "id" = 21;
UPDATE "public"."chain_rpc_node" SET "min_request_interval_ms" = 500 WHERE "environment" = 'prod' AND "chain" IN ('ETH', 'BNB', 'POLYGON', 'ARBITRUM', 'OPTIMISM', 'BASE', 'AVAX_C', 'TRON');

INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "min_request_interval_ms", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (32, 'BNB', 'testnet', 'dev', 'publicnode-bnb-testnet', 'rpc', 'HTTP_JSON_RPC', 'https://bsc-testnet-rpc.publicnode.com', 'NONE', NULL, NULL, NULL, NULL, 50, 500, true, NULL, 'PublicNode backup for BNB Chain testnet.', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', NULL, NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "min_request_interval_ms", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (33, 'POLYGON', 'amoy', 'dev', 'publicnode-polygon-amoy', 'rpc', 'HTTP_JSON_RPC', 'https://polygon-amoy-bor-rpc.publicnode.com', 'NONE', NULL, NULL, NULL, NULL, 50, 500, true, NULL, 'PublicNode backup for Polygon Amoy.', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', NULL, NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "min_request_interval_ms", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (34, 'ARBITRUM', 'sepolia', 'dev', 'publicnode-arbitrum-sepolia', 'rpc', 'HTTP_JSON_RPC', 'https://arbitrum-sepolia-rpc.publicnode.com', 'NONE', NULL, NULL, NULL, NULL, 50, 500, true, NULL, 'PublicNode backup for Arbitrum Sepolia.', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', NULL, NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "min_request_interval_ms", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (35, 'OPTIMISM', 'sepolia', 'dev', 'publicnode-optimism-sepolia', 'rpc', 'HTTP_JSON_RPC', 'https://optimism-sepolia-rpc.publicnode.com', 'NONE', NULL, NULL, NULL, NULL, 50, 500, true, NULL, 'PublicNode backup for OP Sepolia.', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', NULL, NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "min_request_interval_ms", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (36, 'BASE', 'sepolia', 'dev', 'publicnode-base-sepolia', 'rpc', 'HTTP_JSON_RPC', 'https://base-sepolia-rpc.publicnode.com', 'NONE', NULL, NULL, NULL, NULL, 50, 500, true, NULL, 'PublicNode backup for Base Sepolia.', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', NULL, NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "min_request_interval_ms", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (37, 'AVAX_C', 'fuji', 'dev', 'publicnode-avax-fuji', 'rpc', 'HTTP_JSON_RPC', 'https://avalanche-fuji-c-chain-rpc.publicnode.com', 'NONE', NULL, NULL, NULL, NULL, 50, 500, true, NULL, 'PublicNode backup for Avalanche Fuji C-Chain.', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', NULL, NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "min_request_interval_ms", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (39, 'TRON', 'nile', 'dev', 'trongrid-nile-jsonrpc', 'jsonrpc', 'HTTP_JSON_RPC', 'https://nile.trongrid.io/jsonrpc/', 'API_KEY_OPTIONAL', 'TRON-PRO-API-KEY', NULL, NULL, NULL, 40, 250, true, NULL, 'Official TRON Nile JSON-RPC endpoint for tools that need JSON-RPC.', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', NULL, NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "min_request_interval_ms", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (40, 'XRP', 'testnet', 'dev', 'xrpl-testnet-official', 'rpc', 'HTTP_JSON_RPC', 'https://s.altnet.rippletest.net:51234/', 'NONE', NULL, NULL, NULL, NULL, 10, 250, true, NULL, 'Official XRP Ledger testnet JSON-RPC public server.', '2026-06-27 12:10:00+08', '2026-06-27 12:10:00+08', NULL, NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "min_request_interval_ms", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (41, 'XRP', 'mainnet', 'prod', 'xrpl-mainnet-official', 'rpc', 'HTTP_JSON_RPC', 'https://s1.ripple.com:51234/', 'NONE', NULL, NULL, NULL, NULL, 10, 250, true, NULL, 'Official XRP Ledger mainnet JSON-RPC public server; profile is disabled by default.', '2026-06-27 12:10:00+08', '2026-06-27 12:10:00+08', NULL, NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "min_request_interval_ms", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (42, 'XRP', 'testnet', 'dev', 'drpc-xrp-testnet', 'rpc', 'HTTP_JSON_RPC', 'https://ripple-testnet.drpc.org', 'NONE', NULL, NULL, NULL, NULL, 20, 500, true, NULL, 'dRPC XRP Ledger testnet backup endpoint.', '2026-06-27 12:10:00+08', '2026-06-27 12:10:00+08', NULL, NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "min_request_interval_ms", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (43, 'XRP', 'testnet', 'dev', 'tatum-xrp-testnet', 'rpc', 'HTTP_JSON_RPC', 'https://ripple-testnet.gateway.tatum.io', 'NONE', NULL, NULL, NULL, NULL, 30, 500, true, NULL, 'Tatum XRP Ledger testnet backup endpoint.', '2026-06-27 12:10:00+08', '2026-06-27 12:10:00+08', NULL, NULL, NULL);

-- Dedicated test2 RPC nodes. Runtime lookup is environment-exact: test2 never reads dev/prod nodes.
-- Provider keys are stored directly in chain_rpc_node.api_key or in the stored rpc_url. Replace CHANGE_ME_* values in DB before use.
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "min_request_interval_ms", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password")
SELECT "id" + 100, "chain", "network", 'test2', "node_label" || '-dev-copy', "purpose", "connection_type", "rpc_url",
       "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref",
       "priority" + 100, "min_request_interval_ms", "enabled", "renewal_due_at",
       'test2 fallback copied from dev. ' || COALESCE("remark", ''),
       "created_at", "updated_at", "api_key", "username", "password"
FROM "public"."chain_rpc_node"
WHERE "environment" = 'dev' AND "enabled" = true;

INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "min_request_interval_ms", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (200, 'ETH', 'sepolia', 'test2', 'alchemy-eth-sepolia', 'rpc', 'HTTP_JSON_RPC', 'https://eth-sepolia.g.alchemy.com/v2/CHANGE_ME_ALCHEMY_ETH_SEPOLIA_KEY', 'NONE', NULL, NULL, NULL, NULL, 5, 100, true, NULL, 'test2 Alchemy Ethereum Sepolia RPC.', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', NULL, NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "min_request_interval_ms", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (201, 'ETH', 'sepolia', 'test2', 'drpc-eth-sepolia', 'rpc', 'HTTP_JSON_RPC', 'https://lb.drpc.live/sepolia/CHANGE_ME_DRPC_ETH_SEPOLIA_KEY', 'NONE', NULL, NULL, NULL, NULL, 6, 100, true, NULL, 'test2 dRPC Ethereum Sepolia RPC.', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', NULL, NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "min_request_interval_ms", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (202, 'ETH', 'sepolia', 'test2', 'infura-eth-sepolia', 'rpc', 'HTTP_JSON_RPC', 'https://sepolia.infura.io/v3/CHANGE_ME_INFURA_API_KEY', 'NONE', NULL, NULL, NULL, NULL, 7, 100, true, NULL, 'test2 Infura Ethereum Sepolia RPC.', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', NULL, NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "min_request_interval_ms", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (203, 'BNB', 'testnet', 'test2', 'infura-bnb-testnet', 'rpc', 'HTTP_JSON_RPC', 'https://bsc-testnet.infura.io/v3/CHANGE_ME_INFURA_API_KEY', 'NONE', NULL, NULL, NULL, NULL, 5, 100, true, NULL, 'test2 Infura BNB Chain testnet RPC.', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', NULL, NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "min_request_interval_ms", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (204, 'POLYGON', 'amoy', 'test2', 'infura-polygon-amoy', 'rpc', 'HTTP_JSON_RPC', 'https://polygon-amoy.infura.io/v3/CHANGE_ME_INFURA_API_KEY', 'NONE', NULL, NULL, NULL, NULL, 5, 100, true, NULL, 'test2 Infura Polygon Amoy RPC.', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', NULL, NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "min_request_interval_ms", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (205, 'BASE', 'sepolia', 'test2', 'infura-base-sepolia', 'rpc', 'HTTP_JSON_RPC', 'https://base-sepolia.infura.io/v3/CHANGE_ME_INFURA_API_KEY', 'NONE', NULL, NULL, NULL, NULL, 5, 100, true, NULL, 'test2 Infura Base Sepolia RPC.', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', NULL, NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "min_request_interval_ms", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (206, 'OPTIMISM', 'sepolia', 'test2', 'infura-optimism-sepolia', 'rpc', 'HTTP_JSON_RPC', 'https://optimism-sepolia.infura.io/v3/CHANGE_ME_INFURA_API_KEY', 'NONE', NULL, NULL, NULL, NULL, 5, 100, true, NULL, 'test2 Infura OP Sepolia RPC.', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', NULL, NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "min_request_interval_ms", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (207, 'ARBITRUM', 'sepolia', 'test2', 'infura-arbitrum-sepolia', 'rpc', 'HTTP_JSON_RPC', 'https://arbitrum-sepolia.infura.io/v3/CHANGE_ME_INFURA_API_KEY', 'NONE', NULL, NULL, NULL, NULL, 5, 100, true, NULL, 'test2 Infura Arbitrum Sepolia RPC.', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', NULL, NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "min_request_interval_ms", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (208, 'AVAX_C', 'fuji', 'test2', 'infura-avalanche-fuji', 'rpc', 'HTTP_JSON_RPC', 'https://avalanche-fuji.infura.io/v3/CHANGE_ME_INFURA_API_KEY', 'NONE', NULL, NULL, NULL, NULL, 5, 100, true, NULL, 'test2 Infura Avalanche Fuji RPC.', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', NULL, NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "min_request_interval_ms", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (209, 'SOLANA', 'devnet', 'test2', 'infura-solana-devnet', 'rpc', 'HTTP_JSON_RPC', 'https://solana-devnet.infura.io/v3/CHANGE_ME_INFURA_API_KEY', 'NONE', NULL, NULL, NULL, NULL, 5, 100, true, NULL, 'test2 Infura Solana Devnet RPC.', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', NULL, NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "min_request_interval_ms", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (210, 'BTC', 'testnet3', 'test2', 'getblock-btc-testnet', 'rpc', 'HTTP_JSON_RPC', 'https://go.getblock.io/CHANGE_ME_GETBLOCK_BTC_TESTNET_TOKEN', 'NONE', NULL, NULL, NULL, NULL, 5, 50, true, NULL, 'test2 GetBlock BTC testnet RPC.', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', NULL, NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "min_request_interval_ms", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (211, 'TON', 'testnet', 'test2', 'toncenter-testnet-keyed', 'rpc', 'HTTP_JSON', 'https://testnet.toncenter.com/api/v2', 'API_KEY_OPTIONAL', 'X-API-Key', NULL, NULL, NULL, 5, 100, true, NULL, 'test2 TON Center keyed testnet API.', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', 'CHANGE_ME_TONCENTER_TESTNET_API_KEY', NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "min_request_interval_ms", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (212, 'TON', 'testnet', 'test2', 'tonapi-testnet-keyed', 'indexer', 'HTTP_JSON', 'https://testnet.tonapi.io', 'bearer', 'Authorization', NULL, NULL, NULL, 5, 500, true, NULL, 'test2 TON API keyed testnet indexer.', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', NULL, NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "min_request_interval_ms", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (213, 'TRON', 'nile', 'test2', 'trongrid-nile-fullnode-keyed', 'rpc', 'GRPC', 'grpc.nile.trongrid.io:50051', 'API_KEY_OPTIONAL', 'TRON-PRO-API-KEY', NULL, NULL, NULL, 5, 250, true, NULL, 'test2 TRON Nile fullnode gRPC with API key.', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', 'CHANGE_ME_TRONGRID_NILE_API_KEY', NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "min_request_interval_ms", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (214, 'TRON', 'nile', 'test2', 'trongrid-nile-solidity-keyed', 'solidity', 'GRPC', 'grpc.nile.trongrid.io:50061', 'API_KEY_OPTIONAL', 'TRON-PRO-API-KEY', NULL, NULL, NULL, 5, 250, true, NULL, 'test2 TRON Nile solidity gRPC with API key.', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', 'CHANGE_ME_TRONGRID_NILE_API_KEY', NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "min_request_interval_ms", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (215, 'TRON', 'nile', 'test2', 'trongrid-nile-event-keyed', 'event', 'HTTP_JSON', 'https://nile.trongrid.io', 'API_KEY_OPTIONAL', 'TRON-PRO-API-KEY', NULL, NULL, NULL, 5, 250, true, NULL, 'test2 TRON Nile event API with API key.', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', 'CHANGE_ME_TRONGRID_NILE_API_KEY', NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "min_request_interval_ms", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (216, 'TRON', 'nile', 'test2', 'trongrid-nile-jsonrpc-keyed', 'jsonrpc', 'HTTP_JSON_RPC', 'https://nile.trongrid.io/jsonrpc/', 'API_KEY_OPTIONAL', 'TRON-PRO-API-KEY', NULL, NULL, NULL, 5, 250, true, NULL, 'test2 TRON Nile JSON-RPC with API key.', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', 'CHANGE_ME_TRONGRID_NILE_API_KEY', NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "min_request_interval_ms", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (217, 'SUI', 'testnet', 'test2', 'sui-testnet-official', 'rpc', 'GRPC', 'fullnode.testnet.sui.io:443', 'NONE', NULL, NULL, NULL, NULL, 5, 350, true, NULL, 'test2 Sui official testnet gRPC.', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', NULL, NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "min_request_interval_ms", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (221, 'ETH', 'sepolia', 'test2', 'nownodes-eth-sepolia', 'rpc', 'HTTP_JSON_RPC', 'https://eth-sepolia.nownodes.io/CHANGE_ME_NOWNODES_API_KEY', 'NONE', NULL, NULL, NULL, NULL, 8, 1000, true, NULL, 'test2 NOWNodes Ethereum Sepolia RPC backup. Replace CHANGE_ME_NOWNODES_API_KEY in DB before use.', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', NULL, NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "min_request_interval_ms", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (222, 'BTC', 'testnet3', 'test2', 'nownodes-btc-testnet', 'rpc', 'HTTP_JSON_RPC', 'https://btc-testnet.nownodes.io/CHANGE_ME_NOWNODES_API_KEY', 'NONE', NULL, NULL, NULL, NULL, 6, 1000, true, NULL, 'test2 NOWNodes Bitcoin testnet RPC backup. Replace CHANGE_ME_NOWNODES_API_KEY in DB before use.', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', NULL, NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "min_request_interval_ms", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (223, 'LTC', 'testnet', 'test2', 'nownodes-ltc-testnet', 'rpc', 'HTTP_JSON_RPC', 'https://ltc-testnet.nownodes.io/CHANGE_ME_NOWNODES_API_KEY', 'NONE', NULL, NULL, NULL, NULL, 5, 1000, true, NULL, 'test2 NOWNodes Litecoin testnet JSON-RPC. Replace CHANGE_ME_NOWNODES_API_KEY in DB before use.', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', NULL, NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "min_request_interval_ms", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (224, 'DOGE', 'testnet', 'test2', 'nownodes-doge-testnet', 'rpc', 'HTTP_JSON_RPC', 'https://doge-testnet.nownodes.io/CHANGE_ME_NOWNODES_API_KEY', 'NONE', NULL, NULL, NULL, NULL, 5, 1000, true, NULL, 'test2 NOWNodes Dogecoin testnet RPC. Replace CHANGE_ME_NOWNODES_API_KEY in DB before use. BCH testnet endpoint was unavailable, so BCH is not added to test2.', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', NULL, NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "min_request_interval_ms", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (225, 'NEAR', 'testnet', 'dev', 'near-testnet-official', 'rpc', 'HTTP_JSON_RPC', 'https://rpc.testnet.near.org', 'NONE', NULL, NULL, NULL, NULL, 10, 500, true, NULL, 'NEAR official testnet JSON-RPC. Enable the matching profile only when native and NEP-141 test funds are ready.', '2026-06-28 10:45:00+08', '2026-06-28 10:45:00+08', NULL, NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "min_request_interval_ms", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (226, 'NEAR', 'testnet', 'test2', 'near-testnet-official', 'rpc', 'HTTP_JSON_RPC', 'https://rpc.testnet.near.org', 'NONE', NULL, NULL, NULL, NULL, 10, 500, true, NULL, 'test2 NEAR official testnet JSON-RPC. Enable the matching profile only when native and NEP-141 test funds are ready.', '2026-06-28 10:45:00+08', '2026-06-28 10:45:00+08', NULL, NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "min_request_interval_ms", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (227, 'NEAR', 'mainnet', 'prod', 'near-mainnet-official', 'rpc', 'HTTP_JSON_RPC', 'https://rpc.mainnet.near.org', 'NONE', NULL, NULL, NULL, NULL, 10, 500, true, NULL, 'NEAR official mainnet JSON-RPC. Enable the matching profile only after production risk review and monitoring are ready.', '2026-06-28 10:45:00+08', '2026-06-28 10:45:00+08', NULL, NULL, NULL);

-- Canonical token configuration: explicit networks and one value for each setting.
-- Official issuer references retained from the baseline (checked 2026-07-21):
-- Circle USDC: https://developers.circle.com/stablecoins/usdc-contract-addresses
-- Tether USDt: https://tether.to/en/supported-protocols/
-- USDT0 deployments: https://docs.usdt0.to/technical-documentation/deployments
-- Mainnet token entries remain disabled until the matching chain, audited RPC nodes
-- and wallet tasks are deliberately enabled.
INSERT INTO public.token_config (id, chain, network, symbol, standard, contract_address, contract_address_base58, contract_address_hex, decimals, enabled, collect_enabled, min_deposit, min_withdraw, collect_threshold, gas_strategy, confirmation_required, created_at, updated_at) VALUES
    (1, 'ETH', 'sepolia', 'USDT', 'ERC20', '0x01a6810727db185bbf7f30ec158c3ac8b8112627', NULL, NULL, 6, true, true, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'native-gas', 1, '2026-06-20T18:23:33.141962+08:00', '2026-10-24T04:42:57.407759+08:00'),
    (2, 'ETH', 'sepolia', 'USDC', 'ERC20', '0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238', NULL, NULL, 6, true, true, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'native-gas', 1, '2026-06-20T18:23:33.151844+08:00', '2026-10-24T04:42:57.407759+08:00'),
    (7, 'BNB', 'testnet', 'USDT', 'ERC20', '0xE8709025f99dd1B8533FB9b78Ca879Ee4ec7E70a', NULL, NULL, 6, true, true, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'native-gas', 1, '2026-06-20T18:34:35.29862+08:00', '2026-10-24T04:42:57.407759+08:00'),
    (8, 'BNB', 'testnet', 'USDC', 'ERC20', '0x31873b5804bABE258d6ea008f55e08DD00b7d51E', NULL, NULL, 6, true, true, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'native-gas', 1, '2026-06-20T18:34:35.301957+08:00', '2026-10-24T04:42:57.407759+08:00'),
    (17, 'POLYGON', 'amoy', 'USDT', 'ERC20', '0xb5F6211f94FCC162D5c8cebba4f656c965577392', NULL, NULL, 6, true, true, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'native-gas', 1, '2026-06-20T18:38:52.367548+08:00', '2026-10-24T04:42:57.407759+08:00'),
    (18, 'POLYGON', 'amoy', 'USDC', 'ERC20', '0x41E94Eb019C0762f9Bfcf9Fb1E58725BfB0e7582', NULL, NULL, 6, true, true, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'native-gas', 1, '2026-06-20T18:38:52.370655+08:00', '2026-10-24T04:42:57.407759+08:00'),
    (19, 'ARBITRUM', 'sepolia', 'USDT', 'ERC20', '0xEf54C221Fc94517877F0F40eCd71E0A3866D66C2', NULL, NULL, 6, true, true, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'native-gas', 1, '2026-06-20T18:39:07.857094+08:00', '2026-10-24T04:42:57.407759+08:00'),
    (20, 'ARBITRUM', 'sepolia', 'USDC', 'ERC20', '0x75faf114eafb1BDbe2F0316DF893fd58CE46AA4d', NULL, NULL, 6, true, true, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'native-gas', 1, '2026-06-20T18:39:07.860093+08:00', '2026-10-24T04:42:57.407759+08:00'),
    (21, 'OPTIMISM', 'sepolia', 'USDT', 'ERC20', '0xcb4CB0127B079d42b81F53e747b763d206D050f9', NULL, NULL, 6, true, true, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'native-gas', 1, '2026-06-20T18:39:23.204371+08:00', '2026-10-24T04:42:57.407759+08:00'),
    (22, 'OPTIMISM', 'sepolia', 'USDC', 'ERC20', '0x5fd84259d66Cd46123540766Be93DFE6D43130D7', NULL, NULL, 6, true, true, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'native-gas', 1, '2026-06-20T18:39:23.207534+08:00', '2026-10-24T04:42:57.407759+08:00'),
    (23, 'BASE', 'sepolia', 'USDT', 'ERC20', '0x210BBd033630e5e611B7922D70b0Caabe64636d9', NULL, NULL, 6, true, true, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'native-gas', 1, '2026-06-20T18:39:38.653475+08:00', '2026-10-24T04:42:57.407759+08:00'),
    (24, 'BASE', 'sepolia', 'USDC', 'ERC20', '0x036CbD53842c5426634e7929541eC2318f3dCF7e', NULL, NULL, 6, true, true, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'native-gas', 1, '2026-06-20T18:39:38.656754+08:00', '2026-10-24T04:42:57.407759+08:00'),
    (25, 'AVAX_C', 'fuji', 'USDT', 'ERC20', '0x1B43cbC6879C8237469794F9B8Ed290810e502d9', NULL, NULL, 6, true, true, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'native-gas', 1, '2026-06-20T18:39:54.422415+08:00', '2026-10-24T04:42:57.407759+08:00'),
    (26, 'AVAX_C', 'fuji', 'USDC', 'ERC20', '0x5425890298aed601595a70AB815c96711a31Bc65', NULL, NULL, 6, true, true, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'native-gas', 1, '2026-06-20T18:39:54.425852+08:00', '2026-10-24T04:42:57.407759+08:00'),
    (75, 'TRON', 'nile', 'USDT', 'TRC20', 'TXYZopYRdj2D9XRtbG411XZZ3kM5VkAeBf', 'TXYZopYRdj2D9XRtbG411XZZ3kM5VkAeBf', '41eca9bc828a3005b9a3b909f2cc5c2a54794de05f', 6, true, true, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'energy-bandwidth', 1, '2026-06-21T00:25:35.727551+08:00', '2026-10-24T04:42:57.407759+08:00'),
    (78, 'SOLANA', 'devnet', 'USDT', 'SPL', '2VHdVaW1jBGmvU1PdQpvmPnWV5AiAxrmxmUTLvf8951y', '2VHdVaW1jBGmvU1PdQpvmPnWV5AiAxrmxmUTLvf8951y', NULL, 6, true, true, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'SOL_FEE_PAYER', 1, '2026-06-23T15:01:18.893717+08:00', '2026-10-24T04:42:57.407759+08:00'),
    (79, 'SOLANA', 'devnet', 'USDC', 'SPL', '4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU', '4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU', NULL, 6, true, true, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'SOL_FEE_PAYER', 1, '2026-06-23T15:01:43.398652+08:00', '2026-10-24T04:42:57.407759+08:00'),
    (82, 'TON', 'testnet', 'USDT', 'JETTON', 'kQCZ5SAA78W_0vA5eSoU23YomxnUwah3KYagqeesNQI5jOXT', NULL, NULL, 6, true, true, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'TON_FORWARD_FEE', 1, '2026-06-23T21:01:38.590818+08:00', '2026-10-24T04:42:57.407759+08:00'),
    (83, 'TON', 'testnet', 'USDC', 'JETTON', 'kQCzPT6908-8TR862TQo1S43-2kEme8UKRCRSWkaxNLD7H_2', NULL, NULL, 6, true, true, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'TON_FORWARD_FEE', 1, '2026-06-23T21:01:38.590818+08:00', '2026-10-24T04:42:57.407759+08:00'),
    (98, 'SUI', 'testnet', 'TESTCOIN', 'COIN', '0x2::sui::SUI', NULL, NULL, 9, false, false, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'SUI_GAS_OBJECT', 1, '2026-06-23T23:21:21.43093+08:00', '2026-06-23T23:21:21.43093+08:00'),
    (114, 'APTOS', 'testnet', 'USDC', 'APTOS_FA', '0x69091fbab5f7d635ee7ac5098cf0c1efbe31d68fec0f2cd565e8d168daf52832', NULL, NULL, 6, true, true, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'APT_GAS', 1, '2026-10-24T04:42:57.407759+08:00', '2026-10-24T04:42:57.407759+08:00'),
    (115, 'APTOS', 'testnet', 'USDT', 'APTOS_FA', '0x6b015ce46a5d15c63c659ebf949c99c3d79dab462db4cf8fb8f609e45229e2cf', NULL, NULL, 6, true, true, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'APT_GAS', 1, '2026-10-24T04:42:57.407759+08:00', '2026-10-24T04:42:57.407759+08:00'),
    (116, 'SUI', 'testnet', 'USDC', 'COIN', '0xa1ec7fc00a6f40db9693ad1415d0c193ad3906494428cf252621037bd7117e29::usdc::USDC', NULL, NULL, 6, true, true, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'SUI_GAS_OBJECT', 1, '2026-10-24T04:42:57.407759+08:00', '2026-10-24T04:42:57.407759+08:00'),
    (118, 'XRP', 'testnet', 'USDC', 'ISSUED_CURRENCY', 'rHuGNhqTG32mfmAvWA8hUyWRLV3tCSwKQt:5553444300000000000000000000000000000000', NULL, '5553444300000000000000000000000000000000', 6, true, true, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'XRP_TRUSTLINE', 1, '2026-10-24T04:42:57.407759+08:00', '2026-10-24T04:42:57.407759+08:00'),
    (119, 'XRP', 'testnet', 'USDT', 'ISSUED_CURRENCY', 'rHuGNhqTG32mfmAvWA8hUyWRLV3tCSwKQt:5553445400000000000000000000000000000000', NULL, '5553445400000000000000000000000000000000', 6, true, true, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'XRP_TRUSTLINE', 1, '2026-10-24T04:42:57.407759+08:00', '2026-10-24T04:42:57.407759+08:00'),
    (130, 'HYPEREVM', 'testnet', 'USDC', 'ERC20', '0x2B3370eE501B4a559b57D449569354196457D8Ab', NULL, NULL, 6, true, true, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'native-gas', 1, '2026-10-24T04:42:57.416116+08:00', '2026-10-24T04:42:57.416116+08:00'),
    (131, 'LINEA', 'sepolia', 'USDC', 'ERC20', '0xFEce4462D57bD51A6A552365A011b95f0E16d9B7', NULL, NULL, 6, true, true, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'native-gas', 1, '2026-10-24T04:42:57.419002+08:00', '2026-10-24T04:42:57.419002+08:00'),
    (132, 'LINEA', 'mainnet', 'USDT', 'ERC20', '0xA219439258ca9da29E9Cc4cE5596924745e12B93', NULL, NULL, 6, false, false, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'native-gas', 1, '2026-10-24T04:42:57.419002+08:00', '2026-10-24T04:42:57.4354+08:00'),
    (133, 'SCROLL', 'sepolia', 'USDC', 'ERC20', '0x7878290DB8C4f02bd06E0E249617871c19508bE6', NULL, NULL, 6, true, true, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'native-gas', 1, '2026-10-24T04:42:57.419002+08:00', '2026-10-24T04:42:57.419002+08:00'),
    (134, 'SCROLL', 'mainnet', 'USDT', 'ERC20', '0xf55BEC9cafDbE8730f096Aa55dad6D22d44099Df', NULL, NULL, 6, false, false, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'native-gas', 1, '2026-10-24T04:42:57.419002+08:00', '2026-10-24T04:42:57.4354+08:00'),
    (135, 'UNICHAIN', 'sepolia', 'USDC', 'ERC20', '0x31d0220469e10c4E71834a79b1f276d740d3768F', NULL, NULL, 6, true, true, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'native-gas', 1, '2026-10-24T04:42:57.419002+08:00', '2026-10-24T04:42:57.419002+08:00'),
    (136, 'X_LAYER', 'mainnet', 'USDC', 'ERC20', '0x74b7F16337b8972027F6196A17a631aC6dE26d22', NULL, NULL, 6, false, false, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'native-gas', 1, '2026-10-24T04:42:57.419002+08:00', '2026-10-24T04:42:57.4354+08:00'),
    (137, 'X_LAYER', 'mainnet', 'USDT', 'ERC20', '0x1E4a5963aBFD975d8c9021ce480b42188849D41d', NULL, NULL, 6, false, false, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'native-gas', 1, '2026-10-24T04:42:57.419002+08:00', '2026-10-24T04:42:57.4354+08:00'),
    (138, 'ROBINHOOD_CHAIN', 'mainnet', 'USDG', 'ERC20', '0x5fc5360D0400a0Fd4f2af552ADD042D716F1d168', NULL, NULL, 6, false, true, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'native-gas', 1, '2026-10-24T04:42:57.419002+08:00', '2026-10-24T04:42:57.419002+08:00'),
    (139, 'HYPERCORE', 'testnet', 'HYPE', 'HIP1', '0x7317beb7cceed72ef0b346074cc8e7ab', NULL, NULL, 8, true, true, '0.000001000000000000', '0.000001000000000000', '0.000001000000000000', 'hypercore-no-gas', 1, '2026-10-24T04:42:57.422829+08:00', '2026-10-24T04:42:57.422829+08:00'),
    (140, 'ADA', 'preprod', 'USDC', 'CARDANO_NATIVE_ASSET', 'CHANGE_ME_CARDANO_USDC_POLICY_ID.CHANGE_ME_ASSET_NAME_HEX', NULL, NULL, 6, false, false, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'CARDANO_MIN_ADA', 15, '2026-10-24T04:42:57.426805+08:00', '2026-10-24T04:42:57.426805+08:00'),
    (141, 'ADA', 'preprod', 'USDT', 'CARDANO_NATIVE_ASSET', 'CHANGE_ME_CARDANO_USDT_POLICY_ID.CHANGE_ME_ASSET_NAME_HEX', NULL, NULL, 6, false, false, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'CARDANO_MIN_ADA', 15, '2026-10-24T04:42:57.426805+08:00', '2026-10-24T04:42:57.426805+08:00'),
    (142, 'DOT', 'westend', 'USDC', 'ASSET_HUB_ASSET', '31337', NULL, NULL, 6, false, false, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'ASSET_HUB_NATIVE_GAS', 12, '2026-10-24T04:42:57.426805+08:00', '2026-10-24T04:42:57.426805+08:00'),
    (143, 'DOT', 'westend', 'USDT', 'ASSET_HUB_ASSET', '1984', NULL, NULL, 6, false, false, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'ASSET_HUB_NATIVE_GAS', 12, '2026-10-24T04:42:57.426805+08:00', '2026-10-24T04:42:57.426805+08:00'),
    (144, 'NEAR', 'testnet', 'USDC', 'NEP141', '3e2210e1184b45b64c8a434c0a7e7b23cc04ea7eb7a6c3c32520d03d4afcb8af', NULL, NULL, 6, false, false, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'NEAR_STORAGE_DEPOSIT', 1, '2026-10-24T04:42:57.426805+08:00', '2026-10-24T04:42:57.426805+08:00'),
    (145, 'NEAR', 'testnet', 'USDT', 'NEP141', 'CHANGE_ME_USDT_CONTRACT.testnet', NULL, NULL, 6, false, false, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'NEAR_STORAGE_DEPOSIT', 1, '2026-10-24T04:42:57.426805+08:00', '2026-10-24T04:42:57.426805+08:00'),
    (146, 'APTOS', 'mainnet', 'USDC', 'APTOS_FA', '0xbae207659db88bea0cbead6da0ed00aac12edcdda169e591cd41c94180b46f3b', NULL, NULL, 6, false, false, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'APT_GAS', 1, '2026-10-24T04:42:57.433455+08:00', '2026-10-24T04:42:57.433455+08:00'),
    (147, 'APTOS', 'mainnet', 'USDT', 'APTOS_FA', '0x357b0b74bc833e95a115ad22604854d6b0fca151cecd94111770e5d6ffc9dc2b', NULL, NULL, 6, false, false, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'APT_GAS', 1, '2026-10-24T04:42:57.433455+08:00', '2026-10-24T04:42:57.433455+08:00'),
    (148, 'ARBITRUM', 'mainnet', 'USDC', 'ERC20', '0xaf88d065e77c8cC2239327C5EDb3A432268e5831', NULL, NULL, 6, false, false, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'native-gas', 40, '2026-10-24T04:42:57.433455+08:00', '2026-10-24T04:42:57.433455+08:00'),
    (149, 'ARBITRUM', 'mainnet', 'USDT', 'ERC20', '0xFd086bC7CD5C481DCC9C85ebE478A1C0b69FCbb9', NULL, NULL, 6, false, false, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'native-gas', 40, '2026-10-24T04:42:57.433455+08:00', '2026-10-24T04:42:57.433455+08:00'),
    (150, 'AVAX_C', 'mainnet', 'USDC', 'ERC20', '0xB97EF9Ef8734C71904D8002F8b6Bc66Dd9c48a6E', NULL, NULL, 6, false, false, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'native-gas', 20, '2026-10-24T04:42:57.433455+08:00', '2026-10-24T04:42:57.433455+08:00'),
    (151, 'AVAX_C', 'mainnet', 'USDT', 'ERC20', '0x9702230A8Ea53601f5cD2dc00fDBc13d4dF4A8c7', NULL, NULL, 6, false, false, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'native-gas', 20, '2026-10-24T04:42:57.433455+08:00', '2026-10-24T04:42:57.433455+08:00'),
    (152, 'BASE', 'mainnet', 'USDC', 'ERC20', '0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913', NULL, NULL, 6, false, false, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'native-gas', 40, '2026-10-24T04:42:57.433455+08:00', '2026-10-24T04:42:57.433455+08:00'),
    (153, 'DOT', 'mainnet', 'USDC', 'ASSET_HUB_ASSET', '1337', NULL, NULL, 6, false, false, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'ASSET_HUB_NATIVE_GAS', 12, '2026-10-24T04:42:57.433455+08:00', '2026-10-24T04:42:57.433455+08:00'),
    (154, 'DOT', 'mainnet', 'USDT', 'ASSET_HUB_ASSET', '1984', NULL, NULL, 6, false, false, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'ASSET_HUB_NATIVE_GAS', 12, '2026-10-24T04:42:57.433455+08:00', '2026-10-24T04:42:57.433455+08:00'),
    (155, 'ETH', 'mainnet', 'USDC', 'ERC20', '0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48', NULL, NULL, 6, false, false, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'native-gas', 24, '2026-10-24T04:42:57.433455+08:00', '2026-10-24T04:42:57.433455+08:00'),
    (156, 'ETH', 'mainnet', 'USDT', 'ERC20', '0xdAC17F958D2ee523a2206206994597C13D831ec7', NULL, NULL, 6, false, false, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'native-gas', 24, '2026-10-24T04:42:57.433455+08:00', '2026-10-24T04:42:57.433455+08:00'),
    (157, 'HYPEREVM', 'mainnet', 'USDC', 'ERC20', '0xb88339CB7199b77E23DB6E890353E22632Ba630f', NULL, NULL, 6, false, false, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'native-gas', 6, '2026-10-24T04:42:57.433455+08:00', '2026-10-24T04:42:57.433455+08:00'),
    (158, 'HYPEREVM', 'mainnet', 'USDT', 'ERC20', '0xB8CE59FC3717ada4C02eaDF9682A9e934F625ebb', NULL, NULL, 6, false, false, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'native-gas', 6, '2026-10-24T04:42:57.433455+08:00', '2026-10-24T04:42:57.433455+08:00'),
    (159, 'LINEA', 'mainnet', 'USDC', 'ERC20', '0x176211869cA2b568f2A7D4EE941E073a821EE1ff', NULL, NULL, 6, false, false, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'native-gas', 40, '2026-10-24T04:42:57.433455+08:00', '2026-10-24T04:42:57.433455+08:00'),
    (160, 'NEAR', 'mainnet', 'USDC', 'NEP141', '17208628f84f5d6ad33f0da3bbbeb27ffcb398eac501a31bd6ad2011e36133a1', NULL, NULL, 6, false, false, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'NEAR_STORAGE_DEPOSIT', 1, '2026-10-24T04:42:57.433455+08:00', '2026-10-24T04:42:57.433455+08:00'),
    (161, 'NEAR', 'mainnet', 'USDT', 'NEP141', 'usdt.tether-token.near', NULL, NULL, 6, false, false, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'NEAR_STORAGE_DEPOSIT', 1, '2026-10-24T04:42:57.433455+08:00', '2026-10-24T04:42:57.433455+08:00'),
    (162, 'OPTIMISM', 'mainnet', 'USDC', 'ERC20', '0x0b2C639c533813f4Aa9D7837CAf62653d097Ff85', NULL, NULL, 6, false, false, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'native-gas', 40, '2026-10-24T04:42:57.433455+08:00', '2026-10-24T04:42:57.433455+08:00'),
    (163, 'OPTIMISM', 'mainnet', 'USDT', 'ERC20', '0x01bFF41798a0BcF287b996046Ca68b395DbC1071', NULL, NULL, 6, false, false, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'native-gas', 40, '2026-10-24T04:42:57.433455+08:00', '2026-10-24T04:42:57.433455+08:00'),
    (164, 'POLYGON', 'mainnet', 'USDC', 'ERC20', '0x3c499c542cEF5E3811e1192ce70d8cC03d5c3359', NULL, NULL, 6, false, false, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'native-gas', 128, '2026-10-24T04:42:57.433455+08:00', '2026-10-24T04:42:57.433455+08:00'),
    (165, 'POLYGON', 'mainnet', 'USDT', 'ERC20', '0xc2132D05D31c914a87C6611C10748AEb04B58e8F', NULL, NULL, 6, false, false, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'native-gas', 128, '2026-10-24T04:42:57.433455+08:00', '2026-10-24T04:42:57.433455+08:00'),
    (166, 'SOLANA', 'mainnet', 'USDC', 'SPL', 'EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v', 'EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v', NULL, 6, false, false, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'SOL_FEE_PAYER', 32, '2026-10-24T04:42:57.433455+08:00', '2026-10-24T04:42:57.433455+08:00'),
    (167, 'SOLANA', 'mainnet', 'USDT', 'SPL', 'Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB', 'Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB', NULL, 6, false, false, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'SOL_FEE_PAYER', 32, '2026-10-24T04:42:57.433455+08:00', '2026-10-24T04:42:57.433455+08:00'),
    (168, 'SUI', 'mainnet', 'USDC', 'COIN', '0xdba34672e30cb065b1f93e3ab55318768fd6fef66c15942c9f7cb846e2f900e7::usdc::USDC', NULL, NULL, 6, false, false, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'SUI_GAS_OBJECT', 1, '2026-10-24T04:42:57.433455+08:00', '2026-10-24T04:42:57.433455+08:00'),
    (169, 'TON', 'mainnet', 'USDT', 'JETTON', 'EQCxE6mUtQJKFnGfaROTKOt1lZbDiiX1kCixRv7Nw2Id_sDs', NULL, NULL, 6, false, false, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'TON_FORWARD_FEE', 1, '2026-10-24T04:42:57.433455+08:00', '2026-10-24T04:42:57.433455+08:00'),
    (170, 'TRON', 'mainnet', 'USDT', 'TRC20', 'TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t', 'TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t', '41a614f803b6fd780986a42c78ec9c7f77e6ded13c', 6, false, false, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'energy-bandwidth', 20, '2026-10-24T04:42:57.433455+08:00', '2026-10-24T04:42:57.433455+08:00'),
    (171, 'UNICHAIN', 'mainnet', 'USDC', 'ERC20', '0x078D782b760474a361dDA0AF3839290b0EF57AD6', NULL, NULL, 6, false, false, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'native-gas', 40, '2026-10-24T04:42:57.433455+08:00', '2026-10-24T04:42:57.433455+08:00'),
    (172, 'UNICHAIN', 'mainnet', 'USDT', 'ERC20', '0x9151434b16b9763660705744891fA906F660EcC5', NULL, NULL, 6, false, false, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'native-gas', 40, '2026-10-24T04:42:57.433455+08:00', '2026-10-24T04:42:57.433455+08:00'),
    (173, 'XRP', 'mainnet', 'USDC', 'ISSUED_CURRENCY', 'rGm7WCVp9gb4jZHWTEtGUr4dd74z2XuWhE:5553444300000000000000000000000000000000', NULL, '5553444300000000000000000000000000000000', 6, false, false, '1.000000000000000000', '1.000000000000000000', '1.000000000000000000', 'XRP_TRUSTLINE', 3, '2026-10-24T04:42:57.433455+08:00', '2026-10-24T04:42:57.433455+08:00');




--
-- Data for Name: wallet_system_config; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO "public"."wallet_system_config" ("config_key", "config_value", "value_type", "enabled", "remark", "created_at", "updated_at") VALUES ('global.all.enabled', 'true', 'boolean', true, 'Global master switch for all wallet runtime jobs', '2026-06-25 00:10:43.487527+08', '2026-06-25 00:10:43.487527+08');
INSERT INTO "public"."wallet_system_config" ("config_key", "config_value", "value_type", "enabled", "remark", "created_at", "updated_at") VALUES ('global.scan.enabled', 'true', 'boolean', true, 'Global block/account scanner switch', '2026-06-25 00:10:43.487527+08', '2026-06-25 00:10:43.487527+08');
INSERT INTO "public"."wallet_system_config" ("config_key", "config_value", "value_type", "enabled", "remark", "created_at", "updated_at") VALUES ('global.withdraw.enabled', 'true', 'boolean', true, 'Global withdrawal switch', '2026-06-25 00:10:43.487527+08', '2026-06-25 00:10:43.487527+08');
INSERT INTO "public"."wallet_system_config" ("config_key", "config_value", "value_type", "enabled", "remark", "created_at", "updated_at") VALUES ('global.collection.enabled', 'true', 'boolean', true, 'Global collection switch', '2026-06-25 00:10:43.487527+08', '2026-06-25 00:10:43.487527+08');
INSERT INTO "public"."wallet_system_config" ("config_key", "config_value", "value_type", "enabled", "remark", "created_at", "updated_at") VALUES ('global.transfer.enabled', 'true', 'boolean', true, 'Global internal transfer switch', '2026-06-25 00:10:43.487527+08', '2026-06-25 00:10:43.487527+08');
INSERT INTO "public"."wallet_system_config" ("config_key", "config_value", "value_type", "enabled", "remark", "created_at", "updated_at") VALUES ('near.token.account.activation.yocto', '50000000000000000000000', 'bigint', true, 'NEAR native amount sent from the default NEAR hot wallet before preparing a NEP-141 token deposit address. Default is 0.05 NEAR.', '2026-06-28 13:20:00+08', '2026-06-28 13:20:00+08');


--
-- Name: chain_asset_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('"public"."chain_asset_id_seq"', 187, true);


--
-- Name: chain_profile_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('"public"."chain_profile_id_seq"', 154, true);


--
-- Name: chain_rpc_node_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('"public"."chain_rpc_node_id_seq"', 227, true);


--
-- Name: monero_transaction_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('"public"."monero_transaction_id_seq"', 1, false);


--
-- Name: near_transaction_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('"public"."near_transaction_id_seq"', 1, false);


--
-- Name: token_config_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('"public"."token_config_id_seq"', 173, true);

INSERT INTO "public"."chain_profile" ("chain", "network", "family", "runtime_currency_id", "bip44_coin_type",
                                      "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations",
                                      "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled",
                                      "created_at", "updated_at", "chain_id", "gas_policy", "scan_batch_size",
                                      "scan_enabled", "withdraw_enabled", "collection_enabled", "transfer_enabled",
                                      "scan_start_height", "scan_max_blocks_per_run")
VALUES
    ('HYPEREVM', 'testnet', 'evm', 9004, 60, 'HYPE',
     'https://rpc.hyperliquid-testnet.xyz/evm', 'https://app.hyperliquid-testnet.xyz/explorer/tx/',
     2, 2, 1, 0, true, now(), now(), 998, 'legacy-gas-price', 40,
     true, true, true, true, 0, 40),
    ('HYPEREVM', 'mainnet', 'evm', 9004, 60, 'HYPE',
     'https://rpc.hyperliquid.xyz/evm', 'https://app.hyperliquid.xyz/explorer/tx/',
     6, 6, 1, 0, false, now(), now(), 999, 'legacy-gas-price', 40,
     false, false, false, false, 0, 40)
ON CONFLICT ("chain", "network") DO UPDATE SET
    "family" = EXCLUDED."family",
    "runtime_currency_id" = EXCLUDED."runtime_currency_id",
    "bip44_coin_type" = EXCLUDED."bip44_coin_type",
    "native_symbol" = EXCLUDED."native_symbol",
    "rpc_url" = EXCLUDED."rpc_url",
    "explorer_url" = EXCLUDED."explorer_url",
    "deposit_confirmations" = EXCLUDED."deposit_confirmations",
    "withdraw_confirmations" = EXCLUDED."withdraw_confirmations",
    "default_fee_rate" = EXCLUDED."default_fee_rate",
    "dust_threshold" = EXCLUDED."dust_threshold",
    "enabled" = EXCLUDED."enabled",
    "updated_at" = now(),
    "chain_id" = EXCLUDED."chain_id",
    "gas_policy" = EXCLUDED."gas_policy",
    "scan_batch_size" = EXCLUDED."scan_batch_size",
    "scan_enabled" = EXCLUDED."scan_enabled",
    "withdraw_enabled" = EXCLUDED."withdraw_enabled",
    "collection_enabled" = EXCLUDED."collection_enabled",
    "transfer_enabled" = EXCLUDED."transfer_enabled",
    "scan_start_height" = EXCLUDED."scan_start_height",
    "scan_max_blocks_per_run" = EXCLUDED."scan_max_blocks_per_run";

INSERT INTO "public"."chain_rpc_node" ("chain", "network", "environment", "node_label", "purpose",
                                       "connection_type", "rpc_url", "auth_type", "auth_header_name",
                                       "priority", "min_request_interval_ms", "enabled", "remark",
                                       "created_at", "updated_at", "api_key")
VALUES
    ('HYPEREVM', 'testnet', 'dev', 'official-hyperevm-testnet', 'rpc', 'HTTP_JSON_RPC',
     'https://rpc.hyperliquid-testnet.xyz/evm', 'NONE', NULL, 10, 500, true,
     'Official HyperEVM testnet JSON-RPC endpoint.',
     now(), now(), NULL),
    ('HYPEREVM', 'testnet', 'test2', 'official-hyperevm-testnet', 'rpc', 'HTTP_JSON_RPC',
     'https://rpc.hyperliquid-testnet.xyz/evm', 'NONE', NULL, 10, 500, true,
     'test2 official HyperEVM testnet JSON-RPC endpoint.',
     now(), now(), NULL),
    ('HYPEREVM', 'mainnet', 'prod', 'official-hyperevm-mainnet', 'rpc', 'HTTP_JSON_RPC',
     'https://rpc.hyperliquid.xyz/evm', 'NONE', NULL, 10, 1000, false,
     'Production HyperEVM mainnet JSON-RPC endpoint. Keep disabled until mainnet launch checks, monitoring and funding are ready.',
     now(), now(), NULL)
ON CONFLICT ("chain", "network", "environment", "purpose", "node_label") DO UPDATE SET
    "connection_type" = EXCLUDED."connection_type",
    "rpc_url" = EXCLUDED."rpc_url",
    "auth_type" = EXCLUDED."auth_type",
    "auth_header_name" = EXCLUDED."auth_header_name",
    "api_key" = EXCLUDED."api_key",
    "priority" = EXCLUDED."priority",
    "min_request_interval_ms" = EXCLUDED."min_request_interval_ms",
    "enabled" = EXCLUDED."enabled",
    "remark" = EXCLUDED."remark",
    "updated_at" = now();

INSERT INTO "public"."chain_profile" ("chain", "network", "family", "runtime_currency_id", "bip44_coin_type",
                                      "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations",
                                      "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled",
                                      "created_at", "updated_at", "chain_id", "gas_policy", "scan_batch_size",
                                      "scan_enabled", "withdraw_enabled", "collection_enabled", "transfer_enabled",
                                      "scan_start_height", "scan_max_blocks_per_run")
VALUES
    ('LINEA', 'sepolia', 'evm', 9007, 60, 'ETH_LINEA',
     'https://rpc.sepolia.linea.build', 'https://sepolia.lineascan.build/tx/',
     40, 40, 1, 0, true, now(), now(), 59141, 'eip1559', 200,
     true, true, true, true, 0, 200),
    ('LINEA', 'mainnet', 'evm', 9007, 60, 'ETH_LINEA',
     'https://rpc.linea.build', 'https://lineascan.build/tx/',
     40, 40, 1, 0, false, now(), now(), 59144, 'eip1559', 200,
     false, false, false, false, 0, 200),
    ('SCROLL', 'sepolia', 'evm', 9008, 60, 'ETH_SCROLL',
     'https://sepolia-rpc.scroll.io', 'https://sepolia.scrollscan.com/tx/',
     40, 40, 1, 0, true, now(), now(), 534351, 'eip1559', 200,
     true, true, true, true, 0, 200),
    ('SCROLL', 'mainnet', 'evm', 9008, 60, 'ETH_SCROLL',
     'https://rpc.scroll.io', 'https://scrollscan.com/tx/',
     40, 40, 1, 0, false, now(), now(), 534352, 'eip1559', 200,
     false, false, false, false, 0, 200),
    ('UNICHAIN', 'sepolia', 'evm', 9009, 60, 'ETH_UNICHAIN',
     'https://sepolia.unichain.org', 'https://sepolia.uniscan.xyz/tx/',
     40, 40, 1, 0, true, now(), now(), 1301, 'eip1559', 200,
     true, true, true, true, 0, 200),
    ('UNICHAIN', 'mainnet', 'evm', 9009, 60, 'ETH_UNICHAIN',
     'https://mainnet.unichain.org', 'https://uniscan.xyz/tx/',
     40, 40, 1, 0, false, now(), now(), 130, 'eip1559', 200,
     false, false, false, false, 0, 200),
    ('X_LAYER', 'testnet', 'evm', 9022, 60, 'OKB',
     'https://testrpc.xlayer.tech/terigon', 'https://www.okx.com/web3/explorer/xlayer-test/tx/',
     40, 40, 1, 0, false, now(), now(), 1952, 'eip1559', 200,
     false, false, false, false, 0, 200),
    ('X_LAYER', 'mainnet', 'evm', 9022, 60, 'OKB',
     'https://rpc.xlayer.tech', 'https://www.okx.com/web3/explorer/xlayer/tx/',
     40, 40, 1, 0, false, now(), now(), 196, 'eip1559', 200,
     false, false, false, false, 0, 200),
    ('ROBINHOOD_CHAIN', 'testnet', 'evm', 9024, 60, 'ETH_ROBINHOOD',
     'https://rpc.testnet.chain.robinhood.com', 'https://explorer.testnet.chain.robinhood.com/tx/',
     40, 40, 1, 0, false, now(), now(), 46630, 'eip1559', 200,
     false, false, false, false, 0, 200),
    ('ROBINHOOD_CHAIN', 'mainnet', 'evm', 9024, 60, 'ETH_ROBINHOOD',
     'https://rpc.mainnet.chain.robinhood.com', 'https://robinhoodchain.blockscout.com/tx/',
     40, 40, 1, 0, false, now(), now(), 4663, 'eip1559', 200,
     false, false, false, false, 0, 200)
ON CONFLICT ("chain", "network") DO UPDATE SET
    "family" = EXCLUDED."family",
    "runtime_currency_id" = EXCLUDED."runtime_currency_id",
    "bip44_coin_type" = EXCLUDED."bip44_coin_type",
    "native_symbol" = EXCLUDED."native_symbol",
    "rpc_url" = EXCLUDED."rpc_url",
    "explorer_url" = EXCLUDED."explorer_url",
    "deposit_confirmations" = EXCLUDED."deposit_confirmations",
    "withdraw_confirmations" = EXCLUDED."withdraw_confirmations",
    "default_fee_rate" = EXCLUDED."default_fee_rate",
    "dust_threshold" = EXCLUDED."dust_threshold",
    "enabled" = EXCLUDED."enabled",
    "updated_at" = now(),
    "chain_id" = EXCLUDED."chain_id",
    "gas_policy" = EXCLUDED."gas_policy",
    "scan_batch_size" = EXCLUDED."scan_batch_size",
    "scan_enabled" = EXCLUDED."scan_enabled",
    "withdraw_enabled" = EXCLUDED."withdraw_enabled",
    "collection_enabled" = EXCLUDED."collection_enabled",
    "transfer_enabled" = EXCLUDED."transfer_enabled",
    "scan_start_height" = EXCLUDED."scan_start_height",
    "scan_max_blocks_per_run" = EXCLUDED."scan_max_blocks_per_run";

INSERT INTO "public"."chain_rpc_node" ("chain", "network", "environment", "node_label", "purpose",
                                       "connection_type", "rpc_url", "auth_type", "auth_header_name",
                                       "priority", "min_request_interval_ms", "enabled", "remark",
                                       "created_at", "updated_at", "api_key")
VALUES
    ('LINEA', 'sepolia', 'dev', 'official-linea-sepolia', 'rpc', 'HTTP_JSON_RPC',
     'https://rpc.sepolia.linea.build', 'NONE', NULL, 10, 500, true,
     'Official Linea Sepolia JSON-RPC endpoint.',
     now(), now(), NULL),
    ('LINEA', 'sepolia', 'test2', 'official-linea-sepolia', 'rpc', 'HTTP_JSON_RPC',
     'https://rpc.sepolia.linea.build', 'NONE', NULL, 10, 500, true,
     'test2 official Linea Sepolia JSON-RPC endpoint.',
     now(), now(), NULL),
    ('LINEA', 'sepolia', 'test2', 'infura-linea-sepolia', 'rpc', 'HTTP_JSON_RPC',
     'https://linea-sepolia.infura.io/v3/f18e59cbca624527aea1b3093520f0e8', 'NONE', NULL, 50, 100, true,
     'test2 Infura Linea Sepolia backup RPC.',
     now(), now(), NULL),
    ('LINEA', 'mainnet', 'prod', 'official-linea-mainnet', 'rpc', 'HTTP_JSON_RPC',
     'https://rpc.linea.build', 'NONE', NULL, 10, 1000, false,
     'Production Linea mainnet JSON-RPC endpoint. Enable only after funding and monitoring are ready.',
     now(), now(), NULL),
    ('SCROLL', 'sepolia', 'dev', 'official-scroll-sepolia', 'rpc', 'HTTP_JSON_RPC',
     'https://sepolia-rpc.scroll.io', 'NONE', NULL, 10, 500, true,
     'Official Scroll Sepolia JSON-RPC endpoint.',
     now(), now(), NULL),
    ('SCROLL', 'sepolia', 'test2', 'official-scroll-sepolia', 'rpc', 'HTTP_JSON_RPC',
     'https://sepolia-rpc.scroll.io', 'NONE', NULL, 10, 500, true,
     'test2 official Scroll Sepolia JSON-RPC endpoint.',
     now(), now(), NULL),
    ('SCROLL', 'sepolia', 'test2', 'publicnode-scroll-sepolia', 'rpc', 'HTTP_JSON_RPC',
     'https://scroll-sepolia-rpc.publicnode.com', 'NONE', NULL, 50, 500, true,
     'test2 PublicNode Scroll Sepolia backup RPC.',
     now(), now(), NULL),
    ('SCROLL', 'mainnet', 'prod', 'official-scroll-mainnet', 'rpc', 'HTTP_JSON_RPC',
     'https://rpc.scroll.io', 'NONE', NULL, 10, 1000, false,
     'Production Scroll mainnet JSON-RPC endpoint. Enable only after funding and monitoring are ready.',
     now(), now(), NULL),
    ('UNICHAIN', 'sepolia', 'dev', 'official-unichain-sepolia', 'rpc', 'HTTP_JSON_RPC',
     'https://sepolia.unichain.org', 'NONE', NULL, 10, 500, true,
     'Official Unichain Sepolia JSON-RPC endpoint. Public endpoint is rate-limited; use private RPC before heavy scanning.',
     now(), now(), NULL),
    ('UNICHAIN', 'sepolia', 'test2', 'official-unichain-sepolia', 'rpc', 'HTTP_JSON_RPC',
     'https://sepolia.unichain.org', 'NONE', NULL, 10, 500, true,
     'test2 official Unichain Sepolia JSON-RPC endpoint. Public endpoint is rate-limited; use private RPC before heavy scanning.',
     now(), now(), NULL),
    ('UNICHAIN', 'sepolia', 'test2', 'publicnode-unichain-sepolia', 'rpc', 'HTTP_JSON_RPC',
     'https://unichain-sepolia-rpc.publicnode.com', 'NONE', NULL, 50, 500, true,
     'test2 PublicNode Unichain Sepolia backup RPC.',
     now(), now(), NULL),
    ('UNICHAIN', 'mainnet', 'prod', 'official-unichain-mainnet', 'rpc', 'HTTP_JSON_RPC',
     'https://mainnet.unichain.org', 'NONE', NULL, 10, 1000, false,
     'Production Unichain mainnet JSON-RPC endpoint. Public endpoint is rate-limited; enable only after private RPC, funding and monitoring are ready.',
     now(), now(), NULL),
    ('X_LAYER', 'testnet', 'dev', 'official-x-layer-testnet', 'rpc', 'HTTP_JSON_RPC',
     'https://testrpc.xlayer.tech/terigon', 'NONE', NULL, 10, 500, false,
     'Official X Layer testnet JSON-RPC endpoint. Disabled by default; local tests use Hardhat.',
     now(), now(), NULL),
    ('X_LAYER', 'testnet', 'test2', 'official-x-layer-testnet', 'rpc', 'HTTP_JSON_RPC',
     'https://testrpc.xlayer.tech/terigon', 'NONE', NULL, 10, 500, false,
     'test2 official X Layer testnet JSON-RPC endpoint. Enable only for explicit live tests.',
     now(), now(), NULL),
    ('X_LAYER', 'mainnet', 'prod', 'official-x-layer-mainnet', 'rpc', 'HTTP_JSON_RPC',
     'https://rpc.xlayer.tech', 'NONE', NULL, 10, 1000, false,
     'Production X Layer mainnet public JSON-RPC endpoint. Enable only after private RPC, funding and monitoring are ready.',
     now(), now(), NULL),
    ('ROBINHOOD_CHAIN', 'testnet', 'dev', 'official-robinhood-testnet', 'rpc', 'HTTP_JSON_RPC',
     'https://rpc.testnet.chain.robinhood.com', 'NONE', NULL, 10, 500, false,
     'Official Robinhood Chain testnet JSON-RPC endpoint. Disabled by default; local tests use Hardhat.',
     now(), now(), NULL),
    ('ROBINHOOD_CHAIN', 'testnet', 'test2', 'official-robinhood-testnet', 'rpc', 'HTTP_JSON_RPC',
     'https://rpc.testnet.chain.robinhood.com', 'NONE', NULL, 10, 500, false,
     'test2 official Robinhood Chain testnet JSON-RPC endpoint. Enable only for explicit live tests.',
     now(), now(), NULL),
    ('ROBINHOOD_CHAIN', 'mainnet', 'prod', 'official-robinhood-mainnet', 'rpc', 'HTTP_JSON_RPC',
     'https://rpc.mainnet.chain.robinhood.com', 'NONE', NULL, 10, 1000, false,
     'Production Robinhood Chain mainnet public JSON-RPC endpoint. Enable only after private RPC, funding and monitoring are ready.',
     now(), now(), NULL)
ON CONFLICT ("chain", "network", "environment", "purpose", "node_label") DO UPDATE SET
    "connection_type" = EXCLUDED."connection_type",
    "rpc_url" = EXCLUDED."rpc_url",
    "auth_type" = EXCLUDED."auth_type",
    "auth_header_name" = EXCLUDED."auth_header_name",
    "api_key" = EXCLUDED."api_key",
    "priority" = EXCLUDED."priority",
    "min_request_interval_ms" = EXCLUDED."min_request_interval_ms",
    "enabled" = EXCLUDED."enabled",
    "remark" = EXCLUDED."remark",
    "updated_at" = now();

INSERT INTO "public"."chain_profile" ("chain", "network", "family", "runtime_currency_id", "bip44_coin_type",
                                      "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations",
                                      "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled",
                                      "created_at", "updated_at", "chain_id", "gas_policy", "scan_batch_size",
                                      "scan_enabled", "withdraw_enabled", "collection_enabled", "transfer_enabled",
                                      "scan_start_height", "scan_max_blocks_per_run")
VALUES
    ('HYPERCORE', 'testnet', 'hypercore', 9005, 60, 'USDC',
     'https://api.hyperliquid-testnet.xyz', 'https://app.hyperliquid-testnet.xyz/explorer/',
     1, 1, 0, 0, true, now(), now(), 421614, 'hypercore-action', 100,
     true, true, true, true, 0, 100),
    ('HYPERCORE', 'mainnet', 'hypercore', 9005, 60, 'USDC',
     'https://api.hyperliquid.xyz', 'https://app.hyperliquid.xyz/explorer/',
     1, 1, 0, 0, false, now(), now(), 421614, 'hypercore-action', 100,
     false, false, false, false, 0, 100)
ON CONFLICT ("chain", "network") DO UPDATE SET
    "family" = EXCLUDED."family",
    "runtime_currency_id" = EXCLUDED."runtime_currency_id",
    "bip44_coin_type" = EXCLUDED."bip44_coin_type",
    "native_symbol" = EXCLUDED."native_symbol",
    "rpc_url" = EXCLUDED."rpc_url",
    "explorer_url" = EXCLUDED."explorer_url",
    "deposit_confirmations" = EXCLUDED."deposit_confirmations",
    "withdraw_confirmations" = EXCLUDED."withdraw_confirmations",
    "default_fee_rate" = EXCLUDED."default_fee_rate",
    "dust_threshold" = EXCLUDED."dust_threshold",
    "enabled" = EXCLUDED."enabled",
    "updated_at" = now(),
    "chain_id" = EXCLUDED."chain_id",
    "gas_policy" = EXCLUDED."gas_policy",
    "scan_batch_size" = EXCLUDED."scan_batch_size",
    "scan_enabled" = EXCLUDED."scan_enabled",
    "withdraw_enabled" = EXCLUDED."withdraw_enabled",
    "collection_enabled" = EXCLUDED."collection_enabled",
    "transfer_enabled" = EXCLUDED."transfer_enabled",
    "scan_start_height" = EXCLUDED."scan_start_height",
    "scan_max_blocks_per_run" = EXCLUDED."scan_max_blocks_per_run";

INSERT INTO "public"."chain_rpc_node" ("chain", "network", "environment", "node_label", "purpose",
                                       "connection_type", "rpc_url", "auth_type", "auth_header_name",
                                       "priority", "min_request_interval_ms", "enabled", "remark",
                                       "created_at", "updated_at", "api_key")
VALUES
    ('HYPERCORE', 'testnet', 'dev', 'official-hypercore-testnet-info', 'info', 'HTTP_JSON',
     'https://api.hyperliquid-testnet.xyz', 'NONE', NULL, 10, 500, true,
     'Official Hyperliquid testnet info API.',
     now(), now(), NULL),
    ('HYPERCORE', 'testnet', 'dev', 'official-hypercore-testnet-exchange', 'exchange', 'HTTP_JSON',
     'https://api.hyperliquid-testnet.xyz', 'NONE', NULL, 10, 500, true,
     'Official Hyperliquid testnet exchange API.',
     now(), now(), NULL),
    ('HYPERCORE', 'testnet', 'test2', 'official-hypercore-testnet-info', 'info', 'HTTP_JSON',
     'https://api.hyperliquid-testnet.xyz', 'NONE', NULL, 10, 500, true,
     'test2 official Hyperliquid testnet info API.',
     now(), now(), NULL),
    ('HYPERCORE', 'testnet', 'test2', 'official-hypercore-testnet-exchange', 'exchange', 'HTTP_JSON',
     'https://api.hyperliquid-testnet.xyz', 'NONE', NULL, 10, 500, true,
     'test2 official Hyperliquid testnet exchange API.',
     now(), now(), NULL),
    ('HYPERCORE', 'mainnet', 'prod', 'official-hypercore-mainnet-info', 'info', 'HTTP_JSON',
     'https://api.hyperliquid.xyz', 'NONE', NULL, 10, 500, false,
     'Production Hyperliquid info API. Enable only after custody checks and funding are ready.',
     now(), now(), NULL),
    ('HYPERCORE', 'mainnet', 'prod', 'official-hypercore-mainnet-exchange', 'exchange', 'HTTP_JSON',
     'https://api.hyperliquid.xyz', 'NONE', NULL, 10, 500, false,
     'Production Hyperliquid exchange API. Enable only after custody checks and funding are ready.',
     now(), now(), NULL)
ON CONFLICT ("chain", "network", "environment", "purpose", "node_label") DO UPDATE SET
    "connection_type" = EXCLUDED."connection_type",
    "rpc_url" = EXCLUDED."rpc_url",
    "auth_type" = EXCLUDED."auth_type",
    "auth_header_name" = EXCLUDED."auth_header_name",
    "api_key" = EXCLUDED."api_key",
    "priority" = EXCLUDED."priority",
    "min_request_interval_ms" = EXCLUDED."min_request_interval_ms",
    "enabled" = EXCLUDED."enabled",
    "remark" = EXCLUDED."remark",
    "updated_at" = now();

INSERT INTO "public"."hypercore_token_metadata" ("network", "token_index", "token_id", "name", "sz_decimals",
                                                 "wei_decimals", "is_canonical", "evm_contract", "full_name",
                                                 "created_at", "updated_at")
VALUES
    ('testnet', 0, '0xeb62eee3685fc4c43992febcd9e75443', 'USDC', 8, 8, true,
     '0x0b80659a4076e9e93c7dbe0f10675a16a3e5c206', NULL, now(), now()),
    ('testnet', 1105, '0x7317beb7cceed72ef0b346074cc8e7ab', 'HYPE', 2, 8, false,
     '0x0000000000000000000000000000000000000000', 'Hyperliquid', now(), now()),
    ('mainnet', 0, '0x6d1e7cde53ba9467b783cb7c530ce054', 'USDC', 8, 8, true,
     '0x6b9e773128f453f5c2c60935ee2de2cbc5390a24', NULL, now(), now()),
    ('mainnet', 150, '0x0d01dc56dcaaca66ad901c959b4011ec', 'HYPE', 2, 8, false,
     NULL, 'Hyperliquid', now(), now())
ON CONFLICT ("network", "token_index") DO UPDATE SET
    "token_id" = EXCLUDED."token_id",
    "name" = EXCLUDED."name",
    "sz_decimals" = EXCLUDED."sz_decimals",
    "wei_decimals" = EXCLUDED."wei_decimals",
    "is_canonical" = EXCLUDED."is_canonical",
    "evm_contract" = EXCLUDED."evm_contract",
    "full_name" = EXCLUDED."full_name",
    "updated_at" = now();

INSERT INTO "public"."chain_profile" ("chain", "network", "family", "runtime_currency_id", "bip44_coin_type",
                                      "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations",
                                      "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled",
                                      "created_at", "updated_at", "chain_id", "gas_policy", "scan_batch_size",
                                      "scan_enabled", "withdraw_enabled", "collection_enabled", "transfer_enabled",
                                      "scan_start_height", "scan_max_blocks_per_run")
VALUES
    ('ADA', 'preprod', 'cardano', 1815, 1815, 'ADA', NULL, 'https://preprod.cardanoscan.io/transaction/',
     15, 15, NULL, 1000, false, now(), now(), NULL, 'cardano-utxo', 100,
     false, false, false, false, 0, 0),
    ('ADA', 'mainnet', 'cardano', 1815, 1815, 'ADA', NULL, 'https://cardanoscan.io/transaction/',
     30, 30, NULL, 1000, false, now(), now(), NULL, 'cardano-utxo', 100,
     false, false, false, false, 0, 0),
    ('DOT', 'westend', 'polkadot', 354, 354, 'DOT', NULL, 'https://westend.subscan.io/extrinsic/',
     12, 12, 200000000000, 100000000, false, now(), now(), 42, 'substrate-balances', 100,
     false, false, false, false, 0, 0),
    ('DOT', 'mainnet', 'polkadot', 354, 354, 'DOT', NULL, 'https://polkadot.subscan.io/extrinsic/',
     12, 12, NULL, 100000000, false, now(), now(), 0, 'substrate-balances', 100,
     false, false, false, false, 0, 0),
    ('NEAR', 'testnet', 'near', 397, 397, 'NEAR', 'https://rpc.testnet.near.org', 'https://testnet.nearblocks.io/txns/',
     1, 1, NULL, 1000000000000000000, false, now(), now(), NULL, 'near-implicit-account', 100,
     false, false, false, false, 0, 0),
    ('NEAR', 'mainnet', 'near', 397, 397, 'NEAR', 'https://rpc.mainnet.near.org', 'https://nearblocks.io/txns/',
     1, 1, NULL, 1000000000000000000, false, now(), now(), NULL, 'near-implicit-account', 100,
     false, false, false, false, 0, 0),
    ('XMR', 'regtest', 'monero', 128, 128, 'XMR', 'http://127.0.0.1:18088', NULL,
     10, 10, 3000000000, 100000000, false, now(), now(), NULL, 'monero-wallet-rpc', 100,
     false, false, false, false, 0, 0),
    ('XMR', 'stagenet', 'monero', 128, 128, 'XMR', NULL, 'https://stagenet.xmrchain.net/tx/',
     10, 10, 3000000000, 100000000, false, now(), now(), NULL, 'monero-wallet-rpc', 100,
     false, false, false, false, 0, 0),
    ('XMR', 'mainnet', 'monero', 128, 128, 'XMR', NULL, 'https://xmrchain.net/tx/',
     10, 10, 3000000000, 100000000, false, now(), now(), NULL, 'monero-wallet-rpc', 100,
     false, false, false, false, 0, 0)
ON CONFLICT ("chain", "network") DO UPDATE SET
    "family" = EXCLUDED."family",
    "runtime_currency_id" = EXCLUDED."runtime_currency_id",
    "bip44_coin_type" = EXCLUDED."bip44_coin_type",
    "native_symbol" = EXCLUDED."native_symbol",
    "rpc_url" = EXCLUDED."rpc_url",
    "explorer_url" = EXCLUDED."explorer_url",
    "deposit_confirmations" = EXCLUDED."deposit_confirmations",
    "withdraw_confirmations" = EXCLUDED."withdraw_confirmations",
    "default_fee_rate" = EXCLUDED."default_fee_rate",
    "dust_threshold" = EXCLUDED."dust_threshold",
    "enabled" = EXCLUDED."enabled",
    "updated_at" = now(),
    "chain_id" = EXCLUDED."chain_id",
    "gas_policy" = EXCLUDED."gas_policy",
    "scan_batch_size" = EXCLUDED."scan_batch_size",
    "scan_enabled" = EXCLUDED."scan_enabled",
    "withdraw_enabled" = EXCLUDED."withdraw_enabled",
    "collection_enabled" = EXCLUDED."collection_enabled",
    "transfer_enabled" = EXCLUDED."transfer_enabled",
    "scan_start_height" = EXCLUDED."scan_start_height",
    "scan_max_blocks_per_run" = EXCLUDED."scan_max_blocks_per_run";

INSERT INTO "public"."chain_rpc_node" ("chain", "network", "environment", "node_label", "purpose",
                                       "connection_type", "rpc_url", "auth_type", "auth_header_name",
                                       "priority", "min_request_interval_ms", "enabled", "remark",
                                       "created_at", "updated_at", "username", "password")
VALUES
    ('XMR', 'regtest', 'dev', 'local-monero-wallet-rpc-funder-regtest', 'faucet', 'WALLET_RPC',
     'http://127.0.0.1:18090', 'NONE', NULL, 6, 250, true,
     'Local funder monero-wallet-rpc used only by the non-production XMR regtest faucet.',
     now(), now(), NULL, NULL),
    ('XMR', 'regtest', 'dev', 'local-monerod-regtest', 'daemon', 'HTTP_JSON_RPC',
     'http://127.0.0.1:18081', 'NONE', NULL, 7, 250, true,
     'Public native XMR network/genesis/scan-anchor reads and regtest mining; wallet files remain in signing nodes.',
     now(), now(), NULL, NULL),
    ('XMR', 'regtest', 'test2', 'local-monero-wallet-rpc-funder-regtest', 'faucet', 'WALLET_RPC',
     'http://127.0.0.1:18090', 'DIGEST', NULL, 6, 250, true,
     'test2 funder monero-wallet-rpc placeholder. Bind to the private host IP and replace username/password in DB before enabling the faucet.',
     now(), now(), 'CHANGE_ME_MONERO_RPC_USER', 'CHANGE_ME_MONERO_RPC_PASSWORD'),
    ('XMR', 'regtest', 'test2', 'local-monerod-regtest', 'daemon', 'HTTP_JSON_RPC',
     'http://127.0.0.1:18081', 'NONE', NULL, 7, 250, true,
     'test2 public monerod network/genesis/scan-anchor endpoint and regtest mining.',
     now(), now(), NULL, NULL),
    ('XMR', 'stagenet', 'test2', 'local-monerod-stagenet', 'daemon', 'HTTP_JSON_RPC',
     'http://127.0.0.1:38081', 'DIGEST', NULL, 10, 500, false,
     'Public stagenet daemon for native XMR network/genesis and canonical scan-anchor reads.',
     now(), now(), 'CHANGE_ME_MONERO_DAEMON_USER', 'CHANGE_ME_MONERO_DAEMON_PASSWORD'),
    ('XMR', 'mainnet', 'prod', 'local-monerod-mainnet', 'daemon', 'HTTP_JSON_RPC',
     'http://127.0.0.1:18081', 'DIGEST', NULL, 10, 500, false,
     'Public mainnet daemon for native XMR network/genesis and canonical scan-anchor reads.',
     now(), now(), 'CHANGE_ME_MONERO_DAEMON_USER', 'CHANGE_ME_MONERO_DAEMON_PASSWORD')
ON CONFLICT ("chain", "network", "environment", "purpose", "node_label") DO UPDATE SET
    "connection_type" = EXCLUDED."connection_type",
    "rpc_url" = EXCLUDED."rpc_url",
    "auth_type" = EXCLUDED."auth_type",
    "auth_header_name" = EXCLUDED."auth_header_name",
    "username" = EXCLUDED."username",
    "password" = EXCLUDED."password",
    "priority" = EXCLUDED."priority",
    "min_request_interval_ms" = EXCLUDED."min_request_interval_ms",
    "enabled" = EXCLUDED."enabled",
    "remark" = EXCLUDED."remark",
    "updated_at" = now();

INSERT INTO "public"."chain_rpc_node" ("chain", "network", "environment", "node_label", "purpose",
                                       "connection_type", "rpc_url", "auth_type", "auth_header_name",
                                       "priority", "min_request_interval_ms", "enabled", "remark",
                                       "created_at", "updated_at", "api_key")
VALUES
    ('ADA', 'preprod', 'dev', 'cardano-preprod-native', 'rpc', 'GRPC',
     'https://CHANGE_ME_CARDANO_PREPROD_NATIVE_RPC:443', 'NONE', NULL, 10, 500, false,
     'Native cardano-node UTxORPC v1beta TLS endpoint; configure genesis-matched node and explicit auth before enabling.',
     now(), now(), NULL),
    ('ADA', 'preprod', 'test2', 'cardano-preprod-native', 'rpc', 'GRPC',
     'https://CHANGE_ME_CARDANO_PREPROD_NATIVE_RPC:443', 'NONE', NULL, 10, 500, false,
     'test2 native Cardano UTxORPC endpoint; no auxiliary indexer or wallet private keys.',
     now(), now(), NULL),
    ('ADA', 'mainnet', 'prod', 'cardano-mainnet-native', 'rpc', 'GRPC',
     'https://CHANGE_ME_CARDANO_MAINNET_NATIVE_RPC:443', 'NONE', NULL, 10, 1000, false,
     'Production native UTxORPC placeholder. Configure TLS, node authentication and monitoring before enabling.',
     now(), now(), NULL)
ON CONFLICT ("chain", "network", "environment", "purpose", "node_label") DO UPDATE SET
    "connection_type" = EXCLUDED."connection_type",
    "rpc_url" = EXCLUDED."rpc_url",
    "auth_type" = EXCLUDED."auth_type",
    "auth_header_name" = EXCLUDED."auth_header_name",
    "api_key" = EXCLUDED."api_key",
    "priority" = EXCLUDED."priority",
    "min_request_interval_ms" = EXCLUDED."min_request_interval_ms",
    "enabled" = EXCLUDED."enabled",
    "remark" = EXCLUDED."remark",
    "updated_at" = now();

INSERT INTO "public"."chain_rpc_node" ("chain", "network", "environment", "node_label", "purpose",
                                       "connection_type", "rpc_url", "auth_type", "auth_header_name",
                                       "priority", "min_request_interval_ms", "enabled", "remark",
                                       "created_at", "updated_at", "api_key")
VALUES
    ('DOT', 'westend', 'dev', 'polkadot-westend-assethub-ws', 'rpc', 'WS_RPC',
     'wss://westend-asset-hub-rpc.polkadot.io', 'NONE', NULL, 10, 250, false,
     'Official Westend Asset Hub WebSocket node used for DOT, USDT and USDC public reads and externally signed wire.',
     now(), now(), NULL),
    ('DOT', 'westend', 'dev', 'dotters-westend-assethub-ws', 'rpc', 'WS_RPC',
     'wss://asset-hub-westend.dotters.network', 'NONE', NULL, 20, 250, false,
     'Backup Westend Asset Hub WebSocket node used for DOT, USDT and USDC public reads and externally signed wire.',
     now(), now(), NULL),
    ('DOT', 'westend', 'dev', 'local-polkadot-runtime-service', 'runtime', 'HTTP_JSON',
     'http://127.0.0.1:8787', 'bearer', 'Authorization', 20, 100, false,
     'Local Polkadot runtime service backed by @polkadot/api. Fill api_key and enable only with the DOT profile during e2e tests.',
     now(), now(), 'CHANGE_ME_POLKADOT_RUNTIME_API_KEY'),
    ('DOT', 'westend', 'test2', 'polkadot-westend-assethub-ws', 'rpc', 'WS_RPC',
     'wss://westend-asset-hub-rpc.polkadot.io', 'NONE', NULL, 10, 250, false,
     'test2 official Westend Asset Hub WebSocket node used for DOT, USDT and USDC public reads and externally signed wire.',
     now(), now(), NULL),
    ('DOT', 'westend', 'test2', 'dotters-westend-assethub-ws', 'rpc', 'WS_RPC',
     'wss://asset-hub-westend.dotters.network', 'NONE', NULL, 20, 250, false,
     'test2 backup Westend Asset Hub WebSocket node used for DOT, USDT and USDC public reads and externally signed wire.',
     now(), now(), NULL),
    ('DOT', 'westend', 'test2', 'local-polkadot-runtime-service', 'runtime', 'HTTP_JSON',
     'http://127.0.0.1:8787', 'bearer', 'Authorization', 20, 100, false,
     'test2 Polkadot runtime service backed by @polkadot/api. Fill api_key and enable only during e2e tests.',
     now(), now(), 'CHANGE_ME_POLKADOT_RUNTIME_API_KEY'),
    ('DOT', 'mainnet', 'prod', 'polkadot-assethub-ws', 'rpc', 'WS_RPC',
     'wss://polkadot-asset-hub-rpc.polkadot.io', 'NONE', NULL, 10, 500, false,
     'Production official Polkadot Asset Hub WebSocket placeholder; enable only after MPC e2e tests and monitoring are ready.',
     now(), now(), NULL),
    ('DOT', 'mainnet', 'prod', 'dotters-polkadot-assethub-ws', 'rpc', 'WS_RPC',
     'wss://asset-hub-polkadot.dotters.network', 'NONE', NULL, 20, 500, false,
     'Production backup Polkadot Asset Hub WebSocket placeholder.',
     now(), now(), NULL),
    ('DOT', 'mainnet', 'prod', 'private-polkadot-runtime-service', 'runtime', 'HTTP_JSON',
     'http://127.0.0.1:8787', 'bearer', 'Authorization', 20, 100, false,
     'Production Polkadot runtime service placeholder. Bind privately and use a strong API key.',
     now(), now(), 'CHANGE_ME_POLKADOT_RUNTIME_API_KEY')
ON CONFLICT ("chain", "network", "environment", "purpose", "node_label") DO UPDATE SET
    "connection_type" = EXCLUDED."connection_type",
    "rpc_url" = EXCLUDED."rpc_url",
    "auth_type" = EXCLUDED."auth_type",
    "auth_header_name" = EXCLUDED."auth_header_name",
    "api_key" = EXCLUDED."api_key",
    "priority" = EXCLUDED."priority",
    "min_request_interval_ms" = EXCLUDED."min_request_interval_ms",
    "enabled" = EXCLUDED."enabled",
    "remark" = EXCLUDED."remark",
    "updated_at" = now();

-- Safety guard: seeded RPC rows with placeholder URLs or credentials must stay disabled
-- until the target environment stores real provider values in the database.
UPDATE "public"."chain_rpc_node"
   SET "enabled" = false,
       "updated_at" = now()
 WHERE "enabled" = true
   AND (
        upper(coalesce("rpc_url", '')) LIKE '%CHANGE_ME%'
     OR upper(coalesce("api_key", '')) LIKE '%CHANGE_ME%'
     OR upper(coalesce("username", '')) LIKE '%CHANGE_ME%'
     OR upper(coalesce("password", '')) LIKE '%CHANGE_ME%'
     OR upper(coalesce("rpc_url", '')) LIKE '%YOUR_%'
     OR upper(coalesce("api_key", '')) LIKE '%YOUR_%'
     OR upper(coalesce("username", '')) LIKE '%YOUR_%'
     OR upper(coalesce("password", '')) LIKE '%YOUR_%'
     OR upper(coalesce("rpc_url", '')) LIKE '%REPLACE_ME%'
     OR upper(coalesce("api_key", '')) LIKE '%REPLACE_ME%'
     OR upper(coalesce("username", '')) LIKE '%REPLACE_ME%'
     OR upper(coalesce("password", '')) LIKE '%REPLACE_ME%'
     OR upper(coalesce("rpc_url", '')) LIKE '%TODO_%'
     OR upper(coalesce("api_key", '')) LIKE '%TODO_%'
     OR upper(coalesce("username", '')) LIKE '%TODO_%'
     OR upper(coalesce("password", '')) LIKE '%TODO_%'
   );


--
-- PostgreSQL database dump complete
--



SET search_path TO public;

-- Reference prices make stablecoin aggregation usable from a clean development database.
-- Platform administrators can replace these snapshots through /custody/platform/v1/asset-prices.
INSERT INTO custody_asset_price(asset_symbol, usd_price, source, observed_at)
VALUES ('USDT', 1, 'STABLECOIN_REFERENCE', now()),
       ('USDC', 1, 'STABLECOIN_REFERENCE', now())
ON CONFLICT (asset_symbol) DO NOTHING;
BEGIN;

SELECT pg_advisory_xact_lock(hashtext('surprising-wallet:20260723:custody-finality-recovery'));

CREATE TABLE IF NOT EXISTS public.chain_scan_block (
    chain character varying(32) NOT NULL,
    scanner_name character varying(64) NOT NULL,
    block_height bigint NOT NULL,
    block_hash character varying(128) NOT NULL,
    parent_hash character varying(128),
    observed_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chain_scan_block_pkey PRIMARY KEY (chain, scanner_name, block_height)
);

CREATE INDEX IF NOT EXISTS chain_scan_block_recent_idx
    ON public.chain_scan_block (chain, scanner_name, block_height DESC);

ALTER TABLE public.deposit_record
    ADD COLUMN IF NOT EXISTS block_hash character varying(128),
    ADD COLUMN IF NOT EXISTS credit_generation integer DEFAULT 0 NOT NULL,
    ADD COLUMN IF NOT EXISTS canonical_status character varying(24) DEFAULT 'CANONICAL' NOT NULL,
    ADD COLUMN IF NOT EXISTS account_id character varying(160),
    ADD COLUMN IF NOT EXISTS reorged_at timestamp with time zone,
    ADD COLUMN IF NOT EXISTS reorg_reason text;

UPDATE public.deposit_record deposit
   SET account_id = COALESCE(
       (SELECT address.account_id
          FROM public.chain_address address
         WHERE address.tenant_id = deposit.tenant_id
           AND address.chain = deposit.chain
           AND lower(address.address) = lower(deposit.to_address)
         ORDER BY address.id
         LIMIT 1),
       lower(deposit.to_address))
 WHERE account_id IS NULL;

ALTER TABLE public.deposit_record
    ALTER COLUMN account_id SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'public.deposit_record'::regclass
           AND conname = 'deposit_record_canonical_status_check'
    ) THEN
        ALTER TABLE public.deposit_record
            ADD CONSTRAINT deposit_record_canonical_status_check
            CHECK (canonical_status IN ('CANONICAL', 'REORGED'));
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'public.deposit_record'::regclass
           AND conname = 'deposit_record_credit_generation_check'
    ) THEN
        ALTER TABLE public.deposit_record
            ADD CONSTRAINT deposit_record_credit_generation_check
            CHECK (credit_generation >= 0);
    END IF;
END;
$$;

ALTER TABLE public.utxo_record
    ADD COLUMN IF NOT EXISTS block_hash character varying(128);

UPDATE public.utxo_record utxo
   SET block_hash = deposit.block_hash
  FROM public.deposit_record deposit
 WHERE utxo.block_hash IS NULL
   AND deposit.chain = utxo.chain
   AND lower(deposit.tx_hash) = lower(utxo.tx_hash)
   AND deposit.log_index = utxo.vout
   AND deposit.block_hash IS NOT NULL;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM public.utxo_record WHERE block_hash IS NULL) THEN
        RAISE EXCEPTION
            'utxo_record has rows without canonical block hashes; backfill them from the chain before deployment';
    END IF;
END;
$$;

ALTER TABLE public.utxo_record
    ALTER COLUMN block_hash SET NOT NULL;

CREATE TABLE IF NOT EXISTS public.custody_asset_recovery (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    custody_address_id uuid,
    actual_chain character varying(32) NOT NULL,
    expected_chain character varying(32),
    asset_symbol character varying(32) NOT NULL,
    token_contract character varying(128),
    token_decimals integer,
    tx_hash character varying(128) NOT NULL,
    log_index bigint DEFAULT 0 NOT NULL,
    requested_log_index bigint,
    destination_address character varying(160) NOT NULL,
    recovery_address character varying(160),
    claimed_amount numeric(78,24),
    verified_amount numeric(78,24),
    block_height bigint,
    block_hash character varying(128),
    confirmations integer DEFAULT 0 NOT NULL,
    status character varying(32) DEFAULT 'SUBMITTED' NOT NULL,
    verification_details jsonb DEFAULT '{}'::jsonb NOT NULL,
    failure_reason text,
    recovery_tx_hash character varying(128),
    requested_by uuid NOT NULL,
    reviewed_by uuid,
    executed_by uuid,
    approved_at timestamp with time zone,
    executed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT custody_asset_recovery_pkey PRIMARY KEY (id),
    CONSTRAINT custody_asset_recovery_tenant_id_key UNIQUE (tenant_id, id),
    CONSTRAINT custody_asset_recovery_business_key UNIQUE (actual_chain, tx_hash, log_index),
    CONSTRAINT custody_asset_recovery_status_check CHECK (
        status IN ('SUBMITTED', 'VERIFIED', 'APPROVED', 'EXECUTING', 'BROADCAST',
                   'RECOVERED', 'REJECTED', 'CANCELLED')),
    CONSTRAINT custody_asset_recovery_decimals_check
        CHECK (token_decimals IS NULL OR token_decimals BETWEEN 0 AND 36),
    CONSTRAINT custody_asset_recovery_requested_log_index_check
        CHECK (requested_log_index IS NULL OR requested_log_index >= 0),
    CONSTRAINT custody_asset_recovery_tenant_id_fkey FOREIGN KEY (tenant_id)
        REFERENCES public.custody_tenant(id) ON DELETE RESTRICT,
    CONSTRAINT custody_asset_recovery_address_fk FOREIGN KEY (tenant_id, custody_address_id)
        REFERENCES public.custody_address(tenant_id, id) ON DELETE RESTRICT
);

CREATE INDEX IF NOT EXISTS custody_asset_recovery_tenant_status_idx
    ON public.custody_asset_recovery (tenant_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS custody_asset_recovery_platform_status_idx
    ON public.custody_asset_recovery (status, created_at DESC);

CREATE TABLE IF NOT EXISTS public.custody_reorg_deficit (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    custody_address_id uuid NOT NULL,
    deposit_record_id bigint NOT NULL,
    chain character varying(32) NOT NULL,
    asset_symbol character varying(32) NOT NULL,
    account_id character varying(160) NOT NULL,
    original_amount numeric(78,24) NOT NULL,
    deficit_amount numeric(78,24) NOT NULL,
    recovered_amount numeric(78,24) DEFAULT 0 NOT NULL,
    status character varying(24) DEFAULT 'OPEN' NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT custody_reorg_deficit_pkey PRIMARY KEY (id),
    CONSTRAINT custody_reorg_deficit_tenant_id_key UNIQUE (tenant_id, id),
    CONSTRAINT custody_reorg_deficit_deposit_key UNIQUE (tenant_id, deposit_record_id),
    CONSTRAINT custody_reorg_deficit_status_check CHECK (status IN ('OPEN', 'RECOVERED')),
    CONSTRAINT custody_reorg_deficit_amount_check CHECK (
        deficit_amount > 0 AND recovered_amount >= 0 AND recovered_amount <= deficit_amount),
    CONSTRAINT custody_reorg_deficit_tenant_id_fkey FOREIGN KEY (tenant_id)
        REFERENCES public.custody_tenant(id) ON DELETE RESTRICT,
    CONSTRAINT custody_reorg_deficit_address_fk FOREIGN KEY (tenant_id, custody_address_id)
        REFERENCES public.custody_address(tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT custody_reorg_deficit_deposit_record_fkey FOREIGN KEY (tenant_id, deposit_record_id)
        REFERENCES public.deposit_record(tenant_id, id) ON DELETE RESTRICT
);

CREATE INDEX IF NOT EXISTS custody_reorg_deficit_account_idx
    ON public.custody_reorg_deficit (tenant_id, chain, asset_symbol, account_id, status);

COMMIT;
BEGIN;

SELECT pg_advisory_xact_lock(hashtext('surprising-wallet:20260723:eip7702-native-batch'));

ALTER TABLE public.evm_7702_config
    ADD COLUMN IF NOT EXISTS payout_delegate_address character varying(42),
    ADD COLUMN IF NOT EXISTS payout_delegate_code_hash character varying(66),
    ADD COLUMN IF NOT EXISTS native_collection_enabled boolean DEFAULT false NOT NULL,
    ADD COLUMN IF NOT EXISTS batch_withdrawal_enabled boolean DEFAULT false NOT NULL,
    ADD COLUMN IF NOT EXISTS withdrawal_max_wait_ms integer DEFAULT 3000 NOT NULL,
    ADD COLUMN IF NOT EXISTS withdrawal_max_batch_items integer DEFAULT 20 NOT NULL;

-- Existing configurations remain non-active and use the collection delegate as a
-- safe placeholder until an operator deploys and verifies the payout delegate.
UPDATE public.evm_7702_config
   SET payout_delegate_address = delegate_address,
       payout_delegate_code_hash = delegate_code_hash
 WHERE payout_delegate_address IS NULL
    OR payout_delegate_code_hash IS NULL;

ALTER TABLE public.evm_7702_config
    ALTER COLUMN payout_delegate_address SET NOT NULL,
    ALTER COLUMN payout_delegate_code_hash SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'public.evm_7702_config'::regclass
           AND conname = 'evm_7702_config_withdraw_wait_check'
    ) THEN
        ALTER TABLE public.evm_7702_config
            ADD CONSTRAINT evm_7702_config_withdraw_wait_check
            CHECK (withdrawal_max_wait_ms BETWEEN 0 AND 60000);
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'public.evm_7702_config'::regclass
           AND conname = 'evm_7702_config_withdraw_batch_check'
    ) THEN
        ALTER TABLE public.evm_7702_config
            ADD CONSTRAINT evm_7702_config_withdraw_batch_check
            CHECK (withdrawal_max_batch_items BETWEEN 1 AND 100);
    END IF;
END;
$$;

ALTER TABLE public.custody_gas_usage
    DROP CONSTRAINT IF EXISTS custody_gas_usage_operation_type_check;
ALTER TABLE public.custody_gas_usage
    ADD CONSTRAINT custody_gas_usage_operation_type_check
    CHECK (operation_type IN ('WITHDRAWAL', 'COLLECTION_BATCH', 'WITHDRAWAL_BATCH', 'ADDRESS_PREPARATION'));

CREATE TABLE IF NOT EXISTS public.evm_7702_payout_account (
    id uuid NOT NULL PRIMARY KEY,
    tenant_id uuid NOT NULL,
    chain character varying(32) NOT NULL,
    network character varying(64) NOT NULL,
    chain_address_id bigint NOT NULL,
    authority_address character varying(42) NOT NULL,
    delegate_address character varying(42),
    delegate_version integer,
    delegation_status character varying(24) DEFAULT 'NOT_DELEGATED' NOT NULL,
    observed_authority_nonce numeric(78,0),
    observed_operation_nonce numeric(78,0),
    activation_tx_hash character varying(128),
    revocation_tx_hash character varying(128),
    last_code_hash character varying(66),
    last_observed_block bigint,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT evm_7702_payout_account_tenant_chain_key UNIQUE (tenant_id, chain),
    CONSTRAINT evm_7702_payout_account_tenant_authority_key
        UNIQUE (tenant_id, chain, authority_address),
    CONSTRAINT evm_7702_payout_account_status_check CHECK (
        delegation_status IN ('NOT_DELEGATED', 'DELEGATING', 'ACTIVE', 'REVOKING',
                              'REVOKED', 'UNKNOWN', 'MANUAL_REVIEW')),
    CONSTRAINT evm_7702_payout_account_tenant_fk FOREIGN KEY (tenant_id)
        REFERENCES public.custody_tenant(id) ON DELETE RESTRICT,
    CONSTRAINT evm_7702_payout_account_address_fk FOREIGN KEY (tenant_id, chain_address_id)
        REFERENCES public.chain_address(tenant_id, id) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS public.evm_withdrawal_batch (
    id uuid NOT NULL PRIMARY KEY,
    tenant_id uuid NOT NULL,
    chain character varying(32) NOT NULL,
    network character varying(64) NOT NULL,
    asset_symbol character varying(32) NOT NULL,
    token_contract character varying(42) NOT NULL,
    token_decimals integer NOT NULL,
    hot_wallet character varying(42) NOT NULL,
    relayer_address character varying(42) NOT NULL,
    delegate_version integer NOT NULL,
    batch_hash character varying(66) NOT NULL,
    claim_payload jsonb NOT NULL,
    status character varying(32) NOT NULL,
    item_count integer NOT NULL,
    authorization_included boolean DEFAULT false NOT NULL,
    authorization_nonce numeric(78,0),
    operation_nonce numeric(78,0),
    signature_deadline timestamp with time zone,
    estimated_gas bigint,
    gas_limit bigint,
    max_fee_per_gas numeric(78,0),
    max_priority_fee_per_gas numeric(78,0),
    canonical_tx_hash character varying(128),
    actual_gas_used bigint,
    effective_gas_price numeric(78,0),
    l2_fee_atomic numeric(78,0),
    l1_fee_atomic numeric(78,0),
    operator_fee_atomic numeric(78,0),
    total_fee_atomic numeric(78,0),
    actual_fee numeric(78,24),
    confirmed_block_number bigint,
    confirmed_block_hash character varying(128),
    error_code character varying(64),
    error_message text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    submitted_at timestamp with time zone,
    confirmed_at timestamp with time zone,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT evm_withdrawal_batch_tenant_id_key UNIQUE (tenant_id, id),
    CONSTRAINT evm_withdrawal_batch_hash_key UNIQUE (chain, batch_hash),
    CONSTRAINT evm_withdrawal_batch_item_count_check CHECK (item_count BETWEEN 1 AND 100),
    CONSTRAINT evm_withdrawal_batch_status_check CHECK (
        status IN ('LOCKED', 'SIGNING', 'SUBMITTED', 'BROADCAST_UNKNOWN', 'CONFIRMING',
                   'CONFIRMED', 'PARTIAL_FAILED', 'FAILED', 'REORGED', 'MANUAL_REVIEW')),
    CONSTRAINT evm_withdrawal_batch_tenant_fk FOREIGN KEY (tenant_id)
        REFERENCES public.custody_tenant(id) ON DELETE RESTRICT,
    CONSTRAINT evm_withdrawal_batch_claim_check CHECK (
        jsonb_typeof(claim_payload) = 'object'
        AND claim_payload ?& ARRAY['id','tenantId','chain','network','batchHash']
        AND (claim_payload->>'id') IS NOT DISTINCT FROM id::text
        AND (claim_payload->>'tenantId') IS NOT DISTINCT FROM tenant_id::text
        AND (claim_payload->>'chain') IS NOT DISTINCT FROM chain
        AND (claim_payload->>'network') IS NOT DISTINCT FROM network
        AND (claim_payload->>'batchHash') IS NOT DISTINCT FROM batch_hash)
);

CREATE UNIQUE INDEX IF NOT EXISTS evm_withdrawal_batch_tx_key
    ON public.evm_withdrawal_batch (chain, canonical_tx_hash)
    WHERE canonical_tx_hash IS NOT NULL;
CREATE INDEX IF NOT EXISTS evm_withdrawal_batch_work_idx
    ON public.evm_withdrawal_batch (chain, network, status, updated_at);

CREATE TABLE IF NOT EXISTS public.evm_withdrawal_batch_item (
    id uuid NOT NULL PRIMARY KEY,
    tenant_id uuid NOT NULL,
    batch_id uuid NOT NULL,
    item_index integer NOT NULL,
    withdrawal_order_id bigint NOT NULL,
    custody_withdrawal_id uuid NOT NULL,
    withdrawal_id_hash character varying(66) NOT NULL,
    recipient character varying(42) NOT NULL,
    token_contract character varying(42) NOT NULL,
    requested_amount_atomic numeric(78,0) NOT NULL,
    actual_received_atomic numeric(78,0),
    call_gas_limit bigint NOT NULL,
    status character varying(32) NOT NULL,
    log_index integer,
    error_code character varying(64),
    error_hash character varying(66),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT evm_withdrawal_batch_item_position_key UNIQUE (batch_id, item_index),
    CONSTRAINT evm_withdrawal_batch_item_order_key UNIQUE (batch_id, withdrawal_order_id),
    CONSTRAINT evm_withdrawal_batch_item_hash_key UNIQUE (batch_id, withdrawal_id_hash),
    CONSTRAINT evm_withdrawal_batch_item_amount_check CHECK (requested_amount_atomic > 0),
    CONSTRAINT evm_withdrawal_batch_item_received_check
        CHECK (actual_received_atomic IS NULL OR actual_received_atomic >= 0),
    CONSTRAINT evm_withdrawal_batch_item_status_check CHECK (
        status IN ('CREATED', 'SIGNED', 'SUBMITTED', 'CONFIRMED', 'RETRYABLE',
                   'FAILED', 'REORGED', 'MANUAL_REVIEW')),
    CONSTRAINT evm_withdrawal_batch_item_batch_fk FOREIGN KEY (tenant_id, batch_id)
        REFERENCES public.evm_withdrawal_batch(tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT evm_withdrawal_batch_item_order_fk FOREIGN KEY (tenant_id, withdrawal_order_id)
        REFERENCES public.withdrawal_order(tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT evm_withdrawal_batch_item_custody_fk FOREIGN KEY (tenant_id, custody_withdrawal_id)
        REFERENCES public.custody_withdrawal(tenant_id, id) ON DELETE RESTRICT
);

CREATE UNIQUE INDEX IF NOT EXISTS evm_withdrawal_batch_item_active_order_key
    ON public.evm_withdrawal_batch_item (withdrawal_order_id)
    WHERE status IN ('CREATED', 'SIGNED', 'SUBMITTED');

CREATE TABLE IF NOT EXISTS public.evm_withdrawal_batch_attempt (
    id uuid NOT NULL PRIMARY KEY,
    tenant_id uuid NOT NULL,
    batch_id uuid NOT NULL,
    attempt_no integer NOT NULL,
    relayer_nonce numeric(78,0) NOT NULL,
    tx_hash character varying(128) NOT NULL,
    max_fee_per_gas numeric(78,0) NOT NULL,
    max_priority_fee_per_gas numeric(78,0) NOT NULL,
    gas_limit bigint NOT NULL,
    calldata_hash character varying(66) NOT NULL,
    signed_tx_ciphertext text NOT NULL,
    encryption_key_version character varying(64) NOT NULL,
    status character varying(32) NOT NULL,
    error_code character varying(64),
    error_message text,
    replaced_by_tx_hash character varying(128),
    rebroadcast_count integer DEFAULT 0 NOT NULL,
    last_rebroadcast_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    submitted_at timestamp with time zone,
    observed_at timestamp with time zone,
    CONSTRAINT evm_withdrawal_batch_attempt_number_key UNIQUE (batch_id, attempt_no),
    CONSTRAINT evm_withdrawal_batch_attempt_tx_key UNIQUE (tx_hash),
    CONSTRAINT evm_withdrawal_batch_attempt_rebroadcast_count_check CHECK (rebroadcast_count >= 0),
    CONSTRAINT evm_withdrawal_batch_attempt_status_check CHECK (
        status IN ('CREATED', 'SUBMITTED', 'PENDING', 'CONFIRMED', 'DROPPED',
                   'REPLACED', 'FAILED', 'UNKNOWN')),
    CONSTRAINT evm_withdrawal_batch_attempt_batch_fk FOREIGN KEY (tenant_id, batch_id)
        REFERENCES public.evm_withdrawal_batch(tenant_id, id) ON DELETE RESTRICT
);

-- EVM 交易信封与总费用模型是两个独立维度：
-- gas_policy 只决定 legacy/type-2 报价与签名；fee_model 决定 L1/DA/operator 费用核算。
UPDATE public.chain_profile
SET fee_model = CASE
        WHEN chain = 'ARBITRUM' THEN 'arbitrum-nitro'
        WHEN chain IN ('OPTIMISM', 'BASE', 'UNICHAIN') THEN 'op-stack'
        WHEN chain = 'SCROLL' THEN 'scroll'
        ELSE 'standard'
    END,
    updated_at = now()
WHERE family = 'evm';

-- Normalize the legacy value used by older databases while keeping the
-- baseline compatible with the current runtime gas policy enum.
UPDATE public.chain_profile
SET gas_policy = 'eip1559',
    updated_at = now()
WHERE family = 'evm'
  AND gas_policy = 'eip1559-l2';

COMMIT;


-- =================================================================
--  Seeded test accounts (dev environment only)
--  Platform admin password = tenant admin password = the value of
--  SW_CUSTODY_PLATFORM_ADMIN_PASSWORD in the dev wallet.env
-- =================================================================

-- Platform administrator (tenant_id is null = platform-level)
INSERT INTO custody_tenant_user(
    id, tenant_id, email, display_name, password_hash, role, status)
VALUES (
    '5dece8cf-0ddc-4907-846f-3df9e6330cae',
    NULL,
    'admin@local.test',
    'Platform administrator',
    'pbkdf2-sha256$210000$bpHtkDbuvrQbwBeA4PJELg==$5m/0PBbJom2Ibw7Es8MPTtMHhnlMBHeXctednN5j8s0=',
    'PLATFORM_ADMIN',
    'ACTIVE')
ON CONFLICT (id) DO NOTHING;

-- 钱包定时任务数据库租约基线。
CREATE TABLE public.wallet_task_lease (
    task_name character varying(256) NOT NULL,
    owner_id character varying(256) NOT NULL,
    lease_until timestamp with time zone NOT NULL,
    heartbeat_at timestamp with time zone NOT NULL DEFAULT now(),
    updated_at timestamp with time zone NOT NULL DEFAULT now(),
    CONSTRAINT wallet_task_lease_pkey PRIMARY KEY (task_name)
);

ALTER TABLE public.withdrawal_order
    ADD COLUMN lease_owner character varying(256),
    ADD COLUMN lease_until timestamp with time zone,
    ADD COLUMN attempt_count integer NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at timestamp with time zone NOT NULL DEFAULT now(),
    ADD COLUMN last_error_code character varying(64),
    ADD COLUMN last_attempt_at timestamp with time zone;

ALTER TABLE public.chain_signing_transaction
    ADD COLUMN broadcast_owner character varying(256),
    ADD COLUMN broadcast_lease_until timestamp with time zone;

CREATE INDEX withdrawal_order_fair_queue_idx
    ON public.withdrawal_order(chain, asset_symbol, status, tenant_id, id)
    WHERE tenant_id IS NOT NULL AND status IN ('FROZEN', 'RETRYING');

CREATE INDEX withdrawal_order_lease_idx
    ON public.withdrawal_order(status, lease_until, next_attempt_at, id)
    WHERE status IN ('PROCESSING', 'SIGNING', 'BROADCAST_UNKNOWN');

CREATE INDEX chain_signing_transaction_broadcast_lease_idx
    ON public.chain_signing_transaction(status, broadcast_lease_until, id);

ALTER TABLE public.custody_withdrawal
    ADD CONSTRAINT custody_withdrawal_amount_check CHECK (amount > 0),
    ADD CONSTRAINT custody_withdrawal_fee_check CHECK (fee >= 0);

ALTER TABLE public.ledger_balance
    ADD CONSTRAINT ledger_balance_non_negative_check CHECK (
        available_balance >= 0 AND locked_balance >= 0 AND total_balance >= 0
    ),
    ADD CONSTRAINT ledger_balance_invariant_check CHECK (
        total_balance = available_balance + locked_balance
    );

ALTER TABLE public.withdrawal_order
    ADD CONSTRAINT withdrawal_order_amount_check CHECK (amount > 0 AND fee >= 0);

-- Development task default: only local BTC regtest and ETH devtest jobs are active.
UPDATE public.chain_profile
SET scan_enabled = (chain = 'BTC' AND network = 'regtest')
        OR (chain = 'ETH' AND network = 'devtest'),
    withdraw_enabled = (chain = 'BTC' AND network = 'regtest')
        OR (chain = 'ETH' AND network = 'devtest'),
    collection_enabled = (chain = 'BTC' AND network = 'regtest')
        OR (chain = 'ETH' AND network = 'devtest'),
    transfer_enabled = (chain = 'BTC' AND network = 'regtest')
        OR (chain = 'ETH' AND network = 'devtest'),
    updated_at = now();

-- Test tenant
INSERT INTO custody_tenant(id, slug, name)
VALUES (
    '00000000-0000-0000-0000-000000000001',
    'test-tenant',
    'Test Tenant')
ON CONFLICT (id) DO NOTHING;

-- Test tenant administrator
INSERT INTO custody_tenant_user(
    id, tenant_id, email, display_name, password_hash, role, status)
VALUES (
    '00000000-0000-0000-0000-000000000002',
    '00000000-0000-0000-0000-000000000001',
    'tenant@local.test',
    'Tenant Admin',
    'pbkdf2-sha256$210000$bpHtkDbuvrQbwBeA4PJELg==$5m/0PBbJom2Ibw7Es8MPTtMHhnlMBHeXctednN5j8s0=',
    'TENANT_ADMIN',
    'ACTIVE')
ON CONFLICT (id) DO NOTHING;


-- PGMQ was validated before any application tables were dropped.
DO $$
DECLARE q text;
BEGIN
    FOREACH q IN ARRAY ARRAY['wallet_mpc_sign', 'wallet_sign_done',
        'wallet_withdraw', 'wallet_deposit_event', 'wallet_withdraw_event', 'wallet_rbf']
    LOOP
        PERFORM pgmq.create(q);
        PERFORM pgmq.create(q || '_dead');
    END LOOP;
END $$;

CREATE TABLE public.chain_fee_rate (
    chain text PRIMARY KEY,
    fee_rate bigint NOT NULL CHECK (fee_rate > 0),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE public.chain_fee_rate IS 'Network fee quotes in atomic units per byte/vbyte; shared network metadata, not tenant balances';

-- Only an operator with explicit queue privileges may replay; archive retains the original.
-- Repeated calls for the same dead message are harmless. Reason and database actor are retained.
CREATE OR REPLACE FUNCTION public.wallet_replay_dead(q text, dead_id bigint, reason text)
RETURNS bigint LANGUAGE plpgsql AS $$
DECLARE m record; new_id bigint;
BEGIN
    IF q <> ALL(ARRAY['wallet_mpc_sign', 'wallet_sign_done',
        'wallet_withdraw','wallet_deposit_event','wallet_withdraw_event','wallet_rbf']) THEN
        RAISE EXCEPTION 'Unknown wallet queue';
    END IF;
    IF reason IS NULL OR length(trim(reason)) < 5 THEN
        RAISE EXCEPTION 'Replay requires an audit reason';
    END IF;
    EXECUTE format('SELECT message, headers FROM pgmq.%I WHERE msg_id = $1 FOR UPDATE', 'q_' || q || '_dead')
        INTO m USING dead_id;
    IF m.message IS NULL THEN RETURN NULL; END IF;
    SELECT pgmq.send(q, m.message, coalesce(m.headers, '{}'::jsonb) || jsonb_build_object(
        'replay_reason', reason, 'replay_actor', current_user, 'replay_at', clock_timestamp(),
        'replay_dead_id', dead_id)) INTO new_id;
    PERFORM pgmq.archive(q || '_dead', dead_id);
    RETURN new_id;
END $$;
REVOKE ALL ON FUNCTION public.wallet_replay_dead(text, bigint, text) FROM PUBLIC;

-- Signing identity comes from the typed immutable request; generated columns cannot drift.
ALTER TABLE public.chain_signing_transaction
    ADD CONSTRAINT chain_signing_transaction_tenant_fkey FOREIGN KEY (tenant_id) REFERENCES public.custody_tenant(id);

-- Pool and beneficiary identities are independent of the physical withdrawal source.
COMMENT ON COLUMN public.custody_withdrawal.subject IS '实际提现用户或客户标识，独立于出资/签名地址';
COMMENT ON COLUMN public.custody_withdrawal.funding_source IS '本金来源范围：ADDRESS 或 TENANT_POOL';
CREATE INDEX ledger_balance_pool_sources_idx ON public.ledger_balance(tenant_id,chain,asset_symbol,available_balance DESC,id) WHERE available_balance>0;
CREATE INDEX custody_withdrawal_subject_idx ON public.custody_withdrawal(tenant_id,subject,created_at DESC,id);

-- MPC metadata only: secret shares belong exclusively to independent signer nodes.
ALTER TABLE public.custody_tenant ADD CONSTRAINT custody_tenant_mpc_namespace_key UNIQUE (id,derivation_namespace);
CREATE TABLE public.wallet_mpc_key (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES public.custody_tenant(id),
    key_group varchar(32) NOT NULL,
    user_id bigint NOT NULL CHECK (user_id >= 0),
    biz integer NOT NULL CHECK (biz > 0),
    address_index bigint NOT NULL CHECK (address_index >= 0),
    algorithm varchar(32) NOT NULL CHECK (algorithm IN ('ECDSA_SECP256K1','ED25519','SCHNORR_BIP340','MONERO_NATIVE_2_OF_3')),
    state varchar(24) NOT NULL DEFAULT 'CREATING' CHECK (state IN ('CREATING','ACTIVE','REFRESHING','FAILED','RETIRED')),
    active_epoch integer NOT NULL DEFAULT 0 CHECK (active_epoch BETWEEN 0 AND 1000000),
    public_key_hex text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, id, algorithm),
    FOREIGN KEY (tenant_id,biz) REFERENCES public.custody_tenant(id,derivation_namespace),
    CHECK ((state IN ('CREATING','FAILED') AND active_epoch = 0 AND public_key_hex IS NULL)
        OR (state IN ('ACTIVE','REFRESHING','RETIRED') AND active_epoch > 0 AND public_key_hex IS NOT NULL)),
    CHECK (public_key_hex IS NULL OR CASE algorithm
        WHEN 'ECDSA_SECP256K1' THEN public_key_hex ~ '^(02|03)[0-9a-f]{64}$'
        WHEN 'MONERO_NATIVE_2_OF_3' THEN public_key_hex ~ '^[0-9a-f]{128}$'
        ELSE public_key_hex ~ '^[0-9a-f]{64}$' END)
);
CREATE UNIQUE INDEX wallet_mpc_key_coordinates_idx
    ON public.wallet_mpc_key(tenant_id,key_group,user_id,biz,address_index)
    WHERE state IN ('CREATING','ACTIVE','REFRESHING');

ALTER TABLE public.chain_address ADD CONSTRAINT chain_address_mpc_key_fkey
    FOREIGN KEY (tenant_id,mpc_key_id) REFERENCES public.wallet_mpc_key(tenant_id,id);
COMMENT ON COLUMN public.chain_address.mpc_key_id IS 'Immutable tenant-owned joint key; wallet stores public metadata only';

CREATE OR REPLACE FUNCTION public.chain_address_mpc_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.mpc_key_id IS NOT NULL AND
       ROW(NEW.tenant_id,NEW.mpc_key_id,NEW.chain,NEW.asset_symbol,NEW.account_id,NEW.user_id,NEW.biz,
           NEW.address_index,NEW.address,NEW.owner_address,NEW.derivation_path,NEW.wallet_role)
       IS DISTINCT FROM
       ROW(OLD.tenant_id,OLD.mpc_key_id,OLD.chain,OLD.asset_symbol,OLD.account_id,OLD.user_id,OLD.biz,
           OLD.address_index,OLD.address,OLD.owner_address,OLD.derivation_path,OLD.wallet_role) THEN
        RAISE EXCEPTION 'MPC chain address identity is immutable';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER chain_address_mpc_immutable BEFORE UPDATE ON public.chain_address
    FOR EACH ROW EXECUTE FUNCTION public.chain_address_mpc_immutable();

CREATE TABLE public.wallet_mpc_key_epoch (
    request jsonb NOT NULL,
    approval_registration jsonb CHECK (approval_registration IS NULL OR jsonb_typeof(approval_registration)='object'),
    tenant_id uuid GENERATED ALWAYS AS ((request->>'tenantId')::uuid) STORED NOT NULL,
    key_id uuid GENERATED ALWAYS AS ((request->>'keyId')::uuid) STORED NOT NULL,
    epoch integer GENERATED ALWAYS AS ((request->>'epoch')::integer) STORED NOT NULL CHECK (epoch BETWEEN 1 AND 1000000),
    session_id uuid GENERATED ALWAYS AS ((request->>'sessionId')::uuid) STORED NOT NULL UNIQUE,
    algorithm varchar(32) GENERATED ALWAYS AS (request->>'algorithm') STORED NOT NULL,
    state varchar(24) NOT NULL DEFAULT 'PREPARING' CHECK (state IN ('PREPARING','ACTIVE','REFRESHING','FAILED','RETIRED')),
    node_results jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(node_results) = 'object'),
    refresh_actor varchar(192),
    refresh_reason varchar(512),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (key_id,epoch),
    UNIQUE (tenant_id,key_id,epoch),
    UNIQUE (tenant_id,key_id,epoch,algorithm),
    FOREIGN KEY (tenant_id,key_id,algorithm) REFERENCES public.wallet_mpc_key(tenant_id,id,algorithm),
    CHECK (request->>'operation' IS NOT NULL AND request->>'operation' IN ('CREATE_KEY','REFRESH')),
    CHECK ((request->>'operation'='CREATE_KEY' AND epoch=1 AND refresh_actor IS NULL AND refresh_reason IS NULL)
        OR (request->>'operation'='REFRESH' AND epoch>1 AND algorithm<>'MONERO_NATIVE_2_OF_3'
            AND refresh_actor IS NOT NULL AND length(btrim(refresh_actor))>0
            AND refresh_reason IS NOT NULL AND length(btrim(refresh_reason))>=5)),
    CHECK (request->'online' IS NOT NULL AND jsonb_typeof(request->'online') = 'array'
        AND jsonb_array_length(request->'online') = 3),
    CHECK (request->'online'->>0 IS NOT NULL AND request->'online'->>1 IS NOT NULL AND request->'online'->>2 IS NOT NULL
        AND request->'online'->>0 ~ '^[0-9a-f]{64}$' AND request->'online'->>1 ~ '^[0-9a-f]{64}$'
        AND request->'online'->>2 ~ '^[0-9a-f]{64}$'
        AND request->'online'->>0 <> request->'online'->>1
        AND request->'online'->>0 <> request->'online'->>2
        AND request->'online'->>1 <> request->'online'->>2)
);

CREATE TABLE public.wallet_mpc_sign_request (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    key_id uuid NOT NULL,
    key_epoch integer NOT NULL CHECK (key_epoch > 0),
    chain varchar(32) NOT NULL,
    business_type varchar(32) NOT NULL CHECK (business_type IN ('WITHDRAWAL','COLLECTION','RECOVERY','FUNDING','ADDRESS_PREPARATION','DEV_FAUCET')),
    business_reference varchar(192) NOT NULL CHECK (length(btrim(business_reference)) > 0),
    business_references text[] NOT NULL CHECK (cardinality(business_references) BETWEEN 1 AND 100 AND array_ndims(business_references)=1 AND array_position(business_references,NULL) IS NULL),
    signing_slot varchar(96) NOT NULL CHECK (length(btrim(signing_slot)) > 0),
    signing_purpose varchar(48) NOT NULL CHECK (signing_purpose IN ('TRANSACTION','EIP7702_AUTHORIZATION','EIP7702_OPERATION')),
    payload_encoding varchar(48) NOT NULL CHECK (payload_encoding ~ '^[A-Z][A-Z0-9_]{0,47}$'),
    payload_hex text NOT NULL CHECK (payload_hex ~ '^[0-9a-f]+$' AND length(payload_hex) % 2 = 0),
    preimage_hex text NOT NULL CHECK (preimage_hex ~ '^[0-9a-f]*$' AND length(preimage_hex) % 2 = 0),
    preimage_hash text GENERATED ALWAYS AS (encode(sha256(decode(preimage_hex,'hex')),'hex')) STORED NOT NULL,
    state varchar(24) NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING','SIGNING','COMPLETED','INDETERMINATE','CANCELLED')),
    current_attempt_id uuid,
    cancellation_receipt jsonb,
    cancelled_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id,key_id,key_epoch) REFERENCES public.wallet_mpc_key_epoch(tenant_id,key_id,epoch),
    UNIQUE (tenant_id,chain,business_type,business_reference,signing_slot),
    UNIQUE (tenant_id,id,key_id,key_epoch,preimage_hash),
    CHECK ((state IN ('PENDING','CANCELLED') OR current_attempt_id IS NOT NULL) AND (state<>'PENDING' OR current_attempt_id IS NULL)),
    CHECK ((state='CANCELLED' AND cancelled_at IS NOT NULL AND cancellation_receipt IS NOT NULL
        AND jsonb_typeof(cancellation_receipt)='object' AND cancellation_receipt->>'status' IN ('REJECTED','EXPIRED')
        AND cancellation_receipt->>'signingStarted'='false' AND cancellation_receipt->'intent'->>'tenantId'=tenant_id::text)
        OR (state<>'CANCELLED' AND cancelled_at IS NULL AND cancellation_receipt IS NULL))
);
CREATE INDEX wallet_mpc_sign_request_pending_idx ON public.wallet_mpc_sign_request(state,created_at,id)
    WHERE state NOT IN ('COMPLETED','CANCELLED');

CREATE INDEX wallet_mpc_sign_request_business_members_idx ON public.wallet_mpc_sign_request USING gin(business_references);

CREATE TABLE public.wallet_mpc_sign_attempt (
    request_id uuid NOT NULL,
    request jsonb NOT NULL,
    authorization_proof jsonb CHECK (authorization_proof IS NULL OR ((jsonb_typeof(authorization_proof)='object'
        AND (authorization_proof->>'payloadHex') ~ '^[0-9a-f]+$' AND length(authorization_proof->>'payloadHex') BETWEEN 2 AND 8192
        AND length(authorization_proof->>'payloadHex') % 2=0 AND (authorization_proof->>'signatureHex') ~ '^[0-9a-f]{128}$') IS TRUE)),
    automatic_operation jsonb CHECK (automatic_operation IS NULL OR (jsonb_typeof(automatic_operation)='object' AND authorization_proof IS NOT NULL)),
    id uuid GENERATED ALWAYS AS ((request->>'sessionId')::uuid) STORED PRIMARY KEY,
    tenant_id uuid GENERATED ALWAYS AS ((request->>'tenantId')::uuid) STORED NOT NULL,
    key_id uuid GENERATED ALWAYS AS ((request->>'keyId')::uuid) STORED NOT NULL,
    key_epoch integer GENERATED ALWAYS AS ((request->>'epoch')::integer) STORED NOT NULL,
    algorithm varchar(32) GENERATED ALWAYS AS (request->>'algorithm') STORED NOT NULL,
    preimage_hash text GENERATED ALWAYS AS (encode(sha256(decode(request->>'messageHex','hex')),'hex')) STORED NOT NULL,
    state varchar(24) NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING','RUNNING','COMPLETED','INDETERMINATE')),
    signature_ciphertext text,
    node_results jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(node_results) = 'object'),
    replay_actor varchar(192),
    replay_reason varchar(512),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id,request_id,id),
    FOREIGN KEY (tenant_id,request_id,key_id,key_epoch,preimage_hash)
        REFERENCES public.wallet_mpc_sign_request(tenant_id,id,key_id,key_epoch,preimage_hash),
    FOREIGN KEY (tenant_id,key_id,key_epoch,algorithm)
        REFERENCES public.wallet_mpc_key_epoch(tenant_id,key_id,epoch,algorithm),
    CHECK (request->>'operation' IS NOT NULL AND request->>'operation' = 'SIGN'),
    CHECK (request->'online' IS NOT NULL AND jsonb_typeof(request->'online') = 'array'
        AND jsonb_array_length(request->'online') = 2),
    CHECK (request->'online'->>0 IS NOT NULL AND request->'online'->>1 IS NOT NULL
        AND request->'online'->>0 ~ '^[0-9a-f]{64}$' AND request->'online'->>1 ~ '^[0-9a-f]{64}$'
        AND request->'online'->>0 <> request->'online'->>1),
    CHECK (request->>'messageHex' IS NOT NULL AND request->>'messageHex' ~ '^[0-9a-f]*$'
        AND length(request->>'messageHex') % 2 = 0),
    CHECK (algorithm NOT IN ('ECDSA_SECP256K1','SCHNORR_BIP340') OR length(request->>'messageHex') = 64),
    CHECK ((state = 'COMPLETED') = (signature_ciphertext IS NOT NULL)),
    CHECK (signature_ciphertext IS NULL OR signature_ciphertext LIKE 'v1:%'),
    CHECK ((replay_actor IS NULL AND replay_reason IS NULL)
        OR (replay_actor IS NOT NULL AND replay_reason IS NOT NULL
            AND length(btrim(replay_actor)) > 0 AND length(btrim(replay_reason)) >= 5))
);
CREATE UNIQUE INDEX wallet_mpc_sign_attempt_running_idx ON public.wallet_mpc_sign_attempt(request_id)
    WHERE state IN ('PENDING','RUNNING');
ALTER TABLE public.wallet_mpc_sign_request ADD CONSTRAINT wallet_mpc_current_attempt_fkey
    FOREIGN KEY (tenant_id,id,current_attempt_id)
    REFERENCES public.wallet_mpc_sign_attempt(tenant_id,request_id,id);

-- Application code may advance state, but cannot replace accepted signing intent.
CREATE OR REPLACE FUNCTION public.wallet_mpc_sign_request_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'MPC signing receipts are permanent'; END IF;
    IF ROW(NEW.id,NEW.tenant_id,NEW.key_id,NEW.key_epoch,NEW.chain,NEW.business_type,
           NEW.business_reference,NEW.business_references,NEW.signing_slot,NEW.signing_purpose,NEW.payload_encoding,NEW.payload_hex,NEW.preimage_hex)
       IS DISTINCT FROM
       ROW(OLD.id,OLD.tenant_id,OLD.key_id,OLD.key_epoch,OLD.chain,OLD.business_type,
           OLD.business_reference,OLD.business_references,OLD.signing_slot,OLD.signing_purpose,OLD.payload_encoding,OLD.payload_hex,OLD.preimage_hex)
       OR (OLD.state IN ('COMPLETED','CANCELLED') AND ROW(NEW.state,NEW.current_attempt_id,NEW.cancellation_receipt,NEW.cancelled_at)
           IS DISTINCT FROM ROW(OLD.state,OLD.current_attempt_id,OLD.cancellation_receipt,OLD.cancelled_at)) THEN
        RAISE EXCEPTION 'MPC accepted signing intent or completed result is immutable';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER wallet_mpc_sign_request_immutable BEFORE UPDATE OR DELETE ON public.wallet_mpc_sign_request
    FOR EACH ROW EXECUTE FUNCTION public.wallet_mpc_sign_request_immutable();

CREATE OR REPLACE FUNCTION public.wallet_mpc_sign_attempt_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.request_id,NEW.request,NEW.replay_actor,NEW.replay_reason)
       IS DISTINCT FROM ROW(OLD.request_id,OLD.request,OLD.replay_actor,OLD.replay_reason)
       OR (OLD.authorization_proof IS NOT NULL AND ROW(NEW.authorization_proof,NEW.automatic_operation) IS DISTINCT FROM ROW(OLD.authorization_proof,OLD.automatic_operation))
       OR (OLD.state = 'INDETERMINATE' AND NEW.state NOT IN ('INDETERMINATE','COMPLETED'))
       OR (OLD.state = 'COMPLETED' AND ROW(NEW.state,NEW.signature_ciphertext)
           IS DISTINCT FROM ROW(OLD.state,OLD.signature_ciphertext)) THEN
        RAISE EXCEPTION 'MPC accepted attempt or completed signature is immutable';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER wallet_mpc_sign_attempt_immutable BEFORE UPDATE ON public.wallet_mpc_sign_attempt
    FOR EACH ROW EXECUTE FUNCTION public.wallet_mpc_sign_attempt_immutable();

-- Native Monero relay is a durable original operation, never a fresh payment or an unsigned side channel.
CREATE TABLE public.wallet_mpc_relay (
    tenant_id uuid NOT NULL,
    request_id uuid NOT NULL,
    sign_session_id uuid NOT NULL,
    holder varchar(64) NOT NULL CHECK (holder ~ '^[0-9a-f]{64}$'),
    request jsonb NOT NULL CHECK (jsonb_typeof(request)='object'),
    authorization_proof jsonb CHECK (authorization_proof IS NULL OR jsonb_typeof(authorization_proof)='object'),
    session_id uuid GENERATED ALWAYS AS ((request->>'sessionId')::uuid) STORED,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id,request_id,holder),
    UNIQUE (tenant_id,session_id),
    FOREIGN KEY (tenant_id,request_id,sign_session_id) REFERENCES public.wallet_mpc_sign_attempt(tenant_id,request_id,id),
    CHECK ((request->>'tenantId'=tenant_id::text AND request->>'operation'='MONERO_RELAY'
        AND request->>'algorithm'='MONERO_NATIVE_2_OF_3' AND jsonb_array_length(request->'online')=1
        AND request->'online'->>0=holder) IS TRUE)
);
CREATE OR REPLACE FUNCTION public.wallet_mpc_relay_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'original Monero relay receipts are permanent' USING ERRCODE='23514'; END IF;
    IF ROW(NEW.tenant_id,NEW.request_id,NEW.sign_session_id,NEW.holder,NEW.request,NEW.created_at)
        IS DISTINCT FROM ROW(OLD.tenant_id,OLD.request_id,OLD.sign_session_id,OLD.holder,OLD.request,OLD.created_at)
        OR (OLD.authorization_proof IS NOT NULL AND NEW.authorization_proof IS DISTINCT FROM OLD.authorization_proof) THEN
        RAISE EXCEPTION 'original Monero relay descriptor/proof is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER wallet_mpc_relay_immutable BEFORE UPDATE OR DELETE ON public.wallet_mpc_relay
    FOR EACH ROW EXECUTE FUNCTION public.wallet_mpc_relay_immutable();

CREATE OR REPLACE FUNCTION public.wallet_mpc_key_epoch_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.request,NEW.refresh_actor,NEW.refresh_reason)
       IS DISTINCT FROM ROW(OLD.request,OLD.refresh_actor,OLD.refresh_reason)
       OR (OLD.approval_registration IS NOT NULL AND NEW.approval_registration IS DISTINCT FROM OLD.approval_registration)
       OR (OLD.state='RETIRED' AND NEW.state<>'RETIRED')
       OR (OLD.state='ACTIVE' AND NEW.state NOT IN ('ACTIVE','RETIRED'))
       OR (OLD.state IN ('ACTIVE','RETIRED') AND NEW.node_results IS DISTINCT FROM OLD.node_results) THEN
        RAISE EXCEPTION 'MPC accepted key ceremony is immutable';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER wallet_mpc_key_epoch_immutable BEFORE UPDATE ON public.wallet_mpc_key_epoch
    FOR EACH ROW EXECUTE FUNCTION public.wallet_mpc_key_epoch_immutable();

CREATE OR REPLACE FUNCTION public.wallet_mpc_key_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.id,NEW.tenant_id,NEW.key_group,NEW.user_id,NEW.biz,NEW.address_index,NEW.algorithm)
       IS DISTINCT FROM ROW(OLD.id,OLD.tenant_id,OLD.key_group,OLD.user_id,OLD.biz,OLD.address_index,OLD.algorithm)
       OR (OLD.public_key_hex IS NOT NULL AND NEW.public_key_hex IS DISTINCT FROM OLD.public_key_hex) THEN
        RAISE EXCEPTION 'MPC key ownership, coordinates and published public key are immutable';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER wallet_mpc_key_immutable BEFORE UPDATE ON public.wallet_mpc_key
    FOR EACH ROW EXECUTE FUNCTION public.wallet_mpc_key_immutable();

-- TRON outgoing MPC receipts use the existing transaction table; signatures and tenant ownership remain bound by MPC intent/business rows.
COMMENT ON COLUMN public.tron_tx.status IS 'Observed NEW/CONFIRMING, or outgoing PREPARED after raw/business-hash commit, SENT after matching node observation, then CONFIRMED/FAILED';
COMMENT ON COLUMN public.tron_tx.raw_payload IS 'First persisted raw fact; outgoing MPC transactions store canonical signed protobuf hex and confirmation must preserve it';

COMMENT ON TABLE public.tron_tx IS 'TRON network transaction facts; outgoing MPC raw is committed with owned business hash before RPC, solidified CONFIRMED/FAILED fees are retained atomically with settlement';

-- Sui owned Coin admission; active MPC key locks serialize sender reservations across assets.
CREATE INDEX sui_transaction_pending_sender_idx ON public.sui_transaction(sender)
    WHERE status IN ('SIGNING','PREPARED','SENT','CONFIRMING');


-- DOT custody native and tokens share Asset Hub nonce; selected hash includes the MPC witness.
ALTER TABLE public.wallet_mpc_sign_request ADD CONSTRAINT wallet_mpc_sign_request_tenant_id_key UNIQUE (tenant_id,id);
ALTER TABLE public.hypercore_action_record ADD CONSTRAINT hypercore_action_record_request_fk
    FOREIGN KEY (tenant_id,request_id) REFERENCES public.wallet_mpc_sign_request(tenant_id,id);
CREATE TABLE public.polkadot_transaction (
    request_id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    role varchar(16) NOT NULL CHECK (role = 'ASSET_HUB'),
    sender varchar(50) NOT NULL,
    tx_hash varchar(66) UNIQUE CHECK (tx_hash IS NULL OR tx_hash ~ '^0x[0-9a-f]{64}$'),
    status varchar(16) NOT NULL CHECK (status IN ('SIGNING','PREPARED','SENT','CONFIRMING','CONFIRMED','FAILED')),
    raw_payload text,
    fee_planck numeric(39,0) CHECK (fee_planck IS NULL OR fee_planck > 0),
    native_decimals integer NOT NULL CHECK (native_decimals BETWEEN 0 AND 24),
    block_height bigint CHECK (block_height IS NULL OR block_height >= 0),
    execution_receipt jsonb CHECK (execution_receipt IS NULL OR jsonb_typeof(execution_receipt) = 'object'),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id,request_id) REFERENCES public.wallet_mpc_sign_request(tenant_id,id),
    CHECK ((status = 'SIGNING') = (tx_hash IS NULL AND raw_payload IS NULL)),
    CHECK ((tx_hash IS NULL) = (raw_payload IS NULL)),
    CHECK (status NOT IN ('CONFIRMING','CONFIRMED','FAILED') OR
        (execution_receipt IS NOT NULL AND fee_planck IS NOT NULL AND block_height IS NOT NULL))
);
CREATE UNIQUE INDEX polkadot_transaction_pending_sender_idx ON public.polkadot_transaction(role,sender)
    WHERE status IN ('SIGNING','PREPARED','SENT','CONFIRMING');
COMMENT ON TABLE public.polkadot_transaction IS 'Tenant-owned Asset Hub DOT MPC request, shared native/token nonce admission, original signed extrinsic and finalized payment/fee proof';

-- Native Cardano immutable payment admission. Body hash is known before the external witness.
CREATE TABLE public.cardano_transaction (
    request_id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    sender varchar(160) NOT NULL,
    tx_hash varchar(64) NOT NULL UNIQUE CHECK (tx_hash ~ '^[0-9a-f]{64}$'),
    status varchar(16) NOT NULL CHECK (status IN ('SIGNING','PREPARED','SENT','CONFIRMING','CONFIRMED','FAILED')),
    raw_payload text CHECK (raw_payload IS NULL OR
        (length(raw_payload) BETWEEN 2 AND 131072 AND raw_payload ~ '^([0-9a-f]{2})+$')),
    observed_height bigint NOT NULL CHECK (observed_height >= 0),
    observed_slot bigint NOT NULL CHECK (observed_slot >= 0),
    observed_hash varchar(64) NOT NULL CHECK (observed_hash ~ '^[0-9a-f]{64}$'),
    search_height bigint NOT NULL CHECK (search_height >= observed_height),
    search_slot bigint NOT NULL CHECK (search_slot >= observed_slot),
    search_hash varchar(64) NOT NULL CHECK (search_hash ~ '^[0-9a-f]{64}$'),
    fee_lovelace numeric(20,0) CHECK (fee_lovelace IS NULL OR fee_lovelace >= 0),
    native_expense_lovelace numeric(20,0) CHECK (native_expense_lovelace IS NULL OR native_expense_lovelace >= 0),
    block_height bigint CHECK (block_height IS NULL OR block_height >= observed_height),
    execution_receipt jsonb CHECK (execution_receipt IS NULL OR jsonb_typeof(execution_receipt) = 'object'),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id,request_id) REFERENCES public.wallet_mpc_sign_request(tenant_id,id),
    CHECK ((status = 'SIGNING') = (raw_payload IS NULL)),
    CHECK (status NOT IN ('CONFIRMING','CONFIRMED','FAILED') OR
        (execution_receipt IS NOT NULL AND fee_lovelace IS NOT NULL AND native_expense_lovelace IS NOT NULL AND block_height IS NOT NULL))
);
CREATE UNIQUE INDEX cardano_transaction_pending_sender_idx ON public.cardano_transaction(sender)
    WHERE status IN ('SIGNING','PREPARED','SENT','CONFIRMING');
COMMENT ON TABLE public.cardano_transaction IS 'Tenant-owned immutable Cardano MPC payment, original witness CBOR, bounded canonical search and proven native expense';

-- Only wallet-owned outputs, including spent history needed to identify internal inputs.
CREATE TABLE public.cardano_owned_output (
    tx_hash varchar(64) NOT NULL CHECK (tx_hash ~ '^[0-9a-f]{64}$'),
    output_index integer NOT NULL CHECK (output_index BETWEEN 0 AND 65535),
    tenant_id uuid NOT NULL,
    chain_address_id bigint NOT NULL,
    address varchar(160) NOT NULL,
    block_height bigint NOT NULL CHECK (block_height >= 0),
    block_hash varchar(64) NOT NULL CHECK (block_hash ~ '^[0-9a-f]{64}$'),
    output_cbor text NOT NULL CHECK (length(output_cbor) BETWEEN 2 AND 131072 AND output_cbor ~ '^([0-9a-f]{2})+$'),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tx_hash,output_index),
    FOREIGN KEY (tenant_id,chain_address_id) REFERENCES public.chain_address(tenant_id,id) ON DELETE RESTRICT
);
CREATE INDEX cardano_owned_output_tenant_address_idx ON public.cardano_owned_output(tenant_id,chain_address_id);
CREATE INDEX cardano_owned_output_height_idx ON public.cardano_owned_output(block_height);
COMMENT ON TABLE public.cardano_owned_output IS 'Tenant-owned wallet output references and original public output bytes; not a full-chain index';
COMMENT ON COLUMN public.chain_scan_height.genesis_hash IS 'Optional native scanner network identity; must match before advancing a slot/hash cursor';
COMMENT ON COLUMN public.chain_scan_block.block_slot IS 'Optional native slot required with the original hash to resume Cardano FollowTip';

CREATE TABLE public.hypercore_ledger_cursor (
    tenant_id uuid NOT NULL,
    network varchar(32) NOT NULL CHECK (network IN ('mainnet','testnet')),
    chain_address_id bigint NOT NULL,
    asset_symbol varchar(32) NOT NULL,
    address varchar(42) NOT NULL CHECK (address ~ '^0x[0-9a-f]{40}$'),
    last_time_ms bigint NOT NULL DEFAULT 0 CHECK (last_time_ms >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id,network,chain_address_id,asset_symbol),
    FOREIGN KEY (tenant_id,chain_address_id) REFERENCES public.chain_address(tenant_id,id) ON DELETE RESTRICT
);
COMMENT ON TABLE public.hypercore_ledger_cursor IS 'Per tenant/network/asset address inclusive ledger timestamp; real transfer hash deduplicates boundary replays; cursor and mandatory deposit projection commit together';


-- Monero native payment admission and immutable public selection. Native txsets remain encrypted in MPC results.
ALTER TABLE public.monero_transaction ADD CONSTRAINT monero_transaction_request_fk
    FOREIGN KEY (tenant_id,request_id) REFERENCES public.wallet_mpc_sign_request(tenant_id,id);
ALTER TABLE public.monero_transaction ADD CONSTRAINT monero_transaction_attempt_fk
    FOREIGN KEY (tenant_id,request_id,selected_attempt_id) REFERENCES public.wallet_mpc_sign_attempt(tenant_id,request_id,id);
CREATE UNIQUE INDEX monero_transaction_hash_idx ON public.monero_transaction(chain,tx_hash) WHERE tx_hash IS NOT NULL;
CREATE UNIQUE INDEX monero_transaction_pending_sender_idx ON public.monero_transaction(tenant_id,sender) WHERE status <> 'CONFIRMED';
CREATE OR REPLACE FUNCTION public.monero_transaction_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.id,NEW.chain,NEW.tenant_id,NEW.request_id,NEW.sender) IS DISTINCT FROM ROW(OLD.id,OLD.chain,OLD.tenant_id,OLD.request_id,OLD.sender)
       OR (OLD.tx_hash IS NOT NULL AND ROW(NEW.tx_hash,NEW.selected_attempt_id,NEW.artifact_hash,NEW.fee_atomic)
           IS DISTINCT FROM ROW(OLD.tx_hash,OLD.selected_attempt_id,OLD.artifact_hash,OLD.fee_atomic))
       OR (OLD.status = 'CONFIRMED' AND ROW(NEW.status,NEW.execution_receipt,NEW.block_height,NEW.block_hash,NEW.confirmations)
           IS DISTINCT FROM ROW(OLD.status,OLD.execution_receipt,OLD.block_height,OLD.block_hash,OLD.confirmations)) THEN
        RAISE EXCEPTION 'Monero original native payment selection or confirmation is immutable';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER monero_transaction_immutable BEFORE UPDATE ON public.monero_transaction
    FOR EACH ROW EXECUTE FUNCTION public.monero_transaction_immutable();
COMMENT ON TABLE public.monero_transaction IS 'Tenant native Monero request, original authenticated artifact digest/hash selection and canonical two-holder payment proof; no plaintext txset';

-- Original public EIP-7702 batch claim survives interruption before any signing RPC.
-- Identities, claimed principal and the snapshot cannot be rewritten into a different batch.
CREATE OR REPLACE FUNCTION public.wallet_enforce_evm_batch_claim() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.claim_payload IS DISTINCT FROM OLD.claim_payload
       OR ROW(NEW.id, NEW.tenant_id, NEW.chain, NEW.network, NEW.asset_symbol,
              NEW.token_contract, NEW.token_decimals, NEW.hot_wallet, NEW.relayer_address,
              NEW.delegate_version, NEW.batch_hash, NEW.item_count)
          IS DISTINCT FROM
          ROW(OLD.id, OLD.tenant_id, OLD.chain, OLD.network, OLD.asset_symbol,
              OLD.token_contract, OLD.token_decimals, OLD.hot_wallet, OLD.relayer_address,
              OLD.delegate_version, OLD.batch_hash, OLD.item_count) THEN
        RAISE EXCEPTION 'original EIP-7702 batch claim is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER evm_collection_batch_claim_immutable BEFORE UPDATE ON public.evm_collection_batch
    FOR EACH ROW EXECUTE FUNCTION public.wallet_enforce_evm_batch_claim();
CREATE TRIGGER evm_withdrawal_batch_claim_immutable BEFORE UPDATE ON public.evm_withdrawal_batch
    FOR EACH ROW EXECUTE FUNCTION public.wallet_enforce_evm_batch_claim();


-- Independent approval authority. Wallet application roles must have no privileges on this schema.
-- No foreign keys into wallet business tables: enrollment and authorization trust are independent.
DROP SCHEMA IF EXISTS approval CASCADE;
CREATE SCHEMA approval;
REVOKE ALL ON SCHEMA approval FROM PUBLIC;
CREATE TABLE approval.tenant (
    id uuid PRIMARY KEY,
    slug varchar(96) NOT NULL UNIQUE,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DISABLED')),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE approval.member (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES approval.tenant(id),
    email varchar(254) NOT NULL,
    display_name varchar(120) NOT NULL,
    password_hash text NOT NULL,
    role varchar(24) NOT NULL CHECK (role IN ('TENANT_ADMIN','APPROVER')),
    status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DISABLED','INVITED','RESET_REQUIRED')),
    failed_attempts integer NOT NULL DEFAULT 0 CHECK (failed_attempts >= 0),
    locked_until timestamptz,
    activation_token_hash varchar(64),
    activation_expires_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((activation_token_hash IS NULL AND activation_expires_at IS NULL AND status IN ('ACTIVE','DISABLED'))
        OR (activation_token_hash IS NOT NULL AND activation_token_hash ~ '^[0-9a-f]{64}$' AND activation_expires_at IS NOT NULL AND status IN ('INVITED','RESET_REQUIRED'))),
    UNIQUE (tenant_id,id)
);
CREATE UNIQUE INDEX approval_member_email_idx ON approval.member(lower(email));
CREATE UNIQUE INDEX approval_member_activation_idx ON approval.member(activation_token_hash) WHERE activation_token_hash IS NOT NULL;
CREATE INDEX approval_member_tenant_page_idx ON approval.member(tenant_id,created_at,id);
CREATE TABLE approval.member_revocation (
    tenant_id uuid NOT NULL REFERENCES approval.tenant(id),
    member_id uuid NOT NULL,
    sequence bigint NOT NULL CHECK (sequence BETWEEN 1 AND 1000000000000000),
    descriptor_hash varchar(64) NOT NULL CHECK (descriptor_hash ~ '^[0-9a-f]{64}$'),
    descriptor jsonb NOT NULL CHECK (jsonb_typeof(descriptor)='object'),
    PRIMARY KEY (tenant_id,member_id),
    CHECK (descriptor ?& ARRAY['tenantId','memberId','sequence','actorId','revokedAt']
        AND descriptor->>'tenantId'=tenant_id::text AND descriptor->>'memberId'=member_id::text
        AND jsonb_typeof(descriptor->'sequence')='number' AND (descriptor->>'sequence')::bigint=sequence
        AND jsonb_typeof(descriptor->'actorId')='string' AND jsonb_typeof(descriptor->'revokedAt')='string')
);
CREATE FUNCTION approval.enforce_member_revocation_fence() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'member revocation fences are permanent' USING ERRCODE='23514'; END IF;
    IF NEW.tenant_id<>OLD.tenant_id OR NEW.member_id<>OLD.member_id OR NEW.sequence<=OLD.sequence THEN
        RAISE EXCEPTION 'member revocation sequence must strictly advance' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER approval_member_revocation_fence BEFORE UPDATE OR DELETE ON approval.member_revocation
    FOR EACH ROW EXECUTE FUNCTION approval.enforce_member_revocation_fence();
CREATE TABLE approval.policy (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES approval.tenant(id),
    chain varchar(32) NOT NULL,
    network varchar(128) NOT NULL,
    asset_symbol varchar(32) NOT NULL,
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    single_amount numeric(84,24) CHECK (single_amount > 0),
    rolling_24h_amount numeric(84,24) CHECK (rolling_24h_amount > 0),
    all_withdrawals boolean NOT NULL DEFAULT true,
    new_recipient boolean NOT NULL DEFAULT true,
    console_withdrawal boolean NOT NULL DEFAULT true,
    required_reviewers integer NOT NULL DEFAULT 1 CHECK (required_reviewers BETWEEN 1 AND 2),
    expiry_seconds integer NOT NULL DEFAULT 3600 CHECK (expiry_seconds BETWEEN 900 AND 86400),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id,chain,network,asset_symbol), UNIQUE (tenant_id,id)
);
CREATE TABLE approval.request (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES approval.tenant(id),
    business_reference varchar(192) NOT NULL,
    intent_hash varchar(64) NOT NULL CHECK (intent_hash ~ '^[0-9a-f]{64}$'),
    intent jsonb NOT NULL,
    asset_snapshot jsonb NOT NULL CHECK (jsonb_typeof(asset_snapshot)='object'),
    chain varchar(32) NOT NULL,
    network varchar(128) NOT NULL,
    asset_symbol varchar(32) NOT NULL,
    recipient varchar(256) NOT NULL,
    amount numeric(84,24) NOT NULL CHECK (amount > 0),
    source varchar(16) NOT NULL CHECK (source IN ('API','CONSOLE')),
    initiator_id uuid NOT NULL,
    policy_id uuid NOT NULL,
    policy_version bigint NOT NULL CHECK (policy_version > 0),
    reasons jsonb NOT NULL CHECK (jsonb_typeof(reasons) = 'array'),
    required_reviewers integer NOT NULL CHECK (required_reviewers BETWEEN 0 AND 2),
    status varchar(16) NOT NULL CHECK (status IN ('PENDING','APPROVED','REJECTED','EXPIRED')),
    signing_transaction_id uuid,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    UNIQUE (tenant_id,id), UNIQUE (tenant_id,chain,network,business_reference),
    FOREIGN KEY (tenant_id,policy_id) REFERENCES approval.policy(tenant_id,id),
    CHECK (expires_at > created_at),
    CHECK (signing_transaction_id IS NULL OR status='APPROVED')
);
CREATE INDEX approval_request_pending_idx ON approval.request(tenant_id,status,created_at,id);
CREATE INDEX approval_request_rolling_idx ON approval.request(tenant_id,chain,network,asset_symbol,created_at);
CREATE TABLE approval.decision (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    request_id uuid NOT NULL,
    reviewer_id uuid NOT NULL,
    decision varchar(8) NOT NULL CHECK (decision IN ('APPROVE','REJECT')),
    reason varchar(1000) NOT NULL,
    created_at timestamptz NOT NULL,
    FOREIGN KEY (tenant_id,request_id) REFERENCES approval.request(tenant_id,id),
    FOREIGN KEY (tenant_id,reviewer_id) REFERENCES approval.member(tenant_id,id),
    UNIQUE (tenant_id,request_id,reviewer_id)
);
CREATE TABLE approval.audit (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES approval.tenant(id),
    actor_type varchar(24) NOT NULL CHECK (actor_type IN ('SERVICE','MEMBER','SYSTEM')),
    actor_id varchar(192) NOT NULL,
    action varchar(64) NOT NULL,
    entity_id varchar(192) NOT NULL,
    detail jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX approval_audit_tenant_page_idx ON approval.audit(tenant_id,created_at DESC,id DESC);
CREATE INDEX approval_audit_action_page_idx ON approval.audit(tenant_id,action,created_at DESC,id DESC);
REVOKE ALL ON ALL TABLES IN SCHEMA approval FROM PUBLIC;

CREATE TABLE approval.session (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    member_id uuid NOT NULL,
    token_hash varchar(64) NOT NULL UNIQUE CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    csrf_token varchar(64) NOT NULL,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id,member_id) REFERENCES approval.member(tenant_id,id)
);
CREATE INDEX approval_session_expiry_idx ON approval.session(expires_at);
REVOKE ALL ON approval.session FROM PUBLIC;
CREATE TABLE approval.key (
    key_id uuid NOT NULL,
    tenant_id uuid NOT NULL REFERENCES approval.tenant(id),
    key_group varchar(32) NOT NULL,
    algorithm varchar(32) NOT NULL CHECK (algorithm IN ('ECDSA_SECP256K1','ED25519','SCHNORR_BIP340','MONERO_NATIVE_2_OF_3')),
    epoch integer NOT NULL CHECK (epoch BETWEEN 1 AND 1000000),
    public_key_hex varchar(128) NOT NULL CHECK (public_key_hex ~ '^[0-9a-f]+$'),
    public_address varchar(256),
    state varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (state IN ('ACTIVE','DISABLED')),
    ceremony_request jsonb NOT NULL,
    observations jsonb NOT NULL CHECK (jsonb_typeof(observations)='array'),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id,key_id)
);
REVOKE ALL ON approval.key FROM PUBLIC;

-- Permanent authorization receipts. Never delete these to retry an unknown signature or payment.
CREATE TABLE approval.signing_transaction (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES approval.tenant(id),
    chain varchar(32) NOT NULL,
    network varchar(128) NOT NULL,
    business_type varchar(24) NOT NULL CHECK (business_type IN ('WITHDRAWAL','COLLECTION','RECOVERY','FUNDING','ADDRESS_PREPARATION','DEV_FAUCET')),
    business_reference varchar(192) NOT NULL,
    spend_domain varchar(512) NOT NULL,
    business_hash varchar(64) NOT NULL CHECK (business_hash ~ '^[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL,
    UNIQUE (tenant_id,id),
    UNIQUE (tenant_id,chain,network,business_type,business_reference),
    UNIQUE (tenant_id,chain,network,spend_domain)
);
ALTER TABLE approval.request ADD FOREIGN KEY (tenant_id,signing_transaction_id) REFERENCES approval.signing_transaction(tenant_id,id);
CREATE TABLE approval.signing_version (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    transaction_id uuid NOT NULL,
    version_hash varchar(64) NOT NULL CHECK (version_hash ~ '^[0-9a-f]{64}$'),
    payload_encoding varchar(48) NOT NULL,
    payload_hex text NOT NULL CHECK (payload_hex ~ '^([0-9a-f]{2})+$' AND length(payload_hex)<=2097152),
    chain_evidence jsonb CHECK (chain_evidence IS NULL OR jsonb_typeof(chain_evidence)='object'),
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id,transaction_id) REFERENCES approval.signing_transaction(tenant_id,id),
    UNIQUE (tenant_id,id), UNIQUE (tenant_id,transaction_id,version_hash),
    CHECK (payload_encoding NOT IN ('EVM_TX_JSON','UTXO_MPC_JSON_V1','SOLANA_TX_JSON','TON_V4R2_PAYMENT_JSON','TRON_TX_JSON','XRPL_TX_JSON',
        'NEAR_BORSH_PAYMENT_JSON','NEAR_STORAGE_REGISTRATION_JSON','HYPERCORE_JSON','SUI_PTB_PAYMENT_JSON','CARDANO_PAYMENT_JSON','POLKADOT_SCALE_PAYMENT_JSON','EIP7702_SIGNING_JSON') OR chain_evidence IS NOT NULL)
);
CREATE TABLE approval.spend_lock (
    tenant_id uuid NOT NULL,
    transaction_id uuid NOT NULL,
    chain varchar(32) NOT NULL,
    network varchar(128) NOT NULL,
    tx_id varchar(64) NOT NULL CHECK (tx_id ~ '^[0-9a-f]{64}$'),
    output_index integer NOT NULL CHECK (output_index>=0),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id,chain,network,tx_id,output_index),
    FOREIGN KEY (tenant_id,transaction_id) REFERENCES approval.signing_transaction(tenant_id,id)
);
CREATE INDEX approval_spend_lock_transaction_idx ON approval.spend_lock(tenant_id,transaction_id);
CREATE TABLE approval.signing_slot (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    version_id uuid NOT NULL,
    slot_name varchar(96) NOT NULL,
    candidate_hash varchar(64) NOT NULL CHECK (candidate_hash ~ '^[0-9a-f]{64}$'),
    candidate jsonb NOT NULL CHECK (jsonb_typeof(candidate)='object'),
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id,version_id) REFERENCES approval.signing_version(tenant_id,id),
    UNIQUE (tenant_id,id), UNIQUE (tenant_id,version_id,slot_name)
);
CREATE TABLE approval.signing_grant (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    slot_id uuid NOT NULL,
    session_id uuid NOT NULL,
    proof jsonb NOT NULL CHECK (jsonb_typeof(proof)='object'),
    execution jsonb NOT NULL CHECK (jsonb_typeof(execution)='object'),
    replay_actor varchar(192),
    replay_reason varchar(512),
    issued_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL CHECK (expires_at>issued_at AND expires_at<=issued_at+interval '5 minutes'),
    FOREIGN KEY (tenant_id,slot_id) REFERENCES approval.signing_slot(tenant_id,id),
    UNIQUE (tenant_id,slot_id,session_id), UNIQUE (tenant_id,session_id),
    CHECK ((execution->>'sessionId'=session_id::text AND execution->>'tenantId'=tenant_id::text) IS TRUE),
    CHECK ((replay_actor IS NULL AND replay_reason IS NULL) OR (replay_actor IS NOT NULL AND replay_reason IS NOT NULL
        AND length(btrim(replay_actor))>0 AND length(btrim(replay_reason))>=5))
);
CREATE TABLE approval.fee_reservation (
    tenant_id uuid NOT NULL,
    transaction_id uuid NOT NULL,
    chain varchar(32) NOT NULL,
    network varchar(128) NOT NULL,
    network_identity varchar(256) NOT NULL,
    native_symbol varchar(32) NOT NULL,
    native_decimals integer NOT NULL CHECK (native_decimals BETWEEN 0 AND 36),
    maximum_fee_atomic numeric(120,0) NOT NULL CHECK (maximum_fee_atomic>=0),
    created_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id,transaction_id),
    FOREIGN KEY (tenant_id,transaction_id) REFERENCES approval.signing_transaction(tenant_id,id)
);
CREATE INDEX approval_fee_reservation_rolling_idx ON approval.fee_reservation(tenant_id,chain,network,created_at);
-- Independent host-installed operating rules admit automatic payments separately from human decisions.
-- Lifetime expenditure never disappears when a rolling window advances or a rule is replaced.
CREATE TABLE approval.automatic_operation (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES approval.tenant(id),
    transaction_id uuid,
    rule_id uuid NOT NULL,
    rule_fingerprint varchar(64) NOT NULL CHECK (rule_fingerprint ~ '^[0-9a-f]{64}$'),
    chain varchar(32) NOT NULL,
    network varchar(128) NOT NULL,
    network_identity varchar(256) NOT NULL,
    asset_symbol varchar(32) NOT NULL,
    asset_identifier varchar(512),
    asset_decimals integer NOT NULL CHECK (asset_decimals BETWEEN 0 AND 36),
    native_symbol varchar(32) NOT NULL,
    native_decimals integer NOT NULL CHECK (native_decimals BETWEEN 0 AND 36),
    business_type varchar(24) NOT NULL CHECK (business_type IN ('COLLECTION','RECOVERY','FUNDING','ADDRESS_PREPARATION','DEV_FAUCET')),
    business_reference varchar(192) NOT NULL,
    source_key_ids jsonb NOT NULL CHECK (jsonb_typeof(source_key_ids)='array'),
    parent_operation_id uuid,
    parent_scope varchar(48),
    amount_atomic numeric(120,0) NOT NULL CHECK (amount_atomic>0 OR (amount_atomic=0 AND business_type='ADDRESS_PREPARATION' AND chain='XRP' AND asset_identifier IS NOT NULL)),
    maximum_fee_atomic numeric(120,0) NOT NULL CHECK (maximum_fee_atomic>=0),
    receipt jsonb NOT NULL CHECK (jsonb_typeof(receipt)='object'),
    preparation_plan jsonb CHECK (preparation_plan IS NULL OR jsonb_typeof(preparation_plan)='object'),
    preparation_evidence jsonb CHECK (preparation_evidence IS NULL OR jsonb_typeof(preparation_evidence)='object'),
    created_at timestamptz NOT NULL,
    UNIQUE (tenant_id,id),
    UNIQUE (tenant_id,transaction_id),
    UNIQUE (tenant_id,chain,network,business_type,business_reference),
    UNIQUE (tenant_id,parent_operation_id,parent_scope),
    FOREIGN KEY (tenant_id,transaction_id) REFERENCES approval.signing_transaction(tenant_id,id),
    CHECK ((parent_operation_id IS NULL)=(parent_scope IS NULL)),
    CHECK ((preparation_plan IS NULL)=(preparation_evidence IS NULL)),
    CHECK (transaction_id IS NOT NULL OR (business_type='COLLECTION' AND preparation_plan IS NOT NULL))
);
CREATE INDEX approval_automatic_operation_budget_idx ON approval.automatic_operation(tenant_id,chain,network,asset_symbol,created_at);
REVOKE ALL ON ALL TABLES IN SCHEMA approval FROM PUBLIC;

CREATE FUNCTION approval.enforce_permanent_signing_receipt() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'independent signing receipts are permanent and immutable' USING ERRCODE='23514';
END;
$$;
CREATE TRIGGER approval_signing_transaction_immutable BEFORE UPDATE OR DELETE ON approval.signing_transaction
    FOR EACH ROW EXECUTE FUNCTION approval.enforce_permanent_signing_receipt();
CREATE TRIGGER approval_signing_version_immutable BEFORE UPDATE OR DELETE ON approval.signing_version
    FOR EACH ROW EXECUTE FUNCTION approval.enforce_permanent_signing_receipt();
CREATE TRIGGER approval_signing_slot_immutable BEFORE UPDATE OR DELETE ON approval.signing_slot
    FOR EACH ROW EXECUTE FUNCTION approval.enforce_permanent_signing_receipt();
CREATE TRIGGER approval_signing_grant_immutable BEFORE UPDATE OR DELETE ON approval.signing_grant
    FOR EACH ROW EXECUTE FUNCTION approval.enforce_permanent_signing_receipt();
CREATE TRIGGER approval_fee_reservation_immutable BEFORE UPDATE OR DELETE ON approval.fee_reservation
    FOR EACH ROW EXECUTE FUNCTION approval.enforce_permanent_signing_receipt();
CREATE TRIGGER approval_spend_lock_immutable BEFORE UPDATE OR DELETE ON approval.spend_lock
    FOR EACH ROW EXECUTE FUNCTION approval.enforce_permanent_signing_receipt();
CREATE FUNCTION approval.enforce_automatic_operation_binding() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'independent operating receipts are permanent' USING ERRCODE='23514'; END IF;
    IF (to_jsonb(NEW)-'transaction_id') IS DISTINCT FROM (to_jsonb(OLD)-'transaction_id')
        OR OLD.transaction_id IS NOT NULL OR NEW.transaction_id IS NULL THEN
        RAISE EXCEPTION 'only the first original transaction may bind a prepared operating receipt' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER approval_automatic_operation_immutable BEFORE UPDATE OR DELETE ON approval.automatic_operation
    FOR EACH ROW EXECUTE FUNCTION approval.enforce_automatic_operation_binding();
CREATE FUNCTION approval.enforce_request_signing_binding() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.signing_transaction_id IS NOT NULL AND ROW(NEW.signing_transaction_id,NEW.status) IS DISTINCT FROM ROW(OLD.signing_transaction_id,OLD.status) THEN
        RAISE EXCEPTION 'original independent signing binding cannot be cleared, replaced or cancelled' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER approval_request_signing_binding_immutable BEFORE UPDATE ON approval.request
    FOR EACH ROW EXECUTE FUNCTION approval.enforce_request_signing_binding();
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA approval FROM PUBLIC;

-- Wallet's permanent admission receipt is committed with the original principal/Gas freeze.
CREATE TABLE public.wallet_withdrawal_approval (
    tenant_id uuid NOT NULL,
    withdrawal_id uuid NOT NULL,
    order_id bigint NOT NULL,
    approval_request_id uuid NOT NULL,
    chain varchar(32) NOT NULL,
    network varchar(128) NOT NULL,
    business_reference varchar(192) NOT NULL,
    intent_hash varchar(64) NOT NULL CHECK (intent_hash ~ '^[0-9a-f]{64}$'),
    intent jsonb NOT NULL CHECK (jsonb_typeof(intent)='object'),
    state varchar(24) NOT NULL DEFAULT 'QUEUED' CHECK (state IN ('QUEUED','PENDING','APPROVED','AUTHORIZED','REJECTED','EXPIRED')),
    receipt jsonb CHECK (receipt IS NULL OR jsonb_typeof(receipt)='object'),
    terminal_cleanup_complete boolean NOT NULL DEFAULT false,
    CHECK (not terminal_cleanup_complete OR (state IN ('REJECTED','EXPIRED') AND receipt->>'signingStarted'='false')),
    last_checked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id,withdrawal_id),
    UNIQUE (tenant_id,approval_request_id), UNIQUE (tenant_id,chain,business_reference),
    FOREIGN KEY (tenant_id,withdrawal_id) REFERENCES public.custody_withdrawal(tenant_id,id),
    FOREIGN KEY (tenant_id,order_id) REFERENCES public.withdrawal_order(tenant_id,id),
    CHECK ((state='QUEUED')=(receipt IS NULL)),
    CHECK ((intent->>'id'=approval_request_id::text AND intent->>'tenantId'=tenant_id::text
        AND intent->>'chain'=chain AND intent->>'network'=network AND intent->>'businessReference'=business_reference) IS TRUE)
);
CREATE INDEX wallet_withdrawal_approval_poll_idx ON public.wallet_withdrawal_approval(last_checked_at,created_at)
    WHERE state IN ('QUEUED','PENDING','APPROVED') OR (state IN ('REJECTED','EXPIRED') AND not terminal_cleanup_complete);
CREATE OR REPLACE FUNCTION public.wallet_enforce_withdrawal_approval_intent() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' OR ROW(NEW.tenant_id,NEW.withdrawal_id,NEW.order_id,NEW.approval_request_id,NEW.chain,NEW.network,NEW.business_reference,NEW.intent_hash,NEW.intent)
        IS DISTINCT FROM ROW(OLD.tenant_id,OLD.withdrawal_id,OLD.order_id,OLD.approval_request_id,OLD.chain,OLD.network,OLD.business_reference,OLD.intent_hash,OLD.intent) THEN
        RAISE EXCEPTION 'original withdrawal approval intent is permanent and immutable' USING ERRCODE='23514';
    END IF;
    IF (OLD.terminal_cleanup_complete AND NOT NEW.terminal_cleanup_complete)
        OR (OLD.state IN ('AUTHORIZED','REJECTED','EXPIRED') AND NEW.state<>OLD.state) THEN
        RAISE EXCEPTION 'terminal approval state and completed cleanup cannot be reset' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER wallet_withdrawal_approval_intent_immutable BEFORE UPDATE OR DELETE ON public.wallet_withdrawal_approval
    FOR EACH ROW EXECUTE FUNCTION public.wallet_enforce_withdrawal_approval_intent();
