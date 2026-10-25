-- auto: car diagnostics data (MariaDB)
CREATE TABLE IF NOT EXISTS trip (
  uuid             CHAR(36)     NOT NULL PRIMARY KEY,
  device           VARCHAR(64)  NOT NULL,
  started_at       DOUBLE       NOT NULL,            -- unix seconds
  ended_at         DOUBLE       NULL,
  duration_s       DOUBLE       NULL,
  distance_km      DOUBLE       NULL,
  fuel_l           DOUBLE       NULL,
  summary          JSON         NULL,
  analysis_version INT          NULL,
  updated_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY started_at (started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS sample (
  trip_uuid   CHAR(36)  NOT NULL,
  seq         INT       NOT NULL,
  t           DOUBLE    NOT NULL,                    -- unix seconds
  lat         DOUBLE    NULL,
  lon         DOUBLE    NULL,
  gps_speed   FLOAT     NULL,                        -- km/h
  alt         FLOAT     NULL,
  accuracy    FLOAT     NULL,
  speed       SMALLINT  NULL,
  rpm         SMALLINT  NULL,
  maf         FLOAT     NULL,
  throttle    FLOAT     NULL,
  coolant     SMALLINT  NULL,
  engine_load FLOAT     NULL,
  stft        FLOAT     NULL,
  ltft        FLOAT     NULL,
  fuelsys     TINYINT   NULL,
  iat         SMALLINT  NULL,
  timing      FLOAT     NULL,
  o2_down     FLOAT     NULL,
  volts       FLOAT     NULL,
  fuel_lph    FLOAT     NULL,
  lambda      FLOAT     NULL,                        -- front wideband O2 (PID 0134)
  PRIMARY KEY (trip_uuid, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS dtc_snapshot (
  trip_uuid CHAR(36) NOT NULL,
  t         DOUBLE   NOT NULL,
  mil       TINYINT  NULL,
  stored    JSON     NULL,
  pending   JSON     NULL,
  freeze    JSON     NULL,
  PRIMARY KEY (trip_uuid, t)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS app_log (
  device    VARCHAR(64)  NOT NULL,
  client_id BIGINT       NOT NULL,                   -- row id on the phone, makes uploads idempotent
  t         DOUBLE       NOT NULL,
  level     CHAR(1)      NOT NULL,
  msg       TEXT         NOT NULL,
  PRIMARY KEY (device, client_id),
  KEY t (t)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
