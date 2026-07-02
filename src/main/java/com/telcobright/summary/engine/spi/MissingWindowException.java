package com.telcobright.summary.engine.spi;

/**
 * A SUBTRACT delta targeted a window that exists neither in the DB nor in this batch — a data-integrity
 * signal (the original ADD predates the bean, was dead-lettered, or never happened). Distinguished from other
 * engine failures so the drain can treat the row as POISON (dotnet ruling A1: dead-letter after N consecutive
 * failures instead of wedging the bean forever); transient SQL failures deliberately do NOT use this type.
 */
public class MissingWindowException extends IllegalStateException {

    public MissingWindowException(String message) {
        super(message);
    }
}
