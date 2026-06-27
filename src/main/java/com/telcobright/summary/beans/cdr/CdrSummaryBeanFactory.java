package com.telcobright.summary.beans.cdr;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.summary.bean.spi.SummaryBean;
import com.telcobright.summary.registry.spi.BeanConfig;
import com.telcobright.summary.registry.spi.SummaryBeanFactory;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Builds {@link CdrSummaryBean} instances for {@code entity: cdr} config entries (daily, hourly, 5-min, …). */
@ApplicationScoped
public class CdrSummaryBeanFactory implements SummaryBeanFactory {

    private final ObjectMapper blobMapper;

    @Inject
    public CdrSummaryBeanFactory(ObjectMapper mapper) {
        // a case-insensitive + JavaTime copy for the C# PascalCase outbox blob
        this.blobMapper = CdrBlobMapper.from(mapper);
    }

    @Override
    public String entity() {
        return "cdr";
    }

    @Override
    public SummaryBean<?> create(BeanConfig config) {
        return new CdrSummaryBean(blobMapper, config);
    }
}
