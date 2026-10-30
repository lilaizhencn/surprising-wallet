-- One-time custody upgrade. Stop new submissions and signer jobs before applying.
-- This preserves old state; it never declares unknown broadcasts failed or principal released.
BEGIN;
LOCK TABLE custody_idempotency_key,custody_withdrawal,custody_address,ledger_balance,withdrawal_order,collection_record,custody_asset_recovery IN ACCESS EXCLUSIVE MODE;
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM custody_withdrawal WHERE external_reference IS NOT NULL
             GROUP BY tenant_id,external_reference HAVING count(*)>1) THEN
    RAISE EXCEPTION 'duplicate custody withdrawal references require manual reconciliation before upgrade';
  END IF;
END $$;
UPDATE custody_idempotency_key SET expires_at=NULL WHERE operation='WITHDRAWAL.CREATE';
ALTER TABLE custody_idempotency_key ADD CONSTRAINT custody_withdrawal_receipt_permanent
  CHECK(operation<>'WITHDRAWAL.CREATE' OR expires_at IS NULL);
ALTER TABLE withdrawal_order ADD COLUMN durable_submission boolean NOT NULL DEFAULT false,
  ADD COLUMN funds_released boolean NOT NULL DEFAULT false;
ALTER TABLE collection_record ADD COLUMN durable_submission boolean NOT NULL DEFAULT false;
ALTER TABLE custody_asset_recovery ADD COLUMN durable_submission boolean NOT NULL DEFAULT false;
CREATE UNIQUE INDEX custody_withdrawal_external_reference_key ON custody_withdrawal(tenant_id,external_reference)
  WHERE external_reference IS NOT NULL;
ALTER TABLE custody_withdrawal ADD COLUMN subject varchar(160), ADD COLUMN funding_source varchar(24) NOT NULL DEFAULT 'ADDRESS';
UPDATE custody_withdrawal withdrawal SET subject=address.subject FROM custody_address address WHERE address.id=withdrawal.custody_address_id AND address.tenant_id=withdrawal.tenant_id;
ALTER TABLE custody_withdrawal ALTER COLUMN subject SET NOT NULL, ALTER COLUMN funding_source DROP DEFAULT,
  ADD CONSTRAINT custody_withdrawal_funding_source_check CHECK(funding_source IN ('ADDRESS','TENANT_POOL'));
CREATE INDEX ledger_balance_pool_sources_idx ON ledger_balance(tenant_id,chain,asset_symbol,available_balance DESC,id) WHERE available_balance>0;
CREATE INDEX custody_withdrawal_subject_idx ON custody_withdrawal(tenant_id,subject,created_at DESC,id);
COMMENT ON COLUMN custody_withdrawal.subject IS '实际提现客户标识；旧行保留原提现客户事实';
COMMENT ON COLUMN custody_withdrawal.funding_source IS '本金来源范围 ADDRESS 或 TENANT_POOL';
COMMIT;
-- Missing old receipts remain unresolved; restore verified evidence rather than inventing a refusal.
SELECT count(*) AS accepted_orders_missing_permanent_receipt
FROM custody_withdrawal w WHERE w.idempotency_key IS NOT NULL AND NOT EXISTS (
  SELECT 1 FROM custody_idempotency_key k WHERE k.tenant_id=w.tenant_id
    AND k.idempotency_key=w.idempotency_key AND k.operation='WITHDRAWAL.CREATE'
    AND k.response_status=202 AND k.response_body IS NOT NULL);
