import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Fires all three concurrency probes against BASE_URL. Every request in a
// wave is submitted first and only released once all threads hit the
// CyclicBarrier together, so this is genuinely concurrent rather than a
// loop that fires requests one at a time.
//
// Usage: java Burst.java <BASE_URL> [BEARER_TOKEN]
// BEARER_TOKEN defaults to "demo-token", matching the server's default
// AUTH_TOKENS mapping for a fresh `docker compose up`.
public class Burst {

    record HttpResult(int status, String body) {
    }

    // Pinned to HTTP/1.1: against HTTPS the JDK default negotiates HTTP/2
    // and multiplexes every concurrent request onto one TCP connection,
    // which exceeds the server's concurrent-stream limit under a 300-wide
    // burst. HTTP/1.1 opens genuinely separate connections.
    static HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    static String baseUrl;
    static String bearerToken;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: java Burst.java <BASE_URL> [BEARER_TOKEN]");
            System.exit(2);
        }
        baseUrl = args[0].replaceAll("/+$", "");
        bearerToken = args.length >= 2 ? args[1] : "demo-token";
        System.out.println("Target: " + baseUrl);
        System.out.println();

        boolean p1 = probeGetOrCreateRace();
        System.out.println();
        boolean p2 = probeIdempotencyStorm();
        System.out.println();
        boolean p3 = probeConservationUnderContention();
        System.out.println();

        boolean pass = p1 && p2 && p3;
        System.out.println(pass ? "PASS" : "FAIL");
        System.exit(pass ? 0 : 1);
    }

    // ---- Probe 1: get-or-create race ----------------------------------

    static boolean probeGetOrCreateRace() throws Exception {
        System.out.println("== Probe 1: get-or-create race (50 concurrent POST /wallets, one fresh user) ==");
        String userId = "burst-race-" + UUID.randomUUID();
        String body = "{\"user_id\":\"" + userId + "\"}";

        List<Callable<HttpResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            tasks.add(() -> post("/wallets", body));
        }
        List<HttpResult> results = fireConcurrently(tasks);

        Set<Long> ids = new HashSet<>();
        int failures = 0;
        for (HttpResult r : results) {
            if (r.status() != 200 && r.status() != 201) {
                failures++;
                System.out.printf("    non-2xx: status=%d body=%s%n", r.status(), r.body());
                continue;
            }
            ids.add(jnum(r.body(), "id"));
        }
        boolean ok = failures == 0 && ids.size() == 1;
        System.out.printf("  distinct wallet ids=%d non-2xx=%d -> %s%n", ids.size(), failures, ok ? "OK" : "FAIL");
        return ok;
    }

    // ---- Probe 2: idempotency storm ------------------------------------

    static boolean probeIdempotencyStorm() throws Exception {
        System.out.println("== Probe 2: idempotency storm (30 concurrent identical-key transfers, then a same-key/different-amount retry) ==");
        String fromUser = "burst-idem-from-" + UUID.randomUUID();
        String toUser = "burst-idem-to-" + UUID.randomUUID();
        // GET /wallets/{id} is wallet-id-keyed, so capture the id POST
        // /wallets hands back instead of re-querying by user_id.
        long fromWalletId = jnum(post("/wallets", "{\"user_id\":\"" + fromUser + "\"}").body(), "id");
        long toWalletId = jnum(post("/wallets", "{\"user_id\":\"" + toUser + "\"}").body(), "id");
        long seedAmount = 100_000;
        post("/wallets/" + fromUser + "/deposit", "{\"amount_paise\":" + seedAmount + "}");

        String key = "burst-idem-" + UUID.randomUUID();
        long amount = 500;
        String body = transferJson(key, fromUser, toUser, amount);

        List<Callable<HttpResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            tasks.add(() -> post("/transfers", body));
        }
        List<HttpResult> results = fireConcurrently(tasks);

        Set<String> distinctOutcomes = new HashSet<>();
        int badStatus = 0;
        for (HttpResult r : results) {
            if (r.status() != 200 && r.status() != 422) {
                badStatus++;
            }
            distinctOutcomes.add(normalizeTransferBody(r.body()));
        }
        // "identical" is checked on the fields that define the outcome
        // (transfer id, status, parties, amount), not idempotent_replay.
        boolean allIdentical = distinctOutcomes.size() == 1;

        long fromBalance = jnum(get("/wallets/" + fromWalletId).body(), "balance_paise");
        long toBalance = jnum(get("/wallets/" + toWalletId).body(), "balance_paise");
        boolean balancesOk = fromBalance == seedAmount - amount && toBalance == amount;

        System.out.printf("  distinct outcomes=%d bad-status=%d from_balance=%d to_balance=%d -> %s%n",
                distinctOutcomes.size(), badStatus, fromBalance, toBalance,
                (allIdentical && balancesOk && badStatus == 0) ? "OK" : "FAIL");

        String conflictBody = transferJson(key, fromUser, toUser, amount + 1);
        HttpResult conflict = post("/transfers", conflictBody);
        boolean conflictOk = conflict.status() == 409;
        System.out.printf("  same key, different amount -> status=%d -> %s%n", conflict.status(), conflictOk ? "OK" : "FAIL");

        return allIdentical && balancesOk && badStatus == 0 && conflictOk;
    }

    // ---- Probe 3: conservation under contention ------------------------

    static boolean probeConservationUnderContention() throws Exception {
        System.out.println("== Probe 3: conservation under contention (300 concurrent transfers across 5 wallets, including overdraws) ==");
        long[] seedBalances = {100_000, 50_000, 75_000, 20_000, 10_000};
        int n = seedBalances.length;
        String[] users = new String[n];
        long[] walletIds = new long[n];
        for (int i = 0; i < n; i++) {
            users[i] = "burst-wallet-" + i + "-" + UUID.randomUUID();
            walletIds[i] = jnum(post("/wallets", "{\"user_id\":\"" + users[i] + "\"}").body(), "id");
            post("/wallets/" + users[i] + "/deposit", "{\"amount_paise\":" + seedBalances[i] + "}");
        }
        long totalBefore = Arrays.stream(seedBalances).sum();

        int numTransfers = 300;
        Random rnd = new Random(42);
        List<Callable<HttpResult>> tasks = new ArrayList<>();
        for (int i = 0; i < numTransfers; i++) {
            int from = rnd.nextInt(n);
            int to;
            do {
                to = rnd.nextInt(n);
            } while (to == from);
            // Range well above several wallets' seed balances, so a
            // meaningful share of these are expected overdraws.
            long amount = 100 + rnd.nextInt(40_000);
            String key = "burst-cons-" + UUID.randomUUID();
            String txBody = transferJson(key, users[from], users[to], amount);
            tasks.add(() -> post("/transfers", txBody));
        }
        List<HttpResult> results = fireConcurrently(tasks);

        int serverErrors = 0, completed = 0, declined = 0, other = 0;
        for (HttpResult r : results) {
            if (r.status() >= 500) serverErrors++;
            else if (r.status() == 200) completed++;
            else if (r.status() == 422) declined++;
            else other++;
        }

        long totalAfter = 0;
        boolean anyNegative = false;
        for (long walletId : walletIds) {
            long bal = jnum(get("/wallets/" + walletId).body(), "balance_paise");
            totalAfter += bal;
            if (bal < 0) anyNegative = true;
        }

        boolean ok = serverErrors == 0 && other == 0 && totalAfter == totalBefore && !anyNegative;
        System.out.printf("  completed=%d declined=%d 5xx=%d other=%d total_before=%d total_after=%d negative_balance=%b -> %s%n",
                completed, declined, serverErrors, other, totalBefore, totalAfter, anyNegative, ok ? "OK" : "FAIL");
        return ok;
    }

    // ---- HTTP + concurrency plumbing -----------------------------------

    static List<HttpResult> fireConcurrently(List<Callable<HttpResult>> tasks) throws Exception {
        int n = tasks.size();
        // Pool size must equal n: the barrier only releases once all n
        // threads reach it, so a smaller pool deadlocks instead of queuing.
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CyclicBarrier barrier = new CyclicBarrier(n);
        List<Future<HttpResult>> futures = new ArrayList<>(n);
        try {
            for (Callable<HttpResult> task : tasks) {
                futures.add(pool.submit(() -> {
                    barrier.await();
                    return task.call();
                }));
            }
            List<HttpResult> results = new ArrayList<>(n);
            for (Future<HttpResult> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdown();
        }
    }

    // Generous on purpose: 300 transfers against 5 wallets means heavy
    // row-lock contention, and what matters is eventual correctness, not
    // finishing fast.
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    static HttpResult post(String path, String json) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + bearerToken)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        return new HttpResult(resp.statusCode(), resp.body());
    }

    static HttpResult get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + bearerToken)
                .GET()
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        return new HttpResult(resp.statusCode(), resp.body());
    }

    static String transferJson(String key, String from, String to, long amount) {
        return "{\"idempotency_key\":\"" + key + "\",\"from_user_id\":\"" + from
                + "\",\"to_user_id\":\"" + to + "\",\"amount_paise\":" + amount + "}";
    }

    static String normalizeTransferBody(String body) {
        return "transfer_id=" + jnum(body, "transfer_id")
                + " status=" + jstr(body, "status")
                + " from_user_id=" + jstr(body, "from_user_id")
                + " to_user_id=" + jstr(body, "to_user_id")
                + " amount_paise=" + jnum(body, "amount_paise");
    }

    // ---- Tiny hand-rolled JSON field readers ---------------------------
    // Response shape is flat and known, so a JSON library is overhead for
    // a single-file script with no build step.

    static String jstr(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    static long jnum(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        if (!m.find()) {
            throw new IllegalStateException("missing numeric field '" + key + "' in: " + json);
        }
        return Long.parseLong(m.group(1));
    }
}
