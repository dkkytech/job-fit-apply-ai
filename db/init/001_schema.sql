-- Job-Fit-Apply-AI — database schema
-- Applied automatically by the Postgres container on first boot
-- (this directory is mounted at /docker-entrypoint-initdb.d).
--
-- The `tracks` DDL never lived in the repo — it was created by hand in the
-- Supabase dashboard. It is reconstructed here from the application code:
--   • writes    → services/job-fit-apply-ai-pipeline/.../nodes/SupabaseTrackNode.kt
--   • reads     → apps/job-fit-apply-ai-backlog/src/pages/Index.tsx (Track interface + select)
--   • dedup     → services/job-fit-apply-ai-pipeline/.../nodes/CheckDuplicateNode.kt
--   • tailoring → services/job-fit-apply-ai-pipeline/.../resources/migrations/001_resume_tailoring.sql
--
-- Row-level security is intentionally dropped: under the direct-Postgres design
-- the browser no longer talks to the database, so access control moves to the
-- bridge API. There is no anon key to defend against here.

-- ── tracks ─────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS tracks (
    id               SERIAL PRIMARY KEY,

    -- source email (ScanEmailNode → SupabaseTrackNode)
    email_id         TEXT,
    email_subject    TEXT,

    -- posting identity — (company, role_title, location) is the dedup key
    company          TEXT        NOT NULL,
    role_title       TEXT,
    location         TEXT,
    job_url          TEXT,
    remote_policy    TEXT        DEFAULT 'unknown',

    -- scoring — fitScore is a Kotlin Float in the pipeline, so NUMERIC (not INTEGER)
    fit_score        NUMERIC,
    pipeline_action  TEXT,                 -- 'skip' | 'tailor' (PipelineAction.asDbValue)
    tech_stack       TEXT[],
    strengths        TEXT[],
    gaps             TEXT[],
    red_flags        TEXT[],
    fit_reasoning    TEXT,
    jd_text          TEXT,

    -- artifacts
    output_path      TEXT,
    artifact_url     TEXT,

    -- ATS score (added by migration 001)
    ats_score        INTEGER,

    -- dashboard state — set by DB defaults on insert, updated by the frontend.
    -- status values used by the UI: backlog | duplicate | applied | interested |
    --                               skipped | interviewing | rejected | offer
    status           TEXT        NOT NULL DEFAULT 'backlog',
    duplicate        BOOLEAN     NOT NULL DEFAULT FALSE,
    duplicate_id     INTEGER,              -- soft back-reference to the original track's id

    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- CheckDuplicateNode filters on (company, role_title, location) + created_at window
CREATE INDEX IF NOT EXISTS idx_tracks_dedup
    ON tracks (company, role_title, location, created_at DESC);

-- Frontend orders by created_at desc and filters by status
CREATE INDEX IF NOT EXISTS idx_tracks_created_at ON tracks (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_tracks_status     ON tracks (status);

-- ── resume_tailoring (from migration 001) ──────────────────────────────────────
CREATE TABLE IF NOT EXISTS resume_tailoring (
    id                   SERIAL PRIMARY KEY,
    track_id             INTEGER REFERENCES tracks(id) ON DELETE CASCADE,
    role_title           TEXT,
    company              TEXT,
    jd_structured        JSONB,    -- JdStructured: required/preferred skills, ATS phrases
    gap_analysis         JSONB,    -- GapAnalysis: skills table, coverage score, gaps, strengths
    tailored_summary     TEXT,     -- rewritten professional summary (plain text)
    tailored_bullets     JSONB,    -- List<TailoredBullet>: original + rewritten + alignment score
    restructured_skills  JSONB,    -- RestructuredSkills: grouped categories, JD-matched list
    ats_overall_score    INTEGER,  -- AtsScore.overallScore (0-100)
    ats_keyword_match    INTEGER,  -- AtsScore.keywordMatch (0-100)
    ats_remaining_gaps   JSONB,    -- AtsScore.remainingGaps (list of strings)
    ats_top_improvements JSONB,    -- AtsScore.top3Improvements (list of strings)
    created_at           TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_resume_tailoring_track_id   ON resume_tailoring (track_id);
CREATE INDEX IF NOT EXISTS idx_resume_tailoring_created_at ON resume_tailoring (created_at DESC);

-- ── track_events ───────────────────────────────────────────────────────────────
-- Application history per track: status changes, notes, emails, applications …
-- appended by the frontend (via the bridge), the bridge itself, and JobBot.
-- Production never re-runs this directory, so the bridge also creates this table
-- at startup (TracksStore.ensureSchema) — keep the two definitions in sync.
CREATE TABLE IF NOT EXISTS track_events (
    id           BIGSERIAL   PRIMARY KEY,
    track_id     INTEGER     NOT NULL REFERENCES tracks(id) ON DELETE CASCADE,
    occurred_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    kind         TEXT        NOT NULL,   -- whitelisted by the bridge (TracksStore.ALLOWED_EVENT_KINDS)
    summary      TEXT,
    source       TEXT        NOT NULL,   -- who recorded it: frontend | bridge | jobbot | …
    details      JSONB
);

-- GET /api/tracks/{id}/events reads one track's history, newest first
CREATE INDEX IF NOT EXISTS idx_track_events_track_occurred
    ON track_events (track_id, occurred_at);
