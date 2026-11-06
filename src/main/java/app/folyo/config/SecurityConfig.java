package app.folyo.config;

import app.folyo.auth.OAuthLoginSuccessHandler;
import app.folyo.mobile.BearerTokenFilter;
import app.folyo.mobile.MobileAuthService;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableConfigurationProperties(FolyoProperties.class)
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
            ObjectProvider<ClientRegistrationRepository> clientRegistrations,
            OAuthLoginSuccessHandler oauthLoginSuccessHandler, MobileAuthService mobileAuth) throws Exception {
        http
                .cors(cors -> {
                })
                // Mobil uygulama: Authorization: Bearer ile gelir, oturum acmaz.
                .addFilterBefore(new BearerTokenFilter(mobileAuth), AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/me/**").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/profiles/*/guestbook").authenticated()
                        .anyRequest().permitAll())
                // Web ayni origin'den (Next.js proxy) gelir; XSRF-TOKEN cookie + X-XSRF-TOKEN header.
                // /api/public/** altindaki POST'lar (goruntulenme/tiklama sayaci) kullanici adina islem yapmaz ve
                // sendBeacon header gonderemez; CSRF'ten muaftir.
                // Bearer token'li istekler cerez tasimaz, CSRF'e acik degildir; /api/mobile/token da oturumsuz cagrilir.
                .csrf(csrf -> csrf.spa()
                        .ignoringRequestMatchers("/api/public/**", "/api/mobile/**")
                        .ignoringRequestMatchers(BearerTokenFilter::hasBearer))
                // API'de giris sayfasina yonlendirme yok; giris gerekiyorsa 401 doner.
                .exceptionHandling(ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler()));

        // Google/GitHub istemci bilgileri ortam degiskenleriyle verilmisse OAuth girisi acilir.
        if (clientRegistrations.getIfAvailable() != null) {
            http.oauth2Login(oauth -> oauth.successHandler(oauthLoginSuccessHandler));
        }
        return http.build();
    }

    /**
     * Yalniz tarayicida calisan gelistirme istemcileri (Expo web) icin. Native uygulama CORS'a tabi degildir;
     * web istemcisi ayni origin'den (Next.js proxy) geldigi icin listede olmasina gerek yoktur.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${folyo.cors.allowed-origins:}") List<String> allowedOrigins) {
        var config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins.stream().filter(o -> !o.isBlank()).toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setAllowCredentials(false);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
