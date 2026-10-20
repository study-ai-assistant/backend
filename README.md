# backend — сервер «ИИ-помощника для учёбы»

Серверная часть студенческого проекта «ИИ-помощник для учёбы».

Пользователь загружает учебные материалы в мобильное приложение, сервер передаёт текст во внешний LLM API и возвращает результат в одном из режимов:

- краткий конспект;
- подробный конспект;
- объяснение простыми словами;
- вопросы для самопроверки;
- ключевые термины.

Сервер отвечает за API для мобильного клиента, обработку загруженных текстов и безопасный вызов LLM API (ключ хранится только в переменных окружения).

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

## Быстрый старт

> **Будет заполнен после выбора стека.**

Подробная инструкция появится в wiki: [Запуск и окружение](https://github.com/study-ai-assistant/backend/wiki/Запуск-и-окружение).

Уже сейчас: настройки сервера задаются переменными окружения. Скопируйте `.env.example` в `.env` и заполните значения локально. Файл `.env` в репозиторий не попадает.

## Структура репозитория

```
backend/
├── .github/
│   ├── ISSUE_TEMPLATE/     # шаблоны задач и ошибок
│   ├── workflows/          # CI (пока заготовка)
│   ├── CODEOWNERS
│   └── pull_request_template.md
├── docs/
│   ├── api/                # контракт API (OpenAPI)
│   ├── DEFINITION_OF_DONE.md
│   └── STACK_PROPOSAL.md
├── .env.example            # пример переменных окружения (без значений)
├── CONTRIBUTING.md
└── README.md
```

После выбора стека добавятся папки с исходным кодом и тестами — структура будет описана здесь.

## Участники

Список команды — в [wiki](https://github.com/study-ai-assistant/backend/wiki).
