"""Проверка валидации API без генерации: Python 3, только стандартная библиотека.

Отправляет только заведомо невалидные материалы, поэтому не расходует квоту Groq.
Работает с уже запущенным сервером; реальные ответы сохраняет в JSON.
"""
import argparse
import datetime
import json
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--base-url', default='http://localhost:8080')
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
records = []


def request(name, path, expected, data=None, content_type=None):
    start = time.monotonic()
    headers = {'Content-Type': content_type} if content_type else {}
    req = urllib.request.Request(args.base_url.rstrip('/') + path, data=data, headers=headers)
    try:
        response = urllib.request.urlopen(req, timeout=30)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        status = response.status
        raw = response.read().decode('utf-8')
        mime = response.headers.get_content_type()
    try:
        body = json.loads(raw)
    except ValueError:
        body = {'unparsed_body': raw[:500]}
    ok = status == expected
    if expected >= 400:
        ok = ok and mime == 'application/problem+json' and isinstance(body, dict)
        ok = ok and body.get('status') == expected and body.get('instance') == path
        ok = ok and all(body.get(key) for key in ('type', 'title', 'detail'))
    else:
        ok = ok and mime == 'application/json'
        if path == '/health':
            ok = ok and body == {'status': 'ok'}
        if path == '/api/v1/modes':
            expected_modes = ['SHORT_SUMMARY', 'DETAILED_SUMMARY', 'SIMPLE_EXPLANATION',
                              'SELF_CHECK_QUESTIONS', 'KEY_TERMS']
            ok = ok and body == expected_modes
    records.append({'name': name, 'path': path, 'expected': expected, 'actual': status,
                    'passed': ok, 'seconds': round(time.monotonic() - start, 3),
                    'content_type': mime, 'response': body})
    print(('PASS' if ok else 'FAIL') + ' ' + name + ': ' + str(status), flush=True)


def text(name, path, value, expected=400):
    request(name, path, expected, json.dumps(value, ensure_ascii=False).encode(), 'application/json')


def upload(name, path, files, expected, mode='SHORT_SUMMARY', field='file'):
    boundary = 'study-api-check-5961'
    body = bytearray()
    for filename, data in files:
        body += (f'--{boundary}\r\nContent-Disposition: form-data; name="{field}"; '
                 f'filename="{filename}"\r\nContent-Type: application/octet-stream\r\n\r\n').encode()
        body += data + b'\r\n'
    if mode is not None:
        body += (f'--{boundary}\r\nContent-Disposition: form-data; name="mode"\r\n\r\n'
                 f'{mode}\r\n').encode()
    body += f'--{boundary}--\r\n'.encode()
    request(name, path, expected, bytes(body), 'multipart/form-data; boundary=' + boundary)


request('health', '/health', 200)
request('modes', '/api/v1/modes', 200)
request('unknown-job', '/api/v1/jobs/not-found-5961', 404)
for path in ['/api/v1/text/process', '/api/v1/jobs/text']:
    prefix = path.rsplit('/', 1)[-1]
    for label, value in [('blank', {'text': ' \n', 'mode': 'SHORT_SUMMARY'}),
                         ('unknown-mode', {'text': 'Текст', 'mode': 'UNKNOWN'}),
                         ('missing-text', {'mode': 'SHORT_SUMMARY'}),
                         ('missing-mode', {'text': 'Текст'}),
                         ('number-text', {'text': 123, 'mode': 'SHORT_SUMMARY'}),
                         ('number-mode', {'text': 'Текст', 'mode': 0}),
                         ('null-body', None),
                         ('extra-field', {'text': 'Текст', 'mode': 'SHORT_SUMMARY', 'extra': True})]:
        text(prefix + '-' + label, path, value)
    request(prefix + '-content-type', path, 415, b'text', 'text/plain')
    request(prefix + '-broken-json', path, 400, b'{"text":', 'application/json')
    text(prefix + '-character-limit', path,
         {'text': 'a' * (24001 if path.endswith('/text') else 10001), 'mode': 'SHORT_SUMMARY'}, 413)
text('background-token-limit', '/api/v1/jobs/text',
     {'text': 'a ' * 11000, 'mode': 'SHORT_SUMMARY'}, 413)
text('short-needs-sync', '/api/v1/jobs/text', {'text': 'Короткий текст', 'mode': 'SHORT_SUMMARY'})
for path in ['/api/v1/materials/process', '/api/v1/jobs/materials']:
    prefix = 'jobs-file' if '/jobs/' in path else 'sync-file'
    for label, files, expected in [
            ('empty', [('empty.txt', b'')], 400),
            ('blank', [('blank.txt', b' \n\t')], 400),
            ('two-files', [('one.txt', b'one'), ('two.txt', b'two')], 400),
            ('unsupported', [('notes.docx', b'PK\x03\x04')], 415),
            ('fake-pdf', [('notes.pdf', b'ordinary text')], 415),
            ('renamed-pdf', [('notes.txt', (ROOT/'src/test/resources/pdf/plain.pdf').read_bytes())], 415),
            ('scan', [('scan.pdf', (ROOT/'src/test/resources/pdf/scan.pdf').read_bytes())], 422),
            ('password', [('password.pdf', (ROOT/'src/test/resources/pdf/password.pdf').read_bytes())], 422),
            ('corrupted', [('corrupted.pdf', (ROOT/'src/test/resources/pdf/corrupted.pdf').read_bytes())], 422),
            ('file-size', [('large.txt', b'a' * (10 * 1024 * 1024 + 1))], 413),
            ('character-limit', [('long.txt', b'a' * (24001 if '/jobs/' in path else 10001))], 413)]:
        upload(prefix + '-' + label, path, files, expected)
    upload(prefix + '-missing-file', path, [], 400)
    upload(prefix + '-wrong-field', path, [('a.txt', b'text')], 400, field='files')
    upload(prefix + '-missing-mode', path, [('a.txt', b'text')], 400, mode=None)
    upload(prefix + '-unknown-mode', path, [('a.txt', b'text')], 400, mode='UNKNOWN')
request('health-after-errors', '/health', 200)
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps({'checked_at': datetime.datetime.now().astimezone().isoformat(),
                                  'base_url': args.base_url, 'checks': records},
                                 ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
raise SystemExit(0 if all(record['passed'] for record in records) else 1)
