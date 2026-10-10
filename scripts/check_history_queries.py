#!/usr/bin/env python3
"""Exercise actual Room DAO SQL on host SQLite; not a substitute for Android Room tests."""
import json
from pathlib import Path
import re
import sqlite3

root = Path(__file__).resolve().parents[1]
dao = (root / 'app/src/main/java/com/akane/voltwise/battery/data/db/Dao.kt').read_text()
schemas = (root / 'app/schemas/com.akane.voltwise.battery.data.db.BatteryDatabase').glob('*.json')
schema = json.loads(max(schemas, key=lambda p: int(p.stem)).read_text())['database']  # newest version

def query(method, interface=None):
    section = dao if interface is None else dao.split('interface ' + interface + ' {')[1].split('\n}\n')[0]
    found = re.search(r'@Query\("([^"\n]+)"\)\s+(?:suspend )?fun ' + method + r'\(', section)
    assert found, 'Cannot locate actual DAO query: ' + method
    return found[1]

with sqlite3.connect(':memory:') as db:
    for entity in schema['entities']:
        db.execute(entity['createSql'].replace('${TABLE_NAME}', entity['tableName']))
        for index in entity.get('indices', []):
            db.execute(index['createSql'].replace('${TABLE_NAME}', entity['tableName']))
    for at, origin, observation, row_id in [(1500, 'BatteryManager', 'local', 1),
                                           (1600, 'import:BatteryManager', 'import:foreign', -100),
                                           (1700, 'legacy', None, 2)]:
        db.execute('INSERT INTO battery_samples(id,timestamp,status,screenOn,source,observationId,elapsedMs) VALUES(?,?,3,1,?,?,?)',
                   (row_id, at, origin, observation, at))
    rows = db.execute(query('chartSamples'), {'from': 0, 'to': 3000, 'bucketMs': 1}).fetchall()
    assert len(rows) == 1 and rows[0][0] == 1, 'Local chart mixed imported or legacy data'
    assert db.execute('SELECT COUNT(*) FROM battery_samples').fetchone()[0] == 3, 'Filtering deleted history'
    db.execute('INSERT INTO battery_samples(timestamp,status,screenOn) VALUES(1800,3,1)')
    assert db.execute('SELECT id FROM battery_samples WHERE timestamp=1800').fetchone()[0] > 0, 'Imported IDs corrupted local AUTOINCREMENT'
    db.commit()
    try:
        with db:
            db.execute('INSERT INTO battery_samples(timestamp,status,screenOn,observationId,elapsedMs) VALUES(1900,3,1,\'new\',1900)')
            db.execute('INSERT INTO battery_samples(timestamp,status,screenOn,observationId,elapsedMs) VALUES(2000,3,1,\'new\',1900)')
    except sqlite3.IntegrityError:
        pass
    else:
        raise AssertionError('Observed-point uniqueness not enforced')
    assert db.execute("SELECT COUNT(*) FROM battery_samples WHERE observationId='new'").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM battery_samples").fetchone()[0] == 4, "Rollback removed pre-existing records"
    for i in range(20):
        db.execute("INSERT INTO charge_sessions(sessionId,type,startTime,endTime,activeKey) VALUES(?,'DISCHARGE',?,?,?)",
                   (str(i), i * 1000, None if i == 0 else (i + 1) * 1000, 1 if i == 0 else None))
    overlaps = db.execute(query('sessionsBetween'), {'from': 1500, 'to': 1600}).fetchall()
    assert [row[0] for row in overlaps] == ['1'], 'Overlapping session selection was incorrect'
    db.execute(query('boundStorage', 'SessionDao'), {'limit': 3})
    assert db.execute('SELECT COUNT(*) FROM charge_sessions').fetchone()[0] == 3, 'Session bound not enforced'
    assert db.execute('SELECT sessionId FROM charge_sessions WHERE activeKey=1').fetchone()[0] == '0', 'Old active session was evicted'
print('PASS actual DAO SQL: source-separated charts, positive local IDs, unique points/rollback, overlapping windows, bounded sessions retaining active state')

with sqlite3.connect(':memory:') as db:
    db.row_factory = sqlite3.Row
    for entity in schema['entities']:
        db.execute(entity['createSql'].replace('${TABLE_NAME}', entity['tableName']))
        for index in entity.get('indices', []):
            db.execute(index['createSql'].replace('${TABLE_NAME}', entity['tableName']))
    for i in range(125):
        db.execute("INSERT INTO charge_sessions(sessionId,type,startTime,endTime,source) VALUES(?,?,?,?,?)",
                   (str(i), 'CHARGE' if i == 0 else 'DISCHARGE', i * 1000, i * 1000, 'import:saved_origin' if i == 0 else 'BatteryManager observed interval'))
    args = {'type': None, 'query': '', 'limit': 51}
    assert len(db.execute(query('filteredSessions'), args).fetchall()) == 51
    args['limit'] = 151
    assert len(db.execute(query('filteredSessions'), args).fetchall()) == 125, 'Older history is inaccessible'
    args.update({'type': 'CHARGE', 'query': 'SAVED_', 'limit': 51})
    assert [r['sessionId'] for r in db.execute(query('filteredSessions'), args)] == ['0'], 'Filter only searched recent page or mishandled literal underscore'
    for i in range(1001):
        db.execute("INSERT INTO battery_samples(timestamp,status,screenOn,sessionId,observationId,currentNowUa,voltageMv,temperatureDeciC,boundaryReason) VALUES(?,3,1,'chart','o',?,4000,250,?)",
                   (i * 1000, None if i == 400 else -123, 'interrupted' if i == 501 else None))
    db.execute("INSERT INTO battery_samples(timestamp,status,screenOn,sessionId,currentNowUa) VALUES(900000,3,1,'foreign',999999)")
    rows = db.execute(query('sessionChartSamples'), {'sessionId': 'chart', 'from': 0, 'to': 1000000, 'bucketMs': 1000000 // 360 + 1}).fetchall()
    assert len(rows) <= 361, 'Session chart was unbounded'
    assert all(r['currentNowUa'] != 999999 for r in rows), 'Session chart mixed unrelated time-overlapping readings'
    assert sum(r['discontinuity'] for r in rows) >= 2, 'Downsampling hid missing samples or explicit gaps'
print('PASS actual history UI queries: paging/filter across125 records, bounded session-only charts preserving discontinuities')

with sqlite3.connect(':memory:') as db:
    db.execute('PRAGMA foreign_keys=ON')  # Room enables it on open because the schema has foreign keys
    for entity in schema['entities']:
        db.execute(entity['createSql'].replace('${TABLE_NAME}', entity['tableName']))
        for index in entity.get('indices', []):
            db.execute(index['createSql'].replace('${TABLE_NAME}', entity['tableName']))
    db.execute("INSERT INTO charge_sessions(sessionId,type,startTime,endTime,activeKey) VALUES('open','DISCHARGE',5000,NULL,1)")
    db.execute("INSERT INTO charge_sessions(sessionId,type,startTime,endTime,activeKey) VALUES('closed','DISCHARGE',1000,2000,NULL)")
    def snapshot(session, kind, at):
        snapshot_id = db.execute('INSERT INTO app_snapshots(sessionId,kind,capturedAt) VALUES(?,?,?)', (session, kind, at)).lastrowid
        db.execute("INSERT INTO app_snapshot_uids(snapshotId,uid,packageName,powerMah) VALUES(?,10001,'a',1.0)", (snapshot_id,))
        return snapshot_id
    baseline = snapshot('open', 'BASELINE', 100)
    snapshot('closed', 'BASELINE', 50); snapshot(None, 'END', 10)
    ends = [snapshot('closed', 'END', 200 + i) for i in range(4)]
    db.execute(query('pruneSnapshots'), {'keepLatest': 3})
    kept = [r[0] for r in db.execute('SELECT id FROM app_snapshots ORDER BY capturedAt DESC, id DESC')]
    assert kept == [ends[3], ends[2], ends[1], baseline], 'Snapshot pruning lost the open baseline or kept too many'
    assert db.execute('SELECT COUNT(*) FROM app_snapshot_uids').fetchone()[0] == 4, 'Pruned snapshot uids did not cascade'
    assert db.execute(query('latestSnapshot'), {'sessionId': 'open', 'kind': 'BASELINE'}).fetchone()[0] == baseline
    for rank in (2, 0, 1):
        db.execute("INSERT INTO session_app_usage(sessionId,rank,uid,packageName,powerMah,isOthers,basis) VALUES('closed',?,?,'p',1.5,?,'DELTA')",
                   (rank, 10000 + rank, int(rank == 2)))
    assert [r[1] for r in db.execute(query('sessionUsage'), {'sessionId': 'closed'})] == [0, 1, 2], 'App usage not in rank order'
    assert len(db.execute(query('usageForSessionsBetween'), {'from': 1500, 'to': 1600}).fetchall()) == 3, 'Export missed overlapping session usage'
    assert db.execute(query('usageForSessionsBetween'), {'from': 6000, 'to': 7000}).fetchall() == [], 'Export mixed in other sessions'
    db.execute(query('setAppUsageStatus'), {'sessionId': 'closed', 'status': 'READY', 'basis': 'DELTA'})
    assert db.execute("SELECT appUsageStatus, appUsageBasis FROM charge_sessions WHERE sessionId='closed'").fetchone() == ('READY', 'DELTA')
    try:
        db.execute("INSERT INTO session_app_usage(sessionId,rank,uid,packageName,powerMah,isOthers,basis) VALUES('missing',0,1,'p',1,0,'DELTA')")
    except sqlite3.IntegrityError:
        pass
    else:
        raise AssertionError('App usage accepted a missing session')
    db.execute(query('purge', 'SessionDao'), {'olderThan': 3000})
    assert db.execute('SELECT COUNT(*) FROM session_app_usage').fetchone()[0] == 0, 'Deleting a session left its app usage'
    db.execute(query('pruneOrphanSnapshots'))
    assert [r[0] for r in db.execute('SELECT id FROM app_snapshots')] == [baseline], 'Orphaned snapshots survived retention'
    for day in (-1, 0, 5):
        db.execute('INSERT INTO daily_summaries(epochDay,screenOnMs,screenOffMs,screenOnDischargeUah,screenOffDischargeUah,chargedUah,updatedAt) VALUES(?,0,0,0,0,0,0)', (day,))
    assert [r[0] for r in db.execute(query('between', 'DailySummaryDao'), {'fromDay': -1, 'toDay': 4})] == [-1, 0]
    db.execute(query('purgeBefore'), {'epochDay': 0})
    assert [r[0] for r in db.execute('SELECT epochDay FROM daily_summaries ORDER BY epochDay')] == [0, 5], 'Day retention wrong'
print('PASS actual v5 DAO SQL: snapshot pruning keeps the open baseline + last 3 with uid cascade, ranked usage, export join, FK/cascade, orphan and day retention')

with sqlite3.connect(':memory:') as db:
    db.row_factory = sqlite3.Row
    db.execute('PRAGMA foreign_keys=ON')
    for entity in schema['entities']:
        db.execute(entity['createSql'].replace('${TABLE_NAME}', entity['tableName']))
        for index in entity.get('indices', []):
            db.execute(index['createSql'].replace('${TABLE_NAME}', entity['tableName']))
    for i in range(12):
        db.execute("INSERT INTO charge_sessions(sessionId,type,startTime,endTime,capacityEstimateMah,capacityConfidence,capacityBasis) VALUES(?,'DISCHARGE',?,?,?,?,?)",
                   ('s%02d' % i, i * 1000, i * 1000 + 500, None if i % 3 == 0 else 4000 + i, None if i % 3 == 0 else 'HIGH', 'COUNTER_SPAN'))
    trend = db.execute(query('capacityEstimates'), {'limit': 5}).fetchall()
    assert [r['sessionId'] for r in trend] == ['s11', 's10', 's08', 's07', 's05'], 'Trend not newest-first estimates only, or unbounded'
    assert set(trend[0].keys()) == {'sessionId', 'type', 'startTime', 'endTime', 'lastSampleTime', 'startLevel', 'endLevel',
                                    'capacityEstimateMah', 'capacityConfidence', 'capacityBasis', 'source'}, 'Trend projection columns drifted'
    # Per-session delete (SessionDao.deleteSession): snapshots, samples, then the row; uids and app usage cascade.
    snapshot_id = db.execute("INSERT INTO app_snapshots(sessionId,kind,capturedAt) VALUES('s07','BASELINE',1)").lastrowid
    db.execute("INSERT INTO app_snapshot_uids(snapshotId,uid,packageName,powerMah) VALUES(?,10001,'a',1.0)", (snapshot_id,))
    db.execute("INSERT INTO app_snapshots(sessionId,kind,capturedAt) VALUES('s08','BASELINE',2)")
    db.execute("INSERT INTO session_app_usage(sessionId,rank,uid,packageName,powerMah,isOthers,basis) VALUES('s07',0,10001,'a',1.0,0,'DELTA')")
    db.execute("INSERT INTO session_app_usage(sessionId,rank,uid,packageName,powerMah,isOthers,basis) VALUES('s08',0,10001,'a',1.0,0,'DELTA')")
    for at, session in [(7100, 's07'), (7200, 's07'), (8100, 's08')]:
        db.execute('INSERT INTO battery_samples(timestamp,status,screenOn,sessionId) VALUES(?,3,1,?)', (at, session))
    for method in ('deleteSessionSnapshots', 'deleteSessionSamples', 'deleteSessionRow'):
        db.execute(query(method), {'id': 's07'})
    counts = {table: db.execute("SELECT COUNT(*) FROM %s" % table).fetchone()[0]
              for table in ('charge_sessions', 'app_snapshots', 'app_snapshot_uids', 'session_app_usage', 'battery_samples')}
    assert counts == {'charge_sessions': 11, 'app_snapshots': 1, 'app_snapshot_uids': 0, 'session_app_usage': 1, 'battery_samples': 1}, \
        'Per-session delete missed rows or touched another session: %s' % counts
print('PASS actual trend/delete SQL: bounded newest-first estimate projection, per-session delete with snapshot-uid and app-usage cascade')

with sqlite3.connect(':memory:') as db:
    db.row_factory = sqlite3.Row
    db.execute('PRAGMA foreign_keys=ON')
    for entity in schema['entities']:
        db.execute(entity['createSql'].replace('${TABLE_NAME}', entity['tableName']))
        for index in entity.get('indices', []):
            db.execute(index['createSql'].replace('${TABLE_NAME}', entity['tableName']))
    for session, kind, end in [('before', 'DISCHARGE', 99), ('discharge', 'DISCHARGE', 100),
                               ('charge', 'CHARGE', 150), ('plugged', 'PLUGGED', 200),
                               ('after', 'DISCHARGE', 201), ('open', 'DISCHARGE', None)]:
        db.execute('INSERT INTO charge_sessions(sessionId,type,startTime,endTime) VALUES(?,?,0,?)', (session, kind, end))
    assert [r['sessionId'] for r in db.execute(query('closedSessionsBetween'), {'from': 100, 'to': 200})] == ['discharge', 'charge', 'plugged'], 'Closed range must include both endpoints/all types, never open/outside sessions'
    for day in (1, 2, 3):
        db.execute('INSERT INTO daily_summaries(epochDay,screenOnMs,screenOffMs,screenOnDischargeUah,screenOffDischargeUah,chargedUah,updatedAt) VALUES(?,0,0,0,0,0,0)', (day,))
    assert [r['epochDay'] for r in db.execute(query('range', 'DailySummaryDao'), {'fromDay': 1, 'toDay': 2})] == [1, 2], 'One-shot day range differs from flow'

    # Room expands collection parameters. Execute the extracted SQL with the same bounded expansion.
    def collection(method, parameter, values):
        sql = query(method).replace(':' + parameter, ','.join('?' for _ in values))
        return db.execute(sql, values).fetchall()
    for session in ('charge', 'discharge'):
        for rank in (1, 0):
            db.execute('INSERT INTO session_device_wakers(sessionId,kind,name,count,totalMs,rank) VALUES(?,?,?,?,?,?)',
                       (session, 'WAKEUP_REASON', f'alarm{rank}', rank + 1, 20, rank))
            db.execute("INSERT INTO session_app_usage(sessionId,rank,uid,packageName,powerMah,isOthers,basis) VALUES(?,?,10001,'example.app',1,0,'DELTA')", (session, rank))
    expected = [('charge', 0), ('charge', 1), ('discharge', 0), ('discharge', 1)]
    for method in ('sessionWakers', 'usageRowsForSessions'):
        assert [(r['sessionId'], r['rank']) for r in collection(method, 'sessionIds', ['discharge', 'charge'])] == expected, method
        assert [r['sessionId'] for r in collection(method, 'sessionIds', ['charge'])] == ['charge', 'charge'], 'Mixed unrequested sessions'
        assert collection(method, 'sessionIds', []) == [], 'Empty collection returned rows'
    db.execute(query('deleteSessionWakers'), {'sessionId': 'charge'})
    assert len(collection('sessionWakers', 'sessionIds', ['discharge', 'charge'])) == 2, 'Replacement delete touched another session'
    db.execute(query('deleteSessionRow'), {'id': 'discharge'})
    assert collection('sessionWakers', 'sessionIds', ['discharge']) == [], 'Session wakers did not cascade'
    assert collection('usageRowsForSessions', 'sessionIds', ['discharge']) == [], 'Usage did not cascade'

    snapshots = []
    for at in range(4):
        sid = db.execute("INSERT INTO app_snapshots(sessionId,kind,capturedAt) VALUES('charge','END',?)", (at,)).lastrowid
        snapshots.append(sid)
        db.execute("INSERT INTO snapshot_device_wakers VALUES(?,'KERNEL_WAKELOCK','kernel',1,20)", (sid,))
    assert [r['name'] for r in db.execute(query('snapshotWakers'), {'snapshotId': snapshots[0]})] == ['kernel']
    db.execute(query('pruneSnapshots'), {'keepLatest': 3})
    assert db.execute(query('snapshotWakers'), {'snapshotId': snapshots[0]}).fetchall() == [], 'Pruned snapshot wakers did not cascade'
    assert db.execute('SELECT COUNT(*) FROM snapshot_device_wakers').fetchone()[0] == 3
    for table, sql in [('snapshot_device_wakers', "INSERT INTO snapshot_device_wakers VALUES(-1,'WAKEUP_REASON','missing',1,1)"),
                       ('session_device_wakers', "INSERT INTO session_device_wakers VALUES('missing','WAKEUP_REASON','missing',1,1,0)")]:
        try:
            db.execute(sql)
        except sqlite3.IntegrityError:
            pass
        else:
            raise AssertionError(f'{table} accepted an absent parent')

    for key, seen, score in [('old', 99, 1.0), ('boundary', 100, 3.0), ('new', 101, 2.0)]:
        db.execute("INSERT INTO insight_findings(`key`,type,severity,confidence,score,firstSeenAt,lastSeenAt,status,evidenceVersion,evidenceJson) VALUES(?,'TREND','LOW','HIGH',?,1,?,'ACTIVE',1,'{}')", (key, score, seen))
    for method in ('findings', 'findingsOnce'):
        assert [r['key'] for r in db.execute(query(method, 'InsightDao'))] == ['boundary', 'new', 'old'], 'Findings ordering differs'
    assert db.execute("SELECT feedbackMultiplier FROM insight_findings WHERE `key`='old'").fetchone()[0] == 1.0, 'SQL feedback default missing'
    db.execute(query('setStatus', 'InsightDao'), {'key': 'boundary', 'status': 'DISMISSED'})
    assert tuple(db.execute("SELECT status,feedbackMultiplier FROM insight_findings WHERE `key`='boundary'").fetchone()) == ('DISMISSED', 1.0), 'Status update changed feedback'
    assert tuple(db.execute("SELECT status,feedbackMultiplier FROM insight_findings WHERE `key`='new'").fetchone()) == ('ACTIVE', 1.0), 'Targeted update touched another finding'
    db.execute(query('purgeFindingsSeenBefore'), {'ms': 100})
    assert [r['key'] for r in db.execute(query('findingsOnce'))] == ['boundary', 'new'], 'Retention boundary wrong'
    for status, at in [('PREPARED', 1), ('UNKNOWN', 2), ('APPLIED', 3), ('FAILED', 4), ('REVERTED', 5), ('ONE_SHOT', 6)]:
        db.execute("INSERT INTO insight_actions(findingKey,type,userId,status,priorStateVersion,createdAt) VALUES('boundary','RESTRICT_BACKGROUND',0,?,1,?)", (status, at))
    assert [r['status'] for r in collection('actionsWithStatus', 'statuses', ['PREPARED', 'UNKNOWN'])] == ['UNKNOWN', 'PREPARED'], 'Reconciliation selection wrong'
    assert collection('actionsWithStatus', 'statuses', []) == []
    expected_actions = [tuple(r) for r in db.execute(query('actionsOnce'))]
    assert expected_actions == [tuple(r) for r in db.execute(query('actions'))]
    assert len({r[0] for r in expected_actions}) == 6 and all(r[0] > 0 for r in expected_actions), 'Action IDs must be distinct and generated'
    db.execute(query('clearFindings'))
    assert db.execute(query('findingsOnce')).fetchall() == []
    db.execute(query('clearAll', 'SessionDao'))
    db.execute(query('clearSnapshots'))
    assert db.execute('SELECT COUNT(*) FROM snapshot_device_wakers').fetchone()[0] == 0
    assert expected_actions == [tuple(r) for r in db.execute(query('actionsOnce'))], 'Action journal must survive finding/history deletion'
print('PASS actual v7 DAO SQL: inclusive closed/day ranges, collection selection/order, waker FK/cascades, finding status/feedback/retention, action reconciliation and independent journal')

# Execute the DAO mutation calls in the real repository/retention transaction bodies, not a
# separately maintained clear list. Host SQLite exercises the SQL/cascades, not Android Room.
repository = (root / 'app/src/main/java/com/akane/voltwise/battery/data/BatteryRepository.kt').read_text()
policy = (root / 'app/src/main/java/com/akane/voltwise/battery/data/HistoryPolicy.kt').read_text()
clear_body = repository.split('private suspend fun clear(result:')[1].split('// Calibration')[0]
retention_body = policy.split('suspend fun purgeExpired(')[1]

def history_mutations(db, body, args):
    calls = re.findall(r'(?:db\.)?(batteryDao|sessionDao|dailySummaryDao|appUsageDao|insightDao)(?:\(\))?\.(\w+)\(', body)
    assert calls, 'History transaction has no DAO mutations'
    for owner, method in calls:
        interface = owner[0].upper() + owner[1:]
        db.execute(query(method, interface), args)

with sqlite3.connect(':memory:') as db:
    db.row_factory = sqlite3.Row
    db.execute('PRAGMA foreign_keys=ON')
    for entity in schema['entities']:
        db.execute(entity['createSql'].replace('${TABLE_NAME}', entity['tableName']))
        for index in entity.get('indices', []):
            db.execute(index['createSql'].replace('${TABLE_NAME}', entity['tableName']))
    for status in ('PREPARED', 'APPLIED', 'UNKNOWN', 'REVERTED', 'FAILED', 'ONE_SHOT', 'FUTURE_STATUS'):
        for at in (99, 100, 101):
            db.execute("INSERT INTO insight_actions(findingKey,type,userId,status,priorStateVersion,createdAt) VALUES('f','RESTRICT_BACKGROUND',0,?,1,?)", (status, at))
    for at in (99, 100, 101):
        db.execute("INSERT INTO insight_actions(findingKey,type,userId,status,priorStateVersion,createdAt) VALUES('uncertain_force_stop','FORCE_STOP',0,'UNKNOWN',1,?)", (at,))
    # A recently undone action is not old just because its application was old.
    db.execute("INSERT INTO insight_actions(findingKey,type,userId,status,priorStateVersion,createdAt,appliedAt,revertedAt) VALUES('recent_undo','RESTRICT_BACKGROUND',0,'REVERTED',1,1,2,100)")
    db.execute("INSERT INTO insight_actions(findingKey,type,userId,status,priorStateVersion,createdAt,appliedAt) VALUES('recent_one_shot','FORCE_STOP',0,'ONE_SHOT',1,1,100)")
    for seen in (99, 100, 101):
        db.execute("INSERT INTO insight_findings(key,type,severity,confidence,score,firstSeenAt,lastSeenAt,status,evidenceVersion,evidenceJson) VALUES(?,'TREND','LOW','HIGH',1,1,?,'ACTIVE',1,'{}')", (str(seen), seen))
    for session, end in [('old', 99), ('boundary', 100)]:
        db.execute("INSERT INTO charge_sessions(sessionId,type,startTime,endTime) VALUES(?,'DISCHARGE',0,?)", (session, end))
        db.execute("INSERT INTO session_device_wakers VALUES(?,'WAKEUP_REASON','alarm',1,20,0)", (session,))
        sid = db.execute("INSERT INTO app_snapshots(sessionId,kind,capturedAt) VALUES(?,'END',1)", (session,)).lastrowid
        db.execute("INSERT INTO snapshot_device_wakers VALUES(?,'KERNEL_WAKELOCK','kernel',1,20)", (sid,))
    history_mutations(db, retention_body, {'ms': 100, 'olderThan': 100, 'epochDay': 0})
    expected = {(status, at) for status in ('PREPARED', 'APPLIED', 'UNKNOWN', 'REVERTED', 'FAILED', 'ONE_SHOT', 'FUTURE_STATUS')
                for at in (99, 100, 101) if at >= 100 or status not in ('REVERTED', 'FAILED', 'ONE_SHOT')}
    actual = {(r['status'], r['createdAt']) for r in db.execute(query('actionsOnce')) if r['findingKey'] == 'f'}
    assert actual == expected, 'Retention must purge only expired terminal reversible actions, keeping Undo/reconciliation authority and cutoff boundary'
    assert ('UNKNOWN', 99) in actual, 'Retention must preserve old reversible UNKNOWN actions as reconciliation authority'
    force_stop_times = {r['createdAt'] for r in db.execute(query('actionsOnce')) if r['findingKey'] == 'uncertain_force_stop'}
    assert 99 not in force_stop_times, 'Retention must purge old FORCE_STOP UNKNOWN actions without Undo/reconciliation authority'
    assert 100 in force_stop_times, 'Retention must preserve FORCE_STOP UNKNOWN actions exactly at the cutoff'
    assert 101 in force_stop_times, 'Retention must preserve recent FORCE_STOP UNKNOWN attempts'
    assert db.execute("SELECT COUNT(*) FROM insight_actions WHERE findingKey LIKE 'recent_%'").fetchone()[0] == 2, 'Retention discarded a recently completed terminal action'
    assert [r['key'] for r in db.execute(query('findingsOnce'))] == ['100', '101'], 'Repository retention must purge findings by lastSeenAt with an exclusive cutoff'
    for table in ('session_device_wakers', 'snapshot_device_wakers'):
        assert db.execute('SELECT COUNT(*) FROM ' + table).fetchone()[0] == 1, 'Retention must cascade expired wakers, preserving boundary session'
    actions_before = [tuple(r) for r in db.execute(query('actionsOnce'))]
    history_mutations(db, clear_body, {})
    assert db.execute(query('findingsOnce')).fetchall() == [], 'Clear history must delete insight findings'
    assert [tuple(r) for r in db.execute(query('actionsOnce'))] == actions_before, 'Clear history must preserve all insight actions byte-for-byte'
    for table in ('charge_sessions', 'app_snapshots', 'session_device_wakers', 'snapshot_device_wakers'):
        assert db.execute('SELECT COUNT(*) FROM ' + table).fetchone()[0] == 0, 'Clear history must delete history and cascade both waker tables'
print('PASS history orchestration: Clear deletes findings/wakers but preserves actions; retention preserves reversible authority/future statuses and cutoff boundaries, purges expired FORCE_STOP UNKNOWN attempts')
