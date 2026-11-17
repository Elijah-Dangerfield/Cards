#!/usr/bin/env python3
"""Render local Grafana artifacts. This script never connects to Grafana."""
import argparse
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent
TOKEN = re.compile(r"__[A-Z_]+__")
MATCHERS = re.compile(r'\s*[a-zA-Z_][a-zA-Z0-9_]*\s*(?:=|!=|=~|!~)\s*"(?:[^"\\]|\\.)*"\s*(?:,\s*[a-zA-Z_][a-zA-Z0-9_]*\s*(?:=|!=|=~|!~)\s*"(?:[^"\\]|\\.)*"\s*)*')


def render(value, bindings):
    if isinstance(value, dict):
        return {key: render(item, bindings) for key, item in value.items()}
    if isinstance(value, list):
        return [render(item, bindings) for item in value]
    if isinstance(value, str):
        return TOKEN.sub(lambda match: bindings[match.group()], value)
    return value


def validate(bundle, env):
    dashboard, alerts, queries = bundle
    if len({panel['id'] for panel in dashboard['panels']}) != len(queries):
        raise ValueError('Dashboard panel IDs must be unique and cover every query')
    if dashboard['uid'] != 'dc-standing-tables':
        raise ValueError('Unexpected dashboard UID')
    group = alerts['groups'][0]
    rules = group['rules']
    if group['name'] != f'standing-tables-{env}':
        raise ValueError('Alert group must match the selected environment')
    if len(rules) != 5 or len({rule['uid'] for rule in rules}) != 5:
        raise ValueError('Exactly five unique alert UIDs are required')
    if any(not rule['uid'].endswith(f'-{env}') or rule['labels']['environment'] != env for rule in rules):
        raise ValueError('Alert UIDs and labels must match the selected environment')
    for rule in rules:
        if not rule['isPaused'] or rule['noDataState'] != 'OK' or rule['execErrState'] != 'Error':
            raise ValueError('Rules must remain paused, ignore quiet traffic and preserve execution errors')
        expr = rule['data'][0]['model']['expr']
        if not re.search(r'\) >= bool [1-9]\d*$', expr):
            raise ValueError('Every rule must be a count threshold')
        if ' / ' in expr or '[6h]' not in expr or rule['data'][0]['relativeTimeRange']['from'] != 21600:
            raise ValueError('Rules must count over a 6h window, not a ratio')
        if not expr.startswith('(sum(') or ' or vector(0)) >= bool' not in expr:
            raise ValueError('Absent series must count as zero failures')
    if TOKEN.search(json.dumps(bundle)):
        raise ValueError('Unbound template token')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--loki-uid')
    parser.add_argument('--prometheus-uid')
    parser.add_argument('--server-selector')
    parser.add_argument('--env', choices=('dev', 'prod'))
    parser.add_argument('--output-dir', type=Path, default=Path('/tmp/downcard-standing-tables'))
    args = parser.parse_args()
    loki = args.loki_uid or input('Loki datasource UID (Connections > Data sources): ').strip()
    prometheus = args.prometheus_uid or input('Prometheus datasource UID: ').strip()
    env = args.env or input('Environment, dev or prod: ').strip()
    scope = args.server_selector or input('Verified server metric label matchers for that environment, without braces: ').strip()
    if not all(re.fullmatch(r'[A-Za-z0-9_-]+', uid) for uid in (loki, prometheus)):
        parser.error('Both datasource UIDs must be nonempty UID strings')
    if env not in ('dev', 'prod') or not MATCHERS.fullmatch(scope):
        parser.error('Choose dev/prod and provide nonempty Prometheus label matchers without braces')
    bindings = {'__LOKI_UID__': loki, '__PROMETHEUS_UID__': prometheus,
                '__ENV__': env, '__SERVER_SELECTOR__': scope}
    names = ('dashboard', 'alerts', 'queries')
    bundle = [render(json.loads((ROOT / f'{name}.template.json').read_text()), bindings) for name in names]
    validate(bundle, env)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    for name, artifact in zip(names, bundle):
        destination = args.output_dir / f'{name}.json'
        if destination.exists():
            parser.error(f'Refusing to overwrite {destination}; choose another output directory')
    for name, artifact in zip(names, bundle):
        (args.output_dir / f'{name}.json').write_text(json.dumps(artifact, indent=2) + '\n')
    print(f'Rendered {len(bundle[0]["panels"])} panels and {len(bundle[1]["groups"][0]["rules"])} paused rules to {args.output_dir}')
    print('Local structural validation passed. Queries, ingestion and alert activation still require Grafana verification.')


if __name__ == '__main__':
    main()
