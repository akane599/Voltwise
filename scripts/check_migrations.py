#!/usr/bin/env python3
"""Check migrations against Room's exported schema using host SQLite (not device validation).

Picks the newest schemas/…/N.json and every `val MIGRATION_a_b` block in BatteryDatabase.kt automatically.
Seeds: v1–3 from the `legacy.execSQL("…")` fixture in DatabaseMigrationTest, and vN from its `vN.execSQL("…")`
fixture, whose CREATE statements must equal schemas/…/N.json.
"""
import json
from pathlib import Path
import re
import sqlite3

root = Path(__file__).resolve().parents[1]
source = (root / 'app/src/main/java/com/akane/voltwise/battery/data/db/BatteryDatabase.kt').read_text()
fixture = (root / 'app/src/androidTest/java/com/akane/voltwise/battery/data/DatabaseMigrationTest.kt').read_text()
schema_files = {int(p.stem): p for p in (root / 'app/schemas/com.akane.voltwise.battery.data.db.BatteryDatabase').glob('*.json')}
latest = max(schema_files)
schemas = {v: json.loads(p.read_text())['database'] for v, p in schema_files.items()}
schema = schemas[latest]
assert schema['version'] == latest and f'version = {latest},' in source, 'Newest schema JSON does not match @Database(version)'

# Each `val MIGRATION_a_b` block runs to the next one, or to `fun get(`.
marks = [(m.start(), int(m[1]), int(m[2])) for m in re.finditer(r'val MIGRATION_(\d+)_(\d+)\b', source)]
end = source.index('fun get(')
migrations = {}
for i, (at, start, target) in enumerate(marks):
    assert target == start + 1, f'MIGRATION_{start}_{target} skips a version'
    block = source[at:marks[i + 1][0] if i + 1 < len(marks) else end]
    statements = [json.loads(s) for s in re.findall(r'db.execSQL\(("(?:\\.|[^"\\])*")\)', block)]
    assert len(statements) == block.count('db.execSQL'), 'Unsupported SQL expression in test reader'
    migrations[start] = statements
assert sorted(migrations) == list(range(1, latest)), 'Migration chain has a gap'
registered = re.search(r'\.addMigrations\(([^)]*)\)', source[end:])[1]
assert {f'MIGRATION_{v}_{v + 1}' for v in migrations} == {n.strip() for n in registered.split(',')}, 'Migration not registered in get()'

# The newest migration: DDL only (MIGRATION_4_5 by design); new tables/indices use Room's createSql verbatim,
# new columns of kept tables are ALTER TABLE … ADD COLUMN with the createSql column definition.
newest = migrations[latest - 1]
if latest == 5:
    assert all(re.match(r'(CREATE|DROP|ALTER) ', sql) for sql in newest), 'MIGRATION_4_5 must be DDL only'
if latest == 6:
    assert len(newest) == 4 and all(re.fullmatch(
        r'ALTER TABLE `(charge_sessions|daily_summaries)` ADD COLUMN `screen(On|Off)CoveredMs` INTEGER', sql
    ) for sql in newest), 'MIGRATION_5_6 must only add four nullable coverage columns'
if latest >= 7:
    metrics = set('wakeupAlarms partialWakelockCount partialWakelockBgMs jobCount jobMs syncCount fgServiceMs topMs mobileActiveMs gpsMs sensorMs'.split())
    added_columns = {
        'charge_sessions': set('dozeMs screenOffDozeMs appCaptureStartMs appCaptureEndMs'.split()),
        'daily_summaries': set('dozeMs screenOffDozeMs screenOffSuspendMs'.split()),
        'app_snapshot_uids': metrics,
        'session_app_usage': metrics | {'topWakelockTag', 'topAlarmTag', 'topJobName'},
        'app_snapshots': set('deepIdleMs deepIdleCount lightIdleMs lightIdleCount screenOffMs wakersComplete'.split()),
    }
    entities = {e['tableName']: e for e in schemas[7]['entities']}
    old_entities = {e['tableName']: e for e in schemas[6]['entities']}
    assert set(entities) - set(old_entities) == {'snapshot_device_wakers', 'session_device_wakers', 'insight_findings', 'insight_actions'}
    assert all(sql.startswith(('ALTER TABLE ', 'CREATE TABLE ', 'CREATE INDEX ')) for sql in migrations[6]), 'v7 must be additive only'
    for table, old_entity in old_entities.items():
        old_fields = {f['columnName']: f for f in old_entity['fields']}
        new_fields = {f['columnName']: f for f in entities[table]['fields']}
        assert set(new_fields) - set(old_fields) == added_columns.get(table, set()), (table, 'New columns missing or unexpected')
        assert all(new_fields[n] == f for n, f in old_fields.items()), (table, 'Historical column changed')
        for name in added_columns.get(table, set()):
            field = new_fields[name]
            assert not field.get('notNull', False) and 'defaultValue' not in field, (table, name, 'Must remain unknown')
            assert field['affinity'] == ('TEXT' if name in {'topWakelockTag', 'topAlarmTag', 'topJobName'} else 'INTEGER')
    for table in ('insight_findings', 'insight_actions'):
        assert [(i['name'], i['columnNames']) for i in entities[table]['indices']] == [(f'index_{table}_status', ['status'])]
        assert not entities[table].get('foreignKeys', []), 'Findings and journal must not cascade with history'
    feedback = next(f for f in entities['insight_findings']['fields'] if f['columnName'] == 'feedbackMultiplier')
    assert feedback['affinity'] == 'REAL' and feedback['notNull'] and feedback['defaultValue'] == '1.0'
if latest >= 8:
    assert migrations[7] == ['ALTER TABLE `insight_actions` ADD COLUMN `metric` TEXT'], 'v8 must only add a nullable action metric'
    old_entities = {e['tableName']: e for e in schemas[7]['entities']}
    for entity in schemas[8]['entities']:
        old_entity = old_entities[entity['tableName']]
        fields = [f for f in entity['fields'] if not (entity['tableName'] == 'insight_actions' and f['columnName'] == 'metric')]
        assert fields == old_entity['fields'], (entity['tableName'], 'Historical columns changed')
        assert entity.get('indices', []) == old_entity.get('indices', [])
        assert entity.get('foreignKeys', []) == old_entity.get('foreignKeys', [])
    metric = next(f for e in schemas[8]['entities'] if e['tableName'] == 'insight_actions' for f in e['fields'] if f['columnName'] == 'metric')
    assert metric['affinity'] == 'TEXT' and not metric.get('notNull', False) and 'defaultValue' not in metric
if latest >= 9:
    assert schemas[9]['entities'] == schemas[8]['entities'], 'v9 must not change the schema'
    assert all(sql.startswith(('UPDATE ', 'DELETE FROM ')) for sql in migrations[8]), 'v9 must only invalidate old certification/attribution and findings'
before = {e['tableName']: e for e in schemas[latest - 1]['entities']}
for entity in schema['entities']:
    table = entity['tableName']
    create = entity['createSql'].replace('${TABLE_NAME}', table)
    if table not in before:
        assert create in newest, ('New table is not created with its createSql', table)
    else:
        old = {f['columnName'] for f in before[table]['fields']}
        for field in entity['fields']:
            if field['columnName'] in old:
                continue
            definition = re.search(r'(`' + field['columnName'] + r'` [^,)]+)', create)[1]
            recreated = any(sql.startswith('CREATE TABLE') and table in sql for sql in newest)
            assert recreated or f'ALTER TABLE `{table}` ADD COLUMN {definition}' in newest, ('New column differs from createSql', table, definition)
    old_indices = {i['name'] for i in before.get(table, {}).get('indices', [])}
    for index in entity.get('indices', []):
        if index['name'] not in old_indices:
            assert index['createSql'].replace('${TABLE_NAME}', table) in newest, ('New index differs from createSql', index['name'])

def verify(db, version):
    """Tables, columns, primary keys, indices and foreign keys equal the newest schema; no leftover tables."""
    tables = {r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table'")} - {'sqlite_sequence', 'android_metadata', 'room_master_table'}
    assert tables == {e['tableName'] for e in schema['entities']}, (version, tables)
    for entity in schema['entities']:
        rows = db.execute('PRAGMA table_info(' + entity['tableName'] + ')').fetchall()
        actual = {r[1]: (r[2], bool(r[3]), r[4]) for r in rows}
        expected = {f['columnName']: (f['affinity'], f.get('notNull', False), f.get('defaultValue')) for f in entity['fields']}
        assert actual == expected, (version, entity['tableName'], actual, expected)
        primary = [r[1] for r in sorted(rows, key=lambda r: r[5]) if r[5] > 0]
        assert primary == entity['primaryKey']['columnNames'], (version, primary)
        indices = {r[1]: bool(r[2]) for r in db.execute('PRAGMA index_list(' + entity['tableName'] + ')') if not r[1].startswith('sqlite_autoindex')}
        assert indices == {i['name']: i.get('unique', False) for i in entity.get('indices', [])}, (version, indices)
        for index in entity.get('indices', []):
            columns = [r[2] for r in db.execute('PRAGMA index_info(' + index['name'] + ')')]
            assert columns == index['columnNames'], (version, index['name'], columns)
        keys = {}
        for r in db.execute('PRAGMA foreign_key_list(' + entity['tableName'] + ')'):
            key = keys.setdefault(r[0], [r[2], [], [], r[5], r[6]])
            key[1].append(r[3]); key[2].append(r[4])
        assert sorted(keys.values()) == sorted([fk['table'], fk['columns'], fk['referencedColumns'], fk['onUpdate'], fk['onDelete']]
                                               for fk in entity.get('foreignKeys', [])), (version, entity['tableName'], keys)

def migrate(db, version):
    for step in range(version, latest):
        for sql in migrations[step]:
            db.execute(sql)

for version in (1, 2, 3):
    for foreign_keys in ('OFF', 'ON'):
        with sqlite3.connect(':memory:') as db:
            db.execute(f'PRAGMA foreign_keys={foreign_keys}')
            for sql in re.findall(r'legacy.execSQL\("([^"\n]+)"\)', fixture):
                if version == 1 and 'app_energy_stats' in sql:
                    continue
                db.execute(sql)
            migrate(db, version)
            verify(db, version)
            assert db.execute('PRAGMA foreign_keys').fetchone()[0] == (foreign_keys == 'ON')
            assert db.execute('PRAGMA foreign_key_check').fetchall() == [], ('Dangling foreign keys', version)
            assert db.execute('SELECT COUNT(*) FROM battery_samples').fetchone()[0] == 2
            assert db.execute('SELECT currentNowUa FROM battery_samples WHERE id=1').fetchone()[0] == 0
            assert db.execute('SELECT currentNowUa FROM battery_samples WHERE id=2').fetchone()[0] is None
            assert db.execute("SELECT endTime FROM charge_sessions WHERE sessionId='interrupted'").fetchone()[0] == 2000
            assert db.execute('SELECT COUNT(*) FROM charge_sessions WHERE activeKey=1').fetchone()[0] == 0
            db.execute("UPDATE charge_sessions SET activeKey=1 WHERE sessionId='finished'")
            try:
                db.execute("UPDATE charge_sessions SET activeKey=1 WHERE sessionId='interrupted'")
            except sqlite3.IntegrityError:
                pass
            else:
                raise AssertionError('Active session uniqueness was not enforced')
            print(f'PASS SQLite schema, indices, foreign keys, preserved rows, sentinels and active uniqueness: {version} → {latest} (foreign_keys={foreign_keys})')

seeds = {}
for n, sql in re.findall(r'\bv(\d+)\.execSQL\(("(?:\\.|[^"\\])*")\)', fixture):
    seeds.setdefault(int(n), []).append(json.loads(sql))
assert latest - 1 in seeds, f'DatabaseMigrationTest has no v{latest - 1} fixture'
for version, statements in sorted(seeds.items()):
    old = schemas[version]
    creates = [e['createSql'].replace('${TABLE_NAME}', e['tableName']) for e in old['entities']] + \
              [i['createSql'].replace('${TABLE_NAME}', e['tableName']) for e in old['entities'] for i in e.get('indices', [])]
    assert sorted(s for s in statements if s.startswith('CREATE')) == sorted(creates), f'v{version} fixture differs from {version}.json'
    for foreign_keys in ('OFF', 'ON'):
        with sqlite3.connect(':memory:') as db:
            db.execute(f'PRAGMA foreign_keys={foreign_keys}')
            for sql in statements:
                db.execute(sql)
            kept = [e['tableName'] for e in schema['entities'] if e['tableName'] in {x['tableName'] for x in old['entities']}]
            snapshot = {t: db.execute(f'SELECT * FROM {t} ORDER BY 1').fetchall() for t in kept}
            columns = {t: [r[1] for r in db.execute(f'PRAGMA table_info({t})')] for t in kept}
            migrate(db, version)
            verify(db, version)
            assert db.execute('PRAGMA foreign_keys').fetchone()[0] == (foreign_keys == 'ON')
            for t in kept:
                if latest >= 9 and t in ('app_snapshots', 'app_snapshot_uids', 'snapshot_device_wakers', 'session_device_wakers'):
                    assert db.execute(f'SELECT COUNT(*) FROM {t}').fetchone()[0] == 0, ('Legacy untrusted rows survived', t)
                    continue
                width = len(snapshot[t][0]) if snapshot[t] else 0
                expected = snapshot[t]
                if latest >= 9 and t == 'charge_sessions':
                    expected = [tuple(None if name in ('appCaptureStartMs', 'appCaptureEndMs') else value
                                      for name, value in zip(columns[t], row)) for row in expected]
                if latest >= 9 and t == 'insight_findings':
                    expected = [tuple('RESOLVED' if name == 'status' and value == 'ACTIVE' else value
                                      for name, value in zip(columns[t], row)) for row in expected]
                assert [r[:width] for r in db.execute(f'SELECT * FROM {t} ORDER BY 1')] == expected, ('Historical row values changed', t)
            if version == 6:
                for table, names in added_columns.items():
                    if latest >= 9 and table in ('app_snapshots', 'app_snapshot_uids'):
                        continue
                    assert db.execute(f'SELECT COUNT(*) FROM {table}').fetchone()[0] > 0, ('Unseeded v6 table', table)
                    columns = ', '.join(f'`{name}`' for name in sorted(names))
                    assert all(all(value is None for value in row) for row in db.execute(f'SELECT {columns} FROM {table}')), (table, 'New fields must read null')
            if version == 7:
                assert db.execute('SELECT metric FROM insight_actions').fetchall() == [(None,)]
            if version == 5:
                for table in ('charge_sessions', 'daily_summaries'):
                    assert db.execute(f'SELECT screenOnCoveredMs, screenOffCoveredMs FROM {table}').fetchall() == [(None, None)]
            assert db.execute('PRAGMA foreign_key_check').fetchall() == [], ('Dangling foreign keys', version)
            print(f'PASS SQLite schema, foreign keys, createSql-identical DDL, preserved history and legacy provenance invalidation: {version} → {latest} (foreign_keys={foreign_keys})')

# Run the production SQL against the same old-version fixture as the real Room test.
# Missing invalidation is exercised as a no-op, so the regression fails on its observable effect.
for foreign_keys in ('OFF', 'ON'):
    with sqlite3.connect(':memory:') as db:
        db.execute(f'PRAGMA foreign_keys={foreign_keys}')
        for sql in seeds[8]:
            db.execute(sql)
        tables = [e['tableName'] for e in schemas[8]['entities']]
        for table in ('app_snapshots', 'app_snapshot_uids', 'snapshot_device_wakers', 'session_device_wakers'):
            assert db.execute(f'SELECT COUNT(*) FROM {table}').fetchone()[0] > 0, ('Unseeded legacy attribution fixture', table)
        assert {row[0] for row in db.execute('SELECT status FROM insight_actions')} == {
            'PREPARED', 'APPLIED', 'FAILED', 'UNKNOWN', 'REVERTED', 'ONE_SHOT',
        }, 'The legacy fixture must cover every action status'
        assert db.execute('PRAGMA foreign_key_check').fetchall() == [], 'Invalid legacy fixture'
        snapshots = {table: db.execute(f'SELECT * FROM {table} ORDER BY 1').fetchall() for table in tables}
        eligible = "SELECT sessionId FROM charge_sessions WHERE appUsageStatus='READY' AND appUsageBasis='DELTA' AND appCaptureStartMs IS NOT NULL AND appCaptureEndMs IS NOT NULL"
        assert db.execute(eligible).fetchall() == [('v8',)], 'The legacy fixture must initially be eligible'
        device_payload = db.execute("SELECT evidenceJson FROM insight_findings WHERE key='device-active'").fetchone()[0]
        assert json.loads(device_payload)['attributions'][0]['packageName'] == 'victim.app'
        for sql in migrations.get(8, []):
            db.execute(sql)
        verify(db, 8)
        assert db.execute('PRAGMA foreign_keys').fetchone()[0] == (foreign_keys == 'ON')
        assert db.execute(eligible).fetchall() == [], 'Legacy checkin app captures still certify a measured window'
        assert db.execute('SELECT appCaptureStartMs,appCaptureEndMs FROM charge_sessions').fetchall() == [(None, None), (None, None)]
        for table in ('app_snapshots', 'app_snapshot_uids', 'snapshot_device_wakers', 'session_device_wakers'):
            assert db.execute(f'SELECT COUNT(*) FROM {table}').fetchone()[0] == 0, ('Untrusted transient/app attribution rows survived', table)
        assert db.execute("SELECT key,status,feedbackMultiplier FROM insight_findings ORDER BY key").fetchall() == [
            ('app-active', 'RESOLVED', 1.5), ('app-dismissed', 'DISMISSED', 3.0),
            ('app-resolved', 'RESOLVED', 4.0), ('device-active', 'RESOLVED', 2.0),
        ], 'Old actionable findings survived, or lifecycle/feedback history changed'
        for table in ('battery_samples', 'daily_summaries', 'session_app_usage', 'insight_actions'):
            assert db.execute(f'SELECT * FROM {table} ORDER BY 1').fetchall() == snapshots[table], ('Historical data or undo authority changed', table)
        session_columns = [row[1] for row in db.execute('PRAGMA table_info(charge_sessions)')]
        expected_sessions = [tuple(None if name in ('appCaptureStartMs', 'appCaptureEndMs') else value
                                   for name, value in zip(session_columns, row)) for row in snapshots['charge_sessions']]
        assert db.execute('SELECT * FROM charge_sessions ORDER BY 1').fetchall() == expected_sessions
        finding_columns = [row[1] for row in db.execute('PRAGMA table_info(insight_findings)')]
        expected_findings = [tuple('RESOLVED' if name == 'status' and value == 'ACTIVE' else value
                                  for name, value in zip(finding_columns, row)) for row in snapshots['insight_findings']]
        assert db.execute('SELECT * FROM insight_findings ORDER BY 1').fetchall() == expected_findings
        assert db.execute('PRAGMA foreign_key_check').fetchall() == []
        print(f'PASS SQLite 8 → 9 provenance invalidation, finding lifecycle/feedback, history and complete undo journal preservation (foreign_keys={foreign_keys})')
