
package com.springscope;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class AnalysisRepository {

    private final JdbcTemplate jdbcTemplate;

    public AnalysisRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public void save(SourceAnalyzer.AnalysisResult result) {

        // Clear the previous report, keeping the latest analysis.
        jdbcTemplate.update("DELETE FROM bean_dependencies");
        jdbcTemplate.update("DELETE FROM circular_dependencies");
        jdbcTemplate.update("DELETE FROM rest_endpoints");
        jdbcTemplate.update("DELETE FROM analyzed_beans");

        for (String bean : result.beans()) {
            jdbcTemplate.update(
                    "INSERT INTO analyzed_beans (class_name) VALUES (?)",
                    bean
            );
        }

        for (SourceAnalyzer.Dependency dependency :
                result.dependencies()) {

            jdbcTemplate.update(
                    """
                    INSERT INTO bean_dependencies
                        (source_bean, target_bean)
                    VALUES (?, ?)
                    """,
                    dependency.source(),
                    dependency.target()
            );
        }

        for (String cycle : result.cycles()) {
            jdbcTemplate.update(
                    """
                    INSERT INTO circular_dependencies (cycle_path)
                    VALUES (?)
                    """,
                    cycle
            );
        }

        for (SourceAnalyzer.Endpoint endpoint : result.endpoints()) {
            jdbcTemplate.update(
                    """
                    INSERT INTO rest_endpoints
                        (controller_name, http_method, endpoint_path)
                    VALUES (?, ?, ?)
                    """,
                    endpoint.controller(),
                    endpoint.httpMethod(),
                    endpoint.path()
            );
        }
    }
}
