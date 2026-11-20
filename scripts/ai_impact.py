from __future__ import annotations

import argparse
import json
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAP = ROOT / 'docs' / 'ai' / 'project-map.json'


def changed_paths() -> list[str]:
    result = subprocess.run(['git', '-C', str(ROOT), 'diff', '--name-only', 'HEAD'], capture_output=True, text=True)
    paths = [line.strip() for line in result.stdout.splitlines() if line.strip()]
    untracked = subprocess.run(['git', '-C', str(ROOT), 'ls-files', '--others', '--exclude-standard'], capture_output=True, text=True)
    paths.extend(line.strip() for line in untracked.stdout.splitlines() if line.strip())
    return sorted(set(paths))


def matches(path: str, feature_path: str) -> bool:
    feature_path = feature_path.rstrip('/')
    return path == feature_path or path.startswith(feature_path + '/') or feature_path.startswith(path.rstrip('/') + '/')


def main() -> None:
    parser = argparse.ArgumentParser(description='Suggest feature map and verification targets from changed files.')
    parser.add_argument('paths', nargs='*')
    parser.add_argument('--json', action='store_true')
    args = parser.parse_args()
    paths = args.paths or changed_paths()
    data = json.loads(MAP.read_text(encoding='utf-8'))
    features = []
    for feature in data.get('features', []):
        all_paths = []
        for group in ('backend', 'admin', 'h5'):
            all_paths.extend(feature.get(group, []))
        matched = [path for path in paths if any(matches(path, target) for target in all_paths)]
        if matched:
            features.append({
                'id': feature['id'],
                'name': feature['name'],
                'paths': sorted(set(matched)),
                'tests': feature.get('tests', []),
                'tables': feature.get('tables', []),
            })
    commands = []
    if any(path.startswith('delta-game/') for path in paths):
        commands.append('cd delta-game && mvn -pl delta-admin -am test')
    if any(path.startswith('delta-admin-ui/') for path in paths):
        commands.append('cd delta-admin-ui && npm test && npm run build')
    if any(path.startswith('delta-mp/') for path in paths):
        commands.append('cd delta-mp && npm test && npm run build:h5')
    if any(path.startswith(('scripts/', 'contracts/')) for path in paths):
        commands.append('python scripts/check_ai_context.py && python scripts/check_endpoint_baseline.py && python scripts/check_openapi_baseline.py && python scripts/check_openapi_types.py')
    report = {'version': data.get('version'), 'changed': paths, 'features': features, 'commands': commands}
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return
    print('Version: ' + str(report['version']))
    print('Changed files: ' + str(len(paths)))
    for feature in features:
        print('- %s (%s): %s' % (feature['id'], feature['name'], ', '.join(feature['tests']) or 'no mapped tests'))
    if not features:
        print('- no feature map match; inspect docs/ai/FEATURE_MAP.md manually')
    print('Suggested commands:')
    for command in commands or ['no generic command; choose from docs/ai/VERIFICATION.md']:
        print('  ' + command)


if __name__ == '__main__':
    main()
