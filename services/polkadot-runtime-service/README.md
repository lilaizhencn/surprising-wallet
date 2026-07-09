# Polkadot Runtime Service

This service is the metadata-aware runtime companion for the Java wallet.
It uses the official Polkadot.js API to read runtime metadata, scan finalized
transfer events, construct balances/assets transfers, sign Ed25519
transactions, submit them, and wait for finality.

Run locally:

```bash
npm install
POLKADOT_RUNTIME_API_KEY=change-me npm start
```

Run with Docker:

```bash
export POLKADOT_RUNTIME_API_KEY=change-me
docker compose up -d --build
```

Run with plain Docker, including hosts without Docker Compose:

```bash
export POLKADOT_RUNTIME_API_KEY=change-me
scripts/regtest/polkadot-runtime-service.sh up
```

The Java service calls this service through `chain_rpc_node` rows with
`chain=DOT`, `purpose=runtime`, and a private/internal `rpc_url`, for example
`http://127.0.0.1:8787`.

For a split test2 deployment where PostgreSQL/Redis and this runtime service run
on a Linux host and `wallet-server` runs on another host, bind the service to the
Linux private IP and store that private URL in `chain_rpc_node.rpc_url`:

```bash
export POLKADOT_RUNTIME_API_KEY=change-me
export POLKADOT_RUNTIME_BIND_HOST=172.31.x.x
scripts/regtest/polkadot-runtime-service.sh up
```

Then set the DOT runtime row to `http://172.31.x.x:8787`. The private network or
security group must allow the wallet-server host to reach port `8787`. If that
port is not open yet, keep the runtime row at `http://127.0.0.1:8787` and run a
managed SSH tunnel from the wallet-server host to the Linux host until the
private port is available.

Production notes:

- Bind this service only to localhost or a private network interface.
- Set `POLKADOT_RUNTIME_API_KEY` and store the same key in
  `chain_rpc_node.api_key` with `auth_type=bearer`.
- Configure a separate `chain_rpc_node` row with `purpose=rpc` pointing at a
  WebSocket node such as Westend or Polkadot mainnet.
- Configure another `chain_rpc_node` row with `purpose=asset_rpc` pointing at
  the matching Asset Hub WebSocket endpoint before enabling DOT token assets.
  For Westend token tests, use
  `wss://westend-asset-hub-rpc.polkadot.io`.
- `POLKADOT_RUNTIME_CONNECT_TIMEOUT_MS` defaults to `20000`; keep a timeout so
  one bad WebSocket node does not pin the runtime service.
- Do not enable the DOT chain profile until deposit, withdrawal, collection,
  and any selected Asset Hub token have passed end-to-end tests.
