import copy
import time
import unittest
from pooly.tests import test_flask as fixtures


class AutoPickupTests(unittest.TestCase):
    setUp = fixtures.FlaskWorkflowTests.setUp
    tearDown = fixtures.FlaskWorkflowTests.tearDown
    rider = fixtures.FlaskWorkflowTests.rider

    def prepare(self):
        self.rider_auth, _ = self.rider(0)
        self.other_auth, _ = self.rider(1)
        self.path = '/api/appointments/' + self.a['id']
        self.assertEqual(200, self.client.post(self.path+'/actions', json={'action':'depart'}, headers=self.auth).status_code)

    def event(self, index=0, seat='ROW_1_RIGHT'):
        now = time.time()*1000
        return {'member_id':self.contacts[index]['id'], 'observation_id':f'event-{index}-{seat}', 'evidence':{
            'parked':True, 'position':{'name':'SDK location','lat':37.5445,'lon':127.056,'updatedAt':now},
            'observed_at':now,'occupied_at':now-4000,'seat':seat,'driver_seat':'ROW_1_LEFT','vehicle_key':'qa-vehicle'}}

    def act(self, action, event, auth=None):
        return self.client.post(self.path+'/actions',json={**event,'action':action},headers=auth or self.auth)

    def test_auto_two_pickups_without_manual_ready_or_door(self):
        self.prepare();e=self.event()
        self.assertEqual(200,self.act('auto_arrive',e).status_code)
        self.assertEqual(409,self.client.post(self.path+'/actions',json={'action':'board'},headers=self.auth).status_code)
        first=self.act('auto_board',e);self.assertEqual(200,first.status_code)
        a=first.get_json();self.assertEqual(1,a['trip']['index']);self.assertEqual('ROW_1_RIGHT',a['members'][0]['seat_binding']['seat'])
        self.assertEqual('waiting',a['members'][0]['ready'])
        # Replayed receipt cannot board the next person.
        replay=self.act('auto_board',e);self.assertEqual(200,replay.status_code);self.assertEqual(1,replay.get_json()['trip']['index'])
        e2=self.event(1,'ROW_2_RIGHT');self.assertEqual(200,self.act('auto_arrive',e2).status_code)
        self.assertEqual(409,self.act('auto_board',self.event(1)).status_code)
        final=self.act('auto_board',e2);self.assertEqual(200,final.status_code);self.assertEqual('destination',final.get_json()['trip']['phase'])

    def alight_event(self, index=0, seat='ROW_1_RIGHT'):
        now = time.time()*1000
        return {'member_id':self.contacts[index]['id'], 'observation_id':f'alight-{index}-{seat}', 'evidence':{
            'parked':True,'observed_at':now,'vacated_at':now-2000,'seat':seat,'vehicle_key':'qa-vehicle'}}

    def test_auto_alight_after_seat_vacated(self):
        self.prepare();e=self.event()
        self.assertEqual(200,self.act('auto_board',e).status_code)
        # Only a passenger whose boarding was bound to a seat can alight.
        self.assertEqual(409,self.act('auto_alight',self.alight_event(1,'ROW_2_RIGHT')).status_code)
        for mode in ['seat','vehicle','moving','old','stale']:
            bad=self.alight_event()
            if mode=='seat':bad['evidence']['seat']='ROW_2_RIGHT'
            if mode=='vehicle':bad['evidence']['vehicle_key']='other-vehicle'
            if mode=='moving':bad['evidence']['parked']=False
            if mode=='old':bad['evidence']['vacated_at']=e['evidence']['occupied_at']-1
            if mode=='stale':bad['evidence']['observed_at']-=20000
            self.assertEqual(409,self.act('auto_alight',bad).status_code,mode)
        self.assertEqual(403,self.act('auto_alight',self.alight_event(),self.rider_auth).status_code)
        done=self.act('auto_alight',self.alight_event())
        self.assertEqual(200,done.status_code)
        member=done.get_json()['members'][0]
        self.assertTrue(member['alighted_at']);self.assertEqual('ROW_1_RIGHT',member['alight']['seat'])
        self.assertEqual([self.contacts[0]['id']],done.get_json()['trip']['alighted'])
        # A replayed receipt is accepted once; a different signal for the same person is rejected.
        self.assertEqual(200,self.act('auto_alight',self.alight_event()).status_code)
        again=self.alight_event();again['observation_id']='alight-again'
        self.assertEqual(409,self.act('auto_alight',again).status_code)
        notices=self.client.get('/api/state',headers=self.rider_auth).get_json()['notifications']
        self.assertTrue(any(n['title']=='하차 완료' for n in notices))
        # The vacated seat can be bound to the next passenger.
        self.assertEqual(200,self.act('auto_board',self.event(1)).status_code)

    def test_stale_far_and_wrong_member_rejected(self):
        self.prepare()
        for mode in ['stale','far','member']:
            e=self.event()
            if mode=='stale':e['evidence']['position']['updatedAt']-=20000
            if mode=='far':e['evidence']['position']['lat']=37.56
            if mode=='member':e['member_id']=self.contacts[1]['id']
            self.assertEqual(409,self.act('auto_arrive',e).status_code,mode)

    def test_stability_driver_seat_and_permission_guards(self):
        self.prepare();e=self.event();self.act('auto_arrive',e)
        bad=copy.deepcopy(e);bad['evidence']['occupied_at']=bad['evidence']['observed_at']+1000
        self.assertEqual(409,self.act('auto_board',bad).status_code)
        bad=copy.deepcopy(e);bad['evidence']['seat']='ROW_1_LEFT'
        self.assertEqual(400,self.act('auto_board',bad).status_code)
        self.assertEqual(403,self.act('auto_board',e,self.rider_auth).status_code)
        self.assertIsNone(self.client.get('/api/state',headers=self.auth).get_json()['appointments'][0]['members'][0].get('boarded_at'))

    def test_immediate_boarding_while_moving_without_arrive_or_two_minute_limit(self):
        self.prepare();e=self.event()
        e['evidence']['parked']=False
        e['evidence']['occupied_at']=e['evidence']['observed_at']
        result=self.act('auto_board',e)
        self.assertEqual(200,result.status_code)
        self.assertEqual('nearby_occupancy',result.get_json()['members'][0]['seat_binding']['method'])
        e=self.event(1,'ROW_2_RIGHT')
        e['evidence'].pop('parked')
        e['evidence']['occupied_at']=e['evidence']['observed_at']-300000
        self.assertEqual(200,self.act('auto_board',e).status_code)

    def test_demo_server_ignores_location_but_client_cannot_enable_bypass(self):
        self.prepare();e=self.event()
        e['evidence'].pop('position');e['evidence']['skip_proximity']=True
        self.assertNotEqual(200,self.act('auto_board',e).status_code)
        demo=fixtures.create_app({**self.config,'PICKUP_REQUIRE_PROXIMITY':False}).test_client()
        self.assertFalse(demo.get('/api/state',headers=self.auth).get_json()['auto_pickup_proximity_required'])
        result=demo.post(self.path+'/actions',json={**e,'action':'auto_board'},headers=self.auth)
        self.assertEqual(200,result.status_code)
        self.assertEqual('demo_occupancy',result.get_json()['members'][0]['seat_binding']['method'])
        self.assertEqual(403,demo.post(self.path+'/actions',json={**self.event(1,'ROW_2_RIGHT'),'action':'auto_board'},headers=self.other_auth).status_code)

    def test_small_device_clock_lead_is_accepted_but_large_lead_rejected(self):
        self.prepare()
        e=self.event()
        for key in ['observed_at','occupied_at']:e['evidence'][key]+=2000
        e['evidence']['position']['updatedAt']+=2000
        self.assertEqual(200,self.act('auto_arrive',e).status_code)
        bad=copy.deepcopy(e);bad['evidence']['observed_at']+=20000
        self.assertEqual(409,self.act('auto_board',bad).status_code)
        self.assertEqual(200,self.act('auto_board',e).status_code)

    def test_user_owned_profile_and_vehicle_scoped_memory(self):
        self.prepare();settings={'enabled':True,'cold_heat':2,'hot_ventilation':1}
        saved=self.client.post('/api/seat-profile',json=settings,headers=self.rider_auth)
        self.assertEqual(200,saved.status_code)
        self.assertFalse(self.client.get('/api/seat-profile',headers=self.other_auth).get_json()['enabled'])
        request={'member_id':self.contacts[0]['id']}
        self.assertEqual(409,self.client.post(self.path+'/seat-personalization',json=request,headers=self.auth).status_code)
        e=self.event();self.act('auto_arrive',e);self.act('auto_board',e)
        applied=self.client.post(self.path+'/seat-personalization',json=request,headers=self.auth)
        self.assertEqual(2,applied.get_json()['profile']['cold_heat'])
        self.assertEqual(403,self.client.post(self.path+'/seat-personalization',json=request,headers=self.other_auth).status_code)
        settings['vehicle_memory']={'vehicle_key':'another-car','slot':1}
        self.assertEqual(403,self.client.post('/api/seat-profile',json=settings,headers=self.rider_auth).status_code)
        settings['vehicle_memory']['vehicle_key']='qa-vehicle'
        self.assertEqual(200,self.client.post('/api/seat-profile',json=settings,headers=self.rider_auth).status_code)
        self.assertEqual({'qa-vehicle':1},self.client.get('/api/seat-profile',headers=self.rider_auth).get_json()['vehicle_memories'])
        settings['cold_heat']=8
        self.assertEqual(400,self.client.post('/api/seat-profile',json=settings,headers=self.rider_auth).status_code)

if __name__=='__main__':unittest.main()
