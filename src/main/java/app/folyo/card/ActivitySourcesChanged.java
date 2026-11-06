package app.folyo.card;

import java.util.UUID;

/** Aktivite ureten bir kart eklendi, silindi ya da gorunurlugu degisti; akis yeniden senkronlanmali. */
public record ActivitySourcesChanged(UUID profileId) {
}
