package com.telcobright.summary.outbox.spi;

import com.telcobright.summary.engine.spi.MergeMode;

/**
 * One outbox row: its sequence {@code id}, its {@code op} ({@code 'add'} for a normal batch; a billing
 * correction writes a {@code 'subtract'} row with the OLD values + an {@code 'add'} row with the NEW values,
 * consecutive ids, one producer tx — applied strictly in id order), and the encoded batch {@code data}
 * (base64(gzip(JSON))). Rows written before the {@code op} column existed read as {@code add}.
 */
public record OutboxRow(long id, String op, String data) {

    /** Pre-op-column convenience: a plain {@code add} row. */
    public OutboxRow(long id, String data) {
        this(id, "add", data);
    }

    /** How this row folds into the windows; anything but {@code subtract} (incl. null) is an ADD. */
    public MergeMode mergeMode() {
        return "subtract".equalsIgnoreCase(op) ? MergeMode.SUBTRACT : MergeMode.ADD;
    }
}
