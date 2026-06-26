package com.gr74.payment.service;

import java.math.BigDecimal;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gr74.payment.exception.PaymentProviderException;
import com.gr74.payment.model.Payment;
import com.gr74.payment.provider.ChargeCommand;
import com.gr74.payment.provider.PaymentProvider;
import com.gr74.payment.provider.ProviderResult;
import com.gr74.payment.repository.PaymentRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Idempotent charge orchestration.
 *
 * <p>The contract: charging the <em>same</em> idempotency key twice must charge the provider
 * <em>once</em> and return the <em>same</em> stored result. This matters because a saga (Phase 3)
 * or a flaky network will retry {@code POST /payments}, and we must never double-charge.
 *
 * <p>Two layers enforce it:
 * <ol>
 *   <li><b>Pre-read</b> ({@code findByIdempotencyKey}) short-circuits the common case — a key
 *       we've already seen returns its stored {@link Payment} without touching the provider.</li>
 *   <li><b>The DB {@code UNIQUE} constraint</b> is the source of truth for the race the pre-read
 *       can't cover: two concurrent first-time requests with the same key both miss the read, both
 *       call the provider, but only one {@code save} succeeds — the loser catches
 *       {@link DataIntegrityViolationException} and re-reads the winner's row.</li>
 * </ol>
 *
 * <p>This is the synchronous twin of the Phase-4 idempotent <i>consumer</i> (dedupe an event by
 * id); same lesson, different trigger. See {@code docs/concepts/idempotent-consumer.md}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentProvider provider;
    private final PaymentRepository repository;

    /**
     * Charge {@code amount} under {@code idempotencyKey}, returning the (possibly pre-existing)
     * persisted charge. Runs in a transaction so the provider call and the row insert commit
     * together.
     */
    @Transactional
    public Payment charge(String idempotencyKey, BigDecimal amount) {
        return repository.findByIdempotencyKey(idempotencyKey)
                .map(existing -> {
                    log.info("Replaying stored charge for idempotencyKey={} -> {}",
                            idempotencyKey, existing.getStatus());
                    return existing;
                })
                .orElseGet(() -> chargeAndPersist(idempotencyKey, amount));
    }

    private Payment chargeAndPersist(String idempotencyKey, BigDecimal amount) {
        ProviderResult result = callProvider(idempotencyKey, amount);
        Payment payment = new Payment(idempotencyKey, result.status(), result.providerReference(), amount);
        try {
            return repository.saveAndFlush(payment);
        } catch (DataIntegrityViolationException race) {
            // A concurrent request with the same key won the UNIQUE insert between our read and
            // our save. Its row is authoritative — return it so both callers see one charge.
            log.warn("Concurrent duplicate for idempotencyKey={}; returning the winning row", idempotencyKey);
            return repository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> race); // genuinely unexpected: violation but no row to find
        }
    }

    /**
     * Call the provider, translating an <em>infrastructural</em> failure (unreachable, timed out,
     * interrupted) into a {@link PaymentProviderException} so the caller sees
     * {@code PAYMENT_PROVIDER_UNAVAILABLE} (503) — distinct from a normal {@code DECLINED}, which is
     * a successful call the provider returns as a {@link ProviderResult}, not an exception.
     */
    private ProviderResult callProvider(String idempotencyKey, BigDecimal amount) {
        try {
            return provider.charge(new ChargeCommand(idempotencyKey, amount));
        } catch (RuntimeException ex) {
            throw new PaymentProviderException(
                    "Provider failed to process charge for idempotencyKey=" + idempotencyKey, ex);
        }
    }
}
