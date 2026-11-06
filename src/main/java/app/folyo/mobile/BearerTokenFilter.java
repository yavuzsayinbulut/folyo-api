package app.folyo.mobile;

import app.folyo.auth.FolyoPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * "Authorization: Bearer ..." ile gelen mobil istekleri dogrular. Oturum (cookie) acmaz; her istek kendi token'iyla
 * gelir. Gecersiz/suresi dolmus token 401 doner, istek anonim olarak devam etmez.
 */
public class BearerTokenFilter extends OncePerRequestFilter {

    public static final String TOKEN_ID_ATTRIBUTE = "folyo.apiTokenId";

    private final MobileAuthService mobileAuth;

    public BearerTokenFilter(MobileAuthService mobileAuth) {
        this.mobileAuth = mobileAuth;
    }

    public static boolean hasBearer(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        return header != null && header.regionMatches(true, 0, "Bearer ", 0, 7);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!hasBearer(request)) {
            chain.doFilter(request, response);
            return;
        }
        String token = request.getHeader(HttpHeaders.AUTHORIZATION).substring(7).strip();
        var active = mobileAuth.authenticate(token);
        if (active.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"");
            return;
        }
        var principal = new FolyoPrincipal(active.get().userId(), active.get().userName());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        SecurityContextHolder.setContext(context);
        request.setAttribute(TOKEN_ID_ATTRIBUTE, active.get().tokenId());
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
