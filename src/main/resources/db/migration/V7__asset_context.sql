CREATE TABLE asset_replica (
  asset_id BIGINT NOT NULL PRIMARY KEY,
  organization_id BIGINT NOT NULL,
  name VARCHAR(200) NOT NULL,
  location_name VARCHAR(200) NULL,
  updated_at DATETIME(6) NOT NULL
);
