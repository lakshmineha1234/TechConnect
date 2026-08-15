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
        Path dbPath = parent.resolve("techconnect_java.sqlite").normalize();

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

            // Add avatar_mime column to profiles (idempotent — ignore if already exists)
            try { jdbc.execute("ALTER TABLE profiles ADD COLUMN avatar_mime TEXT DEFAULT ''"); } catch (Exception ignored) {}
            // Add resume_name column to profiles (idempotent)
            try { jdbc.execute("ALTER TABLE profiles ADD COLUMN resume_name TEXT DEFAULT ''"); } catch (Exception ignored) {}

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
        };
    }
}
