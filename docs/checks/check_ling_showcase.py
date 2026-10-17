"""Checks real Ling presentation timing and interruption in an isolated, prepared catalog."""
import argparse
import ctypes
import json
import subprocess
import time
import uuid
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--java', type=Path, required=True)
parser.add_argument('--jar', type=Path, required=True)
parser.add_argument('--fixture', type=Path, required=True)
args = parser.parse_args()
root = args.fixture.resolve()
runtime = root / 'runtime'
runtime.mkdir(exist_ok=True)
control = runtime / 'char_2023_ling.json'
status = runtime / 'char_2023_ling.status.json'
catalog = json.loads((root / 'catalog.json').read_text(encoding='utf-8'))
ling = next(c for c in catalog['characters'] if c['id'] == 'char_2023_ling')
model = next(s for s in ling['skins'] if s['id'] == 'skin-1')['models']['building']
config = json.loads((root / 'base.config.json').read_text(encoding='utf-8-sig'))
config.update(character_asset=str(root / model['asset']), character_files=model['files'],
              character_label='令 · 检查', display_scale=.8, initial_position_x=.75, initial_position_y=.9,
              behavior_allow_walk=False, behavior_allow_sit=False, behavior_allow_sleep=False,
              behavior_allow_special=False, behavior_allow_interact=False, enable_telemetry=False,
              behavior_do_peer_repulsion=False)
config_path = runtime / 'check.config.json'
config_path.write_text(json.dumps(config, ensure_ascii=False), encoding='utf-8')
prefs = dict(skin='skin-1', mode='building', autoActions=True, battleActions=True,
             autoWardrobe=False, voiceEnabled=False, volume=0, activityMinSeconds=90,
             activityMaxSeconds=120, bubbleEnabled=True)
trace = []
checks = []
pet = None

def save(command=None):
    if command is not None:
        prefs.update(command=command, commandId=str(uuid.uuid4()))
    temp = control.with_suffix('.check.tmp')
    temp.write_text(json.dumps(prefs, ensure_ascii=False), encoding='utf-8')
    temp.replace(control)

def state():
    try:
        value = json.loads(status.read_text(encoding='utf-8'))
        return value if value.get('pid') == pet.pid else {}
    except (FileNotFoundError, PermissionError, json.JSONDecodeError):
        return {}

def until(predicate, timeout=12):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        assert pet.poll() is None, f'Pet exited: {pet.returncode}'
        current = state()
        if current:
            assert not current.get('error'), current['error']
            if predicate(current):
                return current
        time.sleep(.08)
    raise AssertionError(f'Timed out: {state()}')

def presentation(skill):
    save('showcase')
    start = until(lambda s: s.get('battleShowcase') and s.get('activitySeconds', 0) > 0 and not s.get('transitioning'))
    assert f'技能{skill}' in start['activity'], start
    samples = []
    end = time.monotonic() + 85
    while time.monotonic() < end:
        sample = state()
        if sample:
            assert not sample.get('error'), sample['error']
            if not samples or sample['updatedAt'] != samples[-1]['updatedAt']:
                samples.append(sample)
            if not sample['battleShowcase'] and not sample['transitioning']:
                break
        time.sleep(.08)
    else:
        raise AssertionError('Presentation did not finish')
    names = []
    for s in samples:
        if not names or names[-1] != s['playing']:
            names.append(s['playing'])
        assert abs(s['petCenterX'] - start['petCenterX']) < 1, 'Horizontal anchor jumped'
        assert abs(s['petY'] - start['petY']) < 1, 'Floor anchor jumped'
        assert s['petFacing'] == start['petFacing'], 'Facing changed'
    assert names.count('Attack') == 1, names
    if skill == 3:
        idle_samples = [s for s in samples if s['playing'] == 'Skill_03_Idle']
        assert len(idle_samples) >= 55, f'Skill idle too short: {len(idle_samples)} samples'
        assert names.count('Skill_03_Loop') == 1 and names.count('Skill_03_End') == 1, names
    else:
        assert names.count('Skill_02') == 1 and 'Skill_03_Idle' not in names, names
    final = samples[-1]
    assert final['variant'] == 'skin-1/building' and final['transitionOpacity'] == 1
    assert 88_000 <= final['nextActivityAt'] - final['updatedAt'] <= 121_000, final
    checks.append(dict(skill=skill, seconds=start['activitySeconds'], clips=names,
                       samples=len(samples), returned=True, anchorPreserved=True))
    trace.extend(samples)

try:
    save('none')
    with (root / 'pet.log').open('w', encoding='utf-8') as log:
        pet = subprocess.Popen([str(args.java), '-Dfile.encoding=UTF-8',
            f'-Darkpets.companion.catalog={root / "catalog.json"}', '-Darkpets.companion.character=char_2023_ling',
            '-cp', str(args.jar), 'cn.harryh.arkpets.EmbeddedLauncher', '--config', str(config_path)],
            cwd=root, stdout=log, stderr=subprocess.STDOUT, creationflags=subprocess.CREATE_NO_WINDOW)
    until(lambda s: s.get('playing') == 'Idle' or s.get('playing') == 'Relax', 30)
    time.sleep(2)
    presentation(3)
    presentation(2)

    baseline = state()
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    kernel.CreateFileW.restype = ctypes.c_void_p
    kernel.CreateFileW.argtypes = [ctypes.c_wchar_p, ctypes.c_uint32, ctypes.c_uint32, ctypes.c_void_p, ctypes.c_uint32, ctypes.c_uint32, ctypes.c_void_p]
    kernel.CloseHandle.argtypes = [ctypes.c_void_p]
    handle = kernel.CreateFileW(str(status), 0x80000000, 1, None, 3, 0x80, None)
    assert handle != ctypes.c_void_p(-1).value
    try: time.sleep(1.2)
    finally: kernel.CloseHandle(handle)
    recovered = until(lambda s: s['updatedAt'] > baseline['updatedAt'] and s['statusWriteRetries'] > baseline['statusWriteRetries'])
    assert recovered['nextActivityAt'] == baseline['nextActivityAt'], 'Status contention reset activity timer'
    checks.append(dict(statusContentionRecovered=True, timerPreserved=True))

    save('showcase')
    until(lambda s: s['battleShowcase'] and s['playing'] == 'Skill_03_Idle', 35)
    prefs['skin'] = 'skin-2'; save('none')
    interrupted = until(lambda s: not s['battleShowcase'] and not s['transitioning'] and s['variant'] == 'skin-2/building')
    assert interrupted['transitionOpacity'] == 1
    checks.append(dict(manualWardrobeInterrupts=True))
    save('showcase')
    until(lambda s: s['battleShowcase'] and not s['transitioning'])
    prefs['battleActions'] = False; save('none')
    until(lambda s: not s['battleShowcase'] and s['variant'] == 'skin-2/building' and s['transitionOpacity'] == 1)
    checks.append(dict(battleToggleInterrupts=True))
    print(json.dumps(dict(passed=checks), ensure_ascii=False), flush=True)
finally:
    (root / 'showcase-trace.json').write_text(json.dumps(trace, ensure_ascii=False), encoding='utf-8')
    (root / 'integration-checks.json').write_text(json.dumps(checks, ensure_ascii=False, indent=2), encoding='utf-8')
    if pet and pet.poll() is None:
        save('exit')
        try: pet.wait(timeout=8)
        except subprocess.TimeoutExpired: pet.terminate(); pet.wait(timeout=5)
