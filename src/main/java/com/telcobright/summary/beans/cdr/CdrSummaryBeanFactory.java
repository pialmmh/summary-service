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

    private final ObjectMapper mapper;

    @Inject
    public CdrSummaryBeanFactory(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String entity() {
        return "cdr";
    }

    @Override
    public SummaryBean<?> create(BeanConfig config) {
        return new CdrSummaryBean(mapper, config);
    }
}
