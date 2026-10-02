"""Private real-TCP ByteBans smoke with offline-mode Mineflayer clients."""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import queue
import re
import socket
import subprocess
import sys
import threading
import time
from datetime import datetime, timezone
from urllib.request import urlopen

BYTEBANS = Path(__file__).resolve().parents[1]
WORKSPACE = BYTEBANS.parents[1]
sys.path.insert(0, str(WORKSPACE))
from dashboard.servers.transport import SSHTransport

CLIENT = Path(__file__).parent / 'client' / 'probe_client.js'
PAPER_SHA256 = '5ee4f542f628a14c644410b08c94ea42e772ef4d29fe92973636b6813d4eaffc'


class Client:
    def __init__(self, port, name, version='1.21.4'):
        self.name = name
        self.events = queue.Queue()
        self.all_events = []
        self.stderr_lines = []
        self.process = subprocess.Popen(['node', str(CLIENT), '127.0.0.1', str(port), version, name],
                                        stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=subprocess.PIPE, text=True, encoding='utf-8',
                                        errors='replace', bufsize=1,
                                        creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        threading.Thread(target=self._read, daemon=True).start()
        threading.Thread(target=self._read_stderr, daemon=True).start()

    def _read(self):
        for line in self.process.stdout:
            try:
                value = json.loads(line)
            except ValueError:
                value = {'event': 'invalid_json', 'line': line.strip()}
            self.all_events.append(value)
            self.events.put(value)

    def _read_stderr(self):
        for line in self.process.stderr:
            if len(self.stderr_lines) < 200:
                self.stderr_lines.append(line.rstrip())

    def send(self, action, **fields):
        self.process.stdin.write(json.dumps({'action': action, **fields}) + '\n')
        self.process.stdin.flush()

    def expect(self, acceptable, timeout=30):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                event = self.events.get(timeout=max(0.001, min(1, deadline - time.monotonic())))
            except queue.Empty:
                if self.process.poll() is not None:
                    raise RuntimeError(f'{self.name} exited before {acceptable}: {self.all_events}')
                continue
            if event.get('event') in acceptable:
                return event
        raise TimeoutError(f'{self.name} did not emit {acceptable}: {self.all_events}')

    def expect_message(self, fragment, timeout=15):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            event = self.expect({'message'}, max(0.001, deadline - time.monotonic()))
            if fragment.lower() in event.get('message', '').lower():
                return event
        raise TimeoutError(f'{self.name} did not receive a message containing {fragment!r}: {self.all_events}')

    def close(self):
        if self.process.poll() is None:
            try:
                self.send('quit')
                self.process.wait(timeout=3)
            except Exception:
                self.process.kill()
                self.process.wait(timeout=3)


def assert_true(condition, reason):
    if not condition:
        raise AssertionError(reason)


def sha_artifact(path, title):
    data = path.read_bytes()
    return {'title': title, 'filename': path.name, 'sha512': hashlib.sha512(data).hexdigest(),
            'content_base64': base64.b64encode(data).decode('ascii'),
            'compatibility': 'private-testing'}


def local_port():
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', 0))
        return probe.getsockname()[1]


def rcon(transport, instance_id, command):
    response = transport.call('console', id=instance_id, command=command)
    print('rcon', command, repr(response)[:400], flush=True)
    return response


def remote_paper_sha(host, instance_id):
    if not re.fullmatch(r'mw-[a-f0-9]{16}', instance_id):
        raise ValueError('Unexpected managed server identity')
    path = f'/opt/modrinth-workspace/instances/{instance_id}/data/paper-1.21.4-232.jar'
    response = subprocess.run(['ssh', '-o', 'BatchMode=yes', host, 'sha256sum ' + path],
                              capture_output=True, text=True, timeout=20, check=True)
    return response.stdout.split()[0]


def wait_ready(transport, instance_id):
    deadline = time.monotonic() + 300
    while time.monotonic() < deadline:
        try:
            return rcon(transport, instance_id, 'list')
        except RuntimeError:
            time.sleep(5)
    raise TimeoutError('Paper did not make RCON ready')


def displacement(record):
    before, after = record['before'], record['after']
    return sum((before[k] - after[k]) ** 2 for k in ('x', 'y', 'z')) ** 0.5


def server_position(transport, instance_id, name):
    response = rcon(transport, instance_id, 'bbprobe position ' + name)
    match = re.search(r'BBPROBE (\{[^\n]+\})', response)
    if match is None:
        raise RuntimeError('Private server-side position command did not respond')
    record = json.loads(match.group(1))
    if record.get('online') is not True:
        raise RuntimeError(name + ' is not online during the position check')
    return record


def horizontal_displacement(before, after):
    return ((before['x'] - after['x']) ** 2 + (before['z'] - after['z']) ** 2) ** 0.5


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--reuse', help='Reuse a stopped matching ByteBans private target by managed ID')
    parser.add_argument('--jar', type=Path, help='Exact ByteBans candidate JAR (default: frozen/release/ByteBans-1.1.0.jar)')
    parser.add_argument('--source-revision', help='Git revision that produced --jar (default: frozen source revision, or current HEAD for a custom JAR)')
    args = parser.parse_args()
    config = json.loads((WORKSPACE / 'config' / 'test_servers.json').read_text(encoding='utf-8'))
    if config.get('eula_accepted') is not True:
        raise SystemExit('Minecraft test-server EULA acceptance is not configured')
    jar = args.jar.resolve() if args.jar else Path(__file__).parent / 'frozen/release/ByteBans-1.1.0.jar'
    artifact = sha_artifact(jar, 'ByteBans frozen 1.1.0 beta candidate')
    helper = sha_artifact(Path(__file__).parent / 'frozen/release/bytebans-private-probe-1.0.0.jar',
                          'Separate ByteBans private server-state probe')
    profile = json.load(urlopen('https://api.minecraftservices.com/minecraft/profile/lookup/name/'
                                + config['operator'], timeout=20))
    run_folder = Path(__file__).parent / 'runs' / datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ-offline')
    run_folder.mkdir(parents=True, exist_ok=False)
    revision = (args.source_revision or
                ('70d3daff4b0af93338d95b207ee596c5e52a43fc' if args.jar is None else
                 subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=BYTEBANS,
                                         text=True).strip()))
    if not re.fullmatch(r'[a-f0-9]{40}', revision):
        raise SystemExit('Source revision must be a full Git SHA-1')
    evidence = {'source_revision': revision,
                'script_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                'client_sha256': hashlib.sha256(CLIENT.read_bytes()).hexdigest(),
                'candidate_sha512': artifact['sha512'], 'probe_sha512': helper['sha512'],
                'version': '1.21.4', 'paper_build': 232, 'paper_sha256_expected': PAPER_SHA256,
                'java': 21, 'connection_mode': 'standalone_offline',
                'started_at': datetime.now(timezone.utc).isoformat(), 'checks': {}}
    transport = SSHTransport(config['ssh_host'])
    transport.installed = True
    instance = None
    tunnel = None
    clients = []
    try:
        if args.reuse:
            if not re.fullmatch(r'mw-[a-f0-9]{16}', args.reuse):
                raise ValueError('Unexpected managed server identity')
            found = [item for item in transport.call('list') if item['id'] == args.reuse]
            if len(found) != 1:
                raise ValueError('Managed ByteBans target was not found')
            instance = found[0]
            artifacts = {item['filename']: item for item in instance['artifacts']}
            if (instance.get('owner') != 'modrinth-workspace-v1'
                    or instance.get('name') != 'ByteBans private offline TCP probe'
                    or instance.get('status') not in {'exited', 'stopped'}
                    or instance.get('loader') != 'PAPER' or instance.get('version') != '1.21.4'
                    or instance.get('paper_build') != 232 or instance.get('java') != 21
                    or instance.get('connection_mode') != 'standalone_offline'
                    or instance.get('bind_ip') != '127.0.0.1'
                    or not 27200 <= instance.get('port', 0) <= 27299
                    or artifacts.get(artifact['filename'], {}).get('sha512') != artifact['sha512']
                    or artifacts.get(helper['filename'], {}).get('sha512') != helper['sha512']):
                raise ValueError('Stopped target does not match the frozen ByteBans probe pins')
            evidence['reused_instance'] = True
        else:
            instance = transport.call('create', name='ByteBans private offline TCP probe',
                                      loader='PAPER', version='1.21.4', paper_build=232,
                                      build_channel='PINNED', java=21, memory_gb=4, cpus=4,
                                      operator=profile, eula_accepted=True,
                                      bind_ip='127.0.0.1', join_host='127.0.0.1',
                                      first_port=27200, last_port=27299,
                                      connection_mode='standalone_offline', artifacts=[artifact, helper])
        evidence['instance_id'] = instance['id']
        evidence['port'] = instance['port']
        print('created', instance['id'], instance['port'], flush=True)
        transport.call('start', id=instance['id'])
        wait_ready(transport, instance['id'])
        evidence['paper_sha256_actual'] = remote_paper_sha(config['ssh_host'], instance['id'])
        assert_true(evidence['paper_sha256_actual'] == PAPER_SHA256, 'Paper server binary hash did not match frozen pin')
        rcon(transport, instance['id'], 'whitelist off')
        client_port = local_port()
        tunnel = subprocess.Popen(['ssh', '-N', '-o', 'BatchMode=yes',
                                   '-o', 'ExitOnForwardFailure=yes',
                                   '-L', f'127.0.0.1:{client_port}:127.0.0.1:{instance["port"]}',
                                   config['ssh_host']], stdout=subprocess.DEVNULL,
                                  stderr=subprocess.PIPE,
                                  creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        time.sleep(1)
        assert_true(tunnel.poll() is None, 'SSH loopback tunnel failed')

        first = Client(client_port, 'BBProbeA')
        clients.append(first)
        assert_true(first.expect({'spawn'}, 45)['event'] == 'spawn', 'first offline client did not join')
        evidence['checks']['real_offline_join'] = True
        time.sleep(3)  # ByteBans persists the joined address asynchronously.
        rcon(transport, instance['id'], 'ipban user:BBProbeA reason:Private TCP probe scope:*')
        kicked = first.expect({'kicked'}, 20)
        assert_true('ban' in json.dumps(kicked).lower(), 'connected client was kicked for another reason')
        evidence['checks']['ip_ban_kicks_existing_client'] = kicked

        rejected = Client(client_port, 'BBProbeB')
        clients.append(rejected)
        denial = rejected.expect({'spawn', 'kicked'}, 25)
        assert_true(denial['event'] == 'kicked'
                    and 'ban' in json.dumps(denial).lower(),
                    'second client was not denied specifically by the IP ban')
        evidence['checks']['ip_ban_rejects_shared_address'] = denial
        rcon(transport, instance['id'], 'ipunban user:BBProbeA reason:Private TCP cleanup')
        time.sleep(3)

        second = Client(client_port, 'BBProbeB')
        clients.append(second)
        second.expect({'spawn'}, 45)
        evidence['checks']['ip_unban_restores_join'] = True
        rcon(transport, instance['id'], 'ipmute user:BBProbeA reason:Private TCP probe scope:*')
        time.sleep(2)
        second.send('chat', message='ByteBans blocked chat probe')
        second.expect({'chat_sent'}, 5)
        muted = second.expect_message('muted', 10)
        evidence['checks']['ip_mute_blocks_shared_address_chat'] = muted
        rcon(transport, instance['id'], 'ipunmute user:BBProbeA reason:Private TCP cleanup')
        time.sleep(2)

        rcon(transport, instance['id'], 'freeze user:BBProbeB reason:Private TCP probe scope:*')
        time.sleep(2)
        frozen_before = server_position(transport, instance['id'], 'BBProbeB')
        assert_true(frozen_before['freeze_id'] is not None and not frozen_before['bypass'],
                    'Server has no active freeze or player is bypassing it')
        second.send('move', duration_ms=2000)
        held_client = second.expect({'moved'}, 8)
        time.sleep(1)
        frozen_after = server_position(transport, instance['id'], 'BBProbeB')
        held = horizontal_displacement(frozen_before, frozen_after)
        assert_true(held < 0.3, f'Frozen player moved {held:.2f} server-side blocks')
        evidence['checks']['freeze_holds_real_client'] = {
            'server_before': frozen_before, 'server_after': frozen_after,
            'server_horizontal_blocks': held, 'client_prediction': held_client}
        rcon(transport, instance['id'], 'unfreeze user:BBProbeB reason:Private TCP cleanup')
        time.sleep(2)
        free_before = server_position(transport, instance['id'], 'BBProbeB')
        assert_true(free_before.get('freeze_id') is None, 'Server still has active freeze after unfreeze')
        second.send('move', duration_ms=2000)
        free_client = second.expect({'moved'}, 8)
        time.sleep(1)
        free_after = server_position(transport, instance['id'], 'BBProbeB')
        free = horizontal_displacement(free_before, free_after)
        assert_true(free > 0.75, f'Unfrozen player moved only {free:.2f} server-side blocks')
        evidence['checks']['unfreeze_restores_real_movement'] = {
            'server_before': free_before, 'server_after': free_after,
            'server_horizontal_blocks': free, 'client_prediction': free_client}
        evidence['status'] = 'pass'
    except Exception as error:
        evidence['status'] = 'fail'
        evidence['error'] = repr(error)
    finally:
        evidence['clients'] = {c.name + '-' + str(i): c.all_events for i, c in enumerate(clients)}
        evidence['client_stderr'] = {c.name + '-' + str(i): c.stderr_lines for i, c in enumerate(clients)}
        for client in clients:
            client.close()
        if tunnel is not None:
            tunnel.terminate()
            try:
                tunnel.wait(timeout=3)
            except subprocess.TimeoutExpired:
                tunnel.kill()
                tunnel.wait(timeout=3)
        if instance is not None:
            try:
                evidence['logs'] = transport.call('logs', id=instance['id'])
                transport.call('stop', id=instance['id'])
                evidence['stopped'] = True
            except Exception as error:
                evidence['stop_error'] = repr(error)
        transport.close()
        evidence['finished_at'] = datetime.now(timezone.utc).isoformat()
        (run_folder / 'result.json').write_text(json.dumps(evidence, indent=2) + '\n', encoding='utf-8')
        report = [
            '# ByteBans real offline-client probe', '',
            f"Status: **{evidence['status']}**",
            f"Server: Paper {evidence['version']} build {evidence['paper_build']} / Java {evidence['java']}, offline mode",
            f"Instance: `{evidence.get('instance_id', 'not created')}` (retained; stopped: {evidence.get('stopped', False)})",
            f"ByteBans SHA-512: `{evidence['candidate_sha512']}`",
            f"Paper SHA-256: `{evidence.get('paper_sha256_actual', 'not measured')}`",
            '', 'Checks:', '',
        ]
        for name, value in evidence['checks'].items():
            report.append(f"- {name}: {'pass' if value else 'fail'}")
        if evidence.get('error'):
            report.extend(['', f"Failure: `{evidence['error']}`"])
        report.extend(['', 'The first login, IP-ban and IP-mute stages use real Minecraft TCP clients. '
                       'Freeze movement is judged from the server-side test helper; client prediction is contextual.'])
        (run_folder / 'report.md').write_text('\n'.join(report) + '\n', encoding='utf-8')
        print('status', evidence['status'], 'evidence', run_folder, flush=True)
    if evidence['status'] != 'pass' or not evidence.get('stopped'):
        raise SystemExit(1)


if __name__ == '__main__':
    main()
