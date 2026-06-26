package com.telcobright.summary.registry.internal;

import com.telcobright.summary.bean.spi.SummaryBean;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Properties;

/**
 * Builds a plain {@link KafkaConsumer} for a bean's worker — NOT a smallrye channel, so the registry can
 * hot-start/stop a consumer at runtime. Auto-commit is OFF: the worker commits offsets only AFTER the DB
 * commit. {@code max.poll.records} = the bean's batch size, so one poll feeds one transaction. Each bean gets
 * its own consumer group so offsets are independent.
 */
@ApplicationScoped
public class KafkaConsumerFactory {

    private final String bootstrapServers;
    private final String groupPrefix;

    public KafkaConsumerFactory(
            @ConfigProperty(name = "summary.kafka.bootstrap-servers", defaultValue = "127.0.0.1:9092") String bootstrapServers,
            @ConfigProperty(name = "summary.kafka.consumer-group", defaultValue = "summary-service") String groupPrefix) {
        this.bootstrapServers = bootstrapServers;
        this.groupPrefix = groupPrefix;
    }

    public Consumer<String, byte[]> create(SummaryBean<?> bean) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupPrefix + "-" + bean.name());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, bean.batchSize());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        return new KafkaConsumer<>(props);
    }
}
