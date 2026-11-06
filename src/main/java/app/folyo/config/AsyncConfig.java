package app.folyo.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Aktivite senkronu: periyodik is + kart degisikliginden sonra arka planda tetikleme (sanal thread'ler). */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {
}
