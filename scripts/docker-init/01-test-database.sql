-- Runs once when the Docker volume is first created: the integration tests use a separate
-- database (its name must end with _test, see TestConfig).
CREATE DATABASE queueless_test OWNER queueless;
