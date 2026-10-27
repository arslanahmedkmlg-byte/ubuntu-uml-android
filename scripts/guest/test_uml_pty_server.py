import importlib.util
import socket
import struct
import threading
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location('uml_pty_server', Path(__file__).with_name('uml_pty_server.py'))
server = importlib.util.module_from_spec(spec)
spec.loader.exec_module(server)
TOKEN = b'0' * 64


class NativeGuestPtyTests(unittest.TestCase):
    def test_rejects_bad_authentication(self):
        local, remote = socket.socketpair()
        worker = threading.Thread(target=server.client, args=(remote, TOKEN), daemon=True)
        worker.start()
        server.send_frame(local, 3, b'x' * 64)
        local.settimeout(2)
        self.assertEqual(local.recv(1), b'')
        worker.join(3)
        local.close()

    def test_real_controlling_tty_and_shell_input(self):
        local, remote = socket.socketpair()
        worker = threading.Thread(target=server.client, args=(remote, TOKEN), daemon=True)
        worker.start()
        local.settimeout(8)
        server.send_frame(local, 3, TOKEN)
        server.send_frame(local, 1, struct.pack('!HH', 40, 100))
        server.send_frame(local, 0, b'printf "UMLPTY_OK\\n"; tty; stty size\n')
        output = bytearray()
        for _ in range(40):
            op, payload = server.recv_frame(local)
            if op == 4:
                output.extend(payload)
                if b'UMLPTY_OK' in output and b'40 100' in output and b'/dev/pts/' in output:
                    break
        self.assertIn(b'UMLPTY_OK', output)
        self.assertIn(b'/dev/pts/', output)
        self.assertIn(b'40 100', output)
        server.send_frame(local, 2)
        local.close()
        worker.join(5)


if __name__ == '__main__':
    unittest.main()
