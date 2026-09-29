package io.opencode.loopper.config;

import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Keeps short database transactions distinct from suspended external-effect orchestration. */
@Configuration(proxyBeanMethods = false)
public class TransactionSafetyConfiguration {
    @Bean
    TransactionManagerCustomizer<DataSourceTransactionManager> rollbackAfterCommitFailure() {
        return transactionManager -> {
            transactionManager.setRollbackOnCommitFailure(true);
            // NOT_SUPPORTED must not retain a MyBatis SqlSession/cache across committed inner transactions.
            transactionManager.setTransactionSynchronization(DataSourceTransactionManager.SYNCHRONIZATION_ON_ACTUAL_TRANSACTION);
        };
    }
}
