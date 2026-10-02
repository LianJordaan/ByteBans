"""Collect the first live synthetic probe from a pre-existing managed ByteBans server."""
import hashlib
import json
from pathlib import Path
import subprocess
from datetime import datetime, timezone

from run_matrix import BYTEBANS, remote_result
from dashboard.servers.transport import SSHTransport

INSTANCE_ID = 'mw-cdbc572ba3474863'
HOST = 'root@za.bytebuilders.co.za'


def sha512(path):
    return hashlib.sha512(path.read_bytes()).hexdigest()


def main():
    jar = BYTEBANS / 'private-probe/frozen/release/ByteBans-1.1.0.jar'
    probe_jar = Path(__file__).parent / 'frozen/release/bytebans-private-probe-1.0.0.jar'
    result = remote_result(HOST, INSTANCE_ID)
    if result is None:
        raise SystemExit('No private probe result; leave server running for diagnosis')
    evidence = {
        'target': 'paper-1.21.4-online-synthetic', 'instance_id': INSTANCE_ID,
        'test_kind': 'synthetic Bukkit events on live online-mode Paper; no client login',
        'source_revision': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=BYTEBANS,
                                                   text=True).strip(),
        'candidate_sha512': sha512(jar), 'probe_sha512': sha512(probe_jar),
        'paper_build': 232, 'java': 21, 'connection_mode': 'standalone_online',
        'probe': result, 'collected_at': datetime.now(timezone.utc).isoformat(),
    }
    transport = SSHTransport(HOST)
    transport.installed = True
    try:
        evidence['logs'] = transport.call('logs', id=INSTANCE_ID)
        transport.call('stop', id=INSTANCE_ID)
        evidence['stopped'] = True
    finally:
        transport.close()
    folder = Path(__file__).parent / 'runs' / datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ-synthetic')
    folder.mkdir(parents=True, exist_ok=False)
    (folder / 'result.json').write_text(json.dumps(evidence, indent=2) + '\n', encoding='utf-8')
    print(folder, result.get('passed'), evidence['stopped'])
    if result.get('passed') is not True:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
