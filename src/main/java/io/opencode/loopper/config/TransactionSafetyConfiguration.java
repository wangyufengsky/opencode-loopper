package io.opencode.loopper.config;

import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Restores a reusable JDBC connection when a deferred database constraint rejects commit. */
@Configuration(proxyBeanMethods = false)
public class TransactionSafetyConfiguration {
    @Bean
    TransactionManagerCustomizer<DataSourceTransactionManager> rollbackAfterCommitFailure() {
        return transactionManager -> transactionManager.setRollbackOnCommitFailure(true);
    }
}
