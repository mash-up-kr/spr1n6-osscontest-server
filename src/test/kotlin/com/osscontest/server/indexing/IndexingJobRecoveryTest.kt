package com.osscontest.server.indexing

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 환경 변수를 명시한 전용 로컬 폐기 DB에서만 실행한다. Spring/Flyway 자동 기동은 하지 않는다.
 * Worker 194d570의 claim/소유권 SQL 계약을 JDBC로 재현하며 실제 Worker bean 호출 테스트는 아니다.
 * pg_cron/HA 실환경 검증은 docs/INDEXING_JOB_RECOVERY.md에 별도 기록한다.
 */
@EnabledIfEnvironmentVariable(
    named = "INDEXING_RECOVERY_TEST_URL",
    matches = "jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/aidocs_recovery_test",
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IndexingJobRecoveryTest {
    private val cronName = "aidocs-reclaim-expired-indexing-jobs"
    private val cronEnabled get() = System.getenv("INDEXING_RECOVERY_TEST_CRON") == "true"
    private var initialized = false

    private fun connect(): Connection = DriverManager.getConnection(
        System.getenv("INDEXING_RECOVERY_TEST_URL"),
        System.getenv("INDEXING_RECOVERY_TEST_USERNAME") ?: "postgres",
        System.getenv("INDEXING_RECOVERY_TEST_PASSWORD") ?: "",
    )

    private fun Connection.sql(sql: String) = createStatement().use { it.execute(sql) }
    private fun Connection.update(sql: String): Int = createStatement().use { it.executeUpdate(sql) }
    private fun Connection.value(sql: String): String? = createStatement().use { statement ->
        statement.executeQuery(sql).use { result -> if (result.next()) result.getString(1) else null }
    }
    private fun sql(sql: String) = connect().use { it.sql(sql) }
    private fun value(sql: String): String? = connect().use { it.value(sql) }
    private fun resource(name: String) = checkNotNull(javaClass.getResource(name)).readText()
    private fun scheduleMigration() = resource("/db/migration/V20261014_004__schedule_indexing_job_recovery.sql")

    @BeforeAll
    fun install() {
        connect().use { c ->
            check(c.value("SELECT current_database()") == "aidocs_recovery_test")
            check(c.value("SELECT pg_is_in_recovery()") == "f")
            check(c.value("SELECT to_regclass('public.document')") == null) {
                "Use a fresh disposable cluster; existing document table will not be touched"
            }
            c.autoCommit = false
            try {
                c.sql(resource("/indexing/recovery-fixture.sql"))
                c.sql(resource("/db/migration/V20261014_003__reclaim_expired_indexing_jobs.sql"))
                c.commit()
                initialized = true
            } catch (error: Throwable) {
                c.rollback()
                throw error
            }
        }
    }

    @BeforeEach
    fun fixtures() {
        sql("TRUNCATE public.indexing_job, public.document_version, public.document")
        sql("CALL public.mark_indexing_recovery_primary()")
        sql("UPDATE public.indexing_recovery_primary SET writable_since=clock_timestamp()-INTERVAL '31 seconds'")
    }

    @AfterEach
    fun stopCron() {
        if (initialized && cronEnabled) {
            sql("SELECT cron.unschedule(jobid) FROM cron.job WHERE jobname='$cronName'")
        }
    }

    @AfterAll
    fun cleanup() {
        if (!initialized) return
        connect().use { c ->
            if (cronEnabled) c.sql("SELECT cron.unschedule(jobid) FROM cron.job WHERE jobname='$cronName'")
            c.sql("DROP PROCEDURE public.reclaim_expired_indexing_jobs(INTEGER)")
            c.sql("DROP PROCEDURE public.mark_indexing_recovery_primary()")
            c.sql("DROP TABLE public.indexing_recovery_primary, public.indexing_job, public.document_version, public.document")
        }
    }

    private fun seed(id: Int, type: String = "INDEX", status: String = "PROCESSING", expired: Boolean = true, docId: Int = id) {
        val version = if (type == "INDEX") id.toString() else "NULL"
        val worker = if (status == "PROCESSING") "'old-$id'" else "NULL"
        val lease = if (status != "PROCESSING") "NULL" else
            "clock_timestamp() ${if (expired) "-" else "+"} INTERVAL '1 hour'"
        sql("""
            INSERT INTO public.document(id, execution_epoch) VALUES($docId, 7) ON CONFLICT DO NOTHING;
            INSERT INTO public.document_version(id, document_id) VALUES($id, $docId);
            INSERT INTO public.indexing_job(id, source_event_id, document_id, document_version_id, job_type,
                status, worker_id, lease_until, attempt_count, execution_epoch, phase, last_error_code,
                last_error_message, trace_id, started_at, next_retry_at)
            VALUES($id, gen_random_uuid(), $docId, $version, '$type', '$status', $worker, $lease,
                2, 7, 'EMBEDDING', 'PREVIOUS_ERROR', 'history', 'trace', clock_timestamp()-INTERVAL '1 day',
                clock_timestamp()+INTERVAL '1 hour')
        """.trimIndent())
    }

    private fun row(id: Int) = value("SELECT to_jsonb(j)::text FROM public.indexing_job j WHERE id=$id")
    private fun retained(id: Int) = value("""
        SELECT (to_jsonb(j)-ARRAY['status','worker_id','lease_until','next_retry_at','updated_at'])::text
        FROM public.indexing_job j WHERE id=$id
    """.trimIndent())

    private fun reclaim(batch: Int = 100) = sql("CALL public.reclaim_expired_indexing_jobs($batch)")

    // 실제 Worker 와 같은 문서 → Job 잠금/token 조건. 만료 lease 의 heartbeat 도 허용한다.
    private fun owned(id: Int, worker: String, epoch: Int) = """
        j.id=$id AND j.status='PROCESSING' AND j.worker_id='$worker' AND j.execution_epoch=$epoch
        AND j.lease_until IS NOT NULL AND EXISTS(
            SELECT 1 FROM public.document d WHERE d.id=j.document_id AND d.execution_epoch=$epoch)
    """.trimIndent()

    private fun Connection.workerWrite(id: Int, assignments: String, heartbeat: Boolean = false, worker: String = "old-$id", epoch: Int = 7): Int {
        sql("SELECT d.id FROM public.document d JOIN public.indexing_job j ON j.document_id=d.id WHERE j.id=$id FOR UPDATE OF d")
        sql("SELECT id FROM public.indexing_job WHERE id=$id FOR UPDATE")
        val lease = if (heartbeat) "" else "AND j.lease_until > clock_timestamp()"
        return update("UPDATE public.indexing_job j SET $assignments, updated_at=clock_timestamp() WHERE ${owned(id, worker, epoch)} $lease")
    }

    private fun heartbeat(id: Int): Int = connect().use { c ->
        c.autoCommit = false
        c.sql("SET LOCAL application_name='recovery-test-heartbeat'")
        val result = c.workerWrite(id, "lease_until=clock_timestamp()+INTERVAL '60 seconds'", heartbeat = true)
        c.commit()
        result
    }

    /** breaker가 CLOSED인 claim의 실제 eligibility/epoch/attempt 증가 계약. */
    private fun claim(worker: String = "new-worker", maxAttempts: Int = 5): Int? = connect().use { c ->
        c.autoCommit = false
        val eligible = """
            j.worker_id IS NULL AND j.lease_until IS NULL AND j.attempt_count < $maxAttempts
            AND (j.status='PENDING' OR (j.status='RETRY_WAIT' AND j.next_retry_at<=clock_timestamp()))
            AND NOT EXISTS(SELECT 1 FROM public.indexing_job p WHERE p.document_id=j.document_id
                AND p.enqueue_seq<j.enqueue_seq AND p.status IN ('PENDING','PROCESSING','RETRY_WAIT'))
        """.trimIndent()
        val id = c.value("""
            SELECT j.id FROM public.indexing_job j JOIN public.document d ON d.id=j.document_id
            WHERE $eligible ORDER BY j.enqueue_seq LIMIT 1 FOR UPDATE OF d SKIP LOCKED
        """.trimIndent())?.toInt()
        if (id == null || c.value("SELECT id FROM public.indexing_job WHERE id=$id FOR UPDATE SKIP LOCKED") == null) {
            c.rollback()
            return@use null
        }
        val changed = c.update("""
            WITH bumped AS (UPDATE public.document d SET execution_epoch=d.execution_epoch+1
                FROM public.indexing_job old WHERE old.id=$id AND d.id=old.document_id RETURNING d.id, d.execution_epoch)
            UPDATE public.indexing_job j SET status='PROCESSING', worker_id='$worker', execution_epoch=b.execution_epoch,
                attempt_count=attempt_count+1, lease_until=clock_timestamp()+INTERVAL '60 seconds',
                next_retry_at=NULL, outcome=NULL, started_at=COALESCE(started_at,clock_timestamp()), updated_at=clock_timestamp()
            FROM bumped b WHERE j.id=$id AND j.document_id=b.id AND $eligible
        """.trimIndent())
        assertEquals(1, changed)
        c.commit()
        id
    }

    @Test
    fun `INDEX와 DELETE만 회수하고 나머지 상태와 이력은 보존한다`() {
        seed(1)
        seed(2, type = "DELETE")
        seed(3, expired = false)
        seed(4, status = "RETRY_WAIT")
        seed(5, status = "COMPLETED")
        seed(6, status = "FAILED")
        seed(7, status = "PENDING")
        sql("UPDATE public.document SET deleted_at=clock_timestamp() WHERE id IN (1,2)")
        val preserved = (1..2).associateWith { retained(it) }
        val untouched = (3..7).associateWith { row(it) }
        reclaim()
        for (id in 1..2) {
            assertEquals("PENDING", value("SELECT status FROM public.indexing_job WHERE id=$id"))
            assertEquals("t", value("SELECT worker_id IS NULL AND lease_until IS NULL AND next_retry_at IS NULL FROM public.indexing_job WHERE id=$id"))
            assertEquals(preserved[id], retained(id))
            assertEquals("7", value("SELECT execution_epoch FROM public.document WHERE id=$id"))
        }
        untouched.forEach { (id, before) -> assertEquals(before, row(id)) }
    }

    @Test
    fun `회수는 배치 건수로 제한하고 문서와 Job 잠금은 기다리지 않는다`() {
        for (id in 1..4) seed(id)
        connect().use { docLock ->
            connect().use { jobLock ->
                docLock.autoCommit = false
                jobLock.autoCommit = false
                docLock.sql("SELECT id FROM public.document WHERE id=1 FOR UPDATE")
                jobLock.sql("SELECT id FROM public.indexing_job WHERE id=2 FOR UPDATE")
                CompletableFuture.runAsync { reclaim(3) }.get(3, TimeUnit.SECONDS)
                assertEquals("1", value("SELECT count(*) FROM public.indexing_job WHERE status='PENDING'"))
                assertEquals("PROCESSING", value("SELECT status FROM public.indexing_job WHERE id=4"))
                docLock.rollback()
                jobLock.rollback()
            }
        }
        reclaim(1)
        assertEquals("2", value("SELECT count(*) FROM public.indexing_job WHERE status='PENDING'"))
    }

    @Test
    fun `heartbeat가 먼저 커밋하면 회수하지 않는다`() {
        seed(1)
        assertEquals(1, heartbeat(1))
        val renewed = row(1)
        reclaim()
        assertEquals(renewed, row(1))
    }

    @Test
    fun `후보 조회 이후 바뀐 lease 소유자와 epoch를 잠금 후 재검증한다`() {
        // 첫 후보 UPDATE 에만 테스트 장벽을 둔다. 두 번째 후보의 옛 snapshot을 읽은 상태에서
        // 다른 연결이 heartbeat/소유권을 변경해도 두 번째 Job을 회수하면 안 된다.
        sql("""
            CREATE FUNCTION public.pause_recovery_fixture() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
            BEGIN
                PERFORM set_config('lock_timeout','5s',true);
                PERFORM pg_advisory_xact_lock(987654);
                RETURN NEW;
            END ${'$'}${'$'};
            CREATE TRIGGER pause_recovery_fixture BEFORE UPDATE ON public.indexing_job
                FOR EACH ROW WHEN (OLD.id=1 AND NEW.status='PENDING') EXECUTE FUNCTION public.pause_recovery_fixture()
        """.trimIndent())
        try {
            for (change in listOf("heartbeat", "new-owner", "document-epoch", "expired-renewal", "released")) {
                sql("TRUNCATE public.indexing_job, public.document_version, public.document")
                seed(1)
                seed(2)
                connect().use { barrier ->
                    barrier.sql("SELECT pg_advisory_lock(987654)")
                    val recovery = CompletableFuture.runAsync { reclaim() }
                    try {
                        val deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos()
                        var waiting = false
                        while (System.nanoTime() < deadline) {
                            waiting = value("SELECT EXISTS(SELECT 1 FROM pg_locks WHERE locktype='advisory' AND objid=987654 AND NOT granted)") == "t"
                            if (waiting) break
                            Thread.sleep(10)
                        }
                        assertTrue(waiting, "회수가 첫 후보 장벽에 도달해야 한다")
                        when (change) {
                            "heartbeat" -> assertEquals(1, heartbeat(2))
                            "new-owner" -> sql("""
                                BEGIN;
                                UPDATE public.document SET execution_epoch=8 WHERE id=2;
                                UPDATE public.indexing_job SET execution_epoch=8,worker_id='replacement' WHERE id=2;
                                COMMIT
                            """.trimIndent())
                            "document-epoch" -> sql("UPDATE public.document SET execution_epoch=8 WHERE id=2")
                            "expired-renewal" -> sql("UPDATE public.indexing_job SET lease_until=clock_timestamp()-INTERVAL '1 minute' WHERE id=2")
                            "released" -> sql("UPDATE public.indexing_job SET status='RETRY_WAIT',worker_id=NULL,lease_until=NULL WHERE id=2")
                        }
                        val afterChange = row(2)
                        barrier.sql("SELECT pg_advisory_unlock(987654)")
                        recovery.get(3, TimeUnit.SECONDS)
                        assertEquals("PENDING", value("SELECT status FROM public.indexing_job WHERE id=1"))
                        assertEquals(afterChange, row(2), "후보 조회 뒤 변경된 $change 상태를 보존해야 한다")
                    } finally {
                        barrier.sql("SELECT pg_advisory_unlock(987654)")
                        recovery.get(6, TimeUnit.SECONDS)
                    }
                }
            }
        } finally {
            sql("DROP TRIGGER pause_recovery_fixture ON public.indexing_job; DROP FUNCTION public.pause_recovery_fixture()")
        }
    }

    @Test
    fun `회수가 잠금을 먼저 잡으면 heartbeat는 커밋 후 소유권 상실을 확인한다`() {
        seed(1)
        connect().use { c ->
            c.autoCommit = false
            c.sql("CALL public.reclaim_expired_indexing_jobs(100)")
            val heartbeat = CompletableFuture.supplyAsync { heartbeat(1) }
            try {
                val deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos()
                var blocked = false
                while (System.nanoTime() < deadline) {
                    blocked = value("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE application_name='recovery-test-heartbeat' AND wait_event_type='Lock')") == "t"
                    if (blocked) break
                    Thread.sleep(10)
                }
                assertTrue(blocked, "이전 heartbeat가 회수의 문서 잠금을 기다리는 상태여야 한다")
                c.commit()
                assertEquals(0, heartbeat.get(3, TimeUnit.SECONDS))
            } finally {
                c.rollback()
                heartbeat.get(3, TimeUnit.SECONDS)
            }
        }
        assertEquals("PENDING", value("SELECT status FROM public.indexing_job WHERE id=1"))
    }

    @Test
    fun `회수 커밋 후 재claim 전과 후에 이전 Worker의 모든 쓰기를 거절한다`() {
        seed(1)
        seed(2, status = "PENDING", docId = 1)
        reclaim()
        fun assertOldWritesRejected() {
            val before = row(1)
            assertEquals(0, heartbeat(1))
            for (assignment in listOf(
                "phase='PARSING'",
                "status='FAILED', worker_id=NULL, lease_until=NULL, last_error_code='LATE_ERROR'",
                "status='COMPLETED', worker_id=NULL, lease_until=NULL, completed_at=clock_timestamp(), outcome='INDEXED'",
            )) {
                connect().use { c ->
                    c.autoCommit = false
                    assertEquals(0, c.workerWrite(1, assignment))
                    c.commit()
                }
            }
            assertEquals(before, row(1))
        }
        assertOldWritesRejected()
        assertEquals(1, claim())
        assertEquals("8", value("SELECT execution_epoch FROM public.indexing_job WHERE id=1"))
        assertEquals("8", value("SELECT execution_epoch FROM public.document WHERE id=1"))
        assertEquals("3", value("SELECT attempt_count FROM public.indexing_job WHERE id=1"))
        assertEquals(null, claim("third-worker"), "앞선 미종결 Job이 같은 문서의 후속 Job을 막는다")
        assertOldWritesRejected()
    }

    @Test
    fun `시도 한도는 변경하지 않으며 한도 소진 Job은 회수 후 claim과 후속 Job을 막는다`() {
        seed(1)
        seed(2, status = "PENDING", docId = 1)
        sql("UPDATE public.indexing_job SET attempt_count=5 WHERE id=1")
        reclaim()
        assertEquals("PENDING", value("SELECT status FROM public.indexing_job WHERE id=1"))
        assertEquals("5", value("SELECT attempt_count FROM public.indexing_job WHERE id=1"))
        assertEquals(null, claim())
    }

    @Test
    fun `기동 timeline 불일치와 30초 유예에서는 반환하고 중복 콜백은 유예를 늘리지 않는다`() {
        seed(1)
        val before = row(1)
        sql("UPDATE public.indexing_recovery_primary SET postmaster_started_at=postmaster_started_at-INTERVAL '1 second'")
        reclaim()
        assertEquals(before, row(1))
        sql("CALL public.mark_indexing_recovery_primary()")
        val marker = value("SELECT to_jsonb(p)::text FROM public.indexing_recovery_primary p")
        sql("CALL public.mark_indexing_recovery_primary()")
        assertEquals(marker, value("SELECT to_jsonb(p)::text FROM public.indexing_recovery_primary p"))
        reclaim()
        assertEquals(before, row(1))
        sql("UPDATE public.indexing_recovery_primary SET writable_since=clock_timestamp()-INTERVAL '29 seconds'")
        reclaim()
        assertEquals(before, row(1))
        sql("UPDATE public.indexing_recovery_primary SET writable_since=clock_timestamp()-INTERVAL '30 seconds'")
        reclaim()
        assertEquals("PENDING", value("SELECT status FROM public.indexing_job WHERE id=1"))
        seed(2)
        sql("UPDATE public.indexing_recovery_primary SET wal_timeline='FFFFFFFF'")
        reclaim()
        assertEquals("PROCESSING", value("SELECT status FROM public.indexing_job WHERE id=2"))
        sql("CALL public.mark_indexing_recovery_primary()")
        reclaim()
        assertEquals("PROCESSING", value("SELECT status FROM public.indexing_job WHERE id=2"))
    }

    @Test
    fun `읽기 전용 트랜잭션에서는 Job과 기준 시각을 변경하지 않는다`() {
        seed(1)
        val before = row(1)
        val marker = value("SELECT to_jsonb(p)::text FROM public.indexing_recovery_primary p")
        connect().use { c ->
            c.autoCommit = false
            c.sql("SET TRANSACTION READ ONLY")
            c.sql("CALL public.mark_indexing_recovery_primary()")
            c.sql("CALL public.reclaim_expired_indexing_jobs(100)")
            c.commit()
        }
        connect().use { c ->
            c.sql("SET default_transaction_read_only=on")
            c.sql("CALL public.mark_indexing_recovery_primary()")
            c.sql("CALL public.reclaim_expired_indexing_jobs(100)")
        }
        assertEquals(before, row(1))
        assertEquals(marker, value("SELECT to_jsonb(p)::text FROM public.indexing_recovery_primary p"))
    }

    @Test
    fun `회수의 쓰기 시각은 트랜잭션 시작 시각이 아니라 잠금 이후 시각이다`() {
        seed(1)
        connect().use { c ->
            c.autoCommit = false
            c.sql("SELECT pg_sleep(0.05)")
            c.sql("CALL public.reclaim_expired_indexing_jobs(100)")
            assertEquals("t", c.value("SELECT updated_at>transaction_timestamp() FROM public.indexing_job WHERE id=1"))
            c.commit()
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "INDEXING_RECOVERY_TEST_CRON", matches = "true")
    fun `등록한 스케줄이 로컬 DB와 기존 계정으로 30초마다 실행한다`() {
        val runStarted = value("SELECT clock_timestamp()")!!
        sql(scheduleMigration())
        assertEquals("1", value("SELECT count(*) FROM cron.job WHERE jobname='$cronName'"))
        assertEquals("t", value("""
            SELECT schedule='30 seconds' AND database=current_database() AND username=current_user
                AND (nodename IN ('localhost','127.0.0.1') OR nodename LIKE '/%')
                AND nodeport=current_setting('port')::integer AND active
            FROM cron.job WHERE jobname='$cronName'
        """.trimIndent()))
        seed(1)
        // 실제 스케줄러 실행을 기다린다. DB 실행을 명시적으로 허용한 경우만 활성화된다.
        val deadline = System.nanoTime() + Duration.ofSeconds(95).toNanos()
        var starts = 0
        while (System.nanoTime() < deadline) {
            starts = value("""
                SELECT count(*) FROM cron.job_run_details r JOIN cron.job j USING(jobid)
                WHERE j.jobname='$cronName' AND r.status='succeeded' AND r.start_time>=TIMESTAMPTZ '$runStarted'
            """.trimIndent())!!.toInt()
            if (starts >= 2 && value("SELECT status FROM public.indexing_job WHERE id=1") == "PENDING") break
            Thread.sleep(200)
        }
        assertTrue(starts >= 2, "pg_cron의 실제 실행 성공 기록이 필요하다")
        assertEquals("PENDING", value("SELECT status FROM public.indexing_job WHERE id=1"))
        assertEquals("t", value("""
            SELECT (max(start_time)-min(start_time)) BETWEEN INTERVAL '25 seconds' AND INTERVAL '40 seconds'
            FROM (SELECT r.start_time FROM cron.job_run_details r JOIN cron.job j USING(jobid)
                WHERE j.jobname='$cronName' AND r.status='succeeded' AND r.start_time>=TIMESTAMPTZ '$runStarted'
                ORDER BY r.start_time DESC LIMIT 2) runs
        """.trimIndent()))
    }

}
