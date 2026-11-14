import sys
import os
import time
import json
from http.server import HTTPServer, BaseHTTPRequestHandler
import threading

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gepy_laptop_daemon import AntigravityA2AEngine

class MockCallbackServer:
    def __init__(self):
        self.received_callbacks = []
        outer = self
        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                content_len = int(self.headers.get("Content-Length", 0))
                body = self.rfile.read(content_len).decode("utf-8")
                outer.received_callbacks.append(json.loads(body))
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(b'{"status":"DELIVERED"}')

            def log_message(self, format, *args):
                pass # Silent

        self.server = HTTPServer(("127.0.0.1", 0), Handler)
        self.port = self.server.server_port
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def shutdown(self):
        self.server.shutdown()
        self.server.server_close()

def test_engine():
    print("Testing AntigravityA2AEngine...")
    
    # 1. Test WORKSPACE_HEALTH
    r1 = AntigravityA2AEngine.handle_rpc({"action": "WORKSPACE_HEALTH"})
    print("WORKSPACE_HEALTH:", r1["status"], "-", r1["summary_tts"])
    assert r1["status"] == "SUCCESS"
    assert "branch" in r1["result_data"]
    
    # 2. Test GIT_STATUS
    r2 = AntigravityA2AEngine.handle_rpc({"action": "GIT_STATUS"})
    print("GIT_STATUS:", r2["status"], "-", r2["summary_tts"])
    assert r2["status"] == "SUCCESS"
    assert "branch" in r2["result_data"]

    # 3. Test READ_STATE
    r3 = AntigravityA2AEngine.handle_rpc({"action": "READ_STATE"})
    print("READ_STATE:", r3["status"], "-", r3["summary_tts"])
    assert r3["status"] == "SUCCESS"

    # 4. Test ASK_AGENT
    r4 = AntigravityA2AEngine.handle_rpc({"action": "ASK_AGENT", "prompt": "Audit status arsitektur"})
    print("ASK_AGENT:", r4["status"], "-", r4["summary_tts"])
    assert r4["status"] == "SUCCESS"

    # 5. Test UNKNOWN ACTION
    r5 = AntigravityA2AEngine.handle_rpc({"action": "INVALID_ACTION_XYZ"})
    print("INVALID_ACTION:", r5["status"], "-", r5["summary_tts"])
    assert r5["status"] == "ERROR"

    # 6. Test ASYNCHRONOUS CALLBACK & MOCK WEBHOOK DISPATCH
    print("\nTesting Asynchronous Callback Dispatch...")
    mock_server = MockCallbackServer()
    callback_url = f"http://127.0.0.1:{mock_server.port}/api/antigravity/callback"

    # Trigger async RPC for GIT_STATUS
    r_async = AntigravityA2AEngine.handle_rpc({
        "action": "GIT_STATUS",
        "async": True,
        "callback_url": callback_url,
        "request_id": "test_async_123"
    })
    print("ASYNC GIT_STATUS immediate response:", r_async["status"], "-", r_async["summary_tts"])
    assert r_async["status"] == "PENDING"
    assert r_async["request_id"] == "test_async_123"

    # Wait briefly for worker thread to execute and POST callback
    for _ in range(50):
        if len(mock_server.received_callbacks) > 0:
            break
        time.sleep(0.1)

    assert len(mock_server.received_callbacks) == 1, "Mock server did not receive callback!"
    cb = mock_server.received_callbacks[0]
    print("Received Webhook Callback:", cb["status"], "-", cb["action"], "-", cb["summary_tts"])
    assert cb["status"] == "SUCCESS"
    assert cb["action"] == "GIT_STATUS"
    assert cb["request_id"] == "test_async_123"

    # 7. Test GET_LAST_ASYNC_RESULT & CHECK_TASK_STATUS
    r_last = AntigravityA2AEngine.handle_rpc({"action": "GET_LAST_ASYNC_RESULT"})
    print("GET_LAST_ASYNC_RESULT:", r_last["status"], "-", r_last["action"])
    assert r_last["status"] == "SUCCESS"
    assert r_last["action"] == "GIT_STATUS"

    r_check = AntigravityA2AEngine.handle_rpc({"action": "CHECK_TASK_STATUS"})
    print("CHECK_TASK_STATUS:", r_check["status"], "-", r_check["action"])
    assert r_check["status"] == "SUCCESS"
    assert r_check["action"] == "GIT_STATUS"

    # 8. Test Automatic Failover Retry to fallback_ip when primary callback URL is unreachable
    print("\nTesting Callback Failover to fallback_ip...")
    mock_server_failover = MockCallbackServer()
    # Primary URL pointing to dead port 59999
    dead_primary_url = "http://127.0.0.1:59999/api/antigravity/callback"
    r_failover = AntigravityA2AEngine.handle_rpc({
        "action": "WORKSPACE_HEALTH",
        "async": True,
        "callback_url": dead_primary_url,
        "client_ip": f"127.0.0.1:{mock_server_failover.port}", # Used as fallback host
        "request_id": "test_failover_456"
    })
    assert r_failover["status"] == "PENDING"

    for _ in range(50):
        if len(mock_server_failover.received_callbacks) > 0:
            break
        time.sleep(0.1)

    assert len(mock_server_failover.received_callbacks) == 1, "Fallback server did not receive callback after primary failure!"
    cb_failover = mock_server_failover.received_callbacks[0]
    print("Received Failover Webhook Callback:", cb_failover["status"], "-", cb_failover["action"])
    assert cb_failover["status"] == "SUCCESS"
    assert cb_failover["request_id"] == "test_failover_456"

    mock_server_failover.shutdown()
    mock_server.shutdown()
    print("\n[V] All AntigravityA2AEngine local, async callback, and failover tests passed 100%!")

if __name__ == "__main__":
    test_engine()
