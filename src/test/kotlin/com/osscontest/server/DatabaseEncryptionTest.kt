package com.osscontest.server

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Connection
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest
class DatabaseEncryptionTest {

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Value("\${DB_ENCRYPTION_KEY}")
    private lateinit var encryptionKey: String

    @Value("\${DB_ENCRYPTION_CIPHER:aria256}")
    private lateinit var encryptionCipher: String

    @Test
    fun `읽기 전용을 포함한 모든 트랜잭션에 암호화 키와 알고리즘을 설정한다`() {
        listOf(false, true).forEach { readOnly ->
            val settings = transactionTemplate(readOnly).execute {
                jdbcTemplate.queryForList(
                    "SELECT current_setting('app.encryption_key') AS key, current_setting('app.encryption_cipher') AS cipher",
                ).single()
            }!!

            assertEquals(encryptionKey, settings["key"], "readOnly=$readOnly")
            assertEquals(encryptionCipher, settings["cipher"], "readOnly=$readOnly")
        }
    }

    @Test
    fun `트랜잭션 밖의 연결에는 암호화 키가 남지 않는다`() {
        // 연결 단위로 키를 넣던 방식이 되살아나면 깨진다. 한 번 트랜잭션에서 설정한 연결도
        // 트랜잭션이 끝나면 빈 값으로 돌아가야 한다.
        transactionTemplate(readOnly = true).execute { jdbcTemplate.queryForObject("SELECT 1", Int::class.java) }

        dataSource.connection.use { connection ->
            assertTrue(connection.encryptionKeyIsEmpty())
        }
    }

    @Test
    fun `암복호화 함수의 시그니처와 volatility가 요구사항과 일치한다`() {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT
                    p.proname,
                    p.provolatile,
                    p.proisstrict,
                    format_type(p.proargtypes[0], NULL),
                    pg_get_function_result(p.oid)
                FROM pg_proc p
                JOIN pg_namespace n ON n.oid = p.pronamespace
                WHERE n.nspname = current_schema()
                  AND p.proname IN ('app_encrypt', 'app_decrypt')
                ORDER BY p.proname
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { resultSet ->
                    val functions = buildMap {
                        while (resultSet.next()) {
                            put(
                                resultSet.getString(1),
                                FunctionMetadata(
                                    volatility = resultSet.getString(2),
                                    strict = resultSet.getBoolean(3),
                                    argumentType = resultSet.getString(4),
                                    resultType = resultSet.getString(5),
                                ),
                            )
                        }
                    }

                    assertEquals(FunctionMetadata("s", true, "bytea", "bytea"), functions["app_decrypt"])
                    assertEquals(FunctionMetadata("v", true, "bytea", "bytea"), functions["app_encrypt"])
                }
            }
        }
    }

    @Test
    fun `세션에 설정된 알고리즘으로 암호화하고 기존 AES 256 암호문도 복호화한다`() {
        val (functionDefinition, decryptsOwnCipher, decryptsAes256) = transactionTemplate(readOnly = false).execute {
            jdbcTemplate.query(
                """
                WITH plain AS (
                    SELECT convert_to(?::text, 'UTF8') AS value
                )
                SELECT
                    pg_get_functiondef('app_encrypt(bytea)'::regprocedure),
                    app_decrypt(app_encrypt(value)) = value,
                    app_decrypt(
                        pgp_sym_encrypt_bytea(
                            value,
                            current_setting('app.encryption_key'),
                            'cipher-algo=aes256'
                        )
                    ) = value
                FROM plain
                """.trimIndent(),
                RowMapper { resultSet, _ -> Triple(resultSet.getString(1), resultSet.getBoolean(2), resultSet.getBoolean(3)) },
                "ARIA 암복호화 확인",
            ).single()
        }!!

        // 알고리즘을 함수에 박지 않고 세션 설정에서 읽는지 본다. 값 자체는 프로필마다 다르다.
        assertTrue(functionDefinition.contains("app.encryption_cipher"))
        assertTrue(decryptsOwnCipher)
        assertTrue(decryptsAes256)
    }

    private fun transactionTemplate(readOnly: Boolean): TransactionTemplate =
        TransactionTemplate(transactionManager).apply { isReadOnly = readOnly }

    // 같은 연결에서 한 번이라도 set_config 를 했으면 NULL 이 아니라 빈 문자열이 남는다.
    private fun Connection.encryptionKeyIsEmpty(): Boolean =
        createStatement().use { statement ->
            statement.executeQuery("SELECT coalesce(current_setting('app.encryption_key', true), '') = ''").use { resultSet ->
                resultSet.next()
                resultSet.getBoolean(1)
            }
        }

    private data class FunctionMetadata(
        val volatility: String,
        val strict: Boolean,
        val argumentType: String,
        val resultType: String,
    )
}
