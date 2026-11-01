#!/usr/bin/env python3
"""Real Java/PG18/native refresh, actual absent holder and original-material restarts."""
import hashlib
import json
import os
import pathlib
import signal
import subprocess
import sys
import tempfile
import time
from node_test import Cluster, require, verify_signature


def material(root):
    return {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest()
            for i in range(3) for p in (root / f"node{i}-store").rglob("*") if p.is_file()}


def interrupted(*_):
    raise KeyboardInterrupt()


def main():
    require(len(sys.argv)==2, "usage: epoch_refresh_java_test.py /path/to/wallet-mpc-signer")
    os.umask(0o077)
    classpath=os.environ.get("MPC_WALLET_TEST_CLASSPATH")
    require(classpath and os.environ.get("SW_TEST_CUSTODY_DB_URL"), "fresh Java reactor classpath and own local PG18 database required")
    signal.signal(signal.SIGTERM,interrupted)
    with tempfile.TemporaryDirectory(prefix="surprising-wallet-mpc-refresh-java-") as temporary:
        root=pathlib.Path(temporary)
        cluster=Cluster(root,str(pathlib.Path(sys.argv[1]).resolve(strict=True)),"00000000-0000-0000-0000-000000000001")
        child=None
        try:
            (root/"java-probe.json").write_text(json.dumps({"tenantId":cluster.tenant,"peers":cluster.peers}))
            env=os.environ.copy();env["SW_TEST_MPC_DIRECTORY"]=str(root)
            child=subprocess.Popen(["java","-cp",classpath,"com.surprising.wallet.custody.MpcRefreshNativeProbe",str(root)],env=env)
            deadline=time.monotonic()+240;sequence=0;restarts=0;pairs=[]
            while child.poll() is None and time.monotonic()<deadline:
                control=root/"refresh-control.json"
                if control.is_file():
                    request=json.loads(control.read_text())
                    if request["sequence"]!=sequence:
                        action=request["action"]
                        if action=="restart":
                            require(len(cluster.processes)==3,"refresh restart requires the original three holders")
                            before=material(root);require(before,"original native material missing")
                            for i in range(3):cluster.stop(i)
                            for i in range(3):cluster.start(i)
                            require(material(root)==before,"holder restart changed original material")
                            restarts+=1
                        else:
                            require(action in ("pair","all"),"unknown native refresh control")
                            wanted=request["pair"] if action=="pair" else [0,1,2]
                            require(len(set(wanted))==len(wanted) and set(wanted)<={0,1,2},"invalid requested holders")
                            for i in list(cluster.processes):
                                if i not in wanted:cluster.stop(i)
                            for i in wanted:
                                if i not in cluster.processes:cluster.start(i)
                            if action=="pair":
                                require(len(cluster.processes)==2,"third holder remains running")
                                pairs.append(wanted)
                        require(all(p.poll() is None for p in cluster.processes.values()),"an active original holder exited")
                        sequence=request["sequence"]
                        ack=root/"refresh-control-ack-pending.json";ack.write_text(json.dumps({"sequence":sequence}))
                        ack.replace(root/"refresh-control-ack.json")
                time.sleep(.025)
            require(child.poll() is not None,"Java native refresh acceptance timed out")
            require(child.returncode==0,"Java/PG18/native refresh acceptance failed")
            proof=json.loads((root/"refresh-java-public.json").read_text())
            require(proof["passed"] is True and proof["signatures"]==18,"Java refresh proof incomplete")
            require(restarts==proof["allHolderRestartRounds"]==3 and len(pairs)==18,"actual absent-third/restart coverage incomplete")
            require(proof["lostCompletedReplyScenarios"]==3 and all(proof[k] for k in ["oldSignaturesRecovered","pendingSigningBlockedRefresh","originalRefreshIdempotent"]),"original refresh recovery assertions missing")
            keys={str(k["keyId"]):k for k in proof["keys"]};require(len(keys)==3,"actual algorithms incomplete")
            for witness in proof["witnesses"]:
                key=keys[str(witness["keyId"])]
                verify_signature(root,key["algorithm"],key["publicKeyHex"],witness["messageHex"],witness["signatureHex"])
            output=pathlib.Path(os.environ.get("MPC_REFRESH_JAVA_PUBLIC_PROOF","/tmp/surprising-wallet-mpc-refresh-java-public.json"))
            output.write_text(json.dumps(proof))
            print("Java/PG18/native refresh passed:3 algorithms,18 signatures,3 original all-holder restart rounds and lost-reply recovery, atomic activation, original SIGN recovery.",flush=True)
        finally:
            if child and child.poll() is None:
                child.terminate()
                try:child.wait(timeout=20)
                except subprocess.TimeoutExpired:child.kill();child.wait(timeout=5)
            cluster.close()


if __name__=="__main__":
    try:main()
    except KeyboardInterrupt:raise SystemExit(130)
