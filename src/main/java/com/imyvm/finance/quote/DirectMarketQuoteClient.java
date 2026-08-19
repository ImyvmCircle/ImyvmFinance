package com.imyvm.finance.quote;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.imyvm.finance.market.Instrument;
import com.imyvm.finance.market.MarketQuote;
import com.imyvm.finance.market.MarketStatus;
import com.imyvm.finance.market.QuoteSnapshot;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

public final class DirectMarketQuoteClient {
    private static final URI CHINA_EASTMONEY = URI.create(
        "https://push2.eastmoney.com/api/qt/ulist.np/get?fltt=2&invt=2&fields=f12,f14,f2,f3"
            + "&secids=1.000001,0.399001,0.399006,1.000300,1.000905");
    private static final URI CHINA_SINA = URI.create(
        "https://hq.sinajs.cn/list=s_sh000001,s_sz399001,s_sz399006,s_sh000300,s_sh000905");
    private static final Map<Instrument, String> YAHOO_SYMBOLS = Map.of(
        Instrument.HK_HSI, "^HSI",
        Instrument.HK_HSTECH, "^HSTECH",
        Instrument.US_DJI, "^DJI",
        Instrument.US_SPX, "^GSPC",
        Instrument.US_NDX, "^NDX");
    private static final Map<String, Instrument> CHINA_CODES = Map.of(
        "000001", Instrument.CN_000001,
        "399001", Instrument.CN_399001,
        "399006", Instrument.CN_399006,
        "000300", Instrument.CN_000300,
        "000905", Instrument.CN_000905);
    private final HttpClient httpClient;
    private final Duration requestTimeout;
    private final CryptoQuoteClient cryptoClient;
    private final Map<String, Set<java.time.LocalDate>> marketHolidays;
    private final EnumMap<Instrument, MarketQuote> lastQuotes = new EnumMap<>(Instrument.class);
    private final Set<String> disabledProviders = ConcurrentHashMap.newKeySet();
    private final Set<String> closedMarkets = ConcurrentHashMap.newKeySet();

    public DirectMarketQuoteClient(Duration connectTimeout, Duration requestTimeout) {
        this(connectTimeout, requestTimeout, Map.of());
    }

    public DirectMarketQuoteClient(Duration connectTimeout, Duration requestTimeout, Map<String, Set<java.time.LocalDate>> marketHolidays) {
        this.marketHolidays = Map.copyOf(marketHolidays);
        this.httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        this.requestTimeout = requestTimeout;
        this.cryptoClient = new CryptoQuoteClient(connectTimeout, requestTimeout);
    }

    public CompletableFuture<QuoteSnapshot> fetch() {
        return CompletableFuture.supplyAsync(this::fetchBlocking);
    }

    private QuoteSnapshot fetchBlocking() {
        Instant fetchedAt = Instant.now();
        EnumMap<Instrument, MarketQuote> quotes = new EnumMap<>(Instrument.class);
        List<String> alerts = new ArrayList<>();
        if (!closedMarkets.contains("CN") && MarketHours.status("CN", fetchedAt, marketHolidays.getOrDefault("CN", Set.of())) == MarketStatus.OPEN) {
            try {
                quotes.putAll(fetchChina());
            } catch (Exception exception) {
                alerts.add("failed:market:CN");
            }
        }
        if ((!closedMarkets.contains("HK") && MarketHours.status("HK", fetchedAt, marketHolidays.getOrDefault("HK", Set.of())) == MarketStatus.OPEN)
            || (!closedMarkets.contains("US") && MarketHours.status("US", fetchedAt, marketHolidays.getOrDefault("US", Set.of())) == MarketStatus.OPEN)) {
            try {
                quotes.putAll(fetchGlobal());
            } catch (Exception exception) {
                alerts.add("failed:market:HK_US");
            }
        }
        if (!closedMarkets.contains("CRYPTO")) try {
            var crypto = disabledProviders.contains("CRYPTO:binance")
                ? cryptoClient.fetchCoinbase().join()
                : disabledProviders.contains("CRYPTO:coinbase")
                    ? cryptoClient.fetchBinance().join()
                    : cryptoClient.fetch().join();
            quotes.putAll(crypto.quotes().stream()
                .collect(java.util.stream.Collectors.toMap(MarketQuote::instrument, quote -> quote)));
        } catch (Exception exception) {
            alerts.add("failed:market:CRYPTO");
        }
        lastQuotes.putAll(quotes);
        for (Instrument instrument : Instrument.values()) {
            MarketQuote quote = lastQuotes.get(instrument);
            if (quote == null)
                continue;
            lastQuotes.put(instrument, new MarketQuote(instrument, quote.name(), quote.priceScaled(),
                quote.changeBps(), MarketHours.status(instrument.market(), fetchedAt, marketHolidays.getOrDefault(instrument.market(), Set.of()))));
        }
        if (lastQuotes.isEmpty())
            throw new IllegalStateException("all direct market providers failed");
        long timestamp = fetchedAt.toEpochMilli();
        return new QuoteSnapshot("direct-" + timestamp, "direct", timestamp, timestamp,
            new ArrayList<>(lastQuotes.values()), alerts);
    }

    private Map<Instrument, MarketQuote> fetchChina() throws Exception {
        try {
            if (disabledProviders.contains("CN:eastmoney"))
                throw new IllegalStateException("Eastmoney disabled");
            return parseEastmoney(requestBytes(CHINA_EASTMONEY), Instant.now());
        } catch (Exception primary) {
            if (disabledProviders.contains("CN:sina"))
                throw primary;
            return parseSina(requestBytes(CHINA_SINA), Instant.now());
        }
    }

    private Map<Instrument, MarketQuote> fetchGlobal() throws Exception {
        if (disabledProviders.contains("GLOBAL:yahoo"))
            throw new IllegalStateException("Yahoo disabled");
        EnumMap<Instrument, MarketQuote> result = new EnumMap<>(Instrument.class);
        Instant now = Instant.now();
        for (Map.Entry<Instrument, String> entry : YAHOO_SYMBOLS.entrySet()) {
            if (!closedMarkets.contains(entry.getKey().market())
                && MarketHours.status(entry.getKey().market(), now, marketHolidays.getOrDefault(entry.getKey().market(), Set.of())) == MarketStatus.OPEN)
                result.put(entry.getKey(), parseYahoo(requestText(yahooUri(entry.getValue())), entry.getKey()));
        }
        return result;
    }

    public synchronized String controlStatus() {
        return "{\"closedMarkets\":" + closedMarkets + ",\"disabledProviders\":" + disabledProviders + "}";
    }

    public synchronized void setMarketEnabled(String market, boolean enabled) {
        if (enabled)
            closedMarkets.remove(market);
        else
            closedMarkets.add(market);
    }

    public synchronized void setProviderEnabled(String market, String provider, boolean enabled) {
        String key = switch (market + ":" + provider) {
            case "HK:global", "US:global", "HK:yahoo", "US:yahoo" -> "GLOBAL:yahoo";
            default -> market + ":" + provider;
        };
        if (enabled)
            disabledProviders.remove(key);
        else
            disabledProviders.add(key);
    }

    public static Map<Instrument, MarketQuote> parseEastmoney(byte[] body, Instant fetchedAt) {
        JsonObject data = JsonParser.parseString(new String(body, StandardCharsets.UTF_8))
            .getAsJsonObject().getAsJsonObject("data");
        JsonArray rows = data.getAsJsonArray("diff");
        EnumMap<Instrument, MarketQuote> result = new EnumMap<>(Instrument.class);
        for (var element : rows) {
            JsonObject row = element.getAsJsonObject();
            Instrument instrument = CHINA_CODES.get(row.get("f12").getAsString());
            if (instrument != null)
                result.put(instrument, quote(instrument, row.get("f2").getAsString(), row.get("f3").getAsString()));
        }
        if (result.size() != CHINA_CODES.size())
            throw new IllegalArgumentException("Eastmoney returned incomplete China quotes");
        return result;
    }

    public static Map<Instrument, MarketQuote> parseSina(byte[] body, Instant fetchedAt) {
        String text = new String(body, Charset.forName("GB18030"));
        EnumMap<Instrument, MarketQuote> result = new EnumMap<>(Instrument.class);
        for (String line : text.split(";")) {
            int nameStart = line.indexOf("=\"");
            if (nameStart < 0)
                continue;
            String key = line.substring(line.indexOf("list=") + 5, nameStart);
            String[] fields = line.substring(nameStart + 2).replace("\"", "").split(",", -1);
            String code = key.substring(key.length() - 6);
            Instrument instrument = CHINA_CODES.get(code);
            if (instrument == null || fields.length < 4)
                continue;
            BigDecimal current = new BigDecimal(fields[3]);
            BigDecimal previous = new BigDecimal(fields[2]);
            BigDecimal change = current.subtract(previous).multiply(BigDecimal.valueOf(100))
                .divide(previous, 8, java.math.RoundingMode.HALF_UP);
            result.put(instrument, quote(instrument, current.toPlainString(), change.toPlainString()));
        }
        if (result.size() != CHINA_CODES.size())
            throw new IllegalArgumentException("Sina returned incomplete China quotes");
        return result;
    }

    public static MarketQuote parseYahoo(String body, Instrument instrument) {
        JsonObject meta = JsonParser.parseString(body).getAsJsonObject()
            .getAsJsonObject("chart").getAsJsonArray("result").get(0).getAsJsonObject()
            .getAsJsonObject("meta");
        BigDecimal price = new BigDecimal(meta.get("regularMarketPrice").getAsString());
        BigDecimal previous = new BigDecimal(meta.get("previousClose").getAsString());
        BigDecimal change = price.subtract(previous).multiply(BigDecimal.valueOf(100))
            .divide(previous, 8, java.math.RoundingMode.HALF_UP);
        return quote(instrument, price.toPlainString(), change.toPlainString());
    }

    private static MarketQuote quote(Instrument instrument, String price, String changePercent) {
        return new MarketQuote(instrument, instrument.name(),
            new BigDecimal(price).movePointRight(4).longValueExact(),
            new BigDecimal(changePercent).movePointRight(2).longValue(), MarketStatus.OPEN);
    }

    private byte[] requestBytes(URI uri) throws Exception {
        return request(uri, HttpResponse.BodyHandlers.ofByteArray()).body();
    }

    private String requestText(URI uri) throws Exception {
        return request(uri, HttpResponse.BodyHandlers.ofString()).body();
    }

    private <T> HttpResponse<T> request(URI uri, HttpResponse.BodyHandler<T> handler) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(requestTimeout)
            .header("Accept", "application/json")
            .header("User-Agent", "ImyvmFinance/1.0")
            .header("Referer", "https://finance.sina.com.cn/")
            .GET().build();
        HttpResponse<T> response = httpClient.send(request, handler);
        if (response.statusCode() / 100 != 2)
            throw new IOException("market provider returned HTTP " + response.statusCode());
        return response;
    }

    private static URI yahooUri(String symbol) {
        return URI.create("https://query1.finance.yahoo.com/v8/finance/chart/" + symbol + "?range=1d&interval=1m");
    }
}
