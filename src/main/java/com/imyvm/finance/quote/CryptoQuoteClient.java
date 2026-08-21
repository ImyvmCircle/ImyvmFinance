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
    private static final URI MEXC_BTC_ENDPOINT = URI.create("https://api.mexc.com/api/v3/ticker/24hr?symbol=BTCUSDT");
    private static final URI MEXC_ETH_ENDPOINT = URI.create("https://api.mexc.com/api/v3/ticker/24hr?symbol=ETHUSDT");
    private static final URI BITGET_BTC_ENDPOINT = URI.create("https://api.bitget.com/api/v2/spot/market/tickers?symbol=BTCUSDT");
    private static final URI BITGET_ETH_ENDPOINT = URI.create("https://api.bitget.com/api/v2/spot/market/tickers?symbol=ETHUSDT");
    private static final URI GATE_BTC_ENDPOINT = URI.create("https://api.gateio.ws/api/v4/spot/tickers?currency_pair=BTC_USDT");
    private static final URI GATE_ETH_ENDPOINT = URI.create("https://api.gateio.ws/api/v4/spot/tickers?currency_pair=ETH_USDT");
    private static final URI KUCOIN_BTC_ENDPOINT = URI.create("https://api.kucoin.com/api/ua/v1/market/ticker?tradeType=SPOT&symbol=BTC-USDT");
    private static final URI KUCOIN_ETH_ENDPOINT = URI.create("https://api.kucoin.com/api/ua/v1/market/ticker?tradeType=SPOT&symbol=ETH-USDT");
    private static final URI OKX_BTC_ENDPOINT = URI.create("https://app.okx.com/api/v5/market/ticker?instId=BTC-USDT");
    private static final URI OKX_ETH_ENDPOINT = URI.create("https://app.okx.com/api/v5/market/ticker?instId=ETH-USDT");
    private final HttpClient httpClient;
    private final Duration requestTimeout;

    public CryptoQuoteClient(Duration connectTimeout, Duration requestTimeout) {
        this.httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        this.requestTimeout = requestTimeout;
    }

    public CompletableFuture<QuoteSnapshot> fetch() {
        return fetchBinance().exceptionallyCompose(error ->
            fetchGate().exceptionallyCompose(gateError ->
                fetchKucoin().exceptionallyCompose(kucoinError -> fetchOkx())));
    }

    public CompletableFuture<QuoteSnapshot> fetchGate() {
        CompletableFuture<String> btc = request(GATE_BTC_ENDPOINT);
        CompletableFuture<String> eth = request(GATE_ETH_ENDPOINT);
        return btc.thenCombine(eth, (btcBody, ethBody) -> parseGate(btcBody, ethBody, Instant.now()));
    }

    public CompletableFuture<QuoteSnapshot> fetchKucoin() {
        CompletableFuture<String> btc = request(KUCOIN_BTC_ENDPOINT);
        CompletableFuture<String> eth = request(KUCOIN_ETH_ENDPOINT);
        return btc.thenCombine(eth, (btcBody, ethBody) -> parseKucoin(btcBody, ethBody, Instant.now()));
    }

    public CompletableFuture<QuoteSnapshot> fetchOkx() {
        CompletableFuture<String> btc = request(OKX_BTC_ENDPOINT);
        CompletableFuture<String> eth = request(OKX_ETH_ENDPOINT);
        return btc.thenCombine(eth, (btcBody, ethBody) -> parseOkx(btcBody, ethBody, Instant.now()));
    }

    public CompletableFuture<QuoteSnapshot> fetchMexc() {
        CompletableFuture<String> btc = request(MEXC_BTC_ENDPOINT);
        CompletableFuture<String> eth = request(MEXC_ETH_ENDPOINT);
        return btc.thenCombine(eth, (btcBody, ethBody) -> parseMexc(btcBody, ethBody, Instant.now()));
    }

    public CompletableFuture<QuoteSnapshot> fetchBitget() {
        CompletableFuture<String> btc = request(BITGET_BTC_ENDPOINT);
        CompletableFuture<String> eth = request(BITGET_ETH_ENDPOINT);
        return btc.thenCombine(eth, (btcBody, ethBody) -> parseBitget(btcBody, ethBody, Instant.now()));
    }

    public CompletableFuture<QuoteSnapshot> fetchBinance() {
        return request(BINANCE_ENDPOINT)
            .thenApply(body -> parseBinance(body, Instant.now()));
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
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonArray()) {
            JsonObject response = root.getAsJsonObject();
            if (response.has("code"))
                throw warning("binance", response.get("code").getAsString(), response.has("msg") ? response.get("msg").getAsString() : "provider returned a warning code");
        }
        JsonArray rows = requireArray(root);
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

    public static QuoteSnapshot parseMexc(String btcBody, String ethBody, Instant fetchedAt) {
        List<MarketQuote> quotes = List.of(
            exchangeQuote("mexc", Instrument.CRYPTO_BTC, btcBody, "lastPrice", "openPrice"),
            exchangeQuote("mexc", Instrument.CRYPTO_ETH, ethBody, "lastPrice", "openPrice"));
        return snapshot("mexc", quotes, fetchedAt);
    }

    public static QuoteSnapshot parseBitget(String btcBody, String ethBody, Instant fetchedAt) {
        List<MarketQuote> quotes = List.of(
            exchangeQuote("bitget", Instrument.CRYPTO_BTC, btcBody, "lastPr", "open"),
            exchangeQuote("bitget", Instrument.CRYPTO_ETH, ethBody, "lastPr", "open"));
        return snapshot("bitget", quotes, fetchedAt);
    }

    public static QuoteSnapshot parseGate(String btcBody, String ethBody, Instant fetchedAt) {
        return snapshot("gate", List.of(
            gateQuote(Instrument.CRYPTO_BTC, btcBody),
            gateQuote(Instrument.CRYPTO_ETH, ethBody)), fetchedAt);
    }

    public static QuoteSnapshot parseKucoin(String btcBody, String ethBody, Instant fetchedAt) {
        return snapshot("kucoin", List.of(
            kucoinQuote(Instrument.CRYPTO_BTC, btcBody),
            kucoinQuote(Instrument.CRYPTO_ETH, ethBody)), fetchedAt);
    }

    public static QuoteSnapshot parseOkx(String btcBody, String ethBody, Instant fetchedAt) {
        return snapshot("okx", List.of(
            exchangeQuote("okx", Instrument.CRYPTO_BTC, btcBody, "last", "open24h"),
            exchangeQuote("okx", Instrument.CRYPTO_ETH, ethBody, "last", "open24h")), fetchedAt);
    }

    private static MarketQuote gateQuote(Instrument instrument, String body) {
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonArray()) {
            JsonObject response = root.getAsJsonObject();
            throw warning("gate", response.has("label") ? response.get("label").getAsString() : "unknown",
                response.has("message") ? response.get("message").getAsString() : "provider returned a warning response");
        }
        JsonArray rows = root.getAsJsonArray();
        if (rows.isEmpty())
            throw new IllegalArgumentException("gate returned no ticker");
        JsonObject data = rows.get(0).getAsJsonObject();
        return quote(instrument, instrument.name(), data.get("last").getAsString(), data.get("change_percentage").getAsString());
    }

    private static MarketQuote kucoinQuote(Instrument instrument, String body) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        String code = root.has("code") ? root.get("code").getAsString() : "unknown";
        if (!"200000".equals(code))
            throw warning("kucoin", code, root.has("msg") ? root.get("msg").getAsString() : "provider returned a warning code");
        JsonArray rows = root.getAsJsonObject("data").getAsJsonArray("list");
        if (rows == null || rows.isEmpty())
            throw new IllegalArgumentException("kucoin returned no ticker");
        JsonObject data = rows.get(0).getAsJsonObject();
        return quote(instrument, instrument.name(), data.get("lastPrice").getAsString(), percent(
            new BigDecimal(data.get("lastPrice").getAsString()), new BigDecimal(data.get("open").getAsString())));
    }

    private static MarketQuote exchangeQuote(String source, Instrument instrument, String body, String priceField, String openingField) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        if (root.has("code")) {
            String code = root.get("code").getAsString();
            boolean success = "bitget".equals(source) ? "00000".equals(code) : "0".equals(code) || "200".equals(code);
            if (!success)
                throw warning(source, code, root.has("msg") ? root.get("msg").getAsString() : "provider returned a warning code");
        }
        JsonArray rows = root.getAsJsonArray("data");
        JsonObject data = rows == null ? root : rows.get(0).getAsJsonObject();
        return quote(instrument, instrument.name(), data.get(priceField).getAsString(), percent(
            new BigDecimal(data.get(priceField).getAsString()), new BigDecimal(data.get(openingField).getAsString())));
    }

    private static IllegalStateException warning(String source, String code, String message) {
        return new IllegalStateException("provider warning: " + source + " code=" + code + " " + message);
    }

    private static String percent(BigDecimal value, BigDecimal opening) {
        return value.subtract(opening).multiply(BigDecimal.valueOf(100))
            .divide(opening, 8, java.math.RoundingMode.HALF_UP).toPlainString();
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
