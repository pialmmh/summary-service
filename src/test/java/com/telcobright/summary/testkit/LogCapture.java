package com.telcobright.summary.testkit;

import org.jboss.logmanager.ExtLogRecord;

import java.util.ArrayList;
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

    private LogCapture(String loggerName) {
        this.logger = Logger.getLogger(loggerName);
        logger.addHandler(handler);
    }

    public static LogCapture of(Class<?> source) {
        return new LogCapture(source.getName());
    }

    /** Every class's lines (the root logger) — for a rule over the whole log: "the token is in no log line" (HARDEN-2). */
    public static LogCapture ofEverything() {
        return new LogCapture("");
    }

    /** The WARN lines so far, as they are printed. */
    public List<String> warnings() {
        return records.stream().filter(r -> r.getLevel().intValue() == Level.WARNING.intValue()).map(LogCapture::text).toList();
    }

    /** The lines so far at {@code level} (java.util.logging's: SEVERE = ERROR, WARNING = WARN, INFO, FINE = DEBUG). */
    public List<String> records(Level level) {
        return records.stream().filter(r -> r.getLevel().intValue() == level.intValue()).map(LogCapture::text).toList();
    }

    /** Every line so far (any level), as it is printed. */
    public List<String> lines() {
        return records.stream().map(LogCapture::text).toList();
    }

    /**
     * Every line so far, each with the message of every exception it carries, cause by cause: a secret in an exception that is
     * logged is a secret in the log.
     */
    public List<String> linesWithTheirExceptions() {
        return records.stream().map(LogCapture::textWithExceptions).toList();
    }

    private static String textWithExceptions(LogRecord record) {
        StringBuilder line = new StringBuilder(text(record));
        List<Throwable> seen = new ArrayList<>();
        for (Throwable cause = record.getThrown(); cause != null && !seen.contains(cause); cause = cause.getCause()) {
            seen.add(cause);
            line.append(" | ").append(cause);
        }
        return line.toString();
    }

    private static String text(LogRecord record) {
        return record instanceof ExtLogRecord ext ? ext.getFormattedMessage() : String.valueOf(record.getMessage());
    }

    @Override
    public void close() {
        logger.removeHandler(handler);
    }
}
