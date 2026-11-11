package devPilot.backend.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
public class TooManyRequestsException extends RuntimeException {

    /** The visitor has used all their demo questions; it won't clear by waiting. */
    public static final String DEMO_QUESTION_LIMIT = "DEMO_QUESTION_LIMIT";

    /** Machine-readable reason for the client, or null. */
    private final String code;

    public TooManyRequestsException(String message) {
        this(message, null);
    }

    public TooManyRequestsException(String message, String code) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
