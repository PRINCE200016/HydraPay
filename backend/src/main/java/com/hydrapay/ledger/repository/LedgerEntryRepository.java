package com.hydrapay.ledger.repository;

import com.hydrapay.ledger.domain.entity.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {
    List<LedgerEntry> findByTransactionId(UUID transactionId);
    List<LedgerEntry> findByAccountIdOrderByCreatedAtDesc(UUID accountId);
    Optional<LedgerEntry> findFirstByAccountIdOrderByCreatedAtDesc(UUID accountId);

    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM LedgerEntry e")
    BigDecimal calculateGlobalLedgerNetSum();

    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM LedgerEntry e WHERE e.accountId = :accountId")
    BigDecimal calculateSumByAccountId(@Param("accountId") UUID accountId);
}

