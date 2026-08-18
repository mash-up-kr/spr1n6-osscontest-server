package com.osscontest.server.common.storage

import org.springframework.stereotype.Component
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.io.InputStream

@Component
class S3ObjectStorage(
    private val s3: S3Client,
    private val properties: StorageProperties,
) : ObjectStorage {

    override fun put(key: String, content: InputStream, contentLength: Long, contentType: String) {
        s3.putObject(
            PutObjectRequest.builder()
                .bucket(properties.bucket)
                .key(key)
                .contentType(contentType)
                .build(),
            RequestBody.fromInputStream(content, contentLength),
        )
    }

    override fun get(key: String): InputStream =
        s3.getObject(
            GetObjectRequest.builder()
                .bucket(properties.bucket)
                .key(key)
                .build(),
        )
}
