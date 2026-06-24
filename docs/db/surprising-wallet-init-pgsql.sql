--
-- PostgreSQL database dump
--

\restrict IppSelh0C3y5UxPuOmVV4jFmo6GDOjn9eltqRvfrfOCHp23hVyn7JWWrtP3uRch

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
ALTER TABLE IF EXISTS ONLY "public"."withdrawal_order" DROP CONSTRAINT IF EXISTS "withdrawal_order_pkey";
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
ALTER TABLE IF EXISTS ONLY "public"."token_registry" DROP CONSTRAINT IF EXISTS "token_registry_pkey";
ALTER TABLE IF EXISTS ONLY "public"."token_registry" DROP CONSTRAINT IF EXISTS "token_registry_chain_symbol_key";
ALTER TABLE IF EXISTS ONLY "public"."token_registry" DROP CONSTRAINT IF EXISTS "token_registry_chain_contract_address_key";
ALTER TABLE IF EXISTS ONLY "public"."token_config" DROP CONSTRAINT IF EXISTS "token_config_pkey";
ALTER TABLE IF EXISTS ONLY "public"."token_config" DROP CONSTRAINT IF EXISTS "token_config_chain_symbol_key";
ALTER TABLE IF EXISTS ONLY "public"."token_config" DROP CONSTRAINT IF EXISTS "token_config_chain_contract_address_key";
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
ALTER TABLE IF EXISTS ONLY "public"."chain_profile" DROP CONSTRAINT IF EXISTS "chain_profile_runtime_currency_id_network_key";
ALTER TABLE IF EXISTS ONLY "public"."chain_profile" DROP CONSTRAINT IF EXISTS "chain_profile_pkey";
ALTER TABLE IF EXISTS ONLY "public"."chain_profile" DROP CONSTRAINT IF EXISTS "chain_profile_chain_network_key";
ALTER TABLE IF EXISTS ONLY "public"."chain_asset" DROP CONSTRAINT IF EXISTS "chain_asset_pkey";
ALTER TABLE IF EXISTS ONLY "public"."chain_asset" DROP CONSTRAINT IF EXISTS "chain_asset_chain_symbol_key";
ALTER TABLE IF EXISTS ONLY "public"."chain_address" DROP CONSTRAINT IF EXISTS "chain_address_pkey";
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
ALTER TABLE IF EXISTS "public"."token_registry" ALTER COLUMN "id" DROP DEFAULT;
ALTER TABLE IF EXISTS "public"."token_config" ALTER COLUMN "id" DROP DEFAULT;
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
ALTER TABLE IF EXISTS "public"."chain_profile" ALTER COLUMN "id" DROP DEFAULT;
ALTER TABLE IF EXISTS "public"."chain_asset" ALTER COLUMN "id" DROP DEFAULT;
ALTER TABLE IF EXISTS "public"."chain_address" ALTER COLUMN "id" DROP DEFAULT;
ALTER TABLE IF EXISTS "public"."aptos_transaction" ALTER COLUMN "id" DROP DEFAULT;
ALTER TABLE IF EXISTS "public"."account_sequence" ALTER COLUMN "id" DROP DEFAULT;
DROP SEQUENCE IF EXISTS "public"."withdrawal_order_id_seq";
DROP TABLE IF EXISTS "public"."withdrawal_order";
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
DROP SEQUENCE IF EXISTS "public"."token_registry_id_seq";
DROP TABLE IF EXISTS "public"."token_registry";
DROP SEQUENCE IF EXISTS "public"."token_config_id_seq";
DROP TABLE IF EXISTS "public"."token_config";
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
    "amount" numeric(78,18) DEFAULT 0 NOT NULL,
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
    "min_transfer" numeric(78,0),
    "min_withdraw" numeric(78,0),
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
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL
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
    "balance" numeric(78,18) DEFAULT 0 NOT NULL,
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
    "amount" numeric(78,18) DEFAULT 0 NOT NULL,
    "fee" numeric(78,18) DEFAULT 0 NOT NULL,
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
    "amount" numeric(78,18) NOT NULL,
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
    "amount" numeric(78,18) DEFAULT 0 NOT NULL,
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
    "amount" numeric(78,18) DEFAULT 0 NOT NULL,
    "fee" numeric(78,18) DEFAULT 0 NOT NULL,
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
    "amount" numeric(78,18) DEFAULT 0 NOT NULL,
    "fee" numeric(78,18) DEFAULT 0 NOT NULL,
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
    "amount" numeric(78,18) DEFAULT 0 NOT NULL,
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
    "available_balance" numeric(78,18) DEFAULT 0 NOT NULL,
    "locked_balance" numeric(78,18) DEFAULT 0 NOT NULL,
    "total_balance" numeric(78,18) DEFAULT 0 NOT NULL,
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
    "amount" numeric(78,18) DEFAULT 0 NOT NULL,
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
    "amount" numeric(78,18) DEFAULT 0 NOT NULL,
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
    "min_deposit" numeric(78,18),
    "min_withdraw" numeric(78,18),
    "collect_enabled" boolean DEFAULT true NOT NULL,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "network" character varying(32),
    "token_standard" character varying(32),
    "contract_address_base58" character varying(128),
    "contract_address_hex" character varying(128),
    "min_deposit_amount" numeric(78,18),
    "min_withdraw_amount" numeric(78,18),
    "collect_threshold" numeric(78,18),
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
-- Name: token_registry; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."token_registry" (
    "id" bigint NOT NULL,
    "chain" character varying(32) NOT NULL,
    "symbol" character varying(32) NOT NULL,
    "contract_address" character varying(128) NOT NULL,
    "decimals" integer DEFAULT 18 NOT NULL,
    "standard" character varying(32) NOT NULL,
    "native_asset" boolean DEFAULT false NOT NULL,
    "active" boolean DEFAULT true NOT NULL,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL
);


--
-- Name: token_registry_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE "public"."token_registry_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: token_registry_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE "public"."token_registry_id_seq" OWNED BY "public"."token_registry"."id";


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
    "amount" numeric(78,18) DEFAULT 0 NOT NULL,
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
    "amount" numeric(78,18) DEFAULT 0 NOT NULL,
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
    "amount" numeric(78,18) DEFAULT 0 NOT NULL,
    "fee" numeric(78,18) DEFAULT 0 NOT NULL,
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
    "amount" numeric(78,18) DEFAULT 0 NOT NULL,
    "fee" numeric(78,18) DEFAULT 0 NOT NULL,
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
    "amount" numeric(78,18) NOT NULL,
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
-- Name: withdrawal_order; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."withdrawal_order" (
    "id" bigint NOT NULL,
    "order_no" character varying(96) NOT NULL,
    "user_id" bigint NOT NULL,
    "chain" character varying(32) NOT NULL,
    "asset_symbol" character varying(32) NOT NULL,
    "from_address" character varying(160),
    "to_address" character varying(160) NOT NULL,
    "amount" numeric(78,18) NOT NULL,
    "fee" numeric(78,18) DEFAULT 0 NOT NULL,
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
-- Name: token_config id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."token_config" ALTER COLUMN "id" SET DEFAULT "nextval"('"public"."token_config_id_seq"'::"regclass");


--
-- Name: token_registry id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."token_registry" ALTER COLUMN "id" SET DEFAULT "nextval"('"public"."token_registry_id_seq"'::"regclass");


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
-- Name: token_registry token_registry_chain_contract_address_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."token_registry"
    ADD CONSTRAINT "token_registry_chain_contract_address_key" UNIQUE ("chain", "contract_address");


--
-- Name: token_registry token_registry_chain_symbol_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."token_registry"
    ADD CONSTRAINT "token_registry_chain_symbol_key" UNIQUE ("chain", "symbol");


--
-- Name: token_registry token_registry_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."token_registry"
    ADD CONSTRAINT "token_registry_pkey" PRIMARY KEY ("id");


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

\unrestrict IppSelh0C3y5UxPuOmVV4jFmo6GDOjn9eltqRvfrfOCHp23hVyn7JWWrtP3uRch

--
-- PostgreSQL database dump
--

\restrict bjqNN0t14eWj4c7qSXOJEQUjjYyrWOceDTxgF24JoezZIbQkg44OuC1vcHug53l

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
INSERT INTO "public"."chain_asset" ("id", "chain", "symbol", "asset_kind", "contract_address", "decimals", "native_asset", "active", "min_transfer", "min_withdraw", "created_at", "updated_at") VALUES (26, 'SOLANA', 'USDC', 'TOKEN', 'FUez5CPP3C4VN7JGkgKxamC8f4cvt397R3unqUX6tekt', 6, false, true, 1, 1, '2026-06-23 15:01:43.41494+08', '2026-06-23 15:03:11.79609+08');
INSERT INTO "public"."chain_asset" ("id", "chain", "symbol", "asset_kind", "contract_address", "decimals", "native_asset", "active", "min_transfer", "min_withdraw", "created_at", "updated_at") VALUES (10, 'SOLANA', 'SOL', 'NATIVE', NULL, 9, true, true, 1, 1, '2026-06-20 15:10:22.823199+08', '2026-06-23 21:01:38.531755+08');
INSERT INTO "public"."chain_asset" ("id", "chain", "symbol", "asset_kind", "contract_address", "decimals", "native_asset", "active", "min_transfer", "min_withdraw", "created_at", "updated_at") VALUES (11, 'TON', 'TON', 'NATIVE', NULL, 9, true, true, 1000000, 1000000, '2026-06-20 15:10:22.823199+08', '2026-06-23 21:01:38.590086+08');
INSERT INTO "public"."chain_asset" ("id", "chain", "symbol", "asset_kind", "contract_address", "decimals", "native_asset", "active", "min_transfer", "min_withdraw", "created_at", "updated_at") VALUES (34, 'TON', 'USDT', 'JETTON', 'kQCZ5SAA78W_0vA5eSoU23YomxnUwah3KYagqeesNQI5jOXT', 6, false, true, 1, 1, '2026-06-23 21:37:58.688905+08', '2026-06-23 21:59:30.209563+08');
INSERT INTO "public"."chain_asset" ("id", "chain", "symbol", "asset_kind", "contract_address", "decimals", "native_asset", "active", "min_transfer", "min_withdraw", "created_at", "updated_at") VALUES (35, 'TON', 'USDC', 'JETTON', 'kQCzPT6908-8TR862TQo1S43-2kEme8UKRCRSWkaxNLD7H_2', 6, false, true, 1, 1, '2026-06-23 21:38:19.051838+08', '2026-06-23 21:59:40.049688+08');
INSERT INTO "public"."chain_asset" ("id", "chain", "symbol", "asset_kind", "contract_address", "decimals", "native_asset", "active", "min_transfer", "min_withdraw", "created_at", "updated_at") VALUES (44, 'APTOS', 'APT', 'NATIVE', NULL, 8, true, true, 1, 1, '2026-06-23 22:24:58.098309+08', '2026-06-23 22:24:58.098309+08');
INSERT INTO "public"."chain_asset" ("id", "chain", "symbol", "asset_kind", "contract_address", "decimals", "native_asset", "active", "min_transfer", "min_withdraw", "created_at", "updated_at") VALUES (45, 'SUI', 'SUI', 'NATIVE', NULL, 9, true, true, 1, 1, '2026-06-23 23:21:21.429955+08', '2026-06-23 23:21:21.429955+08');


--
-- Data for Name: chain_profile; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at") VALUES (43, 'SUI', 'testnet', 'sui', 53, 784, 'SUI', 'https://fullnode.testnet.sui.io:443', 'https://suiexplorer.com/txblock/', 1, 1, 10000000, 1, true, '2026-06-23 23:21:21.428505+08', '2026-06-23 23:21:21.428505+08');
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at") VALUES (44, 'SUI', 'mainnet', 'sui', 53, 784, 'SUI', NULL, 'https://suiexplorer.com/txblock/', 1, 1, 10000000, 1, false, '2026-06-23 23:21:21.428505+08', '2026-06-23 23:21:21.428505+08');
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at") VALUES (97, 'BTC', 'regtest', 'bitcoin-like', 1, 0, 'BTC', NULL, NULL, 6, 6, 1, 546, true, '2026-06-24 17:50:02.98325+08', '2026-06-24 18:07:33.452523+08');
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at") VALUES (98, 'LTC', 'regtest', 'bitcoin-like', 24, 2, 'LTC', NULL, NULL, 6, 6, 10, 100000, true, '2026-06-24 17:50:02.98325+08', '2026-06-24 18:07:33.452523+08');
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at") VALUES (16, 'DOGE', 'regtest', 'bitcoin-like', 41, 3, 'DOGE', 'http://127.0.0.1:22555', NULL, 6, 6, 1000, 1000000, true, '2026-06-23 10:11:12.947084+08', '2026-06-24 18:07:33.452523+08');
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at") VALUES (17, 'BCH', 'regtest', 'bitcoin-like', 42, 145, 'BCH', 'http://127.0.0.1:18443', NULL, 6, 6, 1, 546, true, '2026-06-23 11:36:08.628091+08', '2026-06-24 18:07:33.452523+08');
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at") VALUES (81, 'BTC', 'testnet3', 'bitcoin-like', 1, 0, 'BTC', 'https://bitcoin-testnet-rpc.publicnode.com', 'https://mempool.space/testnet/tx/', 1, 6, 10, 546, true, '2026-06-24 14:56:38.262511+08', '2026-06-24 14:56:38.262511+08');
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at") VALUES (82, 'BTC', 'mainnet', 'bitcoin-like', 1, 0, 'BTC', NULL, 'https://mempool.space/tx/', 1, 6, 10, 546, true, '2026-06-24 14:56:38.262511+08', '2026-06-24 14:56:38.262511+08');
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at") VALUES (1, 'LTC', 'testnet', 'bitcoin-like', 24, 2, 'LTC', NULL, 'https://litecoinspace.org/testnet/tx/', 1, 6, 2, 1000, true, '2026-06-21 18:09:31.766008+08', '2026-06-23 21:01:38.382613+08');
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at") VALUES (3, 'LTC', 'mainnet', 'bitcoin-like', 24, 2, 'LTC', NULL, 'https://litecoinspace.org/tx/', 1, 6, 2, 1000, true, '2026-06-21 18:32:14.57969+08', '2026-06-23 21:01:38.420274+08');
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at") VALUES (8, 'DOGE', 'testnet', 'bitcoin-like', 41, 3, 'DOGE', NULL, 'https://doge-testnet-explorer.qed.me/tx/', 6, 12, 1000, 1000000, true, '2026-06-21 19:13:13.385044+08', '2026-06-23 21:01:38.425665+08');
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at") VALUES (9, 'DOGE', 'mainnet', 'bitcoin-like', 41, 3, 'DOGE', NULL, 'https://dogechain.info/tx/', 6, 12, 1000, 1000000, true, '2026-06-21 19:13:13.385893+08', '2026-06-23 21:01:38.426238+08');
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at") VALUES (14, 'BCH', 'testnet', 'bitcoin-like', 42, 145, 'BCH', NULL, 'https://tbch.loping.net/tx/', 1, 6, 1, 546, true, '2026-06-21 23:17:57.795865+08', '2026-06-23 21:01:38.494439+08');
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at") VALUES (15, 'BCH', 'mainnet', 'bitcoin-like', 42, 145, 'BCH', NULL, 'https://blockchair.com/bitcoin-cash/transaction/', 1, 6, 1, 546, true, '2026-06-21 23:17:57.795865+08', '2026-06-23 21:01:38.494439+08');
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at") VALUES (26, 'SOLANA', 'devnet', 'solana', 50, 501, 'SOL', 'https://api.devnet.solana.com', 'https://explorer.solana.com/tx/', 1, 1, 5000, 890880, true, '2026-06-23 14:47:05.346909+08', '2026-06-23 21:01:38.531176+08');
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at") VALUES (27, 'SOLANA', 'mainnet', 'solana', 50, 501, 'SOL', NULL, 'https://explorer.solana.com/tx/', 32, 32, 5000, 890880, false, '2026-06-23 14:47:05.346909+08', '2026-06-23 21:01:38.531176+08');
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at") VALUES (38, 'TON', 'testnet', 'ton', 51, 607, 'TON', 'https://testnet.toncenter.com/api/v2', 'https://testnet.tonviewer.com/transaction/', 1, 1, 5000000, 1000000, true, '2026-06-23 21:01:38.587522+08', '2026-06-23 21:01:38.587522+08');
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at") VALUES (39, 'TON', 'mainnet', 'ton', 51, 607, 'TON', NULL, 'https://tonviewer.com/transaction/', 1, 1, 5000000, 1000000, false, '2026-06-23 21:01:38.587522+08', '2026-06-23 21:01:38.587522+08');
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at") VALUES (40, 'APTOS', 'devnet', 'aptos', 52, 637, 'APT', 'https://fullnode.devnet.aptoslabs.com/v1', 'https://explorer.aptoslabs.com/txn/', 1, 1, 5000000, 1, true, '2026-06-23 22:24:58.095994+08', '2026-06-23 22:34:56.292463+08');
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at") VALUES (42, 'APTOS', 'mainnet', 'aptos', 52, 637, 'APT', NULL, 'https://explorer.aptoslabs.com/txn/', 1, 1, 5000000, 1, false, '2026-06-23 22:24:58.095994+08', '2026-06-23 22:34:56.292463+08');
INSERT INTO "public"."chain_profile" ("id", "chain", "network", "family", "runtime_currency_id", "bip44_coin_type", "native_symbol", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations", "default_fee_rate", "dust_threshold", "enabled", "created_at", "updated_at") VALUES (41, 'APTOS', 'testnet', 'aptos', 52, 637, 'APT', 'https://fullnode.testnet.aptoslabs.com/v1', 'https://explorer.aptoslabs.com/txn/', 1, 1, 5000000, 1, false, '2026-06-23 22:24:58.095994+08', '2026-06-23 22:34:56.292463+08');


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
INSERT INTO "public"."token_config" ("id", "chain", "symbol", "standard", "contract_address", "decimals", "enabled", "min_deposit", "min_withdraw", "collect_enabled", "created_at", "updated_at", "network", "token_standard", "contract_address_base58", "contract_address_hex", "min_deposit_amount", "min_withdraw_amount", "collect_threshold", "gas_strategy", "confirmation_required") VALUES (24, 'BASE', 'USDC', 'ERC20', '0xeba5CEc9257045Df0B44eA784F9a7Fa07DeeF6d4', 6, true, 0.000000000000000000, 0.000000000000000000, true, '2026-06-20 18:39:38.656754+08', '2026-06-20 21:02:57.971571+08', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL);
INSERT INTO "public"."token_config" ("id", "chain", "symbol", "standard", "contract_address", "decimals", "enabled", "min_deposit", "min_withdraw", "collect_enabled", "created_at", "updated_at", "network", "token_standard", "contract_address_base58", "contract_address_hex", "min_deposit_amount", "min_withdraw_amount", "collect_threshold", "gas_strategy", "confirmation_required") VALUES (25, 'AVAX_C', 'USDT', 'ERC20', '0x1B43cbC6879C8237469794F9B8Ed290810e502d9', 6, true, 0.000000000000000000, 0.000000000000000000, true, '2026-06-20 18:39:54.422415+08', '2026-06-20 21:04:18.705305+08', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL);
INSERT INTO "public"."token_config" ("id", "chain", "symbol", "standard", "contract_address", "decimals", "enabled", "min_deposit", "min_withdraw", "collect_enabled", "created_at", "updated_at", "network", "token_standard", "contract_address_base58", "contract_address_hex", "min_deposit_amount", "min_withdraw_amount", "collect_threshold", "gas_strategy", "confirmation_required") VALUES (26, 'AVAX_C', 'USDC', 'ERC20', '0xe757C06f170C8EE956E7d80793087c971Ab5D7b5', 6, true, 0.000000000000000000, 0.000000000000000000, true, '2026-06-20 18:39:54.425852+08', '2026-06-20 21:04:18.708504+08', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL);
INSERT INTO "public"."token_config" ("id", "chain", "symbol", "standard", "contract_address", "decimals", "enabled", "min_deposit", "min_withdraw", "collect_enabled", "created_at", "updated_at", "network", "token_standard", "contract_address_base58", "contract_address_hex", "min_deposit_amount", "min_withdraw_amount", "collect_threshold", "gas_strategy", "confirmation_required") VALUES (94, 'APTOS', 'MUSD', 'APTOS_COIN', '0x0efda149ef9237e8a6cb23228ec986bec0898f320f0d03e8f8b744208244759e::mock_coin::MockCoin', 6, true, 1.000000000000000000, 1.000000000000000000, true, '2026-06-23 22:48:57.330957+08', '2026-06-23 23:03:51.458057+08', 'devnet', 'COIN', NULL, NULL, 1.000000000000000000, 1.000000000000000000, 1.000000000000000000, 'APT_GAS', 1);
INSERT INTO "public"."token_config" ("id", "chain", "symbol", "standard", "contract_address", "decimals", "enabled", "min_deposit", "min_withdraw", "collect_enabled", "created_at", "updated_at", "network", "token_standard", "contract_address_base58", "contract_address_hex", "min_deposit_amount", "min_withdraw_amount", "collect_threshold", "gas_strategy", "confirmation_required") VALUES (98, 'SUI', 'TESTCOIN', 'SUI_COIN', '0x2::sui::SUI', 9, false, 1.000000000000000000, 1.000000000000000000, true, '2026-06-23 23:21:21.43093+08', '2026-06-23 23:21:21.43093+08', 'testnet', 'COIN', NULL, NULL, 1.000000000000000000, 1.000000000000000000, 1.000000000000000000, 'SUI_GAS_OBJECT', 1);
INSERT INTO "public"."token_config" ("id", "chain", "symbol", "standard", "contract_address", "decimals", "enabled", "min_deposit", "min_withdraw", "collect_enabled", "created_at", "updated_at", "network", "token_standard", "contract_address_base58", "contract_address_hex", "min_deposit_amount", "min_withdraw_amount", "collect_threshold", "gas_strategy", "confirmation_required") VALUES (99, 'SUI', 'MUSD', 'SUI_COIN', '0x516b04d9f19a4eee51fb9b2e3d80a4691ee65680cd488ddffd8b91c4d24762ce::mock_coin::MOCK_COIN', 6, true, 1.000000000000000000, 1.000000000000000000, true, '2026-06-23 23:43:22.328161+08', '2026-06-24 00:01:17.686215+08', 'testnet', 'COIN', NULL, NULL, 1.000000000000000000, 1.000000000000000000, 1.000000000000000000, 'SUI_GAS_OBJECT', 1);
INSERT INTO "public"."token_config" ("id", "chain", "symbol", "standard", "contract_address", "decimals", "enabled", "min_deposit", "min_withdraw", "collect_enabled", "created_at", "updated_at", "network", "token_standard", "contract_address_base58", "contract_address_hex", "min_deposit_amount", "min_withdraw_amount", "collect_threshold", "gas_strategy", "confirmation_required") VALUES (17, 'POLYGON', 'USDT', 'ERC20', '0xb5F6211f94FCC162D5c8cebba4f656c965577392', 6, true, 0.000000000000000000, 0.000000000000000000, true, '2026-06-20 18:38:52.367548+08', '2026-06-20 23:13:41.261145+08', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL);
INSERT INTO "public"."token_config" ("id", "chain", "symbol", "standard", "contract_address", "decimals", "enabled", "min_deposit", "min_withdraw", "collect_enabled", "created_at", "updated_at", "network", "token_standard", "contract_address_base58", "contract_address_hex", "min_deposit_amount", "min_withdraw_amount", "collect_threshold", "gas_strategy", "confirmation_required") VALUES (18, 'POLYGON', 'USDC', 'ERC20', '0x729B992ba1ccea88BE66985DCa5Ff28Ebba12046', 6, true, 0.000000000000000000, 0.000000000000000000, true, '2026-06-20 18:38:52.370655+08', '2026-06-20 23:13:41.26619+08', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL);
INSERT INTO "public"."token_config" ("id", "chain", "symbol", "standard", "contract_address", "decimals", "enabled", "min_deposit", "min_withdraw", "collect_enabled", "created_at", "updated_at", "network", "token_standard", "contract_address_base58", "contract_address_hex", "min_deposit_amount", "min_withdraw_amount", "collect_threshold", "gas_strategy", "confirmation_required") VALUES (1, 'ETH', 'USDT', 'ERC20', '0x278E80923f1a7194c0777500d794c489990259FA', 6, true, 0.000000000000000000, 0.000000000000000000, true, '2026-06-20 18:23:33.141962+08', '2026-06-24 17:27:12.470424+08', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL);
INSERT INTO "public"."token_config" ("id", "chain", "symbol", "standard", "contract_address", "decimals", "enabled", "min_deposit", "min_withdraw", "collect_enabled", "created_at", "updated_at", "network", "token_standard", "contract_address_base58", "contract_address_hex", "min_deposit_amount", "min_withdraw_amount", "collect_threshold", "gas_strategy", "confirmation_required") VALUES (75, 'TRON', 'USDT', 'TRC20', 'TXYZopYRdj2D9XRtbG411XZZ3kM5VkAeBf', 6, true, 0.000001000000000000, 0.000001000000000000, true, '2026-06-21 00:25:35.727551+08', '2026-06-21 00:28:58.580102+08', 'NILE', 'TRC20', 'TXYZopYRdj2D9XRtbG411XZZ3kM5VkAeBf', '41eca9bc828a3005b9a3b909f2cc5c2a54794de05f', 0.000001000000000000, 0.000001000000000000, 1.000000000000000000, 'energy-bandwidth', 1);
INSERT INTO "public"."token_config" ("id", "chain", "symbol", "standard", "contract_address", "decimals", "enabled", "min_deposit", "min_withdraw", "collect_enabled", "created_at", "updated_at", "network", "token_standard", "contract_address_base58", "contract_address_hex", "min_deposit_amount", "min_withdraw_amount", "collect_threshold", "gas_strategy", "confirmation_required") VALUES (78, 'SOLANA', 'USDT', 'SPL', '2VHdVaW1jBGmvU1PdQpvmPnWV5AiAxrmxmUTLvf8951y', 6, true, 1.000000000000000000, 1.000000000000000000, true, '2026-06-23 15:01:18.893717+08', '2026-06-23 15:02:47.901793+08', 'devnet', 'SPL', '2VHdVaW1jBGmvU1PdQpvmPnWV5AiAxrmxmUTLvf8951y', NULL, 1.000000000000000000, 1.000000000000000000, 1.000000000000000000, 'SOL_FEE_PAYER', 1);
INSERT INTO "public"."token_config" ("id", "chain", "symbol", "standard", "contract_address", "decimals", "enabled", "min_deposit", "min_withdraw", "collect_enabled", "created_at", "updated_at", "network", "token_standard", "contract_address_base58", "contract_address_hex", "min_deposit_amount", "min_withdraw_amount", "collect_threshold", "gas_strategy", "confirmation_required") VALUES (79, 'SOLANA', 'USDC', 'SPL', 'FUez5CPP3C4VN7JGkgKxamC8f4cvt397R3unqUX6tekt', 6, true, 1.000000000000000000, 1.000000000000000000, true, '2026-06-23 15:01:43.398652+08', '2026-06-23 15:03:11.78696+08', 'devnet', 'SPL', 'FUez5CPP3C4VN7JGkgKxamC8f4cvt397R3unqUX6tekt', NULL, 1.000000000000000000, 1.000000000000000000, 1.000000000000000000, 'SOL_FEE_PAYER', 1);
INSERT INTO "public"."token_config" ("id", "chain", "symbol", "standard", "contract_address", "decimals", "enabled", "min_deposit", "min_withdraw", "collect_enabled", "created_at", "updated_at", "network", "token_standard", "contract_address_base58", "contract_address_hex", "min_deposit_amount", "min_withdraw_amount", "collect_threshold", "gas_strategy", "confirmation_required") VALUES (2, 'ETH', 'USDC', 'ERC20', '0x9478eC397A2F4Be6A84916dD8a353c91b78c6238', 6, true, 0.000000000000000000, 0.000000000000000000, true, '2026-06-20 18:23:33.151844+08', '2026-06-24 17:27:12.473627+08', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL);


--
-- Data for Name: token_registry; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Name: chain_asset_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('"public"."chain_asset_id_seq"', 135, true);


--
-- Name: chain_profile_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('"public"."chain_profile_id_seq"', 136, true);


--
-- Name: token_config_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('"public"."token_config_id_seq"', 105, true);


--
-- Name: token_registry_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('"public"."token_registry_id_seq"', 1, false);


--
-- PostgreSQL database dump complete
--

\unrestrict bjqNN0t14eWj4c7qSXOJEQUjjYyrWOceDTxgF24JoezZIbQkg44OuC1vcHug53l

