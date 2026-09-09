package io.opencode.loopper.service;

/** Explicit recovery must not be blocked solely by the automatic retry allowance. */
enum PackageDesignDispatch {
    CONTINUE,
    AUTOMATIC_REDESIGN,
    MANUAL_REDESIGN;

    boolean redesign() { return this != CONTINUE; }
}
