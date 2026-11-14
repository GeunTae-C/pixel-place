"""F 신규 소유 경계와 검증된 과거 파일의 읽기 연결. 원본 JSON/경로 identity 변경 없음."""
from pathlib import Path
import hashlib
import json
import os
import re
import stat

EVIDENCE = Path('F:/pixel-place-work/evidence')
ARCHIVE = Path('F:/pixel-place-archive/pixel-place')
GRAPH = Path('C:/Users/cgt/Desktop/Java/pixel-place/graphify-out')
CATALOG = ARCHIVE / 'relocations.json'
CONTROL_LOCATION = Path(__file__).with_name('pixel-place-storage.json')


def plain_absolute(value):
    raw = str(value)
    if not re.match(r'^[CFcf]:[/\\]', raw) or ':' in raw[2:]:
        raise ValueError('C/F absolute storage path required')
    path = Path(raw)
    if os.path.normpath(raw).casefold() != raw.replace('/', '\\').rstrip('\\').casefold():
        raise ValueError('Normalized storage path required')
    for part in path.parts[1:]:
        if re.search(r'[. ]$|^(?:con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)', part, re.I):
            raise ValueError('Storage path alias forbidden')
    for parent in [*reversed(path.parents), path]:
        try:
            info = parent.lstat()
        except FileNotFoundError:
            continue
        if stat.S_ISLNK(info.st_mode) or getattr(info, 'st_file_attributes', 0) & 0x400:
            raise ValueError('Storage reparse path forbidden')
    return path


def evidence_path(session):
    if not re.fullmatch(r'[a-z0-9][a-z0-9-]{5,79}', session):
        raise ValueError('Safe evidence session required')
    return plain_absolute(EVIDENCE / session)


def sha(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def control_path(value):
    """현재 제어 root만 별도 연결. archive를 신규 실행 root로 채택하지 않음."""
    path = Path(value)
    if not CONTROL_LOCATION.exists():
        return path
    location = json.loads(CONTROL_LOCATION.read_text(encoding='utf-8-sig'))
    if not location['enabled']:
        return path
    for item in location['mappings']:
        previous, current = Path(item['source']), plain_absolute(item['destination'])
        if not current.is_relative_to(Path('F:/pixel-place-work/projects/pixel-place/control')):
            raise ValueError('Dedicated F control root required')
        if path.is_relative_to(previous):
            return plain_absolute(current / path.relative_to(previous))
    return path


def historical(value, archived=False):
    # 기존 읽기의 상대/../참조는 어휘적으로 정규화. resolve()로 링크를 따라가지 않음.
    # 신규 출력은 plain_absolute/evidence_path의 엄격한 원문 검사를 사용한다.
    path = plain_absolute(os.path.abspath(control_path(value)))
    if path.exists() and not archived:
        return path
    if not CATALOG.exists():
        return path
    plain_absolute(CATALOG)
    catalog = json.loads(CATALOG.read_text(encoding='utf-8-sig'))
    matches = [item for item in catalog['bundles'] if path.is_relative_to(Path(item['source']))]
    if not matches:
        return path
    if len(matches) != 1:
        raise ValueError('Ambiguous historical relocation')
    item = matches[0]
    destination = plain_absolute(item['destination'])
    if not destination.is_relative_to(ARCHIVE):
        raise ValueError('Historical archive scope')
    manifest_path = plain_absolute(item['manifest'])
    if not manifest_path.is_relative_to(ARCHIVE / 'manifests') or sha(manifest_path) != item['manifestSha256']:
        raise ValueError('Historical manifest identity changed')
    manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
    relative = path.relative_to(Path(item['source'])).as_posix()
    target = plain_absolute(destination / relative)
    if target.is_file():
        entries = [entry for entry in manifest['files'] if entry['relative'].casefold() == relative.casefold()]
        if len(entries) != 1 or target.stat().st_size != entries[0]['bytes'] or sha(target) != entries[0]['sha256']:
            raise ValueError('Historical file bytes changed')
    elif not target.is_dir() or relative != '.' and relative not in manifest['directories']:
        raise ValueError('Historical directory identity missing')
    return target


if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser()
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument('--resolve')
    group.add_argument('--control-root')
    args = parser.parse_args()
    print(historical(args.resolve) if args.resolve else control_path(args.control_root))
