-- Faz 2: canli kartlar, OAuth baglantilari, galeri ve aktivite akisi.

alter table cards drop constraint ck_cards_type;
alter table cards add constraint ck_cards_type
    check (type in ('LINK', 'TEXT', 'GITHUB', 'REDDIT', 'LASTFM', 'SPOTIFY', 'STRAVA', 'GALLERY'));

-- Kullanicinin kendi hesabina erisim izni gereken saglayicilar (Spotify, Strava).
-- Herkese acik veriden beslenen kartlar (GitHub, Reddit, Last.fm) kullanici adini kart config'inde tutar.
create table connections (
    id                uuid primary key,
    profile_id        uuid         not null references profiles (id) on delete cascade,
    provider          varchar(20)  not null,
    external_id       varchar(255) not null,
    external_username varchar(255),
    -- Token'lar uygulama tarafinda AES-GCM ile sifrelenir; anahtar veritabaninda degil ortam degiskenindedir.
    access_token_enc  bytea        not null,
    refresh_token_enc bytea,
    expires_at        timestamptz,
    scopes            varchar(500),
    status            varchar(10)  not null default 'ACTIVE',
    created_at        timestamptz  not null default now(),
    updated_at        timestamptz  not null default now(),
    constraint uq_connections_profile_provider unique (profile_id, provider),
    constraint ck_connections_provider check (provider in ('SPOTIFY', 'STRAVA')),
    constraint ck_connections_status check (status in ('ACTIVE', 'REVOKED'))
);

create table media (
    id           uuid primary key,
    card_id      uuid        not null references cards (id) on delete cascade,
    storage_key  varchar(100) not null unique,
    content_type varchar(30) not null,
    size_bytes   int         not null,
    width        int,
    height       int,
    position     int         not null,
    created_at   timestamptz not null default now(),
    constraint ck_media_content_type check (content_type in ('image/jpeg', 'image/png')),
    constraint uq_media_position unique (card_id, position) deferrable initially deferred
);

-- Senkronizasyon isi ayni olayi tekrar cekse bile tek satir kalir (upsert anahtari).
create table activity_events (
    id          uuid primary key,
    profile_id  uuid          not null references profiles (id) on delete cascade,
    provider    varchar(20)   not null,
    external_id varchar(255)  not null,
    kind        varchar(30)   not null,
    title       varchar(300)  not null,
    url         varchar(2048),
    occurred_at timestamptz   not null,
    created_at  timestamptz   not null default now(),
    constraint uq_activity_event unique (profile_id, provider, external_id)
);

create index ix_activity_profile_time on activity_events (profile_id, occurred_at desc);
