package com.valerin.venderchest.config;

import io.lettuce.core.RedisURI;
import org.bukkit.configuration.ConfigurationSection;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class CrossServerSettings {

    private static final String SAFE_ID = "[A-Za-z0-9._-]{1,64}";
    private static final String SAFE_PREFIX = "[A-Za-z0-9_]+";

    private final boolean enabled;
    private final String databaseType;
    private final String tablePrefix;
    private final String networkNamespace;
    private final String serverId;
    private final RedisEndpoint redis;
    private final long ttlMillis;
    private final long renewMillis;
    private final long safetyMillis;
    private final int dbLeaseSeconds;

    private CrossServerSettings(
            boolean enabled,
            String databaseType,
            String tablePrefix,
            String networkNamespace,
            String serverId,
            RedisEndpoint redis,
            long ttlMillis,
            long renewMillis,
            long safetyMillis,
            int dbLeaseSeconds
    ) {
        this.enabled = enabled;
        this.databaseType = databaseType;
        this.tablePrefix = tablePrefix;
        this.networkNamespace = networkNamespace;
        this.serverId = serverId;
        this.redis = redis;
        this.ttlMillis = ttlMillis;
        this.renewMillis = renewMillis;
        this.safetyMillis = safetyMillis;
        this.dbLeaseSeconds = dbLeaseSeconds;
    }

    public static Validation parse(ConfigurationSection config) {
        List<String> errors = new ArrayList<>();
        boolean enabled = config.getBoolean("cross-server.enabled", false);
        String databaseType = text(config, "database.type", "sqlite").toLowerCase(Locale.ROOT);
        String tablePrefix = text(config, "database.table-prefix", "ec_");
        String network = text(config, "cross-server.network", "default");
        String serverId = text(config, "cross-server.server-id", "server-1");
        String host = text(config, "cross-server.redis.host", "localhost");
        int port = config.getInt("cross-server.redis.port", 6379);
        String username = text(config, "cross-server.redis.username", "");
        String password = config.getString("cross-server.redis.password", "");
        boolean tls = config.getBoolean("cross-server.redis.tls", false);
        int database = config.getInt("cross-server.redis.database", 0);
        long timeout = config.getLong("cross-server.redis.timeout-ms", 1_500);
        long ttl = config.getLong("cross-server.lease.ttl-ms", 30_000);
        long renew = config.getLong("cross-server.lease.renew-ms", 5_000);
        long safety = config.getLong("cross-server.lease.safety-ms", 10_000);
        int dbLease = config.getInt("cross-server.lease.mysql-seconds", 45);

        if (!tablePrefix.matches(SAFE_PREFIX)) errors.add("database.table-prefix must contain only letters, numbers, or underscore");
        if (enabled && !"mysql".equals(databaseType)) errors.add("cross-server.enabled requires database.type=mysql");
        if (enabled && !network.matches(SAFE_ID)) errors.add("cross-server.network is invalid");
        if (enabled && !serverId.matches(SAFE_ID)) errors.add("cross-server.server-id is invalid");
        if (enabled && (host.isBlank() || host.chars().anyMatch(Character::isWhitespace))) {
            errors.add("cross-server.redis.host is invalid");
        }
        if (enabled && (port < 1 || port > 65_535)) errors.add("cross-server.redis.port is invalid");
        if (enabled && (database < 0 || database > 65_535)) errors.add("cross-server.redis.database is invalid");
        if (enabled && (timeout < 100 || timeout >= ttl)) errors.add("cross-server.redis.timeout-ms must be >=100 and below TTL");
        if (enabled && (ttl < 3_000 || safety < 1_000 || safety >= ttl)) errors.add("cross-server lease TTL/safety is invalid");
        if (enabled && (renew < 250 || renew >= ttl - safety)) errors.add("cross-server.lease.renew-ms is outside the safe window");
        if (enabled && dbLease * 1_000L <= ttl) errors.add("cross-server MySQL lease must be longer than Redis TTL");

        RedisEndpoint redis = new RedisEndpoint(host, port, username, password == null ? "" : password,
                tls, database, timeout);
        CrossServerSettings settings = new CrossServerSettings(enabled, databaseType, tablePrefix,
                network, serverId, redis, ttl, renew, safety, dbLease);
        return errors.isEmpty() ? Validation.valid(settings) : Validation.invalid(errors);
    }

    private static String text(ConfigurationSection config, String path, String fallback) {
        String value = config.getString(path, fallback);
        return value == null ? fallback : value.trim();
    }

    public boolean enabled() { return enabled; }
    public String databaseType() { return databaseType; }
    public String tablePrefix() { return tablePrefix; }
    public String networkNamespace() { return networkNamespace; }
    public String serverId() { return serverId; }
    public RedisEndpoint redis() { return redis; }
    public long ttlMillis() { return ttlMillis; }
    public long renewMillis() { return renewMillis; }
    public long safetyMillis() { return safetyMillis; }
    public int dbLeaseSeconds() { return dbLeaseSeconds; }

    public String redisKeyPrefix() {
        return "venderchest:" + networkNamespace + ":";
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CrossServerSettings that)) return false;
        return enabled == that.enabled && ttlMillis == that.ttlMillis && renewMillis == that.renewMillis
                && safetyMillis == that.safetyMillis && dbLeaseSeconds == that.dbLeaseSeconds
                && databaseType.equals(that.databaseType) && tablePrefix.equals(that.tablePrefix)
                && networkNamespace.equals(that.networkNamespace) && serverId.equals(that.serverId)
                && redis.equals(that.redis);
    }

    @Override
    public int hashCode() {
        return Objects.hash(enabled, databaseType, tablePrefix, networkNamespace, serverId, redis,
                ttlMillis, renewMillis, safetyMillis, dbLeaseSeconds);
    }

    @Override
    public String toString() {
        return "CrossServerSettings[enabled=" + enabled + ", databaseType=" + databaseType
                + ", tablePrefix=" + tablePrefix + ", network=" + networkNamespace
                + ", serverId=" + serverId + ", redis=" + redis + "]";
    }

    public static final class RedisEndpoint {
        private final String host;
        private final int port;
        private final String username;
        private final String password;
        private final boolean tls;
        private final int database;
        private final long timeoutMillis;

        private RedisEndpoint(String host, int port, String username, String password,
                              boolean tls, int database, long timeoutMillis) {
            this.host = host;
            this.port = port;
            this.username = username;
            this.password = password;
            this.tls = tls;
            this.database = database;
            this.timeoutMillis = timeoutMillis;
        }

        public RedisURI toRedisUri() {
            RedisURI.Builder builder = RedisURI.builder()
                    .withHost(host)
                    .withPort(port)
                    .withSsl(tls)
                    .withDatabase(database)
                    .withTimeout(Duration.ofMillis(timeoutMillis));
            if (!username.isBlank()) builder.withAuthentication(username, password.toCharArray());
            else if (!password.isBlank()) builder.withPassword(password.toCharArray());
            return builder.build();
        }

        public long timeoutMillis() { return timeoutMillis; }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof RedisEndpoint that)) return false;
            return port == that.port && tls == that.tls && database == that.database
                    && timeoutMillis == that.timeoutMillis && host.equals(that.host)
                    && username.equals(that.username) && password.equals(that.password);
        }

        @Override public int hashCode() {
            return Objects.hash(host, port, username, password, tls, database, timeoutMillis);
        }

        @Override public String toString() {
            return "RedisEndpoint[host=" + host + ", port=" + port + ", username="
                    + (username.isBlank() ? "<none>" : "<set>") + ", password=<redacted>, tls="
                    + tls + ", database=" + database + ", timeoutMillis=" + timeoutMillis + "]";
        }
    }

    public record Validation(CrossServerSettings settings, List<String> errors) {
        public Validation {
            errors = List.copyOf(errors);
        }
        static Validation valid(CrossServerSettings settings) { return new Validation(settings, List.of()); }
        static Validation invalid(List<String> errors) { return new Validation(null, errors); }
        public boolean isValid() { return settings != null; }
    }
}
