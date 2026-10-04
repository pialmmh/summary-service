package com.telcobright.summary.runtime.internal;

import org.eclipse.microprofile.config.Config;

import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The store's password, and the rule of where it may come from (brief S9; the owner's secreteer ruling):
 *
 * <pre>
 * summary:
 *   store:
 *     password-ref: env:TENANT_BTCL_SWITCH_SUMMARY_SERVICE_PASSWORD   # the profile NAMES an environment variable
 *     # password: "…"                                                 # the inline form, kept for the deployments that have it
 * </pre>
 *
 * <ul>
 *   <li>{@code password-ref: env:NAME} — the value lives in the unit's ENVIRONMENT only (on the wifi bed:
 *       secreteer's {@code /etc/secreteer/<tenant>/<app>.env}, the unit's {@code EnvironmentFile=}). The profile, the
 *       jar, the command line and the log never hold it;</li>
 *   <li>a profile that names a variable which is NOT SET (or is empty) <b>refuses the start</b>, in words that name
 *       the variable — before anything is dialled. The service never starts with a guessed or empty password;</li>
 *   <li>the two forms are never used together, and a password is never in the URL: both are refused;</li>
 *   <li>a VALUE is never printed — not in a refusal, not in the line that says where the password came from. A
 *       reference that is not {@code env:NAME} is refused WITHOUT being shown: it may be a secret put there by mistake.</li>
 * </ul>
 */
final class StoreSecret {

    static final String INLINE_KEY = StoreConfig.PREFIX + "password";
    static final String REFERENCE_KEY = StoreConfig.PREFIX + "password-ref";

    /** {@code env:} and the name of an environment variable — nothing else is a reference. */
    private static final Pattern REFERENCE = Pattern.compile("env:([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern PASSWORD_IN_A_URL = Pattern.compile("(?i)[?&;](password|pwd)=");

    enum Kind {NONE, INLINE, ENVIRONMENT}

    /** Where the password comes from: the form, and for the environment form the variable's NAME. Never a value. */
    record Source(Kind kind, String variable) {

        /** One line for the start's log — it names the variable, never what it holds. */
        String said() {
            return switch (kind) {
                case NONE -> "the store's password: none is configured";
                case INLINE -> "the store's password: inline in the profile (" + INLINE_KEY + ")";
                case ENVIRONMENT -> "the store's password: from the environment variable " + variable + " (" + REFERENCE_KEY + ")";
            };
        }
    }

    private StoreSecret() {
    }

    /** The form the profile uses. Both forms together, or a reference that is not {@code env:NAME}, are refused. */
    static Source sourceOf(Config config) {
        Optional<String> reference = config.getOptionalValue(REFERENCE_KEY, String.class).map(String::trim).filter(v -> !v.isEmpty());
        boolean inline = config.getOptionalValue(INLINE_KEY, String.class).filter(v -> !v.isEmpty()).isPresent();
        if (reference.isEmpty()) {
            return new Source(inline ? Kind.INLINE : Kind.NONE, null);
        }
        if (inline) {
            throw new IllegalStateException("REFUSING TO START: " + INLINE_KEY + " and " + REFERENCE_KEY + " are BOTH set — "
                    + "name the environment variable, or give the inline form; never both");
        }
        Matcher named = REFERENCE.matcher(reference.get());
        if (!named.matches()) {
            // what is there is NOT shown: a password pasted into the wrong key must not reach a log
            throw new IllegalStateException("REFUSING TO START: " + REFERENCE_KEY + " must be env:<the NAME of an environment variable> "
                    + "(for example env:TENANT_BTCL_SWITCH_SUMMARY_SERVICE_PASSWORD). What is there is not that, and is not shown here");
        }
        return new Source(Kind.ENVIRONMENT, named.group(1));
    }

    /** The password itself. A named variable that is not set, or is empty, refuses the start and names the variable. */
    static String passwordOf(Config config, Function<String, String> environment) {
        Source source = sourceOf(config);
        return switch (source.kind()) {
            case NONE -> "";
            case INLINE -> config.getValue(INLINE_KEY, String.class);
            case ENVIRONMENT -> {
                String value = environment.apply(source.variable());
                if (value == null || value.isEmpty()) {
                    throw new IllegalStateException("REFUSING TO START: the environment variable " + source.variable() + " is "
                            + (value == null ? "not set" : "empty") + " — " + REFERENCE_KEY + " names it as the store's password. "
                            + "Put it into the unit's environment (never into the profile, a URL or a command line) and start again");
                }
                yield value;
            }
        };
    }

    /** A password never rides in the URL (it would be printed with the endpoints and logged by drivers). The URL is not shown. */
    static void refuseAPasswordInTheUrl(String url) {
        if (url != null && PASSWORD_IN_A_URL.matcher(url).find()) {
            throw new IllegalStateException("REFUSING TO START: " + StoreConfig.PREFIX + "url carries a password parameter — a secret is never "
                    + "in a URL. Take it out and name its environment variable in " + REFERENCE_KEY + " (the URL is not shown here)");
        }
    }
}
