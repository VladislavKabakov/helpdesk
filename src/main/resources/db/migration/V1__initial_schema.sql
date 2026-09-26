CREATE TABLE app_user (
    id UUID PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    role VARCHAR(16) NOT NULL CHECK (role IN ('USER', 'AGENT'))
);

CREATE TABLE ticket (
    id UUID PRIMARY KEY,
    requester_id UUID NOT NULL REFERENCES app_user(id),
    assignee_id UUID REFERENCES app_user(id),
    title VARCHAR(120) NOT NULL CHECK (length(trim(title)) > 0),
    description VARCHAR(4000) NOT NULL CHECK (length(trim(description)) > 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('OPEN', 'IN_PROGRESS', 'RESOLVED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ((status = 'OPEN' AND assignee_id IS NULL)
        OR (status IN ('IN_PROGRESS', 'RESOLVED') AND assignee_id IS NOT NULL))
);
CREATE INDEX ticket_requester_idx ON ticket(requester_id, created_at DESC);
CREATE INDEX ticket_assignee_idx ON ticket(assignee_id, created_at DESC);
CREATE INDEX ticket_queue_idx ON ticket(created_at) WHERE status = 'OPEN';

CREATE TABLE ticket_message (
    id UUID PRIMARY KEY,
    ticket_id UUID NOT NULL REFERENCES ticket(id),
    author_id UUID NOT NULL REFERENCES app_user(id),
    body VARCHAR(4000) NOT NULL CHECK (length(trim(body)) > 0),
    internal BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX message_ticket_idx ON ticket_message(ticket_id, created_at);

CREATE TABLE audit_event (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_id UUID NOT NULL REFERENCES app_user(id),
    ticket_id UUID NOT NULL REFERENCES ticket(id),
    action VARCHAR(32) NOT NULL CHECK (action IN
        ('CREATED', 'CLAIMED', 'COMMENTED', 'INTERNAL_NOTE_ADDED', 'RESOLVED', 'REOPENED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
