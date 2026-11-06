package app.folyo.provider;

import app.folyo.config.FolyoProperties;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * GitHub'in herkese acik verisi. Token opsiyoneldir (FOLYO_GITHUB_TOKEN): yoksa IP basina saatte 60 istek siniri vardir.
 * Katki grafigi GitHub'in herkese acik katki sayfasindan okunur; bicim degisirse grafik bos doner, kart calismaya devam eder.
 */
@Component
public class GitHubClient {

    static final String API = "https://api.github.com";
    static final String WEB = "https://github.com";
    static final int HEATMAP_WEEKS = 26;

    private static final Pattern DAY_CELL = Pattern.compile("<td[^>]*\\bdata-date=\"(\\d{4}-\\d{2}-\\d{2})\"[^>]*>");
    private static final Pattern LEVEL = Pattern.compile("\\bdata-level=\"(\\d)\"");

    private final RestClient http;
    /** github.com sayfalari icin ayri istemci: API token'i web sayfasina gonderilmez. */
    private final RestClient web;

    public GitHubClient(RestClient.Builder builder, FolyoProperties properties) {
        this.web = builder.clone().build();
        builder.defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28");
        String token = properties.githubToken();
        if (token != null && !token.isBlank()) {
            builder.defaultHeader("Authorization", "Bearer " + token);
        }
        this.http = builder.build();
    }

    public LiveData.GitHub fetch(String username) {
        JsonNode user = getJson(API + "/users/{username}", username);
        JsonNode repos = getJson(API + "/users/{username}/repos?sort=pushed&per_page=100", username);

        List<LiveData.Repo> topRepos = new ArrayList<>();
        for (JsonNode repo : repos) {
            if (!repo.path("fork").asBoolean(false)) {
                topRepos.add(new LiveData.Repo(Json.text(repo, "name"), Json.text(repo, "html_url"),
                        Json.number(repo, "stargazers_count"), Json.text(repo, "language")));
            }
        }
        topRepos.sort(Comparator.comparingLong(LiveData.Repo::stars).reversed());

        List<Integer> levels = contributionLevels(username);
        int activeDays = (int) levels.stream().filter(level -> level > 0).count();
        String login = Json.text(user, "login");
        return new LiveData.GitHub(login, Json.text(user, "name"), Json.text(user, "avatar_url"),
                Json.text(user, "html_url"), Json.number(user, "followers"), Json.number(user, "public_repos"),
                activeDays, levels, List.copyOf(topRepos.subList(0, Math.min(3, topRepos.size()))));
    }

    public List<ActivityItem> recentActivity(String username) {
        JsonNode events = getJson(API + "/users/{username}/events/public?per_page=30", username);
        List<ActivityItem> items = new ArrayList<>();
        for (JsonNode event : events) {
            String repo = Json.text(event.path("repo"), "name");
            if (repo == null) {
                continue;
            }
            String repoUrl = WEB + "/" + repo;
            JsonNode payload = event.path("payload");
            String title = switch (String.valueOf(Json.text(event, "type"))) {
                case "PushEvent" -> repo + " reposuna push" + branchSuffix(Json.text(payload, "ref"));
                case "CreateEvent" -> "repository".equals(Json.text(payload, "ref_type"))
                        ? repo + " reposunu oluşturdu" : null;
                case "ReleaseEvent" -> repo + " " + firstNonNull(Json.text(payload.path("release"), "tag_name"), "")
                        .strip() + " sürümünü yayınladı";
                case "PullRequestEvent" -> "opened".equals(Json.text(payload, "action"))
                        ? repo + " reposunda pull request açtı" : null;
                case "WatchEvent" -> repo + " reposuna yıldız verdi";
                case "ForkEvent" -> repo + " reposunu forkladı";
                default -> null;
            };
            if (title != null) {
                items.add(new ActivityItem(Json.text(event, "id"), kind(event), title.replaceAll("\\s+", " "),
                        repoUrl, Instant.parse(Json.text(event, "created_at"))));
            }
        }
        return items;
    }

    /** Son 26 haftanin gunluk katki seviyeleri (0-4), pazardan baslayan tam haftalar halinde. */
    List<Integer> contributionLevels(String username) {
        String html;
        try {
            html = web.get().uri(WEB + "/users/{username}/contributions", username).retrieve().body(String.class);
        } catch (RestClientException e) {
            return List.of();
        }
        if (html == null) {
            return List.of();
        }
        TreeMap<LocalDate, Integer> byDate = new TreeMap<>();
        Matcher cell = DAY_CELL.matcher(html);
        while (cell.find()) {
            Matcher level = LEVEL.matcher(cell.group());
            if (level.find()) {
                byDate.put(LocalDate.parse(cell.group(1)), Integer.parseInt(level.group(1)));
            }
        }
        if (byDate.isEmpty()) {
            return List.of();
        }
        LocalDate start = byDate.lastKey().minusWeeks(HEATMAP_WEEKS - 1);
        start = start.minusDays(start.getDayOfWeek() == DayOfWeek.SUNDAY ? 0 : start.getDayOfWeek().getValue());
        return List.copyOf(byDate.tailMap(start, true).values());
    }

    private JsonNode getJson(String uri, String username) {
        try {
            return http.get().uri(uri, username).retrieve()
                    .onStatus(status -> status.value() == HttpStatus.NOT_FOUND.value(), (request, response) -> {
                        throw new AccountNotFoundException("GitHub kullanıcısı bulunamadı");
                    })
                    .body(JsonNode.class);
        } catch (AccountNotFoundException e) {
            throw e;
        } catch (RestClientException e) {
            throw new ProviderUnavailableException("GitHub yanıt vermedi", e);
        }
    }

    private static String kind(JsonNode event) {
        return switch (String.valueOf(Json.text(event, "type"))) {
            case "PushEvent" -> "push";
            case "CreateEvent" -> "repo";
            case "ReleaseEvent" -> "release";
            case "PullRequestEvent" -> "pull_request";
            case "WatchEvent" -> "star";
            case "ForkEvent" -> "fork";
            default -> "other";
        };
    }

    private static String branchSuffix(String ref) {
        if (ref == null || !ref.startsWith("refs/heads/")) {
            return "";
        }
        return " (" + ref.substring("refs/heads/".length()) + ")";
    }

    private static String firstNonNull(String value, String fallback) {
        return value != null ? value : fallback;
    }
}
