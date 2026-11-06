package app.folyo.auth;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserRepository users;

    public UserService(UserRepository users) {
        this.users = users;
    }

    @Transactional
    public User signIn(String provider, String subject, String email, String name, String avatarUrl) {
        String safeName = truncate(name == null || name.isBlank() ? subject : name.strip(), 100);
        users.upsert(UUID.randomUUID(), provider, subject, email, safeName, avatarUrl);
        return users.findByAuthProviderAndAuthSubject(provider, subject).orElseThrow();
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
