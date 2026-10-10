package com.telcobright.summary.summarybeans.call.internal;

import com.telcobright.summary.bean.spi.SqlDialect;
import com.telcobright.summary.bean.spi.TableDdl;
import com.telcobright.summary.testkit.WifiSessionTestSupport;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ARCH-0077-S item 2: the pre-provisioned voice table set with suffix 101 is made EXACTLY as the other suffixes are —
 * the shipped MySQL reference copy ({@code db/sum_voice.provisional.sql}) lists {@code sum_voice_day_101} and
 * {@code sum_voice_hr_101} as copies of the template, and what the service renders for the 101 beans itself
 * ({@link SumVoiceDdl}, at the bean's first use, on either engine) carries the template's columns: the same names, in
 * the same order, with the same types and widths. The file and the rendered DDL are two copies of one table; this
 * test keeps them equal, as the ad's reader-contract test keeps {@code db/postgres/sum_ad.sql}.
 */
class SumVoiceProvisionalFileTest {

    private static final String TEMPLATE = "sum_voice_day_03";

    @Test
    void the_reference_copy_lists_the_101_pair_as_copies_of_the_template() throws IOException {
        String file = resource("db/sum_voice.provisional.sql").replaceAll("--[^\\n]*", "");

        assertTrue(file.contains("CREATE TABLE IF NOT EXISTS sum_voice_day_101 LIKE " + TEMPLATE + ";"), "the day table of suffix 101");
        assertTrue(file.contains("CREATE TABLE IF NOT EXISTS sum_voice_hr_101  LIKE " + TEMPLATE + ";"), "the hour table of suffix 101");
    }

    @Test
    void what_the_service_renders_for_the_101_tables_has_the_templates_columns_in_its_order_with_its_types() throws IOException {
        List<String> template = columnsOf(createOf(resource("db/sum_voice.provisional.sql"), TEMPLATE));

        for (String table : new String[] {WifiSessionTestSupport.DAY_TABLE, WifiSessionTestSupport.HOUR_TABLE}) {
            List<String> rendered = columnsOf(TableDdl.mysql(SumVoiceDdl.table(table)));

            assertEquals(template, rendered, table + ": column for column what the reference copy declares");
            assertEquals(48, rendered.size(), "id and the 47 data columns");
        }
    }

    @Test
    void the_101_tables_are_the_same_table_as_the_30_set_on_both_engines() {
        for (SqlDialect engine : SqlDialect.values()) {
            String of30 = String.join("\n", TableDdl.createIfAbsent(SumVoiceDdl.table("sum_voice_day_30"), engine));
            String of101 = String.join("\n", TableDdl.createIfAbsent(SumVoiceDdl.table("sum_voice_day_101"), engine));

            assertEquals(of30.replace("sum_voice_day_30", "sum_voice_day_101"), of101, engine + ": one description, another name");
        }
    }

    /** The CREATE TABLE statement of {@code table} in {@code sql}, up to its matching close (MySQL's partition clause follows it). */
    private static String createOf(String sql, String table) {
        String bare = sql.replaceAll("--[^\\n]*", "");
        int start = bare.indexOf("CREATE TABLE IF NOT EXISTS " + table + " (");
        assertTrue(start >= 0, table + " is in the file");
        return bare.substring(start);
    }

    /** {@code name TYPE} of every column of a CREATE TABLE, in order; keys, indexes and the clause after the list are not columns. */
    private static List<String> columnsOf(String createTable) {
        int open = createTable.indexOf('(');
        int depth = 0;
        int close = -1;
        for (int i = open; i < createTable.length(); i++) {
            if (createTable.charAt(i) == '(') depth++;
            if (createTable.charAt(i) == ')' && --depth == 0) {
                close = i;
                break;
            }
        }
        List<String> columns = new ArrayList<>();
        for (String part : createTable.substring(open + 1, close).split(",(?![^()]*\\))")) {
            String line = part.trim();
            if (line.isEmpty() || line.matches("(?is)^(PRIMARY KEY|KEY|UNIQUE|INDEX|CONSTRAINT).*")) continue;
            Matcher column = Pattern.compile("(?s)^(\\w+)\\s+([A-Za-z]+(?:\\(\\d+(?:,\\s*\\d+)?\\))?).*$").matcher(line);
            assertTrue(column.matches(), "a column line: " + line);
            columns.add(column.group(1) + " " + column.group(2).toUpperCase().replace("INTEGER", "INT").replace(" ", ""));
        }
        return columns;
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = SumVoiceProvisionalFileTest.class.getClassLoader().getResourceAsStream(name)) {
            assertTrue(in != null, name + " is shipped in the jar");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
