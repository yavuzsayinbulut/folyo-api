package app.folyo.card;

import java.util.EnumSet;
import java.util.Set;

public enum CardType {
    LINK,
    TEXT,
    /** Herkese acik veriden beslenen canli kartlar; config'te kullanici adi tutulur. */
    GITHUB,
    REDDIT,
    LASTFM,
    /** Kullanicinin hesap baglamasini (OAuth) gerektiren canli kartlar. */
    SPOTIFY,
    STRAVA,
    GALLERY;

    public static final Set<CardType> LIVE = EnumSet.of(GITHUB, REDDIT, LASTFM, SPOTIFY, STRAVA);

    /** Aktivite akisina olay ureten kart tipleri. */
    public static final Set<CardType> ACTIVITY_SOURCES = EnumSet.of(GITHUB, REDDIT, STRAVA);

    public boolean isLive() {
        return LIVE.contains(this);
    }
}
