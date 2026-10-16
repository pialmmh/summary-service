package com.telcobright.summary.summarybeans.call;

import com.telcobright.summary.summarybeans.call.model.CallSummary;
import com.telcobright.summary.testkit.CdrTestSupport;
import com.telcobright.summary.testkit.WifiBeansTestSupport;
import com.telcobright.summary.testkit.WifiSessionTestSupport;
import com.telcobright.summary.testkit.WifiSessionTestSupport.Session;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SS-0007 item 1: the shared {@code Cdr} now reads {@code InMgwId} (the BRAS id) — and the CALL beans' keys are untouched
 * by it: the voice tables of every group make the same rows whatever BRAS a record names, or none. Only the WiFi session
 * sums key by the BRAS ({@code tup_gw}).
 */
class CallBeansDoNotKeyByTheBrasTest {

    @Test
    void the_101_call_beans_make_the_same_rows_whatever_bras_the_sessions_name() {
        List<Session> oneBras = WifiBeansTestSupport.knownHour();
        List<Session> threeWays = WifiBeansTestSupport.knownHour();
        for (int i = 0; i < threeWays.size(); i++) {
            threeWays.get(i).gateway(i % 3 == 0 ? "bras-1" : i % 3 == 1 ? "bras-2" : null);
        }

        for (var bean : List.of(WifiSessionTestSupport.daily101(), WifiSessionTestSupport.hourly101())) {
            List<String> named = rowsOf(WifiSessionTestSupport.rollup(bean, oneBras));
            List<String> split = rowsOf(WifiSessionTestSupport.rollup(bean, threeWays));

            assertEquals(named, split, bean.table() + ": the same rows, key for key and sum for sum");
            assertEquals(4, named.size(), bean.table() + ": mirpur-10's minutes, money and uncosted zero-second rows, uttara-7's free row");
        }
    }

    @Test
    void a_voice_record_that_names_a_bras_keys_as_the_one_that_names_none() {
        LocalDateTime t = CdrTestSupport.at(2026, 10, 17, 9, 0);
        String plain = "[" + CdrTestSupport.entryJson(CdrTestSupport.sg10Entry(t)) + "]";
        String named = plain.replace("\"inMgwId\":\"\"", "\"inMgwId\":\"bras-9\"");
        assertTrue(named.contains("\"inMgwId\":\"bras-9\""), "the record named a BRAS: " + named);

        List<String> rowsPlain = rowsOf(CdrTestSupport.dailyBean().buildBatch(plain.getBytes(StandardCharsets.UTF_8)));
        List<String> rowsNamed = rowsOf(CdrTestSupport.dailyBean().buildBatch(named.getBytes(StandardCharsets.UTF_8)));

        assertEquals(rowsPlain, rowsNamed);
    }

    /** Every row as its INSERT values (every key and every sum), in a stable order. */
    private static List<String> rowsOf(Collection<CallSummary> rows) {
        return rows.stream().map(CallSummary::insertValues).sorted().toList();
    }
}
