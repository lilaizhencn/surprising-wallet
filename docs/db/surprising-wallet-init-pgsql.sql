--
-- PostgreSQL database dump
--


-- Dumped from database version 18.4 (Homebrew)
-- Dumped by pg_dump version 18.4 (Homebrew)

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

DROP TABLE IF EXISTS public.evm_collection_batch_attempt;
DROP TABLE IF EXISTS public.evm_collection_batch_item;
DROP TABLE IF EXISTS public.evm_collection_batch;
DROP TABLE IF EXISTS public.evm_7702_account;
DROP TABLE IF EXISTS public.evm_7702_config;
ALTER TABLE IF EXISTS ONLY public.collection_record DROP CONSTRAINT IF EXISTS collection_record_custody_fk;
ALTER TABLE IF EXISTS ONLY public.collection_record DROP CONSTRAINT IF EXISTS collection_record_tenant_fk;
ALTER TABLE IF EXISTS ONLY public.collection_record DROP CONSTRAINT IF EXISTS collection_record_tenant_id_key;

ALTER TABLE IF EXISTS ONLY public.withdrawal_order DROP CONSTRAINT IF EXISTS withdrawal_order_tenant_id_fkey;
ALTER TABLE IF EXISTS ONLY public.ledger_balance DROP CONSTRAINT IF EXISTS ledger_balance_tenant_id_fkey;
ALTER TABLE IF EXISTS ONLY public.deposit_record DROP CONSTRAINT IF EXISTS deposit_record_tenant_id_fkey;
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
DROP INDEX IF EXISTS public.custody_withdrawal_tenant_time_idx;
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
ALTER TABLE IF EXISTS ONLY public.wallet_key_config DROP CONSTRAINT IF EXISTS wallet_key_config_pkey;
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
ALTER TABLE IF EXISTS ONLY public.monero_transaction DROP CONSTRAINT IF EXISTS monero_transaction_chain_tx_hash_direction_subaddress_key;
ALTER TABLE IF EXISTS ONLY public.ledger_balance DROP CONSTRAINT IF EXISTS ledger_balance_tenant_id_key;
ALTER TABLE IF EXISTS ONLY public.ledger_balance DROP CONSTRAINT IF EXISTS ledger_balance_pkey;
ALTER TABLE IF EXISTS ONLY public.ledger_balance DROP CONSTRAINT IF EXISTS ledger_balance_chain_asset_symbol_account_id_key;
ALTER TABLE IF EXISTS ONLY public.hypercore_token_metadata DROP CONSTRAINT IF EXISTS hypercore_token_metadata_pkey;
ALTER TABLE IF EXISTS ONLY public.hypercore_spot_asset DROP CONSTRAINT IF EXISTS hypercore_spot_asset_pkey;
ALTER TABLE IF EXISTS ONLY public.hypercore_balance_snapshot DROP CONSTRAINT IF EXISTS hypercore_balance_snapshot_pkey;
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
DROP TABLE IF EXISTS public.wallet_key_config;
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
DROP TABLE IF EXISTS public.hypercore_balance_snapshot;
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
DROP TABLE IF EXISTS public.deposit_record;
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
    status character varying(32) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


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
    amount numeric(78,18) DEFAULT 0 NOT NULL,
    gas_used bigint DEFAULT 0 NOT NULL,
    gas_unit_price bigint DEFAULT 0 NOT NULL,
    version bigint,
    sequence_number bigint,
    confirmations integer DEFAULT 0 NOT NULL,
    status character varying(32) DEFAULT 'CREATED'::character varying NOT NULL,
    raw_payload text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: aptos_transaction_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.aptos_transaction_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
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
    min_transfer numeric(78,0),
    min_withdraw numeric(78,0),
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
    scan_batch_size integer DEFAULT 100,
    scan_enabled boolean DEFAULT false NOT NULL,
    withdraw_enabled boolean DEFAULT false NOT NULL,
    collection_enabled boolean DEFAULT false NOT NULL,
    transfer_enabled boolean DEFAULT false NOT NULL,
    scan_start_height bigint DEFAULT 0 NOT NULL,
    scan_max_blocks_per_run bigint DEFAULT 0 NOT NULL
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
    best_height bigint DEFAULT 0 NOT NULL,
    safe_height bigint DEFAULT 0 NOT NULL,
    status character varying(32) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
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
    chain character varying(32) NOT NULL,
    asset_symbol character varying(32) NOT NULL,
    business_type character varying(32) NOT NULL,
    business_no character varying(512) NOT NULL,
    tx_id character varying(128) DEFAULT ''::character varying NOT NULL,
    balance numeric(78,18) DEFAULT 0 NOT NULL,
    signature text,
    currency integer NOT NULL,
    status smallint NOT NULL,
    error_message text,
    create_date timestamp with time zone DEFAULT now() NOT NULL,
    update_date timestamp with time zone DEFAULT now() NOT NULL
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
    id bigint NOT NULL,
    collection_no character varying(96) NOT NULL,
    chain character varying(32) NOT NULL,
    asset_symbol character varying(32) NOT NULL,
    from_address character varying(160) NOT NULL,
    to_address character varying(160) NOT NULL,
    amount numeric(78,18) DEFAULT 0 NOT NULL,
    fee numeric(78,18) DEFAULT 0 NOT NULL,
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
    secret_version integer DEFAULT 1 NOT NULL,
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
    CONSTRAINT custody_gas_usage_actual_check CHECK (((actual_amount IS NULL) OR (actual_amount >= (0)::numeric))),
    CONSTRAINT custody_gas_usage_reserved_check CHECK ((reserved_amount > (0)::numeric)),
    CONSTRAINT custody_gas_usage_operation_type_check CHECK (((operation_type)::text = ANY ((ARRAY['WITHDRAWAL'::character varying, 'COLLECTION_BATCH'::character varying])::text[]))),
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
    created_at timestamp with time zone DEFAULT now() NOT NULL
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
    created_at timestamp with time zone DEFAULT now() NOT NULL
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
    CONSTRAINT custody_tenant_user_role_check CHECK (((role)::text = ANY ((ARRAY['PLATFORM_ADMIN'::character varying, 'TENANT_ADMIN'::character varying, 'OPERATOR'::character varying, 'VIEWER'::character varying])::text[]))),
    CONSTRAINT custody_tenant_user_scope_check CHECK (((((role)::text = 'PLATFORM_ADMIN'::text) AND (tenant_id IS NULL)) OR (((role)::text <> 'PLATFORM_ADMIN'::text) AND (tenant_id IS NOT NULL)))),
    CONSTRAINT custody_tenant_user_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'DISABLED'::character varying])::text[])))
);


--
-- Name: custody_webhook_delivery; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.custody_webhook_delivery (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    endpoint_id uuid NOT NULL,
    event_id uuid NOT NULL,
    status character varying(24) DEFAULT 'PENDING'::character varying NOT NULL,
    attempt_count integer DEFAULT 0 NOT NULL,
    next_attempt_at timestamp with time zone DEFAULT now() NOT NULL,
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
    secret_version integer DEFAULT 1 NOT NULL,
    status character varying(24) DEFAULT 'PENDING_VERIFICATION'::character varying NOT NULL,
    verification_token_hash character varying(128),
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
    amount numeric(78,18) NOT NULL,
    block_height bigint NOT NULL,
    confirmations integer DEFAULT 0 NOT NULL,
    status character varying(32) DEFAULT 'DETECTED'::character varying NOT NULL,
    credited boolean DEFAULT false NOT NULL,
    credited_at timestamp with time zone,
    raw_payload text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    tenant_id uuid
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
    status character varying(32) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


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
    amount numeric(78,18) DEFAULT 0 NOT NULL,
    block_height bigint NOT NULL,
    confirmations integer DEFAULT 0 NOT NULL,
    status character varying(32) DEFAULT 'DETECTED'::character varying NOT NULL,
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
    amount numeric(78,18) DEFAULT 0 NOT NULL,
    fee numeric(78,18) DEFAULT 0 NOT NULL,
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
    amount numeric(78,18) DEFAULT 0 NOT NULL,
    fee numeric(78,18) DEFAULT 0 NOT NULL,
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
    amount numeric(78,18) DEFAULT 0 NOT NULL,
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
    status character varying(32) DEFAULT 'CREATED'::character varying NOT NULL,
    error_message text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: hypercore_balance_snapshot; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.hypercore_balance_snapshot (
    chain character varying(32) DEFAULT 'HYPERCORE'::character varying NOT NULL,
    asset_symbol character varying(32) NOT NULL,
    account_id character varying(160) NOT NULL,
    address character varying(160) NOT NULL,
    observed_balance numeric(78,24) DEFAULT 0 NOT NULL,
    raw_payload text,
    observed_at timestamp with time zone DEFAULT now() NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


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
    available_balance numeric(78,18) DEFAULT 0 NOT NULL,
    locked_balance numeric(78,18) DEFAULT 0 NOT NULL,
    total_balance numeric(78,18) DEFAULT 0 NOT NULL,
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
    chain character varying(32) DEFAULT 'XMR'::character varying NOT NULL,
    tx_hash character varying(128) NOT NULL,
    direction character varying(16) NOT NULL,
    account_index integer DEFAULT 0 NOT NULL,
    subaddress_index integer DEFAULT 0 NOT NULL,
    address character varying(128) NOT NULL,
    asset_symbol character varying(32) DEFAULT 'XMR'::character varying NOT NULL,
    amount numeric(78,24) DEFAULT 0 NOT NULL,
    fee_atomic bigint DEFAULT 0 NOT NULL,
    block_height bigint,
    confirmations integer DEFAULT 0 NOT NULL,
    status character varying(32) DEFAULT 'CREATED'::character varying NOT NULL,
    raw_payload text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
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
    block_height bigint,
    status character varying(32) DEFAULT 'CREATED'::character varying NOT NULL,
    raw_payload text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


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
    amount numeric(78,18) DEFAULT 0 NOT NULL,
    fee_lamports bigint DEFAULT 0 NOT NULL,
    slot bigint,
    confirmations integer DEFAULT 0 NOT NULL,
    status character varying(32) DEFAULT 'CREATED'::character varying NOT NULL,
    raw_payload text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
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
    amount numeric(78,18) DEFAULT 0 NOT NULL,
    gas_used bigint DEFAULT 0 NOT NULL,
    checkpoint bigint,
    status character varying(32) DEFAULT 'CREATED'::character varying NOT NULL,
    raw_payload text,
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
    min_deposit numeric(78,18),
    min_withdraw numeric(78,18),
    collect_enabled boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    network character varying(32),
    token_standard character varying(32),
    contract_address_base58 character varying(128),
    contract_address_hex character varying(128),
    min_deposit_amount numeric(78,18),
    min_withdraw_amount numeric(78,18),
    collect_threshold numeric(78,18),
    gas_strategy character varying(64),
    confirmation_required integer
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
    amount numeric(78,18) DEFAULT 0 NOT NULL,
    fee_nano bigint DEFAULT 0 NOT NULL,
    logical_time numeric(78,0),
    confirmations integer DEFAULT 0 NOT NULL,
    status character varying(32) DEFAULT 'CREATED'::character varying NOT NULL,
    raw_payload text,
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
    amount numeric(78,18) DEFAULT 0 NOT NULL,
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
    amount numeric(78,18) DEFAULT 0 NOT NULL,
    fee numeric(78,18) DEFAULT 0 NOT NULL,
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
    amount numeric(78,18) DEFAULT 0 NOT NULL,
    fee numeric(78,18) DEFAULT 0 NOT NULL,
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
    amount numeric(78,18) NOT NULL,
    block_height bigint NOT NULL,
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
-- Name: wallet_key_config; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.wallet_key_config (
    id smallint NOT NULL,
    sig1_seed text NOT NULL,
    sig2_seed text NOT NULL,
    recovery_seed text NOT NULL,
    ed25519_seed text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_by character varying(128) NOT NULL,
    CONSTRAINT wallet_key_config_singleton_check CHECK ((id = 1))
);


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
    id bigint NOT NULL,
    order_no character varying(96) NOT NULL,
    user_id bigint NOT NULL,
    chain character varying(32) NOT NULL,
    asset_symbol character varying(32) NOT NULL,
    from_address character varying(160),
    to_address character varying(160) NOT NULL,
    amount numeric(78,18) NOT NULL,
    fee numeric(78,18) DEFAULT 0 NOT NULL,
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
-- Name: hypercore_balance_snapshot hypercore_balance_snapshot_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.hypercore_balance_snapshot
    ADD CONSTRAINT hypercore_balance_snapshot_pkey PRIMARY KEY (chain, asset_symbol, account_id);


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
-- Name: monero_transaction monero_transaction_chain_tx_hash_direction_subaddress_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.monero_transaction
    ADD CONSTRAINT monero_transaction_chain_tx_hash_direction_subaddress_key UNIQUE (chain, tx_hash, direction, subaddress_index);


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
-- Name: wallet_key_config wallet_key_config_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.wallet_key_config
    ADD CONSTRAINT wallet_key_config_pkey PRIMARY KEY (id);


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
    relayer_chain_address_id bigint NOT NULL,
    relayer_address character varying(42) NOT NULL,
    status character varying(24) DEFAULT 'DISABLED'::character varying NOT NULL,
    max_batch_items integer DEFAULT 20 NOT NULL,
    max_batch_gas bigint DEFAULT 5000000 NOT NULL,
    block_gas_ratio numeric(5,4) DEFAULT 0.3000 NOT NULL,
    gas_limit_multiplier numeric(5,4) DEFAULT 1.2000 NOT NULL,
    signature_ttl_seconds integer DEFAULT 900 NOT NULL,
    required_confirmations integer DEFAULT 2 NOT NULL,
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
    status character varying(32) NOT NULL,
    item_count integer NOT NULL,
    estimated_gas bigint,
    gas_limit bigint,
    max_fee_per_gas numeric(78,0),
    max_priority_fee_per_gas numeric(78,0),
    canonical_tx_hash character varying(128),
    actual_gas_used bigint,
    effective_gas_price numeric(78,0),
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
        REFERENCES public.custody_tenant(id) ON DELETE RESTRICT
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

ALTER TABLE ONLY public.collection_record
    ADD CONSTRAINT collection_record_tenant_fk FOREIGN KEY (tenant_id)
        REFERENCES public.custody_tenant(id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.collection_record
    ADD CONSTRAINT collection_record_custody_fk FOREIGN KEY (tenant_id, custody_address_id)
        REFERENCES public.custody_address(tenant_id, id) ON DELETE RESTRICT;

-- PostgreSQL database dump complete
--




-- Data for Name: chain_asset; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO "public"."chain_asset" ("id", "chain", "symbol", "asset_kind", "contract_address", "decimals", "native_asset", "active", "min_transfer", "min_withdraw", "created_at", "updated_at") VALUES (2, 'ETH', 'ETH', 'NATIVE', NULL, 18, true, true, 0, 0, '2026-06-20 15:10:22.823199+08', '2026-06-20 15:10:22.823199+08');
INSERT INTO "public"."chain_asset" ("id", "chain", "symbol", "asset_kind", "contract_address", "decimals", "native_asset", "active", "min_transfer", "min_withdraw", "created_at", "updated_at") VALUES (3, 'BNB', 'BNB', 'NATIVE', NULL, 18, true, true, 0, 0, '2026-06-20 15:10:22.823199+08', '2026-06-20 15:10:22.823199+08');
INSERT INTO "public"."chain_asset" ("id", "chain", "symbol", "asset_kind", "contract_address", "decimals", "native_asset", "active", "min_transfer", "min_withdraw", "created_at", "updated_at") VALUES (4, 'POLYGON', 'MATIC', 'NATIVE', NULL, 18, true, true, 0, 0, '2026-06-20 15:10:22.823199+08', '2026-06-20 15:10:22.823199+08');
INSERT INTO "public"."chain_asset" ("id", "chain", "symbol", "asset_kind", "contract_address", "decimals", "native_asset", "active", "min_transfer", "min_withdraw", "created_at", "updated_at") VALUES (5, 'ARBITRUM', 'ETH_ARB', 'NATIVE', NULL, 18, true, true, 0, 0, '2026-06-20 15:10:22.823199+08', '2026-06-20 15:10:22.823199+08');
INSERT INTO "public"."chain_asset" ("id", "chain", "symbol", "asset_kind", "contract_address", "decimals", "native_asset", "active", "min_transfer", "min_withdraw", "created_at", "updated_at") VALUES (6, 'OPTIMISM', 'ETH_OP', 'NATIVE', NULL, 18, true, true, 0, 0, '2026-06-20 15:10:22.823199+08', '2026-06-20 15:10:22.823199+08');
INSERT INTO "public"."chain_asset" ("id", "chain", "symbol", "asset_kind", "contract_address", "decimals", "native_asset", "active", "min_transfer", "min_withdraw", "created_at", "updated_at") VALUES (7, 'BASE', 'ETH_BASE', 'NATIVE', NULL, 18, true, true, 0, 0, '2026-06-20 15:10:22.823199+08', '2026-06-20 15:10:22.823199+08');
INSERT INTO "public"."chain_asset" ("id", "chain", "symbol", "asset_kind", "contract_address", "decimals", "native_asset", "active", "min_transfer", "min_withdraw", "created_at", "updated_at") VALUES (8, 'AVAX_C', 'AVAX_C', 'NATIVE', NULL, 18, true, true, 0, 0, '2026-06-20 15:10:22.823199+08', '2026-06-20 15:10:22.823199+08');
INSERT INTO "public"."chain_asset" ("id", "chain", "symbol", "asset_kind", "contract_address", "decimals", "native_asset", "active", "min_transfer", "min_withdraw", "created_at", "updated_at") VALUES (9, 'TRON', 'TRX', 'NATIVE', NULL, 6, true, true, 0, 0, '2026-06-20 15:10:22.823199+08', '2026-06-20 15:10:22.823199+08');
INSERT INTO "public"."chain_asset" ("id", "chain", "symbol", "asset_kind", "contract_address", "decimals", "native_asset", "active", "min_transfer", "min_withdraw", "created_at", "updated_at") VALUES (1, 'BTC', 'BTC', 'NATIVE', NULL, 8, true, true, 546, 546, '2026-06-20 15:10:22.823199+08', '2026-06-24 18:07:33.46956+08');
INSERT INTO "public"."chain_asset" ("id", "chain", "symbol", "asset_kind", "contract_address", "decimals", "native_asset", "active", "min_transfer", "min_withdraw", "created_at", "updated_at") VALUES (12, 'LTC', 'LTC', 'NATIVE', NULL, 8, true, true, 100000, 100000, '2026-06-21 07:45:07.734233+08', '2026-06-24 18:07:33.46956+08');
INSERT INTO "public"."chain_asset" ("id", "chain", "symbol", "asset_kind", "contract_address", "decimals", "native_asset", "active", "min_transfer", "min_withdraw", "created_at", "updated_at") VALUES (17, 'DOGE', 'DOGE', 'NATIVE', NULL, 8, true, true, 1000000, 1000000, '2026-06-21 19:13:13.386542+08', '2026-06-24 18:07:33.46956+08');
INSERT INTO "public"."chain_asset" ("id", "chain", "symbol", "asset_kind", "contract_address", "decimals", "native_asset", "active", "min_transfer", "min_withdraw", "created_at", "updated_at") VALUES (20, 'BCH', 'BCH', 'NATIVE', NULL, 8, true, true, 546, 546, '2026-06-21 23:17:57.796638+08', '2026-06-24 18:07:33.46956+08');
INSERT INTO "public"."chain_asset" ("id", "chain", "symbol", "asset_kind", "contract_address", "decimals", "native_asset", "active", "min_transfer", "min_withdraw", "created_at", "updated_at") VALUES (25, 'SOLANA', 'USDT', 'TOKEN', '2VHdVaW1jBGmvU1PdQpvmPnWV5AiAxrmxmUTLvf8951y', 6, false, true, 1, 1, '2026-06-23 15:01:18.906314+08', '2026-06-23 15:02:47.92829+08');
INSERT INTO "public"."chain_asset" ("id", "chain", "symbol", "asset_kind", "contract_address", "decimals", "native_asset", "active", "min_transfer", "min_withdraw", "created_at", "updated_at") VALUES (26, 'SOLANA', 'USDC', 'TOKEN', '4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU', 6, false, true, 1, 1, '2026-06-23 15:01:43.41494+08', '2026-06-23 15:03:11.79609+08');
INSERT INTO "public"."chain_asset" ("id", "chain", "symbol", "asset_kind", "contract_address", "decimals", "native_asset", "active", "min_transfer", "min_withdraw", "created_at", "updated_at") VALUES (10, 'SOLANA', 'SOL', 'NATIVE', NULL, 9, true, true, 1, 1, '2026-06-20 15:10:22.823199+08', '2026-06-23 21:01:38.531755+08');
INSERT INTO "public"."chain_asset" ("id", "chain", "symbol", "asset_kind", "contract_address", "decimals", "native_asset", "active", "min_transfer", "min_withdraw", "created_at", "updated_at") VALUES (11, 'TON', 'TON', 'NATIVE', NULL, 9, true, true, 1000000, 1000000, '2026-06-20 15:10:22.823199+08', '2026-06-23 21:01:38.590086+08');
INSERT INTO "public"."chain_asset" ("id", "chain", "symbol", "asset_kind", "contract_address", "decimals", "native_asset", "active", "min_transfer", "min_withdraw", "created_at", "updated_at") VALUES (34, 'TON', 'USDT', 'JETTON', 'kQCZ5SAA78W_0vA5eSoU23YomxnUwah3KYagqeesNQI5jOXT', 6, false, true, 1, 1, '2026-06-23 21:37:58.688905+08', '2026-06-23 21:59:30.209563+08');
INSERT INTO "public"."chain_asset" ("id", "chain", "symbol", "asset_kind", "contract_address", "decimals", "native_asset", "active", "min_transfer", "min_withdraw", "created_at", "updated_at") VALUES (35, 'TON', 'USDC', 'JETTON', 'kQCzPT6908-8TR862TQo1S43-2kEme8UKRCRSWkaxNLD7H_2', 6, false, true, 1, 1, '2026-06-23 21:38:19.051838+08', '2026-06-23 21:59:40.049688+08');
INSERT INTO "public"."chain_asset" ("id", "chain", "symbol", "asset_kind", "contract_address", "decimals", "native_asset", "active", "min_transfer", "min_withdraw", "created_at", "updated_at") VALUES (44, 'APTOS', 'APT', 'NATIVE', NULL, 8, true, true, 1, 1, '2026-06-23 22:24:58.098309+08', '2026-06-23 22:24:58.098309+08');
INSERT INTO "public"."chain_asset" ("id", "chain", "symbol", "asset_kind", "contract_address", "decimals", "native_asset", "active", "min_transfer", "min_withdraw", "created_at", "updated_at") VALUES (45, 'SUI', 'SUI', 'NATIVE', NULL, 9, true, true, 1, 1, '2026-06-23 23:21:21.429955+08', '2026-06-23 23:21:21.429955+08');
INSERT INTO "public"."chain_asset" ("id", "chain", "symbol", "asset_kind", "contract_address", "decimals", "native_asset", "active", "min_transfer", "min_withdraw", "created_at", "updated_at") VALUES (136, 'XRP', 'XRP', 'NATIVE', NULL, 6, true, true, 0.000001, 0.000001, '2026-06-27 12:10:00+08', '2026-06-27 12:10:00+08');


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
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at", "chain_id", "gas_policy", "scan_batch_size", "scan_enabled", "withdraw_enabled", "collection_enabled", "transfer_enabled", "scan_start_height", "scan_max_blocks_per_run") VALUES (141, 'POLYGON', 'amoy', 'evm', 966, 60, 'MATIC', 'https://polygon-amoy-bor-rpc.publicnode.com', 'https://amoy.polygonscan.com/tx/', 64, 64, 1, 0, true, '2026-06-25 00:11:35.419133+08', '2026-06-25 00:11:35.419133+08', 80002, 'eip1559', 300, true, true, true, true, 0, 0);
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at", "chain_id", "gas_policy", "scan_batch_size", "scan_enabled", "withdraw_enabled", "collection_enabled", "transfer_enabled", "scan_start_height", "scan_max_blocks_per_run") VALUES (142, 'POLYGON', 'mainnet', 'evm', 966, 60, 'MATIC', 'https://polygon-bor-rpc.publicnode.com', 'https://polygonscan.com/tx/', 128, 128, 1, 0, false, '2026-06-25 00:11:35.419133+08', '2026-06-25 00:11:35.419133+08', 137, 'eip1559', 300, false, false, false, false, 0, 0);
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at", "chain_id", "gas_policy", "scan_batch_size", "scan_enabled", "withdraw_enabled", "collection_enabled", "transfer_enabled", "scan_start_height", "scan_max_blocks_per_run") VALUES (143, 'ARBITRUM', 'sepolia', 'evm', 9001, 60, 'ETH_ARB', 'https://arbitrum-sepolia-rpc.publicnode.com', 'https://sepolia.arbiscan.io/tx/', 40, 40, 1, 0, true, '2026-06-25 00:11:35.419133+08', '2026-06-25 00:11:35.419133+08', 421614, 'eip1559-l2', 500, true, true, true, true, 0, 0);
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at", "chain_id", "gas_policy", "scan_batch_size", "scan_enabled", "withdraw_enabled", "collection_enabled", "transfer_enabled", "scan_start_height", "scan_max_blocks_per_run") VALUES (144, 'ARBITRUM', 'mainnet', 'evm', 9001, 60, 'ETH_ARB', 'https://arbitrum-one-rpc.publicnode.com', 'https://arbiscan.io/tx/', 40, 40, 1, 0, false, '2026-06-25 00:11:35.419133+08', '2026-06-25 00:11:35.419133+08', 42161, 'eip1559-l2', 500, false, false, false, false, 0, 0);
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at", "chain_id", "gas_policy", "scan_batch_size", "scan_enabled", "withdraw_enabled", "collection_enabled", "transfer_enabled", "scan_start_height", "scan_max_blocks_per_run") VALUES (145, 'OPTIMISM', 'sepolia', 'evm', 9002, 60, 'ETH_OP', 'https://optimism-sepolia-rpc.publicnode.com', 'https://sepolia-optimism.etherscan.io/tx/', 40, 40, 1, 0, true, '2026-06-25 00:11:35.419133+08', '2026-06-25 00:11:35.419133+08', 11155420, 'eip1559-l2', 500, true, true, true, true, 0, 0);
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at", "chain_id", "gas_policy", "scan_batch_size", "scan_enabled", "withdraw_enabled", "collection_enabled", "transfer_enabled", "scan_start_height", "scan_max_blocks_per_run") VALUES (146, 'OPTIMISM', 'mainnet', 'evm', 9002, 60, 'ETH_OP', 'https://optimism-rpc.publicnode.com', 'https://optimistic.etherscan.io/tx/', 40, 40, 1, 0, false, '2026-06-25 00:11:35.419133+08', '2026-06-25 00:11:35.419133+08', 10, 'eip1559-l2', 500, false, false, false, false, 0, 0);
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at", "chain_id", "gas_policy", "scan_batch_size", "scan_enabled", "withdraw_enabled", "collection_enabled", "transfer_enabled", "scan_start_height", "scan_max_blocks_per_run") VALUES (147, 'BASE', 'sepolia', 'evm', 9003, 60, 'ETH_BASE', 'https://base-sepolia-rpc.publicnode.com', 'https://sepolia.basescan.org/tx/', 40, 40, 1, 0, true, '2026-06-25 00:11:35.419133+08', '2026-06-25 00:11:35.419133+08', 84532, 'eip1559-l2', 500, true, true, true, true, 0, 0);
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at", "chain_id", "gas_policy", "scan_batch_size", "scan_enabled", "withdraw_enabled", "collection_enabled", "transfer_enabled", "scan_start_height", "scan_max_blocks_per_run") VALUES (148, 'BASE', 'mainnet', 'evm', 9003, 60, 'ETH_BASE', 'https://base-rpc.publicnode.com', 'https://basescan.org/tx/', 40, 40, 1, 0, false, '2026-06-25 00:11:35.419133+08', '2026-06-25 00:11:35.419133+08', 8453, 'eip1559-l2', 500, false, false, false, false, 0, 0);
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
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (6, 'TON', 'testnet', 'dev', 'toncenter-testnet', 'rpc', 'HTTP_JSON', 'https://testnet.toncenter.com/api/v2', 'API_KEY_OPTIONAL', NULL, NULL, NULL, NULL, 10, true, NULL, 'TON Center testnet API, optional API key', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', NULL, NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (7, 'TON', 'testnet', 'dev', 'tonapi-testnet', 'indexer', 'HTTP_JSON', 'https://testnet.tonapi.io', 'API_KEY_OPTIONAL', NULL, NULL, NULL, NULL, 20, true, NULL, 'TON API testnet indexer, optional API key', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', NULL, NULL, NULL);
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


--
-- Data for Name: token_config; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO "public"."token_config" ("id", "chain", "symbol", "standard", "contract_address", "decimals", "enabled", "min_deposit", "min_withdraw", "collect_enabled", "created_at", "updated_at", "network", "token_standard", "contract_address_base58", "contract_address_hex", "min_deposit_amount", "min_withdraw_amount", "collect_threshold", "gas_strategy", "confirmation_required") VALUES (82, 'TON', 'USDT', 'JETTON', 'kQCZ5SAA78W_0vA5eSoU23YomxnUwah3KYagqeesNQI5jOXT', 6, true, 1.000000000000000000, 1.000000000000000000, true, '2026-06-23 21:01:38.590818+08', '2026-06-23 21:59:30.200728+08', 'testnet', 'JETTON', NULL, NULL, 1.000000000000000000, 1.000000000000000000, 1.000000000000000000, 'TON_FORWARD_FEE', 1);
INSERT INTO "public"."token_config" ("id", "chain", "symbol", "standard", "contract_address", "decimals", "enabled", "min_deposit", "min_withdraw", "collect_enabled", "created_at", "updated_at", "network", "token_standard", "contract_address_base58", "contract_address_hex", "min_deposit_amount", "min_withdraw_amount", "collect_threshold", "gas_strategy", "confirmation_required") VALUES (83, 'TON', 'USDC', 'JETTON', 'kQCzPT6908-8TR862TQo1S43-2kEme8UKRCRSWkaxNLD7H_2', 6, true, 1.000000000000000000, 1.000000000000000000, true, '2026-06-23 21:01:38.590818+08', '2026-06-23 21:59:40.024105+08', 'testnet', 'JETTON', NULL, NULL, 1.000000000000000000, 1.000000000000000000, 1.000000000000000000, 'TON_FORWARD_FEE', 1);
INSERT INTO "public"."token_config" ("id", "chain", "symbol", "standard", "contract_address", "decimals", "enabled", "min_deposit", "min_withdraw", "collect_enabled", "created_at", "updated_at", "network", "token_standard", "contract_address_base58", "contract_address_hex", "min_deposit_amount", "min_withdraw_amount", "collect_threshold", "gas_strategy", "confirmation_required") VALUES (7, 'BNB', 'USDT', 'ERC20', '0x8cE7De403F365090C68922610DC6B032F0c95485', 6, true, 0.000000000000000000, 0.000000000000000000, true, '2026-06-20 18:34:35.29862+08', '2026-06-20 20:57:26.159843+08', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL);
INSERT INTO "public"."token_config" ("id", "chain", "symbol", "standard", "contract_address", "decimals", "enabled", "min_deposit", "min_withdraw", "collect_enabled", "created_at", "updated_at", "network", "token_standard", "contract_address_base58", "contract_address_hex", "min_deposit_amount", "min_withdraw_amount", "collect_threshold", "gas_strategy", "confirmation_required") VALUES (8, 'BNB', 'USDC', 'ERC20', '0x7b331c59e5f9139923a06EA0B06CEa36cE9CF5d7', 6, true, 0.000000000000000000, 0.000000000000000000, true, '2026-06-20 18:34:35.301957+08', '2026-06-20 20:57:26.163088+08', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL);
INSERT INTO "public"."token_config" ("id", "chain", "symbol", "standard", "contract_address", "decimals", "enabled", "min_deposit", "min_withdraw", "collect_enabled", "created_at", "updated_at", "network", "token_standard", "contract_address_base58", "contract_address_hex", "min_deposit_amount", "min_withdraw_amount", "collect_threshold", "gas_strategy", "confirmation_required") VALUES (19, 'ARBITRUM', 'USDT', 'ERC20', '0x3A14799B74b73a63b5ca29023946E0EB3A7E6085', 6, true, 0.000000000000000000, 0.000000000000000000, true, '2026-06-20 18:39:07.857094+08', '2026-06-20 20:59:57.252065+08', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL);
INSERT INTO "public"."token_config" ("id", "chain", "symbol", "standard", "contract_address", "decimals", "enabled", "min_deposit", "min_withdraw", "collect_enabled", "created_at", "updated_at", "network", "token_standard", "contract_address_base58", "contract_address_hex", "min_deposit_amount", "min_withdraw_amount", "collect_threshold", "gas_strategy", "confirmation_required") VALUES (20, 'ARBITRUM', 'USDC', 'ERC20', '0x729788b708E76b54C3AE6F230Df7FDB8D8a16394', 6, true, 0.000000000000000000, 0.000000000000000000, true, '2026-06-20 18:39:07.860093+08', '2026-06-20 20:59:57.255482+08', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL);
INSERT INTO "public"."token_config" ("id", "chain", "symbol", "standard", "contract_address", "decimals", "enabled", "min_deposit", "min_withdraw", "collect_enabled", "created_at", "updated_at", "network", "token_standard", "contract_address_base58", "contract_address_hex", "min_deposit_amount", "min_withdraw_amount", "collect_threshold", "gas_strategy", "confirmation_required") VALUES (21, 'OPTIMISM', 'USDT', 'ERC20', '0xcb4CB0127B079d42b81F53e747b763d206D050f9', 6, true, 0.000000000000000000, 0.000000000000000000, true, '2026-06-20 18:39:23.204371+08', '2026-06-20 21:01:27.95842+08', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL);
INSERT INTO "public"."token_config" ("id", "chain", "symbol", "standard", "contract_address", "decimals", "enabled", "min_deposit", "min_withdraw", "collect_enabled", "created_at", "updated_at", "network", "token_standard", "contract_address_base58", "contract_address_hex", "min_deposit_amount", "min_withdraw_amount", "collect_threshold", "gas_strategy", "confirmation_required") VALUES (22, 'OPTIMISM', 'USDC', 'ERC20', '0x66C9A0b2971316079d31dda472f4eB5bF900C53b', 6, true, 0.000000000000000000, 0.000000000000000000, true, '2026-06-20 18:39:23.207534+08', '2026-06-20 21:01:27.961714+08', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL);
INSERT INTO "public"."token_config" ("id", "chain", "symbol", "standard", "contract_address", "decimals", "enabled", "min_deposit", "min_withdraw", "collect_enabled", "created_at", "updated_at", "network", "token_standard", "contract_address_base58", "contract_address_hex", "min_deposit_amount", "min_withdraw_amount", "collect_threshold", "gas_strategy", "confirmation_required") VALUES (23, 'BASE', 'USDT', 'ERC20', '0x210BBd033630e5e611B7922D70b0Caabe64636d9', 6, true, 0.000000000000000000, 0.000000000000000000, true, '2026-06-20 18:39:38.653475+08', '2026-06-20 21:02:57.968281+08', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL);
INSERT INTO "public"."token_config" ("id", "chain", "symbol", "standard", "contract_address", "decimals", "enabled", "min_deposit", "min_withdraw", "collect_enabled", "created_at", "updated_at", "network", "token_standard", "contract_address_base58", "contract_address_hex", "min_deposit_amount", "min_withdraw_amount", "collect_threshold", "gas_strategy", "confirmation_required") VALUES (24, 'BASE', 'USDC', 'ERC20', '0x036cbd53842c5426634e7929541ec2318f3dcf7e', 6, true, 0.000000000000000000, 0.000000000000000000, true, '2026-06-20 18:39:38.656754+08', '2026-06-20 21:02:57.971571+08', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL);
INSERT INTO "public"."token_config" ("id", "chain", "symbol", "standard", "contract_address", "decimals", "enabled", "min_deposit", "min_withdraw", "collect_enabled", "created_at", "updated_at", "network", "token_standard", "contract_address_base58", "contract_address_hex", "min_deposit_amount", "min_withdraw_amount", "collect_threshold", "gas_strategy", "confirmation_required") VALUES (25, 'AVAX_C', 'USDT', 'ERC20', '0x1B43cbC6879C8237469794F9B8Ed290810e502d9', 6, true, 0.000000000000000000, 0.000000000000000000, true, '2026-06-20 18:39:54.422415+08', '2026-06-20 21:04:18.705305+08', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL);
INSERT INTO "public"."token_config" ("id", "chain", "symbol", "standard", "contract_address", "decimals", "enabled", "min_deposit", "min_withdraw", "collect_enabled", "created_at", "updated_at", "network", "token_standard", "contract_address_base58", "contract_address_hex", "min_deposit_amount", "min_withdraw_amount", "collect_threshold", "gas_strategy", "confirmation_required") VALUES (26, 'AVAX_C', 'USDC', 'ERC20', '0xe757C06f170C8EE956E7d80793087c971Ab5D7b5', 6, true, 0.000000000000000000, 0.000000000000000000, true, '2026-06-20 18:39:54.425852+08', '2026-06-20 21:04:18.708504+08', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL);
INSERT INTO "public"."token_config" ("id", "chain", "symbol", "standard", "contract_address", "decimals", "enabled", "min_deposit", "min_withdraw", "collect_enabled", "created_at", "updated_at", "network", "token_standard", "contract_address_base58", "contract_address_hex", "min_deposit_amount", "min_withdraw_amount", "collect_threshold", "gas_strategy", "confirmation_required") VALUES (98, 'SUI', 'TESTCOIN', 'SUI_COIN', '0x2::sui::SUI', 9, false, 1.000000000000000000, 1.000000000000000000, false, '2026-06-23 23:21:21.43093+08', '2026-06-23 23:21:21.43093+08', 'testnet', 'COIN', NULL, NULL, 1.000000000000000000, 1.000000000000000000, 1.000000000000000000, 'SUI_GAS_OBJECT', 1);
INSERT INTO "public"."token_config" ("id", "chain", "symbol", "standard", "contract_address", "decimals", "enabled", "min_deposit", "min_withdraw", "collect_enabled", "created_at", "updated_at", "network", "token_standard", "contract_address_base58", "contract_address_hex", "min_deposit_amount", "min_withdraw_amount", "collect_threshold", "gas_strategy", "confirmation_required") VALUES (17, 'POLYGON', 'USDT', 'ERC20', '0xb5F6211f94FCC162D5c8cebba4f656c965577392', 6, true, 0.000000000000000000, 0.000000000000000000, true, '2026-06-20 18:38:52.367548+08', '2026-06-20 23:13:41.261145+08', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL);
INSERT INTO "public"."token_config" ("id", "chain", "symbol", "standard", "contract_address", "decimals", "enabled", "min_deposit", "min_withdraw", "collect_enabled", "created_at", "updated_at", "network", "token_standard", "contract_address_base58", "contract_address_hex", "min_deposit_amount", "min_withdraw_amount", "collect_threshold", "gas_strategy", "confirmation_required") VALUES (18, 'POLYGON', 'USDC', 'ERC20', '0x729B992ba1ccea88BE66985DCa5Ff28Ebba12046', 6, true, 0.000000000000000000, 0.000000000000000000, true, '2026-06-20 18:38:52.370655+08', '2026-06-20 23:13:41.26619+08', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL);
INSERT INTO "public"."token_config" ("id", "chain", "symbol", "standard", "contract_address", "decimals", "enabled", "min_deposit", "min_withdraw", "collect_enabled", "created_at", "updated_at", "network", "token_standard", "contract_address_base58", "contract_address_hex", "min_deposit_amount", "min_withdraw_amount", "collect_threshold", "gas_strategy", "confirmation_required") VALUES (1, 'ETH', 'USDT', 'ERC20', '0x278E80923f1a7194c0777500d794c489990259FA', 6, true, 0.000000000000000000, 0.000000000000000000, true, '2026-06-20 18:23:33.141962+08', '2026-06-24 17:27:12.470424+08', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL);
INSERT INTO "public"."token_config" ("id", "chain", "symbol", "standard", "contract_address", "decimals", "enabled", "min_deposit", "min_withdraw", "collect_enabled", "created_at", "updated_at", "network", "token_standard", "contract_address_base58", "contract_address_hex", "min_deposit_amount", "min_withdraw_amount", "collect_threshold", "gas_strategy", "confirmation_required") VALUES (75, 'TRON', 'USDT', 'TRC20', 'TXYZopYRdj2D9XRtbG411XZZ3kM5VkAeBf', 6, true, 0.000001000000000000, 0.000001000000000000, true, '2026-06-21 00:25:35.727551+08', '2026-06-21 00:28:58.580102+08', 'NILE', 'TRC20', 'TXYZopYRdj2D9XRtbG411XZZ3kM5VkAeBf', '41eca9bc828a3005b9a3b909f2cc5c2a54794de05f', 0.000001000000000000, 0.000001000000000000, 1.000000000000000000, 'energy-bandwidth', 1);
INSERT INTO "public"."token_config" ("id", "chain", "symbol", "standard", "contract_address", "decimals", "enabled", "min_deposit", "min_withdraw", "collect_enabled", "created_at", "updated_at", "network", "token_standard", "contract_address_base58", "contract_address_hex", "min_deposit_amount", "min_withdraw_amount", "collect_threshold", "gas_strategy", "confirmation_required") VALUES (78, 'SOLANA', 'USDT', 'SPL', '2VHdVaW1jBGmvU1PdQpvmPnWV5AiAxrmxmUTLvf8951y', 6, true, 1.000000000000000000, 1.000000000000000000, true, '2026-06-23 15:01:18.893717+08', '2026-06-23 15:02:47.901793+08', 'devnet', 'SPL', '2VHdVaW1jBGmvU1PdQpvmPnWV5AiAxrmxmUTLvf8951y', NULL, 1.000000000000000000, 1.000000000000000000, 1.000000000000000000, 'SOL_FEE_PAYER', 1);
INSERT INTO "public"."token_config" ("id", "chain", "symbol", "standard", "contract_address", "decimals", "enabled", "min_deposit", "min_withdraw", "collect_enabled", "created_at", "updated_at", "network", "token_standard", "contract_address_base58", "contract_address_hex", "min_deposit_amount", "min_withdraw_amount", "collect_threshold", "gas_strategy", "confirmation_required") VALUES (79, 'SOLANA', 'USDC', 'SPL', '4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU', 6, true, 1.000000000000000000, 1.000000000000000000, true, '2026-06-23 15:01:43.398652+08', '2026-06-23 15:03:11.78696+08', 'devnet', 'SPL', '4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU', NULL, 1.000000000000000000, 1.000000000000000000, 1.000000000000000000, 'SOL_FEE_PAYER', 1);
INSERT INTO "public"."token_config" ("id", "chain", "symbol", "standard", "contract_address", "decimals", "enabled", "min_deposit", "min_withdraw", "collect_enabled", "created_at", "updated_at", "network", "token_standard", "contract_address_base58", "contract_address_hex", "min_deposit_amount", "min_withdraw_amount", "collect_threshold", "gas_strategy", "confirmation_required") VALUES (2, 'ETH', 'USDC', 'ERC20', '0x9478eC397A2F4Be6A84916dD8a353c91b78c6238', 6, true, 0.000000000000000000, 0.000000000000000000, true, '2026-06-20 18:23:33.151844+08', '2026-06-24 17:27:12.473627+08', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL);



--
-- Data for Name: wallet_system_config; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO "public"."wallet_system_config" ("config_key", "config_value", "value_type", "enabled", "remark", "created_at", "updated_at") VALUES ('global.all.enabled', 'true', 'boolean', true, 'Global master switch for all wallet runtime jobs', '2026-06-25 00:10:43.487527+08', '2026-06-25 00:10:43.487527+08');
INSERT INTO "public"."wallet_system_config" ("config_key", "config_value", "value_type", "enabled", "remark", "created_at", "updated_at") VALUES ('global.scan.enabled', 'true', 'boolean', true, 'Global block/account scanner switch', '2026-06-25 00:10:43.487527+08', '2026-06-25 00:10:43.487527+08');
INSERT INTO "public"."wallet_system_config" ("config_key", "config_value", "value_type", "enabled", "remark", "created_at", "updated_at") VALUES ('global.withdraw.enabled', 'true', 'boolean', true, 'Global withdrawal switch', '2026-06-25 00:10:43.487527+08', '2026-06-25 00:10:43.487527+08');
INSERT INTO "public"."wallet_system_config" ("config_key", "config_value", "value_type", "enabled", "remark", "created_at", "updated_at") VALUES ('global.collection.enabled', 'true', 'boolean', true, 'Global collection switch', '2026-06-25 00:10:43.487527+08', '2026-06-25 00:10:43.487527+08');
INSERT INTO "public"."wallet_system_config" ("config_key", "config_value", "value_type", "enabled", "remark", "created_at", "updated_at") VALUES ('global.transfer.enabled', 'true', 'boolean', true, 'Global internal transfer switch', '2026-06-25 00:10:43.487527+08', '2026-06-25 00:10:43.487527+08');
INSERT INTO "public"."wallet_system_config" ("config_key", "config_value", "value_type", "enabled", "remark", "created_at", "updated_at") VALUES ('withdrawal.admin.approval.required', 'false', 'boolean', true, 'When true, wallet app withdrawals freeze user ledger balance and wait in PENDING_REVIEW until wallet admin approval moves the order to FROZEN for signing.', '2026-07-03 01:20:00+08', '2026-07-03 01:20:00+08');
INSERT INTO "public"."wallet_system_config" ("config_key", "config_value", "value_type", "enabled", "remark", "created_at", "updated_at") VALUES ('near.token.account.activation.yocto', '50000000000000000000000', 'bigint', true, 'NEAR native amount sent from the default NEAR hot wallet before preparing a NEP-141 token deposit address. Default is 0.05 NEAR.', '2026-06-28 13:20:00+08', '2026-06-28 13:20:00+08');
INSERT INTO "public"."wallet_system_config" ("config_key", "config_value", "value_type", "enabled", "remark", "created_at", "updated_at") VALUES ('dot.asset_hub.min_sender_gas.planck', '20000000000', 'bigint', true, 'Minimum Asset Hub native free balance required before signing DOT Asset Hub token transfers. Default is 0.02 WND on Westend units.', '2026-06-28 23:00:00+08', '2026-06-28 23:00:00+08');
INSERT INTO "public"."wallet_system_config" ("config_key", "config_value", "value_type", "enabled", "remark", "created_at", "updated_at") VALUES ('dot.asset_hub.token.gas_topup.planck', '100000000000', 'bigint', true, 'Asset Hub native top-up sent from the default DOT hot wallet when a DOT token sender lacks gas. Default is 0.1 WND on Westend units.', '2026-06-28 23:00:00+08', '2026-06-28 23:00:00+08');


--
-- Name: chain_asset_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('"public"."chain_asset_id_seq"', 136, true);


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

SELECT pg_catalog.setval('"public"."token_config_id_seq"', 105, true);


--
-- Normalize testnet token contracts and expose enabled wallet-app token assets.
--

UPDATE "public"."token_config" token
   SET "network" = profile."network",
       "updated_at" = now()
  FROM "public"."chain_profile" profile
 WHERE upper(profile."chain") = upper(token."chain")
   AND profile."enabled" = true
   AND coalesce(trim(token."network"), '') = '';

WITH token_data(chain, symbol, standard, network, token_standard, contract_address, decimals,
                contract_address_base58, contract_address_hex, gas_strategy) AS (
    VALUES
        ('ETH', 'USDC', 'ERC20', 'sepolia', 'ERC20', '0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238', 6, NULL, NULL, 'native-gas'),
        ('POLYGON', 'USDC', 'ERC20', 'amoy', 'ERC20', '0x41E94Eb019C0762f9Bfcf9Fb1E58725BfB0e7582', 6, NULL, NULL, 'native-gas'),
        ('ARBITRUM', 'USDC', 'ERC20', 'sepolia', 'ERC20', '0x75faf114eafb1BDbe2F0316DF893fd58CE46AA4d', 6, NULL, NULL, 'native-gas'),
        ('OPTIMISM', 'USDC', 'ERC20', 'sepolia', 'ERC20', '0x5fd84259d66Cd46123540766Be93DFE6D43130D7', 6, NULL, NULL, 'native-gas'),
        ('BASE', 'USDC', 'ERC20', 'sepolia', 'ERC20', '0x036CbD53842c5426634e7929541eC2318f3dCF7e', 6, NULL, NULL, 'native-gas'),
        ('AVAX_C', 'USDC', 'ERC20', 'fuji', 'ERC20', '0x5425890298aed601595a70AB815c96711a31Bc65', 6, NULL, NULL, 'native-gas'),
        ('BNB', 'USDC', 'ERC20', 'testnet', 'ERC20', '0x31873b5804bABE258d6ea008f55e08DD00b7d51E', 6, NULL, NULL, 'native-gas'),
        ('SOLANA', 'USDC', 'SPL', 'devnet', 'SPL', '4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU', 6, '4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU', NULL, 'SOL_FEE_PAYER'),
        -- Circle's official Aptos Testnet USDC FA metadata.
        ('APTOS', 'USDC', 'APTOS_FA', 'testnet', 'APTOS_FA', '0x69091fbab5f7d635ee7ac5098cf0c1efbe31d68fec0f2cd565e8d168daf52832', 6, NULL, NULL, 'APT_GAS'),
        -- Tether has no Aptos Testnet issuance; this on-chain mock FA is for test flows only.
        ('APTOS', 'USDT', 'APTOS_FA', 'testnet', 'APTOS_FA', '0x6b015ce46a5d15c63c659ebf949c99c3d79dab462db4cf8fb8f609e45229e2cf', 6, NULL, NULL, 'APT_GAS'),
        ('SUI', 'USDC', 'SUI_COIN', 'testnet', 'COIN', '0xa1ec7fc00a6f40db9693ad1415d0c193ad3906494428cf252621037bd7117e29::usdc::USDC', 6, NULL, NULL, 'SUI_GAS_OBJECT'),
        ('TON', 'USDC', 'JETTON', 'testnet', 'JETTON', 'kQCzPT6908-8TR862TQo1S43-2kEme8UKRCRSWkaxNLD7H_2', 6, NULL, NULL, 'TON_FORWARD_FEE'),
        ('XRP', 'USDC', 'XRPL_ISSUED', 'testnet', 'ISSUED_CURRENCY', 'rHuGNhqTG32mfmAvWA8hUyWRLV3tCSwKQt:5553444300000000000000000000000000000000', 6, NULL, '5553444300000000000000000000000000000000', 'XRP_TRUSTLINE'),
        ('ETH', 'USDT', 'ERC20', 'sepolia', 'ERC20', '0x01a6810727db185bbf7f30ec158c3ac8b8112627', 6, NULL, NULL, 'native-gas'),
        ('POLYGON', 'USDT', 'ERC20', 'amoy', 'ERC20', '0xb5F6211f94FCC162D5c8cebba4f656c965577392', 6, NULL, NULL, 'native-gas'),
        ('ARBITRUM', 'USDT', 'ERC20', 'sepolia', 'ERC20', '0xEf54C221Fc94517877F0F40eCd71E0A3866D66C2', 6, NULL, NULL, 'native-gas'),
        ('OPTIMISM', 'USDT', 'ERC20', 'sepolia', 'ERC20', '0xcb4CB0127B079d42b81F53e747b763d206D050f9', 6, NULL, NULL, 'native-gas'),
        ('BASE', 'USDT', 'ERC20', 'sepolia', 'ERC20', '0x210BBd033630e5e611B7922D70b0Caabe64636d9', 6, NULL, NULL, 'native-gas'),
        ('AVAX_C', 'USDT', 'ERC20', 'fuji', 'ERC20', '0x1B43cbC6879C8237469794F9B8Ed290810e502d9', 6, NULL, NULL, 'native-gas'),
        ('BNB', 'USDT', 'ERC20', 'testnet', 'ERC20', '0xE8709025f99dd1B8533FB9b78Ca879Ee4ec7E70a', 6, NULL, NULL, 'native-gas'),
        ('TRON', 'USDT', 'TRC20', 'NILE', 'TRC20', 'TXYZopYRdj2D9XRtbG411XZZ3kM5VkAeBf', 6, 'TXYZopYRdj2D9XRtbG411XZZ3kM5VkAeBf', '41eca9bc828a3005b9a3b909f2cc5c2a54794de05f', 'energy-bandwidth'),
        ('SOLANA', 'USDT', 'SPL', 'devnet', 'SPL', '2VHdVaW1jBGmvU1PdQpvmPnWV5AiAxrmxmUTLvf8951y', 6, '2VHdVaW1jBGmvU1PdQpvmPnWV5AiAxrmxmUTLvf8951y', NULL, 'SOL_FEE_PAYER'),
        ('TON', 'USDT', 'JETTON', 'testnet', 'JETTON', 'kQCZ5SAA78W_0vA5eSoU23YomxnUwah3KYagqeesNQI5jOXT', 6, NULL, NULL, 'TON_FORWARD_FEE')
)
INSERT INTO "public"."token_config" ("chain", "symbol", "standard", "contract_address", "decimals", "enabled",
                                     "min_deposit", "min_withdraw", "collect_enabled", "created_at", "updated_at",
                                     "network", "token_standard", "contract_address_base58", "contract_address_hex",
                                     "min_deposit_amount", "min_withdraw_amount", "collect_threshold",
                                     "gas_strategy", "confirmation_required")
SELECT chain, symbol, standard, contract_address, decimals, true,
       1, 1, true, now(), now(),
       network, token_standard, contract_address_base58, contract_address_hex,
       1, 1, 1, gas_strategy, 1
  FROM token_data
ON CONFLICT ("chain", "network", "symbol") DO UPDATE SET
    "standard" = EXCLUDED."standard",
    "contract_address" = EXCLUDED."contract_address",
    "decimals" = EXCLUDED."decimals",
    "enabled" = true,
    "min_deposit" = EXCLUDED."min_deposit",
    "min_withdraw" = EXCLUDED."min_withdraw",
    "collect_enabled" = true,
    "updated_at" = now(),
    "network" = EXCLUDED."network",
    "token_standard" = EXCLUDED."token_standard",
    "contract_address_base58" = EXCLUDED."contract_address_base58",
    "contract_address_hex" = EXCLUDED."contract_address_hex",
    "min_deposit_amount" = EXCLUDED."min_deposit_amount",
    "min_withdraw_amount" = EXCLUDED."min_withdraw_amount",
    "collect_threshold" = EXCLUDED."collect_threshold",
    "gas_strategy" = EXCLUDED."gas_strategy",
    "confirmation_required" = EXCLUDED."confirmation_required";

INSERT INTO "public"."chain_asset" ("chain", "symbol", "asset_kind", "contract_address", "decimals",
                                    "native_asset", "active", "min_transfer", "min_withdraw",
                                    "created_at", "updated_at")
SELECT "chain", "symbol",
       CASE WHEN "standard" = 'JETTON' THEN 'JETTON' ELSE 'TOKEN' END,
       "contract_address", "decimals", false, true,
       COALESCE("min_deposit_amount", "min_deposit", 1),
       COALESCE("min_withdraw_amount", "min_withdraw", 1),
       now(), now()
  FROM "public"."token_config"
 WHERE "enabled" = true
   AND "symbol" IN ('USDC', 'USDT')
ON CONFLICT ("chain", "symbol") DO UPDATE SET
    "asset_kind" = EXCLUDED."asset_kind",
    "contract_address" = EXCLUDED."contract_address",
    "decimals" = EXCLUDED."decimals",
    "native_asset" = false,
    "active" = true,
    "min_transfer" = EXCLUDED."min_transfer",
    "min_withdraw" = EXCLUDED."min_withdraw",
    "updated_at" = now();

UPDATE "public"."chain_asset"
   SET "min_transfer" = 0.00000546, "min_withdraw" = 0.00000546
 WHERE "chain" IN ('BTC', 'BCH') AND "symbol" IN ('BTC', 'BCH');
UPDATE "public"."chain_asset"
   SET "min_transfer" = 0.001, "min_withdraw" = 0.001
 WHERE "chain" = 'LTC' AND "symbol" = 'LTC';
UPDATE "public"."chain_asset"
   SET "min_transfer" = 0.01, "min_withdraw" = 0.01
 WHERE "chain" = 'DOGE' AND "symbol" = 'DOGE';
UPDATE "public"."chain_asset"
   SET "min_transfer" = 0.001, "min_withdraw" = 0.001
 WHERE "chain" = 'SOLANA' AND "symbol" = 'SOL';
UPDATE "public"."chain_asset"
   SET "min_transfer" = 0.000001, "min_withdraw" = 0.000001
 WHERE "chain" = 'SOLANA' AND "symbol" IN ('USDC', 'USDT');
UPDATE "public"."chain_asset"
   SET "min_transfer" = 0.01, "min_withdraw" = 0.01
 WHERE "chain" = 'TON' AND "symbol" = 'TON';
UPDATE "public"."chain_asset"
   SET "min_transfer" = 0.000001, "min_withdraw" = 0.000001
 WHERE "chain" = 'TON' AND "symbol" IN ('USDC', 'USDT');
UPDATE "public"."chain_asset"
   SET "min_transfer" = 0.001, "min_withdraw" = 0.001
 WHERE "chain" IN ('APTOS', 'SUI') AND "native_asset" = true;
UPDATE "public"."chain_asset"
   SET "min_transfer" = 0.000001, "min_withdraw" = 0.000001
 WHERE "chain" IN ('APTOS', 'SUI') AND "native_asset" = false;
UPDATE "public"."chain_asset"
   SET "min_transfer" = 0.000001, "min_withdraw" = 0.000001
 WHERE "chain" = 'XRP' AND "symbol" = 'XRP';
UPDATE "public"."chain_asset"
   SET "min_transfer" = 0.000001, "min_withdraw" = 0.000001
 WHERE "chain" = 'XRP' AND "symbol" = 'USDC';

-- HyperEVM is an EVM-compatible chain; HyperCore exchange-account flows are intentionally
-- not modeled here. The seeded USDC row is Circle Native USDC on HyperEVM testnet.
-- Prod databases must replace it with the HyperEVM mainnet USDC contract before enabling prod.
INSERT INTO "public"."chain_asset" ("chain", "symbol", "asset_kind", "contract_address", "decimals",
                                    "native_asset", "active", "min_transfer", "min_withdraw",
                                    "created_at", "updated_at")
VALUES
    ('HYPEREVM', 'HYPE', 'NATIVE', NULL, 18, true, true, 0.000001, 0.000001, now(), now()),
    ('HYPEREVM', 'USDC', 'ERC20', '0x2B3370eE501B4a559b57D449569354196457D8Ab', 6, false, true, 1, 1, now(), now())
ON CONFLICT ("chain", "symbol") DO UPDATE SET
    "asset_kind" = EXCLUDED."asset_kind",
    "contract_address" = EXCLUDED."contract_address",
    "decimals" = EXCLUDED."decimals",
    "native_asset" = EXCLUDED."native_asset",
    "active" = EXCLUDED."active",
    "min_transfer" = EXCLUDED."min_transfer",
    "min_withdraw" = EXCLUDED."min_withdraw",
    "updated_at" = now();

INSERT INTO "public"."token_config" ("chain", "symbol", "standard", "contract_address", "decimals", "enabled",
                                     "min_deposit", "min_withdraw", "collect_enabled", "created_at", "updated_at",
                                     "network", "token_standard", "min_deposit_amount", "min_withdraw_amount",
                                     "collect_threshold", "gas_strategy", "confirmation_required")
VALUES
    ('HYPEREVM', 'USDC', 'ERC20', '0x2B3370eE501B4a559b57D449569354196457D8Ab', 6, true,
     1, 1, true, now(), now(), 'testnet', 'ERC20', 1, 1, 1, 'native-gas', 1)
ON CONFLICT ("chain", "network", "symbol") DO UPDATE SET
    "standard" = EXCLUDED."standard",
    "contract_address" = EXCLUDED."contract_address",
    "decimals" = EXCLUDED."decimals",
    "enabled" = EXCLUDED."enabled",
    "min_deposit" = EXCLUDED."min_deposit",
    "min_withdraw" = EXCLUDED."min_withdraw",
    "collect_enabled" = EXCLUDED."collect_enabled",
    "network" = EXCLUDED."network",
    "token_standard" = EXCLUDED."token_standard",
    "min_deposit_amount" = EXCLUDED."min_deposit_amount",
    "min_withdraw_amount" = EXCLUDED."min_withdraw_amount",
    "collect_threshold" = EXCLUDED."collect_threshold",
    "gas_strategy" = EXCLUDED."gas_strategy",
    "confirmation_required" = EXCLUDED."confirmation_required",
    "updated_at" = now();

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

-- Mantle, Linea, Scroll and Unichain reuse the shared EVM adapter. Testnet profiles are enabled.
-- Token rows only use verified official/token-list contracts. Mantle testnet USDC/USDT is not
-- present in the official Mantle token list, so Mantle token rows are kept disabled until prod.
-- Unichain USDC uses Circle's official Sepolia contract here; prod databases must replace it
-- with the Unichain mainnet USDC contract 0x078D782b760474a361dDA0AF3839290b0EF57AD6 before enabling prod.
INSERT INTO "public"."chain_asset" ("chain", "symbol", "asset_kind", "contract_address", "decimals",
                                    "native_asset", "active", "min_transfer", "min_withdraw",
                                    "created_at", "updated_at")
VALUES
    ('MANTLE', 'MNT', 'NATIVE', NULL, 18, true, true, 0.000001, 0.000001, now(), now()),
    ('MANTLE', 'USDC', 'ERC20', '0x09Bc4E0D864854c6aFB6eB9A9cdF58aC190D0dF9', 6, false, false, 1, 1, now(), now()),
    ('MANTLE', 'USDT', 'ERC20', '0x201EBa5CC46D216Ce6DC03F6a759e8E766e956aE', 6, false, false, 1, 1, now(), now()),
    ('LINEA', 'ETH_LINEA', 'NATIVE', NULL, 18, true, true, 0.000001, 0.000001, now(), now()),
    ('LINEA', 'USDC', 'ERC20', '0xFEce4462D57bD51A6A552365A011b95f0E16d9B7', 6, false, true, 1, 1, now(), now()),
    ('LINEA', 'USDT', 'ERC20', '0xA219439258ca9da29E9Cc4cE5596924745e12B93', 6, false, false, 1, 1, now(), now()),
    ('SCROLL', 'ETH_SCROLL', 'NATIVE', NULL, 18, true, true, 0.000001, 0.000001, now(), now()),
    ('SCROLL', 'USDC', 'ERC20', '0x7878290DB8C4f02bd06E0E249617871c19508bE6', 6, false, true, 1, 1, now(), now()),
    ('SCROLL', 'USDT', 'ERC20', '0xf55BEC9cafDbE8730f096Aa55dad6D22d44099Df', 6, false, false, 1, 1, now(), now()),
    ('UNICHAIN', 'ETH_UNICHAIN', 'NATIVE', NULL, 18, true, true, 0.000001, 0.000001, now(), now()),
    ('UNICHAIN', 'USDC', 'ERC20', '0x31d0220469e10c4E71834a79b1f276d740d3768F', 6, false, true, 1, 1, now(), now())
ON CONFLICT ("chain", "symbol") DO UPDATE SET
    "asset_kind" = EXCLUDED."asset_kind",
    "contract_address" = EXCLUDED."contract_address",
    "decimals" = EXCLUDED."decimals",
    "native_asset" = EXCLUDED."native_asset",
    "active" = EXCLUDED."active",
    "min_transfer" = EXCLUDED."min_transfer",
    "min_withdraw" = EXCLUDED."min_withdraw",
    "updated_at" = now();

INSERT INTO "public"."token_config" ("chain", "symbol", "standard", "contract_address", "decimals", "enabled",
                                     "min_deposit", "min_withdraw", "collect_enabled", "created_at", "updated_at",
                                     "network", "token_standard", "min_deposit_amount", "min_withdraw_amount",
                                     "collect_threshold", "gas_strategy", "confirmation_required")
VALUES
    ('MANTLE', 'USDC', 'ERC20', '0x09Bc4E0D864854c6aFB6eB9A9cdF58aC190D0dF9', 6, false,
     1, 1, true, now(), now(), 'mainnet', 'ERC20', 1, 1, 1, 'native-gas', 1),
    ('MANTLE', 'USDT', 'ERC20', '0x201EBa5CC46D216Ce6DC03F6a759e8E766e956aE', 6, false,
     1, 1, true, now(), now(), 'mainnet', 'ERC20', 1, 1, 1, 'native-gas', 1),
    ('LINEA', 'USDC', 'ERC20', '0xFEce4462D57bD51A6A552365A011b95f0E16d9B7', 6, true,
     1, 1, true, now(), now(), 'sepolia', 'ERC20', 1, 1, 1, 'native-gas', 1),
    ('LINEA', 'USDT', 'ERC20', '0xA219439258ca9da29E9Cc4cE5596924745e12B93', 6, false,
     1, 1, true, now(), now(), 'mainnet', 'ERC20', 1, 1, 1, 'native-gas', 1),
    ('SCROLL', 'USDC', 'ERC20', '0x7878290DB8C4f02bd06E0E249617871c19508bE6', 6, true,
     1, 1, true, now(), now(), 'sepolia', 'ERC20', 1, 1, 1, 'native-gas', 1),
    ('SCROLL', 'USDT', 'ERC20', '0xf55BEC9cafDbE8730f096Aa55dad6D22d44099Df', 6, false,
     1, 1, true, now(), now(), 'mainnet', 'ERC20', 1, 1, 1, 'native-gas', 1),
    ('UNICHAIN', 'USDC', 'ERC20', '0x31d0220469e10c4E71834a79b1f276d740d3768F', 6, true,
     1, 1, true, now(), now(), 'sepolia', 'ERC20', 1, 1, 1, 'native-gas', 1)
ON CONFLICT ("chain", "network", "symbol") DO UPDATE SET
    "standard" = EXCLUDED."standard",
    "contract_address" = EXCLUDED."contract_address",
    "decimals" = EXCLUDED."decimals",
    "enabled" = EXCLUDED."enabled",
    "min_deposit" = EXCLUDED."min_deposit",
    "min_withdraw" = EXCLUDED."min_withdraw",
    "collect_enabled" = EXCLUDED."collect_enabled",
    "network" = EXCLUDED."network",
    "token_standard" = EXCLUDED."token_standard",
    "min_deposit_amount" = EXCLUDED."min_deposit_amount",
    "min_withdraw_amount" = EXCLUDED."min_withdraw_amount",
    "collect_threshold" = EXCLUDED."collect_threshold",
    "gas_strategy" = EXCLUDED."gas_strategy",
    "confirmation_required" = EXCLUDED."confirmation_required",
    "updated_at" = now();

INSERT INTO "public"."chain_profile" ("chain", "network", "family", "runtime_currency_id", "bip44_coin_type",
                                      "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations",
                                      "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled",
                                      "created_at", "updated_at", "chain_id", "gas_policy", "scan_batch_size",
                                      "scan_enabled", "withdraw_enabled", "collection_enabled", "transfer_enabled",
                                      "scan_start_height", "scan_max_blocks_per_run")
VALUES
    ('MANTLE', 'sepolia', 'evm', 9006, 60, 'MNT',
     'https://rpc.sepolia.mantle.xyz', 'https://sepolia.mantlescan.xyz/tx/',
     40, 40, 1, 0, true, now(), now(), 5003, 'eip1559-l2', 200,
     true, true, true, true, 0, 200),
    ('MANTLE', 'mainnet', 'evm', 9006, 60, 'MNT',
     'https://rpc.mantle.xyz', 'https://mantlescan.xyz/tx/',
     40, 40, 1, 0, false, now(), now(), 5000, 'eip1559-l2', 200,
     false, false, false, false, 0, 200),
    ('LINEA', 'sepolia', 'evm', 9007, 60, 'ETH_LINEA',
     'https://rpc.sepolia.linea.build', 'https://sepolia.lineascan.build/tx/',
     40, 40, 1, 0, true, now(), now(), 59141, 'eip1559-l2', 200,
     true, true, true, true, 0, 200),
    ('LINEA', 'mainnet', 'evm', 9007, 60, 'ETH_LINEA',
     'https://rpc.linea.build', 'https://lineascan.build/tx/',
     40, 40, 1, 0, false, now(), now(), 59144, 'eip1559-l2', 200,
     false, false, false, false, 0, 200),
    ('SCROLL', 'sepolia', 'evm', 9008, 60, 'ETH_SCROLL',
     'https://sepolia-rpc.scroll.io', 'https://sepolia.scrollscan.com/tx/',
     40, 40, 1, 0, true, now(), now(), 534351, 'eip1559-l2', 200,
     true, true, true, true, 0, 200),
    ('SCROLL', 'mainnet', 'evm', 9008, 60, 'ETH_SCROLL',
     'https://rpc.scroll.io', 'https://scrollscan.com/tx/',
     40, 40, 1, 0, false, now(), now(), 534352, 'eip1559-l2', 200,
     false, false, false, false, 0, 200),
    ('UNICHAIN', 'sepolia', 'evm', 9009, 60, 'ETH_UNICHAIN',
     'https://sepolia.unichain.org', 'https://sepolia.uniscan.xyz/tx/',
     40, 40, 1, 0, true, now(), now(), 1301, 'eip1559-l2', 200,
     true, true, true, true, 0, 200),
    ('UNICHAIN', 'mainnet', 'evm', 9009, 60, 'ETH_UNICHAIN',
     'https://mainnet.unichain.org', 'https://uniscan.xyz/tx/',
     40, 40, 1, 0, false, now(), now(), 130, 'eip1559-l2', 200,
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
    ('MANTLE', 'sepolia', 'dev', 'official-mantle-sepolia', 'rpc', 'HTTP_JSON_RPC',
     'https://rpc.sepolia.mantle.xyz', 'NONE', NULL, 10, 500, true,
     'Official Mantle Sepolia JSON-RPC endpoint.',
     now(), now(), NULL),
    ('MANTLE', 'sepolia', 'test2', 'official-mantle-sepolia', 'rpc', 'HTTP_JSON_RPC',
     'https://rpc.sepolia.mantle.xyz', 'NONE', NULL, 10, 500, true,
     'test2 official Mantle Sepolia JSON-RPC endpoint.',
     now(), now(), NULL),
    ('MANTLE', 'mainnet', 'prod', 'official-mantle-mainnet', 'rpc', 'HTTP_JSON_RPC',
     'https://rpc.mantle.xyz', 'NONE', NULL, 10, 1000, false,
     'Production Mantle mainnet JSON-RPC endpoint. Enable only after funding and monitoring are ready.',
     now(), now(), NULL),
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

-- HyperCore is the Hyperliquid core account layer. USDC uses usdSend; HYPE/HIP-1 tokens use spotSend.
INSERT INTO "public"."chain_asset" ("chain", "symbol", "asset_kind", "contract_address", "decimals",
                                    "native_asset", "active", "min_transfer", "min_withdraw",
                                    "created_at", "updated_at")
VALUES
    ('HYPERCORE', 'USDC', 'CORE_USDC', NULL, 6, true, true, 1, 1, now(), now()),
    ('HYPERCORE', 'HYPE', 'HIP1', '0x7317beb7cceed72ef0b346074cc8e7ab', 8, false, true, 0.000001, 0.000001, now(), now())
ON CONFLICT ("chain", "symbol") DO UPDATE SET
    "asset_kind" = EXCLUDED."asset_kind",
    "contract_address" = EXCLUDED."contract_address",
    "decimals" = EXCLUDED."decimals",
    "native_asset" = EXCLUDED."native_asset",
    "active" = EXCLUDED."active",
    "min_transfer" = EXCLUDED."min_transfer",
    "min_withdraw" = EXCLUDED."min_withdraw",
    "updated_at" = now();

INSERT INTO "public"."token_config" ("chain", "symbol", "standard", "contract_address", "decimals", "enabled",
                                     "min_deposit", "min_withdraw", "collect_enabled", "created_at", "updated_at",
                                     "network", "token_standard", "min_deposit_amount", "min_withdraw_amount",
                                     "collect_threshold", "gas_strategy", "confirmation_required")
VALUES
    ('HYPERCORE', 'HYPE', 'HIP1', '0x7317beb7cceed72ef0b346074cc8e7ab', 8, true,
     0.000001, 0.000001, true, now(), now(), 'testnet', 'HIP1', 0.000001, 0.000001, 0.000001, 'hypercore-no-gas', 1)
ON CONFLICT ("chain", "network", "symbol") DO UPDATE SET
    "standard" = EXCLUDED."standard",
    "contract_address" = EXCLUDED."contract_address",
    "decimals" = EXCLUDED."decimals",
    "enabled" = EXCLUDED."enabled",
    "min_deposit" = EXCLUDED."min_deposit",
    "min_withdraw" = EXCLUDED."min_withdraw",
    "collect_enabled" = EXCLUDED."collect_enabled",
    "network" = EXCLUDED."network",
    "token_standard" = EXCLUDED."token_standard",
    "min_deposit_amount" = EXCLUDED."min_deposit_amount",
    "min_withdraw_amount" = EXCLUDED."min_withdraw_amount",
    "collect_threshold" = EXCLUDED."collect_threshold",
    "gas_strategy" = EXCLUDED."gas_strategy",
    "confirmation_required" = EXCLUDED."confirmation_required",
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

--
-- New-chain placeholders: keep disabled until each chain adapter supports
-- deterministic address, deposit scan, withdrawal confirmation and collection.
--

INSERT INTO "public"."chain_asset" ("chain", "symbol", "asset_kind", "contract_address", "decimals",
                                    "native_asset", "active", "min_transfer", "min_withdraw",
                                    "created_at", "updated_at")
VALUES
    ('ADA', 'ADA', 'NATIVE', NULL, 6, true, false, 0.001, 0.001, now(), now()),
    ('DOT', 'DOT', 'NATIVE', NULL, 12, true, false, 0.01, 0.01, now(), now()),
    ('NEAR', 'NEAR', 'NATIVE', NULL, 24, true, false, 0.001, 0.001, now(), now()),
    ('XMR', 'XMR', 'NATIVE', NULL, 12, true, false, 0.0001, 0.0001, now(), now())
ON CONFLICT ("chain", "symbol") DO UPDATE SET
    "asset_kind" = EXCLUDED."asset_kind",
    "contract_address" = EXCLUDED."contract_address",
    "decimals" = EXCLUDED."decimals",
    "native_asset" = EXCLUDED."native_asset",
    "active" = EXCLUDED."active",
    "min_transfer" = EXCLUDED."min_transfer",
    "min_withdraw" = EXCLUDED."min_withdraw",
    "updated_at" = now();

WITH new_chain_token_assets(chain, symbol, asset_kind, contract_address, decimals,
                            min_transfer, min_withdraw) AS (
    VALUES
        ('ADA', 'USDC', 'CARDANO_NATIVE_ASSET', 'CHANGE_ME_CARDANO_USDC_POLICY_ID.CHANGE_ME_ASSET_NAME_HEX', 6, 1, 1),
        ('ADA', 'USDT', 'CARDANO_NATIVE_ASSET', 'CHANGE_ME_CARDANO_USDT_POLICY_ID.CHANGE_ME_ASSET_NAME_HEX', 6, 1, 1),
        ('DOT', 'USDC', 'ASSET_HUB_ASSET', '31337', 6, 1, 1),
        ('DOT', 'USDT', 'ASSET_HUB_ASSET', '1984', 6, 1, 1),
        ('NEAR', 'USDC', 'NEP141', '3e2210e1184b45b64c8a434c0a7e7b23cc04ea7eb7a6c3c32520d03d4afcb8af', 6, 1, 1),
        ('NEAR', 'USDT', 'NEP141', 'CHANGE_ME_USDT_CONTRACT.testnet', 6, 1, 1)
)
INSERT INTO "public"."chain_asset" ("chain", "symbol", "asset_kind", "contract_address", "decimals",
                                    "native_asset", "active", "min_transfer", "min_withdraw",
                                    "created_at", "updated_at")
SELECT chain, symbol, asset_kind, contract_address, decimals, false, false,
       min_transfer, min_withdraw, now(), now()
  FROM new_chain_token_assets
ON CONFLICT ("chain", "symbol") DO UPDATE SET
    "asset_kind" = EXCLUDED."asset_kind",
    "contract_address" = EXCLUDED."contract_address",
    "decimals" = EXCLUDED."decimals",
    "native_asset" = EXCLUDED."native_asset",
    "active" = EXCLUDED."active",
    "min_transfer" = EXCLUDED."min_transfer",
    "min_withdraw" = EXCLUDED."min_withdraw",
    "updated_at" = now();

WITH new_chain_tokens(chain, symbol, standard, network, token_standard, contract_address, decimals,
                      min_deposit, min_withdraw, collect_threshold, gas_strategy, confirmation_required) AS (
    VALUES
        ('ADA', 'USDC', 'CARDANO_NATIVE_ASSET', 'preprod', 'CARDANO_NATIVE_ASSET',
         'CHANGE_ME_CARDANO_USDC_POLICY_ID.CHANGE_ME_ASSET_NAME_HEX', 6, 1, 1, 1, 'CARDANO_MIN_ADA', 15),
        ('ADA', 'USDT', 'CARDANO_NATIVE_ASSET', 'preprod', 'CARDANO_NATIVE_ASSET',
         'CHANGE_ME_CARDANO_USDT_POLICY_ID.CHANGE_ME_ASSET_NAME_HEX', 6, 1, 1, 1, 'CARDANO_MIN_ADA', 15),
        ('DOT', 'USDC', 'ASSET_HUB_ASSET', 'westend', 'ASSET_HUB_ASSET',
         '31337', 6, 1, 1, 1, 'ASSET_HUB_NATIVE_GAS', 12),
        ('DOT', 'USDT', 'ASSET_HUB_ASSET', 'westend', 'ASSET_HUB_ASSET',
         '1984', 6, 1, 1, 1, 'ASSET_HUB_NATIVE_GAS', 12),
        ('NEAR', 'USDC', 'NEP141', 'testnet', 'NEP141',
         '3e2210e1184b45b64c8a434c0a7e7b23cc04ea7eb7a6c3c32520d03d4afcb8af', 6, 1, 1, 1, 'NEAR_STORAGE_DEPOSIT', 1),
        ('NEAR', 'USDT', 'NEP141', 'testnet', 'NEP141',
         'CHANGE_ME_USDT_CONTRACT.testnet', 6, 1, 1, 1, 'NEAR_STORAGE_DEPOSIT', 1)
)
INSERT INTO "public"."token_config" ("chain", "symbol", "standard", "contract_address", "decimals", "enabled",
                                     "min_deposit", "min_withdraw", "collect_enabled", "created_at", "updated_at",
                                     "network", "token_standard", "min_deposit_amount", "min_withdraw_amount",
                                     "collect_threshold", "gas_strategy", "confirmation_required")
SELECT chain, symbol, standard, contract_address, decimals, false,
       min_deposit, min_withdraw, false, now(), now(), network, token_standard,
       min_deposit, min_withdraw, collect_threshold, gas_strategy, confirmation_required
  FROM new_chain_tokens
ON CONFLICT ("chain", "network", "symbol") DO UPDATE SET
    "standard" = EXCLUDED."standard",
    "contract_address" = EXCLUDED."contract_address",
    "decimals" = EXCLUDED."decimals",
    "enabled" = EXCLUDED."enabled",
    "min_deposit" = EXCLUDED."min_deposit",
    "min_withdraw" = EXCLUDED."min_withdraw",
    "collect_enabled" = EXCLUDED."collect_enabled",
    "network" = EXCLUDED."network",
    "token_standard" = EXCLUDED."token_standard",
    "min_deposit_amount" = EXCLUDED."min_deposit_amount",
    "min_withdraw_amount" = EXCLUDED."min_withdraw_amount",
    "collect_threshold" = EXCLUDED."collect_threshold",
    "gas_strategy" = EXCLUDED."gas_strategy",
    "confirmation_required" = EXCLUDED."confirmation_required",
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
    ('XMR', 'regtest', 'dev', 'local-monero-wallet-rpc-regtest', 'rpc', 'WALLET_RPC',
     'http://127.0.0.1:18088', 'NONE', NULL, 5, 250, true,
     'Local monero-wallet-rpc connected to monerod --regtest for deterministic integration tests.',
     now(), now(), NULL, NULL),
    ('XMR', 'regtest', 'dev', 'local-monero-wallet-rpc-funder-regtest', 'faucet', 'WALLET_RPC',
     'http://127.0.0.1:18090', 'NONE', NULL, 6, 250, true,
     'Local funder monero-wallet-rpc used only by the non-production XMR regtest faucet.',
     now(), now(), NULL, NULL),
    ('XMR', 'regtest', 'dev', 'local-monerod-regtest', 'daemon', 'HTTP_JSON_RPC',
     'http://127.0.0.1:18081', 'NONE', NULL, 7, 250, true,
     'Local monerod --regtest daemon RPC used by the XMR regtest faucet to mine confirmations.',
     now(), now(), NULL, NULL),
    ('XMR', 'regtest', 'test2', 'local-monero-wallet-rpc-regtest', 'rpc', 'WALLET_RPC',
     'http://127.0.0.1:18088', 'DIGEST', NULL, 5, 250, true,
     'test2 monero-wallet-rpc placeholder. Bind to the private host IP and replace username/password in DB before enabling XMR.',
     now(), now(), 'CHANGE_ME_MONERO_RPC_USER', 'CHANGE_ME_MONERO_RPC_PASSWORD'),
    ('XMR', 'regtest', 'test2', 'local-monero-wallet-rpc-funder-regtest', 'faucet', 'WALLET_RPC',
     'http://127.0.0.1:18090', 'DIGEST', NULL, 6, 250, true,
     'test2 funder monero-wallet-rpc placeholder. Bind to the private host IP and replace username/password in DB before enabling the faucet.',
     now(), now(), 'CHANGE_ME_MONERO_RPC_USER', 'CHANGE_ME_MONERO_RPC_PASSWORD'),
    ('XMR', 'regtest', 'test2', 'local-monerod-regtest', 'daemon', 'HTTP_JSON_RPC',
     'http://127.0.0.1:18081', 'NONE', NULL, 7, 250, true,
     'test2 monerod --regtest daemon RPC placeholder. Bind to the private host IP before enabling the faucet.',
     now(), now(), NULL, NULL),
    ('XMR', 'stagenet', 'test2', 'local-monero-wallet-rpc-stagenet', 'rpc', 'WALLET_RPC',
     'http://127.0.0.1:38088', 'DIGEST', NULL, 10, 500, true,
     'Local monero-wallet-rpc connected to a stagenet wallet. Bind to localhost or private network only; replace username/password in DB.',
     now(), now(), 'CHANGE_ME_MONERO_RPC_USER', 'CHANGE_ME_MONERO_RPC_PASSWORD'),
    ('XMR', 'mainnet', 'prod', 'local-monero-wallet-rpc-mainnet', 'rpc', 'WALLET_RPC',
     'http://127.0.0.1:18088', 'DIGEST', NULL, 10, 500, false,
     'Production monero-wallet-rpc endpoint placeholder; keep disabled until wallet-rpc is hardened, backed up and funded.',
     now(), now(), 'CHANGE_ME_MONERO_RPC_USER', 'CHANGE_ME_MONERO_RPC_PASSWORD')
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
    ('ADA', 'preprod', 'dev', 'blockfrost-cardano-preprod', 'rpc', 'BLOCKFROST',
     'https://cardano-preprod.blockfrost.io/api/v0', 'PROJECT_ID', NULL, 10, 500, false,
     'Cardano preprod Blockfrost-compatible backend. Fill api_key and keep chain_profile disabled until e2e tests pass.',
     now(), now(), 'CHANGE_ME_BLOCKFROST_PREPROD_PROJECT_ID'),
    ('ADA', 'preprod', 'test2', 'blockfrost-cardano-preprod', 'rpc', 'BLOCKFROST',
     'https://cardano-preprod.blockfrost.io/api/v0', 'PROJECT_ID', NULL, 10, 500, false,
     'test2 Cardano preprod Blockfrost-compatible backend. Fill api_key and keep chain_profile disabled until e2e tests pass.',
     now(), now(), 'CHANGE_ME_BLOCKFROST_PREPROD_PROJECT_ID'),
    ('ADA', 'mainnet', 'prod', 'blockfrost-cardano-mainnet', 'rpc', 'BLOCKFROST',
     'https://cardano-mainnet.blockfrost.io/api/v0', 'PROJECT_ID', NULL, 10, 1000, false,
     'Production Cardano Blockfrost-compatible backend placeholder; enable only after production key, monitoring and e2e tests are ready.',
     now(), now(), 'CHANGE_ME_BLOCKFROST_MAINNET_PROJECT_ID')
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
    ('DOT', 'westend', 'dev', 'polkadot-westend-ws', 'rpc', 'WS_RPC',
     'wss://westend-rpc.polkadot.io', 'NONE', NULL, 10, 250, false,
     'Westend substrate WebSocket node used by the metadata-aware Polkadot runtime service.',
     now(), now(), NULL),
    ('DOT', 'westend', 'dev', 'polkadot-westend-assethub-ws', 'asset_rpc', 'WS_RPC',
     'wss://westend-asset-hub-rpc.polkadot.io', 'NONE', NULL, 10, 250, false,
     'Official Westend Asset Hub WebSocket node used only for DOT token scan/sign/confirm.',
     now(), now(), NULL),
    ('DOT', 'westend', 'dev', 'dotters-westend-assethub-ws', 'asset_rpc', 'WS_RPC',
     'wss://asset-hub-westend.dotters.network', 'NONE', NULL, 20, 250, false,
     'Backup Westend Asset Hub WebSocket node used only for DOT token scan/sign/confirm.',
     now(), now(), NULL),
    ('DOT', 'westend', 'dev', 'local-polkadot-runtime-service', 'runtime', 'HTTP_JSON',
     'http://127.0.0.1:8787', 'bearer', 'Authorization', 20, 100, false,
     'Local Polkadot runtime service backed by @polkadot/api. Fill api_key and enable only with the DOT profile during e2e tests.',
     now(), now(), 'CHANGE_ME_POLKADOT_RUNTIME_API_KEY'),
    ('DOT', 'westend', 'test2', 'polkadot-westend-ws', 'rpc', 'WS_RPC',
     'wss://westend-rpc.polkadot.io', 'NONE', NULL, 10, 250, false,
     'test2 Westend substrate WebSocket node used by the metadata-aware Polkadot runtime service.',
     now(), now(), NULL),
    ('DOT', 'westend', 'test2', 'polkadot-westend-assethub-ws', 'asset_rpc', 'WS_RPC',
     'wss://westend-asset-hub-rpc.polkadot.io', 'NONE', NULL, 10, 250, false,
     'test2 official Westend Asset Hub WebSocket node used only for DOT token scan/sign/confirm.',
     now(), now(), NULL),
    ('DOT', 'westend', 'test2', 'dotters-westend-assethub-ws', 'asset_rpc', 'WS_RPC',
     'wss://asset-hub-westend.dotters.network', 'NONE', NULL, 20, 250, false,
     'test2 backup Westend Asset Hub WebSocket node used only for DOT token scan/sign/confirm.',
     now(), now(), NULL),
    ('DOT', 'westend', 'test2', 'local-polkadot-runtime-service', 'runtime', 'HTTP_JSON',
     'http://127.0.0.1:8787', 'bearer', 'Authorization', 20, 100, false,
     'test2 Polkadot runtime service backed by @polkadot/api. Fill api_key and enable only during e2e tests.',
     now(), now(), 'CHANGE_ME_POLKADOT_RUNTIME_API_KEY'),
    ('DOT', 'mainnet', 'prod', 'polkadot-mainnet-ws', 'rpc', 'WS_RPC',
     'wss://rpc.polkadot.io', 'NONE', NULL, 10, 500, false,
     'Production Polkadot WebSocket node placeholder; enable only after runtime service and monitoring are ready.',
     now(), now(), NULL),
    ('DOT', 'mainnet', 'prod', 'polkadot-assethub-ws', 'asset_rpc', 'WS_RPC',
     'wss://polkadot-asset-hub-rpc.polkadot.io', 'NONE', NULL, 10, 500, false,
     'Production official Polkadot Asset Hub WebSocket placeholder; enable only after token e2e tests and monitoring are ready.',
     now(), now(), NULL),
    ('DOT', 'mainnet', 'prod', 'dotters-polkadot-assethub-ws', 'asset_rpc', 'WS_RPC',
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

-- Safety guard: seeded token rows with placeholder or empty contract addresses
-- must stay disabled until the exact testnet/mainnet token contract is configured.
UPDATE "public"."token_config"
   SET "enabled" = false,
       "collect_enabled" = false,
       "updated_at" = now()
 WHERE "enabled" = true
   AND (
        coalesce("contract_address", "contract_address_base58", "contract_address_hex", '') = ''
     OR upper(coalesce("contract_address", "contract_address_base58", "contract_address_hex", '')) LIKE '%CHANGE_ME%'
     OR upper(coalesce("contract_address", "contract_address_base58", "contract_address_hex", '')) LIKE '%YOUR_%'
     OR upper(coalesce("contract_address", "contract_address_base58", "contract_address_hex", '')) LIKE '%REPLACE_ME%'
     OR upper(coalesce("contract_address", "contract_address_base58", "contract_address_hex", '')) LIKE '%TODO_%'
   );

-- Safety guard: normalized token assets with placeholder or empty contracts
-- must stay inactive until they are mapped to a real token configuration.
UPDATE "public"."chain_asset"
   SET "active" = false,
       "updated_at" = now()
 WHERE "active" = true
   AND "native_asset" = false
   AND (
        coalesce("contract_address", '') = ''
     OR upper(coalesce("contract_address", '')) LIKE '%CHANGE_ME%'
     OR upper(coalesce("contract_address", '')) LIKE '%YOUR_%'
     OR upper(coalesce("contract_address", '')) LIKE '%REPLACE_ME%'
     OR upper(coalesce("contract_address", '')) LIKE '%TODO_%'
   );


--
-- PostgreSQL database dump complete
--



SET search_path TO public;

-- Official issuer references (checked 2026-07-21):
-- Circle USDC: https://developers.circle.com/stablecoins/usdc-contract-addresses
-- Tether USDt: https://tether.to/en/supported-protocols/
-- USDT0 deployments: https://docs.usdt0.to/technical-documentation/deployments
-- Mainnet contracts are configuration-only and remain disabled until the matching chain,
-- audited RPC nodes and wallet tasks are deliberately enabled.
WITH mainnet_stablecoins(chain, symbol, standard, token_standard, contract_address,
                         contract_address_base58, contract_address_hex, decimals, gas_strategy) AS (
    VALUES
        ('APTOS', 'USDC', 'APTOS_FA', 'APTOS_FA',
         '0xbae207659db88bea0cbead6da0ed00aac12edcdda169e591cd41c94180b46f3b', NULL, NULL, 6, 'APT_GAS'),
        ('APTOS', 'USDT', 'APTOS_FA', 'APTOS_FA',
         '0x357b0b74bc833e95a115ad22604854d6b0fca151cecd94111770e5d6ffc9dc2b', NULL, NULL, 6, 'APT_GAS'),
        ('ARBITRUM', 'USDC', 'ERC20', 'ERC20',
         '0xaf88d065e77c8cC2239327C5EDb3A432268e5831', NULL, NULL, 6, 'native-gas'),
        ('ARBITRUM', 'USDT', 'ERC20', 'ERC20',
         '0xFd086bC7CD5C481DCC9C85ebE478A1C0b69FCbb9', NULL, NULL, 6, 'native-gas'),
        ('AVAX_C', 'USDC', 'ERC20', 'ERC20',
         '0xB97EF9Ef8734C71904D8002F8b6Bc66Dd9c48a6E', NULL, NULL, 6, 'native-gas'),
        ('AVAX_C', 'USDT', 'ERC20', 'ERC20',
         '0x9702230A8Ea53601f5cD2dc00fDBc13d4dF4A8c7', NULL, NULL, 6, 'native-gas'),
        ('BASE', 'USDC', 'ERC20', 'ERC20',
         '0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913', NULL, NULL, 6, 'native-gas'),
        ('DOT', 'USDC', 'ASSET_HUB_ASSET', 'ASSET_HUB_ASSET',
         '1337', NULL, NULL, 6, 'ASSET_HUB_NATIVE_GAS'),
        ('DOT', 'USDT', 'ASSET_HUB_ASSET', 'ASSET_HUB_ASSET',
         '1984', NULL, NULL, 6, 'ASSET_HUB_NATIVE_GAS'),
        ('ETH', 'USDC', 'ERC20', 'ERC20',
         '0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48', NULL, NULL, 6, 'native-gas'),
        ('ETH', 'USDT', 'ERC20', 'ERC20',
         '0xdAC17F958D2ee523a2206206994597C13D831ec7', NULL, NULL, 6, 'native-gas'),
        ('HYPEREVM', 'USDC', 'ERC20', 'ERC20',
         '0xb88339CB7199b77E23DB6E890353E22632Ba630f', NULL, NULL, 6, 'native-gas'),
        ('HYPEREVM', 'USDT', 'ERC20', 'ERC20',
         '0xB8CE59FC3717ada4C02eaDF9682A9e934F625ebb', NULL, NULL, 6, 'native-gas'),
        ('LINEA', 'USDC', 'ERC20', 'ERC20',
         '0x176211869cA2b568f2A7D4EE941E073a821EE1ff', NULL, NULL, 6, 'native-gas'),
        ('MANTLE', 'USDT', 'ERC20', 'ERC20',
         '0x779Ded0c9e1022225f8E0630b35a9b54bE713736', NULL, NULL, 6, 'native-gas'),
        ('NEAR', 'USDC', 'NEP141', 'NEP141',
         '17208628f84f5d6ad33f0da3bbbeb27ffcb398eac501a31bd6ad2011e36133a1', NULL, NULL, 6, 'NEAR_STORAGE_DEPOSIT'),
        ('NEAR', 'USDT', 'NEP141', 'NEP141',
         'usdt.tether-token.near', NULL, NULL, 6, 'NEAR_STORAGE_DEPOSIT'),
        ('OPTIMISM', 'USDC', 'ERC20', 'ERC20',
         '0x0b2C639c533813f4Aa9D7837CAf62653d097Ff85', NULL, NULL, 6, 'native-gas'),
        ('OPTIMISM', 'USDT', 'ERC20', 'ERC20',
         '0x01bFF41798a0BcF287b996046Ca68b395DbC1071', NULL, NULL, 6, 'native-gas'),
        ('POLYGON', 'USDC', 'ERC20', 'ERC20',
         '0x3c499c542cEF5E3811e1192ce70d8cC03d5c3359', NULL, NULL, 6, 'native-gas'),
        ('POLYGON', 'USDT', 'ERC20', 'ERC20',
         '0xc2132D05D31c914a87C6611C10748AEb04B58e8F', NULL, NULL, 6, 'native-gas'),
        ('SOLANA', 'USDC', 'SPL', 'SPL',
         'EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v',
         'EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v', NULL, 6, 'SOL_FEE_PAYER'),
        ('SOLANA', 'USDT', 'SPL', 'SPL',
         'Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB',
         'Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB', NULL, 6, 'SOL_FEE_PAYER'),
        ('SUI', 'USDC', 'SUI_COIN', 'COIN',
         '0xdba34672e30cb065b1f93e3ab55318768fd6fef66c15942c9f7cb846e2f900e7::usdc::USDC', NULL, NULL, 6, 'SUI_GAS_OBJECT'),
        ('TON', 'USDT', 'JETTON', 'JETTON',
         'EQCxE6mUtQJKFnGfaROTKOt1lZbDiiX1kCixRv7Nw2Id_sDs', NULL, NULL, 6, 'TON_FORWARD_FEE'),
        ('TRON', 'USDT', 'TRC20', 'TRC20',
         'TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t',
         'TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t',
         '41a614f803b6fd780986a42c78ec9c7f77e6ded13c', 6, 'energy-bandwidth'),
        ('UNICHAIN', 'USDC', 'ERC20', 'ERC20',
         '0x078D782b760474a361dDA0AF3839290b0EF57AD6', NULL, NULL, 6, 'native-gas'),
        ('UNICHAIN', 'USDT', 'ERC20', 'ERC20',
         '0x9151434b16b9763660705744891fA906F660EcC5', NULL, NULL, 6, 'native-gas'),
        ('XRP', 'USDC', 'XRPL_ISSUED', 'ISSUED_CURRENCY',
         'rGm7WCVp9gb4jZHWTEtGUr4dd74z2XuWhE:5553444300000000000000000000000000000000',
         NULL, '5553444300000000000000000000000000000000', 6, 'XRP_TRUSTLINE')
)
INSERT INTO token_config(
    chain, network, symbol, standard, token_standard, contract_address,
    contract_address_base58, contract_address_hex, decimals, enabled,
    min_deposit, min_withdraw, min_deposit_amount, min_withdraw_amount,
    collect_enabled, collect_threshold, gas_strategy, confirmation_required, updated_at)
SELECT stablecoin.chain, 'mainnet', stablecoin.symbol, stablecoin.standard,
       stablecoin.token_standard, stablecoin.contract_address,
       stablecoin.contract_address_base58, stablecoin.contract_address_hex,
       stablecoin.decimals, false, 1, 1, 1, 1, false, 1,
       stablecoin.gas_strategy, profile.deposit_confirmations, now()
  FROM mainnet_stablecoins stablecoin
  JOIN chain_profile profile
    ON profile.chain = stablecoin.chain AND profile.network = 'mainnet'
 WHERE NOT EXISTS (
       SELECT 1
         FROM token_config existing
        WHERE existing.chain = stablecoin.chain
          AND existing.network = 'mainnet'
          AND existing.symbol = stablecoin.symbol
 );

-- Mantle's canonical USDT moved to USDT0. Correct only the known disabled legacy
-- template; never rewrite an enabled production token contract during startup.
UPDATE token_config
   SET contract_address = '0x779Ded0c9e1022225f8E0630b35a9b54bE713736',
       updated_at = now()
 WHERE chain = 'MANTLE'
   AND network = 'mainnet'
   AND symbol = 'USDT'
   AND lower(contract_address) = lower('0x201EBa5CC46D216Ce6DC03F6a759e8E766e956aE')
   AND enabled = false;

UPDATE token_config
   SET collect_enabled = false,
       updated_at = now()
 WHERE network = 'mainnet'
   AND symbol IN ('USDC', 'USDT')
   AND enabled = false
   AND collect_enabled = true;

-- Reference prices make stablecoin aggregation usable from a clean development database.
-- Platform administrators can replace these snapshots through /custody/platform/v1/asset-prices.
INSERT INTO custody_asset_price(asset_symbol, usd_price, source, observed_at)
VALUES ('USDT', 1, 'STABLECOIN_REFERENCE', now()),
       ('USDC', 1, 'STABLECOIN_REFERENCE', now())
ON CONFLICT (asset_symbol) DO NOTHING;
