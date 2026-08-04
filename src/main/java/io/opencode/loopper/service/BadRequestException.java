package io.opencode.loopper.service;
public class BadRequestException extends RuntimeException { private final String code; public BadRequestException(String code, String message) { super(message); this.code = code; } public String code() { return code; } }
