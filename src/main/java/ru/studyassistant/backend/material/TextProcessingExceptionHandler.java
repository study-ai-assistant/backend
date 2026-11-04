package ru.studyassistant.backend.material;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;
import ru.studyassistant.backend.llm.LlmUnavailableException;

/** Преобразует ошибки обработки текста в ответы по контракту API. */
// -1 ставит наш обработчик раньше встроенного обработчика Spring Boot с приоритетом 0.
@Order(-1)
@RestControllerAdvice
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

    /** Лимит multipart может сработать до входа в контроллер. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleUploadSize(HttpServletRequest request) {
        return problem(HttpStatus.PAYLOAD_TOO_LARGE,
                "Максимальный размер файла — 10 МиБ, запроса — 30 МиБ.", request);
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<Map<String, Object>> handleMissingFile(HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "Передайте TXT-файл в поле file.", request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingMode(HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "Передайте режим обработки в поле mode.", request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidMode(HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "Неподдерживаемый режим обработки.", request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMediaType(HttpServletRequest request) {
        return problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Неподдерживаемый Content-Type запроса.", request);
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
