package com.example.demo;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ============================================================
 * CLEAN FILE — NO BUGS — FOR TESTING BOT APPROVAL
 * ============================================================
 * Open this as a separate PR to verify the bot posts
 * an approval when no issues are found.
 * ============================================================
 */
@Service
public class CleanPaymentService {

    private final OrderRepository orderRepo;
    private final ItemRepository itemRepo;
    private final AccountRepository accountRepo;
    private final PaymentGateway paymentGateway;
    private final IdempotencyStore idempotencyStore;

    public CleanPaymentService(OrderRepository orderRepo, ItemRepository itemRepo,
                                AccountRepository accountRepo, PaymentGateway paymentGateway,
                                IdempotencyStore idempotencyStore) {
        this.orderRepo = orderRepo;
        this.itemRepo = itemRepo;
        this.accountRepo = accountRepo;
        this.paymentGateway = paymentGateway;
        this.idempotencyStore = idempotencyStore;
    }

    // ✅ Batch fetch — no N+1
    public List<Order> getOrdersWithItems(List<String> orderIds) {
        List<Order> orders = orderRepo.findAllById(orderIds);
        List<String> ids = orders.stream().map(Order::getId).toList();
        Map<String, List<Item>> itemsByOrder = itemRepo.findByOrderIdIn(ids).stream()
            .collect(Collectors.groupingBy(Item::getOrderId));
        orders.forEach(o -> o.setItems(itemsByOrder.getOrDefault(o.getId(), List.of())));
        return orders;
    }

    // ✅ Idempotency key check before charging
    @PostMapping("/api/payments")
    public Payment processPayment(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PaymentRequest request) {

        // Check for duplicate request
        return idempotencyStore.getOrCompute(idempotencyKey, () ->
            paymentGateway.charge(request.getAmount(), request.getCardToken())
        );
    }

    // ✅ Transactional — atomic debit + credit
    @Transactional
    public void transferFunds(String fromAccountId, String toAccountId, double amount) {
        accountRepo.debit(fromAccountId, amount);
        accountRepo.credit(toAccountId, amount);
    }

    // ✅ Timeout configured — won't hang threads
    public String getInventoryStatus(String productId) throws Exception {
        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://inventory-service/api/products/" + productId))
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return response.body();
    }

    // ✅ Specific exception catch, no sensitive data logged
    public void processRefund(String paymentId) {
        try {
            paymentGateway.refund(paymentId);
        } catch (PaymentGatewayException e) {
            // Log only safe identifiers — no card data, no PII
            System.out.println("Refund failed for paymentId=" + paymentId
                + " reason=" + e.getCode());
        }
    }

    // ✅ Paginated endpoint
    @GetMapping("/api/transactions")
    public Page<Transaction> getTransactions(Pageable pageable) {
        return orderRepo.findAllTransactions(pageable);
    }

    // ---- Placeholder types ----
    record Order(String id, List<Item> items) {
        void setItems(List<Item> items) {}
        String getId() { return id; }
    }
    record Item(String id, String orderId) {
        String getOrderId() { return orderId; }
    }
    record Payment(String id) {}
    record Transaction(String id) {}
    record PaymentRequest(double amount, String cardToken) {
        double getAmount() { return amount; }
        String getCardToken() { return cardToken; }
    }
    static class PaymentGatewayException extends RuntimeException {
        String getCode() { return "ERR_001"; }
    }

    interface OrderRepository {
        List<Order> findAllById(List<String> ids);
        Page<Transaction> findAllTransactions(Pageable pageable);
    }
    interface ItemRepository {
        List<Item> findByOrderIdIn(List<String> ids);
    }
    interface AccountRepository {
        void debit(String id, double amount);
        void credit(String id, double amount);
    }
    interface PaymentGateway {
        Payment charge(double amount, String cardToken);
        void refund(String paymentId) throws PaymentGatewayException;
    }
    interface IdempotencyStore {
        Payment getOrCompute(String key, java.util.function.Supplier<Payment> supplier);
    }
}
