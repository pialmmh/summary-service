package com.telcobright.summary.runtime.internal;

import com.telcobright.summary.bean.spi.SqlDialect;
import io.agroal.api.AgroalDataSource;

/**
 * The service's OWN pool for a test: made by the same code as the running service's ({@link StoreDataSource}), so
 * a test of the tree runs every schema over ONE pool of real, reused connections — where a connection keeps the
 * schema the last unit of work entered.
 */
public final class TestPools {

    private TestPools() {
    }

    public static AgroalDataSource pool(SqlDialect dialect, String url, String user, String password, int maxSize) {
        return StoreDataSource.open(new StoreConfig(dialect, url, user, password, 0, maxSize, 30));
    }
}
