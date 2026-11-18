"""Ручной прогон через локальный backend. Сервер сам обращается к Groq; ключ здесь не читается."""
import argparse
import datetime
import json
import time
import urllib.request
import urllib.error
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--base-url', default='http://localhost:18097')
parser.add_argument('--suite', choices=['long', 'demo'], default='long')
parser.add_argument('--case', help='Повторить только указанный name из measurements.json')
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--mode', choices=['SHORT_SUMMARY','DETAILED_SUMMARY','SIMPLE_EXPLANATION',
                                      'SELF_CHECK_QUESTIONS','KEY_TERMS'])
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]
modes = ['SHORT_SUMMARY', 'DETAILED_SUMMARY', 'SIMPLE_EXPLANATION', 'SELF_CHECK_QUESTIONS', 'KEY_TERMS']
if args.mode:
    modes = [args.mode]
args.output.mkdir(parents=True, exist_ok=True)
log = args.output / 'measurements.json'
records = json.loads(log.read_text()) if log.exists() else []

def call(path, data=None, content_type='application/json'):
    request = urllib.request.Request(args.base_url+path, data=data,
                                     headers={'Content-Type': content_type}, method='POST' if data is not None else 'GET')
    try:
        with urllib.request.urlopen(request, timeout=180) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, json.load(error)

def send_file(path, mode, endpoint):
    boundary = 'study-demo-boundary'
    body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{path.name}"\r\n'
            'Content-Type: application/octet-stream\r\n\r\n').encode()+path.read_bytes()
    body += (f'\r\n--{boundary}\r\nContent-Disposition: form-data; name="mode"\r\n\r\n'
             f'{mode}\r\n--{boundary}--\r\n').encode()
    return call(endpoint, body, 'multipart/form-data; boundary='+boundary)

if args.suite == 'long':
    cases = [('2-parts', 'benchmark-2-parts.txt', 'text', True),
             ('4-parts', 'long-lecture.txt', 'text', True),
             ('6-parts', 'long-lecture.pdf', 'file', True)]
else:
    cases = [('short-text', 'short-text.txt', 'text', False),
             ('short-txt', 'short-file.txt', 'file', False),
             ('short-pdf', 'study-notes.pdf', 'file', False)]
short_calls = 0
for name, filename, kind, background in cases:
    if args.case and name != args.case:
        continue
    for mode in modes:
        if any(r['name'] == name and r['mode'] == mode and r.get('status') == 'SUCCEEDED' for r in records):
            continue
        if not background and short_calls and short_calls % 3 == 0:
            time.sleep(61)
        path = root/'docs/demo'/filename
        start = time.monotonic()
        if kind == 'file':
            code, body = send_file(path, mode, '/api/v1/jobs/materials' if background else '/api/v1/materials/process')
        else:
            data = json.dumps({'text': path.read_text(), 'mode': mode}).encode()
            code, body = call('/api/v1/jobs/text' if background else '/api/v1/text/process', data)
        transitions = []
        if code == 202:
            job_id = body['id']
            while True:
                _, body = call('/api/v1/jobs/'+job_id)
                state = [body['status'],body['stage'],body['completedParts'],body['totalParts']]
                if not transitions or transitions[-1] != state:
                    transitions.append(state)
                if body['status'] in ['SUCCEEDED', 'FAILED']:
                    break
                if time.monotonic()-start > 1200:
                    raise RuntimeError('Job exceeded 20 minutes')
                time.sleep(3)
        success = code in [200,202] and body.get('status', 'SUCCEEDED') != 'FAILED'
        record = {'recorded_at': datetime.datetime.now().astimezone().isoformat(), 'name': name, 'file': filename, 'mode': mode, 'http_status': code,
                  'status': 'SUCCEEDED' if success else 'FAILED', 'seconds': round(time.monotonic()-start,2),
                  'parts': body.get('totalParts',1), 'prompt_tokens': body.get('promptTokens'),
                  'completion_tokens': body.get('completionTokens'), 'transitions':transitions,
                  'error':body.get('error',body.get('detail'))}
        answer_file = args.output/(name+'-'+mode+'.txt')
        if success:
            answer_file.write_text(body['result']+'\n')
        records.append(record)
        log.write_text(json.dumps(records,ensure_ascii=False,indent=2)+'\n')
        print(json.dumps(record,ensure_ascii=False),flush=True)
        if not background:
            short_calls += 1
        if not success:
            raise SystemExit('Stopped after failure; inspect the saved error before retrying.')
