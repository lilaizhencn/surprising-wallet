#!/usr/bin/env python3
"""Cold restore into new directories and a second DB on the existing local PG18 instance."""
import hashlib
import json
import os
import pathlib
import re
import secrets
import shutil
import signal
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.error

from monero_native_test import NativeLedger
from node_test import Cluster, require, verify_signature


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def files(root):
    values={}
    for path in root.rglob('*'):
        require(not path.is_symlink(),'backup fixture must contain regular files, not links')
        if path.is_file():values[str(path.relative_to(root))]=(digest(path),path.stat().st_mode & 0o777)
    return values


def command(args,env=None,timeout=60):
    result=subprocess.run(list(map(str,args)),env=env,text=True,capture_output=True,timeout=timeout)
    if result.returncode:
        log=pathlib.Path('/tmp/surprising-wallet-mpc-backup-command-last.log')
        log.write_text(result.stdout+'\n'+result.stderr);log.chmod(0o600)
    require(result.returncode==0,'restore command failed; protected diagnostic /tmp/surprising-wallet-mpc-backup-command-last.log')
    return result.stdout.strip()


def database_name(url):
    prefix='jdbc:postgresql://127.0.0.1:5432/'
    require(url.startswith(prefix),'restore drill requires the existing local PG18')
    name=url[len(prefix):]
    require(re.fullmatch(r'surprising_wallet_test_mpc_restore_[a-z0-9_]+',name),'refusing a database outside this restore drill')
    return name


def pg_args(database):
    return ['-h','127.0.0.1','-p','5432','-U',os.environ['SW_TEST_CUSTODY_DB_USERNAME'],'-d',database]


def psql(database,sql):
    return command(['psql',*pg_args(database),'-X','-Atq','-v','ON_ERROR_STOP=1','-c',sql])


def table_state(database):
    names=psql(database,"select schemaname||'.'||tablename from pg_tables where schemaname in ('public','pgmq') order by 1").splitlines()
    values={}
    for name in names:
        schema,table=name.split('.')
        require(re.fullmatch('[a-z0-9_]+',schema+table),'unexpected fixture table name')
        values[name]=psql(database,'select count(*)::text||\':\'||md5(coalesce(string_agg(row_to_json(t)::text,E\'\\n\' order by row_to_json(t)::text),\'\')) from "'+schema+'"."'+table+'" t')
    sequences=psql(database,"select sequence_schema||'.'||sequence_name from information_schema.sequences where sequence_schema in ('public','pgmq') order by 1").splitlines()
    for name in sequences:
        schema,table=name.split('.')
        require(re.fullmatch('[a-z0-9_]+',schema+table),'unexpected fixture sequence name')
        values[name+':sequence']=psql(database,'select last_value::text||\':\'||is_called::text from "'+schema+'"."'+table+'"')
    return values


def extract(archive,destination):
    with tarfile.open(archive,'r:gz') as source:
        for item in source.getmembers():
            path=pathlib.PurePosixPath(item.name)
            require(not path.is_absolute() and '..' not in path.parts,'invalid archive member')
            if item.isdir():
                (destination/path).mkdir(parents=True,exist_ok=True,mode=0o700)
                continue
            require(item.isfile(),'restore accepts only regular file members')
            target=destination/path;target.parent.mkdir(parents=True,exist_ok=True,mode=0o700)
            with target.open('xb') as output:shutil.copyfileobj(source.extractfile(item),output)
            target.chmod(item.mode & 0o777)


def snapshot(live,backup):
    groups={}
    state=files(live)
    for name in state:
        holder=re.match(r'(?:signers/node([012])(?:[./-])|ledger/wallet([012])/)',name)
        if holder:group='holder-'+str(next(value for value in holder.groups() if value is not None))
        elif name.startswith('ledger/wallet3/'):group='external-test-funder'
        elif name=='signers/ca.key':group='test-certificate-authority'
        elif name.startswith('ledger/'):group='network'
        else:group='coordinator'
        groups.setdefault(group,[]).append(name)
    archives=[]
    for group,names in sorted(groups.items()):
        archive=backup/(group+'.tar.gz')
        with tarfile.open(archive,'w:gz') as output:
            for name in sorted(names):output.add(live/name,arcname=name,recursive=False)
        archive.chmod(0o600);archives.append(archive)
    require(all('holder-'+str(i) in groups for i in range(3)) and 'coordinator' in groups,'incomplete component backup')
    (backup/'private-snapshot-manifest.json').write_text(json.dumps({'files':state,'archives':{p.name:digest(p) for p in archives}}))
    return state,archives


def change_paths(value,old,new):
    if isinstance(value,pathlib.Path):return pathlib.Path(str(value).replace(str(old),str(new)))
    if isinstance(value,str):return value.replace(str(old),str(new))
    if isinstance(value,list):return [change_paths(item,old,new) for item in value]
    if isinstance(value,tuple):return tuple(change_paths(item,old,new) for item in value)
    if isinstance(value,dict):return {key:change_paths(item,old,new) for key,item in value.items()}
    return value


def control(cluster,request):
    require(request['action'] in ('pair','all'),'unknown backup fixture action')
    wanted=request['pair'] if request['action']=='pair' else [0,1,2]
    require(len(set(wanted))==len(wanted) and set(wanted)<={0,1,2},'invalid fixture quorum')
    for index in list(cluster.processes):
        if index not in wanted:cluster.stop(index)
    for index in wanted:
        if index not in cluster.processes:cluster.start(index)
    require(len(cluster.processes)==len(wanted) and all(p.poll() is None for p in cluster.processes.values()),'actual holder state differs')
    return len(wanted)==2


def java(root,cluster,env,phase):
    for name in ('backup-control.json','backup-control-ack.json'):(root/name).unlink(missing_ok=True)
    child=subprocess.Popen(['java','-cp',env['MPC_WALLET_TEST_CLASSPATH'],'com.surprising.wallet.custody.MpcBackupRestoreNativeProbe',str(root),phase],env=env)
    sequence=0;pairs=0;deadline=time.monotonic()+240
    try:
        while child.poll() is None and time.monotonic()<deadline:
            path=root/'backup-control.json'
            if path.is_file():
                request=json.loads(path.read_text())
                if request['sequence']!=sequence:
                    pairs+=int(control(cluster,request));sequence=request['sequence']
                    ack=root/'backup-control-ack-pending.json';ack.write_text(json.dumps({'sequence':sequence}));ack.replace(root/'backup-control-ack.json')
            time.sleep(.025)
        require(child.poll() is not None,'Java restore probe timed out')
        require(child.returncode==0,'Java restore probe failed: '+phase)
        return pairs
    finally:
        if child.poll() is None:
            child.terminate()
            try:child.wait(timeout=15)
            except subprocess.TimeoutExpired:child.kill();child.wait(timeout=5)


def monero_java(root,env,phase,pair):
    result=subprocess.run(['java','-cp',env['MPC_WALLET_TEST_CLASSPATH'],'com.surprising.wallet.custody.MpcMoneroPaymentNativeProbe',str(root),phase,str(pair)],env=env,timeout=150)
    if result.returncode:
        diagnostic=pathlib.Path('/tmp/surprising-wallet-mpc-backup-private-diagnostics');diagnostic.mkdir(exist_ok=True,mode=0o700)
        for path in [*(root/f'node{i}.log' for i in range(3)),root.parent/'ledger/daemon-private.log',*(root.parent/f'ledger/wallet{i}/wallet-private.log' for i in range(3))]:
            if path.is_file():
                target=diagnostic/(path.parent.name+'-'+path.name);shutil.copyfile(path,target);target.chmod(0o600)
    require(result.returncode==0,'actual restored Monero Java phase failed: '+phase)


def main():
    require(len(sys.argv)==3,'usage: backup_restore_test.py release.tar.gz official-monero-bin-dir')
    os.umask(0o077)
    signal.signal(signal.SIGTERM,lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))
    source_db=database_name(os.environ['SW_TEST_CUSTODY_DB_URL'])
    restored_db=database_name(os.environ['MPC_RESTORE_DB_URL']);require(source_db!=restored_db,'restore requires a different owned DB')
    os.environ['PGPASSWORD']=os.environ.get('SW_TEST_CUSTODY_DB_PASSWORD','')
    require(psql(source_db,'show server_version_num').startswith('18'),'restore requires installed PostgreSQL18')
    require(os.environ.get('MPC_WALLET_TEST_CLASSPATH'),'fresh compiled Java classpath required')
    with tempfile.TemporaryDirectory(prefix='surprising-wallet-mpc-cold-restore-') as temporary:
        private=pathlib.Path(temporary);release=private/'release';release.mkdir();extract(pathlib.Path(sys.argv[1]),release)
        package=release/'wallet-mpc-signer';manifest=json.loads((package/'manifest.json').read_text())
        require(all(digest(package/name)==value for name,value in manifest['files'].items()),'release file checksum differs')
        binary=str(package/'bin/wallet-mpc-signer')
        live=private/'original';(live/'ledger').mkdir(parents=True);(live/'signers').mkdir()
        ledger=NativeLedger(live/'ledger',pathlib.Path(sys.argv[2]).resolve(strict=True));cluster=None
        try:
            ledger.__enter__()
            cluster=Cluster(live/'signers',binary,'00000000-0000-0000-0000-000000000001',monero_configs=ledger.configurations)
            root=cluster.root
            (root/'coordinator-aes.bin').write_bytes(secrets.token_bytes(32))
            (root/'java-probe.json').write_text(json.dumps({'tenantId':cluster.tenant,'peers':cluster.peers,'daemonRpcUrl':ledger.daemon,
                'daemonCredentialFile':ledger.configurations[0]['daemonCredentialFile'],'genesisHash':ledger.genesis}))
            env=dict(os.environ,SW_TEST_MPC_DIRECTORY=str(root));prepared_pairs=java(root,cluster,env,'prepare')
            result=subprocess.run(['java','-cp',env['MPC_WALLET_TEST_CLASSPATH'],'com.surprising.wallet.custody.MpcMoneroAddressNativeProbe',str(root),'first'],env=env,timeout=120)
            require(result.returncode==0,'pre-backup native Monero address publication failed')
            publication=json.loads((root/'monero-address-public.json').read_text())
            ledger.call(ledger.funder,'create_wallet',{'filename':'external-restore-funder','password':secrets.token_hex(32),'language':'English'})
            recipient=ledger.call(ledger.funder,'get_address')['address']
            for count in (4,80):ledger.call(ledger.daemon,'generateblocks',{'wallet_address':recipient,'amount_of_blocks':count})
            ledger.call(ledger.funder,'refresh')
            ledger.call(ledger.funder,'transfer',{'destinations':[{'address':publication['addresses'][0],'amount':5*10**12}]})
            ledger.call(ledger.daemon,'generateblocks',{'wallet_address':recipient,'amount_of_blocks':13})
            (root/'payment-case-public.json').write_text(json.dumps({'source':publication['addresses'][0],'recipient':recipient}))
            control(cluster,{'action':'pair','pair':[0,1]});ledger.stop('wallet2')
            monero_java(root,env,'admit',0)
            original=json.loads((root/'admitted-sign-request-public.json').read_text());deadline=time.monotonic()+60
            while time.monotonic()<deadline and not all(cluster.request(i,'/v1/results',original)['state']=='COMPLETED' for i in (0,1)):time.sleep(.1)
            require(all(cluster.request(i,'/v1/results',original)['state']=='COMPLETED' for i in (0,1)),'actual pre-backup Monero SIGN not completed')
            require(not (root/'selected-payment-0-public.json').exists(),'coordinator already selected the deliberately uncollected Monero result')
            chain_height=ledger.call(ledger.daemon,'get_info')['height']
            chain_tip=ledger.call(ledger.daemon,'get_block_header_by_height',{'height':chain_height-1})['block_header']['hash']
            cluster.close();ledger.close()
            before_rows=table_state(source_db)
            command(['pg_dump',*pg_args(source_db),'--format=custom','--no-owner','--no-acl','--file',live/'database.dump'])
            backup=private/'backups';backup.mkdir(mode=0o700);before_files,archives=snapshot(live,backup)
            restored=private/'restored';restored.mkdir(mode=0o700)
            for archive in archives:extract(archive,restored)
            require(files(restored)==before_files,'cold restore did not preserve every file byte and mode')
            # Original private paths are gone before any restored process or DB access begins.
            shutil.rmtree(live)
            command(['pg_restore',*pg_args(restored_db),'--exit-on-error','--no-owner','--no-acl',restored/'database.dump'])
            require(table_state(restored_db)==before_rows,'restored table contents or sequence values differ')
            command(['dropdb','-h','127.0.0.1','-p','5432','-U',os.environ['SW_TEST_CUSTODY_DB_USERNAME'],source_db])
            root=restored/'signers'
            for path in [*(root/f'node{i}.json' for i in range(3)),root/'java-probe.json']:
                path.write_text(json.dumps(change_paths(json.loads(path.read_text()),live,restored)))
            cluster.root=root;cluster.context=cluster.client_context('client')
            ledger.root=restored/'ledger';ledger.auth=change_paths(ledger.auth,live,restored)
            ledger.configurations=change_paths(ledger.configurations,live,restored);ledger.commands=change_paths(ledger.commands,live,restored)
            for name,values in ledger.commands.items():ledger.start(name,*values)
            require(ledger.call(ledger.daemon,'get_info')['height']==chain_height
                and ledger.call(ledger.daemon,'get_block_header_by_height',{'height':chain_height-1})['block_header']['hash']==chain_tip,
                'restored Monero daemon changed the original chain height or tip')
            for i in range(3):cluster.start(i)
            env=dict(os.environ,SW_TEST_MPC_DIRECTORY=str(root),SW_TEST_CUSTODY_DB_URL=os.environ['MPC_RESTORE_DB_URL'])
            preserved={str(p.relative_to(root)):digest(p) for i in range(3) for p in (root/f'node{i}-store').rglob('*') if p.is_file()}
            before=json.loads((root/'backup-before-public.json').read_text())
            for key in before['keys']:
                stale=cluster.descriptor(key['algorithm'],key['keyId'],'SIGN',[0,1],'11'*32)
                try:cluster.request(0,'/v1/sessions',stale)
                except urllib.error.HTTPError as error:require(error.code in (400,409),'unexpected old-epoch fence response')
                else:raise AssertionError('restored epoch1 accepted new signing after epoch2 activation')
            restored_pairs=java(root,cluster,env,'restore')
            after=json.loads((root/'backup-after-public.json').read_text())
            keys={key['keyId']:key for key in before['keys']}
            for witness in before['witnesses']+after['freshSignatures']:
                key=keys[witness['keyId']];verify_signature(root,key['algorithm'],key['publicKeyHex'],witness['messageHex'],witness['signatureHex'])
            monero=[]
            for number,pair in enumerate(([0,1],[0,2],[1,2])):
                control(cluster,{'action':'pair','pair':pair})
                for i in range(3):
                    if i not in pair:ledger.stop('wallet'+str(i))
                    elif 'wallet'+str(i) not in ledger.processes:ledger.restart('wallet'+str(i))
                require(len(cluster.processes)==2 and all('wallet'+str(i) not in ledger.processes for i in range(3) if i not in pair),'third signer or private wallet still running')
                monero_java(root,env,'select' if number==0 else 'sign',number)
                selected=json.loads((root/f'selected-payment-{number}-public.json').read_text())
                if number==0:require(selected['recoveredUncollectedResult'] and selected['requestId']==psql(restored_db,"select request_id from wallet_mpc_sign_attempt where id='"+original['sessionId']+"'"),'restored XMR selected a different request')
                monero_java(root,env,'relay',number)
                ledger.call(ledger.daemon,'generateblocks',{'wallet_address':recipient,'amount_of_blocks':13})
                monero_java(root,env,'observe',number)
                monero.append({'pair':pair,'requestId':selected['requestId'],'transactionHash':selected['selected']['transactionHash'],'feeAtomic':selected['selected']['feeAtomic']})
            require(all((root/name).is_file() and digest(root/name)==value for name,value in preserved.items()),'restored immutable protocol records changed')
            require(prepared_pairs==12 and restored_pairs==12,'three-curve all-pair coverage incomplete')
            proof={'passed':True,'scope':'Same-host cold-directory restore and separate JVM probes on existing PG18; not independent-host failure-domain or production deployment proof.',
                'releaseManifest':manifest,'backupArchives':{p.name:digest(p) for p in archives},'allOriginalFilesRestored':len(before_files),
                'allTableAndSequenceStatesMatched':len(before_rows),'sourceDirectoriesRemoved':not live.exists(),'sourceDatabaseDropped':True,
                'differentRestoredDatabase':source_db!=restored_db,'threeCurveProofBefore':before,'threeCurveProofAfter':after,
                'actualThirdStoppedPairRounds':prepared_pairs+restored_pairs+3,'oldEpochSigningRejected':True,'immutableRecordsPreserved':len(preserved),
                'moneroGenesisHash':ledger.genesis,'moneroOriginalHeight':chain_height,'moneroOriginalTip':chain_tip,'moneroChainTipPreserved':True,
                'moneroAddressesPreserved':publication['addresses'],'restoredMoneroPayments':monero,
                'originalMoneroUncollectedSignRecovered':True,'productionDeployment':False}
            destination=pathlib.Path(os.environ.get('MPC_BACKUP_RESTORE_PUBLIC_PROOF','/tmp/surprising-wallet-mpc-backup-restore-public.json'))
            destination.write_text(json.dumps(proof,indent=2))
            print('Cold backup/restore passed: all files, PG18 tables/sequences, separate JVM,21 three-curve signatures and3 confirmed restored-wallet XMR payments.',flush=True)
        finally:
            if cluster:cluster.close()
            ledger.close()


if __name__=='__main__':
    try:main()
    except KeyboardInterrupt:raise SystemExit(130)
