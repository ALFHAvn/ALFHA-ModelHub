package vn.alfha.modelhub.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class OpenRouterPricing {
    private static final String OFFICIAL_GENERATION = "https://openrouter.ai/api/v1/chat/completions";
    private static final URI CATALOG = URI.create("https://openrouter.ai/api/v1/models");
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    record Check(String model, Instant observedAt, String rejection) {
        String reason(String candidate, Instant now) {
            if (!model.equals(candidate)) return "CLOUD_NOT_VERIFIED";
            if (observedAt.isAfter(now) || observedAt.isBefore(now.minusSeconds(60)))
                return "CLOUD_PRICING_STALE";
            return rejection;
        }
    }

    @FunctionalInterface
    interface CatalogFetch {
        byte[] read() throws IOException, InterruptedException;
    }

    private final ObjectMapper mapper;
    private final String generationEndpoint;
    private final CatalogFetch fetch;

    OpenRouterPricing(ObjectMapper mapper, String generationEndpoint) {
        this(mapper, generationEndpoint, OpenRouterPricing::fetchCatalog);
    }

    OpenRouterPricing(ObjectMapper mapper, String generationEndpoint, CatalogFetch fetch) {
        this.mapper = mapper;
        this.generationEndpoint = generationEndpoint;
        this.fetch = fetch;
    }

    static boolean fixedFreeModel(String model) {
        return model.matches("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+:free");
    }

    Check check(String model) {
        if (!fixedFreeModel(model)) return new Check(model, Instant.now(), "CLOUD_NOT_VERIFIED");
        if (!OFFICIAL_GENERATION.equals(generationEndpoint))
            return new Check(model, Instant.now(), "CLOUD_ENDPOINT_UNTRUSTED");
        Instant started = Instant.now();
        String rejection;
        try {
            rejection = inspect(model, fetch.read());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            rejection = "CLOUD_CATALOG_UNAVAILABLE";
        } catch (IOException e) {
            rejection = "CLOUD_CATALOG_UNAVAILABLE";
        } catch (JacksonException e) {
            rejection = "CLOUD_PRICING_UNVERIFIED";
        }
        return new Check(model, started, rejection);
    }

    private String inspect(String model, byte[] body) {
        JsonNode rows = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(body).path("data");
        if (!rows.isArray()) return "CLOUD_PRICING_UNVERIFIED";
        JsonNode match = null;
        for (JsonNode row : rows) {
            if (!model.equals(row.path("id").asString(null))) continue;
            if (match != null) return "CLOUD_PRICING_UNVERIFIED";
            match = row;
        }
        if (match == null) return "CLOUD_MODEL_NOT_LISTED";
        var pricing = match.path("pricing");
        if (!pricing.isObject() || !pricing.has("prompt") || !pricing.has("completion"))
            return "CLOUD_PRICING_UNVERIFIED";
        boolean nonzero = false;
        for (var entry : pricing.properties()) {
            JsonNode price = entry.getValue();
            if (!price.isString()) return "CLOUD_PRICING_UNVERIFIED";
            try {
                nonzero |= new BigDecimal(price.asString()).signum() != 0;
            } catch (NumberFormatException e) {
                return "CLOUD_PRICING_UNVERIFIED";
            }
        }
        return nonzero ? "CLOUD_NONZERO_PRICE" : null;
    }

    private static byte[] fetchCatalog() throws IOException, InterruptedException {
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var request = HttpRequest.newBuilder(CATALOG).GET().timeout(Duration.ofSeconds(5)).build();
        var bytes = new ByteArrayOutputStream();
        var response = client.sendAsync(request, info -> info.statusCode() == 200
                ? HttpResponse.BodySubscribers.ofByteArrayConsumer(chunk -> chunk.ifPresent(part -> {
                    if (part.length > MAX_BYTES - bytes.size()) throw new IllegalStateException("Catalog too large");
                    bytes.writeBytes(part);
                }))
                : HttpResponse.BodySubscribers.replacing(null));
        try {
            if (response.get(5, TimeUnit.SECONDS).statusCode() != 200)
                throw new IOException("Catalog returned a non-200 status");
            return bytes.toByteArray();
        } catch (ExecutionException | TimeoutException e) {
            throw new IOException("Catalog request failed", e);
        } finally {
            response.cancel(true);
            client.shutdownNow();
        }
    }
}
