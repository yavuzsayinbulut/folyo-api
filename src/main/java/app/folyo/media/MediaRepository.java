package app.folyo.media;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface MediaRepository extends JpaRepository<Media, UUID> {

    List<Media> findByCardIdInOrderByPosition(Collection<UUID> cardIds);

    long countByCardId(UUID cardId);

    @Query("select coalesce(max(m.position), -1) from Media m where m.cardId = :cardId")
    int findMaxPosition(UUID cardId);

    @Query("select m.storageKey from Media m where m.cardId = :cardId")
    List<String> findStorageKeys(UUID cardId);
}
