package app.folyo.auth;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final ObjectProvider<ClientRegistrationRepository> clientRegistrations;
    private final Environment environment;

    public AuthController(ObjectProvider<ClientRegistrationRepository> clientRegistrations, Environment environment) {
        this.clientRegistrations = clientRegistrations;
        this.environment = environment;
    }

    /** Web'in giris sayfasinda hangi butonlarin gosterilecegini soyler. */
    @GetMapping("/providers")
    public List<String> providers() {
        List<String> providers = new ArrayList<>();
        if (clientRegistrations.getIfAvailable() instanceof InMemoryClientRegistrationRepository registrations) {
            registrations.forEach(registration -> providers.add(registration.getRegistrationId()));
        }
        if (environment.acceptsProfiles(Profiles.of("dev"))) {
            providers.add("dev");
        }
        return providers;
    }

    /** Token'a erismek XSRF-TOKEN cookie'sinin yazilmasini saglar; web ilk degistirici istekten once cagirir. */
    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }
}
