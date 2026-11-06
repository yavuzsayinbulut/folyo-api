-- Faz 3: gizlilik dostu istatistik. Tekil ziyaret yerine gun bazinda toplam tutulur; IP saklanmaz.

create table profile_views_daily (
    profile_id      uuid not null references profiles (id) on delete cascade,
    day             date not null,
    views           int  not null default 0,
    unique_visitors int  not null default 0,
    primary key (profile_id, day)
);

-- Kart basina gunluk tekil tiklama (ayni ziyaretci ayni gun ayni karta 5 kez tiklasa da 1 sayilir).
create table card_clicks_daily (
    card_id uuid not null references cards (id) on delete cascade,
    day     date not null,
    clicks  int  not null default 0,
    primary key (card_id, day)
);

-- Gunluk tuz: ziyaretci ozeti (hash) bu tuzla uretilir. Tuz ertesi gun silinir;
-- boylece dunku ozetler bugunle eslestirilemez ve IP'ye geri donulemez.
create table daily_salts (
    day  date primary key,
    salt bytea not null
);

-- Yalniz bugun ve dun icin tutulur; tekil sayimin tek amaci "bu ozeti bugun gorduk mu".
-- scope_id: sayfa goruntulemede profile_id, tiklamada card_id.
create table visit_marks (
    scope_id     uuid  not null,
    day          date  not null,
    visitor_hash bytea not null,
    primary key (scope_id, day, visitor_hash)
);
