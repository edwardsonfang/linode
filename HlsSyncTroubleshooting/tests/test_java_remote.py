"""Real Java source HTTP -> HLS graph -> S3 PUT/GET -> JSON result tests."""
import http.server
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import threading
import unittest
from urllib.parse import urlsplit, unquote

ROOT = Path(__file__).resolve().parents[1]
CP = os.environ.get('JAVA_REMOTE_CLASSES', str(ROOT / 'target/classes')) + ':' + (ROOT / 'target/classpath.txt').read_text().strip()

class JavaRemoteTests(unittest.TestCase):
    def setUp(self):
        self.routes = {
            '/source/master.m3u8': b'#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nmedia?token=private\n',
            '/source/media?token=private': b'#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI="key.bin"\n#EXT-X-MAP:URI="init.mp4"\n#EXTINF:1,\nsegment.ts?token=private\n#EXTINF:1,\nsegment.ts?token=private\n#EXT-X-ENDLIST\n',
            '/source/key.bin':b'0123456789abcdef', '/source/init.mp4':b'init data',
            '/source/segment.ts?token=private':b'segment data',
        }
        self.objects = {}; self.hits=[]; self.fail_put=False; self.tamper=False; self.transient=0
        case=self
        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self,*args):pass
            def do_GET(self):
                if self.path.startswith('/source/'):
                    case.hits.append(self.path)
                    if case.transient:
                        case.transient-=1
                        self.send_response(503);self.send_header('Retry-After','0');self.send_header('Content-Length','0');self.end_headers();return
                    if self.headers.get('Referer')!='https://player.example.invalid/' or self.headers.get('User-Agent')!='Mozilla/5.0':
                        self.send_error(403);return
                    body=case.routes.get(self.path)
                    if body is None:self.send_error(404);return
                    mime='application/octet-stream'
                else:
                    pair=case.objects.get(unquote(urlsplit(self.path).path))
                    if pair is None:self.send_error(404);return
                    body,mime=pair
                    if case.tamper:body=b'x'+body[1:]
                self.send_response(200);self.send_header('Content-Type',mime);self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
            def do_PUT(self):
                body=self.rfile.read(int(self.headers['Content-Length']))
                if case.fail_put:
                    self.send_response(403);self.send_header('Content-Length','0');self.end_headers();return
                if 'aws-chunked' in self.headers.get('Content-Encoding',''):
                    data=io.BytesIO(body); decoded=bytearray()
                    while True:
                        n=int(data.readline().split(b';')[0].strip(),16)
                        if not n:break
                        decoded.extend(data.read(n));assert data.read(2)==b'\r\n'
                    body=bytes(decoded)
                case.objects[unquote(urlsplit(self.path).path)]=(body,self.headers['Content-Type'])
                self.send_response(200);self.send_header('Content-Length','0');self.end_headers()
        self.server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler)
        threading.Thread(target=self.server.serve_forever,daemon=True).start()
        self.base=f'http://127.0.0.1:{self.server.server_port}'
        self.temp=tempfile.TemporaryDirectory()
        self.work=Path(self.temp.name)/'work'

    def tearDown(self):
        self.server.shutdown();self.server.server_close();self.temp.cleanup()

    def run_java(self):
        env=dict(os.environ,LINODE_ENDPOINT=self.base,LINODE_BUCKET='test-bucket',LINODE_REGION='test-region',LINODE_ACCESS_KEY='test-only',LINODE_SECRET_KEY='test-only',LINODE_PUBLIC_BASE_URL='https://test-bucket.example.com')
        for key in list(env):
            if key.lower() in {'https_proxy','http_proxy','all_proxy'}:env.pop(key)
        return subprocess.run(['java','-cp',CP,'LinodeHlsSync',self.base+'/source/master.m3u8','test-prefix','--referer','https://player.example.invalid/','--work-dir',str(self.work)],env=env,capture_output=True,text=True,timeout=90)

    def test_complete_graph_upload_and_return_urls(self):
        r=self.run_java();self.assertEqual(r.returncode,0,r.stderr)
        result=json.loads(r.stdout)
        self.assertEqual(result['verifiedObjectCount'],5)
        self.assertEqual(result['playlistUrl'],'https://test-bucket.example.com/test-prefix/index.m3u8')
        self.assertEqual(len(result['objects']),len(self.objects))
        self.assertEqual(self.hits.count('/source/segment.ts?token=private'),1)
        self.assertNotIn('token=private',r.stdout)
        for item in result['objects']:
            body,mime=self.objects['/test-bucket/'+item['key']]
            self.assertTrue(item['url'].startswith('https://test-bucket.example.com/'))
            if item['key'].endswith('.m3u8'):
                self.assertEqual(mime,'application/vnd.apple.mpegurl')
                self.assertNotIn(b'token=',body)
                for line in body.decode().splitlines():
                    if line and not line.startswith('#'):self.assertIn('/test-bucket/test-prefix/'+line,self.objects)
        self.assertEqual(json.loads((self.work/'result.json').read_text()),result)

    def test_missing_source_no_upload_no_success(self):
        del self.routes['/source/segment.ts?token=private']
        r=self.run_java();self.assertNotEqual(r.returncode,0);self.assertEqual(r.stdout,'');self.assertEqual(self.objects,{})
        self.assertFalse((self.work/'result.json').exists())

    def test_upload_denied_no_success(self):
        self.fail_put=True
        r=self.run_java();self.assertNotEqual(r.returncode,0);self.assertEqual(r.stdout,'');self.assertFalse((self.work/'result.json').exists())

    def test_readback_mismatch_no_success(self):
        self.tamper=True
        r=self.run_java();self.assertNotEqual(r.returncode,0);self.assertEqual(r.stdout,'');self.assertFalse((self.work/'result.json').exists())

    def test_transient_source_retried(self):
        self.transient=1
        r=self.run_java();self.assertEqual(r.returncode,0,r.stderr)
        self.assertEqual(self.hits.count('/source/master.m3u8'),2)
        self.assertEqual(json.loads(r.stdout)['verifiedObjectCount'],5)

    def test_live_playlist_no_upload(self):
        self.routes['/source/media?token=private']=b'#EXTM3U\n#EXTINF:1,\nsegment.ts\n'
        r=self.run_java();self.assertNotEqual(r.returncode,0);self.assertEqual(self.objects,{})

if __name__=='__main__':unittest.main()
