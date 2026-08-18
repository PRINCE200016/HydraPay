package com.hydrapay.ledger.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hydrapay.ledger.controller.LedgerController;
import com.hydrapay.ledger.dto.TransferRequest;
import com.hydrapay.ledger.repository.LedgerTransactionRepository;
import com.hydrapay.ledger.repository.OutboxEventRepository;
import com.hydrapay.ledger.service.LedgerService;
import com.hydrapay.ledger.service.ReconciliationService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = LedgerController.class)
@Import({SecurityConfig.class, CorrelationIdFilter.class, RateLimitingFilter.class})
class SecurityValidationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private LedgerService ledgerService;

    @MockBean
    private ReconciliationService reconciliationService;

    @MockBean
    private LedgerTransactionRepository transactionRepository;

    @MockBean
    private OutboxEventRepository outboxEventRepository;

    @MockBean
    private RateLimiterService rateLimiterService;

    @MockBean
    private AuditLoggerService auditLoggerService;

    @BeforeEach
    void setUp() {
        when(rateLimiterService.isAllowed(anyString())).thenReturn(true);
    }

    @Test
    @DisplayName("Should return X-Correlation-ID in response header for all requests")
    @WithMockUser(roles = "OPERATOR")
    void testCorrelationIdHeaderInjected() throws Exception {
        UUID source = UUID.randomUUID();
        UUID dest = UUID.randomUUID();
        TransferRequest request = TransferRequest.builder()
                .idempotencyKey("idk_corr_1")
                .sourceAccountId(source)
                .destinationAccountId(dest)
                .amount(new BigDecimal("100.00"))
                .currency("USD")
                .build();

        mockMvc.perform(post("/api/v1/transfers")
                        .header("X-Correlation-ID", "custom-corr-id-999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(header().string("X-Correlation-ID", "custom-corr-id-999"));
    }

    @Test
    @DisplayName("Should reject negative transfer amount with 400 Bad Request")
    @WithMockUser(roles = "OPERATOR")
    void testRejectNegativeAmount() throws Exception {
        UUID source = UUID.randomUUID();
        UUID dest = UUID.randomUUID();
        TransferRequest request = TransferRequest.builder()
                .idempotencyKey("idk_neg_1")
                .sourceAccountId(source)
                .destinationAccountId(dest)
                .amount(new BigDecimal("-50.00"))
                .currency("USD")
                .build();

        mockMvc.perform(post("/api/v1/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("Should reject transfer when source and destination accounts are identical")
    @WithMockUser(roles = "OPERATOR")
    void testRejectSameSourceAndDestination() throws Exception {
        UUID sameAccountId = UUID.randomUUID();
        TransferRequest request = TransferRequest.builder()
                .idempotencyKey("idk_same_acc")
                .sourceAccountId(sameAccountId)
                .destinationAccountId(sameAccountId)
                .amount(new BigDecimal("100.00"))
                .currency("USD")
                .build();

        mockMvc.perform(post("/api/v1/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("Should reject requests missing idempotency key with 400 Bad Request")
    @WithMockUser(roles = "OPERATOR")
    void testRejectMissingIdempotencyKey() throws Exception {
        UUID source = UUID.randomUUID();
        UUID dest = UUID.randomUUID();
        TransferRequest request = TransferRequest.builder()
                .sourceAccountId(source)
                .destinationAccountId(dest)
                .amount(new BigDecimal("100.00"))
                .currency("USD")
                .build();

        mockMvc.perform(post("/api/v1/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("Should enforce role-based access control for administrative reconcile endpoint")
    @WithMockUser(roles = "READONLY")
    void testForbiddenForReadonlyUserOnReconcile() throws Exception {
        mockMvc.perform(post("/api/v1/reconcile"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", is("FORBIDDEN")));
    }

    @Test
    @DisplayName("Should return 401 UNAUTHORIZED when request lacks valid authentication credentials")
    void testUnauthorizedWithoutCredentials() throws Exception {
        mockMvc.perform(post("/api/v1/reconcile"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error", is("UNAUTHORIZED")));
    }

    @Test
    @DisplayName("Should return 429 TOO MANY REQUESTS when rate limit is exceeded")
    @WithMockUser(roles = "OPERATOR")
    void testRateLimitExceeded() throws Exception {
        when(rateLimiterService.isAllowed(anyString())).thenReturn(false);

        UUID source = UUID.randomUUID();
        UUID dest = UUID.randomUUID();
        TransferRequest request = TransferRequest.builder()
                .idempotencyKey("idk_rate_exceeded")
                .sourceAccountId(source)
                .destinationAccountId(dest)
                .amount(new BigDecimal("100.00"))
                .currency("USD")
                .build();

        mockMvc.perform(post("/api/v1/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error", is("RATE_LIMIT_EXCEEDED")));
    }
}
