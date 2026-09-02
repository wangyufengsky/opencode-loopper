package io.opencode.loopper.runtime;

/** Parses the configured provider/model pair without consulting a runtime. */
public final class OpenCodeModelSelection {
    private OpenCodeModelSelection() { }

    public static OpenCodeClient.OpenCodeModel configured(String configured) {
        if (configured == null) return null;
        String value = configured.trim();
        int separator = value.indexOf('/');
        if (separator <= 0 || separator >= value.length() - 1) return null;
        String provider = value.substring(0, separator).trim();
        String model = value.substring(separator + 1).trim();
        return provider.isEmpty() || model.isEmpty() ? null
                : new OpenCodeClient.OpenCodeModel(provider, model, null);
    }

    public static OpenCodeClient.OpenCodeModel forStructuredResponse(String configured, boolean structured) {
        OpenCodeClient.OpenCodeModel model = configured(configured);
        return model == null ? null : new OpenCodeClient.OpenCodeModel(model.providerId(), model.modelId(),
                structured ? Boolean.FALSE : model.thinking());
    }
}
