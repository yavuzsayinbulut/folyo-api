-- Faz 1 cekirdek semasi: giris yapan kullanicilar, sayfalar, kartlar, ziyaretci defteri.
-- Entegrasyon (connections, activity_events), galeri (media) ve istatistik tablolari kendi fazlarinda eklenir.

create table users (
    id            uuid primary key,
    auth_provider varchar(20)  not null,
    auth_subject  varchar(255) not null,
    email         varchar(320),
    name          varchar(100) not null,
    avatar_url    varchar(2048),
    created_at    timestamptz  not null default now(),
    -- Ayni kisi Google ve GitHub ile girerse iki ayri kullanici olur; e-posta ile otomatik birlestirme yapilmaz.
    constraint uq_users_identity unique (auth_provider, auth_subject)
);

create table profiles (
    id           uuid primary key,
    user_id      uuid        not null references users (id) on delete cascade,
    username     varchar(20) not null,
    display_name varchar(60) not null,
    bio          varchar(280),
    location     varchar(60),
    timezone     varchar(64),
    status       varchar(40),
    visibility   varchar(10) not null default 'PUBLIC',
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now(),
    constraint uq_profiles_user unique (user_id),
    constraint uq_profiles_username unique (username),
    constraint ck_profiles_username check (username ~ '^[a-z0-9_.]{3,20}$'),
    constraint ck_profiles_visibility check (visibility in ('PUBLIC', 'UNLISTED'))
);

create table cards (
    id         uuid primary key,
    profile_id uuid        not null references profiles (id) on delete cascade,
    type       varchar(20) not null,
    position   int         not null,
    size       varchar(10) not null,
    is_visible boolean     not null default true,
    config     jsonb       not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint ck_cards_type check (type in ('LINK', 'TEXT')),
    constraint ck_cards_size check (size in ('SMALL', 'WIDE', 'LARGE')),
    -- Siralama tek transaction'da yeniden yazilirken ara durumlar cakismasin diye commit'te kontrol edilir.
    constraint uq_cards_position unique (profile_id, position) deferrable initially deferred
);

create table guestbook_entries (
    id             uuid primary key,
    profile_id     uuid         not null references profiles (id) on delete cascade,
    author_user_id uuid         not null references users (id) on delete cascade,
    body           varchar(280) not null,
    status         varchar(10)  not null default 'VISIBLE',
    created_at     timestamptz  not null default now(),
    constraint ck_guestbook_status check (status in ('VISIBLE', 'HIDDEN'))
);

create index ix_guestbook_profile_created on guestbook_entries (profile_id, created_at desc);
create index ix_guestbook_author_profile on guestbook_entries (author_user_id, profile_id, created_at);
