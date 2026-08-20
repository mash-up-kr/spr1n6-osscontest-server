package com.osscontest.server.config

import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class DataSourceConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration::class.java))
        .withPropertyValues(
            "spring.datasource.url=jdbc:postgresql://localhost/test",
            "spring.datasource.username=test",
            "spring.datasource.password=test",
            "spring.datasource.hikari.data-source-properties.options=-c app.encryption_key=test-key",
        )

    @Test
    fun `DB 암호화 키를 pgJDBC 연결 옵션으로 전달한다`() {
        contextRunner.run { context ->
            val dataSource = context.getBean(HikariDataSource::class.java)

            assertEquals(
                "-c app.encryption_key=test-key",
                dataSource.dataSourceProperties.getProperty("options"),
            )
        }
    }
}
