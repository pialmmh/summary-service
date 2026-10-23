package com.telcobright.summary.tenancy.internal;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The tree prime-context serves ({@code POST /get-specific-tenant-root}): a tenant is {@code dbName} +
 * {@code children} (a map) + its whole {@code context}. Only the schemas are read, a node before its children.
 */
class TenantTreeParserTest {

    /** The shape of rtc-domain's {@code Tenant}: dbName, parent, children (name → tenant), context. */
    static String tree() {
        return "{\"dbName\":\"btcl\",\"parent\":null,"
                + "\"context\":{\"partners\":{\"44\":{\"idPartner\":44,\"partnerName\":\"R1\",\"dbName\":\"not_a_tenant\"}},"
                + "\"children\":{\"also_not_a_tenant\":{\"dbName\":\"fake\"}},\"rates\":[[1,2,3],{\"deep\":{\"dbName\":\"nor_this\"}}],"
                // a whole tenant-SHAPED object directly inside the context (its own dbName, its own children): still data
                + "\"self\":{\"dbName\":\"shaped_like_a_tenant\",\"children\":{\"its_child\":{\"dbName\":\"its_child\"}}}},"
                + "\"children\":{"
                + "\"res_44\":{\"dbName\":\"res_44\",\"parent\":\"btcl\",\"context\":{\"partners\":{}},"
                + "\"children\":{\"res_44_7\":{\"dbName\":\"res_44_7\",\"parent\":\"res_44\",\"children\":{},\"context\":null}}},"
                + "\"res_45\":{\"children\":{},\"context\":{},\"parent\":\"btcl\",\"dbName\":\"res_45\"}"
                + "}}";
    }

    private static List<String> schemasOf(String json) throws IOException {
        return TenantTreeParser.schemasOf(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void every_tier_of_the_tree_is_a_schema_the_root_first_a_node_before_its_children() throws IOException {
        assertEquals(List.of("btcl", "res_44", "res_44_7", "res_45"), schemasOf(tree()));
    }

    @Test
    void a_dbname_or_children_inside_a_context_is_not_a_tenant() throws IOException {
        List<String> schemas = schemasOf(tree());

        assertEquals(4, schemas.size(), "the four tiers and nothing else: " + schemas);
        for (String inContext : List.of("not_a_tenant", "also_not_a_tenant", "fake", "nor_this", "shaped_like_a_tenant", "its_child")) {
            assertEquals(false, schemas.contains(inContext), inContext + " is data of a tier's context, skipped unparsed");
        }
    }

    @Test
    void a_root_with_no_reseller_is_one_schema() throws IOException {
        assertEquals(List.of("btcl"), schemasOf("{\"dbName\":\"btcl\",\"children\":{}}"));
        assertEquals(List.of("btcl"), schemasOf("{\"dbName\":\"btcl\"}"));
        assertEquals(List.of("btcl"), schemasOf("{\"children\":null,\"dbName\":\"btcl\"}"));
    }

    @Test
    void the_order_of_the_fields_does_not_matter_the_root_is_still_first() throws IOException {
        assertEquals(List.of("btcl", "res_1"), schemasOf("{\"children\":{\"res_1\":{\"dbName\":\"res_1\"}},\"dbName\":\"btcl\"}"));
    }

    @Test
    void an_answer_that_is_not_a_tree_is_refused() {
        assertThrows(IOException.class, () -> schemasOf("[]"));
        assertThrows(IOException.class, () -> schemasOf("\"oops\""));
        assertThrows(IOException.class, () -> schemasOf("{\"children\":{}}"), "a root that names no schema");
        assertThrows(IOException.class, () -> schemasOf("{\"dbName\":\"btcl\",\"children\":{\"res_1\":{\"dbName\":"), "cut in the middle");
    }
}
