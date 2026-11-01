"""Create independent GitHub release assets from a validated build."""
from pathlib import Path
import json
import sys
import zipfile

root=Path(__file__).resolve().parents[2]
output=Path(sys.argv[1]).resolve()
if sys.platform=='win32' and output.drive.lower()!='d:':raise SystemExit('Release output must be on D:')
output.mkdir(parents=True,exist_ok=True)
catalog=json.loads((root/'delta-game/delta-admin/src/main/resources/platform/catalog.json').read_text(encoding='utf-8'))
version=catalog['version']
if '-' in version:raise SystemExit('Only stable releases can be packaged')
sources={'backend':root/'delta-game/delta-admin/target/delta-admin.jar','admin':root/'delta-admin-ui/dist','h5':root/'delta-mp/dist/build/h5'}
for component,source in sources.items():
    if not source.exists():raise SystemExit('Build artifact missing: '+str(source))
    target=output/('cangshuo-'+component+'.zip')
    with zipfile.ZipFile(target,'w',zipfile.ZIP_DEFLATED) as archive:
        if component=='backend':archive.write(source,'delta-admin.jar')
        else:
            for file in source.rglob('*'):
                if file.is_file():archive.write(file,file.relative_to(source).as_posix())
            archive.writestr('cangshuo-version.json',json.dumps({'version':version,'component':component}))
    print(component,version,target.stat().st_size)
