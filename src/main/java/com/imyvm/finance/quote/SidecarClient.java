package com.imyvm.finance.quote;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.imyvm.finance.market.Instrument;
import com.imyvm.finance.market.MarketQuote;
import com.imyvm.finance.market.MarketStatus;
import com.imyvm.finance.market.QuoteSnapshot;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public final class SidecarClient {
    public static final URI DEFAULT_ENDPOINT = URI.create("http://127.0.0.1:8765/quotes");

    private final HttpClient httpClient;
    private final URI endpoint;
    private final Duration requestTimeout;

    public SidecarClient() {
        this(DEFAULT_ENDPOINT, Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    public SidecarClient(URI endpoint) {
        this(endpoint, Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    public SidecarClient(URI endpoint, Duration connectTimeout, Duration requestTimeout) {
        this.endpoint = endpoint;
        this.requestTimeout = requestTimeout;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(connectTimeout)
            .build();
    }

    public CompletableFuture<String> inspect(String path) {
        HttpRequest request = HttpRequest.newBuilder(endpoint.resolve(path))
            .timeout(requestTimeout)
            .header("Accept", "application/json")
            .GET()
            .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                if (response.statusCode() / 100 != 2)
                    throw new IllegalStateException("sidecar returned HTTP " + response.statusCode() + ": " + response.body());
                return response.body();
            });
    }

    public CompletableFuture<String> control(String path) {
        HttpRequest request = HttpRequest.newBuilder(endpoint.resolve(path))
            .timeout(requestTimeout)
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                if (response.statusCode() / 100 != 2)
                    throw new IllegalStateException("sidecar returned HTTP " + response.statusCode() + ": " + response.body());
                return response.body();
            });
    }

    public CompletableFuture<QuoteSnapshot> fetch() {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
            .timeout(requestTimeout)
            .header("Accept", "application/json")
            .GET()
            .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                if (response.statusCode() != 200)
                    throw new IllegalStateException("sidecar returned HTTP " + response.statusCode());
                return parse(response.body());
            });
    }

    public static QuoteSnapshot parse(String body) {
        JsonObject root = requireObject(JsonParser.parseString(body), "root");
        String snapshotId = requiredString(root, "snapshotId");
        String source = requiredString(root, "source");
        long fetchedAt = requiredLong(root, "fetchedAt");
        long marketTime = requiredLong(root, "marketTime");
        JsonArray quoteArray = requiredArray(root, "quotes");

        List<MarketQuote> quotes = new ArrayList<>();
        Set<Instrument> seen = new HashSet<>();
        for (JsonElement element : quoteArray) {
            try {
                MarketQuote quote = parseQuote(requireObject(element, "quote"));
                if (seen.add(quote.instrument()))
                    quotes.add(quote);
            } catch (RuntimeException ignored) {
                // A malformed instrument must not invalidate other instruments in the snapshot.
            }
        }

        if (quotes.isEmpty())
            throw new IllegalArgumentException("sidecar snapshot has no valid whitelisted quotes");

        List<String> alerts = new ArrayList<>();
        JsonElement alertArray = root.get("alerts");
        if (alertArray != null && alertArray.isJsonArray()) {
            for (JsonElement element : alertArray.getAsJsonArray()) {
                if (element.isJsonPrimitive())
                    alerts.add(element.getAsString());
            }
        }
        return new QuoteSnapshot(snapshotId, source, fetchedAt, marketTime, quotes, alerts);
    }

    private static MarketQuote parseQuote(JsonObject object) {
        Instrument instrument = Instrument.fromSymbol(requiredString(object, "symbol"));
        if (instrument == null)
            throw new IllegalArgumentException("symbol is not whitelisted");

        return new MarketQuote(
            instrument,
            requiredString(object, "name"),
            scaledLong(object, "price", 4),
            scaledLong(object, "changePercent", 2),
            MarketStatus.parse(requiredString(object, "marketStatus")));
    }

    private static long scaledLong(JsonObject object, String key, int scale) {
        BigDecimal value = new BigDecimal(requiredString(object, key));
        return value.movePointRight(scale).longValueExact();
    }

    private static String requiredString(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive())
            throw new IllegalArgumentException("missing " + key);
        String value = element.getAsString();
        if (value.isBlank())
            throw new IllegalArgumentException("blank " + key);
        return value;
    }

    private static long requiredLong(JsonObject object, String key) {
        return Long.parseLong(requiredString(object, key));
    }

    private static JsonObject requireObject(JsonElement element, String name) {
        if (element == null || !element.isJsonObject())
            throw new IllegalArgumentException(name + " must be an object");
        return element.getAsJsonObject();
    }

    private static JsonArray requiredArray(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonArray())
            throw new IllegalArgumentException(key + " must be an array");
        return element.getAsJsonArray();
    }
}
