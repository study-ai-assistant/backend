"""Проверяет формальные свойства ответов. Смысловую проверку не заменяет."""
import json
import re
from pathlib import Path
root = Path(__file__).resolve().parents[2] / 'docs/demo'
checks = []
for folder in [root/'results/long',root/'results/demo',root/'results/recheck',root/'examples']:
    if not folder.exists(): continue
    for path in folder.glob('*.txt'):
        text = path.read_text()
        record = {'file': str(path.relative_to(root)), 'empty': not text.strip(),
                  'markdown_markers': bool(re.search(r'(?m)^#{1,6}\s|\*\*|```',text))}
        if 'SELF_CHECK_QUESTIONS' in path.name:
            questions = [x.strip() for x in text.splitlines() if x.strip()]
            normalized = [re.sub(r'\W','',x.casefold()) for x in questions]
            record.update(question_count=len(questions),
                          exact_duplicates=len(normalized)-len(set(normalized)),
                          within_ten=len(questions)<=10)
        if 'KEY_TERMS' in path.name:
            terms=[]
            for line in text.splitlines():
                pair = re.split(r'\s+[—–-]\s+',line.strip(),maxsplit=1)
                if len(pair)==2:
                    terms.append(re.sub(r'\W','',pair[0].casefold()))
            record.update(term_count=len(terms),exact_duplicate_terms=len(terms)-len(set(terms)))
        checks.append(record)
(root/'results/quality-checks.json').write_text(json.dumps(checks,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(checks,ensure_ascii=False,indent=2))
