package io.opencode.loopper.service.roles;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.function.Supplier;

/** Loads explicitly indexed, versioned prompt fragments from the application classpath. */
public final class RolePromptResources {
    private static final Map<String, String> CATALOG = loadCatalog();
    private static final Map<String, Set<String>> ROLE_KEYS = loadRoleKeys();
    private static final ThreadLocal<Map<String, String>> FRAGMENTS = new ThreadLocal<>();

    private RolePromptResources() { }

    /** Returns the exact UTF-8 resource contents; no template evaluation or whitespace normalization occurs. */
    public static String read(String id) {
        String baseline = readBaseline(id);
        Map<String, String> scoped = FRAGMENTS.get();
        return scoped == null || id.endsWith(".definition") ? baseline : scoped.getOrDefault(id, baseline);
    }

    /** Selects a Role Pack fragment only when its exact versioned definition is bundled. */
    public static String readRolePack(String id, String version, String slot) {
        readBaseline("role-pack." + version + "." + id + ".definition");
        return read("role-pack." + version + "." + id + "." + slot);
    }

    /** Applies an already-authorized frozen revision during synchronous prompt construction only. */
    public static <T> T withFragments(Map<String, String> fragments, Supplier<T> action) {
        Objects.requireNonNull(fragments);
        Objects.requireNonNull(action);
        Map<String, String> previous = FRAGMENTS.get();
        FRAGMENTS.set(Map.copyOf(fragments));
        try {
            return action.get();
        } finally {
            if (previous == null) FRAGMENTS.remove();
            else FRAGMENTS.set(previous);
        }
    }

    /** Complete immutable id-to-content snapshot for creating frozen role revisions and hashes. */
    public static Map<String, String> catalog() {
        return CATALOG;
    }

    /** Exact built-in role ownership, including fragments shared across multiple role groups. */
    public static Map<String, String> defaultsForRole(String roleId) {
        Set<String> keys = ROLE_KEYS.get(roleId);
        if (keys == null) throw new IllegalArgumentException("Unknown built-in role: " + roleId);
        Map<String, String> result = new LinkedHashMap<>();
        keys.stream().sorted().forEach(key -> result.put(key, readBaseline(key)));
        return Collections.unmodifiableMap(result);
    }

    /** Immutable packaged content for historical Role Pack compatibility; never reads scoped overrides. */
    public static String readBaseline(String id) {
        String content = CATALOG.get(id);
        if (content == null) throw new IllegalArgumentException("Unknown role prompt resource: " + id);
        return content;
    }

    private static Map<String, String> loadCatalog() {
        Properties index = new Properties();
        try (InputStream stream = resource("role-prompts/catalog.properties");
             InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            index.load(reader);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot load role prompt catalog", failure);
        }
        Map<String, String> contents = new LinkedHashMap<>();
        index.stringPropertyNames().stream().sorted().forEach(id -> {
            String relative = index.getProperty(id);
            if (relative == null || relative.isBlank() || relative.startsWith("/")
                    || relative.contains("..") || relative.contains("\\")) {
                throw new IllegalStateException("Invalid role prompt resource path for " + id);
            }
            String path = "role-prompts/" + relative;
            try (InputStream stream = resource(path)) {
                contents.put(id, new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException failure) {
                throw new IllegalStateException("Cannot load role prompt resource: " + id, failure);
            }
        });
        return Collections.unmodifiableMap(contents);
    }

    private static Map<String, Set<String>> loadRoleKeys() {
        Properties index = new Properties();
        try (InputStream stream = resource("role-prompts/role-fragments.properties");
             InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            index.load(reader);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot load built-in role fragment mapping", failure);
        }
        Map<String, Set<String>> result = new LinkedHashMap<>();
        index.stringPropertyNames().stream().sorted().forEach(roleId -> {
            String value = index.getProperty(roleId);
            Set<String> keys = value.isEmpty() ? Set.of() : Set.of(value.split(","));
            for (String key : keys) {
                if (!CATALOG.containsKey(key) || key.endsWith(".definition")) {
                    throw new IllegalStateException("Invalid built-in role fragment: " + roleId + "/" + key);
                }
            }
            result.put(roleId, keys);
        });
        return Collections.unmodifiableMap(result);
    }

    private static InputStream resource(String path) {
        InputStream stream = RolePromptResources.class.getClassLoader().getResourceAsStream(path);
        if (stream == null) throw new IllegalStateException("Missing role prompt resource: " + path);
        return stream;
    }
}
