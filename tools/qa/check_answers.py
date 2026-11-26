"""Формальные проверки сохранённых ответов. Не заменяют проверку смысла человеком."""
import argparse
import json
import re
import unicodedata
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('directory', type=Path)
args = parser.parse_args()
records = json.loads((args.directory / 'measurements.json').read_text(encoding='utf-8'))
latest = {(r['name'], r['mode']): r for r in records}
checks = []
for (name, mode), record in latest.items():
    if record['status'] != 'SUCCEEDED':
        continue
    path = args.directory / f'{name}-{mode}.txt'
    text = path.read_text(encoding='utf-8')
    result = {'file': path.name, 'nonempty': bool(text.strip()),
              'no_service_markers': not any(marker in text for marker in
                                             ['<|channel|>', '<|im_start|>', '<think>', '</think>'])}
    if mode == 'SELF_CHECK_QUESTIONS':
        questions = []
        for fragment in text.split('?')[:-1]:
            # Подходит для ответов с нумерацией и без неё. Смысл не анализируется.
            question = re.sub(r'^\s*\d+[.)]\s*', '', fragment.strip())
            questions.append(' '.join(unicodedata.normalize('NFKC', question).casefold().split()))
        result['question_count'] = len(questions)
        result['between_one_and_ten'] = 1 <= len(questions) <= 10
        result['no_exact_duplicates'] = len(set(questions)) == len(questions)
    result['passed'] = all(value for value in result.values() if isinstance(value, bool))
    checks.append(result)
if not checks:
    raise SystemExit('Нет успешных ответов для проверки.')
output = args.directory / 'answer-checks.json'
output.write_text(json.dumps({'note': 'Только формальные проверки, не оценка точности LLM.',
                              'checks': checks}, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(f'{sum(c["passed"] for c in checks)}/{len(checks)} формальных проверок пройдено')
raise SystemExit(0 if all(c['passed'] for c in checks) else 1)
