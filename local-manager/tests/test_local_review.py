import http.client
import json
from pathlib import Path
import shutil
import tempfile
from threading import Thread
import unittest
from unittest.mock import patch

from score_manager import Catalog, Invalid
from score_manager.local_review import Review, LocalServer
from score_manager.workflow import Workflow, digest
from review_fixture import prepare


@unittest.skipUnless(shutil.which('pdftocairo'), 'Optional local visual adapter requires installed pdftocairo')
class ReviewTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.f=prepare(Path(self.temp.name)/'fixture');self.w=Workflow(self.f['live'])
        self.r=Review(self.w,self.f['draft'],shutil.which('pdftocairo'))

    def reviewed(self,member='legacy-player'):return self.r.inspect(member)
    def state(self):
        with Catalog(self.f['live']) as c:return digest(c)
    def page(self,token,member='legacy-player',page=1):
        return self.r.page(member,token,self.f['part'],self.f['binding'],page)

    def test_complete_summary_contains_every_operation_and_asset_delta_and_is_read_only(self):
        before=self.state();draft_bytes=self.w.draft_path(self.f['draft']).read_bytes()
        r=self.reviewed();summary=r['administratorSummary']
        self.assertEqual(5,len(summary['operations']))
        self.assertEqual({'pieces','piece_parts','score_pdfs','score_pdf_versions','part_score_bindings','local_categories','local_numbers','local_number_history'},{d['table'] for d in summary['assetChanges']})
        number=next(x for x in summary['assetChanges'] if x['table']=='local_numbers')
        self.assertIsNone(number['before']);self.assertEqual(999,number['after']['number'])
        self.assertEqual(self.f['version'],summary['originalFileChanges'][0]['versionId'])
        self.assertEqual(before,self.state());self.assertEqual(draft_bytes,self.w.draft_path(self.f['draft']).read_bytes())

    def test_renderer_returns_distinct_real_png_pages_without_mutation(self):
        r=self.reviewed();before=self.state()
        first=self.page(r['confirmationHash']);second=self.page(r['confirmationHash'],page=2)
        self.assertTrue(first.startswith(b'\x89PNG'));self.assertNotEqual(first,second)
        self.assertGreater(len(first),5000);self.assertEqual(before,self.state())

    def test_unauthorized_member_page_binding_and_part_rejected_before_renderer(self):
        r=self.reviewed();admin=self.reviewed('legacy-admin')
        self.assertEqual([],admin['memberPreview']['pieces'])
        with patch('score_manager.local_review.subprocess.run') as renderer:
            for member,token,part,binding,page in [
                ('legacy-admin',admin['confirmationHash'],self.f['part'],self.f['binding'],1),
                ('legacy-player',r['confirmationHash'],self.f['part'],self.f['binding'],0),
                ('legacy-player',r['confirmationHash'],self.f['part'],self.f['binding'],3),
                ('legacy-player',r['confirmationHash'],'legacy-part',self.f['binding'],1),
                ('legacy-player',r['confirmationHash'],self.f['part'],'missing',1)]:
                with self.subTest(member=member,page=page,part=part,binding=binding),self.assertRaisesRegex(Invalid,'UNAUTHORIZED_PAGE'):
                    self.r.page(member,token,part,binding,page)
            renderer.assert_not_called()

    def test_member_switch_requires_new_hash(self):
        r=self.reviewed()
        with self.assertRaisesRegex(Invalid,'STALE_PREVIEW'):self.page(r['confirmationHash'],member='legacy-admin')

    def test_stale_draft_edit_and_live_renumber_reject_preview(self):
        r=self.reviewed();self.w.edit(self.f['draft'],'add_category',{'code':'95','name':'Later'},request_id='later')
        with self.assertRaisesRegex(Invalid,'STALE_PREVIEW'):self.page(r['confirmationHash'])
        new=self.reviewed()
        with Catalog(self.f['live']) as c:c.assign_number('legacy-piece',number=1001)
        with self.assertRaisesRegex(Invalid,'STALE_DRAFT'):self.page(new['confirmationHash'])

    def test_source_changed_rejects_preview_and_confirmation(self):
        r=self.reviewed();before=self.state();self.f['source'].write_bytes(b'%PDF-changed')
        with self.assertRaises(Invalid):self.page(r['confirmationHash'])
        with self.assertRaises(Invalid):self.w.confirm(self.f['draft'],'legacy-player',r['confirmationHash'])
        self.assertEqual(before,self.state())

    def test_source_change_during_render_rejected(self):
        r=self.reviewed()
        import subprocess
        actual=subprocess.run
        def changed(*a,**kw):
            result=actual(*a,**kw);self.f['source'].write_bytes(b'%PDF-changed');return result
        with patch('score_manager.local_review.subprocess.run',side_effect=changed),self.assertRaises(Invalid):self.page(r['confirmationHash'])

    def test_cancelled_or_committed_draft_cannot_render(self):
        r=self.reviewed();self.w.cancel(self.f['draft'])
        with self.assertRaisesRegex(Invalid,'cancelled'):self.page(r['confirmationHash'])

    def test_renderer_failure_preserves_assets(self):
        r=self.reviewed();before=self.state()
        with patch('score_manager.local_review.subprocess.run',side_effect=OSError('renderer failed')),self.assertRaises(OSError):self.page(r['confirmationHash'])
        self.assertEqual(before,self.state())

    def serve(self):
        server=LocalServer(self.r);thread=Thread(target=server.serve_forever,daemon=True);thread.start()
        self.addCleanup(lambda:(server.shutdown(),thread.join(),server.server_close()))
        return server

    def request(self,server,path,args=None,headers=None):
        conn=http.client.HTTPConnection('127.0.0.1',server.server_port,timeout=60)
        h={'Content-Type':'application/json','X-Local-Review':server.token,'Origin':server.origin};h.update(headers or {})
        conn.request('POST' if args is not None else 'GET',path,json.dumps(args) if args is not None else None,h)
        r=conn.getresponse();result=(r.status,dict(r.getheaders()),r.read());conn.close();return result

    def test_http_loopback_token_origin_host_and_no_pdf_route(self):
        s=self.serve();self.assertEqual('127.0.0.1',s.server_address[0])
        for h in [{'X-Local-Review':'bad'},{'Origin':'http://evil.invalid'},{'Host':'evil.invalid'},{'Sec-Fetch-Site':'cross-site'}]:
            self.assertEqual(409,self.request(s,'/api/status',{},h)[0])
        for path in ['/source.pdf','/../live.sqlite','/api/page?file=private.pdf']:
            self.assertEqual(403,self.request(s,path)[0])
        status,headers,_=self.request(s,'/');self.assertEqual(200,status)
        self.assertEqual('no-store',headers['Cache-Control']);self.assertNotIn('Access-Control-Allow-Origin',headers)

    def test_http_explicit_confirm_repeat_receipt_and_page_denial_after_commit(self):
        s=self.serve();r=self.reviewed();args={'member':'legacy-player','hash':r['confirmationHash'],'confirm':False}
        before=self.state();self.assertEqual(409,self.request(s,'/api/confirm',args)[0]);self.assertEqual(before,self.state())
        args['confirm']=True;first=self.request(s,'/api/confirm',args);second=self.request(s,'/api/confirm',args)
        self.assertEqual(200,first[0]);self.assertEqual(json.loads(first[2]),json.loads(second[2]))
        page={'member':'legacy-player','hash':r['confirmationHash'],'part':self.f['part'],'binding':self.f['binding'],'page':1}
        self.assertEqual(409,self.request(s,'/api/page',page)[0])
        self.assertEqual(409,self.request(s,'/api/cancel',{'confirm':True})[0])
