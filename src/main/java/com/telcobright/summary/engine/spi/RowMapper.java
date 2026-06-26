package com.telcobright.summary.engine.spi;

import java.sql.ResultSet;
import java.sql.SQLException;

/** Maps one loaded DB row into a typed summary entity. Supplied by the bean; used by the JDBC store. */
@FunctionalInterface
public interface RowMapper<T> {

    T map(ResultSet row) throws SQLException;
}
