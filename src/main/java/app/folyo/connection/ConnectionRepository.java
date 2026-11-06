package app.folyo.connection;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface ConnectionRepository extends JpaRepository<Connection, UUID> {

    Optional<Connection> findByProfileIdAndProvider(UUID profileId, ConnectionProvider provider);

    List<Connection> findByProfileId(UUID profileId);

    /** Ayni baglantiyi iki istek ayni anda yenilemesin diye satir kilitlenir. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Connection c where c.id = :id")
    Optional<Connection> findByIdForUpdate(UUID id);
}
