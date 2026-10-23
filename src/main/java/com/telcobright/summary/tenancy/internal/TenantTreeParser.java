package com.telcobright.summary.tenancy.internal;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the tenant tree prime-context serves ({@code POST /get-specific-tenant-root}; config-manager's shape):
 *
 * <pre>
 * { "dbName": "btcl", "parent": null, "context": { … everything the switch knows of the tier … },
 *   "children": { "res_44": { "dbName": "res_44", "parent": "btcl", "context": { … }, "children": { "res_44_7": { … } } } } }
 * </pre>
 *
 * Only two things are read: a node's {@code dbName} — its schema — and its {@code children}. The {@code context}
 * (partners, rate plans, every rate) is the bulk of the payload and nothing here needs it, so the document is read
 * as a STREAM and every other field is skipped unparsed: the tree of a tenant with a million rates costs this
 * service no memory. The answer is the schemas in the order they are met — a node before its children, so the root
 * is first.
 */
final class TenantTreeParser {

    private static final JsonFactory JSON = new JsonFactory();

    private TenantTreeParser() {
    }

    static List<String> schemasOf(InputStream treeJson) throws IOException {
        List<String> schemas = new ArrayList<>();
        try (JsonParser parser = JSON.createParser(treeJson)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("the tenant tree is not a JSON object (it starts with " + parser.currentToken() + ")");
            }
            readTenant(parser, schemas);
        }
        if (schemas.isEmpty()) {
            throw new IOException("the tenant tree names no tenant (its root has no dbName)");
        }
        return schemas;
    }

    /** The parser is on a tenant's START_OBJECT; leaves it on the matching END_OBJECT. */
    private static void readTenant(JsonParser parser, List<String> schemas) throws IOException {
        int own = schemas.size();
        String dbName = null;
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            String field = parser.currentName();
            JsonToken value = parser.nextToken();
            if ("dbName".equals(field) && value == JsonToken.VALUE_STRING) {
                dbName = parser.getText();
            } else if ("children".equals(field) && value == JsonToken.START_OBJECT) {
                while (parser.nextToken() == JsonToken.FIELD_NAME) {             // each entry: "<child's dbName>": { tenant }
                    if (parser.nextToken() == JsonToken.START_OBJECT) {
                        readTenant(parser, schemas);
                    } else {
                        parser.skipChildren();
                    }
                }
            } else {
                parser.skipChildren();                                           // the context and every other field: not read
            }
        }
        if (dbName != null && !dbName.isBlank()) {
            schemas.add(own, dbName.trim());                                     // the node before its children
        }
    }
}
