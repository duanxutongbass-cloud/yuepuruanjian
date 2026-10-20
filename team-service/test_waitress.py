import http.client
import json
import threading
import unittest
from waitress import create_server
from server import Application
from test_accounts import AccountFixture, PASSWORD


class WaitressTest(AccountFixture):
    def test_live_runner_only_accepts_https_declaration_from_configured_proxy(self):
        server = create_server(Application(self.service), host="127.0.0.1", port=0, threads=2,
                               trusted_proxy="127.0.0.1", trusted_proxy_count=1,
                               trusted_proxy_headers={"x-forwarded-proto"}, clear_untrusted_proxy_headers=True)
        thread = threading.Thread(target=server.run, daemon=True)
        thread.start()
        body = json.dumps(dict(username="director", password=PASSWORD, deviceId=self.device, deviceName="Proxy fixture"))
        def request(forwarded=False, source="127.0.0.1"):
            client = http.client.HTTPConnection("127.0.0.1", server.effective_port, timeout=20, source_address=(source, 0))
            headers = {"Content-Type": "application/json"}
            if forwarded:
                headers["X-Forwarded-Proto"] = "https"
            try:
                client.request("POST", "/v1/auth/login", body, headers)
                response = client.getresponse()
                result = response.status, json.loads(response.read())
                return result
            finally:
                client.close()
        try:
            self.assertEqual(403, request()[0])
            self.assertEqual(200, request(True)[0])
            self.assertEqual(403, request(True, "127.0.0.2")[0])
        finally:
            server.task_dispatcher.shutdown()
            server.close()
            thread.join(5)


if __name__ == "__main__":
    unittest.main()
