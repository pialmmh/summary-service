package com.telcobright.summary.summarybeans.ad.model;

/**
 * The view's own facts the ad summary reads from the JSON object in {@code Cdr.AdditionalMetaData} — written by
 * the switch (ad-sphere's {@code fillCdr}) into EVERY tier's record: the campaign and the CONTENT that was shown
 * (a campaign holds several: the picture or the clip, by its id), where the view played ({@code zone},
 * {@code site}), the calling application, and how it ended ({@code completed} — the required seconds were
 * watched; {@code credited} — the free session followed). A fact the record does not carry is absent here:
 * campaign 0, an empty text (the {@code contentId} of a refused view, or of a house ad with no content),
 * {@code false}.
 */
public record AdViewFacts(int campaignId, String contentId, String zone, String site, String app, boolean completed, boolean credited) {

    /** A record with no meta data, or one that is not a JSON object. */
    public static final AdViewFacts NONE = new AdViewFacts(0, "", "", "", "", false, false);
}
