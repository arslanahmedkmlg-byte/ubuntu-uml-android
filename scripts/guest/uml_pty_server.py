#!/usr/bin/python3
"""Native guest PTY sessions; no tmux, screen, or shell multiplexer.

Framing: 1 byte operation + 4-byte unsigned network-order payload length.
Operations: 0=input, 1=resize (rows, cols; two uint16), 2=close,
3=authentication, 4=guest output, 5=guest exited.
"""
import fcntl
import hmac
import os
import pty
import select
import signal
import socket
import struct
import termios
import threading

PORT = 5951
LIMIT = 65536


def get_token():
    with open('/proc/cmdline', encoding='utf-8') as source:
        for entry in source.read().split():
            if entry.startswith('eaglenet_pty_token='):
                return entry.partition('=')[2].encode('ascii')
    return None


def read_exact(conn, length):
    chunks = bytearray()
    while len(chunks) < length:
        data = conn.recv(length - len(chunks))
        if not data:
            raise EOFError()
        chunks.extend(data)
    return bytes(chunks)


def recv_frame(conn):
    op, length = struct.unpack('!BI', read_exact(conn, 5))
    if length > LIMIT:
        raise ValueError('frame too large')
    return op, read_exact(conn, length)


def send_frame(conn, op, data=b''):
    conn.sendall(struct.pack('!BI', op, len(data)) + data)


def client(conn, token):
    pid = None
    fd = None
    try:
        conn.settimeout(5)
        op, payload = recv_frame(conn)
        if op != 3 or not hmac.compare_digest(payload, token):
            return
        conn.settimeout(None)
        pid, fd = pty.fork()
        if pid == 0:
            os.environ['TERM'] = 'xterm-256color'
            os.environ['LANG'] = 'C.UTF-8'
            os.execv('/bin/bash', ['/bin/bash', '--login'])
        alive = threading.Event()
        alive.set()

        def socket_to_pty():
            try:
                while alive.is_set():
                    op, payload = recv_frame(conn)
                    if op == 0 and payload:
                        os.write(fd, payload)
                    elif op == 1 and len(payload) == 4:
                        rows, cols = struct.unpack('!HH', payload)
                        if 1 <= rows <= 300 and 1 <= cols <= 500:
                            fcntl.ioctl(fd, termios.TIOCSWINSZ,
                                        struct.pack('HHHH', rows, cols, 0, 0))
                            os.killpg(pid, signal.SIGWINCH)
                    elif op == 2:
                        break
            except (EOFError, OSError, ValueError):
                pass
            finally:
                alive.clear()

        reader = threading.Thread(target=socket_to_pty, daemon=True)
        reader.start()
        while alive.is_set():
            ready, _, _ = select.select([fd], [], [], 0.5)
            if ready:
                try:
                    output = os.read(fd, 16384)
                except OSError:
                    break
                if not output:
                    break
                send_frame(conn, 4, output)
    except (EOFError, OSError, ValueError):
        pass
    finally:
        if fd is not None:
            try: os.close(fd)
            except OSError: pass
        if pid and pid > 0:
            try: os.killpg(pid, signal.SIGHUP)
            except OSError: pass
            try: os.waitpid(pid, 0)
            except OSError: pass
        conn.close()


def main():
    token = get_token()
    if not token or len(token) != 64:
        raise SystemExit('Missing or invalid PTY session token')
    # The guest's isolated passt interface; not reachable via external LAN.
    address = ('169.254.2.1', PORT)
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server:
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind(address)
        server.listen(12)
        while True:
            conn, _ = server.accept()
            threading.Thread(target=client, args=(conn, token), daemon=True).start()


if __name__ == '__main__':
    main()
