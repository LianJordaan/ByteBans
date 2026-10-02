"""Run the separate ByteBans event probe against frozen Paper builds, one server at a time."""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import time
from datetime import datetime, timezone
from urllib.request import urlopen

BYTEBANS = Path(__file__).resolve().parents[1]
WORKSPACE = BYTEBANS.parents[1]
sys.path.insert(0, str(WORKSPACE))
from dashboard.servers.transport import SSHTransport

INSTANCE = re.compile(r'mw-[a-f0-9]{16}\Z')
VERSION = re.compile(r'[0-9][0-9.]*\Z')
REQUIRED_CASES = frozenset({
    'ip_ban_login_and_operator_bypass', 'ip_ban_scope_exclusion',
    'ip_mute_chat_and_operator_bypass', 'freeze_movement_and_operator_bypass',
})


def digest(path):
    data = path.read_bytes()
    return data, hashlib.sha512(data).hexdigest()


def artifact(path, title):
    data, sha = digest(path)
    return {'title': title, 'filename': path.name, 'sha512': sha,
            'content_base64': base64.b64encode(data).decode('ascii'),
            'compatibility': 'private-testing'}


def remote_result(host, instance_id):
    if not INSTANCE.fullmatch(instance_id):
        raise ValueError('Unexpected managed server identity')
    path = f'/opt/modrinth-workspace/instances/{instance_id}/data/plugins/ByteBansPrivateProbe/results.json'
    result = subprocess.run(['ssh', '-o', 'BatchMode=yes', host, 'cat ' + path],
                            capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=20)
    if result.returncode:
        return None
    return json.loads(result.stdout)


def remote_paper_sha(host, instance_id, version, build):
    if not INSTANCE.fullmatch(instance_id) or not VERSION.fullmatch(version) or not isinstance(build, int):
        raise ValueError('Unexpected pinned Paper target identity')
    path = f'/opt/modrinth-workspace/instances/{instance_id}/data/paper-{version}-{build}.jar'
    result = subprocess.run(['ssh', '-o', 'BatchMode=yes', host, 'sha256sum ' + path],
                            capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=20,
                            check=True)
    return result.stdout.split()[0]


def remote_artifact_sha(host, instance_id, index, expected_sha):
    if (not INSTANCE.fullmatch(instance_id) or index not in (0, 1)
            or not re.fullmatch(r'[a-f0-9]{128}', expected_sha)):
        raise ValueError('Unexpected private artifact identity')
    path = (f'/opt/modrinth-workspace/instances/{instance_id}/data/plugins/'
            f'{index:03}-{expected_sha[:12]}.jar')
    result = subprocess.run(['ssh', '-o', 'BatchMode=yes', host, 'sha512sum ' + path],
                            capture_output=True, text=True, encoding='utf-8', errors='replace',
                            timeout=30, check=True)
    return result.stdout.split()[0]


def startup_diagnostics(logs):
    """Keep a passing event probe from masking a failed ByteBans startup."""
    lines = logs.splitlines()
    enabled = any('[ByteBans] ByteBans was successfully initialized.' in line for line in lines)
    failures = [line for line in lines if
                ('Error occurred while enabling ByteBans' in line
                 or ('[ByteBans]' in line and re.search(r'\b(?:ERROR|SEVERE)\b', line)))]
    return {'initialized': enabled, 'fatal_lines': failures[:10]}


def probe_diagnostics(probe, target):
    cases = probe.get('cases') if isinstance(probe, dict) else None
    return {
        'minecraft_matches': isinstance(probe, dict) and probe.get('minecraft') == target['version'],
        'java_matches': isinstance(probe, dict) and str(probe.get('java', '')).split('.')[0] == str(target['java']),
        'server_mentions_version': isinstance(probe, dict)
            and target['version'] in probe.get('server', ''),
        'all_required_cases_pass': isinstance(cases, dict)
            and all(cases.get(name) is True for name in REQUIRED_CASES),
    }


def run(target, transport, host, profile, artifacts, folder, config, revision):
    record = None
    result = {'target': target, 'source_revision': revision,
              'candidate_sha512': artifacts[0]['sha512'], 'probe_sha512': artifacts[1]['sha512'],
              'started_at': datetime.now(timezone.utc).isoformat()}
    try:
        request = {'name': 'ByteBans private probe ' + target['id'],
                   'loader': 'PAPER', 'version': target['version'],
                   'paper_build': target['paper_build'], 'build_channel': 'PINNED',
                   'java': target['java'], 'memory_gb': 4, 'cpus': 4,
                   'operator': profile, 'eula_accepted': True,
                   'bind_ip': '127.0.0.1', 'join_host': '127.0.0.1',
                   'first_port': 27200, 'last_port': 27299,
                   'connection_mode': target['connection_mode'],
                   'artifacts': artifacts}
        record = transport.call('create', **request)
        result['instance_id'] = record['id']
        result['port'] = record['port']
        print('created', target['id'], record['id'], record['port'], flush=True)
        transport.call('start', id=record['id'])
        deadline = time.monotonic() + 600
        while time.monotonic() < deadline:
            probe = remote_result(host, record['id'])
            if probe is not None:
                result['probe'] = probe
                result['probe_diagnostics'] = probe_diagnostics(probe, target)
                result['status'] = ('pass' if probe.get('passed') is True
                                    and all(result['probe_diagnostics'].values()) else 'fail')
                break
            time.sleep(5)
        else:
            result['status'] = 'timeout'
        result['paper_sha256_actual'] = remote_paper_sha(host, record['id'], target['version'],
                                                         target['paper_build'])
        if result['paper_sha256_actual'] != target['paper_sha256']:
            result['status'] = 'paper_hash_mismatch'
        result['candidate_sha512_actual'] = remote_artifact_sha(host, record['id'], 0,
                                                                artifacts[0]['sha512'])
        result['probe_sha512_actual'] = remote_artifact_sha(host, record['id'], 1,
                                                            artifacts[1]['sha512'])
        if (result['candidate_sha512_actual'] != artifacts[0]['sha512']
                or result['probe_sha512_actual'] != artifacts[1]['sha512']):
            result['status'] = 'artifact_hash_mismatch'
        result['logs'] = transport.call('logs', id=record['id'])
        result['startup_diagnostics'] = startup_diagnostics(result['logs'])
        if result['status'] == 'pass' and (not result['startup_diagnostics']['initialized']
                                           or result['startup_diagnostics']['fatal_lines']):
            result['status'] = 'startup_diagnostic_failed'
    except Exception as error:
        result['status'] = 'error'
        result['error'] = str(error)
    finally:
        if record is not None:
            try:
                transport.call('stop', id=record['id'])
                result['stopped'] = True
            except Exception as error:
                result['stop_error'] = str(error)
        result['finished_at'] = datetime.now(timezone.utc).isoformat()
        (folder / (target['id'] + '.json')).write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(target['id'], result['status'], flush=True)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--target', action='append', help='Run only this pinned target ID; repeatable')
    parser.add_argument('--jar', type=Path, help='Exact ByteBans candidate JAR (default: frozen/release/ByteBans-1.1.0.jar)')
    parser.add_argument('--source-revision', help='Git revision that produced --jar (default: frozen source revision, or current HEAD for a custom JAR)')
    parser.add_argument('--list', action='store_true', help='Print selected frozen targets without contacting the server')
    args = parser.parse_args()
    matrix = json.loads((Path(__file__).with_name('matrix.json')).read_text(encoding='utf-8'))
    targets = matrix['targets']
    if len({t['id'] for t in targets}) != len(targets):
        raise SystemExit('Duplicate target IDs')
    if args.target:
        targets = [t for t in targets if t['id'] in set(args.target)]
        if len(targets) != len(set(args.target)):
            raise SystemExit('Unknown target ID')
    if args.list:
        for target in targets:
            label = 'experimental' if target['experimental'] else 'stable'
            print(target['id'], 'Paper', target['version'], 'build', target['paper_build'],
                  'Java', target['java'], target['build_channel'], label)
        return
    config = json.loads((WORKSPACE / 'config' / 'test_servers.json').read_text(encoding='utf-8'))
    if config.get('eula_accepted') is not True:
        raise SystemExit('Minecraft test-server EULA acceptance is not configured')
    root_jar = args.jar.resolve() if args.jar else Path(__file__).parent / 'frozen/release/ByteBans-1.1.0.jar'
    probe_jar = Path(__file__).parent / 'frozen/release/bytebans-private-probe-1.0.0.jar'
    artifacts = [artifact(root_jar, 'ByteBans frozen 1.1.0 beta candidate'),
                 artifact(probe_jar, 'Separate ByteBans private event probe')]
    profile = json.load(urlopen('https://api.minecraftservices.com/minecraft/profile/lookup/name/'
                                + config['operator'], timeout=20))
    revision = (args.source_revision or
                ('70d3daff4b0af93338d95b207ee596c5e52a43fc' if args.jar is None else
                 subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=BYTEBANS,
                                         text=True).strip()))
    if not re.fullmatch(r'[a-f0-9]{40}', revision):
        raise SystemExit('Source revision must be a full Git SHA-1')
    folder = Path(__file__).parent / 'runs' / datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    folder.mkdir(parents=True, exist_ok=False)
    transport = SSHTransport(config['ssh_host'])
    transport.installed = True  # never replace a live manager while other tests run
    try:
        summary = [run(t, transport, config['ssh_host'], profile, artifacts,
                       folder, config, revision) for t in targets]
    finally:
        transport.close()
    print('evidence', folder, flush=True)
    if any(row['status'] != 'pass' or not row.get('stopped') for row in summary):
        raise SystemExit(1)


if __name__ == '__main__':
    main()
