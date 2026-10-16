-- The WAY BACK from the WiFi session sums split by BRAS (SS-0007 item 5) — PostgreSQL. Run by tools/lab/wifi-gw-wayback.sh
-- (psql -X -q -tA -v ON_ERROR_STOP=1 -v schema=<the tier schema>) AFTER the new jar is stopped and BEFORE 3829e18's jar
-- starts on that schema.
--
-- 3829e18's jar keys sum_wifi_hr_101 / sum_wifi_day_101 by the OLD key — tup_switchid, tup_inpartnerid, tup_site, tup_zone,
-- tup_granttier, tup_category, tup_packagecode, tup_customercurrency, tup_causefamily, tup_starttime — and reloads a bucket
-- without tup_gw: two rows of one old key (the '' row and a BRAS row) stop its two WiFi session beans ("duplicate existing
-- row while populating cache", the offset STUCK). Per table, in ONE transaction:
--   1. every group of rows that share the old key is summed into its LOWEST id — sessions, sessions_connected,
--      total_duration_sec, bytes_up, bytes_dn, customercost — and its avg_session_sec derived again exactly as the bean
--      derives it (WifiSessionSummary.recomputeAverage: total_duration_sec / sessions_connected to six places, half up; 0
--      when none is connected — the division carried to 18 places first, so the one rounding is the bean's);
--   2. the other rows of each such group are deleted;
--   3. tup_gw = '' on every row (the column stays: the new jar, started again, finds its table as described).
-- A second run finds no group and no BRAS, and changes nothing. No other table is read or written.
--
-- What it prints, one line each: wayback|<table>|merged|<groups summed into their lowest id>,
-- wayback|<table>|deleted|<rows>, wayback|<table>|bras cleared|<rows>; after both commits
-- ready|<table>|<old keys held by more than one row>|<rows with a BRAS> — 0 and 0 = 3829e18's jar may start.

SET search_path TO :"schema";

-- ---- sum_wifi_day_101 ---------------------------------------------------------------------------------------------------
BEGIN;

SELECT 'wayback|sum_wifi_day_101|merged|' || count(*)
FROM (SELECT 1 FROM sum_wifi_day_101
      GROUP BY tup_switchid, tup_inpartnerid, tup_site, tup_zone, tup_granttier, tup_category, tup_packagecode,
               tup_customercurrency, tup_causefamily, tup_starttime
      HAVING count(*) > 1) old_key;

WITH old_key AS (
    SELECT min(id) AS keep_id, tup_starttime AS bucket,
           sum(sessions) AS sessions, sum(sessions_connected) AS sessions_connected, sum(total_duration_sec) AS total_duration_sec,
           sum(bytes_up) AS bytes_up, sum(bytes_dn) AS bytes_dn, sum(customercost) AS customercost
    FROM sum_wifi_day_101
    GROUP BY tup_switchid, tup_inpartnerid, tup_site, tup_zone, tup_granttier, tup_category, tup_packagecode,
             tup_customercurrency, tup_causefamily, tup_starttime
    HAVING count(*) > 1)
UPDATE sum_wifi_day_101 r
SET sessions = k.sessions, sessions_connected = k.sessions_connected, total_duration_sec = k.total_duration_sec,
    bytes_up = k.bytes_up, bytes_dn = k.bytes_dn, customercost = k.customercost,
    avg_session_sec = CASE WHEN k.sessions_connected = 0 THEN 0
                           ELSE round(k.total_duration_sec::numeric(38,18) / k.sessions_connected, 6) END
FROM old_key k
WHERE r.id = k.keep_id AND r.tup_starttime = k.bucket;

WITH old_key AS (
    SELECT min(id) AS keep_id, tup_switchid, tup_inpartnerid, tup_site, tup_zone, tup_granttier, tup_category,
           tup_packagecode, tup_customercurrency, tup_causefamily, tup_starttime
    FROM sum_wifi_day_101
    GROUP BY tup_switchid, tup_inpartnerid, tup_site, tup_zone, tup_granttier, tup_category, tup_packagecode,
             tup_customercurrency, tup_causefamily, tup_starttime
    HAVING count(*) > 1),
deleted AS (
    DELETE FROM sum_wifi_day_101 r
    USING old_key k
    WHERE r.tup_switchid = k.tup_switchid AND r.tup_inpartnerid = k.tup_inpartnerid AND r.tup_site = k.tup_site
      AND r.tup_zone = k.tup_zone AND r.tup_granttier = k.tup_granttier AND r.tup_category = k.tup_category
      AND r.tup_packagecode = k.tup_packagecode AND r.tup_customercurrency = k.tup_customercurrency
      AND r.tup_causefamily = k.tup_causefamily AND r.tup_starttime = k.tup_starttime
      AND r.id <> k.keep_id
    RETURNING 1)
SELECT 'wayback|sum_wifi_day_101|deleted|' || count(*) FROM deleted;

WITH cleared AS (UPDATE sum_wifi_day_101 SET tup_gw = '' WHERE tup_gw <> '' RETURNING 1)
SELECT 'wayback|sum_wifi_day_101|bras cleared|' || count(*) FROM cleared;

COMMIT;

-- ---- sum_wifi_hr_101 ----------------------------------------------------------------------------------------------------
BEGIN;

SELECT 'wayback|sum_wifi_hr_101|merged|' || count(*)
FROM (SELECT 1 FROM sum_wifi_hr_101
      GROUP BY tup_switchid, tup_inpartnerid, tup_site, tup_zone, tup_granttier, tup_category, tup_packagecode,
               tup_customercurrency, tup_causefamily, tup_starttime
      HAVING count(*) > 1) old_key;

WITH old_key AS (
    SELECT min(id) AS keep_id, tup_starttime AS bucket,
           sum(sessions) AS sessions, sum(sessions_connected) AS sessions_connected, sum(total_duration_sec) AS total_duration_sec,
           sum(bytes_up) AS bytes_up, sum(bytes_dn) AS bytes_dn, sum(customercost) AS customercost
    FROM sum_wifi_hr_101
    GROUP BY tup_switchid, tup_inpartnerid, tup_site, tup_zone, tup_granttier, tup_category, tup_packagecode,
             tup_customercurrency, tup_causefamily, tup_starttime
    HAVING count(*) > 1)
UPDATE sum_wifi_hr_101 r
SET sessions = k.sessions, sessions_connected = k.sessions_connected, total_duration_sec = k.total_duration_sec,
    bytes_up = k.bytes_up, bytes_dn = k.bytes_dn, customercost = k.customercost,
    avg_session_sec = CASE WHEN k.sessions_connected = 0 THEN 0
                           ELSE round(k.total_duration_sec::numeric(38,18) / k.sessions_connected, 6) END
FROM old_key k
WHERE r.id = k.keep_id AND r.tup_starttime = k.bucket;

WITH old_key AS (
    SELECT min(id) AS keep_id, tup_switchid, tup_inpartnerid, tup_site, tup_zone, tup_granttier, tup_category,
           tup_packagecode, tup_customercurrency, tup_causefamily, tup_starttime
    FROM sum_wifi_hr_101
    GROUP BY tup_switchid, tup_inpartnerid, tup_site, tup_zone, tup_granttier, tup_category, tup_packagecode,
             tup_customercurrency, tup_causefamily, tup_starttime
    HAVING count(*) > 1),
deleted AS (
    DELETE FROM sum_wifi_hr_101 r
    USING old_key k
    WHERE r.tup_switchid = k.tup_switchid AND r.tup_inpartnerid = k.tup_inpartnerid AND r.tup_site = k.tup_site
      AND r.tup_zone = k.tup_zone AND r.tup_granttier = k.tup_granttier AND r.tup_category = k.tup_category
      AND r.tup_packagecode = k.tup_packagecode AND r.tup_customercurrency = k.tup_customercurrency
      AND r.tup_causefamily = k.tup_causefamily AND r.tup_starttime = k.tup_starttime
      AND r.id <> k.keep_id
    RETURNING 1)
SELECT 'wayback|sum_wifi_hr_101|deleted|' || count(*) FROM deleted;

WITH cleared AS (UPDATE sum_wifi_hr_101 SET tup_gw = '' WHERE tup_gw <> '' RETURNING 1)
SELECT 'wayback|sum_wifi_hr_101|bras cleared|' || count(*) FROM cleared;

COMMIT;

-- ---- the two tables as 3829e18's jar needs them (read only) -------------------------------------------------------------
SELECT 'ready|sum_wifi_day_101|'
       || (SELECT count(*) FROM (SELECT 1 FROM sum_wifi_day_101
                                 GROUP BY tup_switchid, tup_inpartnerid, tup_site, tup_zone, tup_granttier, tup_category,
                                          tup_packagecode, tup_customercurrency, tup_causefamily, tup_starttime
                                 HAVING count(*) > 1) twice)
       || '|' || (SELECT count(*) FROM sum_wifi_day_101 WHERE tup_gw <> '');
SELECT 'ready|sum_wifi_hr_101|'
       || (SELECT count(*) FROM (SELECT 1 FROM sum_wifi_hr_101
                                 GROUP BY tup_switchid, tup_inpartnerid, tup_site, tup_zone, tup_granttier, tup_category,
                                          tup_packagecode, tup_customercurrency, tup_causefamily, tup_starttime
                                 HAVING count(*) > 1) twice)
       || '|' || (SELECT count(*) FROM sum_wifi_hr_101 WHERE tup_gw <> '');
