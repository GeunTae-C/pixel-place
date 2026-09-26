package dev.cgt.benchmark;

import dev.cgt.pixelplace.PixelPlaceApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.web.context.support.StandardServletEnvironment;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.*;

/** OS 전역 설정을 시험 Spring Environment로 상속하지 않는 기동 전 자원 경계 */
final class BenchmarkEnvironment {
    private BenchmarkEnvironment() { }
    record Credentials(String dbHost, int dbPort, String dbUsername, String dbPassword,
                       String redisHost, int redisPort, int redisDatabase, String redisUsername, String redisPassword) {
        @Override public String toString() { return "BenchmarkCredentials[redacted]"; }
        String jdbc(String catalog) {
            return "jdbc:mysql://" + dbHost + ":" + dbPort + "/" + catalog
                    + "?serverTimezone=Asia/Seoul&characterEncoding=UTF-8&connectTimeout=15000&socketTimeout=15000";
        }
    }
    record Keys(String jwt, String cookie) {
        @Override public String toString() { return "TrialKeys[redacted]"; }
    }
    static Credentials credentials() {
        String host = env("DB_HOST", "127.0.0.1"), redisHost = env("REDIS_HOST", "127.0.0.1");
        BenchmarkSpec.require(host.matches("[a-zA-Z0-9.-]+"), "DB host");
        int dbPort = Integer.parseInt(env("DB_PORT", "3306")), redisPort = Integer.parseInt(env("REDIS_PORT", "6379"));
        int redisDb = Integer.parseInt(required("REDIS_DATABASE"));
        BenchmarkSpec.require(dbPort > 0 && dbPort <= 65535 && redisPort > 0 && redisPort <= 65535, "ports");
        BenchmarkGuards.redis(redisDb, Integer.parseInt(required("PRODUCTION_REDIS_DATABASE")), redisHost);
        return new Credentials(host, dbPort, required("DB_USERNAME"), required("DB_PASSWORD"),
                redisHost, redisPort, redisDb, env("REDIS_USERNAME", ""), env("REDIS_PASSWORD", ""));
    }
    static Keys keys() {
        byte[] first = new byte[32], second = new byte[32]; var random = new SecureRandom();
        random.nextBytes(first); do { random.nextBytes(second); } while (Arrays.equals(first, second));
        return new Keys(Base64.getEncoder().encodeToString(first), Base64.getEncoder().encodeToString(second));
    }
    static Map<String, Object> properties(Credentials credentials, String catalog, Path wal, Keys keys, boolean enabled) {
        var p = new LinkedHashMap<String, Object>();
        p.put("spring.config.location", "classpath:/phase15/application.yml");
        p.put("spring.config.import", ""); p.put("spring.config.additional-location", "");
        p.put("spring.profiles.active", "benchmark"); p.put("spring.profiles.include", "");
        p.put("spring.datasource.url", credentials.jdbc(catalog)); p.put("spring.datasource.username", credentials.dbUsername);
        p.put("spring.datasource.password", credentials.dbPassword);
        p.put("spring.data.redis.host", credentials.redisHost); p.put("spring.data.redis.port", credentials.redisPort);
        p.put("spring.data.redis.database", credentials.redisDatabase);
        if (!credentials.redisUsername.isBlank()) p.put("spring.data.redis.username", credentials.redisUsername);
        if (!credentials.redisPassword.isBlank()) p.put("spring.data.redis.password", credentials.redisPassword);
        p.put("pixel-place.wal.active-file", wal.toAbsolutePath().toString());
        p.put("pixel-place.wal.max-segment-bytes", 33554432);
        p.put("pixel-place.flush.fixed-delay", "1s"); p.put("pixel-place.measurement.enabled", enabled);
        p.put("pixel-place.auth.jwt-secret", keys.jwt); p.put("pixel-place.auth.oauth-cookie-secret", keys.cookie);
        p.put("server.address", "127.0.0.1"); p.put("server.port", 0);
        p.put("logging.level.root", "OFF"); p.put("spring.main.banner-mode", "off");
        return p;
    }
    static StandardServletEnvironment isolated(Map<String, Object> properties) {
        var environment = new StandardServletEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("guarded-benchmark", properties));
        environment.setActiveProfiles("benchmark");
        return environment;
    }
    static SpringApplication application(Map<String, Object> properties) {
        var app = new SpringApplication(PixelPlaceApplication.class, BenchmarkServletConfiguration.class);
        app.setEnvironment(isolated(properties)); app.setAddCommandLineProperties(false); app.setRegisterShutdownHook(false);
        app.addInitializers(context -> {
            var actual = context.getEnvironment();
            // bean 생성 전 다시 exact 비교. 환경의 오염을 기동 뒤에야 발견하는 경로 금지
            for (var entry : properties.entrySet()) {
                if (!Objects.equals(String.valueOf(entry.getValue()), actual.getProperty(entry.getKey())))
                    throw new IllegalStateException("Guarded benchmark property changed before bean creation");
            }
            if (!Arrays.equals(actual.getActiveProfiles(), new String[]{"benchmark"}))
                throw new IllegalStateException("Unexpected benchmark profiles");
            if (actual.getProperty("spring.data.redis.url") != null || actual.getProperty("spring.data.redis.cluster.nodes") != null
                    || actual.getProperty("spring.data.redis.sentinel.master") != null)
                throw new IllegalStateException("Unexpected alternate Redis connection settings");
        });
        return app;
    }
    private static String required(String suffix) {
        String value = System.getenv("PIXEL_PLACE_BENCH_" + suffix);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing PIXEL_PLACE_BENCH_" + suffix);
        return value;
    }
    private static String env(String suffix, String fallback) {
        String value = System.getenv("PIXEL_PLACE_BENCH_" + suffix); return value == null ? fallback : value;
    }
}
