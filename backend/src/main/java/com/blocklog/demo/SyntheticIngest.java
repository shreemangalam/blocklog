package com.blocklog.demo;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Random;

/**
 * Synthetic log generator for demo and manual load tests.
 *
 * Posts realistic microservice logs to /api/v1/logs for a chosen tenant.
 * Distribution is roughly the shape you see in real traffic — mostly INFO,
 * some WARN, occasional ERROR — spread across a handful of named services
 * with correlated tags (env, service, level, region). Message bodies carry
 * fake user/order/sku ids so keyword searches ("payment", "timeout",
 * "SKU-4172") return interesting result sets in the UI.
 *
 * Deterministic: the same --seed always emits the same records, so a demo
 * or a regression can be repeated.
 *
 * Usage: java -cp target/classes com.blocklog.demo.SyntheticIngest \
 *          [--tenant demo-shop] [--count 1000] [--batch 25] \
 *          [--seed 42] [--url http://localhost:8080] [--hours 24]
 *
 * A 429 response is treated as backpressure and retried after a short
 * sleep. Any other non-2xx aborts with a nonzero exit code.
 */
public final class SyntheticIngest {

    private static final String[] ENVS = {"prod", "prod", "prod", "staging", "dev"};
    private static final String[] REGIONS = {"us-east-1", "us-west-2", "eu-west-1", "ap-south-1"};

    private static final Template[] TEMPLATES = {
            // INFO — normal operation, most traffic
            info("web-gateway", "request GET /api/orders/%ORDER% 200 in %MS%ms user=%USER%"),
            info("web-gateway", "request POST /api/checkout 202 in %MS%ms user=%USER%"),
            info("web-gateway", "request GET /api/products/%SKU% 200 in %MS%ms"),
            info("checkout",    "order created id=%ORDER% user=%USER% amount=%AMOUNT% USD items=%ITEMS%"),
            info("checkout",    "cart updated user=%USER% items=%ITEMS%"),
            info("search",      "query executed q=\"%QUERY%\" results=%RESULTS% in %MS%ms"),
            info("payments",    "payment authorized order=%ORDER% amount=%AMOUNT% card=****%CARD%"),
            info("payments",    "settlement batch closed count=%RESULTS% total=%AMOUNT%"),
            info("inventory",   "stock updated sku=%SKU% delta=%DELTA% remaining=%STOCK%"),
            info("inventory",   "warehouse sync completed sku=%SKU% at %MS%ms"),
            info("email",       "notification sent to user=%USER% template=order-confirmation"),
            info("email",       "notification sent to user=%USER% template=shipping-update"),
            // WARN — degraded, notable
            warn("web-gateway", "upstream response 502 from checkout retry=%RETRY%"),
            warn("web-gateway", "request GET /api/products/%SKU% slow %MS%ms threshold_ms=200"),
            warn("search",      "slow query q=\"%QUERY%\" %MS%ms threshold_ms=100"),
            warn("inventory",   "low stock sku=%SKU% remaining=%STOCK% threshold=10"),
            warn("payments",    "retryable failure order=%ORDER% code=%CODE% attempt=%RETRY%"),
            // ERROR — real problems
            error("checkout",   "payment gateway timeout for user=%USER% order=%ORDER%"),
            error("payments",   "payment declined order=%ORDER% code=%CODE% reason=\"insufficient funds\""),
            error("payments",   "payment declined order=%ORDER% code=%CODE% reason=\"card expired\""),
            error("inventory",  "stock reconciliation failed sku=%SKU% expected=%DELTA% actual=%STOCK%"),
            error("web-gateway","request POST /api/checkout 500 in %MS%ms user=%USER% cause=\"downstream timeout\""),
    };

    // Rough weights: 12 INFO templates x weight 8, 5 WARN x 3, 5 ERROR x 1
    // -> ~74% INFO, ~19% WARN, ~7% ERROR after normalization.
    private static final int WEIGHT_INFO = 8;
    private static final int WEIGHT_WARN = 3;
    private static final int WEIGHT_ERROR = 1;

    private static final int[] CUMULATIVE_WEIGHTS = buildCumulativeWeights();

    public static void main(String[] args) throws Exception {
        Args a = Args.parse(args);
        System.out.printf(Locale.ROOT,
                "seeding tenant=%s count=%d batch=%d hours=%d seed=%d url=%s%n",
                a.tenant, a.count, a.batch, a.hours, a.seed, a.url);

        Random rng = new Random(a.seed);
        HttpClient http = HttpClient.newHttpClient();
        long now = Instant.now().toEpochMilli();
        long from = now - a.hours * 3_600_000L;

        int sent = 0;
        int accepted = 0;
        int retries = 0;
        long start = System.currentTimeMillis();

        while (sent < a.count) {
            int size = Math.min(a.batch, a.count - sent);
            String body = buildBatch(a.tenant, size, rng, from, now);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(a.url + "/api/v1/logs"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() == 202) {
                accepted += parseAcceptedRecords(resp.body());
                sent += size;
                if (sent % 200 == 0 || sent == a.count) {
                    System.out.printf(Locale.ROOT,
                            "  progress sent=%d accepted=%d retries=%d%n",
                            sent, accepted, retries);
                }
            } else if (resp.statusCode() == 429) {
                retries++;
                Thread.sleep(150);
            } else {
                System.err.printf(Locale.ROOT,
                        "abort: status=%d body=%s%n", resp.statusCode(), resp.body());
                System.exit(2);
            }
        }

        long ms = System.currentTimeMillis() - start;
        long rate = ms > 0 ? sent * 1000L / ms : 0;
        System.out.printf(Locale.ROOT,
                "DONE tenant=%s sent=%d accepted=%d retries=%d elapsed_ms=%d rate=%d r/s%n",
                a.tenant, sent, accepted, retries, ms, rate);
    }

    private static String buildBatch(String tenant, int size, Random rng, long from, long to) {
        StringBuilder sb = new StringBuilder(256 + size * 200);
        sb.append("{\"tenant_id\":\"").append(tenant).append("\",\"records\":[");
        for (int i = 0; i < size; i++) {
            if (i > 0) sb.append(',');
            Template t = pickWeighted(rng);
            long ts = from + (long) (rng.nextDouble() * (to - from));
            String isoTs = Instant.ofEpochMilli(ts).toString();
            String env = ENVS[rng.nextInt(ENVS.length)];
            String region = REGIONS[rng.nextInt(REGIONS.length)];
            sb.append("{\"timestamp\":\"").append(isoTs)
              .append("\",\"tags\":{\"env\":\"").append(env)
              .append("\",\"service\":\"").append(t.service)
              .append("\",\"level\":\"").append(t.level)
              .append("\",\"region\":\"").append(region)
              .append("\"},\"message\":\"").append(jsonEscape(renderMessage(t.pattern, rng)))
              .append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String renderMessage(String pattern, Random rng) {
        // Cheap parameter expansion. Each %TOKEN% is replaced with a fake but
        // stable-shaped value. Keep it JSON-safe (no quotes/backslashes/newlines).
        StringBuilder out = new StringBuilder(pattern.length() + 32);
        int i = 0;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            if (c == '%') {
                int end = pattern.indexOf('%', i + 1);
                if (end < 0) { out.append(c); i++; continue; }
                String tok = pattern.substring(i + 1, end);
                out.append(expandToken(tok, rng));
                i = end + 1;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static String expandToken(String tok, Random rng) {
        return switch (tok) {
            case "USER"    -> String.valueOf(1000 + rng.nextInt(9000));
            case "ORDER"   -> String.valueOf(500000 + rng.nextInt(500000));
            case "SKU"     -> "SKU-" + (1000 + rng.nextInt(9000));
            case "CARD"    -> String.format(Locale.ROOT, "%04d", rng.nextInt(10000));
            case "AMOUNT"  -> String.format(Locale.ROOT, "%.2f", 5.0 + rng.nextDouble() * 495.0);
            case "MS"      -> String.valueOf(1 + rng.nextInt(500));
            case "ITEMS"   -> String.valueOf(1 + rng.nextInt(8));
            case "RESULTS" -> String.valueOf(rng.nextInt(200));
            case "DELTA"   -> String.valueOf(-20 + rng.nextInt(41));
            case "STOCK"   -> String.valueOf(rng.nextInt(500));
            case "RETRY"   -> String.valueOf(1 + rng.nextInt(3));
            case "CODE"    -> String.valueOf(400 + rng.nextInt(200));
            case "QUERY"   -> QUERIES[rng.nextInt(QUERIES.length)];
            default        -> "";
        };
    }

    private static final String[] QUERIES = {
            "wireless headphones", "running shoes", "coffee grinder",
            "phone case", "kitchen knife", "gaming mouse",
            "yoga mat", "backpack"
    };

    private static Template pickWeighted(Random rng) {
        int r = rng.nextInt(CUMULATIVE_WEIGHTS[CUMULATIVE_WEIGHTS.length - 1]);
        for (int idx = 0; idx < CUMULATIVE_WEIGHTS.length; idx++) {
            if (r < CUMULATIVE_WEIGHTS[idx]) return TEMPLATES[idx];
        }
        return TEMPLATES[TEMPLATES.length - 1];
    }

    private static int[] buildCumulativeWeights() {
        int[] cum = new int[TEMPLATES.length];
        int running = 0;
        for (int i = 0; i < TEMPLATES.length; i++) {
            int w = switch (TEMPLATES[i].level) {
                case "INFO" -> WEIGHT_INFO;
                case "WARN" -> WEIGHT_WARN;
                case "ERROR" -> WEIGHT_ERROR;
                default -> 1;
            };
            running += w;
            cum[i] = running;
        }
        return cum;
    }

    private static int parseAcceptedRecords(String body) {
        // Body is like {"accepted_records":25,"durability":"buffered"} — no need
        // to pull in Jackson for one integer. Locate the key and read digits.
        int key = body.indexOf("\"accepted_records\"");
        if (key < 0) return 0;
        int colon = body.indexOf(':', key);
        if (colon < 0) return 0;
        int i = colon + 1;
        while (i < body.length() && Character.isWhitespace(body.charAt(i))) i++;
        int start = i;
        while (i < body.length() && Character.isDigit(body.charAt(i))) i++;
        if (i == start) return 0;
        return Integer.parseInt(body.substring(start, i));
    }

    private static String jsonEscape(String s) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.toString();
    }

    private static Template info(String service, String pattern)  { return new Template("INFO",  service, pattern); }
    private static Template warn(String service, String pattern)  { return new Template("WARN",  service, pattern); }
    private static Template error(String service, String pattern) { return new Template("ERROR", service, pattern); }

    private record Template(String level, String service, String pattern) {}

    private record Args(String tenant, int count, int batch, long seed, String url, int hours) {
        static Args parse(String[] argv) {
            String tenant = "demo-shop";
            int count = 1000;
            int batch = 25;
            long seed = 42L;
            String url = "http://localhost:8080";
            int hours = 24;

            ArrayList<String> pos = new ArrayList<>();
            for (int i = 0; i < argv.length; i++) {
                String a = argv[i];
                switch (a) {
                    case "--tenant" -> tenant = argv[++i];
                    case "--count"  -> count = Integer.parseInt(argv[++i]);
                    case "--batch"  -> batch = Integer.parseInt(argv[++i]);
                    case "--seed"   -> seed = Long.parseLong(argv[++i]);
                    case "--url"    -> url = argv[++i];
                    case "--hours"  -> hours = Integer.parseInt(argv[++i]);
                    case "--help", "-h" -> {
                        System.out.println("SyntheticIngest --tenant <id> --count <n> --batch <n> --seed <n> --url <base> --hours <n>");
                        System.exit(0);
                    }
                    default -> pos.add(a);
                }
            }
            return new Args(tenant, count, batch, seed, url, hours);
        }
    }
}
