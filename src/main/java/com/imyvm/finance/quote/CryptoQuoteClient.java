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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class CryptoQuoteClient {
    private static final URI BINANCE_ENDPOINT = URI.create(
        "https://data-api.binance.vision/api/v3/ticker/24hr?symbols=%5B%22BTCUSDT%22,%22ETHUSDT%22%5D");
    private static final String COINBASE_BASE = "https://api.exchange.coinbase.com/products/";
    private final HttpClient httpClient;
    private final Duration requestTimeout;

    public CryptoQuoteClient(Duration connectTimeout, Duration requestTimeout) {
        this.httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        this.requestTimeout = requestTimeout;
    }

    public CompletableFuture<QuoteSnapshot> fetch() {
        return request(BINANCE_ENDPOINT)
            .thenApply(body -> parseBinance(body, Instant.now()))
            .exceptionallyCompose(error -> fetchCoinbase());
    }

    private CompletableFuture<QuoteSnapshot> fetchCoinbase() {
        CompletableFuture<String> btc = request(URI.create(COINBASE_BASE + "BTC-USD/stats"));
        CompletableFuture<String> eth = request(URI.create(COINBASE_BASE + "ETH-USD/stats"));
        return btc.thenCombine(eth, (btcBody, ethBody) -> parseCoinbase(btcBody, ethBody, Instant.now()));
    }

    private CompletableFuture<String> request(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(requestTimeout)
            .header("Accept", "application/json")
            .header("User-Agent", "ImyvmFinance/1.0")
            .GET()
            .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                if (response.statusCode() / 100 != 2)
                    throw new IllegalStateException("quote provider returned HTTP " + response.statusCode());
                return response.body();
            });
    }

    public static QuoteSnapshot parseBinance(String body, Instant fetchedAt) {
        JsonArray rows = requireArray(JsonParser.parseString(body));
        List<MarketQuote> quotes = new ArrayList<>();
        for (JsonElement element : rows) {
            JsonObject row = element.getAsJsonObject();
            Instrument instrument = switch (row.get("symbol").getAsString()) {
                case "BTCUSDT" -> Instrument.CRYPTO_BTC;
                case "ETHUSDT" -> Instrument.CRYPTO_ETH;
                default -> null;
            };
            if (instrument != null)
                quotes.add(quote(instrument, instrument.name(), row.get("lastPrice").getAsString(),
                    row.get("priceChangePercent").getAsString()));
        }
        return snapshot("binance", quotes, fetchedAt);
    }

    public static QuoteSnapshot parseCoinbase(String btcBody, String ethBody, Instant fetchedAt) {
        List<MarketQuote> quotes = List.of(
            statsQuote(Instrument.CRYPTO_BTC, btcBody),
            statsQuote(Instrument.CRYPTO_ETH, ethBody));
        return snapshot("coinbase", quotes, fetchedAt);
    }

    private static MarketQuote statsQuote(Instrument instrument, String body) {
        JsonObject stats = JsonParser.parseString(body).getAsJsonObject();
        BigDecimal last = new BigDecimal(stats.get("last").getAsString());
        BigDecimal opening = new BigDecimal(stats.get("open").getAsString());
        BigDecimal change = last.subtract(opening).multiply(BigDecimal.valueOf(100))
            .divide(opening, 8, java.math.RoundingMode.HALF_UP);
        return quote(instrument, instrument.name(), last.toPlainString(), change.toPlainString());
    }

    private static MarketQuote quote(Instrument instrument, String name, String price, String changePercent) {
        return new MarketQuote(instrument, name,
            new BigDecimal(price).movePointRight(4).longValueExact(),
            new BigDecimal(changePercent).movePointRight(2).longValue(), MarketStatus.OPEN);
    }

    private static QuoteSnapshot snapshot(String source, List<MarketQuote> quotes, Instant fetchedAt) {
        if (quotes.size() != 2)
            throw new IllegalArgumentException("crypto provider did not return all instruments");
        long timestamp = fetchedAt.toEpochMilli();
        return new QuoteSnapshot("crypto-" + source + "-" + timestamp, source, timestamp, timestamp, quotes, List.of());
    }

    private static JsonArray requireArray(JsonElement element) {
        if (element == null || !element.isJsonArray())
            throw new IllegalArgumentException("provider response must be an array");
        return element.getAsJsonArray();
    }
}
