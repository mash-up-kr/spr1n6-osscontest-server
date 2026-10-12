package com.osscontest.server.config

import jakarta.persistence.EntityManagerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizers
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.orm.jpa.JpaDialect
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.transaction.PlatformTransactionManager

/** Boot 기본 transactionManager 를 암호화 키를 설정하는 트랜잭션 매니저로 대체한다. */
@Configuration
class TransactionConfig {

    @Bean
    fun transactionManager(
        entityManagerFactory: EntityManagerFactory,
        transactionManagerCustomizers: ObjectProvider<TransactionManagerCustomizers>,
        @Value("\${DB_ENCRYPTION_KEY}") encryptionKey: String,
        // ARIA 는 Tmax OpenCrypto 확장에만 있어 로컬 pgcrypto 에서는 쓸 수 없다. local 프로필이 aes256 으로 덮는다.
        @Value("\${DB_ENCRYPTION_CIPHER:aria256}") encryptionCipher: String,
    ): PlatformTransactionManager =
        EncryptionKeyTransactionManager(EncryptionKeyJpaDialect(encryptionKey, encryptionCipher))
            .apply { this.entityManagerFactory = entityManagerFactory }
            .also { transactionManager -> transactionManagerCustomizers.ifAvailable { it.customize(transactionManager) } }

    // EntityManagerFactory 를 받는 부모 생성자는 이 클래스의 필드가 채워지기 전에 afterPropertiesSet 을
    // 부르므로 인자 없는 생성자를 쓴다.
    private class EncryptionKeyTransactionManager(
        private val encryptionKeyDialect: JpaDialect,
    ) : JpaTransactionManager() {

        // 부모가 초기화 때 EntityManagerFactory 의 기본 dialect 로 덮어쓰므로 그 뒤에 다시 넣는다.
        override fun afterPropertiesSet() {
            super.afterPropertiesSet()
            setJpaDialect(encryptionKeyDialect)
        }
    }
}
