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
import java.util.concurrent.atomic.AtomicLong;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DirectMarketQuoteClient {
    private static final Logger LOGGER = LoggerFactory.getLogger("imyvm_finance/quotes");
    private static final long MAX_PROVIDER_BACKOFF_MILLIS = Duration.ofDays(1).toMillis();
    private static final URI CHINA_EASTMONEY = URI.create(
        "https://push2.eastmoney.com/api/qt/ulist.np/get?fltt=2&invt=2&fields=f12,f14,f2,f3"
            + "&secids=1.000001,0.399001,0.399006,1.000300,1.000905,1.518880,1.511090,0.159980");
    private static final URI CHINA_TENCENT = URI.create("https://qt.gtimg.cn/q=s_sh000001,s_sz399001,s_sz399006,s_sh000300,s_sh000905,s_sh518880,s_sh511090,s_sz159980");
    private static final URI CHINA_SINA = URI.create(
        "https://hq.sinajs.cn/list=s_sh000001,s_sz399001,s_sz399006,s_sh000300,s_sh000905,s_sh518880,s_sh511090,s_sz159980");
    private static final URI CHINA_WSCN = URI.create(
        "https://api-ddc-wscn.awtmt.com/market/real?fields=prod_name%2Cprod_code%2Clast_px%2Cpx_change_rate%2Cpreclose_px"
            + "&prod_code=000001.SS%2C399001.SZ%2C399006.SZ%2C000300.SS%2C000905.SS%2C518880.SS%2C511090.SS%2C159980.SZ");
    private static final Map<String, Instrument> CHINA_CODES = Map.of(
        "000001", Instrument.CN_000001,
        "399001", Instrument.CN_399001,
        "399006", Instrument.CN_399006,
        "000300", Instrument.CN_000300,
        "000905", Instrument.CN_000905,
        "518880", Instrument.GOLD_518880,
        "511090", Instrument.BOND_511090,
        "159980", Instrument.FUTURES_159980);
    private final HttpClient httpClient;
    private final Duration requestTimeout;
    private final long providerBackoffMillis;
    private final CryptoQuoteClient cryptoClient;
    private final Map<String, Set<java.time.LocalDate>> marketHolidays;
    private final Map<String, List<String>> providerOrder;
    private final Map<String, String> activeProviders = new ConcurrentHashMap<>();
    private final Map<String, Integer> providerCursors = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> requestCounts = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> failureCounts = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> lastAttemptAt = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> lastSuccessAt = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> lastFailureAt = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> consecutiveFailures = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> consecutiveWarnings = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> backoffUntil = new ConcurrentHashMap<>();
    private final Map<String, String> lastErrors = new ConcurrentHashMap<>();
    private final EnumMap<Instrument, MarketQuote> lastQuotes = new EnumMap<>(Instrument.class);
    private final Set<String> disabledProviders = ConcurrentHashMap.newKeySet();
    private final Map<String, Boolean> marketEnabled;
    private final Set<String> closedMarkets = ConcurrentHashMap.newKeySet();
    private final Set<String> probingMarkets = ConcurrentHashMap.newKeySet();

    public DirectMarketQuoteClient(Duration connectTimeout, Duration requestTimeout) {
        this(connectTimeout, requestTimeout, Map.of());
    }

    public DirectMarketQuoteClient(Duration connectTimeout, Duration requestTimeout, Map<String, Set<java.time.LocalDate>> marketHolidays) {
        this(connectTimeout, requestTimeout, marketHolidays, Map.of("CN", true, "GOLD", true, "BOND", true, "FUTURES", true, "CRYPTO", true), Set.of(), Map.of("CN", List.of("eastmoney", "wscn", "sina", "tencent"), "CRYPTO", List.of("binance", "gate", "kucoin", "okx")), 15);
    }

    public DirectMarketQuoteClient(Duration connectTimeout, Duration requestTimeout, Map<String, Set<java.time.LocalDate>> marketHolidays, Map<String, Boolean> marketEnabled, Set<String> disabledProviders, Map<String, List<String>> providerOrder) {
        this(connectTimeout, requestTimeout, marketHolidays, marketEnabled, disabledProviders, providerOrder, 15);
    }

    public DirectMarketQuoteClient(Duration connectTimeout, Duration requestTimeout, Map<String, Set<java.time.LocalDate>> marketHolidays, Map<String, Boolean> marketEnabled, Set<String> disabledProviders, Map<String, List<String>> providerOrder, long providerBackoffMinutes) {
        this.marketHolidays = Map.copyOf(marketHolidays);
        this.providerBackoffMillis = Math.min(providerBackoffMinutes, Duration.ofDays(1).toMinutes()) * 60_000L;
        this.marketEnabled = new ConcurrentHashMap<>(marketEnabled);
        this.providerOrder = Map.copyOf(providerOrder);
        this.disabledProviders.addAll(disabledProviders);
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
        List<String> chinaMarkets = activeMarkets("CN", fetchedAt);
        if (!chinaMarkets.isEmpty()) {
            boolean probing = probingMarkets.contains("CN");
            try {
                for (Map.Entry<Instrument, MarketQuote> entry : fetchChina().entrySet())
                    if (chinaMarkets.contains(entry.getKey().market()))
                        quotes.put(entry.getKey(), entry.getValue());
                if (probing)
                    for (String market : chinaMarkets) alerts.add("recovered:market:" + market);
            } catch (Exception exception) {
                for (String market : chinaMarkets) alerts.add("failed:market:" + market);
            }
        }
        if (isMarketActive("CRYPTO", fetchedAt)) {
            boolean probing = probingMarkets.contains("CRYPTO");
            try {
                var crypto = fetchCrypto();
                quotes.putAll(crypto.quotes().stream()
                    .collect(java.util.stream.Collectors.toMap(MarketQuote::instrument, quote -> quote)));
                if (probing)
                    alerts.add("recovered:market:CRYPTO");
            } catch (Exception exception) {
                alerts.add("failed:market:CRYPTO");
            }
        }
        lastQuotes.putAll(quotes);
        for (Instrument instrument : Instrument.values()) {
            MarketQuote quote = lastQuotes.get(instrument);
            if (quote == null)
                continue;
            String sourceMarket = instrument.sourceMarket();
            lastQuotes.put(instrument, new MarketQuote(instrument, quote.name(), quote.priceScaled(),
                quote.changeBps(), MarketHours.status(sourceMarket, fetchedAt, marketHolidays.getOrDefault(sourceMarket, Set.of()))));
        }
        if (lastQuotes.isEmpty())
            throw new IllegalStateException("all direct market providers failed");
        long timestamp = fetchedAt.toEpochMilli();
        return new QuoteSnapshot("direct-" + timestamp, "direct", timestamp, timestamp,
            new ArrayList<>(lastQuotes.values()), alerts);
    }

    private List<String> activeMarkets(String sourceMarket, Instant fetchedAt) {
        return Instrument.markets().stream()
            .filter(market -> sourceMarket.equals(Instrument.sourceMarket(market)))
            .filter(market -> isMarketActive(market, fetchedAt))
            .toList();
    }

    private boolean isMarketActive(String market, Instant fetchedAt) {
        String sourceMarket = Instrument.sourceMarket(market);
        return marketEnabled.getOrDefault(market, true) && !closedMarkets.contains(market)
            && MarketHours.status(sourceMarket, fetchedAt, marketHolidays.getOrDefault(sourceMarket, Set.of())) == MarketStatus.OPEN;
    }

    private Map<Instrument, MarketQuote> fetchChina() throws Exception {
        Exception failure = null;
        boolean probing = probingMarkets.contains("CN");
        List<String> providers = providersForAttempt("CN");
        if (probing)
            LOGGER.info("Market outage retry: market=CN provider={}", providers.isEmpty() ? "none" : providers.getFirst());
        for (String provider : providers) {
            if (disabledProviders.contains("CN:" + provider)) continue;
            String statsKey = "CN:" + provider;
            if (isBackedOff(statsKey)) {
                LOGGER.info("Quote provider skipped: market=CN provider={} reason=backoff remainingSeconds={}",
                    provider, backoffSecondsRemaining(statsKey));
                continue;
            }
            recordAttempt(statsKey);
            try {
                Map<Instrument, MarketQuote> result = switch (provider) {
                    case "eastmoney" -> parseEastmoney(requestBytes(CHINA_EASTMONEY), Instant.now());
                    case "wscn" -> parseWscn(requestText(CHINA_WSCN));
                    case "sina" -> parseSina(requestBytes(CHINA_SINA), Instant.now());
                    case "tencent" -> parseTencent(requestBytes(CHINA_TENCENT));
                    default -> throw new IllegalArgumentException("unknown CN provider: " + provider);
                };
                activeProviders.put("CN", provider);
                advanceProviderCursor("CN", provider);
                recordSuccess(statsKey);
                probingMarkets.remove("CN");
                LOGGER.info("Quote provider succeeded: market=CN provider={}", provider);
                return result;
            } catch (Exception exception) {
                recordFailure(statsKey, exception, isProviderWarning(exception));
                LOGGER.warn("Quote provider failed: market=CN provider={} error={}", provider, errorMessage(exception));
                failure = exception;
            }
        }
        probingMarkets.add("CN");
        throw failure == null ? new IllegalStateException("no CN providers enabled") : failure;
    }

    private com.imyvm.finance.market.QuoteSnapshot fetchCrypto() throws Exception {
        Exception failure = null;
        boolean probing = probingMarkets.contains("CRYPTO");
        List<String> providers = providersForAttempt("CRYPTO");
        if (probing)
            LOGGER.info("Market outage retry: market=CRYPTO provider={}", providers.isEmpty() ? "none" : providers.getFirst());
        for (String provider : providers) {
            if (disabledProviders.contains("CRYPTO:" + provider)) continue;
            String statsKey = "CRYPTO:" + provider;
            if (isBackedOff(statsKey)) {
                LOGGER.info("Quote provider skipped: market=CRYPTO provider={} reason=backoff remainingSeconds={}",
                    provider, backoffSecondsRemaining(statsKey));
                continue;
            }
            recordAttempt(statsKey);
            try {
                var result = switch (provider) {
                    case "binance" -> cryptoClient.fetchBinance().join();
                    case "gate" -> cryptoClient.fetchGate().join();
                    case "kucoin" -> cryptoClient.fetchKucoin().join();
                    case "okx" -> cryptoClient.fetchOkx().join();
                    case "mexc" -> cryptoClient.fetchMexc().join();
                    case "bitget" -> cryptoClient.fetchBitget().join();
                    default -> throw new IllegalArgumentException("unknown crypto provider: " + provider);
                };
                activeProviders.put("CRYPTO", provider);
                advanceProviderCursor("CRYPTO", provider);
                recordSuccess(statsKey);
                probingMarkets.remove("CRYPTO");
                LOGGER.info("Quote provider succeeded: market=CRYPTO provider={}", provider);
                return result;
            } catch (Exception exception) {
                recordFailure(statsKey, exception, isProviderWarning(exception));
                LOGGER.warn("Quote provider failed: market=CRYPTO provider={} error={}", provider, errorMessage(exception));
                failure = exception;
            }
        }
        probingMarkets.add("CRYPTO");
        throw failure == null ? new IllegalStateException("no crypto providers enabled") : failure;
    }

    private List<String> providersForAttempt(String market) {
        List<String> scheduled = scheduledProviders(market);
        if (!probingMarkets.contains(market))
            return scheduled;
        for (String provider : scheduled) {
            String key = market + ":" + provider;
            if (!disabledProviders.contains(key) && !isBackedOff(key))
                return List.of(provider);
        }
        return List.of();
    }

    private List<String> scheduledProviders(String market) {
        List<String> providers = providerOrder.getOrDefault(market, List.of());
        if (providers.isEmpty())
            return providers;
        int start = Math.floorMod(providerCursors.getOrDefault(market, 0), providers.size());
        List<String> scheduled = new ArrayList<>(providers.size());
        for (int offset = 0; offset < providers.size(); offset++)
            scheduled.add(providers.get((start + offset) % providers.size()));
        LOGGER.info("Quote provider schedule: market={} start={} providers={}", market, start, scheduled);
        return scheduled;
    }

    private void advanceProviderCursor(String market, String provider) {
        List<String> providers = providerOrder.getOrDefault(market, List.of());
        int index = providers.indexOf(provider);
        if (index >= 0)
            providerCursors.put(market, (index + 1) % providers.size());
    }

    private static String errorMessage(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    public synchronized String controlStatus() {
        return "{\"marketEnabled\":" + jsonMapBoolean(marketEnabled) + ",\"closedMarkets\":" + jsonArray(closedMarkets) + ",\"probingMarkets\":" + jsonArray(probingMarkets)
            + ",\"disabledProviders\":" + jsonArray(disabledProviders)
            + ",\"lastSuccessfulProviders\":" + jsonMap(activeProviders) + ",\"providerOrder\":" + jsonMapList(providerOrder)
            + ",\"statsSince\":" + jsonString(Instant.ofEpochMilli(statsStartedAt).toString())
            + ",\"providerStats\":" + jsonStats() + "}";
    }

    private final long statsStartedAt = System.currentTimeMillis();

    private void recordAttempt(String provider) {
        requestCounts.computeIfAbsent(provider, ignored -> new AtomicLong()).incrementAndGet();
        lastAttemptAt.computeIfAbsent(provider, ignored -> new AtomicLong()).set(System.currentTimeMillis());
    }

    private void recordSuccess(String provider) {
        lastSuccessAt.computeIfAbsent(provider, ignored -> new AtomicLong()).set(System.currentTimeMillis());
        consecutiveFailures.computeIfAbsent(provider, ignored -> new AtomicLong()).set(0);
        consecutiveWarnings.computeIfAbsent(provider, ignored -> new AtomicLong()).set(0);
        backoffUntil.remove(provider);
    }

    private void recordFailure(String provider, Exception exception) {
        recordFailure(provider, exception, false);
    }

    private void recordFailure(String provider, Exception exception, boolean providerWarning) {
        failureCounts.computeIfAbsent(provider, ignored -> new AtomicLong()).incrementAndGet();
        lastFailureAt.computeIfAbsent(provider, ignored -> new AtomicLong()).set(System.currentTimeMillis());
        consecutiveFailures.computeIfAbsent(provider, ignored -> new AtomicLong()).incrementAndGet();
        lastErrors.put(provider, exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
        if (!providerWarning)
            return;
        long warnings = consecutiveWarnings.computeIfAbsent(provider, ignored -> new AtomicLong()).incrementAndGet();
        long multiplier = 1L << Math.min(warnings - 1, 7);
        long cooldown = Math.min(providerBackoffMillis * multiplier, MAX_PROVIDER_BACKOFF_MILLIS);
        backoffUntil.computeIfAbsent(provider, ignored -> new AtomicLong()).set(System.currentTimeMillis() + cooldown);
    }

    private static boolean isProviderWarning(Throwable throwable) {
        Throwable cause = throwable;
        while (cause != null) {
            if (cause.getMessage() != null && cause.getMessage().startsWith("provider warning:"))
                return true;
            cause = cause.getCause();
        }
        return false;
    }

    private boolean isBackedOff(String provider) {
        AtomicLong until = backoffUntil.get(provider);
        return until != null && until.get() > System.currentTimeMillis();
    }

    private String jsonStats() {
        return requestCounts.keySet().stream().sorted().map(provider -> {
            long requests = requestCounts.get(provider).get();
            long failures = failureCounts.getOrDefault(provider, new AtomicLong()).get();
            double rate = requests == 0 ? 0 : failures * 100.0 / requests;
            return jsonString(provider) + ":{\"requests\":" + requests + ",\"failures\":" + failures
                + ",\"failureRatePercent\":" + String.format(Locale.ROOT, "%.2f", rate)
                + ",\"lastAttemptAt\":" + jsonTime(lastAttemptAt.get(provider))
                + ",\"lastSuccessAt\":" + jsonTime(lastSuccessAt.get(provider))
                + ",\"lastFailureAt\":" + jsonTime(lastFailureAt.get(provider))
                + ",\"consecutiveFailures\":" + consecutiveFailures.getOrDefault(provider, new AtomicLong()).get()
                + ",\"consecutiveWarnings\":" + consecutiveWarnings.getOrDefault(provider, new AtomicLong()).get()
                + ",\"backoffUntil\":" + jsonTime(backoffUntil.get(provider))
                + ",\"backoffSecondsRemaining\":" + backoffSecondsRemaining(provider)
                + ",\"lastError\":" + jsonString(lastErrors.get(provider)) + "}";
        }).collect(java.util.stream.Collectors.joining(",", "{", "}"));
    }

    private long backoffSecondsRemaining(String provider) {
        AtomicLong until = backoffUntil.get(provider);
        if (until == null) return 0;
        return Math.max(0, (until.get() - System.currentTimeMillis() + 999) / 1000);
    }

    private static String jsonTime(AtomicLong time) {
        return time == null || time.get() == 0 ? "null" : jsonString(Instant.ofEpochMilli(time.get()).toString());
    }

    private static String jsonString(String value) {
        if (value == null) return "null";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String jsonArray(Set<String> values) {
        return values.stream().sorted().map(value -> "\"" + value + "\"").collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private static String jsonMapBoolean(Map<String, Boolean> values) {
        return values.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(entry -> "\"" + entry.getKey() + "\":" + entry.getValue()).collect(java.util.stream.Collectors.joining(",", "{", "}"));
    }

    private static String jsonMap(Map<String, String> values) {
        return values.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(entry -> "\"" + entry.getKey() + "\":\"" + entry.getValue() + "\"").collect(java.util.stream.Collectors.joining(",", "{", "}"));
    }

    private static String jsonMapList(Map<String, List<String>> values) {
        return values.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(entry -> "\"" + entry.getKey() + "\":" + entry.getValue().stream().map(value -> "\"" + value + "\"").collect(java.util.stream.Collectors.joining(",", "[", "]"))).collect(java.util.stream.Collectors.joining(",", "{", "}"));
    }

    public synchronized void clearProviderBackoff() {
        backoffUntil.clear();
        consecutiveFailures.clear();
        consecutiveWarnings.clear();
        probingMarkets.clear();
    }

    public synchronized void setMarketEnabled(String market, boolean enabled) {
        marketEnabled.put(market, enabled);
        if (enabled)
            closedMarkets.remove(market);
        else
            closedMarkets.add(market);
    }

    public synchronized void setProviderEnabled(String market, String provider, boolean enabled) {
        String key = switch (market + ":" + provider) {
            case "HK:global", "US:global", "HK:yahoo", "US:yahoo" -> market + ":" + provider;
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

    public static Map<Instrument, MarketQuote> parseTencent(byte[] body) {
        String text = new String(body, Charset.forName("GB18030"));
        EnumMap<Instrument, MarketQuote> result = new EnumMap<>(Instrument.class);
        for (String line : text.split("\\n")) {
            int start = line.indexOf("=\"");
            if (start < 0) continue;
            String[] fields = line.substring(start + 2).replace("\";", "").split("~", -1);
            if (fields.length < 6) continue;
            Instrument instrument = CHINA_CODES.get(fields[2]);
            if (instrument != null) result.put(instrument, quote(instrument, fields[3], fields[5]));
        }
        if (result.size() != CHINA_CODES.size())
            throw new IllegalArgumentException("Tencent returned incomplete China quotes");
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
            result.put(instrument, quote(instrument, fields[1], fields[3]));
        }
        if (result.size() != CHINA_CODES.size())
            throw new IllegalArgumentException("Sina returned incomplete China quotes");
        return result;
    }

    public static Map<Instrument, MarketQuote> parseWscn(String body) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        String code = root.has("code") ? root.get("code").getAsString() : "unknown";
        if (!"20000".equals(code))
            throw new IllegalStateException("provider warning: wscn code=" + code + " "
                + (root.has("message") ? root.get("message").getAsString() : "provider returned a warning code"));
        JsonObject rows = root.getAsJsonObject("data").getAsJsonObject("snapshot");
        EnumMap<Instrument, MarketQuote> result = new EnumMap<>(Instrument.class);
        for (Map.Entry<String, com.google.gson.JsonElement> entry : rows.entrySet()) {
            Instrument instrument = CHINA_CODES.get(entry.getKey().substring(0, 6));
            JsonArray row = entry.getValue().getAsJsonArray();
            if (instrument != null && row.size() > 3)
                result.put(instrument, quote(instrument, row.get(2).getAsString(), row.get(3).getAsString()));
        }
        if (result.size() != CHINA_CODES.size())
            throw new IllegalArgumentException("WSCN returned incomplete China quotes");
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
