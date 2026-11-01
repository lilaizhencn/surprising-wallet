#!/usr/bin/env python3
"""Start the actual packaged API twice without wallet seeds, with real independent MPC holders."""
import hashlib
import io
import json
import os
import pathlib
import re
import secrets
import signal
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from node_test import Cluster, free_port, require


def interrupted(*_):
    raise KeyboardInterrupt()


def rpc(endpoint,method,params):
    request=urllib.request.Request(endpoint,data=json.dumps({"jsonrpc":"2.0","id":1,"method":method,"params":params}).encode(),headers={"Content-Type":"application/json"})
    result=json.load(urllib.request.urlopen(request,timeout=10))
    require("error" not in result,"official local NEAR status failed")
    return result["result"]


def psql(database,sql=None,source=None):
    require(re.fullmatch(r"surprising_wallet_test_mpc_startup_[a-z0-9_]+",database),"refusing a non-owned startup database")
    command=["psql","-h","127.0.0.1","-p","5432","-U",os.environ["SW_TEST_CUSTODY_DB_USERNAME"],"-d",database,"-X","-v","ON_ERROR_STOP=1","-Atq"]
    if source:command.extend(["-f",str(source)])
    env=os.environ.copy();env["PGPASSWORD"]=os.environ.get("SW_TEST_CUSTODY_DB_PASSWORD","")
    result=subprocess.run(command,input=sql,text=True,capture_output=True,env=env,timeout=45)
    if result.returncode!=0:
        saved=pathlib.Path("/tmp/surprising-wallet-mpc-seedless-db-last.log")
        saved.write_text(result.stderr);saved.chmod(0o600)
    require(result.returncode==0,"isolated startup database initialization failed")
    return result.stdout.strip()


def inspect_jar(jar):
    forbidden=["com/surprising/wallet/bootstrap/WalletMode", "com/surprising/wallet/bootstrap/RuntimeKeyConfiguration",
               "com/surprising/wallet/service/HotWalletAddressService", "com/surprising/wallet/service/AccountSecp256k1KeyService",
               "com/surprising/wallet/config/PubKeyConfig", "com/surprising/wallet/chain/evm/Evm7702BatchTransactionService",
               "com/surprising/wallet/chain/evm/Evm7702AuthorizationService", "com/surprising/wallet/chain/tron/TronTridentKeyFactory"]
    with zipfile.ZipFile(jar) as packed:
        names=packed.namelist()
        require("BOOT-INF/classes/com/surprising/wallet/WalletApiApplication.class" in names,"actual executable Spring Boot JAR required")
        require(not any("wallet-sig1" in n or "wallet-sig2" in n for n in names),"legacy signer module is still packaged")
        require(not any(any(bad in n for bad in forbidden) for n in names),"obsolete local wallet signing component is still packaged")
        legacy_names=["AptosKeyService","CardanoKeyService","NearKeyService","PolkadotKeyService","SolanaKeyService","SuiKeyService","TonKeyService","XrpKeyService","AptosTransactionSigner"]
        require(not any(n.endswith("/"+bad+".class") for n in names for bad in legacy_names),"old private-key bean survived clean packaging")
        configuration=packed.read("BOOT-INF/classes/application.yaml").decode()
        require("SW_WALLET_MODE" not in configuration and "SW_WALLET_SIG1_SEED" not in configuration and "SW_WALLET_ED25519_SEED" not in configuration,"packaged application still contains wallet seed/mode configuration")
        common=next(n for n in names if n.startswith("BOOT-INF/lib/common-") and n.endswith(".jar"))
        with zipfile.ZipFile(io.BytesIO(packed.read(common))) as dependency:
            require(not any(n.startswith("com/surprising/wallet/common/key/") for n in dependency.namelist()),"common still packages a wallet seed provider")


def main():
    require(len(sys.argv)==4,"usage: seedless_startup_test.py /path/to/native /path/to/wallet.jar /path/to/near-fixture.json")
    os.umask(0o077)
    signal.signal(signal.SIGTERM,interrupted)
    binary=str(pathlib.Path(sys.argv[1]).resolve(strict=True));jar=pathlib.Path(sys.argv[2]).resolve(strict=True)
    fixture=json.loads(pathlib.Path(sys.argv[3]).read_text())
    endpoint=urllib.parse.urlparse(fixture["endpoint"])
    require(endpoint.scheme=="http" and endpoint.hostname=="127.0.0.1","startup uses only the preserved isolated NEAR ledger")
    status=rpc(fixture["endpoint"],"status",[])
    require(status["chain_id"]=="mpc-localnet-2134" and status["genesis_hash"]==fixture["genesisHash"],"original NEAR ledger identity differs")
    url=os.environ["SW_TEST_CUSTODY_DB_URL"]
    require(url.startswith("jdbc:postgresql://127.0.0.1:5432/"),"startup requires the existing local PG18")
    database=url.rsplit("/",1)[1]
    require(psql(database,"show server_version_num;").startswith("18"),"startup PostgreSQL must be18")
    root=pathlib.Path(__file__).resolve().parents[4]
    inspect_jar(jar)
    psql(database,source=root/"resources/docs/db/surprising-wallet-init-pgsql.sql")
    safe_endpoint=fixture["endpoint"].replace("'","''")
    psql(database,"""
        update wallet_system_config set config_value='false' where config_key like 'global.%.enabled';
        update chain_profile set enabled=false,scan_enabled=false,withdraw_enabled=false,collection_enabled=false,transfer_enabled=false;
        update chain_profile set network='localnet',enabled=true where chain='NEAR' and network='testnet';
        update token_config set enabled=false;
        update chain_asset set active=false where native_asset=false;
        update evm_7702_config set status='DISABLED';
        insert into chain_rpc_node(chain,network,environment,node_label,purpose,rpc_url,min_request_interval_ms)
            values('NEAR','localnet','mpc-startup','seedless-official-near','rpc','"""+safe_endpoint+"""',0);
    """)
    require(psql(database,"select count(*) from chain_profile where enabled;")=="1","startup profile setup differs")
    with tempfile.TemporaryDirectory(prefix="surprising-wallet-mpc-seedless-") as temporary:
        private=pathlib.Path(temporary);cluster=Cluster(private,binary,"00000000-0000-0000-0000-000000000001")
        child=None
        try:
            env=os.environ.copy()
            for key in list(env):
                if key.startswith("SPRING_") or (key.startswith("SW_WALLET_") and ("SEED" in key or "PUBLIC_ROOT" in key)) or key in ("SW_WALLET_MODE","SW_ED25519_SEED","JAVA_TOOL_OPTIONS","JDK_JAVA_OPTIONS"):
                    env.pop(key)
            env.update({"SW_DB_URL":url,"SW_DB_USERNAME":env["SW_TEST_CUSTODY_DB_USERNAME"],"SW_DB_PASSWORD":env.get("SW_TEST_CUSTODY_DB_PASSWORD",""),
                        "SW_APP_ENV":"mpc-startup","SW_CUSTODY_SECRET_MASTER_KEY":secrets.token_hex(32),
                        "SW_MPC_CA_FILE":str(private/"ca.pem"),"SW_MPC_CLIENT_CERTIFICATE_FILE":str(private/"client.pem"),
                        "SW_MPC_CLIENT_PRIVATE_KEY_FILE":str(private/"client.key"),"SW_HTTP_PORT":str(free_port()),
                        "SPRING_CONFIG_LOCATION":"classpath:/application.yaml"})
            for i in range(3):
                env[f"SW_MPC_NODE_{i+1}_ID"]=cluster.identities[i]
                env[f"SW_MPC_NODE_{i+1}_URL"]=f"https://localhost:{cluster.ports[i]}"
            require(not any(k.startswith("SW_WALLET_") and "SEED" in k for k in env),"wallet seed remains in startup environment")
            rounds=[]
            for number in (1,2):
                log=private/f"api-{number}.log"
                with log.open("wb") as output:
                    child=subprocess.Popen(["java","-jar",str(jar)],env=env,cwd=private,stdout=output,stderr=subprocess.STDOUT)
                deadline=time.monotonic()+100;healthy=False
                while child.poll() is None and time.monotonic()<deadline:
                    try:
                        with urllib.request.urlopen(f"http://127.0.0.1:{env['SW_HTTP_PORT']}/actuator/health",timeout=2) as response:
                            health=json.load(response)
                        text=log.read_text(errors="replace")
                        if health.get("status")=="UP" and "wallet runtime config env=mpc-startup global all=false" in text:
                            healthy=True;break
                    except (OSError,urllib.error.URLError,ValueError):pass
                    time.sleep(.25)
                if not healthy:
                    saved=pathlib.Path("/tmp/surprising-wallet-mpc-seedless-startup-last.log");saved.write_bytes(log.read_bytes());saved.chmod(0o600)
                    raise AssertionError("actual seedless API did not become ready; protected log: "+str(saved))
                require(child.poll() is None,"application exited after startup validation")
                require(psql(database,"select count(*) from wallet_mpc_key;")=="0","startup created an unsolicited wallet key")
                child.terminate();child.wait(timeout=45)
                require(child.returncode in (0,143,-signal.SIGTERM),"API shutdown failed")
                rounds.append({"round":number,"health":"UP","startupValidationCompleted":True,"gracefulExit":child.returncode})
                child=None
                print(f"Seedless packaged API round{number}: actual HTTP health UP, startup checks complete, graceful shutdown.",flush=True)
            proof={"passed":True,"rounds":rounds,"walletSeedEnvironmentAbsent":True,"legacyClassesAbsent":True,"nativeHolders":3,
                   "jarSha256":hashlib.sha256(jar.read_bytes()).hexdigest(),"genesisHash":fixture["genesisHash"],"unsolicitedKeys":0,
                   "scope":"Real packaged API JVM and real native holders; chain tasks disabled. Startup and wiring, not chain-transfer acceptance or backup restore."}
            pathlib.Path(os.environ.get("MPC_SEEDLESS_STARTUP_PROOF","/tmp/surprising-wallet-mpc-seedless-startup-public.json")).write_text(json.dumps(proof))
        finally:
            if child and child.poll() is None:
                child.terminate()
                try:child.wait(timeout=45)
                except subprocess.TimeoutExpired:child.kill();child.wait(timeout=5)
            cluster.close()


if __name__=="__main__":
    try:main()
    except KeyboardInterrupt:raise SystemExit(130)
