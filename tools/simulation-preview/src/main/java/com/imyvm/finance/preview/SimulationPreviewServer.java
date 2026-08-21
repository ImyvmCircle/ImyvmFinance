package com.imyvm.finance.preview;

import com.imyvm.finance.FinanceConfig;
import com.imyvm.finance.market.Instrument;
import com.imyvm.finance.market.MarketQuote;
import com.imyvm.finance.market.MarketStatus;
import com.imyvm.finance.quote.SimulatedQuoteGenerator;
import com.imyvm.finance.quote.SimulationModelConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Executors;

public final class SimulationPreviewServer {
    private static final Set<String> MODEL_FIELDS = Set.of(
        "volatility-multiplier", "trend-multiplier", "jump-multiplier",
        "common-factor-multiplier", "duration-multiplier", "downside-multiplier",
        "student-t-df-adjustment");
    private static final Set<String> STAT_FIELDS = Set.of(
        "annual-volatility", "downside-volatility-multiplier", "student-t-df",
        "garch-alpha", "garch-beta", "jump-rate-per-year", "jump-up-min",
        "jump-up-max", "jump-down-min", "jump-down-max", "jump-down-probability",
        "max-step-percent", "idiosyncratic-variance-floor", "trading-days-per-year",
        "trading-hours-per-day", "long-duration-min-days", "long-duration-max-days",
        "long-up-min", "long-up-max", "long-down-min", "long-down-max",
        "medium-duration-min-days", "medium-duration-max-days", "medium-up-min",
        "medium-up-max", "medium-down-min", "medium-down-max",
        "short-duration-min-days", "short-duration-max-days", "short-up-min",
        "short-up-max", "short-down-min", "short-down-max", "loading-cn-market",
        "loading-cn-style", "loading-crypto-market", "loading-risk-appetite",
        "loading-inflation-rate");
    private static final Set<String> CORRELATION_FIELDS = Set.of(
        "simulation.correlation.minimum-scale",
        "simulation.correlation.maximum-scale",
        "simulation.correlation.cycle-effective-days");
    private static final int MAX_POINTS = 250_000;

    private final Path root;
    private final Path modelFile;

    private SimulationPreviewServer(Path root) {
        this.root = root;
        this.modelFile = root.resolve(
            "src/main/resources/imyvm_finance/simulation-models.properties");
    }

    public static void main(String[] args) throws Exception {
        int port = args.length == 0 ? 8765 : Integer.parseInt(args[0]);
        SimulationPreviewServer application = new SimulationPreviewServer(findRoot());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", application::route);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.println("Simulation preview: http://127.0.0.1:" + port);
        System.out.println("Configuration: " + application.modelFile);
    }

    private void route(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/") && exchange.getRequestMethod().equals("GET")) {
                send(exchange, 200, "text/html; charset=utf-8", resource("/preview/index.html"));
            } else if (path.equals("/api/config") && exchange.getRequestMethod().equals("GET")) {
                sendJson(exchange, 200, configJson());
            } else if (path.equals("/api/simulate") && exchange.getRequestMethod().equals("POST")) {
                sendJson(exchange, 200, simulate(parseForm(exchange)));
            } else if (path.equals("/api/save") && exchange.getRequestMethod().equals("POST")) {
                sendJson(exchange, 200, save(parseForm(exchange)));
            } else {
                sendJson(exchange, 404, error("not found"));
            }
        } catch (IllegalArgumentException exception) {
            sendJson(exchange, 400, error(exception.getMessage()));
        } catch (Exception exception) {
            sendJson(exchange, 500, error(exception.getMessage()));
        } finally {
            exchange.close();
        }
    }

    private String configJson() throws IOException {
        Properties raw = readProperties();
        SimulationModelConfig config = SimulationModelConfig.fromProperties(raw);
        Map<String, Long> prices = FinanceConfig.defaults().simulationDefaultPrices();
        StringBuilder result = new StringBuilder("{\"defaultModel\":");
        appendJson(result, config.defaultModelId());
        result.append(",\"models\":[");
        appendStrings(result, config.modelIds());
        result.append("],\"instruments\":[");
        boolean first = true;
        for (Instrument instrument : Instrument.values()) {
            if (!first)
                result.append(',');
            first = false;
            SimulationModelConfig.InstrumentStats stats = config.instrument(instrument);
            result.append("{\"id\":");
            appendJson(result, instrument.commandForm());
            result.append(",\"symbol\":");
            appendJson(result, instrument.symbol());
            result.append(",\"market\":");
            appendJson(result, instrument.market());
            result.append(",\"profile\":");
            appendJson(result, stats.profile());
            result.append(",\"hours\":").append(stats.tradingHoursPerDay());
            result.append(",\"defaultPrice\":")
                .append(prices.getOrDefault(instrument.symbol(), 10_000L) / 10_000.0);
            result.append('}');
        }
        result.append("],\"properties\":{");
        first = true;
        List<String> keys = raw.stringPropertyNames().stream().sorted().toList();
        for (String key : keys) {
            if (!first)
                result.append(',');
            first = false;
            appendJson(result, key);
            result.append(':');
            appendJson(result, raw.getProperty(key));
        }
        return result.append("}}").toString();
    }

    private String simulate(Map<String, String> form) throws IOException {
        Properties raw = readProperties();
        String modelId = required(form, "preview.model");
        Instrument instrument = instrument(required(form, "preview.instrument"));
        applyAdjustments(raw, form, modelId, instrument);
        SimulationModelConfig config = SimulationModelConfig.fromProperties(raw);
        config.model(modelId);
        SimulationModelConfig.InstrumentStats stats = config.instrument(instrument);
        int factor = integer(form, "preview.factor", 0, 5);
        long seed = longValue(form, "preview.seed", Long.MIN_VALUE, Long.MAX_VALUE);
        double days = decimal(form, "preview.days", 0.01, 730.0);
        double start = decimal(form, "preview.start", 0.0001, 1_000_000_000.0);
        int points = (int) Math.ceil(days * stats.tradingHoursPerDay() * 20.0);
        if (points > MAX_POINTS)
            throw new IllegalArgumentException("preview exceeds " + MAX_POINTS
                + " three-minute points");
        long startScaled = Math.max(1L, Math.round(start * 10_000.0));
        MarketQuote quote = new MarketQuote(instrument, instrument.symbol(), startScaled,
            0, MarketStatus.OPEN);
        SimulatedQuoteGenerator.State state = SimulatedQuoteGenerator.State.initial();
        double cumulativeLong = 0.0;
        double cumulativeMedium = 0.0;
        double cumulativeShort = 0.0;
        long minimum = startScaled;
        long maximum = startScaled;
        int positive = 0;
        int negative = 0;
        int switchCount = 0;
        StringBuilder samples = new StringBuilder();
        StringBuilder switches = new StringBuilder();
        for (int iteration = 1; iteration <= points; iteration++) {
            SimulatedQuoteGenerator.Step step = SimulatedQuoteGenerator.nextStep(
                instrument, quote, seed, iteration, factor, state, config, modelId, 180_000L);
            quote = step.quote();
            state = step.state();
            cumulativeLong += step.longBps() / 10_000.0;
            cumulativeMedium += step.mediumBps() / 10_000.0;
            cumulativeShort += step.shortBps() / 10_000.0;
            minimum = Math.min(minimum, quote.priceScaled());
            maximum = Math.max(maximum, quote.priceScaled());
            if (step.appliedBps() > 0.0)
                positive++;
            else if (step.appliedBps() < 0.0)
                negative++;
            if (iteration > 1)
                samples.append(',');
            double effectiveDay = iteration / (stats.tradingHoursPerDay() * 20.0);
            samples.append('[').append(number(effectiveDay)).append(',')
                .append(number((quote.priceScaled() / (double) startScaled - 1.0) * 100.0))
                .append(',').append(number(Math.expm1(cumulativeLong) * 100.0))
                .append(',').append(number(Math.expm1(cumulativeMedium) * 100.0))
                .append(',').append(number(Math.expm1(cumulativeShort) * 100.0))
                .append(']');
            for (String event : step.switches()) {
                if (switchCount++ > 0)
                    switches.append(',');
                String[] parts = event.split(":", 2);
                switches.append('[').append(iteration - 1).append(',')
                    .append(number(effectiveDay)).append(',');
                appendJson(switches, parts[0]);
                switches.append(',');
                appendJson(switches, parts[1]);
                switches.append(']');
            }
        }
        return "{\"points\":[" + samples + "],\"switches\":[" + switches
            + "],\"summary\":{\"points\":" + points
            + ",\"final\":" + number(quote.priceScaled() / 10_000.0)
            + ",\"changePercent\":"
            + number((quote.priceScaled() / (double) startScaled - 1.0) * 100.0)
            + ",\"minimum\":" + number(minimum / 10_000.0)
            + ",\"maximum\":" + number(maximum / 10_000.0)
            + ",\"positive\":" + positive + ",\"negative\":" + negative
            + ",\"switches\":" + switchCount + "}}";
    }

    private String save(Map<String, String> form) throws Exception {
        String modelId = required(form, "preview.model");
        Instrument instrument = instrument(required(form, "preview.instrument"));
        String summary = required(form, "preview.summary").trim();
        if (summary.length() > 72 || summary.contains("\n") || summary.contains("\r"))
            throw new IllegalArgumentException("commit summary must be one line and at most 72 characters");
        String status = git("status", "--porcelain");
        if (!status.isBlank())
            throw new IllegalArgumentException("working tree must be clean before saving:\n" + status.trim());
        Properties current = readProperties();
        Properties proposed = new Properties();
        proposed.putAll(current);
        Map<String, String> adjustments = applyAdjustments(
            proposed, form, modelId, instrument);
        if (adjustments.isEmpty())
            throw new IllegalArgumentException("no parameter changes to save");
        SimulationModelConfig.fromProperties(proposed);
        String original = Files.readString(modelFile);
        String updated = updateProperties(original, adjustments);
        Path temporary = Files.createTempFile(modelFile.getParent(), "simulation-models-", ".tmp");
        try {
            Files.writeString(temporary, updated);
            Files.move(temporary, modelFile, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
            SimulationModelConfig.loadFile(modelFile);
            git("diff", "--check", "--", root.relativize(modelFile).toString());
            git("add", "--", root.relativize(modelFile).toString());
            try {
                git("commit", "-m", "chore: " + summary, "--",
                    root.relativize(modelFile).toString());
            } catch (Exception exception) {
                git("restore", "--staged", "--", root.relativize(modelFile).toString());
                Files.writeString(modelFile, original);
                throw exception;
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return "{\"ok\":true,\"message\":\"saved and committed\",\"commit\":"
            + quoted(git("rev-parse", "--short", "HEAD").trim()) + "}";
    }

    private Map<String, String> applyAdjustments(
        Properties properties,
        Map<String, String> form,
        String modelId,
        Instrument instrument
    ) {
        String modelPrefix = "simulation.models." + modelId + ".";
        String instrumentPrefix = "simulation.instruments." + instrument.commandForm() + ".";
        Map<String, String> applied = new LinkedHashMap<>();
        for (var entry : form.entrySet()) {
            String key = entry.getKey();
            boolean allowed = key.startsWith(modelPrefix)
                && MODEL_FIELDS.contains(key.substring(modelPrefix.length()));
            allowed |= key.startsWith(instrumentPrefix)
                && STAT_FIELDS.contains(key.substring(instrumentPrefix.length()));
            allowed |= CORRELATION_FIELDS.contains(key);
            if (!allowed)
                continue;
            String value = entry.getValue().trim();
            if (!value.equals(properties.getProperty(key))) {
                properties.setProperty(key, value);
                applied.put(key, value);
            }
        }
        return applied;
    }

    private static String updateProperties(String source, Map<String, String> changes) {
        List<String> lines = new ArrayList<>(source.lines().toList());
        Map<String, String> remaining = new LinkedHashMap<>(changes);
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            int separator = line.indexOf('=');
            if (separator < 1)
                continue;
            String key = line.substring(0, separator).trim();
            String replacement = remaining.remove(key);
            if (replacement != null)
                lines.set(index, key + "=" + replacement);
        }
        if (!remaining.isEmpty()) {
            lines.add("");
            remaining.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> lines.add(entry.getKey() + "=" + entry.getValue()));
        }
        return String.join(System.lineSeparator(), lines) + System.lineSeparator();
    }

    private Properties readProperties() throws IOException {
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(modelFile)) {
            properties.load(reader);
        }
        return properties;
    }

    private String git(String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).directory(root.toFile())
            .redirectErrorStream(true).start();
        String output;
        try (InputStream input = process.getInputStream()) {
            output = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        int status = process.waitFor();
        if (status != 0)
            throw new IllegalStateException(String.join(" ", command) + " failed: " + output.trim());
        return output;
    }

    private static Path findRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve(
                "src/main/resources/imyvm_finance/simulation-models.properties")))
                return current;
            current = current.getParent();
        }
        throw new IllegalStateException("run simulationPreview from the ImyvmFinance repository");
    }

    private static Instrument instrument(String value) {
        Instrument instrument = Instrument.fromSymbol(value);
        if (instrument == null)
            throw new IllegalArgumentException("unknown instrument: " + value);
        return instrument;
    }

    private static Map<String, String> parseForm(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> result = new LinkedHashMap<>();
        if (body.isBlank())
            return result;
        for (String field : body.split("&")) {
            String[] pair = field.split("=", 2);
            String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
            String value = pair.length == 1 ? ""
                : URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
            result.put(key, value);
        }
        return result;
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("missing " + key);
        return value;
    }

    private static int integer(Map<String, String> values, String key, int minimum, int maximum) {
        long value = longValue(values, key, minimum, maximum);
        return (int) value;
    }

    private static long longValue(Map<String, String> values, String key, long minimum, long maximum) {
        try {
            long value = Long.parseLong(required(values, key));
            if (value < minimum || value > maximum)
                throw new NumberFormatException();
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(key + " must be between " + minimum + " and " + maximum);
        }
    }

    private static double decimal(Map<String, String> values, String key, double minimum, double maximum) {
        try {
            double value = Double.parseDouble(required(values, key));
            if (!Double.isFinite(value) || value < minimum || value > maximum)
                throw new NumberFormatException();
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(key + " must be between " + minimum + " and " + maximum);
        }
    }

    private static String resource(String path) throws IOException {
        try (InputStream input = SimulationPreviewServer.class.getResourceAsStream(path)) {
            if (input == null)
                throw new IOException("missing preview resource: " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        send(exchange, status, "application/json; charset=utf-8", body);
    }

    private static void send(HttpExchange exchange, int status, String type, String body)
        throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("Content-Security-Policy",
            "default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static String error(String message) {
        return "{\"error\":" + quoted(message == null ? "unexpected error" : message) + "}";
    }

    private static void appendStrings(StringBuilder target, List<String> values) {
        for (int index = 0; index < values.size(); index++) {
            if (index > 0)
                target.append(',');
            appendJson(target, values.get(index));
        }
    }

    private static String quoted(String value) {
        StringBuilder result = new StringBuilder();
        appendJson(result, value);
        return result.toString();
    }

    private static void appendJson(StringBuilder target, String value) {
        target.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> target.append("\\\"");
                case '\\' -> target.append("\\\\");
                case '\n' -> target.append("\\n");
                case '\r' -> target.append("\\r");
                case '\t' -> target.append("\\t");
                default -> target.append(character);
            }
        }
        target.append('"');
    }

    private static String number(double value) {
        if (!Double.isFinite(value))
            return "0";
        return Double.toString(value);
    }
}
