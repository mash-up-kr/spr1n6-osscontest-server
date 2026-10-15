/*
 * Copyright (c) 2026 매쉬업스프링YB
 * SPDX-License-Identifier: MIT
 */

package com.osscontest.server.config

import com.zaxxer.hikari.HikariDataSource
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties
import org.springframework.boot.jdbc.health.DataSourceHealthIndicator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.PlatformTransactionManager
import javax.sql.DataSource

/**
 * 기본 연결(Primary)과 검색 연결을 나눈다.
 *
 * `search.datasource.url` 이 있으면 검색은 별도 풀(OpenProxy `aidocs_ro`, Replica)을 쓰고,
 * 없으면 기본 연결과 트랜잭션 매니저를 그대로 함께 쓴다.
 */
@Configuration
class DataSourceConfig {

    // 검색 연결이 두 번째 DataSource 라 Boot 자동 구성이 물러나므로 기본 연결을 직접 정의한다.
    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    fun dataSourceProperties(): DataSourceProperties = DataSourceProperties()

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    fun dataSource(properties: DataSourceProperties): HikariDataSource =
        properties.initializeDataSourceBuilder().type(HikariDataSource::class.java).build()

    @Bean
    @Primary
    fun namedParameterJdbcTemplate(jdbcTemplate: JdbcTemplate): NamedParameterJdbcTemplate =
        NamedParameterJdbcTemplate(jdbcTemplate)

    @Bean
    @ConditionalOnProperty(prefix = "search.datasource", name = ["url"])
    @ConfigurationProperties("search.datasource")
    fun searchDataSourceProperties(): DataSourceProperties = DataSourceProperties()

    @Bean
    @ConditionalOnProperty(prefix = "search.datasource", name = ["url"])
    @ConfigurationProperties("search.datasource.hikari")
    fun searchDataSource(
        @Qualifier("searchDataSourceProperties") properties: DataSourceProperties,
    ): HikariDataSource =
        properties.initializeDataSourceBuilder().type(HikariDataSource::class.java).build()

    @Bean
    fun searchJdbcTemplate(
        @Qualifier("searchDataSource") searchDataSource: ObjectProvider<DataSource>,
        dataSource: DataSource,
    ): NamedParameterJdbcTemplate = NamedParameterJdbcTemplate(searchDataSource.getIfAvailable { dataSource })

    @Bean
    fun searchTransactionManager(
        @Qualifier("searchDataSource") searchDataSource: ObjectProvider<DataSource>,
        @Qualifier("transactionManager") transactionManager: PlatformTransactionManager,
    ): PlatformTransactionManager =
        searchDataSource.ifAvailable?.let { DataSourceTransactionManager(it) }
            ?: SharedTransactionManager(transactionManager)

    // 서버 상태(db)는 기본 연결로만 본다. 검색 풀까지 넣으면 Replica 가 모두 죽었을 때 서버 전체가 DOWN 이 된다.
    @Bean
    fun dbHealthIndicator(dataSource: DataSource): DataSourceHealthIndicator = DataSourceHealthIndicator(dataSource)

    // 같은 트랜잭션 매니저 인스턴스를 다른 이름으로 다시 등록하면 초기화 콜백이 한 번 더 돌아서 감싸서 넘긴다.
    private class SharedTransactionManager(
        delegate: PlatformTransactionManager,
    ) : PlatformTransactionManager by delegate
}
