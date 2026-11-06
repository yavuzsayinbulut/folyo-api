package app.folyo.provider;

import java.time.Instant;
import java.util.List;

/** Canli kartlarin ziyaretciye donen verisi. Her kart tipi kendi kaydini kullanir. */
public sealed interface LiveData {

    record GitHub(String login, String name, String avatarUrl, String url, long followers, long publicRepos,
            int activeDays, List<Integer> contributionLevels, List<Repo> topRepos) implements LiveData {
    }

    record Repo(String name, String url, long stars, String language) {
    }

    record Reddit(String name, String url, long totalKarma, Instant createdAt, String iconUrl) implements LiveData {
    }

    /** Spotify ve Last.fm ayni bicimi kullanir; web tek bir "su an calan" karti cizer. */
    record Track(String source, boolean nowPlaying, String title, String artist, String imageUrl, String url,
            Instant playedAt, String profileUrl) implements LiveData {
    }

    record Strava(String athleteName, String profileUrl, Activity lastActivity, double weekDistanceMeters,
            int weekActivities) implements LiveData {
    }

    record Activity(String name, String sportType, double distanceMeters, long movingSeconds, Instant startDate,
            String url) {
    }
}
