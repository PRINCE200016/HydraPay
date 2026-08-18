package com.hydrapay.ledger.domain.enums;

public enum EntryType {
    DEBIT,  // Decreases balance for asset/expense or reduces liability
    CREDIT  // Increases balance for liability/equity or reduces asset
}
