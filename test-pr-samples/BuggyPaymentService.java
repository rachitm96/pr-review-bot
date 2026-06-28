package com.example.demo;

import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;

import java.net.URL;
import java.util.*;

/**
 * ============================================================
 * TEST FILE — INTENTIONAL BACKEND BUGS FOR PR REVIEW DEMO
 * ============================================================
 * Open this file as a PR against your test repo to trigger
 * the bot and verify all review categories fire correctly.
 *
 * Expected bot output:
 *   🔴 CRITICAL x3  (N+1, missing idempotency, missing transaction)
 *   🟠 HIGH x3      (no timeout, generic exception, logging PII)
 *   🟡 MEDIUM x1    (no pagination)
 * ============================================================
 */
@Service
public class BuggyPaymentService {

    private final OrderRepository orderRepo;
    private final ItemRepository itemRepo;
    private final AccountRepository accountRepo;
    private final PaymentGateway paymentGateway;

    public BuggyPaymentService(OrderRepository orderRepo, ItemRepository itemRepo,
                                AccountRepository accountRepo, PaymentGateway paymentGateway) {
        this.orderRepo = orderRepo;
        this.itemRepo = itemRepo;
        this.accountRepo = accountRepo;
        this.paymentGateway = paymentGateway;
    }

    // ============================================================
    // 🔴 BUG 1: N+1 QUERY PATTERN
    // Expected: CRITICAL — N+1 Query
    // Problem: DB query fires once per order inside the loop.
    //          With 500 orders → 501 DB round trips.
    //          At scale: DB connection pool exhaustion, timeouts.
    // Fix: use itemRepo.findByOrderIdIn(orderIds) and group in memory.
    // ============================================================
    public List<Order> getOrdersWithItems(List<String> orderIds) {
        List<Order> orders = orderRepo.findAllById(orderIds);
        for (Order order : orders) {
            // ❌ N+1: one query per order
            order.setItems(itemRepo.findByOrderId(order.getId()));
        }
        return orders;
    }

    // ============================================================
    // 🔴 BUG 2: MISSING IDEMPOTENCY
    // Expected: CRITICAL — Missing Idempotency
    // Problem: POST endpoint processes payment without checking for
    //          duplicate requests. On network timeout, client retries
    //          → customer charged twice.
    // Fix: accept idempotency key header, check Redis/DB before charging.
    // ============================================================
    @PostMapping("/api/payments")
    public Payment processPayment(@RequestBody PaymentRequest request) {
        // ❌ No idempotency key check — double charge risk on retry
        return paymentGateway.charge(request.getAmount(), request.getCardToken());
    }

    // ============================================================
    // 🔴 BUG 3: MISSING TRANSACTION BOUNDARY
    // Expected: CRITICAL — Missing Transaction
    // Problem: If the credit succeeds but debit fails (or vice versa),
    //          money is created or destroyed. No atomicity guarantee.
    // Fix: wrap both calls in @Transactional or use a single DB transaction.
    // ============================================================
    public void transferFunds(String fromAccountId, String toAccountId, double amount) {
        // ❌ No @Transactional — partial failure = lost/created money
        accountRepo.debit(fromAccountId, amount);
        accountRepo.credit(toAccountId, amount); // crash here = money disappears
    }

    // ============================================================
    // 🟠 BUG 4: NO TIMEOUT ON EXTERNAL HTTP CALL
    // Expected: HIGH — Missing Timeout
    // Problem: If inventory service hangs, this thread hangs indefinitely.
    //          Under load: all threads blocked → cascade failure.
    // Fix: configure connectTimeout and readTimeout on the HTTP client.
    // ============================================================
    public String getInventoryStatus(String productId) throws Exception {
        // ❌ No timeout — hangs forever if inventory service is down
        URL url = new URL("http://inventory-service/api/products/" + productId);
        return new String(url.openStream().readAllBytes());
    }

    // ============================================================
    // 🟠 BUG 5: CATCHING GENERIC EXCEPTION
    // Expected: HIGH — Generic Exception Catch
    // Problem: Catches ALL throwables including OutOfMemoryError,
    //          InterruptedException (breaks thread interruption model),
    //          and hides real bugs behind silent logging.
    // Fix: catch specific checked exceptions; re-interrupt if catching InterruptedException.
    // ============================================================
    public void processRefund(String paymentId) {
        try {
            paymentGateway.refund(paymentId);
        } catch (Exception e) {
            // ❌ Generic catch — swallows OOM, interrupted, and real bugs
            System.out.println("Refund failed: " + e.getMessage());
        }
    }

    // ============================================================
    // 🟠 BUG 6: LOGGING SENSITIVE DATA (PII)
    // Expected: HIGH — Logging Sensitive Data
    // Problem: Card number and CVV appear in logs.
    //          PCI-DSS violation — logs are often stored in plain text
    //          and accessible to anyone with log access.
    // Fix: never log card details; log only a masked/truncated token.
    // ============================================================
    public void logPaymentAttempt(String customerId, String cardNumber, String cvv) {
        // ❌ PCI-DSS violation — card data in logs
        System.out.println("Payment attempt: customer=" + customerId
            + " card=" + cardNumber + " cvv=" + cvv);
    }

    // ============================================================
    // 🟡 BUG 7: MISSING PAGINATION
    // Expected: MEDIUM — Missing Pagination
    // Problem: Returns ALL transactions in the DB. For active users
    //          this could be thousands of rows — OOM risk, slow response.
    // Fix: accept page/size parameters; use Pageable in the repository.
    // ============================================================
    @GetMapping("/api/transactions")
    public List<Transaction> getAllTransactions() {
        // ❌ No pagination — SELECT * FROM transactions with no LIMIT
        return orderRepo.findAllTransactions();
    }


    // ---- Placeholder types to make this file compile-like ----
    record Order(String id, List<Item> items) {
        void setItems(List<Item> items) {}
        String getId() { return id; }
    }
    record Item(String id) {}
    record Payment(String id) {}
    record Transaction(String id) {}
    record PaymentRequest(double amount, String cardToken) {
        double getAmount() { return amount; }
        String getCardToken() { return cardToken; }
    }

    interface OrderRepository {
        List<Order> findAllById(List<String> ids);
        List<Transaction> findAllTransactions();
    }
    interface ItemRepository {
        List<Item> findByOrderId(String orderId);
    }
    interface AccountRepository {
        void debit(String id, double amount);
        void credit(String id, double amount);
    }
    interface PaymentGateway {
        Payment charge(double amount, String cardToken);
        void refund(String paymentId);
    }
}
