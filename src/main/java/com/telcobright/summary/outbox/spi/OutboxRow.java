package com.telcobright.summary.outbox.spi;

/** One outbox row: its sequence {@code id} and the encoded batch {@code data} (base64(gzip(JSON))). */
public record OutboxRow(long id, String data) {
}
