
CREATE TABLE IF NOT EXISTS analyzed_beans (
                                              id BIGSERIAL PRIMARY KEY,
                                              class_name VARCHAR(255) NOT NULL UNIQUE
    );

CREATE TABLE IF NOT EXISTS bean_dependencies (
                                                 id BIGSERIAL PRIMARY KEY,
                                                 source_bean VARCHAR(255) NOT NULL,
    target_bean VARCHAR(255) NOT NULL,
    UNIQUE (source_bean, target_bean)
    );

CREATE TABLE IF NOT EXISTS circular_dependencies (
                                                     id BIGSERIAL PRIMARY KEY,
                                                     cycle_path TEXT NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS rest_endpoints (
                                              id BIGSERIAL PRIMARY KEY,
                                              controller_name VARCHAR(255) NOT NULL,
    http_method VARCHAR(20) NOT NULL,
    endpoint_path TEXT NOT NULL
    );
