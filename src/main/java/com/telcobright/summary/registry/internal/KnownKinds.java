package com.telcobright.summary.registry.internal;

import com.telcobright.summary.bean.spi.ServiceGroupReader;
import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.bean.spi.SummaryKind;
import com.telcobright.summary.summarybeans.ad.AdKind;
import com.telcobright.summary.summarybeans.call.CallKind;
import com.telcobright.summary.summarybeans.wifi.WifiKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The kinds this service knows (SS-0008) — the call, the ad, the WiFi — and the three questions asked of them: which kind
 * a service group is, which kind a bean is, and which kind makes a shape of {@code summary.beans.<name>.kind}.
 * Adding a kind (the SMS next) = its package under {@code summarybeans/} and ONE line here.
 */
public final class KnownKinds {

    /** In this order: the call first (the model, and the kind of every group no other kind names). */
    private static final List<SummaryKind> KINDS = List.of(new CallKind(), new AdKind(), new WifiKind());

    private KnownKinds() {
    }

    public static List<SummaryKind> all() {
        return KINDS;
    }

    /** The kinds' words, in order: {@code call, ad, wifi}. */
    public static List<String> words() {
        return KINDS.stream().map(SummaryKind::word).toList();
    }

    /** The kind of this word, or null when no kind has it. */
    public static SummaryKind byWord(String word) {
        return KINDS.stream().filter(kind -> kind.word().equals(word)).findFirst().orElse(null);
    }

    /** The kind whose cdr rows a record of {@code serviceGroup} is: the kind that names the group, else the one that takes every unnamed group. */
    public static SummaryKind ofServiceGroup(int serviceGroup) {
        return KINDS.stream().filter(kind -> kind.serviceGroups().contains(serviceGroup)).findFirst()
                .orElseGet(() -> KINDS.stream().filter(SummaryKind::takesEveryUnnamedServiceGroup).findFirst().orElseThrow());
    }

    /** The kind {@code bean} belongs to: a call-shaped bean by the group it reads, any other by the kind that owns its shape. */
    public static SummaryKind kindOf(SummaryBean<?> bean) {
        if (bean instanceof ServiceGroupReader reader) {
            return ofServiceGroup(reader.serviceGroup());
        }
        return KINDS.stream().filter(kind -> kind.owns(bean)).findFirst().orElseThrow(() -> new IllegalArgumentException(
                "bean '" + bean.name() + "' belongs to no kind — its kind (" + String.join(", ", words()) + ") must own its class"));
    }

    /**
     * The bean of the shape {@code summary.beans.<name>.kind} names, made by the kind whose shape it is (whatever its case or
     * blanks); a word no kind makes is refused in words that name every shape.
     */
    public static SummaryBean<?> shaped(String name, String shape, String window, String context) {
        String wanted = shape == null ? "" : shape.trim().toLowerCase(Locale.ROOT);
        for (SummaryKind kind : KINDS) {
            if (kind.shapes().contains(wanted)) {
                return kind.shaped(name, shape, window, context);
            }
        }
        throw new IllegalArgumentException("summary.beans." + name + ".kind is '" + shape + "' — it must be " + everyShape()
                + " (or absent: a call bean, as before)");
    }

    /** {@code wifi-session or wifi-dst}; with more: {@code a, b or c}. */
    private static String everyShape() {
        List<String> shapes = new ArrayList<>();
        KINDS.forEach(kind -> shapes.addAll(kind.shapes()));
        if (shapes.size() < 2) {
            return String.join("", shapes);
        }
        return String.join(", ", shapes.subList(0, shapes.size() - 1)) + " or " + shapes.get(shapes.size() - 1);
    }
}
