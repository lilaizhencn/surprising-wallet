# Surprising Wallet

Surprising Wallet is a multi-tenant blockchain custody platform for exchanges, payment providers, and digital-asset businesses. It provides tenant-isolated deposit addresses, chain monitoring, confirmations, ledger posting, withdrawals, collection, gas accounting, audit trails, and signed webhooks behind one operational control plane.

The project is built for controlled custody operations: PostgreSQL is the source of truth for balances and workflow state, Redis transports signing work, and every enabled chain and asset is controlled through database configuration.

## Supported Chains

The current codebase supports **62 networks across 14 integration groups**. The list below follows `common/.../ChainType.java`. Network activation, RPC endpoints, token configuration, and operational switches are database driven.

Icons are loaded from each project's official website favicon. Every icon and network name links to the official project website.

<table>
<thead>
<tr><th>Integration group</th><th>Supported networks</th></tr>
</thead>
<tbody>
<tr>
<td><strong>Bitcoin-like UTXO</strong></td>
<td><a href="https://bitcoin.org/"><img src="https://www.google.com/s2/favicons?domain=bitcoin.org&amp;sz=32" alt="BTC" width="20" height="20"></a> BTC &nbsp; <a href="https://litecoin.org/"><img src="https://www.google.com/s2/favicons?domain=litecoin.org&amp;sz=32" alt="LTC" width="20" height="20"></a> LTC &nbsp; <a href="https://dogecoin.com/"><img src="https://www.google.com/s2/favicons?domain=dogecoin.com&amp;sz=32" alt="DOGE" width="20" height="20"></a> DOGE &nbsp; <a href="https://bitcoincash.org/"><img src="https://www.google.com/s2/favicons?domain=bitcoincash.org&amp;sz=32" alt="BCH" width="20" height="20"></a> BCH</td>
</tr>
<tr>
<td><strong>EVM and EVM L2</strong></td>
<td><a href="https://ethereum.org/"><img src="https://www.google.com/s2/favicons?domain=ethereum.org&amp;sz=32" alt="ETH" width="20" height="20"></a> ETH &nbsp; <a href="https://www.bnbchain.org/"><img src="https://www.google.com/s2/favicons?domain=www.bnbchain.org&amp;sz=32" alt="BNB" width="20" height="20"></a> BNB &nbsp; <a href="https://polygon.technology/"><img src="https://www.google.com/s2/favicons?domain=polygon.technology&amp;sz=32" alt="Polygon" width="20" height="20"></a> Polygon &nbsp; <a href="https://arbitrum.io/"><img src="https://www.google.com/s2/favicons?domain=arbitrum.io&amp;sz=32" alt="Arbitrum" width="20" height="20"></a> Arbitrum &nbsp; <a href="https://www.optimism.io/"><img src="https://www.google.com/s2/favicons?domain=www.optimism.io&amp;sz=32" alt="Optimism" width="20" height="20"></a> Optimism &nbsp; <a href="https://base.org/"><img src="https://www.google.com/s2/favicons?domain=base.org&amp;sz=32" alt="Base" width="20" height="20"></a> Base &nbsp; <br> &nbsp; <a href="https://www.avax.network/"><img src="https://www.google.com/s2/favicons?domain=www.avax.network&amp;sz=32" alt="Avalanche C-Chain" width="20" height="20"></a> Avalanche C-Chain &nbsp; <a href="https://hyperliquid.xyz/"><img src="https://www.google.com/s2/favicons?domain=hyperliquid.xyz&amp;sz=32" alt="HyperEVM" width="20" height="20"></a> HyperEVM &nbsp; <a href="https://www.mantle.xyz/"><img src="https://www.google.com/s2/favicons?domain=www.mantle.xyz&amp;sz=32" alt="Mantle" width="20" height="20"></a> Mantle &nbsp; <a href="https://linea.build/"><img src="https://www.google.com/s2/favicons?domain=linea.build&amp;sz=32" alt="Linea" width="20" height="20"></a> Linea &nbsp; <a href="https://scroll.io/"><img src="https://www.google.com/s2/favicons?domain=scroll.io&amp;sz=32" alt="Scroll" width="20" height="20"></a> Scroll &nbsp; <a href="https://www.unichain.org/"><img src="https://www.google.com/s2/favicons?domain=www.unichain.org&amp;sz=32" alt="Unichain" width="20" height="20"></a> Unichain &nbsp; <br> &nbsp; <a href="https://zksync.io/"><img src="https://www.google.com/s2/favicons?domain=zksync.io&amp;sz=32" alt="zkSync Era" width="20" height="20"></a> zkSync Era &nbsp; <a href="https://www.berachain.com/"><img src="https://www.google.com/s2/favicons?domain=www.berachain.com&amp;sz=32" alt="Berachain" width="20" height="20"></a> Berachain &nbsp; <a href="https://www.gnosis.io/"><img src="https://www.google.com/s2/favicons?domain=www.gnosis.io&amp;sz=32" alt="Gnosis" width="20" height="20"></a> Gnosis &nbsp; <a href="https://celo.org/"><img src="https://www.google.com/s2/favicons?domain=celo.org&amp;sz=32" alt="Celo" width="20" height="20"></a> Celo &nbsp; <a href="https://monad.xyz/"><img src="https://www.google.com/s2/favicons?domain=monad.xyz&amp;sz=32" alt="Monad" width="20" height="20"></a> Monad &nbsp; <a href="https://world.org/"><img src="https://www.google.com/s2/favicons?domain=world.org&amp;sz=32" alt="World Chain" width="20" height="20"></a> World Chain &nbsp; <br> &nbsp; <a href="https://inkonchain.com/"><img src="https://www.google.com/s2/favicons?domain=inkonchain.com&amp;sz=32" alt="Ink" width="20" height="20"></a> Ink &nbsp; <a href="https://soneium.org/"><img src="https://www.google.com/s2/favicons?domain=soneium.org&amp;sz=32" alt="Soneium" width="20" height="20"></a> Soneium &nbsp; <a href="https://katana.network/"><img src="https://www.google.com/s2/favicons?domain=katana.network&amp;sz=32" alt="Katana" width="20" height="20"></a> Katana &nbsp; <br> &nbsp; <a href="https://megaeth.com/"><img src="https://www.google.com/s2/favicons?domain=megaeth.com&amp;sz=32" alt="MegaETH" width="20" height="20"></a> MegaETH &nbsp; <a href="https://www.xlayer.tech/"><img src="https://www.google.com/s2/favicons?domain=www.xlayer.tech&amp;sz=32" alt="X Layer" width="20" height="20"></a> X Layer &nbsp; <a href="https://docs.robinhood.com/"><img src="https://www.google.com/s2/favicons?domain=docs.robinhood.com&amp;sz=32" alt="Robinhood Chain" width="20" height="20"></a> Robinhood Chain &nbsp; <a href="https://www.etherlink.com/"><img src="https://www.google.com/s2/favicons?domain=www.etherlink.com&amp;sz=32" alt="Etherlink" width="20" height="20"></a> Etherlink &nbsp; <br> &nbsp; <a href="https://cronos.org/"><img src="https://www.google.com/s2/favicons?domain=cronos.org&amp;sz=32" alt="Cronos" width="20" height="20"></a> Cronos &nbsp; <a href="https://www.soniclabs.com/"><img src="https://www.google.com/s2/favicons?domain=www.soniclabs.com&amp;sz=32" alt="Sonic" width="20" height="20"></a> Sonic &nbsp; <a href="https://pulsechain.com/"><img src="https://www.google.com/s2/favicons?domain=pulsechain.com&amp;sz=32" alt="PulseChain" width="20" height="20"></a> PulseChain &nbsp; <a href="https://somnia.network/"><img src="https://www.google.com/s2/favicons?domain=somnia.network&amp;sz=32" alt="Somnia" width="20" height="20"></a> Somnia &nbsp; <br> &nbsp; <a href="https://roninchain.com/"><img src="https://www.google.com/s2/favicons?domain=roninchain.com&amp;sz=32" alt="Ronin" width="20" height="20"></a> Ronin &nbsp; <a href="https://kaia.io/"><img src="https://www.google.com/s2/favicons?domain=kaia.io&amp;sz=32" alt="Kaia" width="20" height="20"></a> Kaia &nbsp; <a href="https://plasma.to/"><img src="https://www.google.com/s2/favicons?domain=plasma.to&amp;sz=32" alt="Plasma" width="20" height="20"></a> Plasma &nbsp; <br> &nbsp; <a href="https://www.sei.io/"><img src="https://www.google.com/s2/favicons?domain=www.sei.io&amp;sz=32" alt="Sei" width="20" height="20"></a> Sei &nbsp; </td>
</tr>
<tr>
<td><strong>Starknet</strong></td>
<td><a href="https://www.starknet.io/"><img src="https://www.google.com/s2/favicons?domain=www.starknet.io&amp;sz=32" alt="Starknet" width="20" height="20"></a> Starknet</td>
</tr>
<tr>
<td><strong>Hyperliquid</strong></td>
<td><a href="https://hyperliquid.xyz/"><img src="https://www.google.com/s2/favicons?domain=hyperliquid.xyz&amp;sz=32" alt="HyperCore" width="20" height="20"></a> HyperCore</td>
</tr>
<tr>
<td><strong>TRON</strong></td>
<td><a href="https://tron.network/"><img src="https://www.google.com/s2/favicons?domain=tron.network&amp;sz=32" alt="TRON" width="20" height="20"></a> TRON</td>
</tr>
<tr>
<td><strong>XRP Ledger</strong></td>
<td><a href="https://xrpl.org/"><img src="https://www.google.com/s2/favicons?domain=xrpl.org&amp;sz=32" alt="XRP" width="20" height="20"></a> XRP</td>
</tr>
<tr>
<td><strong>Solana</strong></td>
<td><a href="https://solana.com/"><img src="https://www.google.com/s2/favicons?domain=solana.com&amp;sz=32" alt="Solana" width="20" height="20"></a> Solana</td>
</tr>
<tr>
<td><strong>TON</strong></td>
<td><a href="https://ton.org/"><img src="https://www.google.com/s2/favicons?domain=ton.org&amp;sz=32" alt="TON" width="20" height="20"></a> TON</td>
</tr>
<tr>
<td><strong>Aptos</strong></td>
<td><a href="https://aptosfoundation.org/"><img src="https://www.google.com/s2/favicons?domain=aptosfoundation.org&amp;sz=32" alt="Aptos" width="20" height="20"></a> Aptos</td>
</tr>
<tr>
<td><strong>Sui</strong></td>
<td><a href="https://sui.io/"><img src="https://www.google.com/s2/favicons?domain=sui.io&amp;sz=32" alt="Sui" width="20" height="20"></a> Sui</td>
</tr>
<tr>
<td><strong>Cardano</strong></td>
<td><a href="https://cardano.org/"><img src="https://www.google.com/s2/favicons?domain=cardano.org&amp;sz=32" alt="Cardano" width="20" height="20"></a> Cardano</td>
</tr>
<tr>
<td><strong>Polkadot</strong></td>
<td><a href="https://polkadot.com/"><img src="https://www.google.com/s2/favicons?domain=polkadot.com&amp;sz=32" alt="Polkadot" width="20" height="20"></a> Polkadot</td>
</tr>
<tr>
<td><strong>NEAR</strong></td>
<td><a href="https://near.org/"><img src="https://www.google.com/s2/favicons?domain=near.org&amp;sz=32" alt="NEAR" width="20" height="20"></a> NEAR</td>
</tr>
<tr>
<td><strong>Monero</strong></td>
<td><a href="https://www.getmonero.org/"><img src="https://www.google.com/s2/favicons?domain=www.getmonero.org&amp;sz=32" alt="Monero" width="20" height="20"></a> Monero</td>
</tr>
</tbody>
</table>

## Architecture

```mermaid
flowchart LR
    Tenant[Tenants / Console / API clients] --> API[wallet-api<br/>Spring MVC control plane]
    API --> Services[Application services<br/>workflows · jobs · state transitions]
    Services --> Adapters[Chain adapters<br/>RPC · scanners · signing · broadcast]
    Adapters --> Networks[(Supported networks)]
    API --> PostgreSQL[(PostgreSQL<br/>ledger · custody state · leases · outbox · audit)]
    API --> Redis[(Redis<br/>signing transport)]
    API --> Sig1[wallet-sig1<br/>first signature]
    Sig1 --> Sig2[wallet-sig2<br/>final signature and broadcast]
    Sig2 --> Redis
    Common[common] --> API
    Common --> Sig1
    Common --> Sig2
    SDKs[chain-sdks] --> API
    SDKs --> Sig1
    SDKs --> Sig2
```

| Module | Responsibility |
|---|---|
| `wallet-api` | Spring MVC API and console, scheduled jobs, custody workflows, chain adapters, repositories, gas accounting, webhooks, and startup validation. |
| `wallet-sig1` | First signature service for Bitcoin-like withdrawal transactions. |
| `wallet-sig2` | Final signature, rebroadcast, and broadcast service for Bitcoin-like, EVM, and TRON transactions. |
| `common` | Shared chain and asset contracts, signing DTOs, wallet key configuration, and cross-module infrastructure. |
| `chain-sdks` | Bitcoin-like, TRON, RPC, UTXO, BIP32, Ed25519, and Protobuf based SDK components. |

The dependency direction is `wallet-api → common, chain-sdks` and `wallet-sig1/wallet-sig2 → common, chain-sdks`. Inside `wallet-api`, controllers and jobs handle boundaries and scheduling; services own workflows; chain, gateway, coordinator, observer, repository, and configuration packages implement the domain and infrastructure layers. Each SQL repository is responsible for one table.

## Deployment

### Requirements

- JDK 25
- Maven 3.9+
- PostgreSQL 18
- Redis 7+
- Node.js only for the Polkadot runtime bridge and selected test infrastructure

### Local development or a disposable test database

The repository has one canonical database file. It is destructive by design and must be used only with a new or disposable database.

```bash
createdb wallet
psql -U wallet -d wallet -f resources/docs/db/surprising-wallet-init-pgsql.sql

mvn -DskipTests package

java -jar wallet-api/target/wallet-api-1.0.0-SNAPSHOT.jar
java -jar wallet-sig1/target/wallet-sig1-1.0.0-SNAPSHOT.jar
java -jar wallet-sig2/target/wallet-sig2-1.0.0-SNAPSHOT.jar
```

Set the database, Redis, custody master key, platform administrator, wallet key, CORS, and chain RPC environment variables before starting the services. Do not put private keys, seed phrases, production credentials, or RPC secrets in Git.

### Linux systemd deployment

The repository includes service units for the API and both signing services under `resources/infra/systemd/`.

1. Provision PostgreSQL and Redis.
2. Initialize a new database with `resources/docs/db/surprising-wallet-init-pgsql.sql`.
3. Install the environment file at `/etc/surprising-wallet/wallet.env`.
4. Install the three systemd units and enable them.
5. Start or restart `surprising-wallet.service`, `surprising-wallet-sig1.service`, and `surprising-wallet-sig2.service`.

The service units run as the unprivileged `wallet` user. The API health endpoint is `/actuator/health`.

### Automated backend deployment

`.github/workflows/deploy-backend-dev.yml` triggers the server-side deployment through a restricted SSH command. `scripts/deploy/backend-deploy.sh` fetches the selected branch, builds the three JARs with JDK 25, stages an immutable release, updates the systemd units, restarts the services, checks health, and rolls back the release when verification fails.

The canonical initialization SQL is never applied automatically to an existing deployment. Apply it manually only when provisioning a new disposable database.
