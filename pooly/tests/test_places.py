import unittest
from unittest.mock import patch
from urllib.parse import parse_qs, urlsplit
from pooly.server.places import PlaceSearch, ProviderError


class PlaceSearchTests(unittest.TestCase):
    def setUp(self):
        self.provider = PlaceSearch()
        self.settings = patch.object(self.provider, 'settings', return_value={'vworld_key':'test-secret','vworld_domain':'http://localhost','kakao_key':''})
        self.settings.start()
        self.addCleanup(self.settings.stop)

    @staticmethod
    def item(id='id', title='성수역', lat='37.5445', lon='127.056'):
        return {'id':id,'title':title,'address':{'road':'서울특별시 성동구 아차산로 100'},'point':{'x':lon,'y':lat}}

    def test_live_place_address_merge_coordinates_and_no_key_in_response(self):
        def fetch(url):
            q=parse_qs(urlsplit(url).query)
            self.assertEqual(['EPSG:4326'],q['crs'])
            self.assertEqual(['test-secret'],q['key'])
            if q['type']==['PLACE']:
                items=[self.item(),self.item(),self.item('bad',lat='nan')]
            else:
                self.assertEqual(['ROAD'],q['category']);items=[self.item('address','서울특별시 성동구 아차산로 100')]
            return {'response':{'status':'OK','result':{'items':items}}}
        with patch.object(self.provider,'fetch',side_effect=fetch):
            result=self.provider.search('성수역')
        self.assertEqual('vworld',result['provider']);self.assertEqual(2,len(result['results']))
        self.assertEqual(37.5445,result['results'][0]['lat']);self.assertEqual(127.056,result['results'][0]['lon'])
        self.assertNotIn('test-secret',str(result))

    def test_full_address_ranks_matching_region_first(self):
        items=[self.item('wrong','경기도 구리시 아차산로 100'),self.item('right','서울특별시 성동구 아차산로 100')]
        items[0]['address']['road']='경기도 구리시 아차산로 100'
        with patch.object(self.provider,'fetch',return_value={'response':{'status':'OK','result':{'items':items}}}):
            result=self.provider.search('서울 성동구 아차산로 100')
        self.assertEqual('vworld-right',result['results'][0]['id'])

    def test_errors_are_explicit_fallback_without_provider_exception_or_secret(self):
        with patch.object(self.provider,'fetch',return_value={'response':{'status':'ERROR','error':{'code':'INCORRECT_KEY','text':'test-secret'}}}):
            result=self.provider.search('성수')
        self.assertEqual('saved',result['provider']);self.assertIn('도메인',result['notice']);self.assertNotIn('test-secret',str(result))

    def test_not_found_is_empty_and_partial_failure_keeps_successful_results(self):
        with patch.object(self.provider,'fetch',return_value={'response':{'status':'NOT_FOUND'}}):
            self.assertEqual([],self.provider.search('없는 장소')['results'])
        def fetch(url):
            if parse_qs(urlsplit(url).query)['type']==['ADDRESS']:raise ProviderError('provider-unavailable')
            return {'response':{'status':'OK','result':{'items':[self.item()]}}}
        with patch.object(self.provider,'fetch',side_effect=fetch):
            result=self.provider.search('성수역')
        self.assertEqual('vworld',result['provider']);self.assertEqual(1,len(result['results']));self.assertIn('일부',result['notice'])

    def test_blank_query_does_not_call_remote_and_parcel_category(self):
        with patch.object(self.provider,'fetch') as fetch:
            self.assertEqual('saved',self.provider.search('')['provider']);fetch.assert_not_called()
        def fetch(url):
            q=parse_qs(urlsplit(url).query)
            if q['type']==['ADDRESS']:self.assertEqual(['PARCEL'],q['category'])
            return {'response':{'status':'NOT_FOUND'}}
        with patch.object(self.provider,'fetch',side_effect=fetch):self.provider.search('성수동 289-36')


if __name__=='__main__':unittest.main()
