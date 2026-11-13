import time
import unittest
from pooly.tests import test_flask as fixtures


class NavigationSyncTests(unittest.TestCase):
    setUp=fixtures.FlaskWorkflowTests.setUp
    tearDown=fixtures.FlaskWorkflowTests.tearDown
    rider=fixtures.FlaskWorkflowTests.rider

    def prepare(self):
        self.rider_auth,_=self.rider(0)
        self.rider(1)
        self.path='/api/appointments/'+self.a['id']+'/actions'
        self.client.post(self.path,json={'action':'depart'},headers=self.auth)
        self.now=int(time.time()*1000)

    def status(self,status,session='route1',at=None,headers=None):
        return self.client.post(self.path,json={'action':'navigation_status','status':status,'session_id':session,'requested_at':self.now if at is None else at,'reason':'test'},headers=headers or self.auth)

    def test_stop_preserves_trip_and_riders_and_resume_uses_new_session(self):
        self.prepare()
        for s in ['requested','guiding','stopped']:
            r=self.status(s);self.assertEqual(200,r.status_code)
        a=r.get_json();self.assertEqual('driving',a['trip']['phase']);self.assertEqual([],a['trip']['boarded'])
        self.assertIsNone(a['trip']['finished_at']);self.assertEqual('stopped',a['trip']['navigation']['status'])
        self.assertEqual('stopped',self.status('guiding').get_json()['trip']['navigation']['status'])
        self.assertEqual('guiding',self.status('guiding','route2',self.now+1).get_json()['trip']['navigation']['status'])
        self.assertEqual('route2',self.status('stopped','route1').get_json()['trip']['navigation']['session_id'])

    def test_status_is_driver_owned_and_bad_metadata_rejected(self):
        self.prepare();self.assertEqual(403,self.status('stopped',headers=self.rider_auth).status_code)
        self.assertEqual(400,self.status('invalid').status_code);self.assertEqual(400,self.status('guiding',at=self.now+30000).status_code)
        self.assertEqual(400,self.status('guiding',session='').status_code)

    def test_same_session_status_never_regresses_or_changes_revision_on_replay(self):
        self.prepare();a=self.status('guiding').get_json();revision=a['revision']
        for s in ['guiding','requested']:
            b=self.status(s).get_json();self.assertEqual('guiding',b['trip']['navigation']['status']);self.assertEqual(revision,b['revision'])

    def test_finished_appointment_cannot_restart_via_late_native_event(self):
        self.prepare();self.status('guiding')
        self.client.post(self.path,json={'action':'cancel'},headers=self.auth)
        self.assertEqual(409,self.status('guiding','later',self.now+1).status_code)
        a=self.client.get('/api/state',headers=self.auth).get_json()['appointments'][0]
        self.assertEqual('cancelled',a['trip']['phase']);self.assertEqual('stopped',a['trip']['navigation']['status'])

    def test_destination_proximity_finishes_only_the_matching_final_leg(self):
        self.prepare()
        self.status('guiding')
        destination = self.a['destination']
        evidence = dict(action='auto_finish', source='nearby', session_id='route1',
                        observed_at=int(time.time()*1000), lat=destination['lat'], lon=destination['lon'], remaining_m=40)

        def send(**changes):
            return self.client.post(self.path, json={**evidence, **changes}, headers=self.auth)

        self.assertEqual(409, send().status_code)  # Pending pickups.
        for _ in range(2):
            self.assertEqual(200, self.client.post(self.path, json={'action':'skip'}, headers=self.auth).status_code)
        for changes in (dict(session_id='older'), dict(lat=destination['lat']+.01),
                        dict(remaining_m=81), dict(observed_at=evidence['observed_at']-30000)):
            self.assertEqual(409, send(**changes).status_code)
        a = send().get_json()
        self.assertEqual('finished', a['trip']['phase'])
        self.assertEqual('stopped', a['trip']['navigation']['status'])
        self.assertEqual(a['trip']['finished_at'], a['owner']['arrived_at'])
        self.assertFalse(a['owner']['share'])
        self.assertEqual(409, send().status_code)

    def test_final_arrival_can_follow_oem_guidance_stop(self):
        self.path = '/api/appointments/' + self.a['id'] + '/actions'
        self.assertEqual(200, self.client.post(self.path, json={'action':'depart'}, headers=self.auth).status_code)
        self.now = int(time.time()*1000)
        self.status('guiding')
        stopped = dict(action='navigation_status', status='stopped', session_id='route1',
                       requested_at=self.now, reason='oem_stopped')
        self.assertEqual(200, self.client.post(self.path, json=stopped, headers=self.auth).status_code)
        destination = self.a['destination']
        evidence = dict(action='auto_finish', source='nearby', session_id='route1', observed_at=self.now,
                        lat=destination['lat'], lon=destination['lon'], remaining_m=10)
        self.assertEqual(409, self.client.post(self.path, json=evidence, headers=self.auth).status_code)
        response = self.client.post(self.path, json={**evidence, 'source':'arrival'}, headers=self.auth)
        self.assertEqual(200, response.status_code)
        self.assertEqual('finished', response.get_json()['trip']['phase'])

    def test_selection_order_is_saved_when_creating_appointment(self):
        payload={**self.payload,'pickup_order':[self.contacts[1]['id'],self.contacts[0]['id']]}
        a=self.client.post('/api/appointments',json=payload,headers=self.auth)
        self.assertEqual(201,a.status_code);self.assertEqual(payload['pickup_order'],a.get_json()['pickup_order'])
        payload['pickup_order']=[self.contacts[0]['id']]
        self.assertEqual(400,self.client.post('/api/appointments',json=payload,headers=self.auth).status_code)

if __name__=='__main__':unittest.main()
