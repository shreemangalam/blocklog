package com.blocklog.demo;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;

/**
 * Search latency measurement tool. Runs a mixed query workload against a
 * tenant already seeded via {@link SyntheticIngest} and reports p50/p95/p99
 * end-to-end latency, backend-reported elapsed time, and mean pruning
 * effectiveness.
 *
 * The workload rotates through six query classes that stress different
 * paths:
 *   1. full-tenant, no filters              — scans everything
 *   2. tenant + last 1h                     — time pruning
 *   3. tenant + level=ERROR                 — tag pruning
 *   4. tenant + level=ERROR + service       — two-tag AND
 *   5. tenant + keyword "payment"           — text filter on full set
 *   6. tenant + level=ERROR + keyword       — tag + text
 *
 * Each query gets a fresh timeout budget. Latencies are collected client-side
 * (round-trip including network + JSON parse) as the honest number a user
 * feels, and separately from {@code elapsed_ms} in the response body as the
 * pure engine time.
 *
 * Usage: java -cp target/classes com.blocklog.demo.MeasureSearch \
 *          [--tenant demo-shop] [--warmup 20] [--iterations 200] \
 *          [--hours 24] [--url http://localhost:8080]
 */
public final class MeasureSearch {

    private static final String[] SERVICES = {
            "web-gateway", "checkout", "inventory", "search", "payments", "email"
    };
    private static final String[] KEYWORDS = {
            "payment", "declined", "timeout", "order", "SKU", "slow"
    };

    public static void main(String[] args) throws Exception {
        Args a = Args.parse(args);
        HttpClient http = HttpClient.newHttpClient();
        int totalBlocks = fetchPublishedBlocks(http, a.url);
        System.out.printf(Locale.ROOT,
                "measuring tenant=%s warmup=%d iterations=%d url=%s total_blocks=%d%n",
                a.tenant, a.warmup, a.iterations, a.url, totalBlocks);

        Random rng = new Random(a.seed);
        long now = Instant.now().toEpochMilli();
        long from = now - a.hours * 3_600_000L;
        String rangeFrom = Instant.ofEpochMilli(from).toString();
        String rangeTo = Instant.ofEpochMilli(now).toString();

        for (int i = 0; i < a.warmup; i++) runOne(http, a.url, a.tenant, rangeFrom, rangeTo, rng);

        Sample[] samples = new Sample[a.iterations];
        for (int i = 0; i < a.iterations; i++) samples[i] = runOne(http, a.url, a.tenant, rangeFrom, rangeTo, rng);

        System.out.println();
        System.out.println("class                     count  p50c  p95c  p99c   maxc  p50e  p95e  p99e  prune% cand/tot scan/cand");
        System.out.println("------------------------  -----  ----  ----  ----  ----  ----  ----  ----  ------ -------- ---------");
        for (int cls = 0; cls < 6; cls++) {
            Sample[] filtered = filter(samples, cls);
            if (filtered.length == 0) continue;
            summarize(className(cls), filtered, totalBlocks);
        }
        summarize("(all)", samples, totalBlocks);
        System.out.println();
        System.out.printf(Locale.ROOT,
                "legend: p*c = client ms, p*e = engine elapsed_ms, prune%% = (total-candidates)/total, cand/tot = mean candidate/total, scan/cand = mean scanned/candidate%n");
    }

    private static int fetchPublishedBlocks(HttpClient http, String url) throws Exception {
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder().uri(URI.create(url + "/api/v1/status")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        int key = resp.body().indexOf("\"published_blocks\"");
        if (key < 0) return 0;
        int colon = resp.body().indexOf(':', key);
        int i = colon + 1;
        while (i < resp.body().length() && Character.isWhitespace(resp.body().charAt(i))) i++;
        int start = i;
        while (i < resp.body().length() && Character.isDigit(resp.body().charAt(i))) i++;
        return start == i ? 0 : Integer.parseInt(resp.body().substring(start, i));
    }

    private static Sample runOne(HttpClient http, String url, String tenant,
                                  String from, String to, Random rng) throws Exception {
        int cls = rng.nextInt(6);
        String body = switch (cls) {
            case 0 -> query(tenant, from, to, null, null);
            case 1 -> query(tenant, Instant.ofEpochMilli(System.currentTimeMillis() - 3_600_000L).toString(), to, null, null);
            case 2 -> query(tenant, from, to, "\"tags\":{\"level\":\"ERROR\"}", null);
            case 3 -> query(tenant, from, to, "\"tags\":{\"level\":\"ERROR\",\"service\":\"" + SERVICES[rng.nextInt(SERVICES.length)] + "\"}", null);
            case 4 -> query(tenant, from, to, null, KEYWORDS[rng.nextInt(KEYWORDS.length)]);
            case 5 -> query(tenant, from, to, "\"tags\":{\"level\":\"ERROR\"}", KEYWORDS[rng.nextInt(KEYWORDS.length)]);
            default -> throw new IllegalStateException();
        };

        long t0 = System.nanoTime();
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(url + "/api/v1/search"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        long clientMs = (System.nanoTime() - t0) / 1_000_000L;

        if (resp.statusCode() != 200) {
            throw new RuntimeException("search failed status=" + resp.statusCode() + " body=" + resp.body());
        }
        int engineMs = intField(resp.body(), "elapsed_ms");
        int candidates = intField(resp.body(), "candidate_blocks");
        int scanned = intField(resp.body(), "scanned_blocks");
        return new Sample(cls, clientMs, engineMs, candidates, scanned);
    }

    private static String query(String tenant, String from, String to, String tagsPart, String text) {
        StringBuilder sb = new StringBuilder(200);
        sb.append("{\"tenant_id\":\"").append(tenant)
          .append("\",\"from\":\"").append(from)
          .append("\",\"to\":\"").append(to).append("\",");
        if (tagsPart != null) sb.append(tagsPart).append(',');
        else sb.append("\"tags\":null,");
        if (text != null) sb.append("\"text\":\"").append(text).append("\",");
        else sb.append("\"text\":null,");
        sb.append("\"limit\":100,\"timeout_ms\":5000}");
        return sb.toString();
    }

    private static int intField(String json, String name) {
        int key = json.indexOf("\"" + name + "\"");
        if (key < 0) return -1;
        int colon = json.indexOf(':', key);
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        int start = i;
        if (i < json.length() && json.charAt(i) == '-') i++;
        while (i < json.length() && Character.isDigit(json.charAt(i))) i++;
        return start == i ? -1 : Integer.parseInt(json.substring(start, i));
    }

    private static Sample[] filter(Sample[] all, int cls) {
        return Arrays.stream(all).filter(s -> s.cls == cls).toArray(Sample[]::new);
    }

    private static void summarize(String label, Sample[] s, int totalBlocks) {
        long[] client = new long[s.length];
        int[] engine = new int[s.length];
        int[] cand = new int[s.length];
        int[] scan = new int[s.length];
        for (int i = 0; i < s.length; i++) { client[i] = s[i].clientMs; engine[i] = s[i].engineMs; cand[i] = s[i].candidateBlocks; scan[i] = s[i].scannedBlocks; }
        Arrays.sort(client);
        int[] engineSorted = engine.clone(); Arrays.sort(engineSorted);
        long p50c = client[percentile(client.length, 50)], p95c = client[percentile(client.length, 95)], p99c = client[percentile(client.length, 99)];
        long maxc = client[client.length - 1];
        long p50e = engineSorted[percentile(engineSorted.length, 50)], p95e = engineSorted[percentile(engineSorted.length, 95)], p99e = engineSorted[percentile(engineSorted.length, 99)];
        int meanCand = mean(cand), meanScan = mean(scan);
        double prunePct = totalBlocks == 0 ? 0.0 : 100.0 * (totalBlocks - meanCand) / totalBlocks;
        System.out.printf(Locale.ROOT, "%-24s  %5d  %4d  %4d  %4d  %4d  %4d  %4d  %4d  %5.1f  %d/%d      %d/%d%n",
                label, s.length, p50c, p95c, p99c, maxc, p50e, p95e, p99e, prunePct, meanCand, totalBlocks, meanScan, meanCand);
    }

    private static int mean(int[] a) {
        long s = 0; for (int v : a) s += v; return (int) (s / Math.max(a.length, 1));
    }

    private static int percentile(int n, int p) {
        int i = (int) Math.ceil(n * p / 100.0) - 1;
        return Math.max(0, Math.min(n - 1, i));
    }

    private static String className(int cls) {
        return switch (cls) {
            case 0 -> "full-tenant";
            case 1 -> "tenant + last 1h";
            case 2 -> "tag: level=ERROR";
            case 3 -> "tag: level + service";
            case 4 -> "text keyword";
            case 5 -> "level=ERROR + keyword";
            default -> "?";
        };
    }

    private record Sample(int cls, long clientMs, int engineMs, int candidateBlocks, int scannedBlocks) {}

    private record Args(String tenant, int warmup, int iterations, long seed, String url, int hours) {
        static Args parse(String[] argv) {
            String tenant = "demo-shop";
            int warmup = 20, iterations = 200, hours = 24;
            long seed = 7L;
            String url = "http://localhost:8080";
            for (int i = 0; i < argv.length; i++) {
                switch (argv[i]) {
                    case "--tenant"     -> tenant = argv[++i];
                    case "--warmup"     -> warmup = Integer.parseInt(argv[++i]);
                    case "--iterations" -> iterations = Integer.parseInt(argv[++i]);
                    case "--seed"       -> seed = Long.parseLong(argv[++i]);
                    case "--url"        -> url = argv[++i];
                    case "--hours"      -> hours = Integer.parseInt(argv[++i]);
                    case "-h", "--help" -> {
                        System.out.println("MeasureSearch --tenant <id> --warmup <n> --iterations <n> --seed <n> --hours <n> --url <base>");
                        System.exit(0);
                    }
                }
            }
            return new Args(tenant, warmup, iterations, seed, url, hours);
        }
    }
}
