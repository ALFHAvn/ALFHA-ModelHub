package vn.alfha.modelhub.exception;

public class ProviderException extends RuntimeException {
    private final String code;
    private final Long retryAfterMs;
    public ProviderException(String code, String message) {
        this(code, message, null);
    }
    public ProviderException(String code, String message, Long retryAfterMs) {
        super(message);
        this.code = code;
        this.retryAfterMs = retryAfterMs;
    }
    public String code() { return code; }
    public Long retryAfterMs() { return retryAfterMs; }
}
