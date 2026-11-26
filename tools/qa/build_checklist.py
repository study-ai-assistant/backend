"""Собрать отчёт приёмки из сохранённых HTTP-ответов и Maven Surefire XML."""
import datetime
import json
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
QA = ROOT / 'docs/qa'
prefix = '2026-11-26'
errors = json.loads((QA / f'{prefix}-api-errors.json').read_text(encoding='utf-8'))
environment = json.loads((QA / f'{prefix}-environment.json').read_text(encoding='utf-8'))
measurements = QA / f'{prefix}-live/measurements.json'
live = json.loads(measurements.read_text(encoding='utf-8')) if measurements.exists() else []
suites = []
for path in sorted((ROOT / 'target/surefire-reports').glob('TEST-*.xml')):
    suite = ET.parse(path).getroot()
    suites.append({'name': suite.attrib['name'],
                   **{key: int(suite.attrib.get(key, 0)) for key in ['tests', 'failures', 'errors', 'skipped']},
                   'seconds': float(suite.attrib['time']),
                   'cases': [case.attrib['name'] for case in suite.findall('testcase')]})
if not suites:
    raise SystemExit('Сначала выполните ./mvnw verify: отчёты Surefire отсутствуют.')
totals = {key: sum(s[key] for s in suites) for key in ['tests', 'failures', 'errors', 'skipped']}
(QA / f'{prefix}-tests.json').write_text(json.dumps({'checked_at': datetime.datetime.now().astimezone().isoformat(),
                                                  'totals': totals, 'suites': suites},
                                                 ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
modes = ['SHORT_SUMMARY', 'DETAILED_SUMMARY', 'SIMPLE_EXPLANATION', 'SELF_CHECK_QUESTIONS', 'KEY_TERMS']
cases = [('short-text', 'JSON-текст', '/api/v1/text/process'),
         ('short-txt', 'TXT', '/api/v1/materials/process'),
         ('short-pdf', 'PDF', '/api/v1/materials/process'),
         ('long-txt', 'Длинный TXT, 4 части', '/api/v1/jobs/materials'),
         ('long-pdf', 'Длинный PDF, 6 частей', '/api/v1/jobs/materials')]
latest = {(r['name'], r['mode']): r for r in live}
rows = []
for case, label, endpoint in cases:
    cells = []
    for mode in modes:
        record = latest.get((case, mode))
        if record is None:
            cells.append('Ещё не выполнено')
        elif record['status'] == 'SUCCEEDED':
            cells.append(f"{record['http_status']}, {record['seconds']:.2f} с")
        else:
            cells.append(f"ОШИБКА: {record.get('error')}")
    rows.append(f'| {label} | ' + ' | '.join(cells) + ' |')
success = sum(r['status'] == 'SUCCEEDED' for r in latest.values())
failed_attempts = sum(r['status'] != 'SUCCEEDED' for r in live)
long_tokens = sum((r.get('prompt_tokens') or 0) + (r.get('completion_tokens') or 0)
                  for (case, _), r in latest.items() if case.startswith('long-'))
text = f'''# Проверка API перед записью

Дата: 26.11.2026. Задачи: [#59](https://github.com/study-ai-assistant/backend/issues/59)
и [#61](https://github.com/study-ai-assistant/backend/issues/61).

Проверена ветка `59_61`, созданная от `feature/56-demo-prompts`.
Основа: `{environment['baseline']}`. В неё уже входит `main`
`{environment['main']}`. Изменения сервера в этой работе не вносились:
добавлены проверки, средства повторного прогона и инструкции.

## Запуск с нуля на ноутбуке

- Отдельный чистый локальный клон, без `target`, `.env` и файлов IDEA из рабочей папки.
- Docker Engine {environment['docker_engine']}, Compose {environment['docker_compose']}.
- Образы скачивались; Maven cache Docker мог использоваться повторно.
- `docker compose up --build -d --wait --wait-timeout 180`: **{environment['compose_build_and_healthy_seconds']:.2f} секунды** до healthy.
- `/health` и список режимов доступны; запрос к Wi-Fi IPv4 ноутбука тоже вернул 200.
- Контейнер работает не от root, `/app/.env` отсутствует. Ключ передан окружением при запуске.
- Проверочный сервер использовал отдельный порт 18099; сервер IDEA на 8080 не останавливался.
- После проверки временный контейнер остановлен, локальный клон с тестовыми настройками удалён.

Это укладывается в десять минут на проверенном ноутбуке. Установка Docker,
авторизация GitHub и получение ключа в измерение не входят. Время на другой
машине с пустым Docker/Maven cache не измерено.
Окружение и SHA-256 материалов: [{prefix}-environment.json]({prefix}-environment.json).

## Автоматические проверки без ключа

`verify` через Maven из поставки IntelliJ IDEA: **{totals['tests']} тестов**, ошибок {totals['errors']},
падений {totals['failures']}, пропущено {totals['skipped']}.
Пропускается ручной Groq-тест; CI не расходует бесплатную квоту.
GitHub Actions для этой ветки ещё не запускался: push и PR не выполнялись.
Сводка с именами сценариев: [{prefix}-tests.json]({prefix}-tests.json).

Короткие JSON, TXT и PDF проверяются во всех пяти режимах. Длинные JSON, TXT
и PDF тоже проверяются во всех пяти режимах; файлы проходят настоящее извлечение,
а LLM заменён контролируемой заглушкой. Дополнительно проверены порядок частей,
FIFO, прогресс, объединение, отсутствие частичного результата при ошибке и лимиты.

## Живые запросы через Docker и Groq

Успешно **{success} из 25** сочетаний. Модель — `openai/gpt-oss-120b`.
Короткие маршруты возвращают 200; длинные — сначала 202, затем `SUCCEEDED`
при опросе статуса. Время длинного материала включает ожидание квоты и опрос раз
в три секунды. Паузы перед отправкой в таблицу не включены.

| Материал | Краткий конспект | Подробный конспект | Простое объяснение | Самопроверка | Термины |
|---|---|---|---|---|---|
{chr(10).join(rows)}

Неуспешных попыток за весь прогон: **{failed_attempts}**. Повторные попытки
не удаляют исходный сбой из measurements.json; таблица показывает последний результат
каждого сочетания.

Ответы сохранены без ручного исправления в [{prefix}-live/]({prefix}-live/),
время, состояния и ошибки — в [measurements.json]({prefix}-live/measurements.json).
Статус 200 / SUCCEEDED подтверждает прохождение API, а не безошибочность содержания LLM.
Замечания к содержанию перечислены в [QUALITY_NOTES.md](QUALITY_NOTES.md).

Измеренный `usage` фоновых вызовов: **{long_tokens:,} токенов** на завершённые
длинные задания. Короткий ответ API не содержит `usage`, поэтому точный общий
расход этого прогона по клиентским ответам вычислить нельзя. При сбое нулевой
usage также может означать отсутствие отчёта провайдера, а не бесплатный вызов. Этот прогон сам
расходует квоту аккаунта; перед записью проверьте остаток в Groq.

## Ошибки контракта

Через поднятый Docker-сервер прошли **{sum(r['passed'] for r in errors['checks'])} из {len(errors['checks'])}** проверок без генерации.
Сохранены HTTP-статус, Content-Type и полное тело каждой ошибки:
[{prefix}-api-errors.json]({prefix}-api-errors.json).

| Сценарий | Ожидается | Как проверено |
|---|---|---|
| Пустые данные, неверный режим/тип/поля, null, сломанный JSON | 400 | Реальный HTTP, оба JSON-маршрута |
| Нет file/mode, неверное имя поля, несколько файлов | 400 | Реальный multipart, оба маршрута файлов |
| Короткий материал в фоновом маршруте | 400 | Реальный HTTP |
| Символы, токены, файл больше 10 МиБ | 413 | Реальный HTTP; дополнительно Java-тесты JSON/TXT/PDF |
| PDF больше 50 страниц, лимит частей | 413 | PdfProcessingTests / TextSplitterTests |
| Content-Type, DOCX, подмена расширения/сигнатуры | 415 | Реальный HTTP |
| PDF-скан без текста, пароль, повреждение | 422 | Реальный PDF worker, оба маршрута файлов |
| Ошибка/429/таймаут Groq | 502 | GroqLlmClientTests с локальным HTTP-имитатором; TextProcessingTests / MaterialProcessingTests |
| Ошибка фоновой части или объединения | 200 + FAILED, error.code/part, result=null | LongMaterialHttpTests с заглушкой LLM |
| Очередь заполнена | 503 | JobCapacityHttpTests: реальный HTTP, capacity=1, управляемое ожидание LLM |
| Все PDF worker заняты / worker не запускается | 503 | PdfTextExtractorTests с управляемыми процессами и проверкой очистки |
| Превышен таймаут PDF | 504 | PdfProcessingTests: настоящий worker, таймаут 1 мс; затем успешный запрос |
| Неизвестное задание | 404 | Реальный HTTP |
| Истёк срок хранения | 404 | JobCapacityHttpTests: реальный HTTP, TTL 500 мс, затем приём нового задания |

Ошибки провайдера не провоцировались намеренным исчерпанием реальной квоты.
Их воспроизводимые тесты используют локальный имитатор, не Groq.

## Повторить проверку

Из корня проекта, с запущенным сервером:

```bash
./mvnw verify
python3 tools/qa/check_api.py --base-url http://localhost:8080 --output target/qa-errors.json
```

Полный прогон с расходом квоты Groq, только при достаточном остатке:

```bash
python3 tools/demo/run_benchmark.py --base-url http://localhost:8080 --suite acceptance --output target/qa-live
python3 tools/qa/check_answers.py target/qa-live
```

Python 3 использует только стандартную библиотеку. В Windows — `python` вместо
`python3`, `.\\mvnw.cmd` вместо `./mvnw`. Для нового прогона выбирайте новую
output-папку: существующие успешные сочетания скрипт пропускает.

`tools/qa/build_checklist.py` пересобирает этот датированный отчёт из сохранённых
данных 26 ноября и текущих Surefire XML. Не используйте его для другой даты без
новых файлов и изменения префикса.

## Что ещё требует человека

- Проверка настоящего телефона через Wi-Fi и всех сцен приложения: по просьбе пользователя проверялся только ноутбук.
- Независимое прохождение инструкции вторым участником и запись его результата.
- Публикация подготовленных карточек замечаний с меткой bug и полем Priority: Medium; см. [BUG_DRAFTS.md](BUG_DRAFTS.md).
- Публикация локальных страниц wiki: ранее отложена пользователем, автоматически не выполнялась.

Коммиты, push и изменение статусов доски в этой работе не выполнялись.
Инструкция для записи и форма подтверждения: [RECORDING.md](../demo/RECORDING.md).
'''
(QA / 'API_CHECKLIST.md').write_text(text, encoding='utf-8')
print(f'Отчёт обновлён: {success}/25 генераций; {totals}')
