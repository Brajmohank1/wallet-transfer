package com.brajmohan.wallettransfer.metrics;

import java.util.Set;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.NamingConvention;
import org.springframework.stereotype.Component;

// Domain-level counters, on top of the request-rate / latency histogram
// Micrometer's WebMVC instrumentation already exposes for every route.
@Component
public class DomainMetrics {

    // Micrometer's Prometheus naming convention reserves a "_created"
    // suffix and silently strips the word before it, so these four names
    // are passed through unchanged instead of being mangled to
    // "wallets_total" / "transfers_total".
    private static final Set<String> LITERAL_NAMES = Set.of(
            "wallets_created_total",
            "transfers_created_total",
            "transfers_declined_insufficient_funds_total",
            "idempotent_replays_total");

    private final Counter walletsCreated;
    private final Counter transfersCreated;
    private final Counter transfersDeclinedInsufficientFunds;
    private final Counter idempotentReplays;

    public DomainMetrics(MeterRegistry registry) {
        NamingConvention defaultConvention = registry.config().namingConvention();
        registry.config().namingConvention((name, type, baseUnit) ->
                LITERAL_NAMES.contains(name) ? name : defaultConvention.name(name, type, baseUnit));

        this.walletsCreated = Counter.builder("wallets_created_total")
                .description("Wallets created via get-or-create.")
                .register(registry);
        this.transfersCreated = Counter.builder("transfers_created_total")
                .description("Transfers that completed successfully (debit + credit applied).")
                .register(registry);
        this.transfersDeclinedInsufficientFunds = Counter.builder("transfers_declined_insufficient_funds_total")
                .description("Transfers declined because the source wallet lacked funds.")
                .register(registry);
        this.idempotentReplays = Counter.builder("idempotent_replays_total")
                .description("Transfer requests that replayed an existing idempotency key's result.")
                .register(registry);
    }

    public void walletCreated() {
        walletsCreated.increment();
    }

    public void transferCreated() {
        transfersCreated.increment();
    }

    public void transferDeclinedInsufficientFunds() {
        transfersDeclinedInsufficientFunds.increment();
    }

    public void idempotentReplay() {
        idempotentReplays.increment();
    }
}
