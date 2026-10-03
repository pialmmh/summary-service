package com.telcobright.summary.summarybeans.ad;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.telcobright.summary.summarybeans.ad.internal.AdSummaryBean;
import com.telcobright.summary.summarybeans.ad.internal.AdTestSupport;
import com.telcobright.summary.summarybeans.ad.model.AdSummary;
import com.telcobright.summary.testkit.BillingStandIn;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * S1's "done when": the design's sample message (billing-core's brief §4 — one view, two tiers: the reseller
 * {@code res_44}'s client pays 0.50, the reseller pays the operator 0.40), mediated as billing-core's brief says,
 * gives the {@code sum_ad_*} rows stream X's tests expected for the same view: one per tier, the tier's own
 * tenant, payer and charge, the same view on both. Each tier's row is built from THAT tier's outbox.
 *
 * <p>The sample is kept as it is written in the brief ({@code ad/sample-view-two-tiers.json}). There the ROOT
 * record's meta data is short ({@code campaignId, levelIndex, partnerName, reserveRef}). The switch writes more:
 * seed-callflow calls ad-sphere's {@code fillCdr} for every tier, so every tier's record carries the view's facts.
 * The first test uses the message as the switch sends it; the second shows what the short form would give.
 */
class AdSampleMessageTest {

    private static final String SAMPLE = "ad/sample-view-two-tiers.json";
    private static final ObjectMapper JSON = new ObjectMapper();   // only re-writes the meta data text; no amount passes through it

    @Test
    void the_sample_view_gives_one_row_per_tier_schema_with_that_tiers_payer_and_charge() {
        Map<String, List<String>> outbox = BillingStandIn.outboxEntriesByTier(asTheSwitchSendsIt(BillingStandIn.wireMessage(SAMPLE)));

        assertEquals(List.of("res_44", "btcl"), List.copyOf(outbox.keySet()), "one outbox entry in EACH tier's schema");
        assertEquals("('res_44',1,12,'7001','dhaka-north','mirpur-10','captive','image','done','2026-10-02 00:00:00',1,1,1,1,0,10,0.50,0)",
                oneRow(AdTestSupport.dailyBean(), outbox, "res_44").insertValues(), "the advertiser's tier: partner 1 pays 0.50");
        assertEquals("('btcl',44,12,'7001','dhaka-north','mirpur-10','captive','image','done','2026-10-02 00:00:00',1,1,1,1,0,10,0.40,0)",
                oneRow(AdTestSupport.dailyBean(), outbox, "btcl").insertValues(), "the operator's tier: the reseller (partner 44) pays 0.40");
        assertEquals("('res_44',1,12,'7001','dhaka-north','mirpur-10','captive','image','done','2026-10-02 21:00:00',1,1,1,1,0,10,0.50,0)",
                oneRow(AdTestSupport.hourlyBean(), outbox, "res_44").insertValues(), "the hour of 21:14:03");
        assertEquals("('btcl',44,12,'7001','dhaka-north','mirpur-10','captive','image','done','2026-10-02 21:00:00',1,1,1,1,0,10,0.40,0)",
                oneRow(AdTestSupport.hourlyBean(), outbox, "btcl").insertValues());
    }

    @Test
    void the_sample_as_the_brief_writes_it_leaves_the_root_tiers_row_without_the_views_facts() {
        Map<String, List<String>> outbox = BillingStandIn.outboxEntriesByTier(BillingStandIn.wireMessage(SAMPLE));

        assertEquals("('res_44',1,12,'7001','dhaka-north','mirpur-10','captive','image','done','2026-10-02 00:00:00',1,1,1,1,0,10,0.50,0)",
                oneRow(AdTestSupport.dailyBean(), outbox, "res_44").insertValues(), "the leaf's record is whole in the brief");
        assertEquals("('btcl',44,12,'7001','','','','image','done','2026-10-02 00:00:00',1,1,0,0,0,10,0.40,0)",
                oneRow(AdTestSupport.dailyBean(), outbox, "btcl").insertValues(),
                "a root record with only campaignId in its meta data: no zone, no site, no app, completed 0, credited 0");
    }

    @Test
    void a_tier_of_the_sample_that_pays_from_a_package_counts_units_and_the_tier_above_still_counts_money() {
        // the advertiser pays the reseller 10 seconds of its package (TF_s); the reseller still pays the operator 0.40 BDT
        ArrayNode message = asTheSwitchSendsIt(BillingStandIn.wireMessage(SAMPLE));
        ObjectNode leaf = (ObjectNode) message.get(0);
        leaf.put("inPartnerUom", "TF_s").put("inPartnerCost", 0).put("packageAmount", 10);

        Map<String, List<String>> outbox = BillingStandIn.outboxEntriesByTier(message);

        assertEquals("('res_44',1,12,'7001','dhaka-north','mirpur-10','captive','image','done','2026-10-02 00:00:00',1,1,1,1,0,10,0,10)",
                oneRow(AdTestSupport.dailyBean(), outbox, "res_44").insertValues(), "money 0, units 10 — never 10 in the money column");
        assertEquals("('btcl',44,12,'7001','dhaka-north','mirpur-10','captive','image','done','2026-10-02 00:00:00',1,1,1,1,0,10,0.40,0)",
                oneRow(AdTestSupport.dailyBean(), outbox, "btcl").insertValues());
    }

    @Test
    void a_refused_view_of_the_wire_is_one_failed_row_with_money_zero_and_no_units() {
        // the brief: one record on the entry tenant — answerTime null, durationSec 0, its cause, the amounts 0,
        // inPartnerId the tenant's operator partner; the producer sends no unit, no rate, no prefix, no account
        ArrayNode message = BillingStandIn.wireMessage(SAMPLE);
        ObjectNode refused = (ObjectNode) message.get(1);
        message.remove(0);
        refused.remove(List.of("answerTime", "inPartnerUom", "callRatePerMinBDT", "matchPrefixCustomer", "idPackageAccount", "incomingRoute"));
        refused.put("hangupCause", "NO_RULE").put("durationSec", 0).put("inPartnerCost", 0).put("packageAmount", 0).put("inPartnerId", 1)
                .put("originatingCalledNumber", "dhaka-north").put("terminatingCalledNumber", "dhaka-north")
                .put("additionalMetaData", "{\"app\":\"captive\",\"zone\":\"dhaka-north\",\"completed\":false,\"credited\":false,\"fallback\":false}");

        Map<String, List<String>> outbox = BillingStandIn.outboxEntriesByTier(message);

        assertEquals(List.of("btcl"), List.copyOf(outbox.keySet()), "one record, on the entry tenant");
        assertEquals("('btcl',1,0,'dhaka-north','dhaka-north','','captive','image','failed','2026-10-02 00:00:00',1,0,0,0,1,0,0,0)",
                oneRow(AdTestSupport.dailyBean(), outbox, "btcl").insertValues(), "1 view, failed, never shown, money 0, units 0");
    }

    private static AdSummary oneRow(AdSummaryBean bean, Map<String, List<String>> outbox, String tier) {
        List<AdSummary> rows = bean.buildBatch(AdTestSupport.batchJson(outbox.get(tier)), tier);
        assertEquals(1, rows.size(), "one record in " + tier + "'s outbox, one row");
        return rows.get(0);
    }

    /**
     * What the switch really publishes: {@code CallCdr.sealed → fillCdr} runs for EVERY tier, so the view's facts
     * of the leaf's meta data are on the root's too; only the tier's own notes (level, partner name, reserve) differ.
     */
    private static ArrayNode asTheSwitchSendsIt(ArrayNode briefSample) {
        try {
            ObjectNode leafFacts = (ObjectNode) JSON.readTree(briefSample.get(0).get("additionalMetaData").asText());
            for (int tier = 1; tier < briefSample.size(); tier++) {
                ObjectNode record = (ObjectNode) briefSample.get(tier);
                ObjectNode own = (ObjectNode) JSON.readTree(record.get("additionalMetaData").asText());
                ObjectNode whole = leafFacts.deepCopy();
                whole.remove(List.of("balanceBefore", "balanceAfter"));
                whole.setAll(own);                                   // the tier's own notes win
                record.put("additionalMetaData", JSON.writeValueAsString(whole));
            }
            return briefSample;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
