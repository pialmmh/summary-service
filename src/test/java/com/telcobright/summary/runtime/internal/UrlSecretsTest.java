package com.telcobright.summary.runtime.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Brief S9 / the house rule "no secret in a URL": what a password looks like inside a URL is known in ONE place.
 * What is REFUSED at a start and what is HIDDEN where a value is printed must be the same forms — a form refused
 * but printed first still leaks; a form hidden but not refused is still used.
 */
class UrlSecretsTest {

    private static final String SECRET = "s3cret-in-a-url";

    /** Every way a password can ride in a JDBC or an HTTP URL that the drivers of this service understand. */
    private static final String[] WITH_A_PASSWORD = {
            "jdbc:postgresql://127.0.0.1:7643/routesphere?user=x&password=" + SECRET + "&currentSchema=btcl",
            "jdbc:postgresql://127.0.0.1:7643/routesphere?PASSWORD=" + SECRET,
            "jdbc:postgresql://127.0.0.1:7643/routesphere?sslmode=require&sslpassword=" + SECRET,
            "jdbc:mysql://h:3306/db?useSSL=false&pwd=" + SECRET,
            "jdbc:mysql://h:3306/db?trustCertificateKeyStorePassword=" + SECRET + "&useSSL=true",
            "jdbc:mysql://summary:" + SECRET + "@127.0.0.1:3306/telcobright",
            "jdbc:mysql://127.0.0.1:3306,summary:" + SECRET + "@10.10.9.9:3306/telcobright",
            "jdbc:mysql://(host=127.0.0.1,port=3306,user=summary,password=" + SECRET + ")/telcobright",
            "jdbc:mysql://address=(host=127.0.0.1)(port=3306)(user=summary)(password=" + SECRET + ")/telcobright",
            "http://reader:" + SECRET + "@127.0.0.1:7691/base",
    };

    private static final String[] WITHOUT_ONE = {
            "jdbc:postgresql://127.0.0.1:7643/routesphere",
            "jdbc:postgresql://127.0.0.1:7643/routesphere?currentSchema=btcl&sslmode=require",
            "jdbc:mysql://127.0.0.1:7633/?useSSL=false&allowPublicKeyRetrieval=true&allowMultiQueries=true",
            "jdbc:mysql://summary@127.0.0.1:3306/telcobright",                 // a user alone is no secret
            "jdbc:postgresql://[::1]:7643/routesphere",                        // the colons of an IPv6 host are not a password
            "http://127.0.0.1:7691",
            "http://127.0.0.1:7691/base?contact=ops:team@example.org",          // user-info is read in the AUTHORITY only
            "jdbc:postgresql://127.0.0.1:7643/routesphere?options=-c%20x=a:b@c",
            "127.0.0.1:7692,127.0.0.1:7693",
    };

    @Test
    void every_form_of_a_password_in_a_url_is_seen_and_hidden() {
        for (String url : WITH_A_PASSWORD) {
            assertTrue(UrlSecrets.in(url), "seen (so the start is refused): " + url);
            assertFalse(UrlSecrets.hidden(url).contains(SECRET), "hidden where it is printed: " + UrlSecrets.hidden(url));
            assertTrue(UrlSecrets.hidden(url).contains("<hidden>"), UrlSecrets.hidden(url));
        }
    }

    @Test
    void a_url_without_a_password_is_left_as_it_is() {
        for (String url : WITHOUT_ONE) {
            assertFalse(UrlSecrets.in(url), url);
            assertEquals(url, UrlSecrets.hidden(url));
        }
        assertFalse(UrlSecrets.in(null), "no URL: nothing to refuse");
    }

    @Test
    void only_the_password_is_hidden_the_rest_of_the_value_stays_readable() {
        assertEquals("jdbc:postgresql://127.0.0.1:7643/routesphere?user=x&password=<hidden>&currentSchema=btcl", UrlSecrets.hidden(WITH_A_PASSWORD[0]));
        assertEquals("jdbc:postgresql://127.0.0.1:7643/routesphere?sslmode=require&sslpassword=<hidden>", UrlSecrets.hidden(WITH_A_PASSWORD[2]));
        assertEquals("jdbc:mysql://<hidden>@127.0.0.1:3306/telcobright", UrlSecrets.hidden(WITH_A_PASSWORD[5]));
        assertEquals("jdbc:mysql://127.0.0.1:3306,<hidden>@10.10.9.9:3306/telcobright", UrlSecrets.hidden(WITH_A_PASSWORD[6]),
                "each host of a list may carry its own; every host stays readable");
        assertEquals("jdbc:mysql://(host=127.0.0.1,port=3306,user=summary,password=<hidden>)/telcobright", UrlSecrets.hidden(WITH_A_PASSWORD[7]));
        assertEquals("jdbc:mysql://address=(host=127.0.0.1)(port=3306)(user=summary)(password=<hidden>)/telcobright", UrlSecrets.hidden(WITH_A_PASSWORD[8]));
    }
}
