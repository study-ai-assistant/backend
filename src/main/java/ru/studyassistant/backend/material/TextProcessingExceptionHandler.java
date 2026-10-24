package ru.studyassistant.backend.material;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import ru.studyassistant.backend.llm.LlmUnavailableException;

/** Преобразует ошибки обработки текста в ответы по контракту API. */
// -1 ставит наш обработчик раньше встроенного обработчика Spring Boot с приоритетом 0.
@Order(-1)
@RestControllerAdvice(assignableTypes = TextProcessingController.class)
public class TextProcessingExceptionHandler {

    /** Сохраняет статус и описание ошибки проверки входных данных. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(
            ResponseStatusException exception,
            HttpServletRequest request
    ) {
        return problem(
                HttpStatus.valueOf(exception.getStatusCode().value()),
                exception.getReason(),
                request
        );
    }

    /** Возвращает 400, если тело запроса невозможно прочитать как ожидаемый JSON. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidJson(
            HttpServletRequest request
    ) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "Некорректный JSON или неверная структура запроса.",
                request
        );
    }

    /** Возвращает 502 без внутренних сведений и ответа LLM-провайдера. */
    @ExceptionHandler(LlmUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleLlmFailure(
            HttpServletRequest request
    ) {
        return problem(
                HttpStatus.BAD_GATEWAY,
                "Сервис обработки текста временно недоступен. Повторите запрос позже.",
                request
        );
    }

    /** Формирует обязательные поля ошибки и Content-Type application/problem+json. */
    private ResponseEntity<Map<String, Object>> problem(
            HttpStatus status,
            String detail,
            HttpServletRequest request
    ) {
        Map<String, Object> body = Map.of(
                "type", "about:blank",
                // Для 413 используем название, указанное в контракте
                "title", status.value() == 413
                        ? "Payload Too Large" : status.getReasonPhrase(),
                "status", status.value(),
                "detail", detail,
                "instance", request.getRequestURI()
        );

        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }
}
