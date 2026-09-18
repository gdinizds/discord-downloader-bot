package dev.gdiniz.discorddownloaderbot.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface GuildConfigRepository extends JpaRepository<GuildConfig, String> {}
