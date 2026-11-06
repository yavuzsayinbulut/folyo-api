package app.folyo.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

/** Giris yontemi ne olursa olsun oturuma ayni FolyoPrincipal'i yazar. */
@Component
public class PrincipalSession {

    private final SecurityContextRepository repository = new HttpSessionSecurityContextRepository();

    public void signIn(User user, HttpServletRequest request, HttpServletResponse response) {
        // Session fixation'a karsi giriste oturum kimligi yenilenir.
        request.getSession(true);
        request.changeSessionId();

        var principal = new FolyoPrincipal(user.getId(), user.getName());
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        repository.saveContext(context, request, response);
    }
}
