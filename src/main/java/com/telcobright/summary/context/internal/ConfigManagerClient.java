package com.telcobright.summary.context.internal;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * Thin client for config-manager — the SAME api billing calls ({@code POST /get-specific-tenant-root?name=…}).
 * config-manager is UNCHANGED; summary is just another client. Best-effort: if config-manager is unreachable it
 * logs and returns empty (the CDR build does not depend on the context in v1), so dev/test boot without it.
 */
@ApplicationScoped
public class ConfigManagerClient {

    private static final Logger LOG = Logger.getLogger(ConfigManagerClient.class);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public Optional<String> fetchTenantRoot(String baseUrl, String tenant) {
        String url = baseUrl + "/get-specific-tenant-root?name=" + URLEncoder.encode(tenant, StandardCharsets.UTF_8);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 == 2) {
                return Optional.of(response.body());
            }
            LOG.warnf("config-manager %s -> HTTP %d (context not loaded)", tenant, response.statusCode());
            return Optional.empty();
        } catch (Exception e) {
            LOG.warnf("config-manager unreachable at %s for tenant %s (context not loaded): %s", baseUrl, tenant, e.toString());
            return Optional.empty();
        }
    }
}
