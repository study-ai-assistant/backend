# Backend учебного помощника

**[Патчноуты: изменения по датам и что осталось доделать](PATCHNOTES.md)**

Сервер учебного помощника: Java 21, Spring Boot 4.1.1, Maven.
Обрабатывает текст через Groq в режиме краткого конспекта. База данных пока не нужна.

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
поэтому потребуется доступ к интернету. Docker и база данных для обычного запуска
не нужны. Без ключа доступен /health; для генерации нужен ключ Groq и доступ к его API.

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
Для запуска /health достаточно пустых LLM-настроек. Для генерации заполните:

```properties
APP_PORT=8080
LLM_PROVIDER=groq
LLM_API_KEY=
LLM_BASE_URL=https://api.groq.com/openai/v1
LLM_MODEL=openai/gpt-oss-120b
```

| Переменная | Назначение | Значение по умолчанию |
|---|---|---|
| `APP_PORT` | HTTP-порт сервера | `8080` |
| `LLM_PROVIDER` | Название провайдера, например `groq` | Пусто |
| `LLM_API_KEY` | Личный ключ Groq (заполнить в своём .env) | Пусто |
| `LLM_BASE_URL` | Базовый URL Groq API | Пусто |
| `LLM_MODEL` | Модель Groq, например openai/gpt-oss-120b | Пусто |

`.env` читается автоматически из **рабочей папки процесса**. Запускайте сервер
из корня проекта, чтобы он нашёл этот файл. Без `.env` сервер тоже запустится
с настройками по умолчанию. Переменные окружения процесса имеют приоритет
над значениями в `.env`.

Формат файла — Java properties: `KEY=value`, без `export` и без кавычек
вокруг значения. `.env` исключён из Git; реальные ключи нельзя добавлять
в код, README, конфигурации запуска, отправляемые в репозиторий, или логи.
Заполните LLM_API_KEY собственным ключом без кавычек. Не оставляйте дубли переменных:
последняя запись в .env может перезаписать предыдущую. Сейчас реализован только
провайдер groq; другой LLM_PROVIDER потребует адаптера. Без полных настроек
запрос обработки текста вернёт 502. После изменения .env перезапустите сервер.

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
| Загрузка файла возвращает `501` | Парсеры файлов не реализованы. Для генерации передавайте готовый текст в JSON. |
| Обработка текста возвращает `400` | Проверьте JSON: только text и mode, режим SHORT_SUMMARY. Перенос внутри строки кодируется как \n; используйте сериализатор или JSON-файл. |
| Обработка текста возвращает `502` | Проверьте LLM_PROVIDER=groq, ключ, модель, URL, доступ к API и квоту; после правки .env перезапустите сервер. |

## Запуск через Docker Compose

Для mobile-разработчиков достаточно Docker с Compose plugin; JDK и Maven
на хосте не нужны. Установка: https://docs.docker.com/get-started/get-docker/.
Проверьте `docker version` и `docker compose version`, затем из корня проекта:

```bash
cp .env.example .env
# Windows PowerShell: Copy-Item .env.example .env
docker compose up --build
```

Не перезаписывайте существующий .env. В другом терминале:
`curl -i http://localhost:8080/health` (PowerShell: curl.exe).
Ожидается 200 и `{"status":"ok"}`. Для запуска в фоне используйте
`docker compose up --build -d --wait --wait-timeout 180`.
Остановить: `docker compose down`; посмотреть состояние: `docker compose ps`.
При изменении .env: `docker compose up -d --force-recreate`.
Порт хоста берётся из APP_PORT, по умолчанию 8080. В контейнере — всегда 8080.
.env и Git-история не входят в Docker-контекст; ключ передаётся окружением
при запуске, а не при сборке. Не публикуйте вывод `docker compose config` с ключами.

Подробная установка Windows/macOS/Linux и разбор проблем:
[локальная инструкция для wiki](docs/wiki/Запуск-и-окружение.md).

## Проверки качества

```bash
./mvnw checkstyle:check
./mvnw verify
```

Windows: `.\mvnw.cmd` вместо `./mvnw`. verify включает линтер, тесты
и сборку JAR. CI настроен на PR и push в main/feature/chore/docs;
обязательные проверки main добавляет руководитель после успешного CI.

## API

| Метод и путь | Что отправлять | Что получить |
|---|---|---|
| GET /health | Без тела | 200, {"status":"ok"} |
| GET /actuator/health | Без тела | Техническое состояние сервера |
| GET /api/v1/modes | Без тела | Пять идентификаторов, но работает только SHORT_SUMMARY |
| POST /api/v1/text/process | JSON: text и mode | Краткий конспект через Groq |
| POST /api/v1/materials/process | multipart: files и mode | Заглушка: 501 для непустых файлов |

Рабочий запрос из Linux/macOS:

```bash
curl -i --max-time 90 http://localhost:8080/api/v1/text/process \
  -H 'Content-Type: application/json' \
  --data-raw '{"text":"Митохондрии обеспечивают клетку энергией.","mode":"SHORT_SUMMARY"}'
```

Пример 200 (формулировка конспекта зависит от модели):

```json
{"mode":"SHORT_SUMMARY","result":"Митохондрии снабжают клетку энергией."}
```

Требуются ровно text и mode. text — непустая строка до 10000 Unicode-кодовых точек;
mode — только SHORT_SUMMARY. Остальные режимы пока дают 400. Неверный JSON,
лишние/пропущенные поля и пустой текст тоже дают 400; превышение длины — 413;
неверные настройки, ошибки, квоты и таймауты Groq — 502.

**Файлы TXT/DOCX/PDF пока не обрабатываются.** Для мобильной команды рабочий
сценарий — отправлять уже готовый текст в application/json. Загрузка файла
технически принимается multipart-маршрутом, но даёт 501; пустой файл — 400.
Лимиты multipart: 10 MB на файл, 30 MB на запрос; превышение — 413.

Не вставляйте буквальный перенос строки между кавычками JSON: используйте \n
или JSON-сериализатор. Для длинного текста и Windows удобнее пример из файла:

```bash
curl -i --max-time 90 http://localhost:8080/api/v1/text/process \
  -H 'Content-Type: application/json' \
  --data-binary @docs/api/examples/short-summary-request.json
```

Windows PowerShell: `curl.exe` вместо `curl`.
Подробности, ошибки, пример для абзацев и адреса для телефона:
[инструкция API](docs/api/README.md). Формальная схема: [OpenAPI](docs/api/openapi.yaml).

## Структура папок

```text
backend/
├── .github/workflows/       # CI: сборка и тесты
├── .mvn/ и mvnw*            # Maven Wrapper
├── .run/                    # конфигурация запуска IntelliJ IDEA
├── docs/api/                # контракт OpenAPI
├── docs/wiki/               # локальные материалы для wiki
├── config/checkstyle/       # правила линтера Java
├── docker/                  # проверка состояния контейнера
├── Dockerfile               # многоэтапная сборка
├── docker-compose.yml       # запуск сервера одной командой
├── src/main/java/ru/studyassistant/backend/
│   ├── BackendApplication.java
│   ├── health/              # GET /health
│   ├── material/            # API материалов и режимы
│   ├── extraction/          # интерфейс извлечения текста
│   └── llm/                 # клиент Groq, настройки и интерфейс LLM
├── src/main/resources/      # application.yml
├── src/test/java/           # интеграционные HTTP-тесты
├── .env.example             # пример локальных настроек
└── pom.xml                  # зависимости и сборка Maven
```

## Следующие шаги

Реализованы Groq-клиент и краткий конспект текста. Следующая функциональность:

- парсеры TXT/DOCX/PDF и распознавание сканов (OCR);
- остальные четыре режима генерации;
- токенный бюджет, очереди и обработка больших материалов частями;
- адаптеры других LLM-провайдеров при необходимости.

Авторизация, хранение и история запросов не реализованы. До обещания обработки
документов нужно проверять форматы по содержимому и лимиты распаковки DOCX.
Docker-запуск ещё требует реальной проверки. Текущие изменения и ограничения —
в [патчноутах](PATCHNOTES.md). Ключ и полный текст запроса не выводить в логи.

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
