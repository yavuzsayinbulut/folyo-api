-- Native: push bildirim. Gonderim "transactional outbox" ile yapilir: bildirim, onu doguran islemle ayni
-- transaction'da kuyruga yazilir; ayri bir is kuyrugu Expo Push servisine iletir.

create table device_push_tokens (
    id           uuid primary key,
    user_id      uuid         not null references users (id) on delete cascade,
    -- Cihaz kaydi uygulama oturumuna baglidir; uygulamadan cikis yapilinca kayit da silinir.
    api_token_id uuid references api_tokens (id) on delete cascade,
    expo_token   varchar(255) not null unique,
    platform     varchar(10)  not null,
    created_at   timestamptz  not null default now(),
    updated_at   timestamptz  not null default now(),
    constraint ck_device_platform check (platform in ('ios', 'android'))
);

create index ix_device_push_tokens_user on device_push_tokens (user_id);

create table notification_settings (
    user_id        uuid primary key references users (id) on delete cascade,
    guestbook      boolean not null default true,
    weekly_summary boolean not null default true
);

create table push_outbox (
    id              uuid primary key,
    user_id         uuid         not null references users (id) on delete cascade,
    kind            varchar(30)  not null,
    title           varchar(120) not null,
    body            varchar(300) not null,
    data            jsonb        not null default '{}',
    -- Ayni olay icin ikinci kez kuyruga yazilmaz (or. haftalik ozet isi iki kez calissa da).
    dedupe_key      varchar(200) not null unique,
    created_at      timestamptz  not null default now(),
    next_attempt_at timestamptz  not null default now(),
    attempts        int          not null default 0,
    sent_at         timestamptz,
    failed_at       timestamptz,
    last_error      varchar(300)
);

create index ix_push_outbox_pending on push_outbox (next_attempt_at) where sent_at is null and failed_at is null;

-- Expo, gonderimin cihaza ulasip ulasmadigini sonradan "makbuz" ile bildirir; gecersiz cihazlar buradan temizlenir.
create table push_receipts (
    ticket_id       varchar(100) primary key,
    device_token_id uuid        not null references device_push_tokens (id) on delete cascade,
    created_at      timestamptz not null default now()
);
