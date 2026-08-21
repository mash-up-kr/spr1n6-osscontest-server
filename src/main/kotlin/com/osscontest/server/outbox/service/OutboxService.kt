package com.osscontest.server.outbox.service

import com.osscontest.server.outbox.domain.OutboxEvent
import com.osscontest.server.outbox.repository.OutboxEventRepository
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 애플리케이션이 직접 만드는 Outbox 이벤트 발행.
 * 업로드 건은 document_version INSERT 트리거가 같은 일을 하므로 여기를 거치지 않는다.
 */
@Service
class OutboxService(
    private val outboxEventRepository: OutboxEventRepository,
    private val entityManager: EntityManager,
) {

    /** 릴레이가 알림을 받고 바로 조회할 수 있도록 행을 먼저 내보낸 뒤 알린다. */
    @Transactional
    fun publish(event: OutboxEvent): OutboxEvent {
        val saved = outboxEventRepository.saveAndFlush(event)

        entityManager.createNativeQuery("SELECT pg_notify('outbox_event', :eventId)")
            .setParameter("eventId", saved.id.toString())
            .singleResult

        return saved
    }
}
