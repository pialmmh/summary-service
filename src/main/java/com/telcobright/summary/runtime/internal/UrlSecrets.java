package com.telcobright.summary.runtime.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * What a password looks like INSIDE a URL — known in ONE place, and used for two things that must never differ:
 * such a store URL is refused at a start ({@link StoreSecret}: a secret is never in a URL), and wherever a
 * configured value is printed (the endpoints of a start, a refusal) the password is hidden. A form that is refused
 * but printed first would still leak; a form that is hidden but not refused would still be used.
 *
 * <p>The forms: a parameter whose name holds {@code password} or {@code pwd} — {@code ?password=…},
 * {@code &sslpassword=…}, MySQL's {@code (host=…,password=…)} — and the user-info form
 * {@code //user:password@host}, for each host of a list. A user alone ({@code //user@host}) is no secret.
 */
public final class UrlSecrets {

    private static final String HIDDEN = "<hidden>";

    /** {@code password=…}, {@code pwd=…}, and any longer name that holds one of them; the value ends at a separator. */
    private static final Pattern PARAMETER = Pattern.compile("(?i)((?:password|pwd)[A-Za-z0-9_.]*\\s*=)[^&;),\\s]*");

    private UrlSecrets() {
    }

    /** True when {@code url} carries a password, in any of the forms. */
    public static boolean in(String url) {
        return url != null && (PARAMETER.matcher(url).find() || !withoutUserInfoPasswords(url).equals(url));
    }

    /** {@code value} as it may be printed: every password in it is replaced by {@code <hidden>}. */
    public static String hidden(String value) {
        return value == null ? null : withoutUserInfoPasswords(PARAMETER.matcher(value).replaceAll("$1" + HIDDEN));
    }

    /** {@code //user:password@host} → {@code //<hidden>@host}, in the authority only, for each host of a list. */
    private static String withoutUserInfoPasswords(String value) {
        int scheme = value.indexOf("://");
        if (scheme < 0) {
            return value;
        }
        int start = scheme + 3;
        int end = value.length();
        for (char stop : new char[] {'/', '?', '#'}) {
            int at = value.indexOf(stop, start);
            if (at >= 0 && at < end) {
                end = at;
            }
        }
        List<String> hosts = new ArrayList<>();
        for (String host : value.substring(start, end).split(",", -1)) {
            int user = host.lastIndexOf('@');
            boolean withPassword = user >= 0 && host.lastIndexOf(':', user) >= 0;
            hosts.add(withPassword ? HIDDEN + host.substring(user) : host);
        }
        return value.substring(0, start) + String.join(",", hosts) + value.substring(end);
    }
}
