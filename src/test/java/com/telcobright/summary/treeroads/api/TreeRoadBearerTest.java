package com.telcobright.summary.treeroads.api;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HARDEN-2 — prime-context's tree roads may ask a bearer (its PC-0025: {@code primecontext.tree-roads.guard = open | bearer}). A
 * block that names a tree road's base URL may NAME the variable that holds the token ({@code <block>token-ref: env:<VAR>}), as the
 * store names its password; the value is read from the environment at start. No key = no bearer, exactly as before. A named
 * variable that is empty refuses the start, in words that name it; nothing here ever prints a value.
 */
class TreeRoadBearerTest {

    private static final String TOKEN = "pct_tree-road-token-0123456789abcdef";
    private static final String TREE = "summary.tenants.prime-context.";
    private static final String CONTEXT = "summary.contexts.mediationContext.";
    private static final Map<String, String> THE_UNITS_ENVIRONMENT = Map.of("PRIME_CONTEXT_TOKEN", TOKEN);
    private static final Function<String, String> NOT_TO_BE_READ = name -> {
        throw new AssertionError("the environment is not read: " + name);
    };

    @Test
    void a_block_that_names_no_variable_has_no_bearer_and_the_environment_is_not_read() {
        TreeRoadBearer bearer = TreeRoadBearer.of(profile(Map.of(TREE + "base-url", "http://10.10.188.2:7091")), TREE, NOT_TO_BE_READ);

        assertEquals(List.of(), headersOf(bearer), "no Authorization header: the road is asked exactly as before");
        assertEquals("tree roads: no bearer (summary.tenants.prime-context.token-ref is not set)", bearer.said());
    }

    @Test
    void the_block_names_the_variable_and_the_token_is_read_from_the_environment_only() {
        TreeRoadBearer bearer = TreeRoadBearer.of(profile(Map.of(TREE + "token-ref", "env:PRIME_CONTEXT_TOKEN")), TREE, THE_UNITS_ENVIRONMENT::get);

        assertEquals(List.of("Authorization: Bearer " + TOKEN), headersOf(bearer));
    }

    @Test
    void the_starts_line_names_the_variable_and_the_key_never_the_value() {
        TreeRoadBearer bearer = TreeRoadBearer.of(profile(Map.of(TREE + "token-ref", "env:PRIME_CONTEXT_TOKEN")), TREE, THE_UNITS_ENVIRONMENT::get);

        assertEquals("tree roads: bearer from PRIME_CONTEXT_TOKEN (summary.tenants.prime-context.token-ref)", bearer.said());
        assertFalse(String.valueOf(bearer).contains(TOKEN), "nor in its toString: " + bearer);
    }

    @Test
    void a_named_variable_that_is_not_set_or_empty_refuses_the_start_in_words_that_name_it() {
        Config config = profile(Map.of(TREE + "token-ref", "env:PRIME_CONTEXT_TOKEN"));
        Map<Map<String, String>, String> said = Map.of(Map.of(), "is not set", Map.of("PRIME_CONTEXT_TOKEN", ""), "is empty",
                Map.of("PRIME_CONTEXT_TOKEN", "  "), "is empty");
        said.forEach((environment, words) -> {
            IllegalStateException refused = assertThrows(IllegalStateException.class, () -> TreeRoadBearer.of(config, TREE, environment::get),
                    environment.toString());

            assertTrue(refused.getMessage().startsWith("REFUSING TO START: the environment variable PRIME_CONTEXT_TOKEN " + words
                    + " — summary.tenants.prime-context.token-ref names it"), refused.getMessage());
        });
    }

    @Test
    void a_reference_that_is_not_env_colon_name_is_refused_and_not_shown() {
        for (String reference : new String[] {"vault:secret/summary", "env:", "env:has space", "PRIME_CONTEXT_TOKEN", "env:9STARTS_WITH_A_DIGIT", TOKEN}) {
            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> TreeRoadBearer.of(profile(Map.of(TREE + "token-ref", reference)), TREE, THE_UNITS_ENVIRONMENT::get), reference);

            assertTrue(refused.getMessage().startsWith("REFUSING TO START: summary.tenants.prime-context.token-ref must be env:<the NAME of an "
                    + "environment variable>"), refused.getMessage());
            assertFalse(refused.getMessage().contains(TOKEN), "a token put into the wrong key is not shown: " + refused.getMessage());
        }
    }

    @Test
    void the_token_is_taken_without_the_blanks_around_it() {
        TreeRoadBearer bearer = TreeRoadBearer.of(profile(Map.of(TREE + "token-ref", " env:PRIME_CONTEXT_TOKEN ")), TREE,
                Map.of("PRIME_CONTEXT_TOKEN", " " + TOKEN + "\n")::get);

        assertEquals(List.of("Authorization: Bearer " + TOKEN), headersOf(bearer), "a newline from an environment file is no part of the token");
    }

    @Test
    void each_block_has_its_own_key_and_its_own_variable() {
        Config config = profile(Map.of(TREE + "token-ref", "env:TREE_TOKEN", CONTEXT + "token-ref", "env:CONTEXT_TOKEN"));
        Map<String, String> environment = Map.of("TREE_TOKEN", "tree-value", "CONTEXT_TOKEN", "context-value");

        assertEquals(List.of("Authorization: Bearer tree-value"), headersOf(TreeRoadBearer.of(config, TREE, environment::get)));
        assertEquals(List.of("Authorization: Bearer context-value"), headersOf(TreeRoadBearer.of(config, CONTEXT, environment::get)));
        assertEquals("tree roads: bearer from CONTEXT_TOKEN (summary.contexts.mediationContext.token-ref)",
                TreeRoadBearer.of(config, CONTEXT, environment::get).said());
    }

    private static Config profile(Map<String, String> keys) {
        return new SmallRyeConfigBuilder().withSources(new PropertiesConfigSource(keys, "a test's profile", 300)).build();
    }

    private static List<String> headersOf(TreeRoadBearer bearer) {
        List<String> headers = new ArrayList<>();
        bearer.applyTo((name, value) -> headers.add(name + ": " + value));
        return headers;
    }
}
