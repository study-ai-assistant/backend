# Backend учебного помощника

Скелет сервера: Java 21, Spring Boot 4.1.1, Maven. База данных пока не нужна.

## IntelliJ IDEA

Откройте существующий проект `backend`, загрузите `pom.xml` как Maven project
(правой кнопкой → Add as Maven Project) и выберите Project SDK 21.
Запустите `BackendApplication` или конфигурацию `Backend`.
Ключи задаются в Run → Edit Configurations → Environment variables.
`.env.example` — только пример; файл `.env` автоматически не читается.
Для запуска скелета ключ не требуется.

## Команды

```bash
./mvnw verify
./mvnw spring-boot:run
# либо после сборки:
java -jar target/backend-0.0.1-SNAPSHOT.jar
```

Нужны JDK 21 и доступ к Maven Central при первой сборке. Windows: `mvnw.cmd`.
Порт по умолчанию 8080, переопределяется переменной `APP_PORT`.

## API

- `GET /actuator/health` — состояние сервера.
- `GET /api/v1/modes` — пять доступных идентификаторов режимов.
- `POST /api/v1/materials/process` — multipart: повторяющееся поле `files`, поле `mode`.

```bash
curl http://localhost:8080/api/v1/modes
curl -F 'files=@lecture.txt' -F 'mode=SHORT_SUMMARY' http://localhost:8080/api/v1/materials/process
```

Режимы: `SHORT_SUMMARY` (краткий конспект), `DETAILED_SUMMARY` (подробный),
`SIMPLE_EXPLANATION` (простыми словами), `SELF_CHECK_QUESTIONS` (самопроверка),
`KEY_TERMS` (термины).

Обработка пока возвращает `501 Not Implemented`, пустой файл — `400`.
Ограничения: 10 MB на файл, 30 MB на запрос; превышение — `413`.
Ошибки API имеют формат Problem Details. Контракт: `docs/api/openapi.yaml`.

## Структура и следующие шаги

- `material` — контроллер, сервис, режимы и модель результата.
- `extraction/TextExtractor` — интерфейс будущих парсеров TXT, DOCX, PDF.
- `llm/LlmClient` — интерфейс будущего клиента внешней модели.

Парсеры, OCR, реальный LLM, авторизация, хранение и история пока не реализованы.
До реализации вызова LLM нужно выбрать провайдера, добавить таймауты,
обработку ошибок провайдера и лимит извлечённого текста. Форматы файлов нужно
проверять по содержимому; для DOCX учитывать распакованный размер.
`LLM_API_KEY`, `LLM_BASE_URL`, `LLM_MODEL` зарезервированы в конфигурации.
Ключ хранить только в окружении сервера; документы, ключи и ответы провайдера
не включать в логи или клиентские ошибки.

## Ссылки

| Что | Где |
|---|---|
| Мобильное приложение | [study-ai-assistant/mobile](https://github.com/study-ai-assistant/mobile) |
| Доска задач (Project) | [Sprints](https://github.com/orgs/study-ai-assistant/projects/1) |
| Wiki backend (ТЗ, правила, архитектура) | [Wiki](https://github.com/study-ai-assistant/backend/wiki) |
| Wiki mobile | [Wiki](https://github.com/study-ai-assistant/mobile/wiki) |
| Контракт API | [docs/api/openapi.yaml](docs/api/openapi.yaml) |
| Как участвовать | [CONTRIBUTING.md](CONTRIBUTING.md) |
| Definition of Done | [docs/DEFINITION_OF_DONE.md](docs/DEFINITION_OF_DONE.md) |
| Предложение по стеку | [docs/STACK_PROPOSAL.md](docs/STACK_PROPOSAL.md) |


## Участники

Список команды — в [wiki](https://github.com/study-ai-assistant/backend/wiki).
