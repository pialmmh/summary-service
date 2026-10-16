-- The WAY BACK from the WiFi session sums split by BRAS (SS-0007 item 5) — MySQL. Run by tools/lab/wifi-gw-wayback.sh
-- (mysql -N -B <the tier database>) AFTER the new jar is stopped and BEFORE 3829e18's jar starts on that database.
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
-- The texts of the key are compared byte for byte (CAST ... AS BINARY), as the jar compares its keys: the tables' collation
-- is case-insensitive, and two rows that differ in case are two keys to the jar. A second run finds no group and no BRAS,
-- and changes nothing. No other table is read or written.
--
-- What it prints, one line each: wayback|<table>|merged|<groups summed into their lowest id>,
-- wayback|<table>|deleted|<rows>, wayback|<table>|bras cleared|<rows>; after both commits
-- ready|<table>|<old keys held by more than one row>|<rows with a BRAS> — 0 and 0 = 3829e18's jar may start.

-- ---- sum_wifi_day_101 ---------------------------------------------------------------------------------------------------
START TRANSACTION;

SELECT CONCAT('wayback|sum_wifi_day_101|merged|', count(*))
FROM (SELECT 1 FROM sum_wifi_day_101
      GROUP BY tup_switchid, tup_inpartnerid, CAST(tup_site AS BINARY), CAST(tup_zone AS BINARY), CAST(tup_granttier AS BINARY),
               tup_category, CAST(tup_packagecode AS BINARY), CAST(tup_customercurrency AS BINARY),
               CAST(tup_causefamily AS BINARY), tup_starttime
      HAVING count(*) > 1) old_key;

UPDATE sum_wifi_day_101 r
JOIN (SELECT min(id) AS keep_id, tup_starttime AS bucket,
             sum(sessions) AS sessions, sum(sessions_connected) AS sessions_connected, sum(total_duration_sec) AS total_duration_sec,
             sum(bytes_up) AS bytes_up, sum(bytes_dn) AS bytes_dn, sum(customercost) AS customercost
      FROM sum_wifi_day_101
      GROUP BY tup_switchid, tup_inpartnerid, CAST(tup_site AS BINARY), CAST(tup_zone AS BINARY), CAST(tup_granttier AS BINARY),
               tup_category, CAST(tup_packagecode AS BINARY), CAST(tup_customercurrency AS BINARY),
               CAST(tup_causefamily AS BINARY), tup_starttime
      HAVING count(*) > 1) k ON r.id = k.keep_id AND r.tup_starttime = k.bucket
SET r.sessions = k.sessions, r.sessions_connected = k.sessions_connected, r.total_duration_sec = k.total_duration_sec,
    r.bytes_up = k.bytes_up, r.bytes_dn = k.bytes_dn, r.customercost = k.customercost,
    r.avg_session_sec = CASE WHEN k.sessions_connected = 0 THEN 0
                             ELSE ROUND(CAST(k.total_duration_sec AS DECIMAL(38,18)) / k.sessions_connected, 6) END;

DELETE r FROM sum_wifi_day_101 r
JOIN (SELECT min(id) AS keep_id, tup_switchid AS switch_id, tup_inpartnerid AS in_partner, CAST(tup_site AS BINARY) AS site,
             CAST(tup_zone AS BINARY) AS zone, CAST(tup_granttier AS BINARY) AS grant_tier, tup_category AS category,
             CAST(tup_packagecode AS BINARY) AS package_code, CAST(tup_customercurrency AS BINARY) AS currency,
             CAST(tup_causefamily AS BINARY) AS cause_family, tup_starttime AS bucket
      FROM sum_wifi_day_101
      GROUP BY tup_switchid, tup_inpartnerid, CAST(tup_site AS BINARY), CAST(tup_zone AS BINARY), CAST(tup_granttier AS BINARY),
               tup_category, CAST(tup_packagecode AS BINARY), CAST(tup_customercurrency AS BINARY),
               CAST(tup_causefamily AS BINARY), tup_starttime
      HAVING count(*) > 1) k
  ON r.tup_switchid = k.switch_id AND r.tup_inpartnerid = k.in_partner AND CAST(r.tup_site AS BINARY) = k.site
 AND CAST(r.tup_zone AS BINARY) = k.zone AND CAST(r.tup_granttier AS BINARY) = k.grant_tier AND r.tup_category = k.category
 AND CAST(r.tup_packagecode AS BINARY) = k.package_code AND CAST(r.tup_customercurrency AS BINARY) = k.currency
 AND CAST(r.tup_causefamily AS BINARY) = k.cause_family AND r.tup_starttime = k.bucket
 AND r.id <> k.keep_id;
SELECT CONCAT('wayback|sum_wifi_day_101|deleted|', ROW_COUNT());

UPDATE sum_wifi_day_101 SET tup_gw = '' WHERE tup_gw <> '';
SELECT CONCAT('wayback|sum_wifi_day_101|bras cleared|', ROW_COUNT());

COMMIT;

-- ---- sum_wifi_hr_101 ----------------------------------------------------------------------------------------------------
START TRANSACTION;

SELECT CONCAT('wayback|sum_wifi_hr_101|merged|', count(*))
FROM (SELECT 1 FROM sum_wifi_hr_101
      GROUP BY tup_switchid, tup_inpartnerid, CAST(tup_site AS BINARY), CAST(tup_zone AS BINARY), CAST(tup_granttier AS BINARY),
               tup_category, CAST(tup_packagecode AS BINARY), CAST(tup_customercurrency AS BINARY),
               CAST(tup_causefamily AS BINARY), tup_starttime
      HAVING count(*) > 1) old_key;

UPDATE sum_wifi_hr_101 r
JOIN (SELECT min(id) AS keep_id, tup_starttime AS bucket,
             sum(sessions) AS sessions, sum(sessions_connected) AS sessions_connected, sum(total_duration_sec) AS total_duration_sec,
             sum(bytes_up) AS bytes_up, sum(bytes_dn) AS bytes_dn, sum(customercost) AS customercost
      FROM sum_wifi_hr_101
      GROUP BY tup_switchid, tup_inpartnerid, CAST(tup_site AS BINARY), CAST(tup_zone AS BINARY), CAST(tup_granttier AS BINARY),
               tup_category, CAST(tup_packagecode AS BINARY), CAST(tup_customercurrency AS BINARY),
               CAST(tup_causefamily AS BINARY), tup_starttime
      HAVING count(*) > 1) k ON r.id = k.keep_id AND r.tup_starttime = k.bucket
SET r.sessions = k.sessions, r.sessions_connected = k.sessions_connected, r.total_duration_sec = k.total_duration_sec,
    r.bytes_up = k.bytes_up, r.bytes_dn = k.bytes_dn, r.customercost = k.customercost,
    r.avg_session_sec = CASE WHEN k.sessions_connected = 0 THEN 0
                             ELSE ROUND(CAST(k.total_duration_sec AS DECIMAL(38,18)) / k.sessions_connected, 6) END;

DELETE r FROM sum_wifi_hr_101 r
JOIN (SELECT min(id) AS keep_id, tup_switchid AS switch_id, tup_inpartnerid AS in_partner, CAST(tup_site AS BINARY) AS site,
             CAST(tup_zone AS BINARY) AS zone, CAST(tup_granttier AS BINARY) AS grant_tier, tup_category AS category,
             CAST(tup_packagecode AS BINARY) AS package_code, CAST(tup_customercurrency AS BINARY) AS currency,
             CAST(tup_causefamily AS BINARY) AS cause_family, tup_starttime AS bucket
      FROM sum_wifi_hr_101
      GROUP BY tup_switchid, tup_inpartnerid, CAST(tup_site AS BINARY), CAST(tup_zone AS BINARY), CAST(tup_granttier AS BINARY),
               tup_category, CAST(tup_packagecode AS BINARY), CAST(tup_customercurrency AS BINARY),
               CAST(tup_causefamily AS BINARY), tup_starttime
      HAVING count(*) > 1) k
  ON r.tup_switchid = k.switch_id AND r.tup_inpartnerid = k.in_partner AND CAST(r.tup_site AS BINARY) = k.site
 AND CAST(r.tup_zone AS BINARY) = k.zone AND CAST(r.tup_granttier AS BINARY) = k.grant_tier AND r.tup_category = k.category
 AND CAST(r.tup_packagecode AS BINARY) = k.package_code AND CAST(r.tup_customercurrency AS BINARY) = k.currency
 AND CAST(r.tup_causefamily AS BINARY) = k.cause_family AND r.tup_starttime = k.bucket
 AND r.id <> k.keep_id;
SELECT CONCAT('wayback|sum_wifi_hr_101|deleted|', ROW_COUNT());

UPDATE sum_wifi_hr_101 SET tup_gw = '' WHERE tup_gw <> '';
SELECT CONCAT('wayback|sum_wifi_hr_101|bras cleared|', ROW_COUNT());

COMMIT;

-- ---- the two tables as 3829e18's jar needs them (read only) -------------------------------------------------------------
SELECT CONCAT('ready|sum_wifi_day_101|',
       (SELECT count(*) FROM (SELECT 1 FROM sum_wifi_day_101
                              GROUP BY tup_switchid, tup_inpartnerid, CAST(tup_site AS BINARY), CAST(tup_zone AS BINARY),
                                       CAST(tup_granttier AS BINARY), tup_category, CAST(tup_packagecode AS BINARY),
                                       CAST(tup_customercurrency AS BINARY), CAST(tup_causefamily AS BINARY), tup_starttime
                              HAVING count(*) > 1) twice),
       '|', (SELECT count(*) FROM sum_wifi_day_101 WHERE tup_gw <> ''));
SELECT CONCAT('ready|sum_wifi_hr_101|',
       (SELECT count(*) FROM (SELECT 1 FROM sum_wifi_hr_101
                              GROUP BY tup_switchid, tup_inpartnerid, CAST(tup_site AS BINARY), CAST(tup_zone AS BINARY),
                                       CAST(tup_granttier AS BINARY), tup_category, CAST(tup_packagecode AS BINARY),
                                       CAST(tup_customercurrency AS BINARY), CAST(tup_causefamily AS BINARY), tup_starttime
                              HAVING count(*) > 1) twice),
       '|', (SELECT count(*) FROM sum_wifi_hr_101 WHERE tup_gw <> ''));
