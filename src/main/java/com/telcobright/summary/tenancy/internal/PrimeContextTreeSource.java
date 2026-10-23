package com.telcobright.summary.tenancy.internal;

import com.telcobright.summary.tenancy.spi.TenantTreeSource;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * The tree from prime-context: {@code POST <base-url>/get-specific-tenant-root?name=<root>} — the road
 * config-manager serves and billing-core calls; a READ (it is one of the six POST roads prime-context's write
 * guard leaves open). This service never calls a road of prime-context that writes.
 *
 * <p>prime-context serves ONE tree and ignores the name it is asked. So the answer's root must BE this profile's
 * root: a tree of another root is refused in words — never served by mistake.
 */
public final class PrimeContextTreeSource implements TenantTreeSource {

    private final String baseUrl;
    private final String root;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public PrimeContextTreeSource(String baseUrl, String root) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.root = root;
    }

    @Override
    public List<String> schemas() throws IOException, InterruptedException {
        String url = baseUrl + "/get-specific-tenant-root?name=" + URLEncoder.encode(root, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            if (response.statusCode() / 100 != 2) {
                throw new IOException("prime-context answered HTTP " + response.statusCode() + " to " + url);
            }
            List<String> schemas = TenantTreeParser.schemasOf(body);
            if (!schemas.get(0).equals(root)) {
                throw new IOException("prime-context at " + baseUrl + " serves the tree of '" + schemas.get(0) + "', this profile's root is '"
                        + root + "' — its tree is refused");
            }
            return schemas;
        }
    }
}
