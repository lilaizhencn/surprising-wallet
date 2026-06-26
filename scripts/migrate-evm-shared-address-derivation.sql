-- Migrate existing EVM address rows to the shared ETH derivation path.
--
-- The wallet now derives all EVM-family chains with coin type 60:
-- m/44/60/{biz}/{user_id}/{address_index}. Existing rows created with
-- BNB=714, POLYGON=966 or AVAX_C=9000 should be aligned with the canonical
-- ETH row for the same user/biz/index/role when that row already exists.
--
-- Review deposits before applying this in a funded environment. Historical
-- old addresses may still need to be monitored if they have been exposed.

update chain_profile
   set bip44_coin_type = 60,
       updated_at = now()
 where family = 'evm'
   and chain in ('BNB', 'POLYGON', 'AVAX_C')
   and bip44_coin_type <> 60;

update chain_address target
   set account_id = canonical.account_id,
       address = canonical.address,
       owner_address = canonical.owner_address,
       derivation_path = canonical.derivation_path,
       updated_at = now()
  from chain_address canonical
 where target.chain in ('BNB', 'POLYGON', 'AVAX_C')
   and target.wallet_role = canonical.wallet_role
   and target.user_id = canonical.user_id
   and target.biz = canonical.biz
   and target.address_index = canonical.address_index
   and canonical.chain = 'ETH'
   and canonical.asset_symbol = 'ETH'
   and (
       lower(target.address) <> lower(canonical.address)
       or target.derivation_path is distinct from canonical.derivation_path
   );
