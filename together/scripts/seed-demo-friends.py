#!/usr/bin/env python3
"""Add fictional, accepted friends to the currently running disposable demo.

Demo HTTP registration deliberately returns a fixed actor. Use Store's normal
validation against the verified live *demo* database; never open personal data.
No account credentials are printed or written to the results file.
"""
import argparse
import datetime as dt
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import sys
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from together.server.domain import Store, PLACES


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--pid', required=True, type=int)
    args = parser.parse_args()
    command = subprocess.check_output(['ps', '-p', str(args.pid), '-o', 'command='], text=True)
    if '-m together.server.demo' not in command:
        raise SystemExit('The selected process is not the disposable demo server.')
    request = urllib.request.Request('http://127.0.0.1:8766/api/sessions', data=b'{}', headers={'Content-Type': 'application/json'}, method='POST')
    with urllib.request.urlopen(request, timeout=5) as response:
        session = json.load(response)
    files = subprocess.check_output(['lsof', '-p', str(args.pid), '-Fn'], text=True)
    databases = {Path(line[1:]) for line in files.splitlines() if line.startswith('n') and line.endswith('/demo.sqlite3')}
    if len(databases) != 1:
        raise SystemExit('Could not uniquely identify the live demo database.')
    database = databases.pop()
    if not database.parent.name.startswith('together-demo-'):
        raise SystemExit('Refusing a database outside the disposable demo directory.')
    store = Store(database)
    try:
        driver = store.user(session['token'])
        assert driver['id'] == session['user']['id'] and driver['name'] == '시연 운전자'
        before = store.state(driver)
        before_docs = {a['id']: json.dumps(a, ensure_ascii=False, sort_keys=True) for a in before['appointments']}
        backup = ROOT/'backups'/('demo-before-friends-'+dt.datetime.now().strftime('%Y%m%d-%H%M%S')+'.sqlite3')
        destination = sqlite3.connect(backup)
        try:
            store.db.backup(destination)
        finally:
            destination.close()
        os.chmod(backup, 0o600)
        key = 'pooly_fictional_friends_v1'
        row = store.db.execute('SELECT value FROM metadata WHERE key=?', (key,)).fetchone()
        meta = json.loads(row[0]) if row else {'profiles': {}}
        contacts = []
        for name in ['Max', 'Kelly', 'Jeff']:
            profile = meta['profiles'].get(name)
            if not profile or not store.db.execute('SELECT id FROM users WHERE id=?', (profile['id'],)).fetchone():
                profile = store.register({'name': name})['user']
                meta['profiles'][name] = profile
            contacts.append(store.contact(driver['id'], {'name': name}))
        aid = meta.get('appointment_id')
        a = next((a for a in store.state(driver)['appointments'] if a['id'] == aid and a['trip']['phase'] not in ['cancelled','finished']), None)
        if a is None:
            meeting = (dt.datetime.now(dt.timezone(dt.timedelta(hours=9))) + dt.timedelta(days=1)).replace(hour=14, minute=0, second=0, microsecond=0)
            a = store.create(driver, {'name': '가상 친구 픽업 테스트', 'meeting_at': meeting.isoformat(), 'origin': PLACES[0], 'destination': PLACES[1], 'mode': 'car', 'members': [{'id': c['id'], 'mode': 'pickup' if n < 2 else 'walk'} for n,c in enumerate(contacts)]})
            for n, contact in enumerate(contacts):
                member = next(m for m in a['members'] if m['id'] == contact['id'])
                body = {'mode': 'pickup' if n < 2 else 'walk', 'share': False}
                if n < 2:
                    body.update(pickup=PLACES[2 if n == 0 else 4], ready='waiting')
                else:
                    body.update(origin=PLACES[6], travel_minutes=12)
                store.join(meta['profiles'][contact['name']], member['invite_token'], body)
            aid = a['id']; meta['appointment_id'] = aid
        with store.db:
            store.db.execute('INSERT OR REPLACE INTO metadata VALUES(?,?)', (key, json.dumps(meta, ensure_ascii=False)))
        after = store.state(driver)
        a = next(a for a in after['appointments'] if a['id'] == aid)
        unchanged = all(json.dumps(next(a for a in after['appointments'] if a['id'] == old_id), ensure_ascii=False, sort_keys=True) == document for old_id,document in before_docs.items() if old_id != aid)
        assert unchanged, 'An existing appointment changed during seeding; inspect before continuing.'
        result = {'appointment_id': aid, 'name': a['name'], 'phase': a['trip']['phase'], 'members': [{'name': m['name'], 'status': m['status'], 'mode': m['mode'], 'ready': m['ready'], 'pickup': m['pickup']['name'] if m['pickup'] else None} for m in a['members']], 'existingAppointmentsUnchanged': unchanged, 'serverRestarted': False}
        output = ROOT/'together/verification/top-right-panel-2026-11-09/seed-result.json'
        output.write_text(json.dumps(result, ensure_ascii=False, indent=2))
        print(json.dumps(result, ensure_ascii=False, indent=2))
    finally:
        store.close()


if __name__ == '__main__':
    main()
