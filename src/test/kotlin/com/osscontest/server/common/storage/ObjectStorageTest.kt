package com.osscontest.server.common.storage

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import java.util.UUID
import kotlin.test.assertContentEquals

/** 실제 MinIO 에 올리고 내려받는다. docker compose 가 떠 있어야 한다. */
@SpringBootTest
class ObjectStorageTest {

    @Autowired
    private lateinit var storage: ObjectStorage

    @Autowired
    private lateinit var s3: S3Client

    @Autowired
    private lateinit var properties: StorageProperties

    @Test
    fun `올린 파일을 그대로 내려받는다`() {
        val key = "test/${UUID.randomUUID()}.txt"
        val body = "사업계획서 본문".toByteArray()

        try {
            storage.put(key, body.inputStream(), body.size.toLong(), "text/plain")
            val downloaded = storage.get(key).use { it.readBytes() }

            assertContentEquals(body, downloaded)
        } finally {
            s3.deleteObject(
                DeleteObjectRequest.builder().bucket(properties.bucket).key(key).build(),
            )
        }
    }
}
