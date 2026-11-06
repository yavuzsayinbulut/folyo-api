package app.folyo;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

	private static final String TOKEN_KEY_PROPERTY = "folyo.token-key";

	static {
		if (System.getProperty(TOKEN_KEY_PROPERTY) == null && System.getenv("FOLYO_TOKEN_KEY") == null) {
			byte[] key = new byte[32];
			new SecureRandom().nextBytes(key);
			System.setProperty(TOKEN_KEY_PROPERTY, Base64.getEncoder().encodeToString(key));
		}
	}

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));
	}

}
