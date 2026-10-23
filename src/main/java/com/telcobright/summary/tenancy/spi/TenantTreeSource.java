package com.telcobright.summary.tenancy.spi;

import java.util.List;

/**
 * Where the tenant tree comes from: the schemas of the root's tree, the ROOT first. Production reads prime-context
 * (the same road config-manager serves: {@code POST /get-specific-tenant-root}); a test hands a list. A source
 * that cannot answer throws — the watcher keeps the schemas it already serves and asks again later.
 */
@FunctionalInterface
public interface TenantTreeSource {

    /** Every tenant schema of the tree, the root first, each by its own name ({@code btcl}, {@code res_44}, {@code res_44_7}). */
    List<String> schemas() throws Exception;
}
