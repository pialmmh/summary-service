package com.telcobright.summary.bean.spi;

/**
 * One time window a bean maintains: its granularity, the MySQL table it writes, and the datetime column
 * that holds the bucket start. A CDR bean declares two — a DAY window into {@code sum_voice_day_03} and an
 * HOUR window into {@code sum_voice_hr_03} — both keyed on {@code tup_starttime}.
 *
 * @param name          short id, unique within the bean (e.g. "day", "hour")
 * @param granularity   how event time is truncated to the bucket
 * @param table         target MySQL table
 * @param bucketColumn  the datetime column holding the bucket start (part of the row key)
 */
public record WindowDef(String name, Granularity granularity, String table, String bucketColumn) {
}
