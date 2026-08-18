package com.osscontest.server

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import java.sql.Connection
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@SpringBootTest
class DatabaseEncryptionTest {

    @Autowired
    private lateinit var dataSource: DataSource

    @Value("\${DB_ENCRYPTION_KEY}")
    private lateinit var encryptionKey: String

    @Test
    fun `새로운 물리 Connection마다 세션 암호화 키를 설정한다`() {
        dataSource.connection.use { first ->
            dataSource.connection.use { second ->
                assertNotEquals(first.backendPid(), second.backendPid())
                assertTrue(first.usesExpectedEncryptionKey())
                assertTrue(second.usesExpectedEncryptionKey())
            }
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

    private fun Connection.backendPid(): Int =
        createStatement().use { statement ->
            statement.executeQuery("SELECT pg_backend_pid()").use { resultSet ->
                resultSet.next()
                resultSet.getInt(1)
            }
        }

    private fun Connection.usesExpectedEncryptionKey(): Boolean =
        prepareStatement("SELECT current_setting('app.encryption_key') = ?").use { statement ->
            statement.setString(1, encryptionKey)
            statement.executeQuery().use { resultSet ->
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
