package com.osscontest.server.indexing

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.postgresql.PGConnection
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 실제 등록·회수·알림 마이그레이션을 전용 폐기 DB 에 적용해 별도 세션의 알림 수신을 검증한다. */
@EnabledIfEnvironmentVariable(
    named = "INDEXING_NOTIFY_TEST_URL",
    matches = "jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/aidocs_notify_test",
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IndexingJobNotificationTest {
    private var initialized = false

    private fun connect(): Connection = DriverManager.getConnection(
        System.getenv("INDEXING_NOTIFY_TEST_URL"),
        System.getenv("INDEXING_NOTIFY_TEST_USERNAME") ?: "postgres",
        System.getenv("INDEXING_NOTIFY_TEST_PASSWORD") ?: "",
    )

    private fun Connection.sql(sql: String) = createStatement().use { it.execute(sql) }
    private fun Connection.value(sql: String): String? = createStatement().use { statement ->
        statement.executeQuery(sql).use { result -> if (result.next()) result.getString(1) else null }
    }
    private fun sql(sql: String) = connect().use { it.sql(sql) }
    private fun value(sql: String): String? = connect().use { it.value(sql) }
    private fun resource(name: String) = checkNotNull(javaClass.getResource(name)).readText()

    @BeforeAll
    fun install() {
        connect().use { connection ->
            check(connection.value("SELECT current_database()") == "aidocs_notify_test")
            check(connection.value("SELECT to_regclass('public.document')") == null) {
                "Use a fresh disposable database; existing document table will not be touched"
            }
            connection.autoCommit = false
            try {
                connection.sql(resource("/indexing/recovery-fixture.sql"))
                connection.sql(resource("/db/migration/V20261014_001__enqueue_indexing_job.sql"))
                connection.sql(resource("/db/migration/V20261014_003__reclaim_expired_indexing_jobs.sql"))
                connection.sql(resource("/db/migration/V20261015_001__notify_indexing_jobs.sql"))
                connection.commit()
                initialized = true
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            }
        }
    }

    @BeforeEach
    fun fixtures() {
        sql("TRUNCATE public.indexing_job, public.document_version, public.document")
        sql("INSERT INTO public.document(id) VALUES (1)")
    }

    @AfterAll
    fun cleanup() {
        if (!initialized) return
        connect().use { connection ->
            connection.sql("DROP TABLE public.indexing_recovery_primary, public.indexing_job, public.document_version, public.document")
            connection.sql("DROP PROCEDURE public.reclaim_expired_indexing_jobs(INTEGER)")
            connection.sql("DROP PROCEDURE public.mark_indexing_recovery_primary()")
            connection.sql("DROP FUNCTION public.fn_enqueue_index_job(), public.fn_enqueue_delete_job(), public.fn_notify_indexing_job()")
        }
    }

    private fun listen(): Connection = connect().apply { sql("LISTEN indexing_job") }

    private fun Connection.assertNotifications(expected: Int) {
        // 양성·음성 단언 모두 제한 시간 동안 실제 소켓을 읽는다. 0 은 무한 대기이므로 사용하지 않는다.
        val notifications = unwrap(PGConnection::class.java).getNotifications(200).orEmpty()
        assertEquals(expected, notifications.size)
        notifications.forEach {
            assertEquals("indexing_job", it.name)
            assertEquals("", it.parameter)
        }
    }

    private fun Connection.insertVersion(id: Int = 1) =
        sql("INSERT INTO public.document_version(id, document_id) VALUES ($id, 1)")

    private fun seedProcessingJob() {
        connect().use { it.insertVersion() }
        sql("""
            UPDATE public.indexing_job
            SET status='PROCESSING', worker_id='worker', lease_until=clock_timestamp()-INTERVAL '1 minute'
        """.trimIndent())
    }

    @Test
    fun `버전 저장으로 생성한 잡은 커밋 후에만 알린다`() {
        listen().use { listener ->
            connect().use { producer ->
                producer.autoCommit = false
                producer.insertVersion()
                listener.assertNotifications(0)
                producer.commit()
                listener.assertNotifications(1)
            }
        }
        assertEquals("INDEX", value("SELECT job_type FROM public.indexing_job"))
    }

    @Test
    fun `버전 저장이 롤백되면 잡과 알림이 남지 않는다`() {
        listen().use { listener ->
            connect().use { producer ->
                producer.autoCommit = false
                producer.insertVersion()
                producer.rollback()
            }
            listener.assertNotifications(0)
        }
        assertEquals("0", value("SELECT count(*) FROM public.indexing_job"))
    }

    @Test
    fun `문서 삭제로 생성한 잡을 알리고 중복 삭제는 알리지 않는다`() {
        listen().use { listener ->
            sql("UPDATE public.document SET deleted_at=clock_timestamp() WHERE id=1")
            listener.assertNotifications(1)
            sql("UPDATE public.document SET deleted_at=clock_timestamp() WHERE id=1")
            listener.assertNotifications(0)
        }
        assertEquals("DELETE", value("SELECT job_type FROM public.indexing_job"))
    }

    @Test
    fun `실패한 버전의 잡을 직접 다시 등록해도 알린다`() {
        connect().use { it.insertVersion() }
        sql("UPDATE public.indexing_job SET status='FAILED'")
        listen().use { listener ->
            // 수동 재인덱싱 API 는 document_version 을 추가하지 않고 indexing_job 을 직접 저장한다.
            sql("""
                INSERT INTO public.indexing_job(source_event_id, document_id, document_version_id, job_type, status)
                VALUES (gen_random_uuid(), 1, 1, 'INDEX', 'PENDING')
            """.trimIndent())
            listener.assertNotifications(1)
        }
        assertEquals("2", value("SELECT count(*) FROM public.indexing_job"))
    }

    @Test
    fun `만료 잡을 PENDING으로 회수하면 알린다`() {
        seedProcessingJob()
        sql("CALL public.mark_indexing_recovery_primary()")
        sql("UPDATE public.indexing_recovery_primary SET writable_since=clock_timestamp()-INTERVAL '31 seconds'")
        listen().use { listener ->
            sql("CALL public.reclaim_expired_indexing_jobs(100)")
            listener.assertNotifications(1)
        }
        assertEquals("PENDING", value("SELECT status FROM public.indexing_job"))
    }

    @Test
    fun `PENDING 복귀가 롤백되면 알리지 않는다`() {
        seedProcessingJob()
        listen().use { listener ->
            connect().use { producer ->
                producer.autoCommit = false
                producer.sql("UPDATE public.indexing_job SET status='PENDING', worker_id=NULL, lease_until=NULL")
                listener.assertNotifications(0)
                producer.rollback()
            }
            listener.assertNotifications(0)
        }
        assertEquals("PROCESSING", value("SELECT status FROM public.indexing_job"))
    }

    @Test
    fun `PENDING이 아닌 상태와 같은 상태의 갱신은 알리지 않는다`() {
        seedProcessingJob()
        listen().use { listener ->
            sql("UPDATE public.indexing_job SET lease_until=clock_timestamp()+INTERVAL '1 minute'")
            sql("UPDATE public.indexing_job SET status='PROCESSING'")
            sql("UPDATE public.indexing_job SET status='RETRY_WAIT', worker_id=NULL, lease_until=NULL")
            sql("UPDATE public.indexing_job SET status='COMPLETED'")
            sql("UPDATE public.indexing_job SET status='FAILED'")
            listener.assertNotifications(0)
            sql("UPDATE public.indexing_job SET status='PENDING'")
            listener.assertNotifications(1)
            sql("UPDATE public.indexing_job SET status='PENDING', updated_at=clock_timestamp()")
            listener.assertNotifications(0)
        }
    }

    @Test
    fun `한 트랜잭션에서 여러 잡을 생성하면 알림 하나로 합친다`() {
        listen().use { listener ->
            connect().use { producer ->
                producer.autoCommit = false
                producer.insertVersion(1)
                producer.insertVersion(2)
                producer.commit()
            }
            listener.assertNotifications(1)
        }
        assertEquals("2", value("SELECT count(*) FROM public.indexing_job"))
    }

    @Test
    fun `구독 연결이 없어도 잡은 저장되고 재구독 후 조회할 수 있다`() {
        connect().use { it.insertVersion() }
        listen().use { listener ->
            listener.assertNotifications(0)
            assertTrue(listener.value("SELECT EXISTS(SELECT 1 FROM public.indexing_job WHERE status='PENDING')") == "t")
        }
    }
}
