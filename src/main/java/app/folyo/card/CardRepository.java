package app.folyo.card;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CardRepository extends JpaRepository<Card, UUID> {

    List<Card> findByProfileIdOrderByPosition(UUID profileId);

    List<Card> findByProfileIdAndVisibleTrueOrderByPosition(UUID profileId);

    Optional<Card> findByIdAndProfileId(UUID id, UUID profileId);

    long countByProfileId(UUID profileId);

    List<Card> findByProfileIdAndVisibleTrueAndTypeIn(UUID profileId, Collection<CardType> types);

    @Query("select distinct c.profileId from Card c where c.visible = true and c.type in :types")
    List<UUID> findProfileIdsWithVisibleTypes(Collection<CardType> types);

    @Query("select coalesce(max(c.position), -1) from Card c where c.profileId = :profileId")
    int findMaxPosition(UUID profileId);
}
