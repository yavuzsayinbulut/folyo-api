package app.folyo.auth;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByAuthProviderAndAuthSubject(String authProvider, String authSubject);

    /**
     * Ayni kimlikle es zamanli ilk giriste iki satir olusmasin diye insert ve guncelleme tek ifadede yapilir.
     */
    @Modifying
    @Query(nativeQuery = true, value = """
            insert into users (id, auth_provider, auth_subject, email, name, avatar_url)
            values (:id, :provider, :subject, :email, :name, :avatarUrl)
            on conflict (auth_provider, auth_subject)
            do update set email = excluded.email, name = excluded.name, avatar_url = excluded.avatar_url
            """)
    void upsert(UUID id, String provider, String subject, String email, String name, String avatarUrl);
}
