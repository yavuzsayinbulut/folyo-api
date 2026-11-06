-- Faz 4: mobil uygulama oturumu. Uygulama cerez kullanmaz; web'de giris yapilir, tek kullanimlik kod
-- PKCE ile uygulamaya devredilir ve uygulama karsiliginda uzun omurlu bir erisim token'i alir.

create table api_tokens (
    id           uuid primary key,
    user_id      uuid         not null references users (id) on delete cascade,
    -- Token'in kendisi saklanmaz, SHA-256 ozeti saklanir; veritabani sizsa da token kullanilamaz.
    token_hash   bytea        not null unique,
    name         varchar(60)  not null,
    created_at   timestamptz  not null default now(),
    last_used_at timestamptz,
    expires_at   timestamptz  not null,
    revoked_at   timestamptz
);

create index ix_api_tokens_user on api_tokens (user_id);

create table mobile_auth_codes (
    code_hash      bytea primary key,
    user_id        uuid         not null references users (id) on delete cascade,
    code_challenge varchar(128) not null,
    redirect_uri   varchar(500) not null,
    expires_at     timestamptz  not null
);
