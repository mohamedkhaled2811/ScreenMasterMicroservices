package com.gr74.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import com.gr74.payment.client.BookingClient;
import com.gr74.payment.client.BookingPayability;
import com.gr74.payment.dto.CreatePaymentRequest;
import com.gr74.payment.model.PaymentGatewayType;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Checkout write boundaries: obligation and attempt commit before the gateway call.
 */
@SpringBootTest
class PaymentTransactionBoundaryTest {

    private static final String USER = "11111111-1111-1111-1111-111111111111";
    private static final Long BOOKING_ID = 1001L;
    private static final BigDecimal AMOUNT = new BigDecimal("300.00");

    /** The dependency the guards read; everything else is real, including the transaction proxies. */
    @MockitoBean
    private BookingClient bookingClient;

    @Autowired private PaymentService paymentService;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private HikariDataSource dataSource;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from payment_attempts");
        jdbcTemplate.update("delete from payments");
        givenPayableBooking();
    }

    @Test
    @DisplayName("payment and attempt are committed before the outer transaction closes — REQUIRES_NEW is real")
    void checkoutWritesCommitIndependentlyOfTheCallerTransaction() {
        CreatePaymentRequest request = new CreatePaymentRequest(BOOKING_ID, PaymentGatewayType.SANDBOX);

        var outcome = transactionTemplate.execute(status -> {
            var result = paymentService.createSession(request, USER);

            // Still inside the outer transaction. This must be a genuinely separate connection:
            // JdbcTemplate would silently ride the transaction-bound one and see its own
            // uncommitted rows. Under READ_COMMITTED a raw connection sees only what the writer
            // already committed — so finding the rows here proves the boundaries are real.
            try (Connection other = separateConnection()) {
                Long paymentId = queryForLong(other,
                        "select id from payments where booking_id = ?", BOOKING_ID);
                String attempt = queryForString(other, """
                        select status || '|' || coalesce(checkout_url, '') || '|' || coalesce(gateway_session_id, '')
                        from payment_attempts where payment_id = ?""", paymentId);

                String[] parts = attempt.split("\\|", 2);
                assertThat(parts[0]).isEqualTo("PENDING");
                assertThat(parts[1]).isNotBlank();   // the gateway's session is already recorded
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }

            return result;
        });

        assertThat(outcome.created()).isTrue();
        assertThat(outcome.session().checkoutUrl()).isNotBlank();
    }

    @Test
    @DisplayName("a caller rollback cannot strand a gateway session without a local record")
    void callerRollbackLeavesCommittedEvidenceBehind() {
        CreatePaymentRequest request = new CreatePaymentRequest(BOOKING_ID, PaymentGatewayType.SANDBOX);

        transactionTemplate.execute(status -> {
            paymentService.createSession(request, USER);
            status.setRollbackOnly();   // the caller's transaction fails after the gateway call
            return null;
        });

        // The obligation and the attempt survived the caller's rollback: reconciliation has rows to
        // work with, which is the entire reason the writes are REQUIRES_NEW.
        Long paymentId = jdbcTemplate.queryForObject(
                "select id from payments where booking_id = ?", Long.class, BOOKING_ID);
        Integer attempts = jdbcTemplate.queryForObject(
                "select count(*) from payment_attempts where payment_id = ?", Integer.class, paymentId);
        assertThat(attempts).isEqualTo(1);
    }

    @Test
    @DisplayName("Pay Again after a failed attempt opens a new session — the payment is loaded, not fresh")
    void secondAttemptOnALoadedPaymentOpensANewSession() {
        CreatePaymentRequest request = new CreatePaymentRequest(BOOKING_ID, PaymentGatewayType.SANDBOX);

        var first = paymentService.createSession(request, USER);
        assertThat(first.created()).isTrue();

        // The first attempt goes terminal the way a webhook would settle it. Raw SQL on purpose:
        // the next request must see the row as committed, not as any test-held entity state.
        jdbcTemplate.update("update payment_attempts set status = 'FAILED' where id = ?",
                first.session().attemptId());

        // "Pay Again" now takes the path the first call never did: getOrCreatePayment LOADS the
        // payment instead of inserting it, and that loaded entity's lazy `attempts` collection
        // belongs to a transaction that already closed. insertPendingAttempt must not mutate it —
        // before it re-loaded the payment inside its own transaction, this threw
        // LazyInitializationException (PersistentBag.add on a detached collection, "no session").
        var second = paymentService.createSession(request, USER);

        assertThat(second.created()).isTrue();
        assertThat(second.session().attemptId()).isNotEqualTo(first.session().attemptId());

        // One obligation, two attempts — the retry history lives on the same payment.
        Integer attempts = jdbcTemplate.queryForObject("""
                select count(*) from payment_attempts
                where payment_id = (select id from payments where booking_id = ?)""",
                Integer.class, BOOKING_ID);
        assertThat(attempts).isEqualTo(2);
    }

    private void givenPayableBooking() {
        given(bookingClient.fetchPayability(BOOKING_ID)).willReturn(
                new BookingPayability(BOOKING_ID, USER, "PENDING",
                        Instant.now().plus(10, ChronoUnit.MINUTES), AMOUNT, "EGP"));
    }

    /** A connection outside any transaction manager — sees committed data only. */
    private Connection separateConnection() throws SQLException {
        return DriverManager.getConnection(dataSource.getJdbcUrl(), dataSource.getUsername(),
                dataSource.getPassword());
    }

    private Long queryForLong(Connection connection, String sql, Object... args) throws SQLException {
        return query(connection, sql, args).getLong(1);
    }

    private String queryForString(Connection connection, String sql, Object... args) throws SQLException {
        return query(connection, sql, args).getString(1);
    }

    private ResultSet query(Connection connection, String sql, Object... args) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        for (int i = 0; i < args.length; i++) {
            statement.setObject(i + 1, args[i]);
        }
        ResultSet resultSet = statement.executeQuery();
        assertThat(resultSet.next()).as("a committed row must be visible: " + sql).isTrue();
        return resultSet;
    }
}
