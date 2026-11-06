CREATE TABLE IF NOT EXISTS workbench_receipt (
    receiver_type VARCHAR(16) NOT NULL,
    receiver_id BIGINT NOT NULL,
    task_kind VARCHAR(32) NOT NULL,
    task_id VARCHAR(64) NOT NULL,
    task_version VARCHAR(96) NOT NULL,
    read_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    acknowledged_at DATETIME NULL,
    PRIMARY KEY (receiver_type, receiver_id, task_kind, task_id, task_version)
);

CREATE TABLE IF NOT EXISTS staff_notification_receipt (
    receiver_id BIGINT NOT NULL,
    notification_id BIGINT NOT NULL,
    read_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (receiver_id, notification_id)
);
