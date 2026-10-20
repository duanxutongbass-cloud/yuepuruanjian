import http.client
import json
import threading
import unittest
import uuid
from waitress import create_server
from server import Application
import test_permanent_deletion_preview as fixtures
from permanent_deletion import piece_reference


class PermanentHttpTest(unittest.TestCase):
    def setUp(self):
        self.f=fixtures.PermanentPreviewTest();self.f.setUp();self.addCleanup(self.f.doCleanups)
        self.server=create_server(Application(self.f.service,True),host='127.0.0.1',port=0,threads=2)
        self.thread=threading.Thread(target=self.server.run,daemon=True);self.thread.start()
        self.addCleanup(self.close)

    def close(self):
        self.server.task_dispatcher.shutdown();self.server.close();self.thread.join(5)

    def api(self,path,body,token=True):
        headers={'Content-Type':'application/json','X-Device-ID':self.f.seed.device}
        if token:headers['Authorization']='Bearer '+self.f.admin['token']
        client=http.client.HTTPConnection('127.0.0.1',self.server.effective_port,timeout=20)
        try:
            client.request('POST',path,json.dumps(body),headers)
            response=client.getresponse();return response.status,json.loads(response.read())
        finally:client.close()

    def test_real_http_preview_confirm_events_and_stale_scope(self):
        self.f.remove()
        code,preview=self.api('/v1/admin/permanent-deletions/preview',self.f.body())
        self.assertEqual(200,code);self.assertTrue(preview['permanentDeletionReady'])
        body=dict(artifactIds=[self.f.seed.artifact],expectedRevision=preview['revision'],scopeDigest=preview['scopeDigest'],
                  requestId=str(uuid.uuid4()),confirmed=False)
        self.assertEqual(400,self.api('/v1/admin/permanent-deletions',body)[0])
        self.assertTrue((self.f.app.catalog.root/(self.f.seed.artifact+'.pdf')).is_file())
        body['confirmed']=True
        self.assertEqual(401,self.api('/v1/admin/permanent-deletions',body,False)[0])
        self.assertEqual(409,self.api('/v1/admin/permanent-deletions',dict(body,scopeDigest='0'*64))[0])
        code,result=self.api('/v1/admin/permanent-deletions',body)
        self.assertEqual((200,True),(code,result['ok']))
        self.assertEqual((code,result),self.api('/v1/admin/permanent-deletions',body))
        code,page=self.api('/v1/deletions',dict(protocolVersion=1,storageId=None,after='0',limit=100))
        self.assertEqual(200,code);self.assertEqual([piece_reference('sequence:001')],page['jobs'][0]['pieceReferences'])
        self.assertFalse((self.f.app.catalog.root/(self.f.seed.artifact+'.pdf')).exists())
        self.assertEqual(self.f.seed.original,self.f.seed.archive.read_bytes())
