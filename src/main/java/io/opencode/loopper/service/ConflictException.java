package io.opencode.loopper.service;
public class ConflictException extends RuntimeException { private final String code; public ConflictException(String code, String message) { super(message); this.code = code; } public String code() { return code; } }
