# Тестовые PDF

Файлы созданы для проекта и не содержат персональных данных.
Реальные учебные материалы из проверки производительности сюда не копируются.

| Файл | Проверка | Ожидаемый результат |
|---|---|---|
| plain.pdf | Обычный английский текст | 200, текст передан в LLM-заглушку |
| russian.pdf | Кириллица, включая Ё; все пять режимов | 200, текст не повреждён |
| multipage.pdf | Три страницы | 200, сохранён порядок страниц |
| scan.pdf | Изображение без текстового слоя | 422, LLM не вызывается |
| table.pdf | Таблица с двумя столбцами | 200, текст и значения доступны; структура таблицы не обещается |
| formulas.pdf | Кириллица, греческие символы и степени | 200, контрольные символы сохранены |
| password.pdf | Зашифрованный PDF | 422, пароль не подбирается и LLM не вызывается |
| corrupted.pdf | Намеренно повреждённый PDF с сигнатурой | 422, LLM не вызывается |

Пароли password.pdf — публичные тестовые значения: fixture-password и fixture-owner.
Они нужны только для воспроизведения теста и не используются приложением.

Первые три файла созданы изолированным прототипом PDFBox. Остальные можно пересоздать:

```bash
./mvnw dependency:build-classpath -Dmdep.outputFile=target/pdf-classpath.txt
javac -cp "$(cat target/pdf-classpath.txt)" -d target/pdf-fixture-tools tools/pdf/GeneratePdfFixtures.java
java -Djava.awt.headless=true -cp "target/pdf-fixture-tools:$(cat target/pdf-classpath.txt)" \
  GeneratePdfFixtures src/test/resources/pdf /usr/share/fonts/truetype/dejavu/DejaVuSans.ttf
```

Команды для Linux/macOS; передайте путь к установленному DejaVu Sans на своей машине.
Файлы содержат подмножество шрифта, лицензия — FONT-LICENSE.txt.
Для запуска обычных тестов установленный DejaVu и генератор не нужны.
