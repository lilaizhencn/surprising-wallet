#!/usr/bin/env python3
"""SSH-only live regtest acceptance. Uses existing services/databases and test accounts.

Requires explicit --run, an empty regtest mempool, and a checkpoint path. Creates
six test deposits, submits duplicate RBF requests, mines blocks, and checks wallet,
Exchange, Bitcoin Core and webhook invariants. No credentials are written.
"""
import argparse
import concurrent.futures
from decimal import Decimal
import json
from pathlib import Path
import shlex
import subprocess
import time
import tempfile

SSH_CONTROL = tempfile.TemporaryDirectory(prefix="surprising-btc-rbf-ssh-", dir="/tmp")


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def run(host, command, attempts=1):
    for attempt in range(attempts):
        try:
            result = subprocess.run(["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10",
                                     "-o", "ControlMaster=auto", "-o", "ControlPersist=60",
                                     "-o", "ControlPath=" + SSH_CONTROL.name + "/%C", host, command],
                                    capture_output=True, text=True, timeout=45)
            if result.returncode == 0:
                return result.stdout.strip()
            error = result.stderr[-1000:]
        except subprocess.TimeoutExpired:
            error = "SSH command timed out"
        if attempt + 1 < attempts:
            time.sleep(2)
    raise RuntimeError(f"SSH operation failed on {host}: {error}")


def decode(value):
    return json.loads(value, parse_float=Decimal)


def sql(host, database, query):
    command = "sudo -u postgres psql -h /run/postgresql -v ON_ERROR_STOP=1 -At -d " + shlex.quote(database)
    # Queue sends are deliberately fenced duplicates; other SQL here is read-only.
    return run(host, command + " -c " + shlex.quote(query), attempts=3)


def rows(host, database, query):
    return decode(sql(host, database, "select coalesce(json_agg(t), '[]'::json) from (" + query + ") t"))


def literal(value):
    return "'" + str(value).replace("'", "''") + "'"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", action="store_true", required=True)
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--resume", action="store_true", help="resume a reconciled funding-stage checkpoint")
    parser.add_argument("--wallet-ssh", default="aliyun-wallet")
    parser.add_argument("--node-ssh", default="root@47.76.68.12")
    parser.add_argument("--exchange-ssh", default="surprising-ex")
    parser.add_argument("--exchange-port", type=int, default=9194)
    parser.add_argument("--accounts", default="321:37,323:38,325:39", help="walletAccount:exchangeUser pairs")
    args = parser.parse_args()
    require(args.checkpoint.exists() == args.resume,
            "existing checkpoints require explicit --resume; new runs require a new checkpoint")
    pairs = [tuple(map(int, pair.split(":"))) for pair in args.accounts.split(",")]
    require(len(pairs) == 3 and len({a for a, _ in pairs}) == 3, "exactly three distinct test accounts required")
    state = decode(args.checkpoint.read_text()) if args.resume else {"startedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "accounts": pairs, "deposits": []}
    def save(stage):
        state["stage"] = stage
        args.checkpoint.write_text(json.dumps(state, indent=2, default=str) + "\n")
        print(stage, flush=True)
    def wallet(query):
        return rows(args.wallet_ssh, "surprising_wallet", query)
    def rpc(method, *parameters):
        output = run(args.node_ssh,
            "bitcoin-cli -regtest -conf=/etc/bitcoin/regtest.conf -datadir=/var/lib/bitcoin-regtest "
            "-rpcwallet=regtest-funder " + " ".join(shlex.quote(str(v)) for v in (method, *parameters)),
            attempts=3 if method.startswith("get") else 1)
        return output if method in ("sendtoaddress", "getnewaddress", "getblockhash") else decode(output)
    def balances():
        result = {}
        for _, user in pairs:
            url = f"http://127.0.0.1:{args.exchange_port}/api/v1/accounts/balance?userId={user}&asset=BTC"
            value = decode(run(args.exchange_ssh, "curl -fsS " + shlex.quote(url), attempts=3))
            result[str(user)] = {key: value[key] for key in ("availableUnits", "lockedUnits", "equityUnits")}
        return result
    def ledgers():
        return wallet("select tenant_id,account_id,available_balance,locked_balance,total_balance "
                      "from ledger_balance where chain='BTC' and asset_symbol='BTC' order by tenant_id,account_id")
    def webhooks():
        return rows(args.exchange_ssh, "surprising_exchange",
                    "select event_id,event_type,status from gateway_wallet_webhook_events order by event_id")
    def amount(value):
        return Decimal(str(value))
    def total():
        return amount(wallet("select coalesce(sum(amount),0) as amount from utxo_record where chain='BTC' "
                             "and state in ('AVAILABLE','LOCKED')")[0]["amount"])
    def wait_for(label, predicate, seconds=300):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            result = predicate()
            if result:
                return result
            time.sleep(5)
        raise RuntimeError("timed out: " + label)

    require(rpc("getblockchaininfo")["chain"] == "regtest", "refuse to fund a non-regtest chain")
    if args.resume:
        require(not state.get("originalCollections"), "only funding-stage checkpoints may be resumed automatically")
        require(state["accounts"] == [list(pair) for pair in pairs], "resume account mapping mismatch")
        require(set(rpc("getrawmempool")) == {d["txId"] for d in state["deposits"]},
                "resume requires reconciliation of every pending transaction")
    else:
        require(not rpc("getrawmempool"), "start requires an empty regtest mempool")
    profiles = wallet("select network from chain_profile where chain='BTC' and enabled=true")
    require(profiles and all(p["network"] == "regtest" for p in profiles), "wallet BTC must use regtest")
    accounts = ",".join(literal(a) for a, _ in pairs)
    sources = wallet("select tenant_id,account_id,address from chain_address where chain='BTC' "
                     f"and wallet_role='DEPOSIT' and enabled=true and account_id in ({accounts}) order by account_id")
    require(len(sources) == 3, "missing unique test deposit addresses")
    require(len({s['tenant_id'] for s in sources}) == 1, "test accounts must belong to the same tenant")
    if args.resume:
        require(sources == state["sources"], "resume deposit address mapping changed")
    else:
        state["sources"] = sources
        state["beforeExchange"] = balances()
        state["beforeLedger"] = ledgers()
        state["beforeWebhooks"] = webhooks()
        state["beforeChainTotal"] = total()
        state["beforeCollectionId"] = wallet("select coalesce(max(id),0) as id from collection_record")[0]["id"]
        state["beforeHeight"] = rpc("getblockcount")
        save("baseline captured")
    for source in sources:
        sent = sum(d["address"] == source["address"] for d in state["deposits"])
        require(sent <= 2, "more than two deposits recorded for one source")
        for _ in range(2 - sent):
            txid = rpc("sendtoaddress", source["address"], "0.00050000")
            state["deposits"].append({"txId": txid, "address": source["address"], "account": source["account_id"]})
            save("deposits submitted " + str(len(state["deposits"])))
    mining_address = rpc("getnewaddress")
    rpc("generatetoaddress", 6, mining_address)
    hashes = ",".join(literal(d["txId"]) for d in state["deposits"])
    def credited():
        records = wallet(f"select tx_hash,status,credited from deposit_record where chain='BTC' and tx_hash in ({hashes})")
        if len(records) != 6 or not all(r["credited"] for r in records):
            return False
        current = balances()
        for _, user in pairs:
            before = state["beforeExchange"][str(user)]
            require(current[str(user)]["lockedUnits"] == before["lockedUnits"], "deposit changed Exchange locked balance")
            if current[str(user)]["availableUnits"] != before["availableUnits"] + 100000:
                return False
        return current
    state["creditedExchange"] = wait_for("six deposits credited to Exchange", credited)
    state["creditedLedger"] = ledgers()
    def original_collections():
        records = wallet(f"select id,collection_no,tenant_id,from_address,to_address,amount,fee,status,tx_hash "
                         f"from collection_record where chain='BTC' and id>{state['beforeCollectionId']} order by id")
        require(len(records) <= 3, "duplicate collection created")
        if len(records) != 3 or not all(r["status"] == "SENT" for r in records):
            return False
        return records
    originals = wait_for("three original sweeps broadcast", original_collections)
    state["originalCollections"] = originals
    collector = originals[0]["to_address"]
    require(all(r["to_address"] == collector for r in originals), "unexpected collector")
    state["collectorBeforeConfirmation"] = wallet("select coalesce(sum(amount),0) as amount from utxo_record "
        f"where chain='BTC' and address={literal(collector)} and state='AVAILABLE'")[0]["amount"]
    state["creditedWebhooks"] = webhooks()
    require(len(state["creditedWebhooks"]) == len(state["beforeWebhooks"]) + 6, "unexpected deposit webhook count")
    require(all(e["status"] == "PROCESSED" for e in state["creditedWebhooks"]), "unprocessed webhook")
    save("original sweeps pending; user deposits credited")
    def request(record):
        body = json.dumps({"transactionId": record["signingId"], "expectedTxId": record["tx_hash"]})
        headers = json.dumps({"tenant_id": record["tenant_id"]})
        sql(args.wallet_ssh, "surprising_wallet", f"select pgmq.send('wallet_rbf',{literal(body)}::jsonb,{literal(headers)}::jsonb)")
    for record in originals:
        signed = wallet("select id from chain_signing_transaction where chain='BTC' "
                        f"and business_type='COLLECTION' and business_no={literal(record['collection_no'])}")
        record["signingId"] = signed[0]["id"]
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        list(pool.map(request, [r for r in originals for _ in range(8)]))
    save("24 duplicate RBF requests submitted")
    def replacements():
        result = []
        for record in originals:
            current = wallet(f"select id,tx_id,status,signature::jsonb as signature from chain_signing_transaction where id={record['signingId']}")[0]
            if current["status"] != 2 or current["tx_id"] == record["tx_hash"]:
                return False
            require(len(current["signature"].get("rbfHistory", [])) == 1, "duplicate request caused repeated fee bump")
            result.append(current)
        return result
    replacements = wait_for("three RBF replacements broadcast", replacements)
    state["replacements"] = [{"id": r["id"], "txId": r["tx_id"], "feeRate": r["signature"]["feeRate"],
                              "feeSat": r["signature"]["fee"], "historyCount": len(r["signature"]["rbfHistory"])} for r in replacements]
    mempool = rpc("getrawmempool")
    require(len(mempool) == 3, "mempool has unexpected transactions")
    raw_transactions = []
    for record, replacement in zip(originals, replacements):
        require(record["tx_hash"] not in mempool, "original was not replaced")
        require(replacement["tx_id"] in mempool, "replacement missing from mempool")
        raw = rpc("getrawtransaction", replacement["tx_id"], "true")
        require(len(raw["vin"]) == 2 and len(raw["vout"]) == 1, "unexpected sweep inputs/outputs")
        expected_inputs = {d["txId"] for d in state["deposits"] if d["address"] == record["from_address"]}
        require({i["txid"] for i in raw["vin"]} == expected_inputs, "RBF changed original inputs")
        require(raw["vout"][0]["scriptPubKey"]["address"] == collector, "RBF changed collector")
        entry = rpc("getmempoolentry", replacement["tx_id"])
        fee = amount(entry["fees"]["base"])
        require(amount(raw["vout"][0]["value"]) + fee == Decimal("0.001"), "on-chain sweep conservation failed")
        require(fee * 100000000 == replacement["signature"]["fee"], "signed fee differs from node")
        raw_transactions.append({"txId": replacement["tx_id"], "inputCount": len(raw["vin"]),
                                 "output": raw["vout"][0]["value"], "fee": fee, "vsize": raw["vsize"]})
    state["nodeTransactions"] = raw_transactions
    require(ledgers() == state["creditedLedger"], "RBF modified wallet user/platform ledger")
    require(balances() == state["creditedExchange"], "RBF modified Exchange balance")
    save("node verified: replacements spend identical inputs; balances unchanged")
    rpc("generatetoaddress", 6, mining_address)
    def confirmed():
        records = wallet("select collection_no,amount,fee,status,tx_hash from collection_record "
                         f"where chain='BTC' and id>{state['beforeCollectionId']} order by id")
        return records if len(records) == 3 and all(r["status"] == "CONFIRMED" for r in records) else False
    state["confirmedCollections"] = wait_for("three replacements reach finality", confirmed)
    for replacement in replacements:
        require(rpc("getrawtransaction", replacement["tx_id"], "true").get("confirmations", 0) >= 6,
                "node finality is below six confirmations")
    state["finalExchange"] = balances(); state["finalLedger"] = ledgers(); state["finalWebhooks"] = webhooks()
    require(state["finalExchange"] == state["creditedExchange"], "collection changed Exchange user balances")
    require(state["finalLedger"] == state["creditedLedger"], "collection changed wallet/platform ledger")
    require(state["finalWebhooks"] == state["creditedWebhooks"], "collection generated extra webhook")
    inputs = wallet(f"select tx_hash,state,spent_tx_hash from utxo_record where chain='BTC' and tx_hash in ({hashes})")
    require(len(inputs) == 6 and all(i["state"] == "SPENT" for i in inputs), "inputs not all spent exactly once")
    require({i["spent_tx_hash"] for i in inputs} == {r["tx_id"] for r in replacements}, "wrong spending attempts recorded")
    fee_sum = sum(amount(r["fee"]) for r in state["confirmedCollections"])
    output_sum = sum(amount(r["amount"]) for r in state["confirmedCollections"])
    require(output_sum + fee_sum == Decimal("0.003"), "platform conservation failed")
    final_collector = amount(wallet("select coalesce(sum(amount),0) as amount from utxo_record "
        f"where chain='BTC' and address={literal(collector)} and state='AVAILABLE'")[0]["amount"])
    require(final_collector - amount(state["collectorBeforeConfirmation"]) == output_sum, "collector chain funds do not reconcile")
    require(total() == amount(state["beforeChainTotal"]) + Decimal("0.003") - fee_sum, "wallet chain assets do not reconcile")
    state["totalFee"] = fee_sum; state["totalOutput"] = output_sum; state["finalInputs"] = inputs
    state["finalHeight"] = rpc("getblockcount")
    require(not rpc("getrawmempool"), "mempool not empty after finality")
    save("PASS: finality, ledgers, collector, input ownership and webhook invariants")
    # Replay after finality and wait through another scheduler period.
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        list(pool.map(request, originals * 2))
    time.sleep(35)
    require(confirmed() == state["confirmedCollections"], "terminal collection changed on replay")
    require(ledgers() == state["finalLedger"] and balances() == state["finalExchange"], "replay changed balances")
    require(not rpc("getrawmempool"), "replay created another transaction")
    require(webhooks() == state["finalWebhooks"], "replay generated an extra webhook")
    ids = ",".join(literal(r["signingId"]) for r in originals)
    require(wallet("select count(*) as count from pgmq.q_wallet_rbf "
                   f"where message->>'transactionId' in ({ids})")[0]["count"] == 0,
            "RBF requests were not all acknowledged")
    new_hashes = ",".join(literal(r["tx_id"]) for r in replacements)
    require(wallet("select count(*) as count from deposit_record "
                   f"where chain='BTC' and tx_hash in ({new_hashes})")[0]["count"] == 0,
            "internal collection appeared as a deposit")
    require(wallet("select count(*) as count from collection_record where chain='BTC' "
                   f"and id>{state['beforeCollectionId']}")[0]["count"] == 3, "duplicate sweep after finality")
    save("PASS: confirmed RBF replay is idempotent")


if __name__ == "__main__":
    main()
