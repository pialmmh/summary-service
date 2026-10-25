package com.telcobright.summary.tenancy.internal;

import com.telcobright.summary.tenancy.spi.TenantTreeSource;
import com.telcobright.summary.treeroads.api.TreeRoadBearer;

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
 *
 * <p>HARDEN-2: prime-context may guard its tree roads ({@code primecontext.tree-roads.guard = bearer}, its PC-0025). The road
 * carries {@code Authorization: Bearer <token>} when the profile names the token's variable
 * ({@code summary.tenants.prime-context.token-ref: env:<VAR>}, {@link TreeRoadBearer}), and NO Authorization header when it names
 * none — as before. A 401 / 403 says which bearer was shown: its variable's name, never its value.
 */
public final class PrimeContextTreeSource implements TenantTreeSource {

    private final String baseUrl;
    private final String root;
    private final TreeRoadBearer bearer;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** @param bearer the bearer the profile's block names — {@link TreeRoadBearer#none} when it names none */
    public PrimeContextTreeSource(String baseUrl, String root, TreeRoadBearer bearer) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.root = root;
        this.bearer = bearer;
    }

    @Override
    public List<String> schemas() throws IOException, InterruptedException {
        String url = baseUrl + "/get-specific-tenant-root?name=" + URLEncoder.encode(root, StandardCharsets.UTF_8);
        HttpResponse<InputStream> response = http.send(treeRoadPost(url), HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            if (response.statusCode() / 100 != 2) {
                throw new IOException("prime-context answered HTTP " + response.statusCode() + " to " + url + refusalHint(response.statusCode()));
            }
            List<String> schemas = TenantTreeParser.schemasOf(body);
            if (!schemas.get(0).equals(root)) {
                throw new IOException("prime-context at " + baseUrl + " serves the tree of '" + schemas.get(0) + "', this profile's root is '"
                        + root + "' — its tree is refused");
            }
            return schemas;
        }
    }

    /** The road's one POST: its timeout, and the bearer when the profile names one. */
    private HttpRequest treeRoadPost(String url) {
        HttpRequest.Builder request = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.noBody());
        bearer.applyTo(request::header);
        return request.build();
    }

    /** A 401 / 403 is the tree roads' guard answering (prime-context PC-0025): say which bearer was shown — its variable, never its value. */
    private String refusalHint(int status) {
        return status == 401 || status == 403 ? " — " + bearer.said() : "";
    }
}
