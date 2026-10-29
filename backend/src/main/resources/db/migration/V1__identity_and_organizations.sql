-- QueueLess schema, part 1: identity, organizations and memberships.
-- All instants are stored as timestamptz (UTC on the wire). Organization-local
-- calendar values (service dates, opening hours) use date/time columns and are
-- interpreted in organizations.timezone.

CREATE TABLE users (
    id             uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    email          varchar(254) NOT NULL,
    full_name      varchar(120) NOT NULL,
    password_hash  varchar(100) NOT NULL,
    enabled        boolean      NOT NULL DEFAULT true,
    platform_admin boolean      NOT NULL DEFAULT false,
    created_at     timestamptz  NOT NULL,
    updated_at     timestamptz  NOT NULL,
    -- Emails are normalised (trimmed, lower-case) by the application; the check
    -- guarantees that the unique constraint really is case-insensitive.
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT ck_users_email_normalized CHECK (email = lower(btrim(email)))
);

CREATE TABLE organizations (
    id          uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    name        varchar(120) NOT NULL,
    slug        varchar(60)  NOT NULL,
    description varchar(500),
    timezone    varchar(64)  NOT NULL,
    active      boolean      NOT NULL DEFAULT true,
    created_at  timestamptz  NOT NULL,
    updated_at  timestamptz  NOT NULL,
    CONSTRAINT uq_organizations_slug UNIQUE (slug),
    CONSTRAINT ck_organizations_slug_format CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$')
);

-- Organization-scoped roles. A user has at most one membership per organization.
CREATE TABLE organization_memberships (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid        NOT NULL REFERENCES organizations (id),
    user_id         uuid        NOT NULL REFERENCES users (id),
    role            varchar(16) NOT NULL,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    -- Serves "memberships of the current user" and the per-request access check
    -- (user_id, organization_id).
    CONSTRAINT uq_memberships_user_org UNIQUE (user_id, organization_id),
    CONSTRAINT ck_memberships_role CHECK (role IN ('CUSTOMER', 'STAFF', 'ADMIN'))
);

-- Serves "list members of an organization" and "count admins" (last-admin rule).
CREATE INDEX ix_memberships_org_role ON organization_memberships (organization_id, role);
