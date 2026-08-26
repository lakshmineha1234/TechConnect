package com.techconnect.config;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.nio.file.Paths;

@Configuration
public class DatabaseConfig {

    /**
     * SQLite datasource — single-file, no native compilation, WAL mode for
     * better concurrent read performance.
     */
    @Bean
    public DataSource dataSource() {
        // Resolve DB file relative to the project root (parent of java-server/).
        // Fall back to the current directory if there is no parent.
        Path here   = Paths.get("").toAbsolutePath();
        Path parent = here.getParent() != null ? here.getParent() : here;
        String envPath = System.getenv("DB_PATH");
        Path dbPath = (envPath != null && !envPath.isBlank())
            ? Paths.get(envPath)
            : parent.resolve("techconnect_java.sqlite").normalize();

        SQLiteConfig cfg = new SQLiteConfig();
        cfg.enforceForeignKeys(true);
        cfg.setJournalMode(SQLiteConfig.JournalMode.WAL);

        SQLiteDataSource ds = new SQLiteDataSource(cfg);
        ds.setUrl("jdbc:sqlite:" + dbPath);
        return ds;
    }

    /**
     * Create tables on startup — idempotent (IF NOT EXISTS), safe to run
     * every time the server boots.
     */
    @Bean
    public CommandLineRunner initSchema(JdbcTemplate jdbc) {
        return args -> {
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS users (
                    id            TEXT PRIMARY KEY,
                    email         TEXT UNIQUE NOT NULL,
                    password_hash TEXT NOT NULL,
                    role          TEXT NOT NULL CHECK(role IN ('student','pro')),
                    first_name    TEXT NOT NULL DEFAULT '',
                    last_name     TEXT NOT NULL DEFAULT '',
                    created_at    TEXT DEFAULT (datetime('now'))
                )
                """);

            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS profiles (
                    user_id     TEXT PRIMARY KEY
                                REFERENCES users(id) ON DELETE CASCADE,
                    phone       TEXT DEFAULT '',
                    location    TEXT DEFAULT '',
                    bio         TEXT DEFAULT '',
                    institution TEXT DEFAULT '',
                    degree      TEXT DEFAULT '',
                    year        TEXT DEFAULT '',
                    company     TEXT DEFAULT '',
                    job_title   TEXT DEFAULT '',
                    experience  TEXT DEFAULT '',
                    linkedin    TEXT DEFAULT '',
                    github      TEXT DEFAULT '',
                    updated_at  TEXT DEFAULT (datetime('now'))
                )
                """);

            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS skills (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    skill_name TEXT NOT NULL,
                    UNIQUE(user_id, skill_name)
                )
                """);

            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS messages (
                    id          TEXT PRIMARY KEY,
                    sender_id   TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    receiver_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    content     TEXT NOT NULL,
                    is_read     INTEGER NOT NULL DEFAULT 0,
                    created_at  TEXT DEFAULT (datetime('now'))
                )
                """);

            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_msg_sender   ON messages(sender_id, created_at)");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_msg_receiver ON messages(receiver_id, created_at)");

            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS connections (
                    id           TEXT PRIMARY KEY,
                    requester_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    recipient_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    status       TEXT NOT NULL DEFAULT 'pending'
                                 CHECK(status IN ('pending','accepted','declined')),
                    created_at   TEXT DEFAULT (datetime('now')),
                    updated_at   TEXT DEFAULT (datetime('now')),
                    UNIQUE(requester_id, recipient_id)
                )
                """);

            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS posts (
                    id         TEXT PRIMARY KEY,
                    user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    content    TEXT NOT NULL,
                    created_at TEXT DEFAULT (datetime('now'))
                )
                """);
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_posts_user ON posts(user_id, created_at)");

            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS post_likes (
                    id         TEXT PRIMARY KEY,
                    post_id    TEXT NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
                    user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    created_at TEXT DEFAULT (datetime('now')),
                    UNIQUE(post_id, user_id)
                )
                """);

            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS post_saves (
                    id         TEXT PRIMARY KEY,
                    post_id    TEXT NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
                    user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    created_at TEXT DEFAULT (datetime('now')),
                    UNIQUE(post_id, user_id)
                )
                """);
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_post_saves_user ON post_saves(user_id, created_at)");

            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS jobs (
                    id          TEXT PRIMARY KEY,
                    user_id     TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    title       TEXT NOT NULL,
                    company     TEXT NOT NULL,
                    location    TEXT DEFAULT '',
                    type        TEXT NOT NULL DEFAULT 'full-time'
                                CHECK(type IN ('full-time','part-time','internship','freelance','remote')),
                    description TEXT NOT NULL,
                    skills      TEXT DEFAULT '',
                    salary      TEXT DEFAULT '',
                    created_at  TEXT DEFAULT (datetime('now'))
                )
                """);
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_jobs_user    ON jobs(user_id, created_at)");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_jobs_type    ON jobs(type)");

            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS job_saves (
                    id         TEXT PRIMARY KEY,
                    job_id     TEXT NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
                    user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    created_at TEXT DEFAULT (datetime('now')),
                    UNIQUE(job_id, user_id)
                )
                """);

            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS notifications (
                    id         TEXT PRIMARY KEY,
                    user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    type       TEXT NOT NULL,
                    actor_id   TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    ref_id     TEXT DEFAULT '',
                    is_read    INTEGER NOT NULL DEFAULT 0,
                    created_at TEXT DEFAULT (datetime('now'))
                )
                """);
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_notif_user ON notifications(user_id, is_read, created_at)");

            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS events (
                    id            TEXT PRIMARY KEY,
                    creator_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    title         TEXT NOT NULL,
                    description   TEXT NOT NULL,
                    event_type    TEXT NOT NULL DEFAULT 'meetup'
                                  CHECK(event_type IN ('meetup','webinar','hackathon','workshop','other')),
                    location      TEXT DEFAULT '',
                    start_time    TEXT NOT NULL,
                    end_time      TEXT DEFAULT '',
                    max_attendees INTEGER DEFAULT 0,
                    created_at    TEXT DEFAULT (datetime('now'))
                )
                """);
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_events_start ON events(start_time)");

            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS event_attendees (
                    id         TEXT PRIMARY KEY,
                    event_id   TEXT NOT NULL REFERENCES events(id) ON DELETE CASCADE,
                    user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    created_at TEXT DEFAULT (datetime('now')),
                    UNIQUE(event_id, user_id)
                )
                """);

            // Add social provider IDs for social login (idempotent)
            try { jdbc.execute("ALTER TABLE users ADD COLUMN google_id   TEXT NOT NULL DEFAULT ''"); } catch (Exception ignored) {}
            try { jdbc.execute("ALTER TABLE users ADD COLUMN linkedin_id TEXT NOT NULL DEFAULT ''"); } catch (Exception ignored) {}
            try { jdbc.execute("ALTER TABLE users ADD COLUMN github_id   TEXT NOT NULL DEFAULT ''"); } catch (Exception ignored) {}
            try { jdbc.execute("CREATE INDEX IF NOT EXISTS idx_users_google_id   ON users(google_id)   WHERE google_id   != ''"); } catch (Exception ignored) {}
            try { jdbc.execute("CREATE INDEX IF NOT EXISTS idx_users_linkedin_id ON users(linkedin_id) WHERE linkedin_id != ''"); } catch (Exception ignored) {}
            try { jdbc.execute("CREATE INDEX IF NOT EXISTS idx_users_github_id   ON users(github_id)   WHERE github_id   != ''"); } catch (Exception ignored) {}

            // Add avatar_mime column to profiles (idempotent — ignore if already exists)
            try { jdbc.execute("ALTER TABLE profiles ADD COLUMN avatar_mime TEXT DEFAULT ''"); } catch (Exception ignored) {}
            // Add resume_name column to profiles (idempotent)
            try { jdbc.execute("ALTER TABLE profiles ADD COLUMN resume_name TEXT DEFAULT ''"); } catch (Exception ignored) {}
            // Add image_url column to posts (idempotent)
            try { jdbc.execute("ALTER TABLE posts ADD COLUMN image_url TEXT DEFAULT ''"); } catch (Exception ignored) {}
            // Add status badges columns to profiles (idempotent)
            try { jdbc.execute("ALTER TABLE profiles ADD COLUMN open_to_work INTEGER NOT NULL DEFAULT 0"); } catch (Exception ignored) {}
            try { jdbc.execute("ALTER TABLE profiles ADD COLUMN is_hiring    INTEGER NOT NULL DEFAULT 0"); } catch (Exception ignored) {}
            // Add repost support to posts (idempotent)
            try { jdbc.execute("ALTER TABLE posts ADD COLUMN shared_from_id TEXT NOT NULL DEFAULT ''"); } catch (Exception ignored) {}
            // Add scheduled publish time to posts (idempotent)
            try { jdbc.execute("ALTER TABLE posts ADD COLUMN scheduled_at TEXT DEFAULT NULL"); } catch (Exception ignored) {}
            try { jdbc.execute("CREATE INDEX IF NOT EXISTS idx_posts_scheduled ON posts(scheduled_at) WHERE scheduled_at IS NOT NULL"); } catch (Exception ignored) {}
            // User status (emoji + short message + optional expiry)
            try { jdbc.execute("ALTER TABLE profiles ADD COLUMN status_emoji TEXT NOT NULL DEFAULT ''"); } catch (Exception ignored) {}
            try { jdbc.execute("ALTER TABLE profiles ADD COLUMN status_text  TEXT NOT NULL DEFAULT ''"); } catch (Exception ignored) {}
            try { jdbc.execute("ALTER TABLE profiles ADD COLUMN status_expires TEXT DEFAULT NULL"); } catch (Exception ignored) {}
            // Pinned post on profile
            try { jdbc.execute("ALTER TABLE profiles ADD COLUMN pinned_post_id TEXT DEFAULT NULL"); } catch (Exception ignored) {}
            // Muted users
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS muted_users (
                    user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    muted_id   TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    created_at TEXT DEFAULT (datetime('now')),
                    PRIMARY KEY (user_id, muted_id)
                )
                """);
            // Private connection notes
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS connection_notes (
                    author_id  TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    subject_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    note       TEXT NOT NULL DEFAULT '',
                    updated_at TEXT DEFAULT (datetime('now')),
                    PRIMARY KEY (author_id, subject_id)
                )
                """);
            // Hashtag following
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS followed_hashtags (
                    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    hashtag TEXT NOT NULL,
                    created_at TEXT DEFAULT (datetime('now')),
                    PRIMARY KEY (user_id, hashtag)
                )
                """);
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_followed_ht_user ON followed_hashtags(user_id)");
            // Add reaction type to post_likes (idempotent) — existing rows keep 'like' default
            try { jdbc.execute("ALTER TABLE post_likes ADD COLUMN reaction TEXT NOT NULL DEFAULT 'like'"); } catch (Exception ignored) {}
            // Add personal note to connection requests (idempotent)
            try { jdbc.execute("ALTER TABLE connections ADD COLUMN note TEXT NOT NULL DEFAULT ''"); } catch (Exception ignored) {}
            // Add parent_id to comments for threaded replies (idempotent)
            try { jdbc.execute("ALTER TABLE post_comments ADD COLUMN parent_id TEXT DEFAULT NULL"); } catch (Exception ignored) {}
            // Add last_seen timestamp to profiles for online presence (idempotent)
            try { jdbc.execute("ALTER TABLE profiles ADD COLUMN last_seen TEXT DEFAULT ''"); } catch (Exception ignored) {}
            try { jdbc.execute("CREATE INDEX IF NOT EXISTS idx_profiles_last_seen ON profiles(last_seen)"); } catch (Exception ignored) {}

            // Post impressions — one row per unique viewer per post
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS post_views (
                    id         TEXT PRIMARY KEY,
                    post_id    TEXT NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
                    viewer_id  TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    viewed_at  TEXT DEFAULT (datetime('now')),
                    UNIQUE(post_id, viewer_id)
                )
                """);
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_post_views_post ON post_views(post_id)");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_post_views_viewer ON post_views(viewer_id, viewed_at)");


            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS post_hashtags (
                    id         TEXT PRIMARY KEY,
                    post_id    TEXT NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
                    hashtag    TEXT NOT NULL,
                    created_at TEXT DEFAULT (datetime('now')),
                    UNIQUE(post_id, hashtag)
                )
                """);
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_hashtags_tag ON post_hashtags(hashtag, created_at)");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_hashtags_post ON post_hashtags(post_id)");

            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS poll_options (
                    id          TEXT PRIMARY KEY,
                    post_id     TEXT NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
                    option_text TEXT NOT NULL,
                    position    INTEGER NOT NULL DEFAULT 0
                )
                """);
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_poll_options_post ON poll_options(post_id, position)");

            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS poll_votes (
                    id        TEXT PRIMARY KEY,
                    post_id   TEXT NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
                    option_id TEXT NOT NULL REFERENCES poll_options(id) ON DELETE CASCADE,
                    user_id   TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    created_at TEXT DEFAULT (datetime('now')),
                    UNIQUE(post_id, user_id)
                )
                """);
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_poll_votes_post ON poll_votes(post_id)");

            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS projects (
                    id          TEXT PRIMARY KEY,
                    user_id     TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    title       TEXT NOT NULL,
                    description TEXT NOT NULL DEFAULT '',
                    tech_stack  TEXT NOT NULL DEFAULT '',
                    github_url  TEXT NOT NULL DEFAULT '',
                    live_url    TEXT NOT NULL DEFAULT '',
                    display_order INTEGER NOT NULL DEFAULT 0,
                    created_at  TEXT DEFAULT (datetime('now'))
                )
                """);
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_projects_user ON projects(user_id, display_order)");

            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS post_comments (
                    id         TEXT PRIMARY KEY,
                    post_id    TEXT NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
                    user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    content    TEXT NOT NULL,
                    created_at TEXT DEFAULT (datetime('now'))
                )
                """);
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_comments_post ON post_comments(post_id, created_at)");

            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS job_applications (
                    id          TEXT PRIMARY KEY,
                    job_id      TEXT NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
                    applicant_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    cover_note  TEXT DEFAULT '',
                    status      TEXT NOT NULL DEFAULT 'pending'
                                CHECK(status IN ('pending','reviewed','accepted','rejected')),
                    created_at  TEXT DEFAULT (datetime('now')),
                    updated_at  TEXT DEFAULT (datetime('now')),
                    UNIQUE(job_id, applicant_id)
                )
                """);
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_apps_job       ON job_applications(job_id, created_at)");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_apps_applicant ON job_applications(applicant_id, created_at)");

            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS skill_endorsements (
                    id          TEXT PRIMARY KEY,
                    endorsed_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    endorser_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    skill_name  TEXT NOT NULL,
                    created_at  TEXT DEFAULT (datetime('now')),
                    UNIQUE(endorsed_id, endorser_id, skill_name)
                )
                """);
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_endorse_user  ON skill_endorsements(endorsed_id, skill_name)");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_endorse_giver ON skill_endorsements(endorser_id)");

            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS meetings (
                    id             TEXT PRIMARY KEY,
                    requester_id   TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    participant_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    title          TEXT NOT NULL,
                    scheduled_time TEXT NOT NULL,
                    duration_mins  INTEGER DEFAULT 30,
                    meeting_link   TEXT DEFAULT '',
                    notes          TEXT DEFAULT '',
                    status         TEXT NOT NULL DEFAULT 'pending'
                                   CHECK(status IN ('pending','accepted','declined','cancelled')),
                    created_at     TEXT DEFAULT (datetime('now'))
                )
                """);
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_meetings_req  ON meetings(requester_id, scheduled_time)");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_meetings_part ON meetings(participant_id, scheduled_time)");

            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS profile_views (
                    id        TEXT PRIMARY KEY,
                    viewer_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    viewed_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    view_date TEXT NOT NULL DEFAULT (date('now')),
                    viewed_at TEXT DEFAULT (datetime('now')),
                    UNIQUE(viewer_id, viewed_id, view_date)
                )
                """);
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_pviews_viewed ON profile_views(viewed_id, viewed_at)");

            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS recommendations (
                    id           TEXT PRIMARY KEY,
                    author_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    recipient_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    text         TEXT NOT NULL,
                    status       TEXT NOT NULL DEFAULT 'pending'
                                 CHECK(status IN ('pending','approved','hidden')),
                    created_at   TEXT DEFAULT (datetime('now')),
                    UNIQUE(author_id, recipient_id)
                )
                """);
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_recs_recipient ON recommendations(recipient_id, status)");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_recs_author    ON recommendations(author_id)");

            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS certifications (
                    id             TEXT PRIMARY KEY,
                    user_id        TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    name           TEXT NOT NULL,
                    issuer         TEXT NOT NULL DEFAULT '',
                    issue_date     TEXT NOT NULL DEFAULT '',
                    expiry_date    TEXT NOT NULL DEFAULT '',
                    credential_url TEXT NOT NULL DEFAULT '',
                    display_order  INTEGER NOT NULL DEFAULT 0,
                    created_at     TEXT DEFAULT (datetime('now'))
                )
                """);
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_certs_user ON certifications(user_id, display_order)");

            // Password reset tokens (expire after 1 hour)
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS password_reset_tokens (
                    token      TEXT PRIMARY KEY,
                    user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    expires_at TEXT NOT NULL,
                    created_at TEXT DEFAULT (datetime('now'))
                )
                """);

            // Blocked users — bidirectional block
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS blocked_users (
                    blocker_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    blocked_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    created_at TEXT DEFAULT (datetime('now')),
                    PRIMARY KEY (blocker_id, blocked_id)
                )
                """);
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_blocked_blocker ON blocked_users(blocker_id)");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_blocked_blocked ON blocked_users(blocked_id)");

            // Post drafts — one per user, upserted on save
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS post_drafts (
                    user_id    TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
                    content    TEXT NOT NULL DEFAULT '',
                    updated_at TEXT DEFAULT (datetime('now'))
                )
                """);

            // Post reports
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS post_reports (
                    id         TEXT PRIMARY KEY,
                    post_id    TEXT NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
                    reporter_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    reason     TEXT NOT NULL DEFAULT '',
                    created_at TEXT DEFAULT (datetime('now')),
                    UNIQUE(post_id, reporter_id)
                )
                """);
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_reports_post ON post_reports(post_id, created_at)");

            // Purge any rows that reference deleted users (guards against external deletes
            // that bypass the server's foreign-key enforcement).
            jdbc.update("DELETE FROM connections   WHERE requester_id NOT IN (SELECT id FROM users) OR recipient_id  NOT IN (SELECT id FROM users)");
            jdbc.update("DELETE FROM messages       WHERE sender_id   NOT IN (SELECT id FROM users) OR receiver_id   NOT IN (SELECT id FROM users)");
            jdbc.update("DELETE FROM profile_views  WHERE viewer_id   NOT IN (SELECT id FROM users) OR viewed_id     NOT IN (SELECT id FROM users)");
            jdbc.update("DELETE FROM notifications  WHERE user_id     NOT IN (SELECT id FROM users) OR actor_id      NOT IN (SELECT id FROM users)");
            jdbc.update("DELETE FROM meetings       WHERE requester_id NOT IN (SELECT id FROM users) OR participant_id NOT IN (SELECT id FROM users)");
        };
    }
}
