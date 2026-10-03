package com.telcobright.summary.testkit;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stands in for billing-core in the tests, for service group 30 ONLY, until its branch writes real rows (N5):
 * takes ONE Kafka message of the ratified wire (ad-is-a-call §4 — a JSON array, every tier's record of one call,
 * the leaf first) and gives, per tier, the outbox blob entry billing-core's brief says it writes there:
 *
 * <ul>
 *   <li>B1 — the wire onto the {@code cdr}: {@code serviceGroup → ServiceGroup}, {@code hangupCause → HangupCause},
 *       {@code channelReadCodecName → Codec}, {@code additionalMetaData → AdditionalMetaData}, the routes, the times
 *       ({@code answerTime} may be absent: never shown);</li>
 *   <li>B3 — pre-rated: ONE customer {@code acc_chargeable} from the record — the settled money
 *       ({@code inPartnerCost}) or, when the record carries none, the units ({@code packageAmount}), the unit, the
 *       rate, the prefix; a failed view gives a chargeable of zero;</li>
 *   <li>B9 — the entry is {@code {Cdr, Chargeables}} with billing-core's own field names (its {@code cdr.java} and
 *       {@code acc_chargeable.java} public fields), a date as the array its mapper writes, nulls left out.</li>
 * </ul>
 *
 * It mediates nothing else (no validation, no idempotency): only the shape the summary side reads.
 */
public final class BillingStandIn {

    /** Exact decimals: billing-core's wire DTO holds every amount as a BigDecimal, so 0.50 stays 0.50 (never 0.5). */
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));
    private static final DateTimeFormatter WIRE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private BillingStandIn() {
    }

    /** The wire message in a test resource, parsed. */
    public static ArrayNode wireMessage(String resource) {
        try (InputStream in = BillingStandIn.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("no test resource " + resource);
            }
            return (ArrayNode) JSON.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Tier (the record's {@code tenant}) → the blob entries billing-core writes in that tier's outbox, in record order. */
    public static Map<String, List<String>> outboxEntriesByTier(JsonNode wireMessage) {
        Map<String, List<String>> byTier = new LinkedHashMap<>();
        for (JsonNode record : wireMessage) {
            byTier.computeIfAbsent(record.get("tenant").asText(), tier -> new ArrayList<>()).add(entryOf(record).toString());
        }
        return byTier;
    }

    /** One wire record → one {@code {Cdr, Chargeables:[the customer leg]}} entry. */
    public static ObjectNode entryOf(JsonNode wire) {
        ObjectNode entry = JSON.createObjectNode();
        entry.set("Cdr", cdrOf(wire));
        entry.putArray("Chargeables").add(customerChargeableOf(wire));
        return entry;
    }

    private static ObjectNode cdrOf(JsonNode wire) {
        ObjectNode cdr = JSON.createObjectNode();
        cdr.put("SwitchId", 0);
        cdr.put("SequenceNumber", wire.path("sequenceNo").asLong());
        cdr.put("FileName", "kafka:cdr");
        cdr.put("ServiceGroup", wire.path("serviceGroup").asInt());
        copyText(wire, "incomingRoute", cdr, "IncomingRoute");
        copyText(wire, "callerIp", cdr, "OriginatingIP");
        copyText(wire, "originatingCalledNumber", cdr, "OriginatingCalledNumber");
        copyText(wire, "terminatingCalledNumber", cdr, "TerminatingCalledNumber");
        copyText(wire, "originatingCallingNumber", cdr, "OriginatingCallingNumber");
        copyText(wire, "terminatingCallingNumber", cdr, "TerminatingCallingNumber");
        copyNumber(wire, "isPrepaid", cdr, "PrePaid");
        copyNumber(wire, "durationSec", cdr, "DurationSec");
        copyTime(wire, "endTime", cdr, "EndTime");
        copyTime(wire, "answerTime", cdr, "ConnectTime");
        copyTime(wire, "answerTime", cdr, "AnswerTime");
        cdr.put("ChargingStatus", wire.path("durationSec").decimalValue().signum() > 0 ? 1 : 0);
        copyNumber(wire, "pdd", cdr, "PDD");
        copyText(wire, "outgoingRoute", cdr, "OutgoingRoute");
        copyText(wire, "receiverIp", cdr, "TerminatingIP");
        copyTime(wire, "startTime", cdr, "StartTime");
        copyNumber(wire, "inPartnerId", cdr, "InPartnerId");
        copyNumber(wire, "callRatePerMinBDT", cdr, "CustomerRate");
        copyNumber(wire, "outPartnerId", cdr, "OutPartnerId");
        copyText(wire, "matchPrefixCustomer", cdr, "MatchedPrefixCustomer");
        copyNumber(wire, "inPartnerCost", cdr, "InPartnerCost");
        copyNumber(wire, "supplierCost", cdr, "OutPartnerCost");
        copyText(wire, "channelReadCodecName", cdr, "Codec");
        copyText(wire, "callId", cdr, "UniqueBillId");
        copyText(wire, "additionalMetaData", cdr, "AdditionalMetaData");
        copyTime(wire, "startTime", cdr, "SignalingStartTime");
        copyText(wire, "resellerHierarchy", cdr, "ResellerHierarchy");
        copyText(wire, "channelCallUuid", cdr, "ChannelCallUuid");
        copyText(wire, "hangupCause", cdr, "HangupCause");
        copyText(wire, "inPartnerUom", cdr, "InPartnerUom");
        copyNumber(wire, "idPackageAccount", cdr, "IdPackageAccount");
        copyNumber(wire, "packageAmount", cdr, "PackageAmount");
        return cdr;
    }

    private static ObjectNode customerChargeableOf(JsonNode wire) {
        BigDecimal money = decimal(wire, "inPartnerCost");
        BigDecimal units = decimal(wire, "packageAmount");
        ObjectNode leg = JSON.createObjectNode();
        leg.put("id", 0);
        copyText(wire, "callId", leg, "uniqueBillId");
        copyTime(wire, "startTime", leg, "transactionTime");
        leg.put("assignedDirection", 1);
        leg.put("servicegroup", wire.path("serviceGroup").asInt());
        leg.put("servicefamily", wire.path("serviceGroup").asInt());
        leg.put("ProductId", 0);
        copyText(wire, "inPartnerUom", leg, "idBilledUom");
        leg.put("BilledAmount", money.signum() != 0 ? money : units);
        leg.put("Quantity", BigDecimal.ONE);
        copyNumber(wire, "callRatePerMinBDT", leg, "unitPriceOrCharge");
        copyText(wire, "matchPrefixCustomer", leg, "Prefix");
        return leg;
    }

    private static BigDecimal decimal(JsonNode wire, String field) {
        return wire.hasNonNull(field) ? wire.get(field).decimalValue() : BigDecimal.ZERO;
    }

    private static void copyText(JsonNode from, String field, ObjectNode to, String as) {
        if (from.hasNonNull(field)) {
            to.put(as, from.get(field).asText());
        }
    }

    private static void copyNumber(JsonNode from, String field, ObjectNode to, String as) {
        if (from.hasNonNull(field)) {
            to.set(as, from.get(field));
        }
    }

    /** A wire time ({@code yyyy-MM-dd HH:mm:ss}) as the array billing-core's mapper writes for a LocalDateTime. */
    private static void copyTime(JsonNode from, String field, ObjectNode to, String as) {
        if (!from.hasNonNull(field)) {
            return;
        }
        LocalDateTime t = LocalDateTime.parse(from.get(field).asText(), WIRE_TIME);
        ArrayNode array = to.putArray(as);
        array.add(t.getYear()).add(t.getMonthValue()).add(t.getDayOfMonth()).add(t.getHour()).add(t.getMinute());
        if (t.getSecond() != 0) {
            array.add(t.getSecond());
        }
    }
}
