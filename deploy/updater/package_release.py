"""Create independent GitHub release assets from a validated build."""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import subprocess
import sys
import zipfile

root=Path(__file__).resolve().parents[2]
catalog=json.loads((root/'delta-game/delta-admin/src/main/resources/platform/catalog.json').read_text(encoding='utf-8'))
version=catalog['version']
if '-' in version:raise SystemExit('Only stable releases can be packaged')
try:
    commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True,stderr=subprocess.DEVNULL).strip()
    dirty=bool(subprocess.check_output(['git','status','--porcelain'],cwd=root,text=True,stderr=subprocess.DEVNULL).strip())
except (OSError,subprocess.CalledProcessError):
    raise SystemExit('Release must be packaged from a Git worktree')
if dirty:raise SystemExit('Release must be packaged from a clean worktree')
tag=subprocess.run(['git','rev-parse','-q','--verify','refs/tags/v'+version],cwd=root,text=True,
                   stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
if tag.returncode==0:raise SystemExit('Version v'+version+' is already tagged; choose the next version before packaging')
output=Path(sys.argv[1]).resolve()
if sys.platform=='win32' and output.drive.lower()!='d:':raise SystemExit('Release output must be on D:')
output.mkdir(parents=True,exist_ok=True)
sources={'backend':root/'delta-game/delta-admin/target/delta-admin.jar','admin':root/'delta-admin-ui/dist','h5':root/'delta-mp/dist/build/h5'}
subprocess.run([sys.executable,str(root/'deploy/qa/check_static_assets.py'),
                str(sources['admin']),str(sources['h5'])],check=True)
assets=[]
for component,source in sources.items():
    if not source.exists():raise SystemExit('Build artifact missing: '+str(source))
    if component=='backend':
        with zipfile.ZipFile(source) as jar:
            catalog_inside=json.loads(jar.read('BOOT-INF/classes/platform/catalog.json'))
        if catalog_inside.get('version')!=version or catalog_inside.get('schemaVersion')!=1:
            raise SystemExit(f'Backend build version mismatch: expected {version}, got {catalog_inside.get("version")}')
    target=output/('cangshuo-'+component+'.zip')
    with zipfile.ZipFile(target,'w',zipfile.ZIP_DEFLATED) as archive:
        if component=='backend':archive.write(source,'delta-admin.jar')
        else:
            for file in source.rglob('*'):
                if file.is_file():archive.write(file,file.relative_to(source).as_posix())
            archive.writestr('cangshuo-version.json',json.dumps({'version':version,'component':component}))
    print(component,version,target.stat().st_size)
    digest=hashlib.sha256()
    with target.open('rb') as stream:
        for chunk in iter(lambda:stream.read(1024*1024),b''):digest.update(chunk)
    assets.append({'component':component,'name':target.name,'size':target.stat().st_size,'sha256':digest.hexdigest()})

manifest={'schemaVersion':1,'version':version,'commit':commit,'dirty':dirty,'createdAt':datetime.now(timezone.utc).isoformat(),'assets':assets}
(output/'release-manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
print('manifest',version,commit,dirty)
