package com.telcobright.summary.context.internal;

import com.telcobright.summary.treeroads.api.TreeRoadBearer;
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
 *
 * <p>HARDEN-2: the road is one of prime-context's TREE ROADS, which prime-context may guard ({@code primecontext.tree-roads.guard =
 * bearer}, its PC-0025). It carries {@code Authorization: Bearer <token>} when the context's block names the token's variable
 * ({@code summary.contexts.<name>.token-ref: env:<VAR>}, {@link TreeRoadBearer}), and NO Authorization header when it names none
 * — as before. A 401 / 403 is said with the bearer's variable, never its value.
 */
@ApplicationScoped
public class ConfigManagerClient {

    private static final Logger LOG = Logger.getLogger(ConfigManagerClient.class);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** The tenant's tree, asked with {@code bearer} ({@link TreeRoadBearer#none} for none); empty when it could not be read. */
    public Optional<String> fetchTenantRoot(String baseUrl, String tenant, TreeRoadBearer bearer) {
        String url = baseUrl + "/get-specific-tenant-root?name=" + URLEncoder.encode(tenant, StandardCharsets.UTF_8);
        try {
            HttpResponse<String> response = http.send(treeRoadPost(url, bearer), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 == 2) {
                return Optional.of(response.body());
            }
            LOG.warnf("config-manager %s -> HTTP %d (context not loaded)%s", tenant, response.statusCode(), refusalHint(response.statusCode(), bearer));
            return Optional.empty();
        } catch (Exception e) {
            LOG.warnf("config-manager unreachable at %s for tenant %s (context not loaded): %s", baseUrl, tenant, e.toString());
            return Optional.empty();
        }
    }

    /** The road's one POST: its timeout, and the bearer when the block names one. */
    private static HttpRequest treeRoadPost(String url, TreeRoadBearer bearer) {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.noBody());
        bearer.applyTo(request::header);
        return request.build();
    }

    /** A 401 / 403 is the tree roads' guard answering (prime-context PC-0025): say which bearer was shown — its variable, never its value. */
    private static String refusalHint(int status, TreeRoadBearer bearer) {
        return status == 401 || status == 403 ? " — " + bearer.said() : "";
    }
}
