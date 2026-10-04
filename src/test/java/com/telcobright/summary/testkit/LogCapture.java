package com.telcobright.summary.testkit;

import org.jboss.logmanager.ExtLogRecord;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Hears what a class logs while a test runs — for a rule whose words ARE the rule ("said once, in one WARN that
 * names the schema and the table"). Closed at the end of the test; nothing is kept after it.
 */
public final class LogCapture implements AutoCloseable {

    private final Logger logger;
    private final List<LogRecord> records = new CopyOnWriteArrayList<>();
    private final Handler handler = new Handler() {
        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    private LogCapture(Class<?> source) {
        this.logger = Logger.getLogger(source.getName());
        logger.addHandler(handler);
    }

    public static LogCapture of(Class<?> source) {
        return new LogCapture(source);
    }

    /** The WARN lines so far, as they are printed. */
    public List<String> warnings() {
        return records.stream().filter(r -> r.getLevel().intValue() == Level.WARNING.intValue()).map(LogCapture::text).toList();
    }

    /** Every line so far (any level), as it is printed. */
    public List<String> lines() {
        return records.stream().map(LogCapture::text).toList();
    }

    private static String text(LogRecord record) {
        return record instanceof ExtLogRecord ext ? ext.getFormattedMessage() : String.valueOf(record.getMessage());
    }

    @Override
    public void close() {
        logger.removeHandler(handler);
    }
}
