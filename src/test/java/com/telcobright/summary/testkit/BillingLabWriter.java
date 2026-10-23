package com.telcobright.summary.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.telcobright.summary.outbox.internal.OutboxCodec;
import com.telcobright.summary.registry.internal.StartEndpoints;
import com.telcobright.summary.summarybeans.ad.internal.AdTestSupport;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * A LAB tool (tools/lab/tree-e2e.sh): writes ONE message of the ratified wire into a PostgreSQL lab the way
 * billing-core writes it, until its own branch can be run against the lab — per tier, as the role
 * {@code billing_core}, in the tier's own schema:
 *
 * <ol>
 *   <li>its batch lock: {@code pg_advisory_lock} by SCHEMA, session level, taken BEFORE the insert and held ACROSS
 *       the commit (its BC-0001 W13) — so outbox ids become visible in commit order;</li>
 *   <li>ONE transaction: the {@code cdr} row (its 110-column table; the columns an ad view uses, its BC-0002 §2),
 *       the ONE customer {@code acc_chargeable}, and ONE {@code summary_affected} row — entity {@code cdr},
 *       {@code op add}, the blob {@code base64(gzip([{Cdr, Chargeables}]))};</li>
 *   <li>after the commit, the ping on Kafka: {@code {"tenant": <schema>, "entity": "cdr", "rows": 1}}.</li>
 * </ol>
 *
 * It mediates nothing (no checklist, no idempotency): the rows of a view that passed. It refuses any address that
 * is not this machine — a lab never dials a box.
 *
 * <pre>
 * BillingLabWriter &lt;jdbc url&gt; &lt;kafka brokers&gt; &lt;ping topic&gt; &lt;session id&gt; [wireTenant=schema ...]
 * </pre>
 * The message is the brief's sample ({@code ad/sample-view-two-tiers.json}) with the switch's facts on every tier;
 * {@code session id} replaces its call id (one view = one id in every tier); {@code res_44=res_45} writes the
 * leaf's record into another reseller's schema.
 */
public final class BillingLabWriter {

    private static final DateTimeFormatter WIRE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private BillingLabWriter() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("usage: BillingLabWriter <jdbc url> <kafka brokers> <ping topic> <session id> [wireTenant=schema ...]");
            System.exit(2);
        }
        String jdbcUrl = args[0], brokers = args[1], pingTopic = args[2], sessionId = args[3];
        for (String endpoint : List.of(jdbcUrl, brokers)) {
            List<String> hosts = StartEndpoints.hostsOf(endpoint);
            if (hosts.isEmpty() || !hosts.stream().allMatch(StartEndpoints::isLoopback)) {
                System.err.println("REFUSED: " + endpoint + " is not on this machine — a lab never dials a box");
                System.exit(3);
            }
        }
        Map<String, String> schemaOf = new HashMap<>();
        for (int i = 4; i < args.length; i++) {
            String[] pair = args[i].split("=");
            schemaOf.put(pair[0], pair[1]);
        }
        ArrayNode message = sampleAsTheSwitchSendsIt(sessionId);
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers);
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        try (KafkaProducer<String, String> pings = new KafkaProducer<>(p)) {
            for (JsonNode record : message) {
                String schema = schemaOf.getOrDefault(record.get("tenant").asText(), record.get("tenant").asText());
                long outboxId = writeTier(jdbcUrl, schema, record);
                String ping = "{\"tenant\":\"" + schema + "\",\"entity\":\"cdr\",\"rows\":1}";
                pings.send(new ProducerRecord<>(pingTopic, null, ping)).get();
                System.out.println("billing-core (lab): " + schema + " cdr + acc_chargeable + summary_affected id=" + outboxId + " committed; ping " + ping);
            }
        }
    }

    /** The brief's sample with the view's facts on every tier (the switch calls fillCdr for each), under a session id of its own. */
    static ArrayNode sampleAsTheSwitchSendsIt(String sessionId) throws Exception {
        ArrayNode message = BillingStandIn.wireMessage("ad/sample-view-two-tiers.json");
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        ObjectNode leafFacts = (ObjectNode) json.readTree(message.get(0).get("additionalMetaData").asText());
        for (int tier = 0; tier < message.size(); tier++) {
            ObjectNode record = (ObjectNode) message.get(tier);
            ObjectNode whole = leafFacts.deepCopy();
            whole.remove(List.of("balanceBefore", "balanceAfter"));
            whole.setAll((ObjectNode) json.readTree(record.get("additionalMetaData").asText()));
            record.put("additionalMetaData", json.writeValueAsString(whole));
            record.put("callId", sessionId).put("channelCallUuid", sessionId);
        }
        return message;
    }

    /** One tier's batch of one record: lock, one transaction, commit, unlock. Returns the outbox row's id. */
    static long writeTier(String jdbcUrl, String schema, JsonNode wire) throws SQLException {
        if (!schema.matches("[a-z_][a-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("'" + schema + "' is not a schema's name");
        }
        try (Connection c = DriverManager.getConnection(jdbcUrl, PgLab.BILLING_CORE, "")) {
            try (Statement st = c.createStatement()) {
                st.execute("SET search_path TO " + schema);
                st.execute("SELECT pg_advisory_lock(hashtext('billing_batch_" + schema + "'))");     // session level: held across the commit
            }
            try {
                c.setAutoCommit(false);
                long idCall = nextId(c, "select coalesce(max(IdCall), 0) + 1 from (select IdCall from cdr union all select IdCall from cdrerror) calls");
                insertCdr(c, idCall, wire);
                insertChargeable(c, nextId(c, "select coalesce(max(id), 0) + 1 from acc_chargeable"), idCall, wire);
                long outboxId;
                try (PreparedStatement ps = c.prepareStatement("insert into summary_affected (entity_type, op, data) values ('cdr', 'add', ?) returning id")) {
                    ps.setString(1, OutboxCodec.encode(AdTestSupport.batchJson(List.of(BillingStandIn.entryOf(wire).toString()))));
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        outboxId = rs.getLong(1);
                    }
                }
                c.commit();
                return outboxId;
            } finally {
                c.setAutoCommit(true);
                try (Statement st = c.createStatement()) {
                    st.execute("SELECT pg_advisory_unlock(hashtext('billing_batch_" + schema + "'))");
                }
            }
        }
    }

    private static void insertCdr(Connection c, long idCall, JsonNode w) throws SQLException {
        String sql = "insert into cdr (SwitchId, IdCall, SequenceNumber, FileName, ServiceGroup, IncomingRoute, OriginatingIP, OriginatingCalledNumber, "
                + "TerminatingCalledNumber, OriginatingCallingNumber, TerminatingCallingNumber, PrePaid, DurationSec, EndTime, ConnectTime, AnswerTime, "
                + "ChargingStatus, PDD, AreaCodeOrLata, OutgoingRoute, TerminatingIP, StartTime, InPartnerId, CustomerRate, OutPartnerId, MatchedPrefixCustomer, "
                + "InPartnerCost, Codec, RoundedDuration, Duration1, UniqueBillId, AdditionalMetaData, SignalingStartTime, ResellerHierarchy, ChannelCallUuid, "
                + "HangupCause, InPartnerUom, IdPackageAccount, PackageAmount) values (0,?,?,'kafka:cdr',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 0;
            ps.setLong(++i, idCall);
            ps.setLong(++i, w.path("sequenceNo").asLong());
            ps.setInt(++i, w.path("serviceGroup").asInt());
            ps.setString(++i, text(w, "incomingRoute"));
            ps.setString(++i, text(w, "callerIp"));
            ps.setString(++i, text(w, "originatingCalledNumber"));
            ps.setString(++i, text(w, "terminatingCalledNumber"));
            ps.setString(++i, text(w, "originatingCallingNumber"));
            ps.setString(++i, text(w, "terminatingCallingNumber"));
            ps.setObject(++i, w.hasNonNull("isPrepaid") ? w.get("isPrepaid").asInt() : null);
            ps.setBigDecimal(++i, decimal(w, "durationSec"));
            ps.setTimestamp(++i, time(w, "endTime"));
            ps.setTimestamp(++i, time(w, "answerTime"));
            ps.setTimestamp(++i, time(w, "answerTime"));
            ps.setInt(++i, w.hasNonNull("answerTime") ? 1 : 0);
            ps.setObject(++i, w.hasNonNull("pdd") ? (float) w.get("pdd").asDouble() : null);
            ps.setString(++i, text(w, "hangupCause"));
            ps.setString(++i, text(w, "outgoingRoute"));
            ps.setString(++i, text(w, "receiverIp"));
            ps.setTimestamp(++i, time(w, "startTime"));
            ps.setObject(++i, w.hasNonNull("inPartnerId") ? w.get("inPartnerId").asInt() : null);
            ps.setBigDecimal(++i, w.hasNonNull("callRatePerMinBDT") ? decimal(w, "callRatePerMinBDT") : null);
            ps.setObject(++i, w.hasNonNull("outPartnerId") ? w.get("outPartnerId").asInt() : null);
            ps.setString(++i, text(w, "matchPrefixCustomer"));
            ps.setBigDecimal(++i, decimal(w, "inPartnerCost"));
            ps.setString(++i, text(w, "channelReadCodecName"));
            ps.setBigDecimal(++i, decimal(w, "durationSec"));
            ps.setBigDecimal(++i, decimal(w, "durationSec"));
            ps.setString(++i, text(w, "callId"));
            ps.setString(++i, text(w, "additionalMetaData"));
            ps.setTimestamp(++i, time(w, "startTime"));
            ps.setString(++i, text(w, "resellerHierarchy"));
            ps.setString(++i, text(w, "channelCallUuid"));
            ps.setString(++i, text(w, "hangupCause"));
            ps.setString(++i, text(w, "inPartnerUom"));
            ps.setObject(++i, w.hasNonNull("idPackageAccount") ? w.get("idPackageAccount").asLong() : null);
            ps.setBigDecimal(++i, decimal(w, "packageAmount"));
            ps.executeUpdate();
        }
    }

    private static void insertChargeable(Connection c, long id, long idCall, JsonNode w) throws SQLException {
        JsonNode leg = BillingStandIn.entryOf(w).get("Chargeables").get(0);
        String sql = "insert into acc_chargeable (id, uniqueBillId, idEvent, transactionTime, assignedDirection, glAccountId, servicegroup, servicefamily, ProductId, "
                + "idBilledUom, BilledAmount, Quantity, unitPriceOrCharge, Prefix, RateId, idBillingrule) values (?,?,?,?,1,0,?,?,0,?,?,?,?,?,0,0)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.setString(2, text(w, "callId"));
            ps.setLong(3, idCall);
            ps.setTimestamp(4, time(w, "startTime"));
            ps.setInt(5, leg.get("servicegroup").asInt());
            ps.setInt(6, leg.get("servicefamily").asInt());
            ps.setString(7, leg.get("idBilledUom").asText());
            ps.setBigDecimal(8, leg.get("BilledAmount").decimalValue());
            ps.setBigDecimal(9, leg.get("Quantity").decimalValue());
            ps.setBigDecimal(10, leg.hasNonNull("unitPriceOrCharge") ? leg.get("unitPriceOrCharge").decimalValue() : null);
            ps.setString(11, leg.hasNonNull("Prefix") ? leg.get("Prefix").asText() : null);
            ps.executeUpdate();
        }
    }

    private static long nextId(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String text(JsonNode w, String field) {
        return w.hasNonNull(field) ? w.get(field).asText() : null;
    }

    private static BigDecimal decimal(JsonNode w, String field) {
        return w.hasNonNull(field) ? w.get(field).decimalValue() : BigDecimal.ZERO;
    }

    private static Timestamp time(JsonNode w, String field) {
        return w.hasNonNull(field) ? Timestamp.valueOf(LocalDateTime.parse(w.get(field).asText(), WIRE_TIME)) : null;
    }
}
