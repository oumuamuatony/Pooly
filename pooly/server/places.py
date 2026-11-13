"""Server-side place/address search. Provider keys never reach the browser or APK."""
import hashlib
import json
import math
import os
import re
import ssl
import threading
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from .domain import PLACES, text


class ProviderError(Exception):
    pass


class PlaceSearch:
    def __init__(self):
        self.search_lock = threading.Lock()
        self.search_cache = {}

    def settings(self):
        path = Path(os.environ.get('POOLY_SEARCH_CONFIG', str(Path(__file__).resolve().parents[1] / 'data/private/search-provider.json')))
        try:
            saved = json.loads(path.read_text())
        except (OSError, ValueError):
            saved = {}
        return {name: os.environ.get(env) or saved.get(name, '') for name, env in [
            ('vworld_key', 'VWORLD_API_KEY'), ('vworld_domain', 'VWORLD_DOMAIN'), ('kakao_key', 'KAKAO_REST_API_KEY')]}

    @staticmethod
    def fetch(url):
        context = ssl.create_default_context(cafile='/etc/ssl/cert.pem' if Path('/etc/ssl/cert.pem').exists() else None)
        request = urllib.request.Request(url, headers={'User-Agent': 'Pooly/0.3', 'Accept': 'application/json'})
        try:
            with urllib.request.urlopen(request, timeout=6, context=context) as response:
                return json.load(response)
        except Exception:
            # Exceptions may contain a provider URL including its key. Never propagate that URL.
            raise ProviderError('provider-unavailable') from None

    def vworld(self, query, settings):
        def search(kind, category=None):
            params = {'service': 'search', 'request': 'search', 'version': '2.0', 'format': 'json',
                      'errorformat': 'json', 'crs': 'EPSG:4326', 'query': query, 'type': kind,
                      'size': 20, 'page': 1, 'key': settings['vworld_key']}
            if category:
                params['category'] = category
            if settings['vworld_domain']:
                params['domain'] = settings['vworld_domain']
            response = self.fetch('https://api.vworld.kr/req/search?' + urllib.parse.urlencode(params)).get('response', {})
            status = response.get('status')
            if status == 'NOT_FOUND':
                return []
            if status != 'OK':
                code = response.get('error', {}).get('code', '')
                raise ProviderError(code if code in {'INVALID_KEY', 'INCORRECT_KEY', 'UNAVAILABLE_KEY', 'OVER_REQUEST_LIMIT'} else 'provider-unavailable')
            rows = []
            for item in response.get('result', {}).get('items', []):
                try:
                    lon, lat = float(item['point']['x']), float(item['point']['y'])
                    if not math.isfinite(lat + lon) or not (-90 <= lat <= 90 and -180 <= lon <= 180):
                        continue
                    address = item.get('address') or {}
                    label = item.get('title') or address.get('bldnm') or address.get('road') or address.get('parcel')
                    if not label:
                        continue
                    rows.append({'id': 'vworld-' + str(item.get('id') or hashlib.sha256((label+str(lat)+str(lon)).encode()).hexdigest()[:16]),
                                 'name': label, 'address': address.get('road') or address.get('parcel', ''),
                                 'lat': lat, 'lon': lon, 'source': 'vworld', 'kind': 'place' if kind == 'PLACE' else 'address'})
                except (ValueError, TypeError, KeyError):
                    continue
            return rows

        address_type = 'PARCEL' if re.search(r'(동|리|읍|면)\s*\d', query) else 'ROAD'
        # Place names and addresses are independent searches; the UI receives a single selectable list.
        with ThreadPoolExecutor(max_workers=2) as executor:
            tasks = [executor.submit(search, 'PLACE'), executor.submit(search, 'ADDRESS', address_type)]
            rows, errors = [], []
            for task in tasks:
                try:
                    rows.extend(task.result())
                except ProviderError as error:
                    errors.append(str(error))
        if errors and not rows:
            raise ProviderError(errors[0])
        needle = query.replace(' ', '').casefold()
        def rank(row):
            title = row['name'].replace(' ', '').casefold()
            haystack = (row['name'] + ' ' + row['address']).casefold()
            matches = sum(token.casefold() in haystack for token in query.split())
            return (0 if title == needle else 1 if needle in title else 2, -matches, len(title))
        seen, unique = set(), []
        for row in sorted(rows, key=rank):
            identity = (row['id'], row['name'])
            if identity not in seen:
                seen.add(identity)
                unique.append(row)
        result = {'results': unique[:20], 'provider': 'vworld', 'map_pick_available': True}
        if errors:
            result['notice'] = '일부 검색 결과를 가져오지 못했어요. 표시된 장소에서 선택하거나 다시 검색해 주세요.'
        return result

    def search(self, query):
        query = text(query, 100)
        local = [dict(p, source='saved') for p in PLACES if not query or query.replace(' ', '') in (p['name'] + p['address']).replace(' ', '')]
        settings = self.settings()
        if not query:
            return {'results': local, 'provider': 'saved', 'map_pick_available': True}
        if settings['vworld_key']:
            try:
                # Search live. The supplied Geocoder endpoint is not used for persistent search-result caching.
                return self.vworld(query, settings)
            except ProviderError as error:
                notice = {'INVALID_KEY': '브이월드 개발키를 확인해 주세요.', 'INCORRECT_KEY': '브이월드 키의 허용 도메인 설정을 확인해 주세요.',
                          'UNAVAILABLE_KEY': '현재 브이월드 개발키를 사용할 수 없어요.', 'OVER_REQUEST_LIMIT': '브이월드 검색 요청 한도에 도달했어요.'}.get(str(error), '장소 검색에 연결하지 못했어요.')
                return {'results': local, 'provider': 'saved', 'map_pick_available': True, 'notice': notice + ' 저장된 장소를 표시해요.'}
        key = settings['kakao_key']
        if not key:
            return {'results': local, 'provider': 'saved', 'map_pick_available': True}
        with self.search_lock:
            if query in self.search_cache:
                return self.search_cache[query]
            params = urllib.parse.urlencode({'query': query, 'size': 10})
            request = urllib.request.Request('https://dapi.kakao.com/v2/local/search/keyword.json?' + params, headers={'Authorization': 'KakaoAK ' + key})
            try:
                context = ssl.create_default_context(cafile='/etc/ssl/cert.pem' if Path('/etc/ssl/cert.pem').exists() else None)
                with urllib.request.urlopen(request, timeout=5, context=context) as response:
                    source = json.load(response)
                results = [{'id': 'kakao-' + p['id'], 'name': p['place_name'], 'address': p.get('road_address_name') or p.get('address_name', ''),
                            'lat': float(p['y']), 'lon': float(p['x']), 'source': 'kakao'} for p in source['documents']]
                result = {'results': results, 'provider': 'kakao', 'map_pick_available': True}
                if len(self.search_cache) >= 100:
                    self.search_cache.clear()
                self.search_cache[query] = result
                return result
            except Exception:
                return {'results': local, 'provider': 'saved', 'map_pick_available': True, 'notice': '장소 검색에 연결하지 못했어요. 저장된 장소를 표시해요.'}
