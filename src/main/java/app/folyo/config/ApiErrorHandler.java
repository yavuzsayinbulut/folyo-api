package app.folyo.config;

import java.util.Map;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Hatalari ProblemDetail olarak, kullaniciya gosterilebilir Turkce "detail" ile doner. */
@RestControllerAdvice
public class ApiErrorHandler extends ResponseEntityExceptionHandler {

    /** Es zamanli isteklerde on kontrolleri gecip kisita takilan durumlar. */
    private static final Map<String, String> CONSTRAINT_MESSAGES = Map.of(
            "uq_profiles_username", "Bu kullanıcı adı alınmış",
            "uq_profiles_user", "Zaten bir sayfan var",
            "uq_cards_position", "Kartlar aynı anda değişti, sayfayı yenile");

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getDefaultMessage())
                .orElse("İstek geçersiz");
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, message));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleConflict(DataIntegrityViolationException ex) {
        String constraint = constraintName(ex);
        String message = constraint == null ? null : CONSTRAINT_MESSAGES.get(constraint);
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                message != null ? message : "Kayıt çakıştı, tekrar dene");
    }

    private static String constraintName(Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return violation.getConstraintName();
            }
        }
        return null;
    }
}
