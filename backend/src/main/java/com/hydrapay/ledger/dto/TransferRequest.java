package com.hydrapay.ledger.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TransferRequest {

    @NotBlank(message = "Idempotency key is required")
    @Size(min = 1, max = 128, message = "Idempotency key length must be between 1 and 128 characters")
    private String idempotencyKey;

    @NotNull(message = "Source account ID is required")
    private UUID sourceAccountId;

    @NotNull(message = "Destination account ID is required")
    private UUID destinationAccountId;

    @NotNull(message = "Amount is required")
    @Positive(message = "Transfer amount must be positive")
    @DecimalMin(value = "0.01", message = "Transfer amount must be at least 0.01")
    private BigDecimal amount;

    @NotBlank(message = "Currency code is required")
    @Pattern(regexp = "^[A-Z]{3}$", message = "Currency must be a valid 3-letter ISO code (e.g. USD)")
    @Builder.Default
    private String currency = "USD";

    @Size(max = 255, message = "Description cannot exceed 255 characters")
    private String description;

    @JsonIgnore
    @AssertTrue(message = "Source and destination accounts must be different")
    public boolean isDifferentAccounts() {
        if (sourceAccountId == null || destinationAccountId == null) {
            return true;
        }
        return !sourceAccountId.equals(destinationAccountId);
    }
}
