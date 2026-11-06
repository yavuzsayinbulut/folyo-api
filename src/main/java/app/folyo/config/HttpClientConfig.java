package app.folyo.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;

/** Dis saglayicilara giden her istek kisa zaman asimiyla sinirlanir; yavas bir saglayici sayfayi bekletmez. */
@Configuration
public class HttpClientConfig {

    @Bean
    RestClientCustomizer providerTimeouts() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        var factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(6));
        return builder -> builder.requestFactory(factory).defaultHeader("User-Agent", "folyo/0.1 (+https://folyo.app)");
    }
}
