BEGIN;

-- Keep development chain jobs limited to the two local nodes that are under test.
UPDATE public.chain_profile
SET enabled = (chain = 'BTC' AND network = 'regtest')
        OR (chain = 'ETH' AND network = 'devtest'),
    scan_enabled = (chain = 'BTC' AND network = 'regtest')
        OR (chain = 'ETH' AND network = 'devtest'),
    withdraw_enabled = (chain = 'BTC' AND network = 'regtest')
        OR (chain = 'ETH' AND network = 'devtest'),
    collection_enabled = (chain = 'BTC' AND network = 'regtest')
        OR (chain = 'ETH' AND network = 'devtest'),
    transfer_enabled = (chain = 'BTC' AND network = 'regtest')
        OR (chain = 'ETH' AND network = 'devtest'),
    updated_at = now();

-- Prevent code paths outside scheduled jobs from selecting external dev RPC nodes.
UPDATE public.chain_rpc_node
SET enabled = (chain = 'BTC' AND network = 'regtest')
        OR (chain = 'ETH' AND network = 'devtest'),
    updated_at = now()
WHERE environment = 'dev';

COMMIT;
