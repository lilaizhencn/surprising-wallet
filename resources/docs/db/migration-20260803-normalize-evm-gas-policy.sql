-- 统一 EVM 链的 gas_policy，运行时仅支持 legacy-gas-price 和 eip1559。
UPDATE public.chain_profile
   SET gas_policy = 'eip1559',
       updated_at = now()
 WHERE family = 'evm'
   AND gas_policy = 'eip1559-l2';
