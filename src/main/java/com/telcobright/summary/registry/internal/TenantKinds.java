package com.telcobright.summary.registry.internal;

import com.telcobright.summary.bean.spi.SummaryKind;
import org.eclipse.microprofile.config.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * The kinds a tenant switched on (SS-0008 item 2): {@code summary.kinds} in its profile — {@code [call, ad, wifi]}. None
 * named = the call alone, which is what the service did before kinds existed (the voice deployment's profile names none).
 * A word that is no kind refuses the start, in words that name the kinds. The words are read whatever their case or blanks;
 * a word named twice is one kind.
 */
public final class TenantKinds {

    /** The profile's key. */
    public static final String KEY = "summary.kinds";

    private final List<SummaryKind> enabled;
    private final boolean named;

    private TenantKinds(List<SummaryKind> enabled, boolean named) {
        this.enabled = List.copyOf(enabled);
        this.named = named;
    }

    /** The kinds the profile names under {@link #KEY}; none named = the call alone. */
    public static TenantKinds of(Config config) {
        return of(config.getOptionalValues(KEY, String.class).orElse(List.of()));
    }

    /** The kinds of these words; an empty list = the call alone. A word that is no kind is refused in words. */
    public static TenantKinds of(List<String> words) {
        List<SummaryKind> kinds = new ArrayList<>();
        for (String word : words) {
            SummaryKind kind = kindOfWord(word);
            if (kind != null && !kinds.contains(kind)) {
                kinds.add(kind);
            }
        }
        if (kinds.isEmpty()) {
            return new TenantKinds(List.of(KnownKinds.byWord("call")), false);
        }
        kinds.sort((a, b) -> KnownKinds.all().indexOf(a) - KnownKinds.all().indexOf(b));
        return new TenantKinds(kinds, true);
    }

    /** Every known kind — what a reader of every group is untold (tests, embedders). */
    public static TenantKinds everyKind() {
        return new TenantKinds(KnownKinds.all(), true);
    }

    public boolean enables(SummaryKind kind) {
        return enabled.contains(kind);
    }

    /** Does a record of {@code serviceGroup} belong to a kind this tenant enabled? */
    public boolean enablesServiceGroup(int serviceGroup) {
        return enables(KnownKinds.ofServiceGroup(serviceGroup));
    }

    public boolean enablesEveryKind() {
        return enabled.size() == KnownKinds.all().size();
    }

    /** The known kinds this tenant did not enable, in the kinds' order. */
    public List<SummaryKind> notEnabled() {
        return KnownKinds.all().stream().filter(kind -> !enables(kind)).toList();
    }

    /** The enabled kinds' words, in the kinds' order. */
    public List<String> words() {
        return enabled.stream().map(SummaryKind::word).toList();
    }

    /** How the start says it: {@code call, ad, wifi (summary.kinds)} — or that none was named. */
    public String said() {
        String words = String.join(", ", words());
        return named ? words + " (" + KEY + ")" : words + " (" + KEY + " names none: the call alone, as before kinds)";
    }

    /** What a kind reads, for the start's line: {@code ad = service group 30}. */
    public static String readsOf(SummaryKind kind) {
        String groups = kind.serviceGroups().stream().sorted().map(String::valueOf).collect(Collectors.joining(", "));
        String unnamed = kind.takesEveryUnnamedServiceGroup() ? " and every group no kind names" : "";
        return kind.word() + " = service group" + (kind.serviceGroups().size() == 1 ? " " : "s ") + groups + unnamed;
    }

    private static SummaryKind kindOfWord(String word) {
        String wanted = word == null ? "" : word.trim().toLowerCase(Locale.ROOT);
        if (wanted.isEmpty()) {
            return null;
        }
        SummaryKind kind = KnownKinds.byWord(wanted);
        if (kind == null) {
            throw new IllegalArgumentException(KEY + " names '" + word + "', which is no kind — the kinds are "
                    + String.join(", ", KnownKinds.words()));
        }
        return kind;
    }
}
