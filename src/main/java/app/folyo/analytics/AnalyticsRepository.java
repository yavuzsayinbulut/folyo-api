package app.folyo.analytics;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Gunluk sayaclar. Tum yazmalar tek ifadelik upsert'tur; es zamanli ziyaretlerde sayac kaybolmaz. */
@Repository
public class AnalyticsRepository {

    public record DayCount(LocalDate day, int views, int visitors) {
    }

    public record CardCount(UUID cardId, int clicks) {
    }

    private final JdbcClient jdbc;

    public AnalyticsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Gunun tuzunu doner; yoksa olusturur. Iki instance ayni anda olusturmaya calissa da tek tuz kalir. */
    public byte[] saltFor(LocalDate day, byte[] candidate) {
        jdbc.sql("insert into daily_salts (day, salt) values (:day, :salt) on conflict (day) do nothing")
                .param("day", day).param("salt", candidate).update();
        return jdbc.sql("select salt from daily_salts where day = :day").param("day", day).query(byte[].class).single();
    }

    /** Bu ozet bugun bu kapsamda ilk kez mi goruldu? */
    public boolean markFirstVisit(UUID scopeId, LocalDate day, byte[] visitorHash) {
        return jdbc.sql("""
                insert into visit_marks (scope_id, day, visitor_hash) values (:scope, :day, :hash)
                on conflict do nothing""")
                .param("scope", scopeId).param("day", day).param("hash", visitorHash).update() == 1;
    }

    public void addView(UUID profileId, LocalDate day, boolean newVisitor) {
        jdbc.sql("""
                insert into profile_views_daily (profile_id, day, views, unique_visitors)
                values (:profile, :day, 1, :visitor)
                on conflict (profile_id, day) do update set
                    views = profile_views_daily.views + 1,
                    unique_visitors = profile_views_daily.unique_visitors + excluded.unique_visitors""")
                .param("profile", profileId).param("day", day).param("visitor", newVisitor ? 1 : 0).update();
    }

    public void addClick(UUID cardId, LocalDate day) {
        jdbc.sql("""
                insert into card_clicks_daily (card_id, day, clicks) values (:card, :day, 1)
                on conflict (card_id, day) do update set clicks = card_clicks_daily.clicks + 1""")
                .param("card", cardId).param("day", day).update();
    }

    public List<DayCount> views(UUID profileId, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                select day, views, unique_visitors from profile_views_daily
                where profile_id = :profile and day between :from and :to order by day""")
                .param("profile", profileId).param("from", from).param("to", to)
                .query((rs, i) -> new DayCount(rs.getObject("day", LocalDate.class), rs.getInt("views"),
                        rs.getInt("unique_visitors")))
                .list();
    }

    public List<CardCount> clicksByCard(UUID profileId, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                select c.card_id, sum(c.clicks) as clicks
                from card_clicks_daily c join cards k on k.id = c.card_id
                where k.profile_id = :profile and c.day between :from and :to
                group by c.card_id order by clicks desc""")
                .param("profile", profileId).param("from", from).param("to", to)
                .query((rs, i) -> new CardCount(rs.getObject("card_id", UUID.class), rs.getInt("clicks")))
                .list();
    }

    /** Tekil sayim icin gereken tuz ve isaretler dunden eskiyse silinir; toplamlar kalir. */
    public void forgetVisitorsBefore(LocalDate day) {
        jdbc.sql("delete from visit_marks where day < :day").param("day", day).update();
        jdbc.sql("delete from daily_salts where day < :day").param("day", day).update();
    }

    public void deleteCountsBefore(LocalDate day) {
        jdbc.sql("delete from profile_views_daily where day < :day").param("day", day).update();
        jdbc.sql("delete from card_clicks_daily where day < :day").param("day", day).update();
    }
}
