# TRON Nile Live Flow Report

Generated: 2026-06-21 Asia/Shanghai.

## Result

- Network: Nile.
- Source/faucet address: `TB1x9vmH5SbBd1EUaUePGbZzqmXGosFtxK`.
- Nile source balance after test: about `1958.81 TRX` and `920 USDT`.
- TRX live flow: passed.
- TRC20 USDT live flow: passed.
- TRC20 USDC live flow: blocked; Nile faucet funding provided TRX and USDT only, and no controlled mock USDC contract has been deployed through the wallet test path yet.
- WAITING_GAS and gas top-up: passed.
- Deposit idempotency: passed.
- Ledger reconciliation: passed for tested user/hot addresses.
- Final commit gate: not satisfied because USDC TRC20 remains blocked; no commit was created.

## Addresses

- User A: `TBkS7rt9FDS9mAD5RS4x51vAsJ4SiLbAxo`.
- User B: `TLdj9WNB7LZWTterdx2RoPdTMfdoGLFHMq`.
- User C: `TT1AnaepAL2igHQ3vrXRMskx4zFjFi1AS8`.
- Hot wallet: `TV3HP5HGjzS6oJPRfBqWNFjLXgm1ghqKYB`.
- External test recipient: `TBohehaoZRqUasyVX3WgK6VuNgqq4sVPUM`.

## Token Config

- Symbol: `USDT`.
- Standard: `TRC20`.
- Contract base58: `TXYZopYRdj2D9XRtbG411XZZ3kM5VkAeBf`.
- Contract hex: `41eca9bc828a3005b9a3b909f2cc5c2a54794de05f`.
- Decimals: `6`.
- Network: `NILE`.

## Transactions

- TRX deposit: `3ada2e6ebc97d206fcf08e332d4c30699d9ea9035d91cb852bf1e5a49b7ad80c`.
- TRX withdraw 1: `6767933aac18c26d14a1e0f07047897bf30dd0e1ac0cd38bef8de0142cad27b4`.
- TRX withdraw 2: `6cf387ca07f3fb05a5ba570d92ec1f066de80ac796f43536795ed0b318cbf985`.
- USDT deposit to User B: `e2b7583e00d7f097e6d5c98c4bf22850b6760b8bdc36aeee716c413a9f4a33ee`.
- USDT deposit to User C: `9895fcd268408d94f28ab23b0f86f7c1b5cfb8efeba44ab37a3f7ac3a3685ede`.
- Gas top-up to User B: `d6189681b50f23199df6845f592bfc01303a58b099d2631166a53816f9bf93f0`.
- USDT collection to hot wallet: `8b7d473180bb4584ccc59cf2244bd9c35a4a4f9c4b93ca5588a237e65007a43d`.
- Gas top-up to User C: `610111a7398087fa3be86a93ebd2f06bd398149daf0eb6f19053a83d37266ca2`.
- USDT withdraw: `c5b2a2ad1d4cfed63c884320c89a34e6dbd69cd24d176828e5a6195408c3f271`.

## Ledger State

- User A TRX: `2.4`, locked `0`.
- User B USDT: `0`, locked `0`.
- User B TRX gas balance: `10`, locked `0`.
- User C USDT: `15`, locked `0`.
- User C TRX gas balance: `10`, locked `0`.
- Hot wallet USDT: `30`, locked `0`.
- Negative ledger rows for tested addresses: `0`.

## Scanner And Idempotency

- TRX scanner parsed real `TransferContract` from block `68491755`.
- TRC20 scanner parsed real `TransactionInfo.log` events.
- Scanner bug fixed: Nile returns TRC20 log contract address as 20 bytes, so scanner now prepends `41` before token_config matching.
- Duplicate scans were executed for TRX and TRC20 deposits; ledger balances did not double-credit.

## Gas And Energy

- Estimated TRC20 collection energy: `37063`.
- Estimated TRC20 fee from policy: `15.566460 TRX`.
- Gas top-up amount produced by `TronGasPolicy.nileDefault()`: `10 TRX` per gas-needing address.
- Actual tested TRC20 fee was `0 TRX` on Nile for the tested transactions, while energy usage was still estimated and validated.

## Test Commands

```bash
mvn -q -pl backendservices/wallet-parent/wallet-service \
  -Dtest=com.surprising.wallet.service.chain.tron.TronLiveFullFlowIntegrationTest \
  -Dtron.live.flow.enabled=true \
  test
```

```bash
mvn -q -pl backendservices/wallet-parent/wallet-service \
  -Dtest=TronAddressGenerationTest,TronTridentKeyFactoryTest,TronAddressCodecTest \
  test
```

```bash
mvn -q -pl backendservices/wallet-parent/wallet-service \
  -Dtest=TronTrxDepositScanIntegrationTest,TronTrxWithdrawIntegrationTest \
  test
```

```bash
mvn -q -pl backendservices/wallet-parent/wallet-service \
  -Dtest=Trc20DepositScanIntegrationTest,Trc20WithdrawIntegrationTest,Trc20CollectionIntegrationTest \
  test
```

```bash
mvn -q -pl backendservices/wallet-parent/wallet-service \
  -Dtest=TronGasTopupIntegrationTest,TronWaitingGasStateTest,TronLedgerIdempotencyTest \
  test
```

```bash
mvn -q clean install -DskipTests=false
```

## Final Gate Verification

- Verified at: `2026-06-21 00:42:00 +0800`.
- Full Maven reactor build: passed with exit code `0`.
- Surefire summary after full build: `57` tests, `0` failures, `0` errors, `11` skipped.
- Redis: `PONG`.
- PostgreSQL: `select 1` succeeded.
- `wallet-server --spring.profiles.active=test`: started on `8002`; `/actuator/health` returned `UP`; PostgreSQL Hikari pool initialized.
- `wallet-sig1 --spring.profiles.active=test`: started on `8004`; first-sign job started.
- `wallet-sig2 --spring.profiles.active=test`: non-web signing process started; second-sign job started.
- Startup risk note: `wallet-sig2` printed the existing runtime message `第二次签名服务校验钱包环境 没有初始化`. The process stayed running, but this remains a runtime environment warning to investigate before production.
- Commit hash: not created.
- Push: no.
