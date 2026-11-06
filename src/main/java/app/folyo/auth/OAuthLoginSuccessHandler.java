package app.folyo.auth;

import app.folyo.config.FolyoProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/** Google/GitHub girisinden sonra kullaniciyi users tablosuna yazar ve oturumu FolyoPrincipal'e cevirir. */
@Component
public class OAuthLoginSuccessHandler implements AuthenticationSuccessHandler {

    private final UserService userService;
    private final PrincipalSession principalSession;
    private final FolyoProperties properties;

    public OAuthLoginSuccessHandler(UserService userService, PrincipalSession principalSession,
            FolyoProperties properties) {
        this.userService = userService;
        this.principalSession = principalSession;
        this.properties = properties;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException {
        var token = (OAuth2AuthenticationToken) authentication;
        String provider = token.getAuthorizedClientRegistrationId();
        Map<String, Object> attributes = token.getPrincipal().getAttributes();

        User user = switch (provider) {
            case "google" -> userService.signIn(provider, text(attributes, "sub"), text(attributes, "email"),
                    text(attributes, "name"), text(attributes, "picture"));
            case "github" -> userService.signIn(provider, text(attributes, "id"), text(attributes, "email"),
                    firstPresent(text(attributes, "name"), text(attributes, "login")), text(attributes, "avatar_url"));
            default -> userService.signIn(provider, token.getName(), null, token.getName(), null);
        };

        principalSession.signIn(user, request, response);
        response.sendRedirect(properties.webUrl() + "/edit");
    }

    private static String text(Map<String, Object> attributes, String key) {
        Object value = attributes.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static String firstPresent(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }
}
