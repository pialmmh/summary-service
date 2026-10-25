package com.telcobright.summary.treeroads.api;

import org.eclipse.microprofile.config.Config;

import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The bearer this service shows prime-context's TREE ROADS (HARDEN-2). prime-context may guard them —
 * {@code primecontext.tree-roads.guard = open | bearer} (its PC-0025) — and then answers a call that shows no token 401. Each block
 * of the profile that names a tree road's base URL may name the VARIABLE that holds the token, the way the store names its password
 * ({@code summary.store.password-ref}):
 *
 * <pre>
 * summary:
 *   tenants:
 *     prime-context:
 *       base-url: http://10.10.188.2:7091
 *       token-ref: env:PRIME_CONTEXT_TOKEN       # the root's tree (TenantWatcher)
 *   contexts:
 *     mediationContext:
 *       base-url: http://10.10.188.2:7091
 *       token-ref: env:PRIME_CONTEXT_TOKEN       # a bean's context (ContextRegistry)
 * </pre>
 *
 * <ul>
 *   <li>no {@code token-ref}: no bearer — the road is asked exactly as before, with no Authorization header;</li>
 *   <li>{@code token-ref: env:NAME}, NAME set: every call of that block carries {@code Authorization: Bearer <value>}; the value is
 *       taken without the blanks around it (a newline from an environment file is no part of a token);</li>
 *   <li>NAME not set, or empty: <b>the start is refused</b>, in words that name the variable — before anything is dialled;</li>
 *   <li>a {@code token-ref} that is not {@code env:NAME}: refused WITHOUT being shown — it may be the token itself, put into the
 *       wrong key.</li>
 * </ul>
 * The value is read from the environment at start and never printed: not in the start's line ({@link #said()}), not in a refusal,
 * not in {@link #toString()} — only the variable's NAME and the key are.
 */
public final class TreeRoadBearer {

    /** The key under a block that names the token's variable. */
    public static final String KEY = "token-ref";

    /** {@code env:} and the name of an environment variable — nothing else is a reference. */
    private static final Pattern REFERENCE = Pattern.compile("env:([A-Za-z_][A-Za-z0-9_]*)");

    private final String key;
    private final String variable;
    private final String token;

    private TreeRoadBearer(String key, String variable, String token) {
        this.key = key;
        this.variable = variable;
        this.token = token;
    }

    /** No bearer for the block {@code block} (a prefix ending in '.'): its road is asked with no Authorization header. */
    public static TreeRoadBearer none(String block) {
        return new TreeRoadBearer(block + KEY, null, null);
    }

    /**
     * The bearer the block {@code block} (a prefix ending in '.', e.g. {@code summary.tenants.prime-context.}) names, its value from
     * {@code environment} (a variable's value, or null). A named variable that is not set or empty, or a reference that is not
     * {@code env:NAME}, refuses the start.
     */
    public static TreeRoadBearer of(Config config, String block, Function<String, String> environment) {
        String key = block + KEY;
        Optional<String> reference = config.getOptionalValue(key, String.class).map(String::trim).filter(v -> !v.isEmpty());
        if (reference.isEmpty()) {
            return none(block);
        }
        String variable = variableNamedBy(key, reference.get());
        return new TreeRoadBearer(key, variable, fromTheEnvironment(key, variable, environment));
    }

    /** Puts {@code Authorization: Bearer <token>} on a request when there is a bearer; puts nothing when there is none. */
    public void applyTo(BiConsumer<String, String> header) {
        if (token != null) {
            header.accept("Authorization", "Bearer " + token);
        }
    }

    /** One line for the start's log: where the bearer comes from — the variable's NAME and the key, never the value. */
    public String said() {
        return variable == null ? "tree roads: no bearer (" + key + " is not set)" : "tree roads: bearer from " + variable + " (" + key + ")";
    }

    /** What a log may print of it: the line, never the value. */
    @Override
    public String toString() {
        return said();
    }

    private static String variableNamedBy(String key, String reference) {
        Matcher named = REFERENCE.matcher(reference);
        if (!named.matches()) {
            // what is there is NOT shown: a token pasted into the wrong key must not reach a log
            throw new IllegalStateException("REFUSING TO START: " + key + " must be env:<the NAME of an environment variable> (for example "
                    + "env:PRIME_CONTEXT_TOKEN). What is there is not that, and is not shown here");
        }
        return named.group(1);
    }

    private static String fromTheEnvironment(String key, String variable, Function<String, String> environment) {
        String value = environment.apply(variable);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("REFUSING TO START: the environment variable " + variable + " is " + (value == null ? "not set" : "empty")
                    + " — " + key + " names it as the token for prime-context's tree roads. Put it into the unit's environment (never into "
                    + "the profile, a URL or a command line) and start again — or remove " + key + " to ask the road with no bearer");
        }
        return value.trim();
    }
}
