import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
import unittest
from unittest.mock import patch

MODULE_PATH = Path(__file__).resolve().parents[1] / "scripts" / "casca_mcp_client.py"
spec = importlib.util.spec_from_file_location("casca_client", MODULE_PATH)
casca = importlib.util.module_from_spec(spec)
spec.loader.exec_module(casca)

TOKEN = "casca_" + "A" * 43


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_POST(self):
        size = int(self.headers.get("Content-Length", "0"))
        body = json.loads(self.rfile.read(size))
        if self.path == "/pair":
            self.send_response(200)
            self.end_headers()
            self.wfile.write("Confirme no celular o código 12 para Jatoba.\n".encode())
            self.wfile.flush()
            self.wfile.write(("Aprovado.\nCASCA_TOKEN=" + TOKEN + "\n").encode())
            self.wfile.flush()
            return
        if self.path != "/mcp":
            self.send_error(404)
            return
        if self.headers.get("Authorization") != "Bearer " + TOKEN:
            self.send_error(401)
            return
        method = body.get("method")
        if method == "initialize":
            result = {
                "protocolVersion": "2025-06-18", "serverInfo": {"name": "casca-de-bala"},
            }
        elif method == "tools/list":
            result = {"tools": [{"name": "casca_capabilities"}, {"name": "band_snapshot"}]}
        else:
            result = {"content": [{"type": "text", "text": "synthetic snapshot"}]}
        reply = json.dumps({"jsonrpc": "2.0", "id": body["id"], "result": result}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(reply)))
        self.end_headers()
        self.wfile.write(reply)


class CascaClientTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.env = patch.dict(os.environ, {"XDG_CONFIG_HOME": self.temp.name})
        self.env.start()
        self.server = HTTPServer(("127.0.0.1", 0), Handler)
        self.worker = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.worker.start()
        self.url = "http://127.0.0.1:" + str(self.server.server_port)

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.worker.join(timeout=2)
        self.env.stop()
        self.temp.cleanup()

    def run_client(self, command, *options):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            exit_code = casca.main(["--url", self.url, command, *options])
        return exit_code, out.getvalue(), err.getvalue()

    def test_pair_and_doctor_redact_token(self):
        code, output, err = self.run_client("pair")
        self.assertEqual((code, err), (0, ""))
        self.assertIn("código 12", output)
        self.assertNotIn(TOKEN, output)
        self.assertEqual(casca.state_path().stat().st_mode & 0o777, 0o600)
        self.assertEqual(casca.state_path().parent.stat().st_mode & 0o777, 0o700)
        code, output, err = self.run_client("doctor")
        self.assertEqual((code, err), (0, ""))
        reply = json.loads(output)
        self.assertTrue(reply["connected"])
        self.assertEqual(reply["tools"], ["casca_capabilities", "band_snapshot"])
        self.assertNotIn(TOKEN, output)

    def test_import_previous_pairing_without_echo(self):
        existing = Path(self.temp.name) / "pair.txt"
        existing.write_text("Aprovado.\nCASCA_TOKEN=" + TOKEN + "\n")
        existing.chmod(0o600)
        code, output, err = self.run_client("import-pair", str(existing))
        self.assertEqual((code, err), (0, ""))
        self.assertNotIn(TOKEN, output)
        code, output, err = self.run_client("snapshot")
        self.assertEqual((code, err), (0, ""))
        self.assertIn("synthetic snapshot", output)

    def test_reject_world_readable_import(self):
        existing = Path(self.temp.name) / "pair.txt"
        existing.write_text("CASCA_TOKEN=" + TOKEN)
        existing.chmod(0o644)
        code, _, err = self.run_client("import-pair", str(existing))
        self.assertEqual(code, 1)
        self.assertIn("0600", err)

    def test_reject_wrong_destination_and_remote_hosts(self):
        for addr in (
            "http://8.8.8.8:8765", "http://100.63.99.1:8765",
            "https://100.92.199.84:8765", "http://example.com:8765",
            "http://127.0.0.1:8765/evil", "http://x:pw@127.0.0.1:8765",
        ):
            with self.subTest(addr=addr):
                with self.assertRaises(casca.CascaError):
                    casca.checked_url(addr)
        self.assertEqual(casca.checked_url("http://100.92.199.84:8765"), "http://100.92.199.84:8765")
        self.assertEqual(casca.checked_url("http://[fd7a:115c:a1e0::1]:8765"), "http://[fd7a:115c:a1e0::1]:8765")

    def test_wrong_token_gives_401_without_exposing_token(self):
        casca.save_token(self.url, "casca_" + "Z" * 43)
        code, output, err = self.run_client("doctor")
        self.assertEqual(code, 1)
        self.assertEqual(output, "")
        self.assertIn("401", err)
        self.assertNotIn(TOKEN, err)


if __name__ == "__main__":
    unittest.main()
