-- Protect token identities, registered hot-wallet identities, and their custody addresses.
-- Apply as the maintenance owner to an EXISTING Wallet database before deploying dependent code.
-- Review the target and take a backup. Earlier overwritten contracts cannot be reconstructed.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

DO $$
BEGIN
    IF current_setting('server_version_num')::integer < 170000
       OR to_regclass('public.token_config') IS NULL
       OR to_regclass('public.custody_hot_wallet') IS NULL
       OR to_regclass('public.custody_address') IS NULL THEN
        RAISE EXCEPTION 'expected an existing PostgreSQL 17+ Wallet database with hot-wallet tables';
    END IF;
END $$;

LOCK TABLE public.token_config, public.custody_hot_wallet, public.custody_address
    IN ACCESS EXCLUSIVE MODE;

-- A registered hot wallet makes its network's token identity historical evidence.
-- The common advisory lock serializes registration with token identity edits, including
-- the case where neither transaction can see the other's uncommitted row yet.
CREATE OR REPLACE FUNCTION public.custody_guard_hot_wallet_identity() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        PERFORM pg_advisory_xact_lock(hashtextextended('SW-HOT-WALLET-TOKEN-IDENTITY-V1', 0));
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'registered hot wallet identity cannot be deleted' USING ERRCODE='23514';
    END IF;
    IF (NEW.id,NEW.tenant_id,NEW.custody_address_id,NEW.mpc_key_id,NEW.chain,
        NEW.network,NEW.created_by,NEW.created_at) IS DISTINCT FROM
       (OLD.id,OLD.tenant_id,OLD.custody_address_id,OLD.mpc_key_id,OLD.chain,
        OLD.network,OLD.created_by,OLD.created_at) THEN
        RAISE EXCEPTION 'registered hot wallet identity cannot change' USING ERRCODE='23514';
    END IF;
    IF (OLD.status IN ('QUARANTINED','RETIRED') AND NEW.status<>OLD.status)
       OR (OLD.status='ACTIVE' AND NEW.status='STANDBY')
       OR (OLD.quarantine_at IS NOT NULL AND
           (NEW.quarantine_actor_id,NEW.quarantine_at,NEW.quarantine_reason) IS DISTINCT FROM
           (OLD.quarantine_actor_id,OLD.quarantine_at,OLD.quarantine_reason))
       OR (OLD.quarantine_fence_confirmed_at IS NOT NULL AND
           NEW.quarantine_fence_confirmed_at IS DISTINCT FROM OLD.quarantine_fence_confirmed_at)
       OR (OLD.status<>'QUARANTINED' AND NEW.quarantine_fence_confirmed_at IS NOT NULL) THEN
        RAISE EXCEPTION 'registered hot wallet quarantine and retirement cannot be reversed or rewritten'
            USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS custody_hot_wallet_identity_guard ON public.custody_hot_wallet;
CREATE TRIGGER custody_hot_wallet_identity_guard
    BEFORE INSERT OR UPDATE OR DELETE ON public.custody_hot_wallet
    FOR EACH ROW EXECUTE FUNCTION public.custody_guard_hot_wallet_identity();

-- The referenced address row must not be repointed while the hot-wallet row stays fixed.
CREATE OR REPLACE FUNCTION public.custody_guard_registered_hot_wallet_address() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF (NEW.id,NEW.tenant_id,NEW.chain_address_id,NEW.chain,NEW.network,NEW.address,
            NEW.memo,NEW.source,NEW.subject,NEW.derivation_subject,NEW.derivation_child,
            NEW.address_version,NEW.created_by,NEW.created_at) IS NOT DISTINCT FROM
           (OLD.id,OLD.tenant_id,OLD.chain_address_id,OLD.chain,OLD.network,OLD.address,
            OLD.memo,OLD.source,OLD.subject,OLD.derivation_subject,OLD.derivation_child,
            OLD.address_version,OLD.created_by,OLD.created_at) THEN
            RETURN NEW;
        END IF;
    END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended('SW-HOT-WALLET-TOKEN-IDENTITY-V1', 0));
    IF EXISTS (SELECT 1 FROM public.custody_hot_wallet WHERE custody_address_id=OLD.id) THEN
        RAISE EXCEPTION 'registered hot wallet address identity cannot change or be deleted'
            USING ERRCODE='23514';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS custody_registered_hot_wallet_address_guard ON public.custody_address;
CREATE TRIGGER custody_registered_hot_wallet_address_guard
    BEFORE UPDATE OR DELETE ON public.custody_address
    FOR EACH ROW EXECUTE FUNCTION public.custody_guard_registered_hot_wallet_address();

CREATE OR REPLACE FUNCTION public.custody_guard_token_identity_after_hot_wallet() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF (NEW.chain,NEW.network,NEW.symbol,NEW.standard,NEW.contract_address,
            NEW.contract_address_base58,NEW.contract_address_hex,NEW.decimals) IS NOT DISTINCT FROM
           (OLD.chain,OLD.network,OLD.symbol,OLD.standard,OLD.contract_address,
            OLD.contract_address_base58,OLD.contract_address_hex,OLD.decimals) THEN
            RETURN NEW;
        END IF;
    END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended('SW-HOT-WALLET-TOKEN-IDENTITY-V1', 0));
    IF EXISTS (SELECT 1 FROM public.custody_hot_wallet
                WHERE upper(chain)=upper(OLD.chain) AND lower(network)=lower(OLD.network)) THEN
        RAISE EXCEPTION 'token identity is locked after hot wallet registration'
            USING ERRCODE='23514';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS custody_token_identity_after_hot_wallet_guard ON public.token_config;
CREATE TRIGGER custody_token_identity_after_hot_wallet_guard
    BEFORE UPDATE OR DELETE ON public.token_config
    FOR EACH ROW EXECUTE FUNCTION public.custody_guard_token_identity_after_hot_wallet();

COMMIT;
