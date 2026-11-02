import base64
import importlib.util
import json
import os
from pathlib import Path
import sys
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import uuid
import zipfile
from datetime import datetime, timezone, timedelta
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives import serialization

class UpdaterTests(unittest.TestCase):
    def setUp(self):
        self.temporary=tempfile.TemporaryDirectory(dir=os.environ['TEMP']);self.directory=Path(self.temporary.name);self.state=self.directory/'state';self.state.mkdir()
        self.instance=str(uuid.uuid4());(self.state/'state.json').write_text(json.dumps({'instanceId':self.instance}))
        self.key=Ed25519PrivateKey.generate();(self.state/'license-public.pem').write_bytes(self.key.public_key().public_bytes(serialization.Encoding.PEM,serialization.PublicFormat.SubjectPublicKeyInfo))
        config=self.directory/'worker.json';config.write_text(json.dumps({'root':str(self.directory),'state_dir':str(self.state),'customer_id':'test','product_id':'detla','repository':'owner/repo','compose_directory':str(self.directory),'compose_files':['base.yml'],'mysql_container':'mysql','containers':{'backend':'backend','admin':'admin'},'backup_paths':[]}))
        spec=importlib.util.spec_from_file_location('worker',Path(__file__).with_name('worker.py'));self.worker=importlib.util.module_from_spec(spec)
        with patch.object(sys,'argv',['worker.py',str(config)]):spec.loader.exec_module(self.worker)
        self.scratch=self.directory/'scratch';self.scratch.mkdir()
    def tearDown(self):self.temporary.cleanup()
    def envelope(self,**changes):
        now=datetime.now(timezone.utc)
        plan={'id':str(uuid.uuid4()),'customerId':'test','instanceId':self.instance,'productId':'detla','issuedAt':now.isoformat(),'expiresAt':(now+timedelta(minutes=10)).isoformat(),'schemaVersion':1,'fromVersion':'1.1.0','targetVersion':'1.2.0','items':[{'key':'1.2.0:admin'}],'packages':[{'component':'admin','version':'1.2.0','url':'https://github.com/owner/repo/releases/download/v1.2.0/cangshuo-admin.zip','sha256':'a'*64,'size':100}]}
        plan.update(changes);payload=base64.urlsafe_b64encode(json.dumps(plan).encode()).decode().rstrip('=');signature=self.key.sign(('cangshuo-update-v1.'+payload).encode())
        return {'format':'cangshuo-update-v1','payload':payload,'signature':base64.urlsafe_b64encode(signature).decode().rstrip('=')}
    def openssl(self,args,**kwargs):
        if args[0]=='docker':raise subprocess.CalledProcessError(1,args)
        public=serialization.load_pem_public_key((self.state/'license-public.pem').read_bytes())
        public.verify(Path(args[args.index('-sigfile')+1]).read_bytes(),Path(args[args.index('-in')+1]).read_bytes())
    def test_signature_instance_expiry_and_schema_are_verified(self):
        with patch.object(self.worker,'run',side_effect=self.openssl):
            self.assertEqual(self.worker.verify(self.envelope(),self.scratch)['instanceId'],self.instance)
            for changes in [{'instanceId':str(uuid.uuid4())},{'schemaVersion':2},{'expiresAt':'2020-01-01T00:00:00+00:00'}]:
                with self.assertRaises(ValueError):self.worker.verify(self.envelope(**changes),self.scratch)
            envelope=self.envelope();envelope['payload']=envelope['payload'][:-1]+'A'
            with self.assertRaises(Exception):self.worker.verify(envelope,self.scratch)
    def test_archive_traversal_and_wrong_internal_version_are_rejected(self):
        archive=self.directory/'bad.zip'
        with zipfile.ZipFile(archive,'w') as zipped:zipped.writestr('../escaped','x')
        with self.assertRaises(ValueError):self.worker.extract(archive,self.directory/'bad',{'component':'admin','version':'1.2.0'})
        self.assertFalse((self.directory/'escaped').exists())
        with zipfile.ZipFile(archive,'w') as zipped:
            zipped.writestr('index.html','test');zipped.writestr('cangshuo-version.json',json.dumps({'component':'admin','version':'9.0.0'}))
        with self.assertRaises(ValueError):self.worker.extract(archive,self.directory/'wrong',{'component':'admin','version':'1.2.0'})
    def test_services_start_in_dependency_order_and_wait_for_each_health_check(self):
        with patch.object(self.worker,'compose') as compose,patch.object(self.worker,'health') as health:
            self.worker.start_services(['base.yml'],['h5','admin','backend'])
        self.assertEqual([call.args[1] for call in compose.call_args_list],[['backend'],['admin'],['h5']])
        self.assertEqual([call.args[0] for call in health.call_args_list],[['backend'],['admin'],['h5']])
    def test_failed_health_check_restores_previous_compose_and_keeps_private_backup(self):
        override=self.directory/'compose.self-update.json';original=json.dumps({'services':{'admin':{'volumes':['old:/usr/share/nginx/html:ro']}}});override.write_text(original)
        job=self.state/'update-job.json';job.write_text(json.dumps(self.envelope()))
        def download(package,target,envelope=None):
            with zipfile.ZipFile(target,'w') as zipped:
                zipped.writestr('index.html','test');zipped.writestr('cangshuo-version.json',json.dumps({'component':'admin','version':'1.2.0'}))
        with patch.object(self.worker,'run',side_effect=self.openssl),patch.object(self.worker,'download',side_effect=download),patch.object(self.worker,'backup',side_effect=lambda path:path.mkdir(parents=True)),patch.object(self.worker,'compose') as compose,patch.object(self.worker,'health',side_effect=[RuntimeError('failed'),None]):
            self.worker.process(job)
        self.assertEqual(json.loads((self.state/'update-result.json').read_text())['status'],'ROLLED_BACK');self.assertEqual(override.read_text(),original);self.assertEqual(compose.call_count,2);self.assertFalse((self.state/'updating').exists());self.assertFalse(job.exists())

if __name__=='__main__':unittest.main()
