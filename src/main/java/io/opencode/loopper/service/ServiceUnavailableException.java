package io.opencode.loopper.service;

public class ServiceUnavailableException extends RuntimeException {
    private final String code;

    public ServiceUnavailableException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() { return code; }
}
