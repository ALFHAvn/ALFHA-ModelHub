package vn.alfha.modelhub.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class OpenRouterPricingTest {
    private static final String ENDPOINT = "https://openrouter.ai/api/v1/chat/completions";
    private static final String MODEL = "vendor/model:free";
    private static final String ZERO = """
            {"prompt":"0","completion":"0.00"}
            """;

    @Test void requiresExactCatalogMatchAndExplicitZeroTokenPrices() {
        assertNull(check(catalog(ZERO)));
        assertEquals("CLOUD_MODEL_NOT_LISTED", check(catalog(ZERO).replace(MODEL, "vendor/other:free")));
        assertEquals("CLOUD_PRICING_UNVERIFIED", check(catalog("{\"prompt\":\"0\"}")));
        assertEquals("CLOUD_PRICING_UNVERIFIED", check(catalog(ZERO) + "{}"));
        assertEquals("CLOUD_PRICING_UNVERIFIED", check(catalog(ZERO).replace(
                "\"completion\":\"0.00\"", "\"completion\":\"1\",\"completion\":\"0\"")));
        var row = catalog(ZERO).substring("{\"data\":[".length(), catalog(ZERO).length() - 2);
        assertEquals("CLOUD_PRICING_UNVERIFIED", check("{\"data\":[" + row + "," + row + "]}"));
    }

    @Test void refusesAnyNonzeroOrMalformedFee() {
        for (String field : new String[] {"prompt", "completion", "request", "future_fee"}) {
            assertEquals("CLOUD_NONZERO_PRICE", check(catalog(
                    "{\"prompt\":\"0\",\"completion\":\"0\",\"request\":\"0\",\"future_fee\":\"0\"}"
                            .replace("\"" + field + "\":\"0\"", "\"" + field + "\":\"1e-1000\""))));
        }
        assertEquals("CLOUD_PRICING_UNVERIFIED", check(catalog("{\"prompt\":\"0\",\"completion\":null}")));
        assertEquals("CLOUD_PRICING_UNVERIFIED", check(catalog("{\"prompt\":\"0\",\"completion\":0}")));
    }

    @Test void rejectsUntrustedEndpointAndVariableOrPaidIdsWithoutFetching() {
        OpenRouterPricing.CatalogFetch neverFetch = () -> fail("Unexpected catalog request");
        assertEquals("CLOUD_ENDPOINT_UNTRUSTED",
                new OpenRouterPricing(new ObjectMapper(), ENDPOINT + "?proxy=true", neverFetch).check(MODEL).rejection());
        var pricing = new OpenRouterPricing(new ObjectMapper(), ENDPOINT, neverFetch);
        assertEquals("CLOUD_NOT_VERIFIED", pricing.check("openrouter/free").rejection());
        assertEquals("CLOUD_NOT_VERIFIED", pricing.check("vendor/model").rejection());
    }

    @Test void treatsCatalogOutageAsUnavailable() {
        var pricing = new OpenRouterPricing(new ObjectMapper(), ENDPOINT, () -> {
            throw new IOException("offline");
        });
        assertEquals("CLOUD_CATALOG_UNAVAILABLE", pricing.check(MODEL).rejection());
    }

    private String check(String body) {
        return new OpenRouterPricing(new ObjectMapper(), ENDPOINT,
                () -> body.getBytes(StandardCharsets.UTF_8)).check(MODEL).rejection();
    }

    private String catalog(String pricing) {
        return "{\"data\":[{\"id\":\"" + MODEL + "\",\"pricing\":" + pricing + "}]}";
    }
}
