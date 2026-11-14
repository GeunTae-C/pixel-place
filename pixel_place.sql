-- 데이터베이스 생성
CREATE DATABASE IF NOT EXISTS `pixel_place`
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

-- 생성된 데이터베이스 사용
USE `pixel_place`;

-- 타일 최신 스냅샷
CREATE TABLE IF NOT EXISTS tiles (
    z TINYINT UNSIGNED NOT NULL,
    tx TINYINT UNSIGNED NOT NULL,
    ty TINYINT UNSIGNED NOT NULL,
    data MEDIUMBLOB NOT NULL,
    tile_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    updated_at DATETIME(3) NOT NULL
        DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (z, tx, ty),
    KEY idx_tiles_updated_at (updated_at)
) ENGINE=InnoDB;


-- WAL에서 DB로 반영된 픽셀 이벤트
CREATE TABLE IF NOT EXISTS pixel_events (
    event_seq BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,

    z TINYINT UNSIGNED NOT NULL,
    tx TINYINT UNSIGNED NOT NULL,
    ty TINYINT UNSIGNED NOT NULL,

    x SMALLINT UNSIGNED NOT NULL,
    y SMALLINT UNSIGNED NOT NULL,
    color SMALLINT UNSIGNED NOT NULL,

    created_at DATETIME(3) NOT NULL,

    PRIMARY KEY (event_seq),
    KEY idx_pixel_events_user_id (user_id)
) ENGINE=InnoDB;


-- DB에 반영 완료된 WAL 경계
CREATE TABLE IF NOT EXISTS wal_checkpoint (
    checkpoint_name VARCHAR(64) NOT NULL,
    last_flushed_event_seq BIGINT UNSIGNED NOT NULL,
    updated_at DATETIME(3) NOT NULL
        DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (checkpoint_name)
) ENGINE=InnoDB;


-- main checkpoint는 반드시 존재해야 한다.
-- 스크립트를 다시 실행해도 기존 checkpoint 값을 0으로 덮어쓰지 않는다.
INSERT INTO wal_checkpoint (
    checkpoint_name,
    last_flushed_event_seq
)
VALUES (
    'main',
    0
)
ON DUPLICATE KEY UPDATE
    checkpoint_name = checkpoint_name;


-- 카카오 식별자와 내부 사용자 ID의 최소 저장소. 기존 event/WAL에는 FK 추가하지 않음
CREATE TABLE IF NOT EXISTS users (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    kakao_user_id BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_kakao_user_id (kakao_user_id)
) ENGINE=InnoDB;

-- 확인용
SELECT * FROM tiles;
SELECT * FROM pixel_events;
SELECT * FROM wal_checkpoint;
