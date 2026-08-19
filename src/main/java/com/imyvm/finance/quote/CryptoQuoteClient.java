package com.imyvm.finance.quote;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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
    private static final URI KRAKEN_ENDPOINT = URI.create("https://api.kraken.com/0/public/Ticker?pair=XBTUSD,ETHUSD");
    private static final URI OKX_BTC_ENDPOINT = URI.create("https://www.okx.com/api/v5/market/ticker?instId=BTC-USDT");
    private static final URI OKX_ETH_ENDPOINT = URI.create("https://www.okx.com/api/v5/market/ticker?instId=ETH-USDT");
    private static final URI BYBIT_BTC_ENDPOINT = URI.create("https://api.bybit.com/v5/market/tickers?category=spot&symbol=BTCUSDT");
    private static final URI BYBIT_ETH_ENDPOINT = URI.create("https://api.bybit.com/v5/market/tickers?category=spot&symbol=ETHUSDT");
    private static final String COINBASE_BASE = "https://api.exchange.coinbase.com/products/";
    private final HttpClient httpClient;
    private final Duration requestTimeout;

    public CryptoQuoteClient(Duration connectTimeout, Duration requestTimeout) {
        this.httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        this.requestTimeout = requestTimeout;
    }

    public CompletableFuture<QuoteSnapshot> fetch() {
        return fetchBinance().exceptionallyCompose(error -> fetchCoinbase());
    }

    public CompletableFuture<QuoteSnapshot> fetchKraken() {
        return request(KRAKEN_ENDPOINT).thenApply(body -> parseKraken(body, Instant.now()));
    }

    public CompletableFuture<QuoteSnapshot> fetchOkx() {
        CompletableFuture<String> btc = request(OKX_BTC_ENDPOINT);
        CompletableFuture<String> eth = request(OKX_ETH_ENDPOINT);
        return btc.thenCombine(eth, (btcBody, ethBody) -> parseOkx(btcBody, ethBody, Instant.now()));
    }

    public CompletableFuture<QuoteSnapshot> fetchBybit() {
        CompletableFuture<String> btc = request(BYBIT_BTC_ENDPOINT);
        CompletableFuture<String> eth = request(BYBIT_ETH_ENDPOINT);
        return btc.thenCombine(eth, (btcBody, ethBody) -> parseBybit(btcBody, ethBody, Instant.now()));
    }

    public CompletableFuture<QuoteSnapshot> fetchBinance() {
        return request(BINANCE_ENDPOINT)
            .thenApply(body -> parseBinance(body, Instant.now()));
    }

    public CompletableFuture<QuoteSnapshot> fetchCoinbase() {
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

    public static QuoteSnapshot parseKraken(String body, Instant fetchedAt) {
        JsonObject result = JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("result");
        List<MarketQuote> quotes = new ArrayList<>();
        for (String key : new String[] {"XXBTZUSD", "XETHZUSD"}) {
            JsonObject row = result.getAsJsonObject(key);
            if (row == null) continue;
            Instrument instrument = key.startsWith("XXBT") ? Instrument.CRYPTO_BTC : Instrument.CRYPTO_ETH;
            BigDecimal last = new BigDecimal(row.getAsJsonArray("c").get(0).getAsString());
            BigDecimal opening = new BigDecimal(row.get("o").getAsString());
            quotes.add(quote(instrument, instrument.name(), last.toPlainString(), percent(last, opening)));
        }
        return snapshot("kraken", quotes, fetchedAt);
    }

    public static QuoteSnapshot parseOkx(String btcBody, String ethBody, Instant fetchedAt) {
        List<MarketQuote> quotes = new ArrayList<>();
        for (String body : new String[] {btcBody, ethBody}) {
            JsonObject row = JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("data").get(0).getAsJsonObject();
            Instrument instrument = row.get("instId").getAsString().equals("BTC-USDT") ? Instrument.CRYPTO_BTC : Instrument.CRYPTO_ETH;
            BigDecimal last = new BigDecimal(row.get("last").getAsString());
            BigDecimal opening = new BigDecimal(row.get("open24h").getAsString());
            quotes.add(quote(instrument, instrument.name(), last.toPlainString(), percent(last, opening)));
        }
        return snapshot("okx", quotes, fetchedAt);
    }

    public static QuoteSnapshot parseBybit(String btcBody, String ethBody, Instant fetchedAt) {
        List<MarketQuote> quotes = List.of(
            tickerQuote(Instrument.CRYPTO_BTC, btcBody, "BTCUSDT"),
            tickerQuote(Instrument.CRYPTO_ETH, ethBody, "ETHUSDT"));
        return snapshot("bybit", quotes, fetchedAt);
    }

    private static MarketQuote tickerQuote(Instrument instrument, String body, String symbol) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonArray rows = root.getAsJsonObject("result").getAsJsonArray("list");
        if (rows.size() != 1 || !symbol.equals(rows.get(0).getAsJsonObject().get("symbol").getAsString()))
            throw new IllegalArgumentException("Bybit returned an incomplete ticker");
        JsonObject ticker = rows.get(0).getAsJsonObject();
        BigDecimal last = new BigDecimal(ticker.get("lastPrice").getAsString());
        BigDecimal opening = new BigDecimal(ticker.get("prevPrice24h").getAsString());
        return quote(instrument, instrument.name(), last.toPlainString(), percent(last, opening));
    }

    private static String percent(BigDecimal value, BigDecimal opening) {
        return value.subtract(opening).multiply(BigDecimal.valueOf(100))
            .divide(opening, 8, java.math.RoundingMode.HALF_UP).toPlainString();
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
