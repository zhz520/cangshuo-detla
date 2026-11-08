"""Host-side signed package installer. Config is provisioned by the operator, never by a client request."""
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import tarfile
import threading
import time
import urllib.request
import uuid
import zipfile
from datetime import datetime, timezone

os.umask(0o077)
CONFIG = json.loads(Path(sys.argv[1]).read_text())
STATE = Path(CONFIG['state_dir']).resolve()
ROOT = Path(CONFIG['root']).resolve()

def inside(path, parent):
    try:path.resolve().relative_to(parent.resolve());return True
    except ValueError:return False

def remove(path):
    try:path.unlink()
    except FileNotFoundError:pass

def prune_directories(parent, keep, preserve=()):
    """Retain a bounded rollback history without following symlinked directories."""
    parent=parent.resolve()
    if not parent.is_dir():
        return
    preserve={path.resolve() for path in preserve if path}
    entries=[]
    for path in parent.iterdir():
        if path.is_dir() and not path.is_symlink() and inside(path,parent):
            entries.append((path.stat().st_mtime,path))
    entries.sort(key=lambda item:(item[0],item[1].name),reverse=True)
    retained={path.resolve() for _,path in entries[:max(0,int(keep))]}|preserve
    for _,path in entries:
        if path.resolve() not in retained:
            shutil.rmtree(path,ignore_errors=True)

def instant(value):
    value=re.sub(r'\+00:00$','Z',value)
    for pattern in ['%Y-%m-%dT%H:%M:%S.%fZ','%Y-%m-%dT%H:%M:%SZ']:
        try:return datetime.strptime(value,pattern).replace(tzinfo=timezone.utc)
        except ValueError:pass
    raise ValueError('Invalid UTC timestamp')

def run(args, **kwargs):
    return subprocess.run(args, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=kwargs.pop('timeout', 180), **kwargs)

def write_json(path, value):
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(value, ensure_ascii=False), encoding='utf-8')
    temporary.replace(path)

def verify(envelope, scratch):
    if envelope.get('format') != 'cangshuo-update-v1':
        raise ValueError('Invalid update format')
    payload, signature = envelope.get('payload', ''), envelope.get('signature', '')
    if not re.fullmatch(r'[A-Za-z0-9_-]{1,1500000}', payload) or not re.fullmatch(r'[A-Za-z0-9_-]{80,90}', signature):
        raise ValueError('Invalid update signature encoding')
    message = scratch/'message'; signed = scratch/'signature'
    message.write_bytes(('cangshuo-update-v1.'+payload).encode())
    signed.write_bytes(base64.urlsafe_b64decode(signature+'='*(-len(signature)%4)))
    if CONFIG.get('verifier_image'):
        # Supports hosts whose system OpenSSL predates Ed25519. No network or signing private key is mounted.
        run(['docker','run','--rm','--network','none','--read-only','--cap-drop','ALL','--security-opt','no-new-privileges','--user','0:0',
             '-v',str(STATE/'license-public.pem')+':/verify/public.pem:ro','-v',str(scratch)+':/verify/data:ro','--entrypoint','node',CONFIG['verifier_image'],'--input-type=module','-e',
             "import{readFileSync as r}from'node:fs';import{verify,createPublicKey}from'node:crypto';if(!verify(null,r('/verify/data/message'),createPublicKey(r('/verify/public.pem')),r('/verify/data/signature')))process.exit(1)"])
    else:
        run(['openssl','pkeyutl','-verify','-pubin','-inkey',str(STATE/'license-public.pem'),'-rawin','-in',str(message),'-sigfile',str(signed)])
    plan=json.loads(base64.urlsafe_b64decode(payload+'='*(-len(payload)%4)))
    current=json.loads((STATE/'state.json').read_text())
    now=datetime.now(timezone.utc)
    if plan['instanceId'] != current['instanceId'] or plan['customerId'] != CONFIG['customer_id'] or plan['productId'] != CONFIG['product_id']:
        raise ValueError('Wrong update instance')
    if instant(plan['expiresAt'])<=now or (now-instant(plan['issuedAt'])).total_seconds()< -300:
        raise ValueError('Update plan expired')
    if plan['schemaVersion'] != 1 or not 1 <= len(plan['packages']) <= 3:
        raise ValueError('Unsupported database schema or package count')
    uuid.UUID(plan['id'])
    return plan

def download(package, target, envelope=None):
    prefix='https://github.com/'+CONFIG['repository']+'/releases/download/'
    if not package['url'].startswith(prefix) or not re.fullmatch(r'[0-9a-f]{64}',package['sha256']):
        raise ValueError('Untrusted update artifact')
    digest=hashlib.sha256();size=0
    if package.get('downloadPath'):
        if package['downloadPath']!='/api/client/artifact' or not CONFIG.get('authority_url','').startswith('https://') or not envelope:
            raise ValueError('Invalid authority artifact endpoint')
        endpoint=CONFIG['authority_url'].rstrip('/')+package['downloadPath']
        request_data={'url':endpoint,'body':{'plan':envelope,'component':package['component']}}
        request_file=target.with_suffix('.request.json');request_file.write_text(json.dumps(request_data))
        try:
            if CONFIG.get('verifier_image'):
                script="import{readFileSync,createWriteStream}from'node:fs';import{Readable,Transform}from'node:stream';import{pipeline}from'node:stream/promises';process.umask(0o077);const q=JSON.parse(readFileSync('/data/"+request_file.name+"'));const r=await fetch(q.url,{method:'POST',redirect:'error',headers:{'Content-Type':'application/json'},body:JSON.stringify(q.body),signal:AbortSignal.timeout(300000)});if(!r.ok)throw Error('artifact_http_'+r.status);let n=0;await pipeline(Readable.fromWeb(r.body),new Transform({transform(c,e,cb){n+=c.length;cb(n>500000000?Error('too_large'):null,c)}}),createWriteStream('/data/"+target.name+"'));"
                run(['docker','run','--rm','--read-only','--cap-drop','ALL','--security-opt','no-new-privileges','--user','0:0','-v',str(target.parent)+':/data:rw','--entrypoint','node',CONFIG['verifier_image'],'--input-type=module','-e',script],timeout=330)
            else:
                request=urllib.request.Request(endpoint,data=json.dumps(request_data['body']).encode(),headers={'Content-Type':'application/json'})
                with urllib.request.urlopen(request,timeout=300) as source,target.open('wb') as output:shutil.copyfileobj(source,output)
        finally:remove(request_file)
        with target.open('rb') as source:
            while True:
                data=source.read(1024*1024)
                if not data:break
                size+=len(data);digest.update(data)
    else:
        request=urllib.request.Request(package['url'],headers={'User-Agent':'Cangshuo-Updater'})
        with urllib.request.urlopen(request,timeout=60) as source, target.open('wb') as output:
            if not source.url.startswith(('https://github.com/','https://release-assets.githubusercontent.com/','https://objects.githubusercontent.com/')):
                raise ValueError('Untrusted artifact redirect')
            while True:
                data=source.read(1024*1024)
                if not data:break
                size+=len(data)
                if size>500_000_000:raise ValueError('Artifact too large')
                digest.update(data);output.write(data)
    if size!=package['size'] or digest.hexdigest()!=package['sha256']:
        raise ValueError('Artifact checksum mismatch')

def extract(source, destination, package):
    destination.mkdir()
    with zipfile.ZipFile(source) as archive:
        if sum(item.file_size for item in archive.infolist())>1_000_000_000:raise ValueError('Expanded archive too large')
        for item in archive.infolist():
            target=(destination/item.filename).resolve()
            if not inside(target,destination) or '\\' in item.filename or stat.S_ISLNK(item.external_attr>>16):
                raise ValueError('Unsafe archive path')
            if item.filename.startswith('.') or any(part.startswith('.') for part in Path(item.filename).parts):
                raise ValueError('Hidden config files are forbidden')
        archive.extractall(destination)
    if package['component']=='backend':
        jar=destination/'delta-admin.jar'
        if not jar.is_file() or len(list(destination.iterdir()))!=1:raise ValueError('Backend package must only contain delta-admin.jar')
        with zipfile.ZipFile(jar) as archive:
            catalog=json.loads(archive.read('BOOT-INF/classes/platform/catalog.json'))
            if catalog['version']!=package['version'] or catalog['schemaVersion']!=1:raise ValueError('Backend version does not match release')
    else:
        version=json.loads((destination/'cangshuo-version.json').read_text())
        if not (destination/'index.html').is_file() or version['version']!=package['version'] or version['component']!=package['component']:
            raise ValueError('Frontend version does not match release')

def compose(files, services):
    command=['docker','compose','--project-directory',CONFIG['compose_directory']]
    if CONFIG.get('env_file'):command+=['--env-file',CONFIG['env_file']]
    for file in files:command+=['-f',file]
    return run(command+['up','-d','--no-deps']+services,timeout=300)

def start_services(files, services, expected_versions=None):
    # Start in dependency order and wait ourselves. Compose otherwise enforces the
    # admin -> backend health condition inside one update command and can abort
    # before a slower application startup has finished.
    for service in ('backend','admin','h5'):
        if service not in services:continue
        compose(files,[service])
        health([service],expected_versions)

def backup(folder):
    folder.mkdir(mode=0o700,parents=True)
    # Single transaction preserves a consistent MySQL snapshot without exposing credentials.
    with (folder/'business.sql').open('wb') as output:
        subprocess.run(['docker','exec',CONFIG['mysql_container'],'sh','-c','export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysqldump -uroot --single-transaction --routines --triggers --events --set-gtid-purged=OFF "$MYSQL_DATABASE"'],stdout=output,stderr=subprocess.PIPE,check=True,timeout=300)
    with tarfile.open(folder/'customer-files.tar.gz','w:gz') as archive:
        for name in CONFIG['backup_paths']:
            source=Path(name)
            if source.exists():archive.add(source,arcname=str(source).lstrip('/'))

def active_release_paths(override):
    if not override.exists():
        return set()
    try:merged=json.loads(override.read_text())
    except (json.JSONDecodeError,OSError):return set()
    paths=set();release_root=(ROOT/'self-update-releases').resolve()
    for service in merged.get('services',{}).values():
        for volume in service.get('volumes',[]):
            match=re.fullmatch(r'(.+):(/[^:]+)(?::(?:ro|rw|z))?',str(volume))
            if match:
                source=Path(match.group(1)).resolve()
                # Mounted frontend/backend artifacts live one or more levels below a release.
                # Preserve that top-level release directory, not only the nested artifact path.
                try:
                    relative=source.relative_to(release_root)
                    if relative.parts:paths.add(release_root/relative.parts[0])
                except ValueError:
                    paths.add(source)
    return paths

def missing_active_release_artifacts(override):
    if not override.exists():
        return set()
    try:merged=json.loads(override.read_text())
    except (json.JSONDecodeError,OSError):return set()
    release_root=(ROOT/'self-update-releases').resolve();missing=set()
    for service in merged.get('services',{}).values():
        for volume in service.get('volumes',[]):
            match=re.fullmatch(r'(.+):(/[^:]+)(?::(?:ro|rw|z))?',str(volume))
            if not match:continue
            source=Path(match.group(1)).resolve()
            if inside(source,release_root) and not source.exists():missing.add(source)
    return missing

def prune_history(override):
    prune_directories(ROOT/'update-backups',CONFIG.get('backup_retention',3))
    prune_directories(ROOT/'self-update-releases',CONFIG.get('release_retention',3),active_release_paths(override))

def service_http_healthy(service, inspected, expected_versions):
    networks=inspected.get('NetworkSettings',{}).get('Networks',{})
    address=next((network.get('IPAddress') for network in networks.values() if network.get('IPAddress')),None)
    if not address:return False
    path,port=('/system/config/site',8080) if service=='backend' else ('/cangshuo-version.json',80)
    try:
        request=urllib.request.Request('http://%s:%s%s'%(address,port,path),headers={'User-Agent':'Cangshuo-HealthCheck'})
        with urllib.request.urlopen(request,timeout=5) as response:
            body=response.read(65536)
            if response.status!=200:return False
        if service in ('admin','h5') and expected_versions:
            version=json.loads(body)
            return version.get('component')==service and version.get('version')==expected_versions.get(service)
        return True
    except Exception:return False

def health(services, expected_versions=None):
    for _ in range(90):
        inspected={}
        healthy=True
        for service in services:
            container=CONFIG['containers'][service]
            inspected[service]=json.loads(run(['docker','inspect',container]).stdout)[0]
            state=inspected[service]['State']
            if not state.get('Running') or state.get('Health',{}).get('Status','healthy')!='healthy':healthy=False
        if healthy and all(service_http_healthy(service,inspected[service],expected_versions) for service in services):
            return
        if healthy:
            # Verify the new Java app is serving requests, not merely accepting TCP.
            try:
                result=run(['docker','exec',CONFIG['containers']['backend'],'bash','-c',"exec 3<>/dev/tcp/127.0.0.1/8080; printf 'GET /system/config/site HTTP/1.0\\r\\nHost: localhost\\r\\n\\r\\n' >&3; head -1 <&3"],timeout=10)
                if b'200' in result.stdout:return
            except Exception:pass
        time.sleep(3)
    raise RuntimeError('Health check failed')

def process(job):
    attempt=str(uuid.uuid4());scratch=ROOT/'update-staging'/attempt;scratch.mkdir(parents=True)
    backup_dir=ROOT/'update-backups'/attempt;override=ROOT/'compose.self-update.json'
    prior=override.read_bytes() if override.exists() else None
    if missing_active_release_artifacts(override):raise ValueError('Active release artifact missing')
    changed=False;job_id=None;retain_gate=False
    try:
        envelope=json.loads(job.read_text());plan=verify(envelope,scratch);job_id=plan['id']
        versions_file=STATE/'component-versions.json'
        versions=json.loads(versions_file.read_text()) if versions_file.exists() else {component:plan['fromVersion'] for component in ['backend','admin','h5']}
        numeric=lambda version:tuple(int(number) for number in version.split('.'))
        for package in plan['packages']:
            if not re.fullmatch(r'\d+\.\d+\.\d+',package['version']) or numeric(package['version'])<numeric(versions.get(package['component'],plan['fromVersion'])):
                raise ValueError('Component downgrade is forbidden')
        processed=STATE/'installed-plans'/job_id
        if processed.exists():raise ValueError('Update plan already installed')
        status=lambda value,**extra:write_json(STATE/'update-result.json',{'id':job_id,'status':value,**extra})
        status('DOWNLOADING')
        destinations={}
        for package in plan['packages']:
            component=package['component']
            if component not in ('backend','admin','h5') or component in destinations:raise ValueError('Invalid component')
            zipped=scratch/(component+'.zip');download(package,zipped,envelope)
            target=scratch/component;extract(zipped,target,package);destinations[component]=target
            if component in ('admin','h5'):
                container=CONFIG['containers'][component]
                for preserved in ['branding','instance-config.js','favicon.ico']:
                    old=target/preserved
                    try:
                        # Only fixed presentation/config files, never business uploads or arbitrary paths.
                        data=run(['docker','exec',container,'test','-e','/usr/share/nginx/html/'+preserved])
                        if not inside(old,scratch):raise ValueError('Preserved asset escaped staging')
                        if old.is_dir():shutil.rmtree(old)
                        elif old.exists():old.unlink()
                        run(['docker','cp',container+':/usr/share/nginx/html/'+preserved,str(old)])
                    except subprocess.CalledProcessError:pass
        (STATE/'updating').write_text(job_id)
        status('BACKING_UP');backup(backup_dir)
        if instant(plan['expiresAt'])<=datetime.now(timezone.utc):raise ValueError('Update plan expired before installation')
        status('INSTALLING')
        release_dir=ROOT/'self-update-releases'/job_id;release_dir.parent.mkdir(exist_ok=True);release_dir.parent.chmod(0o755);release_dir.mkdir()
        for component,target in destinations.items():shutil.copytree(target,release_dir/component)
        release_dir.chmod(0o755)
        for artifact in release_dir.rglob('*'):
            artifact.chmod(0o755 if artifact.is_dir() else 0o644)
        merged=json.loads(prior) if prior else {'services':{}}
        for component in destinations:
            target=release_dir/component
            source=target/'delta-admin.jar' if component=='backend' else target
            destination='/app/app.jar' if component=='backend' else '/usr/share/nginx/html'
            merged['services'][component]={'volumes':[str(source)+':'+destination+':ro']}
        write_json(ROOT/'update-journal.json',{'previous':prior.decode() if prior else None,'services':list(destinations),'id':job_id,'versionsBefore':versions})
        write_json(override,merged);changed=True
        expected_versions={package['component']:package['version'] for package in plan['packages']}
        start_services(CONFIG['compose_files']+[str(override)],list(destinations),expected_versions)
        processed.parent.mkdir(exist_ok=True);processed.write_text(json.dumps(plan))
        versions.update({package['component']:package['version'] for package in plan['packages']})
        installed_components=[versions.get(component,plan['fromVersion']) for component in ('backend','admin','h5')]
        versions['release']=min(installed_components,key=lambda version:tuple(int(number) for number in version.split('.')))
        write_json(versions_file,versions)
        status('SUCCEEDED',targetVersion=plan['targetVersion'],items=[item['key'] for item in plan['items']],backup=str(backup_dir))
        prune_history(override)
    except Exception as error:
        rolled_back=False
        if changed:
            try:
                if prior is None:remove(override)
                else:override.write_bytes(prior)
                start_services(CONFIG['compose_files']+([str(override)] if prior else []),list(destinations));rolled_back=True
            except Exception:pass
            retain_gate=not rolled_back
        # Do not log command output or secrets. Backups remain private on the host.
        write_json(STATE/'update-result.json',{'id':job_id,'status':'ROLLED_BACK' if rolled_back else 'FAILED','error':type(error).__name__,'message':'更新失败，已回退原程序' if rolled_back else '更新失败，请联系维护人员检查','backup':str(backup_dir) if backup_dir.exists() else None})
    finally:
        shutil.rmtree(scratch,ignore_errors=True)
        if not retain_gate:
            remove(ROOT/'update-journal.json')
            remove(STATE/'updating')
        remove(job)
        prune_history(override)

def recover_interrupted():
    journal=ROOT/'update-journal.json'
    if not journal.exists():return
    interrupted=json.loads(journal.read_text());override=ROOT/'compose.self-update.json'
    if interrupted['previous'] is None:remove(override)
    else:override.write_text(interrupted['previous'])
    remove(STATE/'installed-plans'/interrupted['id'])
    versions_before=interrupted.get('versionsBefore')
    if isinstance(versions_before,dict):write_json(STATE/'component-versions.json',versions_before)
    start_services(CONFIG['compose_files']+([str(override)] if interrupted['previous'] else []),interrupted['services'])
    write_json(STATE/'update-result.json',{'id':interrupted['id'],'status':'ROLLED_BACK','message':'上次更新意外中断，已回退原程序'})
    journal.unlink();remove(STATE/'updating');remove(STATE/'update-job.json')

def heartbeat():
    while True:
        (STATE/'installer-ready').touch()
        time.sleep(10)

def main():
    STATE.mkdir(parents=True,exist_ok=True)
    shutil.rmtree(ROOT/'update-staging',ignore_errors=True)
    (ROOT/'update-staging').mkdir(exist_ok=True)
    prune_history(ROOT/'compose.self-update.json')
    recover_interrupted()
    threading.Thread(target=heartbeat,daemon=True).start()
    (STATE/'installer-ready').touch()
    while True:
        pending=STATE/'update-job.json'
        if pending.exists():process(pending)
        time.sleep(5)

if __name__=='__main__':main()
