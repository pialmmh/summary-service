package com.telcobright.summary.summarybeans.ad;

import com.telcobright.summary.bean.spi.SqlDialect;
import com.telcobright.summary.summarybeans.ad.internal.AdTestSupport;
import com.telcobright.summary.summarybeans.ad.model.AdSummary;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Brief S2: the tables keep their columns, because ad-sphere's summary road reads them
 * ({@code JdbcCdrReader.summary}: {@code SELECT … FROM <tier>.sum_ad_day_30 | sum_ad_hr_30}). What that reader
 * selects is held here word for word; a column of it that left the entity, the MySQL DDL or the PostgreSQL DDL
 * turns this red before ad-sphere's screen turns empty. The reader needs no change but the schema it names.
 */
class AdReaderContractTest {

    /** ad-sphere {@code JdbcCdrReader.summary}'s select list (ad-sphere main 92f3735), in its order. */
    private static final List<String> READER_SELECTS = List.of("tup_starttime", "tup_tenant", "tup_partnerid", "tup_campaignid",
            "tup_rulecode", "tup_zone", "tup_site", "tup_app", "tup_mediakind", "tup_outcome",
            "views", "shown", "completed", "credited", "failed", "watchedsec", "chargedamount");
    /** What it filters and orders by. */
    private static final List<String> READER_FILTERS = List.of("tup_partnerid", "tup_starttime", "tup_campaignid");

    @Test
    void the_insert_columns_are_the_seventeen_the_reader_knows_in_their_order_and_the_ruled_units_measure_last() {
        assertEquals("tup_tenant,tup_partnerid,tup_campaignid,tup_rulecode,tup_zone,tup_site,tup_app,tup_mediakind,tup_outcome,"
                        + "tup_starttime,views,shown,completed,credited,failed,watchedsec,chargedamount,chargedunits",
                AdSummary.INSERT_COLUMNS, "a renamed, removed or moved column breaks ad-sphere's reader (and every existing table)");
    }

    @Test
    void every_column_the_reader_selects_or_filters_by_is_a_column_of_the_entity() {
        List<String> columns = Arrays.asList(AdSummary.INSERT_COLUMNS.split(","));
        for (String column : READER_SELECTS) assertTrue(columns.contains(column), "the reader selects " + column);
        for (String column : READER_FILTERS) assertTrue(columns.contains(column), "the reader filters or orders by " + column);
    }

    @Test
    void the_tables_are_the_two_the_reader_names() {
        assertEquals("sum_ad_day_30", AdTestSupport.dailyBean().table(), "JdbcCdrReader.SUM_DAY_TABLE");
        assertEquals("sum_ad_hr_30", AdTestSupport.hourlyBean().table(), "JdbcCdrReader.SUM_HOUR_TABLE");
    }

    @Test
    void the_mysql_ddl_declares_every_insert_column_in_order_after_the_id() {
        for (String ddl : List.of(AdTestSupport.dailyBean().tableDdl(), AdTestSupport.hourlyBean().tableDdl())) {
            List<String> declared = new ArrayList<>(columnsOf(ddl).keySet());
            assertEquals("id", declared.get(0));
            assertEquals(Arrays.asList(AdSummary.INSERT_COLUMNS.split(",")), declared.subList(1, declared.size()));
        }
    }

    @Test
    void the_postgres_reference_ddl_declares_the_same_columns_in_the_same_order_for_both_tables() throws IOException {
        Map<String, Map<String, String>> tables = tablesOf(resource("db/postgres/sum_ad.sql"));

        assertEquals(List.of("sum_ad_day_30", "sum_ad_hr_30"), List.copyOf(tables.keySet()), "one pair, the reader's two names");
        for (Map<String, String> columns : tables.values()) {
            List<String> declared = new ArrayList<>(columns.keySet());
            assertEquals("id", declared.get(0));
            assertEquals(Arrays.asList(AdSummary.INSERT_COLUMNS.split(",")), declared.subList(1, declared.size()));
            for (String column : READER_SELECTS) assertNotNull(columns.get(column), "the reader selects " + column);
        }
    }

    @Test
    void the_two_engines_give_a_column_the_same_shape() throws IOException {
        Map<String, String> mysql = columnsOf(AdTestSupport.dailyBean().tableDdl());
        Map<String, String> postgres = tablesOf(resource("db/postgres/sum_ad.sql")).get("sum_ad_day_30");

        for (String column : AdSummary.INSERT_COLUMNS.split(",")) {
            assertEquals(portable(mysql.get(column)), portable(postgres.get(column)), column + " is the same type and width on both engines");
        }
    }

    @Test
    void the_postgres_ddl_the_service_runs_is_the_reference_file_statement_for_statement() throws IOException {
        // db/postgres/sum_ad.sql is what ad-sphere's side was given (brief §4.2); the service renders its own DDL
        // from the bean's table description — the two must be one text, or the file lies
        List<String> rendered = new ArrayList<>();
        rendered.addAll(AdTestSupport.dailyBean().tableDdl(SqlDialect.POSTGRESQL));
        rendered.addAll(AdTestSupport.hourlyBean().tableDdl(SqlDialect.POSTGRESQL));

        List<String> reference = new ArrayList<>();
        for (String statement : resource("db/postgres/sum_ad.sql").replaceAll("--[^\\n]*", "").split(";")) {
            if (!statement.isBlank()) reference.add(oneLine(statement));
        }

        assertEquals(rendered.stream().map(AdReaderContractTest::oneLine).toList(), reference);
    }

    /** A statement with its layout taken out: one space between words, none around a comma or inside a parenthesis. */
    private static String oneLine(String sql) {
        return sql.replaceAll("\\s+", " ").replaceAll("\\s*,\\s*", ",").replaceAll("\\(\\s+", "(").replaceAll("\\s+\\)", ")").trim();
    }

    // ---- reading a CREATE TABLE ----

    /** MySQL's and PostgreSQL's spellings of one type, brought to one word; widths and precisions stay. */
    private static String portable(String type) {
        return type.toUpperCase()
                .replace("DATETIME", "TIMESTAMP").replace("DECIMAL", "NUMERIC").replaceAll("\\bINT\\b", "INTEGER")
                .replace("AUTO_INCREMENT", "").replace("GENERATED BY DEFAULT AS IDENTITY", "").replaceAll("\\s+", " ").trim();
    }


    private static Map<String, Map<String, String>> tablesOf(String sql) {
        Map<String, Map<String, String>> tables = new LinkedHashMap<>();
        Matcher create = Pattern.compile("CREATE TABLE IF NOT EXISTS (\\w+) \\((.*?)\\n\\);", Pattern.DOTALL).matcher(sql.replaceAll("--[^\\n]*", ""));
        while (create.find()) {
            tables.put(create.group(1), columnsOf("CREATE TABLE IF NOT EXISTS " + create.group(1) + " (" + create.group(2) + ")"));
        }
        return tables;
    }

    /** Column name → its declaration, in order; keys and indexes are not columns. */
    private static Map<String, String> columnsOf(String createTable) {
        String body = columnList(createTable);
        Map<String, String> columns = new LinkedHashMap<>();
        for (String part : body.split(",(?![^()]*\\))")) {
            String line = part.trim();
            if (line.isEmpty() || line.matches("(?is)^(PRIMARY KEY|KEY|UNIQUE|INDEX|CONSTRAINT|\\)).*")) {
                continue;
            }
            Matcher column = Pattern.compile("(?s)^(\\w+)\\s+(.*?)(\\s+NOT NULL.*)?$").matcher(line);
            if (column.matches()) {
                columns.put(column.group(1), column.group(2).trim());
            }
        }
        return columns;
    }

    /** The text between the table's opening parenthesis and its matching close (MySQL's partition clause comes after it). */
    private static String columnList(String createTable) {
        int open = createTable.indexOf('(');
        int depth = 0;
        for (int i = open; i < createTable.length(); i++) {
            char c = createTable.charAt(i);
            if (c == '(') depth++;
            if (c == ')' && --depth == 0) return createTable.substring(open + 1, i);
        }
        return createTable.substring(open + 1);
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = AdReaderContractTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(in, "resource " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
