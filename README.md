# Backend учебного помощника

Скелет сервера: Java 21, Spring Boot 4.1.1, Maven. База данных пока не нужна.

## Быстрый старт

### 1. Подготовить окружение

Нужен **JDK 21** (именно комплект разработчика, а не только JRE).
Проверьте в терминале:

```bash
java -version
javac -version
```

Обе команды должны показывать версию 21. Если установлено несколько JDK,
выберите JDK 21 через `JAVA_HOME` и добавьте его каталог `bin` в `PATH`.
После изменения переменных откройте новый терминал.

Maven отдельно устанавливать не нужно: в репозитории есть **Maven Wrapper**.
При первом запуске он скачает Maven 3.9.9 и зависимости из Maven Central,
поэтому потребуется доступ к интернету. Docker, база данных и ключ LLM
для текущего скелета не нужны.

Все команды ниже выполняются из корня `backend` — папки с `pom.xml`,
`mvnw` и `.env.example`. Текущая ветка разработки — `feature/server-base`.

### 2. Создать локальные настройки

Linux / macOS:

```bash
cp .env.example .env
```

Windows PowerShell:

```powershell
Copy-Item .env.example .env
```

Если `.env` уже существует, используйте его, не перезаписывая локальные настройки.
Для запуска достаточно оставить такие значения:

```properties
APP_PORT=8080
LLM_API_KEY=
LLM_BASE_URL=
LLM_MODEL=
```

| Переменная | Назначение | Значение по умолчанию |
|---|---|---|
| `APP_PORT` | HTTP-порт сервера | `8080` |
| `LLM_API_KEY` | Ключ будущего LLM-клиента | Пусто |
| `LLM_BASE_URL` | Адрес будущего LLM API | Пусто |
| `LLM_MODEL` | Название будущей модели | Пусто |

`.env` читается автоматически из **рабочей папки процесса**. Запускайте сервер
из корня проекта, чтобы он нашёл этот файл. Без `.env` сервер тоже запустится
с настройками по умолчанию. Переменные окружения процесса имеют приоритет
над значениями в `.env`.

Формат файла — Java properties: `KEY=value`, без `export` и без кавычек
вокруг значения. `.env` исключён из Git; реальные ключи нельзя добавлять
в код, README, конфигурации запуска, отправляемые в репозиторий, или логи.
Параметры LLM пока зарезервированы: заполнение ключа ещё не включает обработку.

### 3. Запустить через IntelliJ IDEA

1. Откройте папку `backend` через **File → Open**.
2. Если IDEA не распознала Maven, нажмите правой кнопкой на `pom.xml`
   и выберите **Add as Maven Project**. Дождитесь загрузки зависимостей;
   при необходимости выполните **Reload All Maven Projects** в окне Maven.
3. В **File → Project Structure → Project** выберите **Project SDK: JDK 21**.
   Если его нет в списке, добавьте установленный JDK через **Add SDK**.
4. В настройках Maven проверьте, что **JDK for importer** и **Runner JRE**
   используют JDK 21 / Project SDK, а Maven выбран из Wrapper.
   Названия пунктов могут немного отличаться между версиями IDEA.
5. Откройте `src/main/java/ru/studyassistant/backend/BackendApplication.java`
   и нажмите зелёную кнопку рядом с `main`, либо выберите конфигурацию **Backend**.
6. В **Run → Edit Configurations** проверьте **Working directory**:
   это корень `backend` (`$PROJECT_DIR$`). Там же можно задать
   **Environment variables**, например `APP_PORT=8081`.
7. Запустите конфигурацию. В консоли должны появиться сообщения
   `Tomcat started on port 8080` и `Started BackendApplication`.

Остановить сервер можно красной кнопкой **Stop** в окне Run.

### 4. Либо запустить через терминал

Linux / macOS:

```bash
# Проверить сборку и запустить тесты
./mvnw verify
# Запустить сервер; терминал останется занят до остановки
./mvnw spring-boot:run
```

Windows PowerShell:

```powershell
.\mvnw.cmd verify
.\mvnw.cmd spring-boot:run
```

`BUILD SUCCESS` после `verify` означает, что сборка и тесты прошли.
Команда `verify` сама по себе не оставляет работающий сервер.
Для остановки `spring-boot:run` нажмите **Ctrl+C**.

Можно запустить собранный JAR из корня проекта:

```bash
java -jar target/backend-0.0.1-SNAPSHOT.jar
```

Не запускайте JAR и `spring-boot:run` одновременно на одном порту.
После изменения Java-кода пересоберите JAR командой `verify`.

### 5. Проверить ответ сервера

Оставьте сервер запущенным, а запрос выполните в другом терминале:

```bash
curl -i http://localhost:8080/health
```

В Windows PowerShell используйте `curl.exe` вместо `curl`.
Ожидается HTTP **200**, `Content-Type: application/json` и тело:

```json
{"status":"ok"}
```

Также можно открыть `http://localhost:8080/health` в браузере.
Если поменяли `APP_PORT`, используйте новый порт во всех адресах.
Адрес `/` не настроен: ответ 404 на него не означает, что сервер не работает.

### 6. Подключить мобильный клиент

- На самом компьютере: `http://localhost:8080`.
- На стандартном Android-эмуляторе: `http://10.0.2.2:8080`.
- На физическом телефоне: `http://<локальный-IP-компьютера>:8080`.
  Компьютер и телефон должны находиться в общей доступной локальной сети;
  входящие подключения к выбранному порту должны быть разрешены межсетевым экраном.

На телефоне `localhost` указывает на сам телефон. Для Android-клиента
нужно разрешение `android.permission.INTERNET`; если система блокирует обычный
HTTP, разрешите его в debug-конфигурации мобильного приложения для локальной разработки.
Первый запрос клиента для проверки связи — `GET /health`.

### Частые проблемы

| Проблема | Что проверить |
|---|---|
| `java` не найдена или `release version 21 not supported` | Установите JDK 21; проверьте `JAVA_HOME`, `PATH`, JDK Maven и Project SDK в IDEA. |
| `Permission denied` при запуске Wrapper | На Linux / macOS выполните `chmod +x mvnw`. |
| Не скачиваются Maven или зависимости | Проверьте интернет, доступ к Maven Central и настройки прокси Maven. |
| `Port 8080 was already in use` | Остановите другой экземпляр или задайте `APP_PORT=8081` в `.env` и перезапустите. |
| Изменения `.env` не применились | Перезапустите сервер, проверьте рабочую папку и переменные окружения конфигурации запуска: у них приоритет. |
| Телефон не видит сервер | Проверьте IP компьютера, общую сеть, порт и межсетевой экран; сначала проверьте `/health` на компьютере. |
| Загрузка файла возвращает `501` | Это ожидаемо: парсеры и вызов LLM пока не реализованы. |

## API

- `GET /health` — тестовый эндпоинт для мобильного клиента: `{"status":"ok"}`.
- `GET /actuator/health` — техническая проверка Spring Boot.
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

## Структура папок

```text
backend/
├── .github/workflows/       # CI: сборка и тесты
├── .mvn/ и mvnw*            # Maven Wrapper
├── .run/                    # конфигурация запуска IntelliJ IDEA
├── docs/api/                # контракт OpenAPI
├── src/main/java/ru/studyassistant/backend/
│   ├── BackendApplication.java
│   ├── health/              # GET /health
│   ├── material/            # API материалов и режимы
│   ├── extraction/          # интерфейс извлечения текста
│   └── llm/                 # интерфейс внешнего LLM
├── src/main/resources/      # application.yml
├── src/test/java/           # интеграционные HTTP-тесты
├── .env.example             # пример локальных настроек
└── pom.xml                  # зависимости и сборка Maven
```

## Следующие шаги

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
