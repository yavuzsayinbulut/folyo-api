package app.folyo.profile;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface ProfileRepository extends JpaRepository<Profile, UUID> {

    Optional<Profile> findByUserId(UUID userId);

    Optional<Profile> findByUsername(String username);

    boolean existsByUsername(String username);

    boolean existsByUserId(UUID userId);

    /** Kart ekleme/siralama gibi islemler ayni sayfa uzerinde sirayla calissin diye profil satiri kilitlenir. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Profile p where p.userId = :userId")
    Optional<Profile> findByUserIdForUpdate(UUID userId);
}
