--
-- PostgreSQL database dump
--

\restrict u57OHvqSag6SqhfB2Qz4gmcyqHuwAbv1WRVW4E6Z0DXFIP6BetuzPHvn4D8Imsw

-- Dumped from database version 18.3 (Homebrew)
-- Dumped by pg_dump version 18.3 (Homebrew)

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

DROP INDEX IF EXISTS "public"."idx_utxo_record_spendable";
DROP INDEX IF EXISTS "public"."idx_utxo_record_address";
DROP INDEX IF EXISTS "public"."idx_chain_signing_transaction_tx_id";
DROP INDEX IF EXISTS "public"."idx_chain_signing_transaction_status";
DROP INDEX IF EXISTS "public"."idx_chain_address_scan";
DROP INDEX IF EXISTS "public"."idx_chain_address_owner";
DROP INDEX IF EXISTS "public"."idx_wallet_transfer_order_user";
DROP INDEX IF EXISTS "public"."idx_wallet_user_session_user";
ALTER TABLE IF EXISTS ONLY "public"."withdrawal_order" DROP CONSTRAINT IF EXISTS "withdrawal_order_pkey";
ALTER TABLE IF EXISTS ONLY "public"."wallet_system_config" DROP CONSTRAINT IF EXISTS "wallet_system_config_pkey";
ALTER TABLE IF EXISTS ONLY "public"."wallet_public_key" DROP CONSTRAINT IF EXISTS "wallet_public_key_pkey";
ALTER TABLE IF EXISTS ONLY "public"."utxo_record" DROP CONSTRAINT IF EXISTS "utxo_record_pkey";
ALTER TABLE IF EXISTS ONLY "public"."utxo_record" DROP CONSTRAINT IF EXISTS "utxo_record_chain_tx_hash_vout_key";
ALTER TABLE IF EXISTS ONLY "public"."withdrawal_order" DROP CONSTRAINT IF EXISTS "uq_withdrawal_order_chain_order_no";
ALTER TABLE IF EXISTS ONLY "public"."collection_record" DROP CONSTRAINT IF EXISTS "uq_collection_record_chain_collection_no";
ALTER TABLE IF EXISTS ONLY "public"."tron_tx" DROP CONSTRAINT IF EXISTS "tron_tx_pkey";
ALTER TABLE IF EXISTS ONLY "public"."tron_tx" DROP CONSTRAINT IF EXISTS "tron_tx_chain_tx_hash_key";
ALTER TABLE IF EXISTS ONLY "public"."tron_transaction" DROP CONSTRAINT IF EXISTS "tron_transaction_pkey";
ALTER TABLE IF EXISTS ONLY "public"."tron_transaction" DROP CONSTRAINT IF EXISTS "tron_transaction_chain_tx_hash_key";
ALTER TABLE IF EXISTS ONLY "public"."tron_token_transfer" DROP CONSTRAINT IF EXISTS "tron_token_transfer_pkey";
ALTER TABLE IF EXISTS ONLY "public"."tron_token_transfer" DROP CONSTRAINT IF EXISTS "tron_token_transfer_chain_tx_hash_log_index_key";
ALTER TABLE IF EXISTS ONLY "public"."ton_transaction" DROP CONSTRAINT IF EXISTS "ton_transaction_pkey";
ALTER TABLE IF EXISTS ONLY "public"."ton_transaction" DROP CONSTRAINT IF EXISTS "ton_transaction_chain_tx_hash_key";
ALTER TABLE IF EXISTS ONLY "public"."monero_transaction" DROP CONSTRAINT IF EXISTS "monero_transaction_pkey";
ALTER TABLE IF EXISTS ONLY "public"."monero_transaction" DROP CONSTRAINT IF EXISTS "monero_transaction_chain_tx_hash_direction_subaddress_key";
ALTER TABLE IF EXISTS ONLY "public"."token_config" DROP CONSTRAINT IF EXISTS "token_config_pkey";
ALTER TABLE IF EXISTS ONLY "public"."token_config" DROP CONSTRAINT IF EXISTS "token_config_chain_symbol_key";
ALTER TABLE IF EXISTS ONLY "public"."token_config" DROP CONSTRAINT IF EXISTS "token_config_chain_contract_address_key";
ALTER TABLE IF EXISTS ONLY "public"."xrp_transaction" DROP CONSTRAINT IF EXISTS "xrp_transaction_pkey";
ALTER TABLE IF EXISTS ONLY "public"."xrp_transaction" DROP CONSTRAINT IF EXISTS "xrp_transaction_chain_tx_hash_key";
ALTER TABLE IF EXISTS ONLY "public"."near_transaction" DROP CONSTRAINT IF EXISTS "near_transaction_pkey";
ALTER TABLE IF EXISTS ONLY "public"."near_transaction" DROP CONSTRAINT IF EXISTS "near_transaction_chain_tx_hash_action_index_key";
ALTER TABLE IF EXISTS ONLY "public"."near_transaction" DROP CONSTRAINT IF EXISTS "near_transaction_chain_tx_hash_key";
ALTER TABLE IF EXISTS ONLY "public"."sui_transaction" DROP CONSTRAINT IF EXISTS "sui_transaction_pkey";
ALTER TABLE IF EXISTS ONLY "public"."sui_transaction" DROP CONSTRAINT IF EXISTS "sui_transaction_chain_tx_digest_key";
ALTER TABLE IF EXISTS ONLY "public"."sol_transaction" DROP CONSTRAINT IF EXISTS "sol_transaction_pkey";
ALTER TABLE IF EXISTS ONLY "public"."sol_transaction" DROP CONSTRAINT IF EXISTS "sol_transaction_chain_signature_key";
ALTER TABLE IF EXISTS ONLY "public"."ledger_balance" DROP CONSTRAINT IF EXISTS "ledger_balance_pkey";
ALTER TABLE IF EXISTS ONLY "public"."ledger_balance" DROP CONSTRAINT IF EXISTS "ledger_balance_chain_asset_symbol_account_id_key";
ALTER TABLE IF EXISTS ONLY "public"."hot_wallet_address" DROP CONSTRAINT IF EXISTS "hot_wallet_address_pkey";
ALTER TABLE IF EXISTS ONLY "public"."hot_wallet_address" DROP CONSTRAINT IF EXISTS "hot_wallet_address_chain_asset_symbol_wallet_role_key";
ALTER TABLE IF EXISTS ONLY "public"."gas_topup_task" DROP CONSTRAINT IF EXISTS "gas_topup_task_task_no_key";
ALTER TABLE IF EXISTS ONLY "public"."gas_topup_task" DROP CONSTRAINT IF EXISTS "gas_topup_task_pkey";
ALTER TABLE IF EXISTS ONLY "public"."gas_topup_task" DROP CONSTRAINT IF EXISTS "gas_topup_task_chain_target_address_status_key";
ALTER TABLE IF EXISTS ONLY "public"."evm_tx" DROP CONSTRAINT IF EXISTS "evm_tx_pkey";
ALTER TABLE IF EXISTS ONLY "public"."evm_tx" DROP CONSTRAINT IF EXISTS "evm_tx_chain_tx_hash_key";
ALTER TABLE IF EXISTS ONLY "public"."evm_transaction" DROP CONSTRAINT IF EXISTS "evm_transaction_pkey";
ALTER TABLE IF EXISTS ONLY "public"."evm_transaction" DROP CONSTRAINT IF EXISTS "evm_transaction_chain_tx_hash_key";
ALTER TABLE IF EXISTS ONLY "public"."evm_token_transfer" DROP CONSTRAINT IF EXISTS "evm_token_transfer_pkey";
ALTER TABLE IF EXISTS ONLY "public"."evm_token_transfer" DROP CONSTRAINT IF EXISTS "evm_token_transfer_chain_tx_hash_log_index_key";
ALTER TABLE IF EXISTS ONLY "public"."evm_nonce" DROP CONSTRAINT IF EXISTS "evm_nonce_pkey";
ALTER TABLE IF EXISTS ONLY "public"."evm_nonce" DROP CONSTRAINT IF EXISTS "evm_nonce_chain_address_key";
ALTER TABLE IF EXISTS ONLY "public"."deposit_record" DROP CONSTRAINT IF EXISTS "deposit_record_pkey";
ALTER TABLE IF EXISTS ONLY "public"."deposit_record" DROP CONSTRAINT IF EXISTS "deposit_record_chain_tx_hash_log_index_key";
ALTER TABLE IF EXISTS ONLY "public"."collection_record" DROP CONSTRAINT IF EXISTS "collection_record_pkey";
ALTER TABLE IF EXISTS ONLY "public"."collection_record" DROP CONSTRAINT IF EXISTS "collection_record_chain_asset_symbol_from_address_to_addres_key";
ALTER TABLE IF EXISTS ONLY "public"."chain_signing_transaction" DROP CONSTRAINT IF EXISTS "chain_signing_transaction_pkey";
ALTER TABLE IF EXISTS ONLY "public"."chain_signing_transaction" DROP CONSTRAINT IF EXISTS "chain_signing_transaction_chain_business_type_business_no_key";
ALTER TABLE IF EXISTS ONLY "public"."chain_scan_height" DROP CONSTRAINT IF EXISTS "chain_scan_height_pkey";
ALTER TABLE IF EXISTS ONLY "public"."chain_scan_height" DROP CONSTRAINT IF EXISTS "chain_scan_height_chain_scanner_name_key";
ALTER TABLE IF EXISTS ONLY "public"."chain_rpc_node" DROP CONSTRAINT IF EXISTS "chain_rpc_node_unique_label";
ALTER TABLE IF EXISTS ONLY "public"."chain_rpc_node" DROP CONSTRAINT IF EXISTS "chain_rpc_node_pkey";
ALTER TABLE IF EXISTS ONLY "public"."chain_profile" DROP CONSTRAINT IF EXISTS "chain_profile_runtime_currency_id_network_key";
ALTER TABLE IF EXISTS ONLY "public"."chain_profile" DROP CONSTRAINT IF EXISTS "chain_profile_pkey";
ALTER TABLE IF EXISTS ONLY "public"."chain_profile" DROP CONSTRAINT IF EXISTS "chain_profile_chain_network_key";
ALTER TABLE IF EXISTS ONLY "public"."chain_asset" DROP CONSTRAINT IF EXISTS "chain_asset_pkey";
ALTER TABLE IF EXISTS ONLY "public"."chain_asset" DROP CONSTRAINT IF EXISTS "chain_asset_chain_symbol_key";
ALTER TABLE IF EXISTS ONLY "public"."chain_address" DROP CONSTRAINT IF EXISTS "chain_address_pkey";
ALTER TABLE IF EXISTS ONLY "public"."chain_address" DROP CONSTRAINT IF EXISTS "chain_address_reserved_hot_wallet_check";
ALTER TABLE IF EXISTS ONLY "public"."chain_address" DROP CONSTRAINT IF EXISTS "chain_address_chain_asset_symbol_user_id_biz_address_index__key";
ALTER TABLE IF EXISTS ONLY "public"."chain_address" DROP CONSTRAINT IF EXISTS "chain_address_chain_asset_symbol_address_key";
ALTER TABLE IF EXISTS ONLY "public"."aptos_transaction" DROP CONSTRAINT IF EXISTS "aptos_transaction_pkey";
ALTER TABLE IF EXISTS ONLY "public"."aptos_transaction" DROP CONSTRAINT IF EXISTS "aptos_transaction_chain_tx_hash_key";
ALTER TABLE IF EXISTS ONLY "public"."account_sequence" DROP CONSTRAINT IF EXISTS "account_sequence_pkey";
ALTER TABLE IF EXISTS ONLY "public"."account_sequence" DROP CONSTRAINT IF EXISTS "account_sequence_chain_address_key";
ALTER TABLE IF EXISTS "public"."withdrawal_order" ALTER COLUMN "id" DROP DEFAULT;
ALTER TABLE IF EXISTS "public"."utxo_record" ALTER COLUMN "id" DROP DEFAULT;
ALTER TABLE IF EXISTS "public"."tron_tx" ALTER COLUMN "id" DROP DEFAULT;
ALTER TABLE IF EXISTS "public"."tron_transaction" ALTER COLUMN "id" DROP DEFAULT;
ALTER TABLE IF EXISTS "public"."tron_token_transfer" ALTER COLUMN "id" DROP DEFAULT;
ALTER TABLE IF EXISTS "public"."ton_transaction" ALTER COLUMN "id" DROP DEFAULT;
ALTER TABLE IF EXISTS "public"."monero_transaction" ALTER COLUMN "id" DROP DEFAULT;
ALTER TABLE IF EXISTS "public"."token_config" ALTER COLUMN "id" DROP DEFAULT;
ALTER TABLE IF EXISTS "public"."xrp_transaction" ALTER COLUMN "id" DROP DEFAULT;
ALTER TABLE IF EXISTS "public"."near_transaction" ALTER COLUMN "id" DROP DEFAULT;
ALTER TABLE IF EXISTS "public"."sui_transaction" ALTER COLUMN "id" DROP DEFAULT;
ALTER TABLE IF EXISTS "public"."sol_transaction" ALTER COLUMN "id" DROP DEFAULT;
ALTER TABLE IF EXISTS "public"."ledger_balance" ALTER COLUMN "id" DROP DEFAULT;
ALTER TABLE IF EXISTS "public"."hot_wallet_address" ALTER COLUMN "id" DROP DEFAULT;
ALTER TABLE IF EXISTS "public"."gas_topup_task" ALTER COLUMN "id" DROP DEFAULT;
ALTER TABLE IF EXISTS "public"."evm_tx" ALTER COLUMN "id" DROP DEFAULT;
ALTER TABLE IF EXISTS "public"."evm_transaction" ALTER COLUMN "id" DROP DEFAULT;
ALTER TABLE IF EXISTS "public"."evm_token_transfer" ALTER COLUMN "id" DROP DEFAULT;
ALTER TABLE IF EXISTS "public"."evm_nonce" ALTER COLUMN "id" DROP DEFAULT;
ALTER TABLE IF EXISTS "public"."deposit_record" ALTER COLUMN "id" DROP DEFAULT;
ALTER TABLE IF EXISTS "public"."collection_record" ALTER COLUMN "id" DROP DEFAULT;
ALTER TABLE IF EXISTS "public"."chain_scan_height" ALTER COLUMN "id" DROP DEFAULT;
ALTER TABLE IF EXISTS "public"."chain_rpc_node" ALTER COLUMN "id" DROP DEFAULT;
ALTER TABLE IF EXISTS "public"."chain_profile" ALTER COLUMN "id" DROP DEFAULT;
ALTER TABLE IF EXISTS "public"."chain_asset" ALTER COLUMN "id" DROP DEFAULT;
ALTER TABLE IF EXISTS "public"."chain_address" ALTER COLUMN "id" DROP DEFAULT;
ALTER TABLE IF EXISTS "public"."aptos_transaction" ALTER COLUMN "id" DROP DEFAULT;
ALTER TABLE IF EXISTS "public"."account_sequence" ALTER COLUMN "id" DROP DEFAULT;
DROP SEQUENCE IF EXISTS "public"."withdrawal_order_id_seq";
DROP TABLE IF EXISTS "public"."withdrawal_order";
DROP TABLE IF EXISTS "public"."wallet_transfer_order";
DROP TABLE IF EXISTS "public"."wallet_user_session";
DROP TABLE IF EXISTS "public"."wallet_user";
DROP TABLE IF EXISTS "public"."wallet_system_config";
DROP TABLE IF EXISTS "public"."wallet_public_key";
DROP SEQUENCE IF EXISTS "public"."utxo_record_id_seq";
DROP TABLE IF EXISTS "public"."utxo_record";
DROP SEQUENCE IF EXISTS "public"."tron_tx_id_seq";
DROP TABLE IF EXISTS "public"."tron_tx";
DROP SEQUENCE IF EXISTS "public"."tron_transaction_id_seq";
DROP TABLE IF EXISTS "public"."tron_transaction";
DROP SEQUENCE IF EXISTS "public"."tron_token_transfer_id_seq";
DROP TABLE IF EXISTS "public"."tron_token_transfer";
DROP SEQUENCE IF EXISTS "public"."ton_transaction_id_seq";
DROP TABLE IF EXISTS "public"."ton_transaction";
DROP SEQUENCE IF EXISTS "public"."monero_transaction_id_seq";
DROP TABLE IF EXISTS "public"."monero_transaction";
DROP SEQUENCE IF EXISTS "public"."token_config_id_seq";
DROP TABLE IF EXISTS "public"."token_config";
DROP SEQUENCE IF EXISTS "public"."xrp_transaction_id_seq";
DROP TABLE IF EXISTS "public"."xrp_transaction";
DROP SEQUENCE IF EXISTS "public"."near_transaction_id_seq";
DROP TABLE IF EXISTS "public"."near_transaction";
DROP SEQUENCE IF EXISTS "public"."sui_transaction_id_seq";
DROP TABLE IF EXISTS "public"."sui_transaction";
DROP SEQUENCE IF EXISTS "public"."sol_transaction_id_seq";
DROP TABLE IF EXISTS "public"."sol_transaction";
DROP SEQUENCE IF EXISTS "public"."ledger_balance_id_seq";
DROP TABLE IF EXISTS "public"."ledger_balance";
DROP SEQUENCE IF EXISTS "public"."hot_wallet_address_id_seq";
DROP TABLE IF EXISTS "public"."hot_wallet_address";
DROP SEQUENCE IF EXISTS "public"."gas_topup_task_id_seq";
DROP TABLE IF EXISTS "public"."gas_topup_task";
DROP SEQUENCE IF EXISTS "public"."evm_tx_id_seq";
DROP TABLE IF EXISTS "public"."evm_tx";
DROP SEQUENCE IF EXISTS "public"."evm_transaction_id_seq";
DROP TABLE IF EXISTS "public"."evm_transaction";
DROP SEQUENCE IF EXISTS "public"."evm_token_transfer_id_seq";
DROP TABLE IF EXISTS "public"."evm_token_transfer";
DROP SEQUENCE IF EXISTS "public"."evm_nonce_id_seq";
DROP TABLE IF EXISTS "public"."evm_nonce";
DROP SEQUENCE IF EXISTS "public"."deposit_record_id_seq";
DROP TABLE IF EXISTS "public"."deposit_record";
DROP SEQUENCE IF EXISTS "public"."collection_record_id_seq";
DROP TABLE IF EXISTS "public"."collection_record";
DROP TABLE IF EXISTS "public"."chain_signing_transaction";
DROP SEQUENCE IF EXISTS "public"."chain_scan_height_id_seq";
DROP TABLE IF EXISTS "public"."chain_scan_height";
DROP SEQUENCE IF EXISTS "public"."chain_rpc_node_id_seq";
DROP TABLE IF EXISTS "public"."chain_rpc_node";
DROP SEQUENCE IF EXISTS "public"."chain_profile_id_seq";
DROP TABLE IF EXISTS "public"."chain_profile";
DROP SEQUENCE IF EXISTS "public"."chain_asset_id_seq";
DROP TABLE IF EXISTS "public"."chain_asset";
DROP SEQUENCE IF EXISTS "public"."chain_address_id_seq";
DROP TABLE IF EXISTS "public"."chain_address";
DROP SEQUENCE IF EXISTS "public"."aptos_transaction_id_seq";
DROP TABLE IF EXISTS "public"."aptos_transaction";
DROP SEQUENCE IF EXISTS "public"."account_sequence_id_seq";
DROP TABLE IF EXISTS "public"."account_sequence";
--
-- Name: SCHEMA "public"; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON SCHEMA "public" IS 'standard public schema';


SET default_tablespace = '';

SET default_table_access_method = "heap";

--
-- Name: account_sequence; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."account_sequence" (
    "id" bigint NOT NULL,
    "chain" character varying(32) NOT NULL,
    "address" character varying(160) NOT NULL,
    "chain_sequence" bigint DEFAULT 0 NOT NULL,
    "next_sequence" bigint DEFAULT 0 NOT NULL,
    "status" character varying(32) DEFAULT 'ACTIVE'::character varying NOT NULL,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL
);


--
-- Name: account_sequence_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE "public"."account_sequence_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: account_sequence_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE "public"."account_sequence_id_seq" OWNED BY "public"."account_sequence"."id";


--
-- Name: aptos_transaction; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."aptos_transaction" (
    "id" bigint NOT NULL,
    "chain" character varying(32) DEFAULT 'APTOS'::character varying NOT NULL,
    "tx_hash" character varying(128) NOT NULL,
    "sender" character varying(128),
    "receiver" character varying(128) NOT NULL,
    "asset_symbol" character varying(32) NOT NULL,
    "coin_type" character varying(256),
    "amount" numeric(78,24) DEFAULT 0 NOT NULL,
    "gas_used" bigint DEFAULT 0 NOT NULL,
    "gas_unit_price" bigint DEFAULT 0 NOT NULL,
    "version" bigint,
    "sequence_number" bigint,
    "confirmations" integer DEFAULT 0 NOT NULL,
    "status" character varying(32) DEFAULT 'CREATED'::character varying NOT NULL,
    "raw_payload" "text",
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL
);


--
-- Name: aptos_transaction_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE "public"."aptos_transaction_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: aptos_transaction_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE "public"."aptos_transaction_id_seq" OWNED BY "public"."aptos_transaction"."id";


--
-- Name: chain_address; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."chain_address" (
    "id" bigint NOT NULL,
    "chain" character varying(32) NOT NULL,
    "asset_symbol" character varying(32) NOT NULL,
    "account_id" character varying(160) NOT NULL,
    "user_id" bigint NOT NULL,
    "biz" integer DEFAULT 0 NOT NULL,
    "address_index" bigint NOT NULL,
    "address" character varying(160) NOT NULL,
    "owner_address" character varying(160),
    "derivation_path" character varying(96) NOT NULL,
    "wallet_role" character varying(32) DEFAULT 'DEPOSIT'::character varying NOT NULL,
    "enabled" boolean DEFAULT true NOT NULL,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL
);


--
-- Name: chain_address_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE "public"."chain_address_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: chain_address_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE "public"."chain_address_id_seq" OWNED BY "public"."chain_address"."id";


--
-- Name: chain_asset; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."chain_asset" (
    "id" bigint NOT NULL,
    "chain" character varying(32) NOT NULL,
    "symbol" character varying(32) NOT NULL,
    "asset_kind" character varying(32) NOT NULL,
    "contract_address" character varying(128),
    "decimals" integer DEFAULT 18 NOT NULL,
    "native_asset" boolean DEFAULT false NOT NULL,
    "active" boolean DEFAULT true NOT NULL,
    "min_transfer" numeric(78,24),
    "min_withdraw" numeric(78,24),
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL
);


--
-- Name: chain_asset_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE "public"."chain_asset_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: chain_asset_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE "public"."chain_asset_id_seq" OWNED BY "public"."chain_asset"."id";


--
-- Name: chain_profile; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."chain_profile" (
    "id" bigint NOT NULL,
    "chain" character varying(32) NOT NULL,
    "network" character varying(32) NOT NULL,
    "family" character varying(32) NOT NULL,
    "runtime_currency_id" integer NOT NULL,
    "bip44_coin_type" integer NOT NULL,
    "native_symbol" character varying(32) NOT NULL,
    "rpc_url" character varying(512),
    "explorer_url" character varying(512),
    "deposit_confirmations" integer DEFAULT 1 NOT NULL,
    "withdraw_confirmations" integer DEFAULT 1 NOT NULL,
    "default_fee_rate" bigint,
    "dust_threshold" bigint,
    "enabled" boolean DEFAULT true NOT NULL,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "chain_id" bigint,
    "gas_policy" character varying(64),
    "scan_batch_size" integer DEFAULT 100,
    "scan_enabled" boolean DEFAULT false NOT NULL,
    "withdraw_enabled" boolean DEFAULT false NOT NULL,
    "collection_enabled" boolean DEFAULT false NOT NULL,
    "transfer_enabled" boolean DEFAULT false NOT NULL,
    "scan_start_height" bigint DEFAULT 0 NOT NULL,
    "scan_max_blocks_per_run" bigint DEFAULT 0 NOT NULL
);


--
-- Name: chain_profile_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE "public"."chain_profile_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: chain_profile_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE "public"."chain_profile_id_seq" OWNED BY "public"."chain_profile"."id";


--
-- Name: chain_rpc_node; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."chain_rpc_node" (
    "id" bigint NOT NULL,
    "chain" character varying(32) NOT NULL,
    "network" character varying(32) NOT NULL,
    "environment" character varying(32) DEFAULT 'dev'::character varying NOT NULL,
    "node_label" character varying(64) NOT NULL,
    "purpose" character varying(32) DEFAULT 'rpc'::character varying NOT NULL,
    "connection_type" character varying(32) DEFAULT 'HTTP_JSON_RPC'::character varying NOT NULL,
    "rpc_url" "text" NOT NULL,
    "auth_type" character varying(32) DEFAULT 'NONE'::character varying NOT NULL,
    "auth_header_name" character varying(64),
    "api_key_ref" character varying(128),
    "username_ref" character varying(128),
    "password_ref" character varying(128),
    "priority" integer DEFAULT 100 NOT NULL,
    "min_request_interval_ms" integer DEFAULT 0 NOT NULL,
    "enabled" boolean DEFAULT true NOT NULL,
    "renewal_due_at" timestamp with time zone,
    "remark" "text",
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "api_key" character varying(1024),
    "username" character varying(256),
    "password" character varying(1024)
);


--
-- Name: chain_rpc_node_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE "public"."chain_rpc_node_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: chain_rpc_node_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE "public"."chain_rpc_node_id_seq" OWNED BY "public"."chain_rpc_node"."id";


--
-- Name: chain_scan_height; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."chain_scan_height" (
    "id" bigint NOT NULL,
    "chain" character varying(32) NOT NULL,
    "scanner_name" character varying(64) NOT NULL,
    "best_height" bigint DEFAULT 0 NOT NULL,
    "safe_height" bigint DEFAULT 0 NOT NULL,
    "status" character varying(32) DEFAULT 'ACTIVE'::character varying NOT NULL,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL
);


--
-- Name: chain_scan_height_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE "public"."chain_scan_height_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: chain_scan_height_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE "public"."chain_scan_height_id_seq" OWNED BY "public"."chain_scan_height"."id";


--
-- Name: chain_signing_transaction; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."chain_signing_transaction" (
    "id" integer NOT NULL,
    "chain" character varying(32) NOT NULL,
    "asset_symbol" character varying(32) NOT NULL,
    "business_type" character varying(32) NOT NULL,
    "business_no" character varying(512) NOT NULL,
    "tx_id" character varying(128) DEFAULT ''::character varying NOT NULL,
    "balance" numeric(78,24) DEFAULT 0 NOT NULL,
    "signature" "text",
    "currency" integer NOT NULL,
    "status" smallint NOT NULL,
    "error_message" "text",
    "create_date" timestamp with time zone DEFAULT "now"() NOT NULL,
    "update_date" timestamp with time zone DEFAULT "now"() NOT NULL
);


--
-- Name: chain_signing_transaction_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE "public"."chain_signing_transaction" ALTER COLUMN "id" ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME "public"."chain_signing_transaction_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: collection_record; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."collection_record" (
    "id" bigint NOT NULL,
    "collection_no" character varying(96) NOT NULL,
    "chain" character varying(32) NOT NULL,
    "asset_symbol" character varying(32) NOT NULL,
    "from_address" character varying(160) NOT NULL,
    "to_address" character varying(160) NOT NULL,
    "amount" numeric(78,24) DEFAULT 0 NOT NULL,
    "fee" numeric(78,24) DEFAULT 0 NOT NULL,
    "tx_hash" character varying(128),
    "status" character varying(32) DEFAULT 'CREATED'::character varying NOT NULL,
    "error_message" "text",
    "raw_payload" "text",
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL
);


--
-- Name: collection_record_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE "public"."collection_record_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: collection_record_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE "public"."collection_record_id_seq" OWNED BY "public"."collection_record"."id";


--
-- Name: deposit_record; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."deposit_record" (
    "id" bigint NOT NULL,
    "chain" character varying(32) NOT NULL,
    "asset_symbol" character varying(32) NOT NULL,
    "tx_hash" character varying(128) NOT NULL,
    "log_index" bigint DEFAULT 0 NOT NULL,
    "from_address" character varying(160),
    "to_address" character varying(160) NOT NULL,
    "contract_address" character varying(128),
    "amount" numeric(78,24) NOT NULL,
    "block_height" bigint NOT NULL,
    "confirmations" integer DEFAULT 0 NOT NULL,
    "status" character varying(32) DEFAULT 'DETECTED'::character varying NOT NULL,
    "credited" boolean DEFAULT false NOT NULL,
    "credited_at" timestamp with time zone,
    "raw_payload" "text",
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL
);


--
-- Name: deposit_record_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE "public"."deposit_record_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: deposit_record_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE "public"."deposit_record_id_seq" OWNED BY "public"."deposit_record"."id";


--
-- Name: evm_nonce; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."evm_nonce" (
    "id" bigint NOT NULL,
    "chain" character varying(32) NOT NULL,
    "address" character varying(128) NOT NULL,
    "chain_nonce" bigint DEFAULT 0 NOT NULL,
    "reserved_nonce" bigint DEFAULT 0 NOT NULL,
    "status" character varying(32) DEFAULT 'ACTIVE'::character varying NOT NULL,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL
);


--
-- Name: evm_nonce_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE "public"."evm_nonce_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: evm_nonce_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE "public"."evm_nonce_id_seq" OWNED BY "public"."evm_nonce"."id";


--
-- Name: evm_token_transfer; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."evm_token_transfer" (
    "id" bigint NOT NULL,
    "chain" character varying(32) NOT NULL,
    "tx_hash" character varying(128) NOT NULL,
    "log_index" bigint NOT NULL,
    "token_symbol" character varying(32) NOT NULL,
    "contract_address" character varying(128) NOT NULL,
    "from_address" character varying(128) NOT NULL,
    "to_address" character varying(128) NOT NULL,
    "amount" numeric(78,24) DEFAULT 0 NOT NULL,
    "block_height" bigint NOT NULL,
    "confirmations" integer DEFAULT 0 NOT NULL,
    "status" character varying(32) DEFAULT 'DETECTED'::character varying NOT NULL,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL
);


--
-- Name: evm_token_transfer_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE "public"."evm_token_transfer_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: evm_token_transfer_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE "public"."evm_token_transfer_id_seq" OWNED BY "public"."evm_token_transfer"."id";


--
-- Name: evm_transaction; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."evm_transaction" (
    "id" bigint NOT NULL,
    "chain" character varying(32) NOT NULL,
    "tx_hash" character varying(128) NOT NULL,
    "from_address" character varying(128) NOT NULL,
    "to_address" character varying(128) NOT NULL,
    "asset_symbol" character varying(32) NOT NULL,
    "contract_address" character varying(128),
    "amount" numeric(78,24) DEFAULT 0 NOT NULL,
    "fee" numeric(78,24) DEFAULT 0 NOT NULL,
    "nonce" bigint,
    "block_height" bigint,
    "confirmations" integer DEFAULT 0 NOT NULL,
    "status" character varying(32) DEFAULT 'CREATED'::character varying NOT NULL,
    "raw_payload" "text",
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL
);


--
-- Name: evm_transaction_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE "public"."evm_transaction_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: evm_transaction_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE "public"."evm_transaction_id_seq" OWNED BY "public"."evm_transaction"."id";


--
-- Name: evm_tx; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."evm_tx" (
    "id" bigint NOT NULL,
    "chain" character varying(32) NOT NULL,
    "tx_hash" character varying(128) NOT NULL,
    "from_address" character varying(128) NOT NULL,
    "to_address" character varying(128) NOT NULL,
    "asset_symbol" character varying(32) NOT NULL,
    "contract_address" character varying(128),
    "amount" numeric(78,24) DEFAULT 0 NOT NULL,
    "fee" numeric(78,24) DEFAULT 0 NOT NULL,
    "nonce" bigint,
    "block_height" bigint,
    "confirmations" integer DEFAULT 0 NOT NULL,
    "status" character varying(32) DEFAULT 'NEW'::character varying NOT NULL,
    "raw_payload" "text",
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL
);


--
-- Name: evm_tx_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE "public"."evm_tx_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: evm_tx_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE "public"."evm_tx_id_seq" OWNED BY "public"."evm_tx"."id";


--
-- Name: gas_topup_task; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."gas_topup_task" (
    "id" bigint NOT NULL,
    "task_no" character varying(96) NOT NULL,
    "chain" character varying(32) NOT NULL,
    "target_address" character varying(160) NOT NULL,
    "source_address" character varying(160),
    "amount" numeric(78,24) DEFAULT 0 NOT NULL,
    "tx_hash" character varying(128),
    "status" character varying(32) DEFAULT 'CREATED'::character varying NOT NULL,
    "reason" character varying(256),
    "retry_count" integer DEFAULT 0 NOT NULL,
    "raw_payload" "text",
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL
);


--
-- Name: gas_topup_task_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE "public"."gas_topup_task_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: gas_topup_task_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE "public"."gas_topup_task_id_seq" OWNED BY "public"."gas_topup_task"."id";


--
-- Name: hot_wallet_address; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."hot_wallet_address" (
    "id" bigint NOT NULL,
    "chain" character varying(32) NOT NULL,
    "asset_symbol" character varying(32) NOT NULL,
    "address" character varying(160) NOT NULL,
    "address_index" bigint DEFAULT 0 NOT NULL,
    "wallet_role" character varying(32) DEFAULT 'HOT_WITHDRAW'::character varying NOT NULL,
    "enabled" boolean DEFAULT true NOT NULL,
    "kms_key_ref" character varying(256),
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL
);


--
-- Name: hot_wallet_address_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE "public"."hot_wallet_address_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: hot_wallet_address_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE "public"."hot_wallet_address_id_seq" OWNED BY "public"."hot_wallet_address"."id";


--
-- Name: ledger_balance; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."ledger_balance" (
    "id" bigint NOT NULL,
    "chain" character varying(32) NOT NULL,
    "asset_symbol" character varying(32) NOT NULL,
    "account_id" character varying(128) NOT NULL,
    "available_balance" numeric(78,24) DEFAULT 0 NOT NULL,
    "locked_balance" numeric(78,24) DEFAULT 0 NOT NULL,
    "total_balance" numeric(78,24) DEFAULT 0 NOT NULL,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL
);


--
-- Name: ledger_balance_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE "public"."ledger_balance_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ledger_balance_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE "public"."ledger_balance_id_seq" OWNED BY "public"."ledger_balance"."id";


--
-- Name: sol_transaction; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."sol_transaction" (
    "id" bigint NOT NULL,
    "chain" character varying(32) DEFAULT 'SOLANA'::character varying NOT NULL,
    "signature" character varying(128) NOT NULL,
    "from_address" character varying(128),
    "to_address" character varying(128) NOT NULL,
    "asset_symbol" character varying(32) NOT NULL,
    "mint_address" character varying(128),
    "amount" numeric(78,24) DEFAULT 0 NOT NULL,
    "fee_lamports" bigint DEFAULT 0 NOT NULL,
    "slot" bigint,
    "confirmations" integer DEFAULT 0 NOT NULL,
    "status" character varying(32) DEFAULT 'CREATED'::character varying NOT NULL,
    "raw_payload" "text",
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL
);


--
-- Name: sol_transaction_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE "public"."sol_transaction_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: sol_transaction_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE "public"."sol_transaction_id_seq" OWNED BY "public"."sol_transaction"."id";


--
-- Name: sui_transaction; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."sui_transaction" (
    "id" bigint NOT NULL,
    "chain" character varying(32) DEFAULT 'SUI'::character varying NOT NULL,
    "tx_digest" character varying(128) NOT NULL,
    "sender" character varying(128),
    "receiver" character varying(128) NOT NULL,
    "asset_symbol" character varying(32) NOT NULL,
    "coin_type" character varying(256),
    "amount" numeric(78,24) DEFAULT 0 NOT NULL,
    "gas_used" bigint DEFAULT 0 NOT NULL,
    "checkpoint" bigint,
    "status" character varying(32) DEFAULT 'CREATED'::character varying NOT NULL,
    "raw_payload" "text",
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL
);


--
-- Name: sui_transaction_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE "public"."sui_transaction_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: sui_transaction_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE "public"."sui_transaction_id_seq" OWNED BY "public"."sui_transaction"."id";


--
-- Name: near_transaction; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."near_transaction" (
    "id" bigint NOT NULL,
    "chain" character varying(32) DEFAULT 'NEAR'::character varying NOT NULL,
    "tx_hash" character varying(128) NOT NULL,
    "action_index" bigint DEFAULT 0 NOT NULL,
    "sender" character varying(128),
    "receiver" character varying(128) NOT NULL,
    "asset_symbol" character varying(32) DEFAULT 'NEAR'::character varying NOT NULL,
    "amount" numeric(78,24) DEFAULT 0 NOT NULL,
    "gas_burnt" bigint DEFAULT 0 NOT NULL,
    "block_height" bigint,
    "status" character varying(32) DEFAULT 'CREATED'::character varying NOT NULL,
    "raw_payload" "text",
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL
);


--
-- Name: near_transaction_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE "public"."near_transaction_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: near_transaction_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE "public"."near_transaction_id_seq" OWNED BY "public"."near_transaction"."id";


--
-- Name: xrp_transaction; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."xrp_transaction" (
    "id" bigint NOT NULL,
    "chain" character varying(32) DEFAULT 'XRP'::character varying NOT NULL,
    "tx_hash" character varying(128) NOT NULL,
    "from_address" character varying(160),
    "to_address" character varying(160) NOT NULL,
    "asset_symbol" character varying(32) NOT NULL,
    "issuer_address" character varying(160),
    "currency_code" character varying(64),
    "amount" numeric(78,24) DEFAULT 0 NOT NULL,
    "fee_drops" bigint DEFAULT 0 NOT NULL,
    "ledger_index" bigint,
    "sequence_number" bigint,
    "confirmations" integer DEFAULT 0 NOT NULL,
    "status" character varying(32) DEFAULT 'CREATED'::character varying NOT NULL,
    "raw_payload" "text",
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL
);


--
-- Name: xrp_transaction_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE "public"."xrp_transaction_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: xrp_transaction_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE "public"."xrp_transaction_id_seq" OWNED BY "public"."xrp_transaction"."id";


--
-- Name: monero_transaction; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."monero_transaction" (
    "id" bigint NOT NULL,
    "chain" character varying(32) DEFAULT 'XMR'::character varying NOT NULL,
    "tx_hash" character varying(128) NOT NULL,
    "direction" character varying(16) NOT NULL,
    "account_index" integer DEFAULT 0 NOT NULL,
    "subaddress_index" integer DEFAULT 0 NOT NULL,
    "address" character varying(128) NOT NULL,
    "asset_symbol" character varying(32) DEFAULT 'XMR'::character varying NOT NULL,
    "amount" numeric(78,24) DEFAULT 0 NOT NULL,
    "fee_atomic" bigint DEFAULT 0 NOT NULL,
    "block_height" bigint,
    "confirmations" integer DEFAULT 0 NOT NULL,
    "status" character varying(32) DEFAULT 'CREATED'::character varying NOT NULL,
    "raw_payload" "text",
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL
);


--
-- Name: monero_transaction_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE "public"."monero_transaction_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: monero_transaction_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE "public"."monero_transaction_id_seq" OWNED BY "public"."monero_transaction"."id";


--
-- Name: token_config; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."token_config" (
    "id" bigint NOT NULL,
    "chain" character varying(32) NOT NULL,
    "symbol" character varying(32) NOT NULL,
    "standard" character varying(32) NOT NULL,
    "contract_address" character varying(128) NOT NULL,
    "decimals" integer NOT NULL,
    "enabled" boolean DEFAULT true NOT NULL,
    "min_deposit" numeric(78,24),
    "min_withdraw" numeric(78,24),
    "collect_enabled" boolean DEFAULT true NOT NULL,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "network" character varying(32),
    "token_standard" character varying(32),
    "contract_address_base58" character varying(128),
    "contract_address_hex" character varying(128),
    "min_deposit_amount" numeric(78,24),
    "min_withdraw_amount" numeric(78,24),
    "collect_threshold" numeric(78,24),
    "gas_strategy" character varying(64),
    "confirmation_required" integer
);


--
-- Name: token_config_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE "public"."token_config_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: token_config_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE "public"."token_config_id_seq" OWNED BY "public"."token_config"."id";


--
-- Name: ton_transaction; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."ton_transaction" (
    "id" bigint NOT NULL,
    "chain" character varying(32) DEFAULT 'TON'::character varying NOT NULL,
    "tx_hash" character varying(128) NOT NULL,
    "from_address" character varying(160),
    "to_address" character varying(160) NOT NULL,
    "asset_symbol" character varying(32) NOT NULL,
    "jetton_master" character varying(160),
    "amount" numeric(78,24) DEFAULT 0 NOT NULL,
    "fee_nano" bigint DEFAULT 0 NOT NULL,
    "logical_time" numeric(78,0),
    "confirmations" integer DEFAULT 0 NOT NULL,
    "status" character varying(32) DEFAULT 'CREATED'::character varying NOT NULL,
    "raw_payload" "text",
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL
);


--
-- Name: ton_transaction_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE "public"."ton_transaction_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ton_transaction_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE "public"."ton_transaction_id_seq" OWNED BY "public"."ton_transaction"."id";


--
-- Name: tron_token_transfer; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."tron_token_transfer" (
    "id" bigint NOT NULL,
    "chain" character varying(32) DEFAULT 'TRON'::character varying NOT NULL,
    "tx_hash" character varying(128) NOT NULL,
    "log_index" bigint DEFAULT 0 NOT NULL,
    "token_symbol" character varying(32) NOT NULL,
    "contract_address" character varying(128) NOT NULL,
    "from_address" character varying(128) NOT NULL,
    "to_address" character varying(128) NOT NULL,
    "amount" numeric(78,24) DEFAULT 0 NOT NULL,
    "block_height" bigint NOT NULL,
    "confirmations" integer DEFAULT 0 NOT NULL,
    "status" character varying(32) DEFAULT 'DETECTED'::character varying NOT NULL,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL
);


--
-- Name: tron_token_transfer_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE "public"."tron_token_transfer_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: tron_token_transfer_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE "public"."tron_token_transfer_id_seq" OWNED BY "public"."tron_token_transfer"."id";


--
-- Name: tron_transaction; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."tron_transaction" (
    "id" bigint NOT NULL,
    "chain" character varying(32) DEFAULT 'TRON'::character varying NOT NULL,
    "tx_hash" character varying(128) NOT NULL,
    "from_address" character varying(128) NOT NULL,
    "to_address" character varying(128) NOT NULL,
    "asset_symbol" character varying(32) NOT NULL,
    "contract_address" character varying(128),
    "amount" numeric(78,24) DEFAULT 0 NOT NULL,
    "fee" numeric(78,24) DEFAULT 0 NOT NULL,
    "block_height" bigint,
    "confirmations" integer DEFAULT 0 NOT NULL,
    "status" character varying(32) DEFAULT 'CREATED'::character varying NOT NULL,
    "raw_payload" "text",
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL
);


--
-- Name: tron_transaction_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE "public"."tron_transaction_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: tron_transaction_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE "public"."tron_transaction_id_seq" OWNED BY "public"."tron_transaction"."id";


--
-- Name: tron_tx; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."tron_tx" (
    "id" bigint NOT NULL,
    "chain" character varying(32) NOT NULL,
    "tx_hash" character varying(128) NOT NULL,
    "from_address" character varying(128) NOT NULL,
    "to_address" character varying(128) NOT NULL,
    "asset_symbol" character varying(32) NOT NULL,
    "contract_address" character varying(128),
    "amount" numeric(78,24) DEFAULT 0 NOT NULL,
    "fee" numeric(78,24) DEFAULT 0 NOT NULL,
    "block_height" bigint,
    "confirmations" integer DEFAULT 0 NOT NULL,
    "status" character varying(32) DEFAULT 'NEW'::character varying NOT NULL,
    "raw_payload" "text",
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL
);


--
-- Name: tron_tx_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE "public"."tron_tx_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: tron_tx_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE "public"."tron_tx_id_seq" OWNED BY "public"."tron_tx"."id";


--
-- Name: utxo_record; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."utxo_record" (
    "id" bigint NOT NULL,
    "chain" character varying(32) NOT NULL,
    "asset_symbol" character varying(32) NOT NULL,
    "tx_hash" character varying(128) NOT NULL,
    "vout" integer NOT NULL,
    "address" character varying(160) NOT NULL,
    "amount" numeric(78,24) NOT NULL,
    "block_height" bigint NOT NULL,
    "confirmations" integer DEFAULT 0 NOT NULL,
    "state" character varying(32) DEFAULT 'AVAILABLE'::character varying NOT NULL,
    "lock_ref" character varying(128),
    "spent_tx_hash" character varying(128),
    "credited" boolean DEFAULT false NOT NULL,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL
);


--
-- Name: utxo_record_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE "public"."utxo_record_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: utxo_record_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE "public"."utxo_record_id_seq" OWNED BY "public"."utxo_record"."id";


--
-- Name: wallet_public_key; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."wallet_public_key" (
    "key_slot" integer NOT NULL,
    "key_role" character varying(64) NOT NULL,
    "key_type" character varying(32) DEFAULT 'BIP32_XPUB'::character varying NOT NULL,
    "network" character varying(32) DEFAULT 'test'::character varying NOT NULL,
    "public_key" "text" NOT NULL,
    "enabled" boolean DEFAULT true NOT NULL,
    "remark" "text",
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL
);


--
-- Name: wallet_system_config; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."wallet_system_config" (
    "config_key" character varying(128) NOT NULL,
    "config_value" "text" NOT NULL,
    "value_type" character varying(32) DEFAULT 'boolean'::character varying NOT NULL,
    "enabled" boolean DEFAULT true NOT NULL,
    "remark" "text",
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL
);


--
-- Name: wallet_user; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."wallet_user" (
    "id" bigint GENERATED BY DEFAULT AS IDENTITY NOT NULL,
    "email" character varying(160) NOT NULL,
    "password_hash" "text" NOT NULL,
    "display_name" character varying(64) NOT NULL,
    "status" character varying(32) DEFAULT 'ACTIVE'::character varying NOT NULL,
    "failed_login_count" integer DEFAULT 0 NOT NULL,
    "locked_until" timestamp with time zone,
    "last_login_at" timestamp with time zone,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    CONSTRAINT "wallet_user_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "wallet_user_email_key" UNIQUE ("email")
);


--
-- Name: wallet_user_session; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."wallet_user_session" (
    "id" bigint GENERATED BY DEFAULT AS IDENTITY NOT NULL,
    "user_id" bigint NOT NULL,
    "token_hash" character varying(64) NOT NULL,
    "expires_at" timestamp with time zone NOT NULL,
    "revoked_at" timestamp with time zone,
    "ip_address" character varying(64),
    "user_agent" character varying(300),
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "last_seen_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    CONSTRAINT "wallet_user_session_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "wallet_user_session_token_hash_key" UNIQUE ("token_hash")
);


--
-- Name: wallet_transfer_order; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."wallet_transfer_order" (
    "id" bigint GENERATED BY DEFAULT AS IDENTITY NOT NULL,
    "transfer_no" character varying(96) NOT NULL,
    "from_user_id" bigint NOT NULL,
    "to_user_id" bigint NOT NULL,
    "chain" character varying(32) NOT NULL,
    "asset_symbol" character varying(32) NOT NULL,
    "amount" numeric(78,24) NOT NULL,
    "from_account_id" character varying(160) NOT NULL,
    "to_account_id" character varying(160) NOT NULL,
    "status" character varying(32) DEFAULT 'CREATED'::character varying NOT NULL,
    "error_message" "text",
    "completed_at" timestamp with time zone,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    CONSTRAINT "wallet_transfer_order_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "wallet_transfer_order_transfer_no_key" UNIQUE ("transfer_no")
);


--
-- Name: withdrawal_order; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."withdrawal_order" (
    "id" bigint NOT NULL,
    "order_no" character varying(96) NOT NULL,
    "user_id" bigint NOT NULL,
    "chain" character varying(32) NOT NULL,
    "asset_symbol" character varying(32) NOT NULL,
    "from_address" character varying(160),
    "debit_account_id" character varying(160),
    "to_address" character varying(160) NOT NULL,
    "amount" numeric(78,24) NOT NULL,
    "fee" numeric(78,24) DEFAULT 0 NOT NULL,
    "tx_hash" character varying(128),
    "status" character varying(32) DEFAULT 'CREATED'::character varying NOT NULL,
    "error_message" "text",
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL
);


--
-- Name: withdrawal_order_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE "public"."withdrawal_order_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: withdrawal_order_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE "public"."withdrawal_order_id_seq" OWNED BY "public"."withdrawal_order"."id";


--
-- Name: account_sequence id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."account_sequence" ALTER COLUMN "id" SET DEFAULT "nextval"('"public"."account_sequence_id_seq"'::"regclass");


--
-- Name: aptos_transaction id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."aptos_transaction" ALTER COLUMN "id" SET DEFAULT "nextval"('"public"."aptos_transaction_id_seq"'::"regclass");


--
-- Name: chain_address id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."chain_address" ALTER COLUMN "id" SET DEFAULT "nextval"('"public"."chain_address_id_seq"'::"regclass");


--
-- Name: chain_asset id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."chain_asset" ALTER COLUMN "id" SET DEFAULT "nextval"('"public"."chain_asset_id_seq"'::"regclass");


--
-- Name: chain_profile id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."chain_profile" ALTER COLUMN "id" SET DEFAULT "nextval"('"public"."chain_profile_id_seq"'::"regclass");


--
-- Name: chain_rpc_node id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."chain_rpc_node" ALTER COLUMN "id" SET DEFAULT "nextval"('"public"."chain_rpc_node_id_seq"'::"regclass");


--
-- Name: chain_scan_height id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."chain_scan_height" ALTER COLUMN "id" SET DEFAULT "nextval"('"public"."chain_scan_height_id_seq"'::"regclass");


--
-- Name: collection_record id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."collection_record" ALTER COLUMN "id" SET DEFAULT "nextval"('"public"."collection_record_id_seq"'::"regclass");


--
-- Name: deposit_record id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."deposit_record" ALTER COLUMN "id" SET DEFAULT "nextval"('"public"."deposit_record_id_seq"'::"regclass");


--
-- Name: evm_nonce id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."evm_nonce" ALTER COLUMN "id" SET DEFAULT "nextval"('"public"."evm_nonce_id_seq"'::"regclass");


--
-- Name: evm_token_transfer id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."evm_token_transfer" ALTER COLUMN "id" SET DEFAULT "nextval"('"public"."evm_token_transfer_id_seq"'::"regclass");


--
-- Name: evm_transaction id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."evm_transaction" ALTER COLUMN "id" SET DEFAULT "nextval"('"public"."evm_transaction_id_seq"'::"regclass");


--
-- Name: evm_tx id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."evm_tx" ALTER COLUMN "id" SET DEFAULT "nextval"('"public"."evm_tx_id_seq"'::"regclass");


--
-- Name: gas_topup_task id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."gas_topup_task" ALTER COLUMN "id" SET DEFAULT "nextval"('"public"."gas_topup_task_id_seq"'::"regclass");


--
-- Name: hot_wallet_address id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."hot_wallet_address" ALTER COLUMN "id" SET DEFAULT "nextval"('"public"."hot_wallet_address_id_seq"'::"regclass");


--
-- Name: ledger_balance id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."ledger_balance" ALTER COLUMN "id" SET DEFAULT "nextval"('"public"."ledger_balance_id_seq"'::"regclass");


--
-- Name: sol_transaction id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."sol_transaction" ALTER COLUMN "id" SET DEFAULT "nextval"('"public"."sol_transaction_id_seq"'::"regclass");


--
-- Name: sui_transaction id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."sui_transaction" ALTER COLUMN "id" SET DEFAULT "nextval"('"public"."sui_transaction_id_seq"'::"regclass");


--
-- Name: near_transaction id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."near_transaction" ALTER COLUMN "id" SET DEFAULT "nextval"('"public"."near_transaction_id_seq"'::"regclass");


--
-- Name: xrp_transaction id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."xrp_transaction" ALTER COLUMN "id" SET DEFAULT "nextval"('"public"."xrp_transaction_id_seq"'::"regclass");


--
-- Name: monero_transaction id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."monero_transaction" ALTER COLUMN "id" SET DEFAULT "nextval"('"public"."monero_transaction_id_seq"'::"regclass");


--
-- Name: token_config id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."token_config" ALTER COLUMN "id" SET DEFAULT "nextval"('"public"."token_config_id_seq"'::"regclass");


--
-- Name: ton_transaction id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."ton_transaction" ALTER COLUMN "id" SET DEFAULT "nextval"('"public"."ton_transaction_id_seq"'::"regclass");


--
-- Name: tron_token_transfer id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."tron_token_transfer" ALTER COLUMN "id" SET DEFAULT "nextval"('"public"."tron_token_transfer_id_seq"'::"regclass");


--
-- Name: tron_transaction id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."tron_transaction" ALTER COLUMN "id" SET DEFAULT "nextval"('"public"."tron_transaction_id_seq"'::"regclass");


--
-- Name: tron_tx id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."tron_tx" ALTER COLUMN "id" SET DEFAULT "nextval"('"public"."tron_tx_id_seq"'::"regclass");


--
-- Name: utxo_record id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."utxo_record" ALTER COLUMN "id" SET DEFAULT "nextval"('"public"."utxo_record_id_seq"'::"regclass");


--
-- Name: withdrawal_order id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."withdrawal_order" ALTER COLUMN "id" SET DEFAULT "nextval"('"public"."withdrawal_order_id_seq"'::"regclass");


--
-- Name: account_sequence account_sequence_chain_address_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."account_sequence"
    ADD CONSTRAINT "account_sequence_chain_address_key" UNIQUE ("chain", "address");


--
-- Name: account_sequence account_sequence_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."account_sequence"
    ADD CONSTRAINT "account_sequence_pkey" PRIMARY KEY ("id");


--
-- Name: aptos_transaction aptos_transaction_chain_tx_hash_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."aptos_transaction"
    ADD CONSTRAINT "aptos_transaction_chain_tx_hash_key" UNIQUE ("chain", "tx_hash");


--
-- Name: aptos_transaction aptos_transaction_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."aptos_transaction"
    ADD CONSTRAINT "aptos_transaction_pkey" PRIMARY KEY ("id");


--
-- Name: chain_address chain_address_chain_asset_symbol_address_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."chain_address"
    ADD CONSTRAINT "chain_address_chain_asset_symbol_address_key" UNIQUE ("chain", "asset_symbol", "address");


--
-- Name: chain_address chain_address_chain_asset_symbol_user_id_biz_address_index__key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."chain_address"
    ADD CONSTRAINT "chain_address_chain_asset_symbol_user_id_biz_address_index__key" UNIQUE ("chain", "asset_symbol", "user_id", "biz", "address_index", "wallet_role");


--
-- Name: chain_address chain_address_reserved_hot_wallet_check; Type: CHECK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."chain_address"
    ADD CONSTRAINT "chain_address_reserved_hot_wallet_check" CHECK ((("user_id" <> 0) OR ("biz" <> 0) OR (("address_index" = 0) AND (("wallet_role")::text = 'DEPOSIT'::text))));


--
-- Name: chain_address chain_address_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."chain_address"
    ADD CONSTRAINT "chain_address_pkey" PRIMARY KEY ("id");


--
-- Name: chain_asset chain_asset_chain_symbol_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."chain_asset"
    ADD CONSTRAINT "chain_asset_chain_symbol_key" UNIQUE ("chain", "symbol");


--
-- Name: chain_asset chain_asset_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."chain_asset"
    ADD CONSTRAINT "chain_asset_pkey" PRIMARY KEY ("id");


--
-- Name: chain_profile chain_profile_chain_network_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."chain_profile"
    ADD CONSTRAINT "chain_profile_chain_network_key" UNIQUE ("chain", "network");


--
-- Name: chain_profile chain_profile_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."chain_profile"
    ADD CONSTRAINT "chain_profile_pkey" PRIMARY KEY ("id");


--
-- Name: chain_profile chain_profile_runtime_currency_id_network_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."chain_profile"
    ADD CONSTRAINT "chain_profile_runtime_currency_id_network_key" UNIQUE ("runtime_currency_id", "network");


--
-- Name: chain_rpc_node chain_rpc_node_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."chain_rpc_node"
    ADD CONSTRAINT "chain_rpc_node_pkey" PRIMARY KEY ("id");


--
-- Name: chain_rpc_node chain_rpc_node_unique_label; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."chain_rpc_node"
    ADD CONSTRAINT "chain_rpc_node_unique_label" UNIQUE ("chain", "network", "environment", "purpose", "node_label");


--
-- Name: chain_scan_height chain_scan_height_chain_scanner_name_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."chain_scan_height"
    ADD CONSTRAINT "chain_scan_height_chain_scanner_name_key" UNIQUE ("chain", "scanner_name");


--
-- Name: chain_scan_height chain_scan_height_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."chain_scan_height"
    ADD CONSTRAINT "chain_scan_height_pkey" PRIMARY KEY ("id");


--
-- Name: chain_signing_transaction chain_signing_transaction_chain_business_type_business_no_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."chain_signing_transaction"
    ADD CONSTRAINT "chain_signing_transaction_chain_business_type_business_no_key" UNIQUE ("chain", "business_type", "business_no");


--
-- Name: chain_signing_transaction chain_signing_transaction_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."chain_signing_transaction"
    ADD CONSTRAINT "chain_signing_transaction_pkey" PRIMARY KEY ("id");


--
-- Name: collection_record collection_record_chain_asset_symbol_from_address_to_addres_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."collection_record"
    ADD CONSTRAINT "collection_record_chain_asset_symbol_from_address_to_addres_key" UNIQUE ("chain", "asset_symbol", "from_address", "to_address", "tx_hash");


--
-- Name: collection_record collection_record_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."collection_record"
    ADD CONSTRAINT "collection_record_pkey" PRIMARY KEY ("id");


--
-- Name: deposit_record deposit_record_chain_tx_hash_log_index_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."deposit_record"
    ADD CONSTRAINT "deposit_record_chain_tx_hash_log_index_key" UNIQUE ("chain", "tx_hash", "log_index");


--
-- Name: deposit_record deposit_record_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."deposit_record"
    ADD CONSTRAINT "deposit_record_pkey" PRIMARY KEY ("id");


--
-- Name: evm_nonce evm_nonce_chain_address_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."evm_nonce"
    ADD CONSTRAINT "evm_nonce_chain_address_key" UNIQUE ("chain", "address");


--
-- Name: evm_nonce evm_nonce_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."evm_nonce"
    ADD CONSTRAINT "evm_nonce_pkey" PRIMARY KEY ("id");


--
-- Name: evm_token_transfer evm_token_transfer_chain_tx_hash_log_index_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."evm_token_transfer"
    ADD CONSTRAINT "evm_token_transfer_chain_tx_hash_log_index_key" UNIQUE ("chain", "tx_hash", "log_index");


--
-- Name: evm_token_transfer evm_token_transfer_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."evm_token_transfer"
    ADD CONSTRAINT "evm_token_transfer_pkey" PRIMARY KEY ("id");


--
-- Name: evm_transaction evm_transaction_chain_tx_hash_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."evm_transaction"
    ADD CONSTRAINT "evm_transaction_chain_tx_hash_key" UNIQUE ("chain", "tx_hash");


--
-- Name: evm_transaction evm_transaction_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."evm_transaction"
    ADD CONSTRAINT "evm_transaction_pkey" PRIMARY KEY ("id");


--
-- Name: evm_tx evm_tx_chain_tx_hash_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."evm_tx"
    ADD CONSTRAINT "evm_tx_chain_tx_hash_key" UNIQUE ("chain", "tx_hash");


--
-- Name: evm_tx evm_tx_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."evm_tx"
    ADD CONSTRAINT "evm_tx_pkey" PRIMARY KEY ("id");


--
-- Name: gas_topup_task gas_topup_task_chain_target_address_status_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."gas_topup_task"
    ADD CONSTRAINT "gas_topup_task_chain_target_address_status_key" UNIQUE ("chain", "target_address", "status");


--
-- Name: gas_topup_task gas_topup_task_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."gas_topup_task"
    ADD CONSTRAINT "gas_topup_task_pkey" PRIMARY KEY ("id");


--
-- Name: gas_topup_task gas_topup_task_task_no_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."gas_topup_task"
    ADD CONSTRAINT "gas_topup_task_task_no_key" UNIQUE ("task_no");


--
-- Name: hot_wallet_address hot_wallet_address_chain_asset_symbol_wallet_role_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."hot_wallet_address"
    ADD CONSTRAINT "hot_wallet_address_chain_asset_symbol_wallet_role_key" UNIQUE ("chain", "asset_symbol", "wallet_role");


--
-- Name: hot_wallet_address hot_wallet_address_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."hot_wallet_address"
    ADD CONSTRAINT "hot_wallet_address_pkey" PRIMARY KEY ("id");


--
-- Name: ledger_balance ledger_balance_chain_asset_symbol_account_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."ledger_balance"
    ADD CONSTRAINT "ledger_balance_chain_asset_symbol_account_id_key" UNIQUE ("chain", "asset_symbol", "account_id");


--
-- Name: ledger_balance ledger_balance_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."ledger_balance"
    ADD CONSTRAINT "ledger_balance_pkey" PRIMARY KEY ("id");


--
-- Name: sol_transaction sol_transaction_chain_signature_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."sol_transaction"
    ADD CONSTRAINT "sol_transaction_chain_signature_key" UNIQUE ("chain", "signature");


--
-- Name: sol_transaction sol_transaction_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."sol_transaction"
    ADD CONSTRAINT "sol_transaction_pkey" PRIMARY KEY ("id");


--
-- Name: sui_transaction sui_transaction_chain_tx_digest_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."sui_transaction"
    ADD CONSTRAINT "sui_transaction_chain_tx_digest_key" UNIQUE ("chain", "tx_digest");


--
-- Name: sui_transaction sui_transaction_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."sui_transaction"
    ADD CONSTRAINT "sui_transaction_pkey" PRIMARY KEY ("id");


--
-- Name: near_transaction near_transaction_chain_tx_hash_action_index_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."near_transaction"
    ADD CONSTRAINT "near_transaction_chain_tx_hash_action_index_key" UNIQUE ("chain", "tx_hash", "action_index");


--
-- Name: near_transaction near_transaction_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."near_transaction"
    ADD CONSTRAINT "near_transaction_pkey" PRIMARY KEY ("id");


--
-- Name: xrp_transaction xrp_transaction_chain_tx_hash_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."xrp_transaction"
    ADD CONSTRAINT "xrp_transaction_chain_tx_hash_key" UNIQUE ("chain", "tx_hash");


--
-- Name: xrp_transaction xrp_transaction_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."xrp_transaction"
    ADD CONSTRAINT "xrp_transaction_pkey" PRIMARY KEY ("id");


--
-- Name: monero_transaction monero_transaction_chain_tx_hash_direction_subaddress_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."monero_transaction"
    ADD CONSTRAINT "monero_transaction_chain_tx_hash_direction_subaddress_key" UNIQUE ("chain", "tx_hash", "direction", "subaddress_index");


--
-- Name: monero_transaction monero_transaction_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."monero_transaction"
    ADD CONSTRAINT "monero_transaction_pkey" PRIMARY KEY ("id");


--
-- Name: token_config token_config_chain_contract_address_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."token_config"
    ADD CONSTRAINT "token_config_chain_contract_address_key" UNIQUE ("chain", "contract_address");


--
-- Name: token_config token_config_chain_symbol_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."token_config"
    ADD CONSTRAINT "token_config_chain_symbol_key" UNIQUE ("chain", "symbol");


--
-- Name: token_config token_config_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."token_config"
    ADD CONSTRAINT "token_config_pkey" PRIMARY KEY ("id");


--
-- Name: ton_transaction ton_transaction_chain_tx_hash_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."ton_transaction"
    ADD CONSTRAINT "ton_transaction_chain_tx_hash_key" UNIQUE ("chain", "tx_hash");


--
-- Name: ton_transaction ton_transaction_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."ton_transaction"
    ADD CONSTRAINT "ton_transaction_pkey" PRIMARY KEY ("id");


--
-- Name: tron_token_transfer tron_token_transfer_chain_tx_hash_log_index_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."tron_token_transfer"
    ADD CONSTRAINT "tron_token_transfer_chain_tx_hash_log_index_key" UNIQUE ("chain", "tx_hash", "log_index");


--
-- Name: tron_token_transfer tron_token_transfer_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."tron_token_transfer"
    ADD CONSTRAINT "tron_token_transfer_pkey" PRIMARY KEY ("id");


--
-- Name: tron_transaction tron_transaction_chain_tx_hash_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."tron_transaction"
    ADD CONSTRAINT "tron_transaction_chain_tx_hash_key" UNIQUE ("chain", "tx_hash");


--
-- Name: tron_transaction tron_transaction_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."tron_transaction"
    ADD CONSTRAINT "tron_transaction_pkey" PRIMARY KEY ("id");


--
-- Name: tron_tx tron_tx_chain_tx_hash_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."tron_tx"
    ADD CONSTRAINT "tron_tx_chain_tx_hash_key" UNIQUE ("chain", "tx_hash");


--
-- Name: tron_tx tron_tx_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."tron_tx"
    ADD CONSTRAINT "tron_tx_pkey" PRIMARY KEY ("id");


--
-- Name: collection_record uq_collection_record_chain_collection_no; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."collection_record"
    ADD CONSTRAINT "uq_collection_record_chain_collection_no" UNIQUE ("chain", "collection_no");


--
-- Name: withdrawal_order uq_withdrawal_order_chain_order_no; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."withdrawal_order"
    ADD CONSTRAINT "uq_withdrawal_order_chain_order_no" UNIQUE ("chain", "order_no");


--
-- Name: utxo_record utxo_record_chain_tx_hash_vout_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."utxo_record"
    ADD CONSTRAINT "utxo_record_chain_tx_hash_vout_key" UNIQUE ("chain", "tx_hash", "vout");


--
-- Name: utxo_record utxo_record_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."utxo_record"
    ADD CONSTRAINT "utxo_record_pkey" PRIMARY KEY ("id");


--
-- Name: wallet_public_key wallet_public_key_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."wallet_public_key"
    ADD CONSTRAINT "wallet_public_key_pkey" PRIMARY KEY ("key_slot");


--
-- Name: wallet_system_config wallet_system_config_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."wallet_system_config"
    ADD CONSTRAINT "wallet_system_config_pkey" PRIMARY KEY ("config_key");


--
-- Name: withdrawal_order withdrawal_order_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."withdrawal_order"
    ADD CONSTRAINT "withdrawal_order_pkey" PRIMARY KEY ("id");


--
-- Name: idx_chain_address_owner; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "idx_chain_address_owner" ON "public"."chain_address" USING "btree" ("chain", "owner_address");


--
-- Name: idx_chain_address_scan; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "idx_chain_address_scan" ON "public"."chain_address" USING "btree" ("chain", "asset_symbol", "enabled");


--
-- Name: idx_wallet_transfer_order_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "idx_wallet_transfer_order_user" ON "public"."wallet_transfer_order" USING "btree" ("from_user_id", "to_user_id", "updated_at");


--
-- Name: idx_wallet_user_session_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "idx_wallet_user_session_user" ON "public"."wallet_user_session" USING "btree" ("user_id", "expires_at");


--
-- Name: idx_chain_signing_transaction_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "idx_chain_signing_transaction_status" ON "public"."chain_signing_transaction" USING "btree" ("chain", "status", "update_date");


--
-- Name: idx_chain_signing_transaction_tx_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "idx_chain_signing_transaction_tx_id" ON "public"."chain_signing_transaction" USING "btree" ("chain", "tx_id");


--
-- Name: idx_utxo_record_address; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "idx_utxo_record_address" ON "public"."utxo_record" USING "btree" ("chain", "address");


--
-- Name: idx_utxo_record_spendable; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "idx_utxo_record_spendable" ON "public"."utxo_record" USING "btree" ("chain", "asset_symbol", "state", "confirmations");


--
-- PostgreSQL database dump complete
--

\unrestrict u57OHvqSag6SqhfB2Qz4gmcyqHuwAbv1WRVW4E6Z0DXFIP6BetuzPHvn4D8Imsw

--
-- PostgreSQL database dump
--

\restrict 9Kpi8hXJ4id9GXP43FVP7ZCSSFGYuxl1ckMhU4r3jA1jBkYZd1EtR0ZsAls1RQg

-- Dumped from database version 18.3 (Homebrew)
-- Dumped by pg_dump version 18.3 (Homebrew)

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
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at", "chain_id", "gas_policy", "scan_batch_size", "scan_enabled", "withdraw_enabled", "collection_enabled", "transfer_enabled", "scan_start_height", "scan_max_blocks_per_run") VALUES (43, 'SUI', 'testnet', 'sui', 53, 784, 'SUI', 'https://fullnode.testnet.sui.io:443', 'https://suiexplorer.com/txblock/', 1, 1, 10000000, 1, true, '2026-06-23 23:21:21.428505+08', '2026-06-25 00:11:35.418443+08', NULL, 'sui', 100, true, true, true, true, 0, 0);
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
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (10, 'SUI', 'testnet', 'dev', 'sui-testnet-public', 'rpc', 'HTTP_JSON_RPC', 'https://fullnode.testnet.sui.io:443', 'NONE', NULL, NULL, NULL, NULL, 10, true, NULL, 'Sui testnet fullnode', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', NULL, NULL, NULL);
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
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "min_request_interval_ms", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (38, 'SUI', 'testnet', 'dev', 'publicnode-sui-testnet', 'rpc', 'HTTP_JSON_RPC', 'https://sui-testnet-rpc.publicnode.com', 'NONE', NULL, NULL, NULL, NULL, 50, 500, true, NULL, 'PublicNode backup for Sui testnet JSON-RPC.', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', NULL, NULL, NULL);
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
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "min_request_interval_ms", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (217, 'SUI', 'testnet', 'test2', 'sui-testnet-official-keyed', 'rpc', 'HTTP_JSON_RPC', 'https://fullnode.testnet.sui.io:443', 'NONE', NULL, NULL, NULL, NULL, 5, 350, true, NULL, 'test2 Sui official testnet JSON-RPC.', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', NULL, NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "min_request_interval_ms", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (218, 'SUI', 'testnet', 'test2', 'publicnode-sui-testnet-priority', 'rpc', 'HTTP_JSON_RPC', 'https://sui-testnet-rpc.publicnode.com', 'NONE', NULL, NULL, NULL, NULL, 50, 500, true, NULL, 'test2 PublicNode backup for Sui testnet JSON-RPC.', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', NULL, NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "min_request_interval_ms", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (219, 'APTOS', 'testnet', 'test2', 'aptos-testnet-official-keyed', 'rpc', 'HTTP_REST', 'https://fullnode.testnet.aptoslabs.com/v1', 'NONE', NULL, NULL, NULL, NULL, 5, 250, true, NULL, 'test2 Aptos Labs testnet fullnode.', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', NULL, NULL, NULL);
INSERT INTO "public"."chain_rpc_node" ("id", "chain", "network", "environment", "node_label", "purpose", "connection_type", "rpc_url", "auth_type", "auth_header_name", "api_key_ref", "username_ref", "password_ref", "priority", "min_request_interval_ms", "enabled", "renewal_due_at", "remark", "created_at", "updated_at", "api_key", "username", "password") VALUES (220, 'SUI', 'testnet', 'test2', 'blockpi-sui-testnet', 'rpc', 'HTTP_JSON_RPC', 'https://sui-testnet.blockpi.network/v1/rpc/CHANGE_ME_BLOCKPI_SUI_TESTNET_TOKEN', 'NONE', NULL, NULL, NULL, NULL, 60, 1000, true, NULL, 'test2 BlockPI Sui testnet HTTP JSON-RPC backup. Replace with gRPC client when Sui gRPC write/read path is complete.', '2026-06-25 00:12:12.264406+08', '2026-06-25 00:12:12.264406+08', NULL, NULL, NULL);
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
INSERT INTO "public"."token_config" ("id", "chain", "symbol", "standard", "contract_address", "decimals", "enabled", "min_deposit", "min_withdraw", "collect_enabled", "created_at", "updated_at", "network", "token_standard", "contract_address_base58", "contract_address_hex", "min_deposit_amount", "min_withdraw_amount", "collect_threshold", "gas_strategy", "confirmation_required") VALUES (94, 'APTOS', 'MUSD', 'APTOS_COIN', '0x0efda149ef9237e8a6cb23228ec986bec0898f320f0d03e8f8b744208244759e::mock_coin::MockCoin', 6, true, 1.000000000000000000, 1.000000000000000000, true, '2026-06-23 22:48:57.330957+08', '2026-06-23 23:03:51.458057+08', 'devnet', 'COIN', NULL, NULL, 1.000000000000000000, 1.000000000000000000, 1.000000000000000000, 'APT_GAS', 1);
INSERT INTO "public"."token_config" ("id", "chain", "symbol", "standard", "contract_address", "decimals", "enabled", "min_deposit", "min_withdraw", "collect_enabled", "created_at", "updated_at", "network", "token_standard", "contract_address_base58", "contract_address_hex", "min_deposit_amount", "min_withdraw_amount", "collect_threshold", "gas_strategy", "confirmation_required") VALUES (98, 'SUI', 'TESTCOIN', 'SUI_COIN', '0x2::sui::SUI', 9, false, 1.000000000000000000, 1.000000000000000000, false, '2026-06-23 23:21:21.43093+08', '2026-06-23 23:21:21.43093+08', 'testnet', 'COIN', NULL, NULL, 1.000000000000000000, 1.000000000000000000, 1.000000000000000000, 'SUI_GAS_OBJECT', 1);
INSERT INTO "public"."token_config" ("id", "chain", "symbol", "standard", "contract_address", "decimals", "enabled", "min_deposit", "min_withdraw", "collect_enabled", "created_at", "updated_at", "network", "token_standard", "contract_address_base58", "contract_address_hex", "min_deposit_amount", "min_withdraw_amount", "collect_threshold", "gas_strategy", "confirmation_required") VALUES (99, 'SUI', 'MUSD', 'SUI_COIN', '0x516b04d9f19a4eee51fb9b2e3d80a4691ee65680cd488ddffd8b91c4d24762ce::mock_coin::MOCK_COIN', 6, true, 1.000000000000000000, 1.000000000000000000, true, '2026-06-23 23:43:22.328161+08', '2026-06-24 00:01:17.686215+08', 'testnet', 'COIN', NULL, NULL, 1.000000000000000000, 1.000000000000000000, 1.000000000000000000, 'SUI_GAS_OBJECT', 1);
INSERT INTO "public"."token_config" ("id", "chain", "symbol", "standard", "contract_address", "decimals", "enabled", "min_deposit", "min_withdraw", "collect_enabled", "created_at", "updated_at", "network", "token_standard", "contract_address_base58", "contract_address_hex", "min_deposit_amount", "min_withdraw_amount", "collect_threshold", "gas_strategy", "confirmation_required") VALUES (17, 'POLYGON', 'USDT', 'ERC20', '0xb5F6211f94FCC162D5c8cebba4f656c965577392', 6, true, 0.000000000000000000, 0.000000000000000000, true, '2026-06-20 18:38:52.367548+08', '2026-06-20 23:13:41.261145+08', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL);
INSERT INTO "public"."token_config" ("id", "chain", "symbol", "standard", "contract_address", "decimals", "enabled", "min_deposit", "min_withdraw", "collect_enabled", "created_at", "updated_at", "network", "token_standard", "contract_address_base58", "contract_address_hex", "min_deposit_amount", "min_withdraw_amount", "collect_threshold", "gas_strategy", "confirmation_required") VALUES (18, 'POLYGON', 'USDC', 'ERC20', '0x729B992ba1ccea88BE66985DCa5Ff28Ebba12046', 6, true, 0.000000000000000000, 0.000000000000000000, true, '2026-06-20 18:38:52.370655+08', '2026-06-20 23:13:41.26619+08', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL);
INSERT INTO "public"."token_config" ("id", "chain", "symbol", "standard", "contract_address", "decimals", "enabled", "min_deposit", "min_withdraw", "collect_enabled", "created_at", "updated_at", "network", "token_standard", "contract_address_base58", "contract_address_hex", "min_deposit_amount", "min_withdraw_amount", "collect_threshold", "gas_strategy", "confirmation_required") VALUES (1, 'ETH', 'USDT', 'ERC20', '0x278E80923f1a7194c0777500d794c489990259FA', 6, true, 0.000000000000000000, 0.000000000000000000, true, '2026-06-20 18:23:33.141962+08', '2026-06-24 17:27:12.470424+08', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL);
INSERT INTO "public"."token_config" ("id", "chain", "symbol", "standard", "contract_address", "decimals", "enabled", "min_deposit", "min_withdraw", "collect_enabled", "created_at", "updated_at", "network", "token_standard", "contract_address_base58", "contract_address_hex", "min_deposit_amount", "min_withdraw_amount", "collect_threshold", "gas_strategy", "confirmation_required") VALUES (75, 'TRON', 'USDT', 'TRC20', 'TXYZopYRdj2D9XRtbG411XZZ3kM5VkAeBf', 6, true, 0.000001000000000000, 0.000001000000000000, true, '2026-06-21 00:25:35.727551+08', '2026-06-21 00:28:58.580102+08', 'NILE', 'TRC20', 'TXYZopYRdj2D9XRtbG411XZZ3kM5VkAeBf', '41eca9bc828a3005b9a3b909f2cc5c2a54794de05f', 0.000001000000000000, 0.000001000000000000, 1.000000000000000000, 'energy-bandwidth', 1);
INSERT INTO "public"."token_config" ("id", "chain", "symbol", "standard", "contract_address", "decimals", "enabled", "min_deposit", "min_withdraw", "collect_enabled", "created_at", "updated_at", "network", "token_standard", "contract_address_base58", "contract_address_hex", "min_deposit_amount", "min_withdraw_amount", "collect_threshold", "gas_strategy", "confirmation_required") VALUES (78, 'SOLANA', 'USDT', 'SPL', '2VHdVaW1jBGmvU1PdQpvmPnWV5AiAxrmxmUTLvf8951y', 6, true, 1.000000000000000000, 1.000000000000000000, true, '2026-06-23 15:01:18.893717+08', '2026-06-23 15:02:47.901793+08', 'devnet', 'SPL', '2VHdVaW1jBGmvU1PdQpvmPnWV5AiAxrmxmUTLvf8951y', NULL, 1.000000000000000000, 1.000000000000000000, 1.000000000000000000, 'SOL_FEE_PAYER', 1);
INSERT INTO "public"."token_config" ("id", "chain", "symbol", "standard", "contract_address", "decimals", "enabled", "min_deposit", "min_withdraw", "collect_enabled", "created_at", "updated_at", "network", "token_standard", "contract_address_base58", "contract_address_hex", "min_deposit_amount", "min_withdraw_amount", "collect_threshold", "gas_strategy", "confirmation_required") VALUES (79, 'SOLANA', 'USDC', 'SPL', '4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU', 6, true, 1.000000000000000000, 1.000000000000000000, true, '2026-06-23 15:01:43.398652+08', '2026-06-23 15:03:11.78696+08', 'devnet', 'SPL', '4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU', NULL, 1.000000000000000000, 1.000000000000000000, 1.000000000000000000, 'SOL_FEE_PAYER', 1);
INSERT INTO "public"."token_config" ("id", "chain", "symbol", "standard", "contract_address", "decimals", "enabled", "min_deposit", "min_withdraw", "collect_enabled", "created_at", "updated_at", "network", "token_standard", "contract_address_base58", "contract_address_hex", "min_deposit_amount", "min_withdraw_amount", "collect_threshold", "gas_strategy", "confirmation_required") VALUES (2, 'ETH', 'USDC', 'ERC20', '0x9478eC397A2F4Be6A84916dD8a353c91b78c6238', 6, true, 0.000000000000000000, 0.000000000000000000, true, '2026-06-20 18:23:33.151844+08', '2026-06-24 17:27:12.473627+08', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL);

UPDATE "public"."token_config" SET "enabled" = false, "collect_enabled" = false, "network" = 'testnet', "updated_at" = "now"() WHERE "id" = 94;


--
-- Data for Name: wallet_public_key; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO "public"."wallet_public_key" ("key_slot", "key_role", "key_type", "network", "public_key", "enabled", "remark", "created_at", "updated_at") VALUES (1, 'sig1-hot', 'BIP32_XPUB', 'test', 'tpubD6NzVbkrYhZ4YeTnP6ae6en8YvKSvxvvCwh5X7gNpwqEeix6o7etGgsyGywcB9gS1bGTmC4WfLKAdK6vxDEzedd7PMRLcYk5yZLj5JkLAVB', true, 'online first signer public root', '2026-06-25 00:10:43.489237+08', '2026-06-25 00:10:43.489237+08');
INSERT INTO "public"."wallet_public_key" ("key_slot", "key_role", "key_type", "network", "public_key", "enabled", "remark", "created_at", "updated_at") VALUES (2, 'sig2-cold', 'BIP32_XPUB', 'test', 'tpubD6NzVbkrYhZ4WuN2bmdffo5p894oRYGQVCfKe3TKT4QVw7qQT18jG1FYbYyB3ePESejLdfaEFMRpsYGVjb4Bh6HiiWaSU8iJRVE46EirNBT', true, 'online second signer public root', '2026-06-25 00:10:43.489237+08', '2026-06-25 00:10:43.489237+08');
INSERT INTO "public"."wallet_public_key" ("key_slot", "key_role", "key_type", "network", "public_key", "enabled", "remark", "created_at", "updated_at") VALUES (3, 'offline-recovery', 'BIP32_XPUB', 'test', 'tpubD6NzVbkrYhZ4XKeuSHwv2p3snJxWjacFsu2rEEht2qMaM5FYV2RkbMaJEYNZGK7B3i8D46RTs83DJNPh2Jd5MzXivXCiHLbqAFKv8MKxrC4', true, 'offline recovery public root', '2026-06-25 00:10:43.489237+08', '2026-06-25 00:10:43.489237+08');


--
-- Data for Name: wallet_system_config; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO "public"."wallet_system_config" ("config_key", "config_value", "value_type", "enabled", "remark", "created_at", "updated_at") VALUES ('global.all.enabled', 'true', 'boolean', true, 'Global master switch for all wallet runtime jobs', '2026-06-25 00:10:43.487527+08', '2026-06-25 00:10:43.487527+08');
INSERT INTO "public"."wallet_system_config" ("config_key", "config_value", "value_type", "enabled", "remark", "created_at", "updated_at") VALUES ('global.scan.enabled', 'true', 'boolean', true, 'Global block/account scanner switch', '2026-06-25 00:10:43.487527+08', '2026-06-25 00:10:43.487527+08');
INSERT INTO "public"."wallet_system_config" ("config_key", "config_value", "value_type", "enabled", "remark", "created_at", "updated_at") VALUES ('global.withdraw.enabled', 'true', 'boolean', true, 'Global withdrawal switch', '2026-06-25 00:10:43.487527+08', '2026-06-25 00:10:43.487527+08');
INSERT INTO "public"."wallet_system_config" ("config_key", "config_value", "value_type", "enabled", "remark", "created_at", "updated_at") VALUES ('global.collection.enabled', 'true', 'boolean', true, 'Global collection switch', '2026-06-25 00:10:43.487527+08', '2026-06-25 00:10:43.487527+08');
INSERT INTO "public"."wallet_system_config" ("config_key", "config_value", "value_type", "enabled", "remark", "created_at", "updated_at") VALUES ('global.transfer.enabled', 'true', 'boolean', true, 'Global internal transfer switch', '2026-06-25 00:10:43.487527+08', '2026-06-25 00:10:43.487527+08');
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
        ('APTOS', 'USDC', 'APTOS_FA', 'testnet', 'APTOS_FA', '0x69091fbab5f7d635ee7ac5098cf0c1efbe31d68fec0f2cd565e8d168daf52832', 6, NULL, NULL, 'APT_GAS'),
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
ON CONFLICT ("chain", "symbol") DO UPDATE SET
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
-- not modeled here.
INSERT INTO "public"."chain_asset" ("chain", "symbol", "asset_kind", "contract_address", "decimals",
                                    "native_asset", "active", "min_transfer", "min_withdraw",
                                    "created_at", "updated_at")
VALUES
    ('HYPEREVM', 'HYPE', 'NATIVE', NULL, 18, true, true, 0.000001, 0.000001, now(), now())
ON CONFLICT ("chain", "symbol") DO UPDATE SET
    "asset_kind" = EXCLUDED."asset_kind",
    "contract_address" = EXCLUDED."contract_address",
    "decimals" = EXCLUDED."decimals",
    "native_asset" = EXCLUDED."native_asset",
    "active" = EXCLUDED."active",
    "min_transfer" = EXCLUDED."min_transfer",
    "min_withdraw" = EXCLUDED."min_withdraw",
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
ON CONFLICT ("chain", "symbol") DO UPDATE SET
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

\unrestrict 9Kpi8hXJ4id9GXP43FVP7ZCSSFGYuxl1ckMhU4r3jA1jBkYZd1EtR0ZsAls1RQg
