package com.telcobright.summary.registry.internal;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SS-0008 item 2: {@code summary.kinds} — the kinds a tenant switched on; none named = the call alone. */
class TenantKindsTest {

    @Test
    void none_named_is_the_call_alone_as_before_kinds() {
        TenantKinds kinds = TenantKinds.of(List.of());

        assertEquals(List.of("call"), kinds.words());
        assertTrue(kinds.enablesServiceGroup(10) && kinds.enablesServiceGroup(11), "the voice groups");
        assertTrue(kinds.enablesServiceGroup(4), "a group no kind names is the call's");
        assertFalse(kinds.enablesServiceGroup(30), "the ad's group is not");
        assertFalse(kinds.enablesServiceGroup(101), "the WiFi's group is not");
        assertFalse(kinds.enablesEveryKind());
        assertEquals(List.of("ad", "wifi"), kinds.notEnabled().stream().map(k -> k.word()).toList());
        assertEquals("call (summary.kinds names none: the call alone, as before kinds)", kinds.said());
    }

    @Test
    void the_profile_value_is_read_as_the_list_the_yaml_loader_makes_of_it() {
        TenantKinds none = TenantKinds.of(new SmallRyeConfigBuilder().withSources(new PropertiesConfigSource(Map.of(), "a profile", 300)).build());
        TenantKinds two = TenantKinds.of(new SmallRyeConfigBuilder().withSources(
                new PropertiesConfigSource(Map.of("summary.kinds", "call,ad"), "a profile", 300)).build());

        assertEquals(List.of("call"), none.words());
        assertEquals(List.of("call", "ad"), two.words());
        assertEquals("call, ad (summary.kinds)", two.said());
    }

    @Test
    void the_words_are_read_whatever_their_case_blanks_order_or_repeats_and_kept_in_the_kinds_order() {
        TenantKinds kinds = TenantKinds.of(List.of(" WIFI", "call", "Ad ", "call"));

        assertEquals(List.of("call", "ad", "wifi"), kinds.words());
        assertTrue(kinds.enablesEveryKind());
        assertTrue(kinds.notEnabled().isEmpty());
        assertTrue(kinds.enablesServiceGroup(30) && kinds.enablesServiceGroup(101) && kinds.enablesServiceGroup(10));
    }

    @Test
    void a_tenant_of_the_ad_alone_reads_neither_the_voice_groups_nor_the_unnamed_ones() {
        TenantKinds kinds = TenantKinds.of(List.of("ad"));

        assertTrue(kinds.enablesServiceGroup(30));
        assertFalse(kinds.enablesServiceGroup(10));
        assertFalse(kinds.enablesServiceGroup(4));
        assertFalse(kinds.enablesServiceGroup(101));
    }

    @Test
    void a_word_that_is_no_kind_is_refused_in_words_that_name_the_kinds() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> TenantKinds.of(List.of("call", "sms")));

        assertEquals("summary.kinds names 'sms', which is no kind — the kinds are call, ad, wifi", refused.getMessage());
    }

    @Test
    void what_a_kind_reads_is_said_in_one_line() {
        assertEquals("call = service groups 10, 11 and every group no kind names", TenantKinds.readsOf(KnownKinds.byWord("call")));
        assertEquals("ad = service group 30", TenantKinds.readsOf(KnownKinds.byWord("ad")));
        assertEquals("wifi = service group 101", TenantKinds.readsOf(KnownKinds.byWord("wifi")));
    }
}
