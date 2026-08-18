package com.hydrapay.ledger.dto;

import com.hydrapay.ledger.domain.enums.AccountStatus;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AccountResponse {
    private UUID id;
    private String accountNumber;
    private String accountHolderName;
    private String currency;
    private BigDecimal balance;
    private AccountStatus status;
    private Long version;
    private OffsetDateTime createdAt;
}
