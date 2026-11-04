package vn.alfha.modelhub.exception;

public class ProviderException extends RuntimeException {
    private final String code;
    public ProviderException(String code, String message) {
        super(message);
        this.code = code;
    }
    public String code() { return code; }
}
