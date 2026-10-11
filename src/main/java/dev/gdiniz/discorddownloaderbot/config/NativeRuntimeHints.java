package dev.gdiniz.discorddownloaderbot.config;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

public class NativeRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        hints.resources().registerPattern("db/migration/*");
        hints.resources().registerPattern("db/migration/*.sql");

        registerTypeIfPresent(hints, classLoader, "org.flywaydb.core.Flyway");
        registerTypeIfPresent(hints, classLoader, "org.flywaydb.core.api.migration.JavaMigration");
        registerTypeIfPresent(hints, classLoader, "org.flywaydb.core.internal.database.postgresql.PostgreSQLDatabaseType");
        registerTypeIfPresent(hints, classLoader, "org.flywaydb.database.postgresql.PostgreSQLDatabaseType");
        registerTypeIfPresent(hints, classLoader, "org.flywaydb.core.internal.plugin.PluginRegister");
        registerTypeIfPresent(hints, classLoader, "org.flywaydb.core.internal.logging.slf4j.Slf4jLogCreator");

        registerTypeIfPresent(hints, classLoader, "org.postgresql.Driver");
        registerTypeIfPresent(hints, classLoader, "org.postgresql.PGProperty");
        registerTypeIfPresent(hints, classLoader, "com.zaxxer.hikari.HikariConfig");
        registerTypeIfPresent(hints, classLoader, "com.zaxxer.hikari.HikariDataSource");
    }

    private void registerTypeIfPresent(RuntimeHints hints, ClassLoader classLoader, String className) {
        try {
            Class<?> clazz = Class.forName(className, false, classLoader);
            hints.reflection().registerType(clazz, MemberCategory.values());
        } catch (ClassNotFoundException ignored) {
        }
    }
}
