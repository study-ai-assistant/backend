# API backend

Актуальная инструкция с curl и правилами mobile:
[docs/api/README.md](https://github.com/study-ai-assistant/backend/blob/main/docs/api/README.md).
Формальная схема: [openapi.yaml](https://github.com/study-ai-assistant/backend/blob/main/docs/api/openapi.yaml).

| Запрос | Действие |
|---|---|
| GET /health | Проверка связи без ключа LLM |
| GET /actuator/health | Техническая проверка |
| GET /api/v1/modes | Пять режимов без ключа |
| POST /api/v1/text/process | JSON с text и mode |
| POST /api/v1/materials/process | multipart с одним file и mode |

Все пять режимов доступны для JSON и TXT. TXT поддерживает UTF-8 (с BOM и без)
и Windows-1251; лимит файла 10 МиБ, запроса 30 МиБ, текста 10000 Unicode-кодовых точек.
Пустые данные / неверный режим — 400, превышение лимита — 413,
другое расширение — 415, ошибка или таймаут LLM — 502.
PDF/DOCX/OCR не реализованы. Файлы не сохраняются в постоянном хранилище.

```bash
curl -i --max-time 90 http://localhost:8080/api/v1/materials/process \
  -F 'file=@lecture.txt;type=text/plain' \
  -F 'mode=DETAILED_SUMMARY'
```

Не задавайте общий Content-Type вручную: multipart boundary формирует клиент.
Результат: JSON с mode и result. Ключ провайдера мобильному приложению не передаётся.
