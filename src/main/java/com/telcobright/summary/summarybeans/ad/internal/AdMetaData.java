package com.telcobright.summary.summarybeans.ad.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.summary.summarybeans.ad.model.AdViewFacts;

import java.io.IOException;

/**
 * Reads the view's facts out of {@code Cdr.AdditionalMetaData}: a STRING holding one JSON object (ad-is-a-call
 * §4.1). The text is the switch's, relayed by billing-core as it came — so this reader never fails a drain over
 * it: a missing, empty or malformed text, or a JSON value that is not an object, reads as {@link AdViewFacts#NONE}
 * (the view still counts, on campaign 0); a single fact of the wrong type reads as absent.
 */
final class AdMetaData {

    private AdMetaData() {
    }

    /** {@code null} when the text is not a JSON object — the caller counts those and says so once per batch. */
    static AdViewFacts parse(ObjectMapper mapper, String additionalMetaData) {
        if (additionalMetaData == null || additionalMetaData.isBlank()) {
            return AdViewFacts.NONE;
        }
        JsonNode meta;
        try {
            meta = mapper.readTree(additionalMetaData);
        } catch (IOException e) {
            return null;
        }
        if (meta == null || !meta.isObject()) {
            return null;
        }
        return new AdViewFacts(
                number(meta.get("campaignId")),
                text(meta.get("contentId")),            // a text at its source (ad_content.id); a number is read as its digits
                text(meta.get("zone")),
                text(meta.get("site")),
                text(meta.get("app")),
                flag(meta.get("completed")),
                flag(meta.get("credited")));
    }

    /** A whole number, as a JSON number or as digits in a string; anything else is 0 (no campaign). */
    private static int number(JsonNode node) {
        if (node == null || node.isNull()) {
            return 0;
        }
        if (node.isIntegralNumber() && node.canConvertToInt()) {
            return node.intValue();
        }
        if (node.isTextual()) {
            try {
                return Integer.parseInt(node.textValue().trim());
            } catch (NumberFormatException notANumber) {
                return 0;
            }
        }
        return 0;
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() || node.isContainerNode() ? "" : node.asText("");
    }

    /** {@code true} only for the JSON boolean true or the word "true". */
    private static boolean flag(JsonNode node) {
        if (node == null || node.isNull()) {
            return false;
        }
        return node.isBoolean() ? node.booleanValue() : node.isTextual() && "true".equalsIgnoreCase(node.textValue().trim());
    }
}
