"""Synthetic APKs and API replies only. Never signs, contacts GitHub, or reads local credentials."""
from datetime import datetime, timezone
import io
import hashlib
import json
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
import release
from release_common import apk_identity, apk_payload, extract_candidate, sha256, validate_manifest

class GitHubDownloadTest(unittest.TestCase):
    def test_public_asset_uses_bounded_https_curl_and_reads_each_time(self):
        payload = b'\x00synthetic APK\xff'
        def installed(name): return {'gh': 'gh.exe', 'curl.exe': 'curl.exe'}.get(name)
        with patch.object(release.shutil, 'which', side_effect=installed), patch.object(release, 'command', return_value=SimpleNamespace(stdout=payload)) as run:
            github = release.GitHub(ROOT)
            for _ in range(2):
                self.assertEqual(github.download('repos/ShizhenZhou/StudyAssistant/releases/assets/123?download=1', binary=True), payload)
            self.assertEqual(run.call_count, 2, 'Final public verification must read the remote bytes every time')
            self.assertEqual(run.call_args.args[0], ['curl.exe', '--disable', '--silent', '--show-error',
                '--http1.1', '--ipv4', '--fail', '--location', '--connect-timeout', '15', '--max-time', '120',
                '--proto', '=https', '--proto-redir', '=https', '-H', 'Accept: application/octet-stream',
                'https://api.github.com/repos/ShizhenZhou/StudyAssistant/releases/assets/123?download=1'])
            self.assertEqual(run.call_args.kwargs, dict(root=ROOT, timeout=130))

    def test_curl_route_is_narrow_and_absent_curl_preserves_gh(self):
        public = 'repos/ShizhenZhou/StudyAssistant/releases/assets/123?download=1'
        def installed(name): return {'gh': 'gh.exe', 'curl.exe': 'curl.exe'}.get(name)
        with patch.object(release.shutil, 'which', side_effect=installed), patch.object(release, 'command', return_value=SimpleNamespace(stdout=b'{}', stderr=b'', returncode=0)) as run:
            github = release.GitHub(ROOT)
            for path, binary in [(public, False), ('https://api.github.com/' + public, True),
                    (public.replace('ShizhenZhou', 'other'), True), (public + '&other=1', True),
                    ('repos/ShizhenZhou/StudyAssistant/actions/artifacts/123/zip', True)]:
                with self.subTest(path=path, binary=binary):
                    self.assertEqual(github.download(path, binary), b'{}')
                    self.assertEqual(run.call_args.args[0][0], 'gh.exe')
            with patch.object(release.shutil, 'which', return_value=None):
                self.assertEqual(github.download(public, binary=True), b'{}')
                self.assertEqual(run.call_args.args[0][0], 'gh.exe')
            self.assertEqual(github.api('releases/123', {'draft': False}), {})
            self.assertEqual(run.call_args.args[0][:4], ['gh.exe', 'api', 'repos/ShizhenZhou/StudyAssistant/releases/123', '--method'])

    def test_public_curl_failure_is_not_retried_or_replaced_with_gh(self):
        def installed(name): return {'gh': 'gh.exe', 'curl.exe': 'curl.exe'}.get(name)
        with patch.object(release.shutil, 'which', side_effect=installed), patch.object(release.subprocess, 'run', return_value=SimpleNamespace(stdout=b'partial download', stderr=b'private native error', returncode=22)) as run:
            github = release.GitHub(ROOT)
            with self.assertRaises(release.ReleaseError) as error:
                github.download('repos/ShizhenZhou/StudyAssistant/releases/assets/123?download=1', binary=True)
            self.assertEqual(run.call_count, 1)
            self.assertEqual(run.call_args.args[0][0], 'curl.exe')
            self.assertNotIn('partial download', str(error.exception))
            self.assertNotIn('private native error', str(error.exception))
            self.assertNotIn('https://', str(error.exception))

class ReleaseTest(unittest.TestCase):
    def test_atomic_state_replace_tolerates_a_short_windows_sharing_denial(self):
        source=self.root/'state.tmp';target=self.root/'state.json'
        source.write_bytes(b'new');target.write_bytes(b'old')
        denied=PermissionError('temporary sharing denial');denied.winerror=32
        original=release.os.replace;calls=[]
        def replace(*args):
            calls.append(args)
            if len(calls)==1:
                self.assertEqual(target.read_bytes(),b'old')
                raise denied
            return original(*args)
        with patch.object(release.os,'replace',side_effect=replace),patch.object(release.time,'sleep') as delay:
            release.atomic_replace(source,target)
        self.assertEqual(len(calls),2);delay.assert_called_once()
        self.assertEqual(target.read_bytes(),b'new')

    def test_atomic_state_replace_keeps_old_state_and_blocks_permanent_denial(self):
        source=self.root/'state.tmp';target=self.root/'state.json'
        source.write_bytes(b'new');target.write_bytes(b'old')
        denied=PermissionError('persistent denial');denied.winerror=5
        with patch.object(release.os,'replace',side_effect=denied) as replace,patch.object(release.time,'sleep'):
            with self.assertRaises(PermissionError):release.atomic_replace(source,target)
        self.assertEqual(replace.call_count,4)
        self.assertEqual(target.read_bytes(),b'old')
        with patch.object(release.os,'replace',side_effect=PermissionError('ordinary permissions')) as replace,patch.object(release.time,'sleep') as delay:
            with self.assertRaises(PermissionError):release.atomic_replace(source,target)
        self.assertEqual(replace.call_count,1);delay.assert_not_called()

    def test_full_verification_start_dispatches_without_a_previous_release_and_binds_mode(self):
        source = self.root / 'app/build.gradle.kts'; source.parent.mkdir(parents=True)
        source.write_text('val appVersionName = "9.9.9"')
        requests = []
        class Fake:
            def api(self, path, value=None, **kwargs):
                if path == 'git/ref/tags/v9.9.9': return None
                if path.endswith('/dispatches'):
                    requests.append(value); return None
                raise AssertionError('Full verification must not depend on release asset downloads: ' + path)
        tool = release.Release(self.root, Fake())
        tool.git = lambda *args, **kwargs: SimpleNamespace(returncode=0,
            stdout=b'' if args[0]=='status' else b'Fix notifications [skip ci]\nPrivate body' if args[0]=='log' else b'a'*40)
        tool.remote = lambda branch: 'c'*40
        pushes=[]; tool.push=lambda branch,commit: pushes.append((branch,commit))
        tool.status=lambda: {'status':'ci dispatched'}
        with patch.object(release, 'command', return_value=SimpleNamespace(returncode=0)) as checks:
            tool.start('9.9.9', full_verification=True)
            self.assertEqual(checks.call_count, 1, 'Local Basic still runs')
            tool.start('9.9.9', full_verification=True)
            self.assertEqual(checks.call_count, 1, 'Resume must not dispatch or check twice')
        self.assertEqual(len(requests),1)
        self.assertEqual(requests[0]['inputs'], dict(mode='release',expected_sha='a'*40,base_sha='',
                                                   run_description='v9.9.9 · Fix notifications'))
        self.assertIsNone(tool.state['verificationBase'])
        self.assertIsNone(tool.state['verificationBaselineReleaseId'])
        self.assertTrue(tool.state['fullVerification'])
        self.assertEqual(pushes,[('main','a'*40),('Develop','a'*40)])
        with self.assertRaisesRegex(release.ReleaseError, 'original verification mode'):
            tool.start('9.9.9')

    def test_run_description_uses_only_bounded_commit_subject(self):
        self.assertEqual(release.run_description('9.9.9', '  修复下载 [skip ci]  \r\nPrivate body'),
                         'v9.9.9 · 修复下载')
        self.assertEqual(release.run_description('9.9.9', '[skip ci]\nPrivate body'), 'v9.9.9')
        self.assertLessEqual(len(release.run_description('9.9.9', '修' * 500)), 200)

    def test_verified_download_reuses_only_matching_remote_content_identity(self):
        calls = []
        data = b'fixture bytes'
        tool = release.Release(self.root, SimpleNamespace(download=lambda url: calls.append(url) or data))
        obj = dict(id=1, size_in_bytes=len(data), digest='sha256:' + hashlib.sha256(data).hexdigest())
        self.assertEqual(tool.verified_download(obj, 'artifact', 'action-artifact'), data)
        self.assertEqual(tool.verified_download(obj, 'artifact', 'action-artifact'), data)
        self.assertEqual(calls, ['artifact'])
        for changed in (dict(obj, id=2), dict(obj, size_in_bytes=len(data)+1), dict(obj, digest='sha256:'+'0'*64)):
            if changed['size_in_bytes'] != len(data) or changed['digest'] != obj['digest']:
                with self.assertRaises(release.ReleaseError): tool.verified_download(changed, 'artifact', 'action-artifact')
            else:
                self.assertEqual(tool.verified_download(changed, 'artifact', 'action-artifact'), data)
        self.assertEqual(len(calls), 4)
        asset = dict(obj, size=len(data))
        self.assertEqual(tool.verified_download(asset, 'artifact', 'release-asset'), data)
        self.assertEqual(tool.verified_download(asset, 'artifact', 'release-asset'), data)
        self.assertEqual(len(calls), 5, 'Asset and artifact IDs belong to separate namespaces')

    def test_verified_download_repairs_corrupt_bytes_and_rejects_bad_remote_bytes(self):
        calls = []
        data = b'fixture bytes'
        tool = release.Release(self.root, SimpleNamespace(download=lambda url: calls.append(url) or data))
        obj = dict(id=1, size=len(data), digest='sha256:' + hashlib.sha256(data).hexdigest())
        tool.verified_download(obj, 'asset', 'release-asset')
        cache = list((self.root / '.local-notes/release-state/download-cache').glob('*.bin'))
        self.assertEqual(len(cache), 1)
        cache[0].write_bytes(b'corrupt')
        self.assertEqual(tool.verified_download(obj, 'asset', 'release-asset'), data)
        self.assertEqual(len(calls), 2)
        cache[0].write_bytes(b'corrupt again')
        tool.github.download = lambda url: b'bad remote bytes'
        with self.assertRaises(release.ReleaseError): tool.verified_download(obj, 'asset', 'release-asset')

    def test_verified_download_without_trusted_identity_always_reads_remote(self):
        calls = []
        tool = release.Release(self.root, SimpleNamespace(download=lambda url: calls.append(url) or b'fixture'))
        valid = dict(id=1, size=7, digest='sha256:' + hashlib.sha256(b'fixture').hexdigest())
        for obj in (dict(valid, digest=None), dict(valid, digest='sha256:bad'), dict(valid, id=None),
                    dict(valid, id=True), dict(valid, size=0)):
            self.assertEqual(tool.verified_download(obj, 'asset', 'release-asset'), b'fixture')
            self.assertEqual(tool.verified_download(obj, 'asset', 'release-asset'), b'fixture')
        self.assertEqual(len(calls), 10)
        self.assertFalse((self.root / '.local-notes/release-state/download-cache').exists())

    def test_full_verification_cannot_bypass_replacement_baseline_proof(self):
        tool=release.Release(self.root)
        tool.git=lambda *args,**kwargs:SimpleNamespace(returncode=0,stdout=b'' if args[0]=='status' else b'a'*40)
        with self.assertRaisesRegex(release.ReleaseError, 'original baseline proof'):
            tool.start('9.9.9', replacement=True, full_verification=True)

    def test_beta_cannot_replace_a_stable_tag_through_the_start_entry(self):
        tool=release.Release(self.root)
        tool.git=lambda *args, **kwargs: SimpleNamespace(returncode=0,stdout=b'' if args==('status','--porcelain') else b'a'*40)
        with self.assertRaisesRegex(release.ReleaseError,'Beta replacement'):
            tool.start('9.9.9',replacement=True,channel='beta')

    def test_release_channel_is_visible_on_the_final_requirement_line(self):
        notes = '- Feature\n- Minimum Android 16 (API 36).'
        for channel, label in [('stable', '正式版'), ('beta', '测试版')]:
            body = release.release_body(notes, {'schema': 1, 'channel': channel})
            visible = body.split('<!--')[0].strip().splitlines()
            self.assertIn('Android 16', visible[-1])
            self.assertIn(label, visible[-1])
        changed = release.release_body(notes + ' 本版本为正式版。', {'channel': 'beta'})
        self.assertNotIn('本版本为正式版。', changed.split('<!--')[0])
        self.assertIn('本版本为测试版', changed.split('<!--')[0])

    def test_real_verify_replacement_keeps_original_baseline_across_remote_switch(self):
        self.verify_replacement_baseline_resume()

    def test_channel_aware_replacement_resumes_after_remote_asset_switch(self):
        self.verify_replacement_baseline_resume('stable')

    def verify_replacement_baseline_resume(self, channel=None):
        import shutil
        from verification import digest
        for failure in ('list releases','replacement marker network','delete 2','download 4','delete 1',None):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory(dir=self.root) as temporary:
                root=Path(temporary); work=root/'.local-notes/release-state/artifacts/42';work.mkdir(parents=True)
                old=self.apk('old.apk',code=b'old synthetic source')
                new=self.apk('new.apk',code=b'new synthetic source')
                old_plan=dict(schema=1,policy=1,commit='c'*40,base=None,changedFiles=[],devices=[],mode='release')
                plan=dict(old_plan,commit='a'*40,base='c'*40)
                old_manifest=dict(self.manifest(old),schema=2,commit='c'*40,planSha256=digest(old_plan))
                manifest=dict(self.manifest(new),schema=2,planSha256=digest(plan))
                def evidence(m,p,run):
                    value=dict(schema=1,success=True,runId=run,attempt=1,plan=p,devices=[],build=dict(
                        commit=m['commit'],runId=run,attempt=1,planSha256=digest(p),candidateSha256=m['candidateSha256'],jvm=m['jvm'],apks=None))
                    out=io.BytesIO()
                    with zipfile.ZipFile(out,'w') as archive:archive.writestr('verification.json',json.dumps(value))
                    return out.getvalue()
                old_meta=release.update_manifest(old,old_manifest,old_manifest)
                old_meta['provenance']=dict(sourceCommit='c'*40,runId=43)
                def asset(i,name,data):return dict(id=i,name=name,size=len(data),digest='sha256:'+release.hashlib.sha256(data).hexdigest())
                class Fake:
                    def __init__(self):
                        self.data={1:old.read_bytes(),2:json.dumps(old_meta).encode()};self.failed=False;self.events=[];self.original_ci_success=True;self.tag_reads=0
                        self.item=dict(id=7,tag_name='v9.9.9',draft=False,prerelease=False,html_url='https://example.invalid/release',
                            assets=[asset(1,old.name,self.data[1]),asset(2,'update-manifest.json',self.data[2])])
                    def boundary(self,event):
                        self.events.append(event)
                        if event==failure and not self.failed:self.failed=True;raise release.ReleaseError('synthetic interruption')
                    def api(self,path,value=None,method=None,**kwargs):
                        if path.startswith('git/ref/heads'):return {'object':{'sha':'a'*40}}
                        if path.startswith('git/ref/tags'):
                            self.tag_reads+=1
                            if self.tag_reads==2:self.boundary('replacement marker network')
                            return {'object':{'sha':'b'*40}}
                        if path.startswith('commits/'):return {'sha':'b'*40}
                        if path.startswith('actions/runs/'):
                            run=int(path.split('/')[2]);m=manifest if run==42 else old_manifest
                            if '/jobs?' in path:return {'jobs':[dict(id=i,name=n,conclusion='success') for i,n in enumerate(('build','Windows tool tests','Verification gate'))]}
                            if '/artifacts?' in path:return {'artifacts':[dict(id=run*10+i,name=n,expired=False,archive_download_url=f'{run}-{kind}') for i,n,kind in ((1,'release-candidate','candidate'),(2,'verification-evidence','evidence'))]}
                            return dict(id=run,head_sha=m['commit'],head_branch='main',event='workflow_dispatch',conclusion='success' if run==42 or self.original_ci_success else 'failure',display_title='release: fixture',path='.github/workflows/build.yml',run_attempt=1)
                        if path.startswith('releases?'):self.boundary('list releases');return [self.item]
                        if method=='DELETE':
                            i=int(path.rsplit('/',1)[1]);self.item['assets']=[a for a in self.item['assets'] if a['id']!=i];self.boundary('delete '+str(i));return None
                        if value:self.item.update(value)
                        return self.item
                    def call(self,*args):
                        p=Path(args[3]);i=max(self.data)+1;self.data[i]=p.read_bytes()
                        self.item['assets'].append(asset(i,p.name,self.data[i]));self.boundary('upload '+p.name)
                    def download(self,path,**kwargs):
                        if '/assets/' in path:
                            i=int(path.split('/assets/')[1].split('?')[0]);self.boundary('download '+str(i))
                            if not any(a['id']==i for a in self.item['assets']):raise release.ReleaseError('deleted artificial asset')
                            for item in self.item['assets']:
                                if item['id']==i:item['download_count']=item.get('download_count',0)+1
                            return self.data[i]
                        run,kind=path.split('-');m,p,apk=(manifest,plan,new) if run=='42' else (old_manifest,old_plan,old)
                        return self_artifact(m,apk) if kind=='candidate' else evidence(m,p,int(run))
                self_artifact=self.artifact;github=Fake();tool=release.Release(root,github)
                if channel is not None:
                    pending=root/'.local-notes/release-state'/('9.9.9-'+'a'*40+'-pending.json')
                    pending.write_text(json.dumps(dict(schema=1,version='9.9.9',commit='a'*40,channel=channel)),encoding='utf-8')
                def git(*args,**kwargs):return SimpleNamespace(returncode=0,stdout=b'a'*40 if args==('rev-parse','HEAD') else b'')
                tool.git=git
                def signed_identity(path,m):
                    self.assertEqual(apk_payload(path),(m['payloadSha256'],m['buildUtc']))
                    return release.update_manifest(path,m,m)
                tool.signed_identity=signed_identity
                def sign(args,**kwargs):
                    destination=Path(args[args.index('-OutDir')+1])/f"StudyAssistant-{manifest['version']}-{manifest['buildUtc']}.apk"
                    shutil.copy(args[args.index('-InApk')+1],destination)
                (root/'tools').mkdir();shutil.copy(ROOT/'tools/check-secrets.py',root/'tools/check-secrets.py')
                notes=root/'notes.md';notes.write_text('## 更新内容\nSynthetic replacement',encoding='utf-8')
                with patch.object(release,'make_plan',return_value=plan),patch.object(release,'command',side_effect=sign):
                    if failure:
                        with self.assertRaises(release.ReleaseError):tool.publish(42,notes,replacement=True)
                        self.assertTrue(github.failed,'must reach the selected remote boundary')
                        if failure=='list releases':
                            github.item.update(body='updated presentation',updated_at='2099-01-01T00:00:00Z')
                            github.item['assets'].reverse()
                            for item in github.item['assets']:item['uploader']={'login':'synthetic-uploader'}
                        if failure=='replacement marker network':
                            self.assertIn('replacement',tool.state)
                            self.assertTrue((root/tool.state['signedPath']).with_name('update-manifest.json').exists())
                        conflict=asset(99,'unexpected.apk',b'conflict');github.item['assets'].append(conflict)
                        with self.assertRaises(release.ReleaseError):tool.publish(42,notes,replacement=True)
                        github.item['assets'].remove(conflict)
                    self.assertEqual(tool.publish(42,notes,replacement=True)['phase'],'archived')
                    self.assertEqual(tool.publish(42,notes,replacement=True)['phase'],'archived')
                    self.assertEqual(tool.verify(42)['phase'],'archived')
                    github.original_ci_success=False
                    with self.assertRaises(release.ReleaseError):tool.publish(42,notes,replacement=True)
                    github.original_ci_success=True
                    record=next(iter(tool.state['originalBaselineProof']['files'].values()))
                    path=root/record['path'];path.write_bytes(path.read_bytes()+b'tampered')
                    with self.assertRaises(release.ReleaseError):tool.publish(42,notes,replacement=True)

    def test_build_states_bind_run_and_preserve_legacy(self):
        legacy=self.root/'.local-notes/release-state/9.9.9.json'
        legacy.parent.mkdir(parents=True); legacy.write_text('legacy sentinel')
        tool=release.Release(self.root)
        tool.bind_build('9.9.9','a'*40,42,'20261024120000')
        first=tool.path; tool.state['signedSha256']='b'*64; tool.save()
        tool.bind_build('9.9.9','c'*40,43,'20261025120000')
        self.assertNotEqual(first,tool.path); self.assertNotIn('signedSha256',tool.state)
        tool.bind_build('9.9.9','a'*40,42,'20261024120000')
        self.assertEqual(tool.state['signedSha256'],'b'*64)
        self.assertEqual(legacy.read_text(),'legacy sentinel')
        with self.assertRaises(release.ReleaseError): tool.bind_build('9.9.9','c'*40,42,'20261024120000')
        with self.assertRaises(release.ReleaseError): tool.bind_build('9.9.9','a'*40,42,'20261025120000')

    def test_legacy_status_is_repeatable_without_modifying_original_state(self):
        folder=self.root/'.local-notes/release-state';folder.mkdir(parents=True)
        legacy=folder/'9.9.9.json'
        original=json.dumps(dict(schema=1,version='9.9.9',commit='a'*40))
        legacy.write_text(original)
        tool=release.Release(self.root)
        self.assertEqual(tool.load_status('9.9.9'),tool.load_status('9.9.9'))
        self.assertEqual(legacy.read_text(),original)

    def test_replacement_resumes_remote_mutations_without_switching_manifest_early(self):
        old=self.root/'old.apk'; old.write_bytes(b'old artificial apk')
        new=self.root/'new.apk'; new.write_bytes(b'new artificial apk')
        meta=self.root/'update-manifest.json'; meta.write_bytes(b'new artificial manifest')
        def asset(i,name,data):
            return dict(id=i,name=name,size=len(data),digest='sha256:'+release.hashlib.sha256(data).hexdigest())
        for fail_at in range(1,8):
            class Fake:
                def __init__(self):
                    self.assets=[asset(1,old.name,old.read_bytes()),asset(2,meta.name,b'old manifest')]
                    self.data={1:old.read_bytes(),2:b'old manifest'}; self.count=0; self.failed=False; self.events=[]
                def boundary(self,event):
                    self.events.append(event); self.count+=1
                    if self.count==fail_at and not self.failed:
                        self.failed=True; raise release.ReleaseError('artificial interruption')
                def api(self,path,value=None,method=None,**kwargs):
                    if method=='DELETE':
                        i=int(path.rsplit('/',1)[1]); self.assets=[a for a in self.assets if a['id']!=i]
                        self.boundary('delete '+str(i)); return None
                    return dict(id=7,assets=list(self.assets),draft=False,prerelease=False,tag_name='v9.9.9')
                def call(self,*args):
                    p=Path(args[3]); i=max(self.data)+1; data=p.read_bytes()
                    self.data[i]=data; self.assets.append(asset(i,p.name,data)); self.boundary('upload '+p.name)
                def download(self,path,**kwargs):
                    i=int(path.split('/assets/')[1].split('?')[0]); self.boundary('download '+str(i)); return self.data[i]
            github=Fake(); tool=release.Release(self.root,github)
            tool.state=dict(version='9.9.9',replacement=dict(releaseId=7,oldAssets=list(github.assets)))
            tool.save=lambda:None
            for attempt in range(3):
                try: tool.replace_assets(dict(id=7),{new.name:new,meta.name:meta}); break
                except release.ReleaseError:
                    if not github.failed: raise
            self.assertEqual({a['name'] for a in github.assets},{new.name,meta.name})
            self.assertLess(github.events.index('download 3'),github.events.index('delete 2'))
            self.assertLess(github.events.index('upload update-manifest.json'),github.events.index('delete 1'))
            github.assets.append(asset(90,'unexpected.apk',b'conflict'))
            with self.assertRaises(release.ReleaseError): tool.replace_assets(dict(id=7),{new.name:new,meta.name:meta})
            github.assets.pop()
            github.assets[0]['digest']='sha256:'+'0'*64
            with self.assertRaises(release.ReleaseError): tool.replace_assets(dict(id=7),{new.name:new,meta.name:meta})

    def test_explicit_publish_replacement_preserves_tag_and_archives_final_provenance(self):
        import shutil
        work=self.root/'.local-notes/release-state/artifacts/42'; work.mkdir(parents=True)
        signed=Path(shutil.move(self.apk('new.apk'),work/'new.apk'))
        manifest=dict(self.manifest(signed),schema=2,planSha256='d'*64)
        notes=self.root/'notes.md'; notes.write_text('## 更新内容\nArtificial update',encoding='utf-8')
        (self.root/'tools').mkdir(); shutil.copy(ROOT/'tools/check-secrets.py',self.root/'tools/check-secrets.py')
        def asset(i,name,data): return dict(id=i,name=name,size=len(data),digest='sha256:'+release.hashlib.sha256(data).hexdigest())
        class Fake:
            def __init__(self):
                self.data={1:b'artificial old apk',2:b'artificial old metadata'}
                self.item=dict(id=7,tag_name='v9.9.9',draft=False,prerelease=False,html_url='https://example.invalid/release',
                    assets=[asset(1,'old.apk',self.data[1]),asset(2,'update-manifest.json',self.data[2])])
                self.tag_mutations=[]; self.fail_body=True
            def api(self,path,value=None,method=None,**kwargs):
                if path.startswith('git/ref'): return {'object':{'sha':'c'*40}}
                if path.startswith('commits/'): return {'sha':'c'*40}
                if path.startswith('releases?'): return [self.item]
                if method=='DELETE': self.item['assets']=[a for a in self.item['assets'] if a['id']!=int(path.rsplit('/',1)[1])]; return None
                if value:
                    self.item.update(value)
                    if self.fail_body:
                        self.fail_body=False; raise release.ReleaseError('Interrupted after body PATCH')
                return self.item
            def call(self,*args):
                p=Path(args[3]); i=max(self.data)+1; self.data[i]=p.read_bytes()
                self.item['assets'].append(asset(i,p.name,self.data[i]))
            def download(self,path,**kwargs): return self.data[int(path.split('/assets/')[1].split('?')[0])]
        github=Fake(); tool=release.Release(self.root,github)
        tool.bind_build('9.9.9','a'*40,42,manifest['buildUtc'])
        tool.state.update(manifest=manifest,signedPath=str(signed.relative_to(self.root)),signedSha256=sha256(signed))
        tool.save(); tool.verify=lambda run,**kwargs:None
        tool.signed_identity=lambda path,m:release.update_manifest(path,m,m)
        tool.proven_published_source=lambda item:'c'*40
        git_calls=[]
        def git(*args,**kwargs): git_calls.append(args); return SimpleNamespace(returncode=0)
        tool.git=git
        with self.assertRaises(release.ReleaseError): tool.publish(42,notes)
        self.assertNotIn('replacement',tool.state)
        with self.assertRaises(release.ReleaseError): tool.publish(42,notes,replacement=True)
        self.assertEqual(tool.publish(42,notes,replacement=True)['phase'],'archived')
        tool.publish(42,notes,replacement=True)
        self.assertTrue(all(args[0]=='merge-base' for args in git_calls))
        metadata=json.loads((work/'update-manifest.json').read_text(encoding='utf-8'))
        self.assertEqual(metadata['provenance'],dict(sourceCommit='a'*40,runId=42))
        self.assertEqual(metadata['apkSha256'],sha256(signed))
        self.assertEqual({a['name'] for a in github.item['assets']},{signed.name,'update-manifest.json'})
        self.assertEqual(len(list((self.root/'history_apk').iterdir())),2)

    def test_normal_start_rejects_existing_tag_before_checks_or_dispatch(self):
        source=self.root/'app/build.gradle.kts'; source.parent.mkdir(parents=True)
        source.write_text('val appVersionName = "9.9.9"')
        class Fake:
            def api(self,path,**kwargs):
                if path=='git/ref/tags/v9.9.9': return {'object':{'sha':'c'*40}}
                raise AssertionError('Unexpected mutation or release request')
        tool=release.Release(self.root,Fake())
        tool.git=lambda *args,**kwargs:SimpleNamespace(stdout=b'' if args[0]=='status' else b'a'*40,returncode=0)
        with patch.object(release,'command',side_effect=AssertionError('No checks or signing')):
            with self.assertRaises(release.ReleaseError):tool.start('9.9.9')
        self.assertNotIn('replacement',tool.state)

    def test_replacement_start_requires_explicit_flag_and_reaches_existing_checks(self):
        source=self.root/'app/build.gradle.kts';source.parent.mkdir(parents=True)
        source.write_text('val appVersionName = "9.9.9"')
        class ChecksReached(Exception): pass
        class Fake:
            def api(self,path,**kwargs):
                if path.startswith('git/ref/tags/'): return {'object':{'sha':'c'*40}}
                if path=='releases/tags/v9.9.9':return dict(id=7,draft=False,prerelease=False)
                raise AssertionError('No dispatch before Basic')
        tool=release.Release(self.root,Fake());tool.proven_published_source=lambda item:'c'*40
        def git(*args,**kwargs):
            return SimpleNamespace(stdout=b'' if args[0]=='status' else b'[skip ci]' if args[0]=='log' else b'a'*40,returncode=0)
        tool.git=git
        with patch.object(release,'command',side_effect=ChecksReached):
            with self.assertRaises(ChecksReached):tool.start('9.9.9',replacement=True)
        self.assertTrue(tool.state['replacementRequested'])

    def setUp(self):
        folder = ROOT / "app/build/tool-tests"; folder.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=folder); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
    def apk(self, name, code=b"synthetic-code", signature=None):
        path = self.root / name
        with zipfile.ZipFile(path, "w") as z:
            z.writestr("classes.dex", code)
            z.writestr("resources.arsc", b"synthetic-resources")
            z.writestr("assets/build-time-utc.txt", "20261024120000")
            if signature is not None: z.writestr("META-INF/CERT.SF", signature)
        return path
    def manifest(self, apk):
        payload, stamp = apk_payload(apk)
        return dict(schema=1, package="com.zsz.studyassistant",version="9.9.9",versionCode=99,minSdk=28,targetSdk=37,
            commit="a"*40,buildUtc=stamp,candidate="StudyAssistant-9.9.9-"+stamp+"-candidate.apk",
            candidateSha256=sha256(apk),payloadSha256=payload,expectedSigner="b"*64,jvm={"tests":1,"failures":0,"errors":0,"skipped":0})
    def artifact(self, manifest, apk, extra=False):
        out = io.BytesIO()
        with zipfile.ZipFile(out,"w") as z:
            z.writestr("release-manifest.json",json.dumps(manifest))
            z.writestr(manifest["candidate"],apk.read_bytes())
            if extra:z.writestr("secrets.properties",b"synthetic forbidden entry")
        return out.getvalue()
    def test_signing_metadata_is_allowed_but_code_changes_are_not(self):
        plain=self.apk("plain.apk"); signed=self.apk("signed.apk",signature=b"synthetic-signature")
        changed=self.apk("changed.apk",code=b"different-code",signature=b"synthetic-signature")
        self.assertEqual(apk_payload(plain),apk_payload(signed))
        self.assertNotEqual(apk_payload(plain),apk_payload(changed))
    def test_artifact_only_accepts_two_public_allowlisted_files(self):
        apk=self.apk("input.apk"); manifest=self.manifest(apk)
        found,path=extract_candidate(self.artifact(manifest,apk),self.root/"good")
        self.assertEqual(found,manifest);self.assertEqual(sha256(path),manifest["candidateSha256"])
        with self.assertRaises(ValueError):extract_candidate(self.artifact(manifest,apk,True),self.root/"bad")
        self.assertFalse((self.root/"bad").exists())
    def test_candidate_tampering_and_unknown_manifest_fields_are_rejected(self):
        apk=self.apk("input.apk"); manifest=self.manifest(apk)
        bad=dict(manifest,candidateSha256="0"*64)
        with self.assertRaises(ValueError):extract_candidate(self.artifact(bad,apk),self.root/"bad")
        with self.assertRaises(ValueError):validate_manifest(dict(manifest,privateConfig="forbidden"))
    def test_local_state_cannot_retarget_a_version(self):
        path=self.root/"state.json";state=release.bind_state(path,"9.9.9","a"*40);path.write_text(json.dumps(state))
        before=path.read_bytes()
        with self.assertRaises(release.ReleaseError):release.bind_state(path,"9.9.9","b"*40)
        self.assertEqual(before,path.read_bytes())
    def test_state_cannot_select_a_private_file_as_upload(self):
        with self.assertRaises(release.ReleaseError):release.local_artifact(self.root,"secrets.properties")
        with self.assertRaises(release.ReleaseError):release.local_artifact(self.root,"../outside.apk")
    def test_only_the_updates_section_can_be_published(self):
        text="private header\n## 更新内容\n- public change\n\n## Internal\nprivate footer"
        self.assertEqual(release.update_notes(text),"- public change")
        with self.assertRaises(release.ReleaseError):release.update_notes("missing section")
    def test_draft_timeout_can_recover_only_the_exact_owned_draft(self):
        state={"draftAttempted":True,"version":"9.9.9","commit":"a"*40}
        draft={"draft":True,"tag_name":"v9.9.9","target_commitish":"a"*40,"body":"public","assets":[]}
        self.assertTrue(release.recoverable_draft(draft,state,"public"))
        for changed in [dict(draft,draft=False),dict(draft,target_commitish="b"*40),dict(draft,assets=[{}])]:
            self.assertFalse(release.recoverable_draft(changed,state,"public"))
    def test_uncertain_dispatch_is_queried_without_build_or_second_post(self):
        commit="a"*40;now=datetime.now(timezone.utc).isoformat()
        run={"id":123,"head_sha":commit,"display_title":"release: "+commit,"created_at":now,"status":"in_progress","conclusion":None}
        class FakeGitHub:
            def api(self,path,value=None,**kwargs):
                if value is not None: raise AssertionError("Duplicate dispatch")
                return {"workflow_runs":[run]} if "/runs?" in path else run
        tool=release.Release(self.root,FakeGitHub());tool.bind("9.9.9",commit)
        tool.state.update(dispatchAttempted=True,requestedAt=now);tool.save()
        def git(*args,**kwargs):
            if args==("status","--porcelain"):return SimpleNamespace(stdout=b"",returncode=0)
            if args==("rev-parse","HEAD"):return SimpleNamespace(stdout=commit.encode(),returncode=0)
            raise AssertionError("No build/merge/push should repeat")
        tool.git=git
        with patch.object(release,"command",side_effect=AssertionError("No process should run")):
            self.assertEqual(tool.start("9.9.9")["runId"],123)
            self.assertEqual(tool.start("9.9.9")["runId"],123)
    def test_release_requires_both_android_api_suites_to_succeed(self):
        class SourceReached(Exception): pass
        run = dict(event="workflow_dispatch", head_branch="main", conclusion="success", display_title="release: fixture", head_sha="a"*40)
        good = [dict(name=f"Android emulator tests (API {api})", conclusion="success") for api in (35, 36)]
        for jobs, accepted in [(good, True), (good[:1], False), (good[1:], False),
                ([good[0], dict(good[1], conclusion="failure")], False),
                ([dict(name="Android emulator tests", conclusion="success")], False)]:
            class FakeGitHub:
                def api(self, path):
                    return {"jobs": jobs} if "/jobs?" in path else run
            tool = release.Release(self.root, FakeGitHub())
            def reached(commit): raise SourceReached()
            tool.assert_source = reached
            with self.assertRaises(SourceReached if accepted else release.ReleaseError):
                tool.verify(123)

    def test_later_failed_attempt_blocks_older_successful_job(self):
        jobs = [dict(id=1, name="Android emulator tests (API 36)", conclusion="success"),
                dict(id=2, name="Android emulator tests (API 36)", conclusion="failure")]
        with self.assertRaises(release.ReleaseError): release.required_jobs(jobs, [36])
        jobs[1]["conclusion"] = "success"
        release.required_jobs(jobs, [36])

    def test_empty_device_plan_still_requires_build_tools_and_gate(self):
        jobs = [dict(name=name, conclusion="success") for name in ("build", "Windows tool tests", "Verification gate")]
        release.required_jobs(jobs, [], True)
        with self.assertRaises(release.ReleaseError): release.required_jobs(jobs[:-1], [], True)
        with self.assertRaises(release.ReleaseError): release.required_jobs(jobs, [36], True)

    def test_modern_verify_consumes_evidence_before_any_signing(self):
        from verification import digest
        apk = self.apk("input.apk")
        plan = dict(schema=1, policy=1, commit="a"*40, base=None, changedFiles=[], devices=[], mode="release")
        manifest = dict(self.manifest(apk), schema=2, planSha256=digest(plan))
        evidence = dict(schema=1, success=True, runId=42, attempt=1, plan=plan, devices=[],
                        build=dict(commit="a"*40, runId=42, attempt=1, planSha256=digest(plan),
                                   candidateSha256=manifest["candidateSha256"], jvm=manifest["jvm"], apks=None))
        artifacts = [dict(id=1, name="release-candidate", expired=False, archive_download_url="candidate"),
                     dict(id=2, name="verification-evidence", expired=False, archive_download_url="evidence")]
        candidate = self.artifact(manifest, apk)
        class SigningReached(Exception): pass
        class FakeGitHub:
            def api(self, path):
                if "/jobs?" in path:
                    return {"jobs": [dict(id=i, name=n, conclusion="success") for i, n in enumerate(("build", "Windows tool tests", "Verification gate"))]}
                if "/artifacts?" in path: return {"artifacts": artifacts}
                return dict(event="workflow_dispatch", head_branch="main", conclusion="success", display_title="release: fixture", head_sha="a"*40, run_attempt=1)
            def download(self, url):
                if url == "candidate": return candidate
                out = io.BytesIO()
                with zipfile.ZipFile(out, "w") as z: z.writestr("verification.json", json.dumps(evidence))
                return out.getvalue()
        tool = release.Release(self.root, FakeGitHub())
        tool.assert_source = lambda commit: None
        with patch.object(release, "make_plan", return_value={k: v for k, v in plan.items() if k != "mode"}), patch.object(release, "command", side_effect=SigningReached):
            with self.assertRaises(SigningReached): tool.verify(42)
            artifacts[0]['id']=99
            with self.assertRaises(release.ReleaseError):tool.verify(42)
            artifacts[0]['id']=1
            evidence["build"]["candidateSha256"] = "0"*64
            with self.assertRaises(ValueError): tool.verify(42)

    def test_repeated_verify_caches_evidence_bytes_but_rechecks_current_remote_state(self):
        from verification import digest
        apk = self.apk('input.apk')
        plan = dict(schema=1, policy=1, commit='a'*40, base=None, changedFiles=[], devices=[], mode='release')
        manifest = dict(self.manifest(apk), schema=2, planSha256=digest(plan))
        evidence = dict(schema=1, success=True, runId=42, attempt=1, plan=plan, devices=[],
                        build=dict(commit='a'*40, runId=42, attempt=1, planSha256=digest(plan),
                                   candidateSha256=manifest['candidateSha256'], jvm=manifest['jvm'], apks=None))
        def archive():
            out = io.BytesIO()
            with zipfile.ZipFile(out, 'w') as z: z.writestr('verification.json', json.dumps(evidence))
            return out.getvalue()
        data = {'candidate': self.artifact(manifest, apk), 'evidence': archive()}
        artifacts = [dict(id=1, name='release-candidate', expired=False, archive_download_url='candidate'),
                     dict(id=2, name='verification-evidence', expired=False, archive_download_url='evidence',
                          size_in_bytes=len(data['evidence']), digest='sha256:' + hashlib.sha256(data['evidence']).hexdigest())]
        run = dict(event='workflow_dispatch', head_branch='main', conclusion='success', display_title='release: fixture',
                   head_sha='a'*40, run_attempt=1)
        jobs = [dict(id=i, name=n, conclusion='success') for i, n in enumerate(('build', 'Windows tool tests', 'Verification gate'))]
        class GitHub:
            def __init__(self): self.downloads = []; self.queries = []
            def api(self, path):
                self.queries.append(path)
                if '/jobs?' in path: return {'jobs': jobs}
                if '/artifacts?' in path: return {'artifacts': artifacts}
                return run
            def download(self, url): self.downloads.append(url); return data[url]
        tool = release.Release(self.root, GitHub())
        tool.assert_source = lambda commit: None
        class SigningReached(Exception): pass
        with patch.object(release, 'make_plan', return_value={k: v for k, v in plan.items() if k != 'mode'}), patch.object(release, 'command', side_effect=SigningReached):
            for _ in range(2):
                with self.assertRaises(SigningReached): tool.verify(42)
            self.assertEqual(tool.github.downloads, ['candidate', 'evidence'])
            self.assertEqual(tool.github.queries.count('actions/runs/42'), 2)
            run.update(run_attempt=2, conclusion='failure')
            with self.assertRaises(release.ReleaseError): tool.verify(42)
            run['conclusion'] = 'success'
            jobs[-1]['conclusion'] = 'failure'
            with self.assertRaises(release.ReleaseError): tool.verify(42)
            jobs[-1]['conclusion'] = 'success'
            artifacts[1]['expired'] = True
            with self.assertRaises(release.ReleaseError): tool.verify(42)
            artifacts[1]['expired'] = False
            evidence['build']['candidateSha256'] = '0'*64
            data['evidence'] = archive()
            artifacts[1].update(id=3, size_in_bytes=len(data['evidence']), digest='sha256:' + hashlib.sha256(data['evidence']).hexdigest())
            with self.assertRaises(ValueError): tool.verify(42)

    def test_identity_and_debug_candidates_are_rejected(self):
        good="package: name='com.zsz.studyassistant' versionCode='99' versionName='9.9.9'\nsdkVersion:'28'\ntargetSdkVersion:'37'"
        self.assertEqual(apk_identity(good)["versionCode"],99)
        for bad in [good+"\napplication-debuggable",good.replace("sdkVersion:'28'","sdkVersion:'0'"),good.replace("com.zsz.studyassistant","other")]:
            with self.assertRaises(ValueError):apk_identity(bad)
    def test_update_manifest_binds_actual_signed_apk(self):
        apk = self.apk("signed.apk")
        manifest = dict(self.manifest(apk), minSdk=36)
        identity = {k: manifest[k] for k in ("package", "version", "versionCode", "minSdk", "targetSdk")}
        value = release.update_manifest(apk, identity, manifest)
        self.assertEqual(value, dict(schema=1, version="9.9.9", versionCode=99, minSdk=36,
                                    apkName=apk.name, apkSha256=sha256(apk), apkSize=apk.stat().st_size))
        with self.assertRaises(release.ReleaseError): release.update_manifest(apk, dict(identity, minSdk=28), manifest)
        validate_manifest(manifest)

    def test_asset_allowlist_rejects_wrong_existing_assets(self):
        apk = self.apk("signed.apk")
        metadata = self.root / "update-manifest.json"; metadata.write_text("{}")
        files = {apk.name: apk, metadata.name: metadata}
        asset = dict(name=apk.name, digest="sha256:"+sha256(apk), size=apk.stat().st_size)
        release.validate_release_assets([asset], files)
        for assets in ([dict(asset, digest="sha256:"+"0"*64)], [asset, asset],
                       [dict(asset, name="private.txt")], [dict(asset, size=0)]):
            with self.assertRaises(release.ReleaseError): release.validate_release_assets(assets, files)
        with self.assertRaises(release.ReleaseError): release.validate_release_assets([asset], files, complete=True)

    def test_publish_resumes_each_asset_and_checks_both_downloads(self):
        self.verify_publication_resume()

    def test_beta_publication_is_optional_and_never_becomes_latest(self):
        self.verify_publication_resume('beta')

    def test_new_stable_publication_records_its_explicit_channel(self):
        self.verify_publication_resume('stable')

    def verify_publication_resume(self, channel=None):
        import shutil
        work = self.root / ".local-notes/release-state/artifacts/42"
        work.mkdir(parents=True)
        signed = self.apk("signed.apk"); signed = Path(shutil.move(signed, work / signed.name))
        manifest = self.manifest(signed)
        notes = self.root / "notes.md"; notes.write_text("## 更新内容\nSynthetic update", encoding="utf-8")
        (self.root / "tools").mkdir()
        shutil.copy(ROOT / "tools/check-secrets.py", self.root / "tools/check-secrets.py")
        metadata = release.update_manifest(signed, manifest, manifest)
        class FakeGitHub:
            def __init__(self):
                self.uploads = []; self.downloads = []; self.fail_upload = True
                self.item = dict(id=7, tag_name="v9.9.9", draft=True, assets=[], html_url="https://example.invalid/release")
            def api(self, path, value=None, **kwargs):
                if path.startswith("releases?"): return [self.item]
                if path.startswith("git/ref"): return {"object": {"sha": "a"*40}}
                if path.startswith("commits/"): return {"sha": "a"*40}
                if value: self.item.update(value)
                return self.item
            def call(self, *args):
                path = Path(args[3]); self.uploads.append(path.name)
                self.item["assets"].append(dict(id=len(self.uploads), name=path.name, size=path.stat().st_size, digest="sha256:"+sha256(path)))
                if self.fail_upload:
                    self.fail_upload = False
                    raise release.ReleaseError("Interrupted after remote upload")
            def download(self, path, **kwargs):
                asset = next(a for a in self.item["assets"] if "/"+str(a["id"])+"?" in path)
                self.downloads.append(asset["name"])
                return (work / asset["name"]).read_bytes()
        github = FakeGitHub(); tool = release.Release(self.root, github)
        tool.state = dict(manifest=manifest, signedPath=str(signed.relative_to(self.root)), signedSha256=sha256(signed), releaseId=7)
        if channel is not None: tool.state['channel'] = channel
        tool.verify = lambda run: None; tool.save = lambda: None
        tool.signed_identity = lambda path, manifest: metadata
        with self.assertRaises(release.ReleaseError): tool.publish(42, notes)
        tool.publish(42, notes)
        if channel is not None:
            self.assertEqual(github.item['prerelease'], channel == 'beta')
            self.assertEqual(github.item['make_latest'], 'false' if channel == 'beta' else 'true')
            self.assertEqual(metadata['channel'], channel)
        self.assertEqual(github.uploads, [signed.name, "update-manifest.json"])
        self.assertEqual(set(github.downloads), set(github.uploads))
        marker = "<!-- studyassistant-update-manifest\n"
        self.assertIn(marker, github.item["body"])
        inline = github.item["body"].split(marker, 1)[1].split("\n-->", 1)[0]
        self.assertEqual(json.loads(inline), metadata)
        tool.publish(42, notes)
        self.assertEqual(len(github.uploads), 2)
        self.assertEqual(len(list((self.root / "history_apk").iterdir())), 2)
        # An already-owned publication from the old coordinator keeps its bound notes.
        github.item["body"] = "Synthetic update"
        tool.state["notesSha256"] = release.hashlib.sha256(b"Synthetic update").hexdigest()
        tool.publish(42, notes)
        self.assertEqual(github.item["body"], "Synthetic update")
        self.assertEqual(len(github.uploads), 2)

    def test_inline_manifest_is_escaped_and_cannot_be_duplicated_in_notes(self):
        metadata = dict(schema=1, apkName="unsafe-->name.apk")
        body = release.release_body("Readable\n- minimum Android last", metadata)
        self.assertEqual(body.count("-->"), 1)
        self.assertTrue(body.startswith("Readable\n- minimum Android last\n\n<!--"))
        encoded = body.split("<!-- studyassistant-update-manifest\n")[1].split("\n-->")[0]
        self.assertEqual(json.loads(encoded), metadata)
        with self.assertRaises(release.ReleaseError): release.release_body(body, metadata)

    def test_ci_does_not_read_repository_signing_secrets(self):
        workflow=(ROOT/".github/workflows/build.yml").read_text(encoding="utf-8")
        self.assertNotIn("secrets.RELEASE_",workflow)
        self.assertNotIn("secrets.properties <<",workflow)
        self.assertNotIn("path: .",workflow)


class PublishedBaselineTest(unittest.TestCase):
    setUp = ReleaseTest.setUp
    apk = ReleaseTest.apk
    manifest = ReleaseTest.manifest
    artifact = ReleaseTest.artifact
    def baseline_fixture(self, provenance=True):
        import hashlib
        from verification import digest
        candidate = self.apk("candidate.apk")
        signed = self.apk("published.apk", signature=b"synthetic")
        plan = dict(schema=1, policy=1, commit="a"*40, base=None, changedFiles=[], devices=[])
        manifest = dict(self.manifest(candidate), schema=2, planSha256=digest(plan))
        evidence = dict(schema=1, success=True, runId=42, attempt=1, plan=plan, devices=[],
            build=dict(commit="a"*40, runId=42, attempt=1, planSha256=digest(plan),
                       candidateSha256=manifest["candidateSha256"], jvm=manifest["jvm"], apks=None))
        meta = release.update_manifest(signed, manifest, manifest)
        if provenance: meta["provenance"] = dict(sourceCommit="a"*40, runId=42)
        out = io.BytesIO()
        with zipfile.ZipFile(out, "w") as archive: archive.writestr("verification.json", json.dumps(evidence))
        data = {"candidate": self.artifact(manifest, candidate), "evidence": out.getvalue(),
                "apk": signed.read_bytes(), "metadata": json.dumps(meta).encode()}
        def asset(name, key, ident):
            return dict(id=ident, name=name, size=len(data[key]), digest="sha256:" + hashlib.sha256(data[key]).hexdigest())
        published = dict(id=7, tag_name="v9.9.9", draft=False, prerelease=False,
                         assets=[asset(signed.name, "apk", 1), asset("update-manifest.json", "metadata", 2)],
                         body="sourceCommit: " + "f"*40, target_commitish="f"*40)
        run = dict(head_sha="a"*40, conclusion="success", head_branch="main", event="workflow_dispatch",
                   display_title="release: fixture", path=".github/workflows/build.yml", run_attempt=1)
        artifacts = [dict(id=i, name=n, expired=False, archive_download_url=url,
                          size_in_bytes=len(data[url]), digest='sha256:' + hashlib.sha256(data[url]).hexdigest())
                     for i, (n, url) in enumerate([('release-candidate', 'candidate'), ('verification-evidence', 'evidence')], 10)]
        class GitHub:
            def __init__(self): self.downloads = []; self.queries = []
            def api(self, path, **kwargs):
                self.queries.append(path)
                if path.startswith("commits/"): return dict(sha="b"*40)
                if "/artifacts?" in path:
                    return dict(artifacts=artifacts)
                return run
            def download(self, url, **kwargs):
                self.downloads.append(url)
                if "/assets/1?" in url: return data["apk"]
                if "/assets/2?" in url: return data["metadata"]
                return data[url]
        tool = release.Release(self.root, GitHub())
        tool.git = lambda *args, **kwargs: SimpleNamespace(returncode=0)
        return tool, published, run, data, meta

    def test_public_provenance_binds_exact_apk_and_ci_payload(self):
        tool, published, run, data, meta = self.baseline_fixture()
        self.assertEqual(tool.published_baseline(published), "a"*40)
        run["conclusion"] = "failure"
        self.assertEqual(tool.published_baseline(published), "b"*40)
        run["conclusion"] = "success"; run["head_sha"] = "c"*40
        self.assertEqual(tool.published_baseline(published), "b"*40)
        run["head_sha"] = "a"*40
        data["apk"] += b"changed"
        published['assets'][0]['digest'] = 'sha256:' + hashlib.sha256(data['apk']).hexdigest()
        published['assets'][0]['size'] = len(data['apk'])
        self.assertEqual(tool.published_baseline(published), "b"*40)

    def test_baseline_cache_still_rechecks_remote_run_expiry_and_evidence(self):
        tool, published, run, data, meta = self.baseline_fixture()
        self.assertEqual(tool.proven_published_source(published), 'a'*40)
        self.assertEqual(len(tool.github.downloads), 4)
        self.assertEqual(tool.proven_published_source(published), 'a'*40)
        self.assertEqual(len(tool.github.downloads), 4)
        self.assertEqual(tool.github.queries.count('actions/runs/42'), 2)
        run['conclusion'] = 'failure'
        with self.assertRaises(release.ReleaseError): tool.proven_published_source(published)
        run['conclusion'] = 'success'
        artifacts = tool.github.api('actions/runs/42/artifacts?per_page=100')['artifacts']
        artifacts[1]['expired'] = True
        with self.assertRaises(release.ReleaseError): tool.proven_published_source(published)
        artifacts[1]['expired'] = False
        with zipfile.ZipFile(io.BytesIO(data['evidence'])) as archive:
            evidence = json.loads(archive.read('verification.json'))
        evidence['build']['candidateSha256'] = '0'*64
        out = io.BytesIO()
        with zipfile.ZipFile(out, 'w') as archive: archive.writestr('verification.json', json.dumps(evidence))
        data['evidence'] = out.getvalue()
        artifacts[1].update(id=12, size_in_bytes=len(data['evidence']), digest='sha256:' + hashlib.sha256(data['evidence']).hexdigest())
        with self.assertRaises(ValueError): tool.proven_published_source(published)

    def test_legacy_replacement_requires_matching_local_state_not_body(self):
        tool, published, run, data, meta = self.baseline_fixture(False)
        self.assertEqual(tool.published_baseline(published), "b"*40)
        folder = self.root / ".local-notes/release-state"; folder.mkdir(parents=True, exist_ok=True)
        path = folder / "replacement.json"
        state = dict(published=True, phase="archived", releaseId=7, version="9.9.9",
                     signedSha256=meta["apkSha256"], commit="a"*40, runId=42)
        path.write_text(json.dumps(state))
        self.assertEqual(tool.published_baseline(published), "a"*40)
        state["signedSha256"] = "0"*64; path.write_text(json.dumps(state))
        self.assertEqual(tool.published_baseline(published), "b"*40)
        tool.git = lambda *args, **kwargs: SimpleNamespace(returncode=1)
        self.assertIsNone(tool.published_baseline(published))

    def test_readonly_legacy_state_migration_does_not_make_baseline_ambiguous(self):
        tool,published,run,data,meta=self.baseline_fixture(False)
        folder=self.root/'.local-notes/release-state'; folder.mkdir(parents=True)
        state=dict(published=True,phase='archived',releaseId=7,version='9.9.9',
            signedSha256=meta['apkSha256'],commit='a'*40,runId=42)
        for name in ('legacy.json','migrated-build.json'):
            (folder/name).write_text(json.dumps(state))
        self.assertEqual(tool.published_baseline(published),'a'*40)
