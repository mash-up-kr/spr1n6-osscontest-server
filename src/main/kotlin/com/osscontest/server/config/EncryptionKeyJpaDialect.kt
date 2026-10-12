package com.osscontest.server.config

import jakarta.persistence.EntityManager
import org.springframework.orm.jpa.vendor.HibernateJpaDialect
import org.springframework.transaction.TransactionDefinition

/**
 * 트랜잭션을 열 때마다 DB 암호화 키와 알고리즘을 그 트랜잭션에만 설정한다.
 *
 * 연결 단위 세션 설정은 OpenProxy를 거치면 쓸 수 없다. pgJDBC `options`는 전달되지 않고,
 * connection-init-sql 자리는 `SET SERVER ROLE`이 차지한다. 그래서 app_encrypt·app_decrypt를
 * 부르는 쿼리는 반드시 트랜잭션 안에서 실행해야 한다.
 */
class EncryptionKeyJpaDialect(
    private val encryptionKey: String,
    private val encryptionCipher: String,
) : HibernateJpaDialect() {

    override fun beginTransaction(entityManager: EntityManager, definition: TransactionDefinition): Any {
        val transactionData = super.beginTransaction(entityManager, definition)
        entityManager.createNativeQuery(SET_ENCRYPTION_KEY_SQL)
            .setParameter("key", encryptionKey)
            .setParameter("cipher", encryptionCipher)
            .singleResult
        return transactionData
    }

    private companion object {
        const val SET_ENCRYPTION_KEY_SQL =
            "SELECT set_config('app.encryption_key', :key, true), set_config('app.encryption_cipher', :cipher, true)"
    }
}
