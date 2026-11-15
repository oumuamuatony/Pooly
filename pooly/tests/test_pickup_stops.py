import unittest
from pooly.tests import test_flask as fixtures


class PickupStopTests(unittest.TestCase):
    setUp = fixtures.FlaskWorkflowTests.setUp
    tearDown = fixtures.FlaskWorkflowTests.tearDown
    rider = fixtures.FlaskWorkflowTests.rider

    def start(self):
        self.rider_auth, _ = self.rider(0)
        self.path = '/api/appointments/' + self.a['id']
        self.assertEqual(200, self.client.post(self.path+'/actions', json={'action':'depart'}, headers=self.auth).status_code)

    def stop(self, lat=37.5445, lon=127.0560, dwell=40, hour=18, kind='board', auth=None):
        return self.client.post(self.path+'/stops', json={'lat':lat,'lon':lon,'dwell_s':dwell,'hour':hour,'kind':kind}, headers=auth or self.auth)

    def recommend(self, query='lat=37.5445&lon=127.0560&hour=18'):
        return self.client.get('/api/pickup-recommendations?'+query, headers=self.auth)

    def test_stop_needs_driver_of_moving_trip(self):
        self.assertEqual(409, self.client.post('/api/appointments/'+self.a['id']+'/stops', json={'lat':37.5,'lon':127.0,'dwell_s':30,'hour':9,'kind':'board'}, headers=self.auth).status_code)
        self.start()
        self.assertEqual(403, self.stop(auth=self.rider_auth).status_code)
        self.assertEqual(200, self.stop().status_code)

    def test_invalid_and_long_stops_are_rejected(self):
        self.start()
        for bad in [dict(dwell=601), dict(dwell=0), dict(dwell=12.5), dict(hour=24), dict(kind='park'), dict(lat=91), dict(lon=float('nan'))]:
            args = dict(lat=37.5445, lon=127.056, dwell=40, hour=18, kind='board'); args.update(bad)
            self.assertEqual(400, self.stop(**args).status_code, bad)

    def test_only_cells_with_enough_boardings_are_recommended(self):
        self.start()
        for i in range(4):
            self.assertEqual(200, self.stop(lat=37.54452+i*0.00001).status_code)
        self.assertEqual([], self.recommend().get_json()['spots'])
        self.assertEqual(200, self.stop().status_code)
        spots = self.recommend().get_json()['spots']
        self.assertEqual(1, len(spots))
        spot = spots[0]
        # Stored as a coarse ~50 m cell, never the exact point.
        self.assertEqual((37.5445, 127.056), (spot['lat'], spot['lon']))
        self.assertEqual((5, 5, 40, 1), (spot['count'], spot['hour_count'], spot['avg_dwell_s'], spot['rank']))
        # Alighting records do not count as pickup spots.
        for _ in range(5):
            self.stop(lat=37.546, kind='alight')
        self.assertEqual(1, len(self.recommend().get_json()['spots']))

    def test_frequent_long_nearby_stops_rank_first(self):
        self.start()
        for _ in range(6):
            self.stop(lat=37.5450, lon=127.0560, dwell=30)    # close, short stop
            self.stop(lat=37.5480, lon=127.0560, dwell=30)    # farther away
            self.stop(lat=37.5440, lon=127.0570, dwell=500)   # close and safe to wait long
            self.stop(lat=37.5900, lon=127.0560, dwell=20)    # outside 1.5 km
        spots = self.recommend('lat=37.5450&lon=127.0560').get_json()['spots']
        self.assertEqual([(37.544, 127.057), (37.545, 127.056), (37.548, 127.056)], [(s['lat'], s['lon']) for s in spots])
        self.assertEqual(400, self.recommend('lat=abc&lon=127').status_code)
        self.assertEqual(400, self.recommend('lat=37.5&lon=127&hour=25').status_code)
