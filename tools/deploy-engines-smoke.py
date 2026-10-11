#!/usr/bin/env python3
"""Run baseline + real engine tests; --runtime also prompts on the phone.

Runtime tests target only the user's scoped fixture, and restore its patch in a
finally block. No confirmation preference is modified. Tokens stay in memory.
"""
import argparse
import runpy
import sys
import time
from pathlib import Path

p = argparse.ArgumentParser()
p.add_argument('--serial', default='3972b041')
p.add_argument('--output', required=True)
p.add_argument('--runtime', action='store_true')
p.add_argument('--runtime-only', action='store_true', help='Skip the previously passed static suite')
opt = p.parse_args()
sys.argv = [str(Path(__file__).with_name('deploy-smoke.py')), '--serial', opt.serial, '--output', opt.output]
base = runpy.run_path(sys.argv[0], run_name='__client__' if opt.runtime_only else '__main__')
deploy, posed, record, fixture = [base[k] for k in ('deploy', 'posed', 'record', 'fixture')]

samples = {
    'aarch64': 'mov x0, #42; ret', 'arm': 'mov r0, #42; bx lr',
    'thumb': 'movs r0, #42; bx lr', 'x86': 'mov eax, 42; ret',
    'x86_64': 'mov rax, 42; ret',
}
for endpoint, client in ([] if opt.runtime_only else [('direct', deploy), ('aidl', posed)]):
    for arch, source in samples.items():
        code = client.call('deploy_assemble', arch=arch, source=source, address='0x1000')
        record(endpoint+'_'+arch+'_assemble', code, code.get('size', 0) > 0 and code.get('executed') is False)
        decoded = client.call('deploy_analyze_bytes', arch=arch, bytes=code['bytes'], address='0x1000', max=8)
        record(endpoint+'_'+arch+'_roundtrip', decoded, decoded.get('count') == 2
               and decoded.get('decodedBytes') == code['size'] and decoded['instructions'][0]['address'] == '0x1000')
    branch = client.call('deploy_assemble', arch='aarch64', source='b done; mov x0,#1; done: ret', address='0x1000')
    record(endpoint+'_branch_assembly', branch, branch.get('size') == 12)
    decoded = client.call('deploy_analyze_bytes', arch='aarch64', bytes=branch['bytes'], address='0x1000')
    record(endpoint+'_branch_origin', decoded, '0x1008' in decoded['instructions'][0]['operands'])
    for label, args in [
        ('directive', dict(source='.space 100000000')),
        ('label_directive', dict(source='label: .space 100000000')),
        ('block_comment_directive', dict(source='/* comment */ .space 100000000')),
        ('numeric_label_directive', dict(source='1: .space 100000000')),
        ('invalid_instruction', dict(source='this_is_not_an_instruction')),
        ('alignment', dict(source='ret', address='0x1001')),
        ('overflow', dict(source='ret', address='0xfffffffffffffffc')),
        ('statement_limit', dict(source='ret;' * 513)),
    ]:
        response = client.call('deploy_assemble', **dict(dict(arch='aarch64', source='ret', address='0x1000'), **args))
        record(endpoint+'_asm_'+label, response, 'error' in response)
    for name in ['deploy_fixture_answer', 'deploy_fixture_calculate']:
        code = client.call('deploy_analyze', path=fixture, op='decompile', name=name)
        record(endpoint+'_decompile_'+name, code, code.get('backend') == 'ghidra'
               and 'return' in code.get('pseudocode', '') and ('0x2a' in code['pseudocode'] or '42' in code['pseudocode']
                    if name.endswith('answer') else '7' in code['pseudocode']))
        by_address = client.call('deploy_analyze', path=fixture, op='decompile', address=code['address'])
        record(endpoint+'_decompile_address_'+name, by_address, 'return' in by_address.get('pseudocode', ''))
    limited = client.call('deploy_analyze', path=fixture, op='decompile', name='deploy_fixture_calculate', max=1)
    record(endpoint+'_decompile_budget', limited, 'error' in limited)
    status = client.call('deploy_status')
    record(endpoint+'_worker_recovery', status, status.get('decompile', {}).get('available') is True)

if opt.runtime or opt.runtime_only:
    base['adb']('shell', 'am', 'start', '-n', 'dev.deploy.fixture/.FixtureActivity')
    # Foregrounding unfreezes ColorOS targets, but their bridge reconnect is async.
    deadline=time.monotonic()+20
    while True:
        peers = [x for x in posed.call('module_status').get('bridgePeers', []) if x.startswith('app:dev.deploy.fixture:')]
        if len(peers)==1 or time.monotonic()>=deadline: break
        time.sleep(1)
    record('runtime_peer', peers, len(peers) == 1)
    peer = peers[0]
    def load():
        return posed.call('deploy_load_native', package='dev.deploy.fixture', peer=peer, path=fixture,
            entry='deploy_fixture_patchpoint', reason='Call our test library function returning 7 (or 9 during the reversible instruction test).')
    loaded = load()
    record('runtime_patchpoint_load', loaded, loaded.get('entryResult') == '7')
    address = loaded['entryAddress']
    common = dict(package='dev.deploy.fixture', peer=peer, address=address)
    snapshot = posed.call('deploy_disasm_memory', **common, length=8, max=2,
        reason='Read only the two instructions of our fixture patchpoint, then decode its snapshot in Deploy over AIDL.')
    record('runtime_snapshot', snapshot, 'error' not in snapshot)
    original = 'e00080d2'  # mov x0,#7
    replacement = '200180d2'  # mov x0,#9
    patched = False
    try:
        result = posed.call('deploy_patch_memory', **common, source='mov x0,#9', expected=original,
            reason='Temporarily change our fixture return value from 7 to 9; verify old bytes, then restore the instruction.')
        patched = result.get('patched') is True
        record('runtime_patch', result, patched and result.get('permissionsRestored') is True)
        executed = load()
        record('runtime_patched_result', executed, executed.get('entryResult') == '9')
        mismatch = posed.call('deploy_patch_memory', **common, source='mov x0,#8', expected=original,
            reason='Verify stale expected bytes reject a second write to our fixture; the value must stay 9.')
        record('runtime_expected_mismatch', mismatch, mismatch.get('patched') is False and 'error' in mismatch)
    finally:
        if patched:
            restored = posed.call('deploy_patch_memory', **common, source='mov x0,#7', expected=replacement,
                reason='Restore our fixture original instruction and page permissions after the reversible test.')
            record('runtime_restore', restored, restored.get('patched') is True and restored.get('permissionsRestored') is True)
            final = load()
            record('runtime_restored_result', final, final.get('entryResult') == '7')
    bad = posed.call('deploy_disasm_memory', **dict(common, address='0x4'), length=8,
        reason='Verify an unmapped read is rejected without dereferencing a bad pointer in our fixture.')
    record('runtime_unmapped', bad, 'error' in bad)
    offline = posed.call('deploy_disasm_memory', **dict(common, peer='app:dev.deploy.fixture:0'), length=8,
        reason='Verify offline peers are rejected before reading memory.')
    record('runtime_offline', offline, 'error' in offline)
