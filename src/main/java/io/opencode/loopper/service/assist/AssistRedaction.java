package io.opencode.loopper.service.assist;

/** Scope credentials are machine-only and must not appear in UI projections or handoffs. */
public final class AssistRedaction {
    private AssistRedaction() { }
    public static String text(String value) {
        return value==null?null:value.replaceAll("(?:lpa|lpp)_[A-Za-z0-9_-]{1,800}\\.[A-Za-z0-9_-]{43}","[辅助作用域凭证已隐藏]");
    }
}
