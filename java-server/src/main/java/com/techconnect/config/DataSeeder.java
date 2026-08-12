package com.techconnect.config;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Seeds 6 demo profiles (3 students + 3 IT professionals) on first boot.
 * Skips silently if any users already exist.
 */
@Component
public class DataSeeder {

    private final JdbcTemplate            jdbc;
    private final BCryptPasswordEncoder   bcrypt = new BCryptPasswordEncoder(10);

    public DataSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class);
        if (count != null && count > 0) return;

        System.out.println("[Seed] Inserting demo profiles...");
        String hash = bcrypt.encode("demo123");

        List<SeedEntry> entries = List.of(
            new SeedEntry("Emma",   "Thompson", "emma@demo.tc",   "student",
                "MIT", "B.Sc. Computer Science", "3rd Year",
                "", "", "",
                "Passionate about ML and AI-driven products. Looking to connect with industry mentors.",
                "Cambridge, MA", new String[]{"Python","Machine Learning","React","TensorFlow"},
                "https://linkedin.com/in/emma-demo", ""),

            new SeedEntry("Liam",   "Park",     "liam@demo.tc",   "pro",
                "", "", "",
                "Google", "Senior Software Engineer", "5–10 years",
                "Senior engineer specialising in distributed systems and cloud-native architecture.",
                "Mountain View, CA", new String[]{"Java","Kubernetes","GCP","Go"},
                "https://linkedin.com/in/liam-demo", ""),

            new SeedEntry("Priya",  "Sharma",   "priya@demo.tc",  "student",
                "Stanford University", "M.Sc. Data Science", "Postgraduate",
                "", "", "",
                "Data science student with a love for visualisation and storytelling through data.",
                "Palo Alto, CA", new String[]{"Python","SQL","Tableau","R"},
                "", "https://github.com/priya-demo"),

            new SeedEntry("Marcus", "Reid",     "marcus@demo.tc", "pro",
                "", "", "",
                "Microsoft", "Cloud Architect", "10+ years",
                "Full-stack developer and cloud architect focused on .NET and Azure solutions.",
                "Redmond, WA", new String[]{"C#",".NET","Azure","TypeScript"},
                "https://linkedin.com/in/marcus-demo", ""),

            new SeedEntry("Sofia",  "Chen",     "sofia@demo.tc",  "student",
                "UC Berkeley", "B.Sc. Information Security", "4th Year",
                "", "", "",
                "Cybersecurity enthusiast and CTF player, interning at a security consultancy.",
                "Berkeley, CA", new String[]{"Python","Cybersecurity","Linux","Networking"},
                "", "https://github.com/sofia-demo"),

            new SeedEntry("James",  "Okafor",   "james@demo.tc",  "pro",
                "", "", "",
                "Amazon", "DevOps Engineer", "3–5 years",
                "DevOps engineer passionate about infrastructure-as-code and fast deployments.",
                "Seattle, WA", new String[]{"DevOps","Terraform","Docker","AWS"},
                "https://linkedin.com/in/james-demo", "")
        );

        for (SeedEntry e : entries) {
            String id = UUID.randomUUID().toString();

            jdbc.update(
                "INSERT INTO users (id,email,password_hash,role,first_name,last_name) VALUES(?,?,?,?,?,?)",
                id, e.email, hash, e.role, e.firstName, e.lastName);

            jdbc.update(
                "INSERT INTO profiles (user_id,institution,degree,year,company,job_title,experience,bio,location,linkedin,github) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                id, e.institution, e.degree, e.year,
                e.company, e.jobTitle, e.experience,
                e.bio, e.location, e.linkedin, e.github);

            for (String skill : e.skills) {
                jdbc.update("INSERT OR IGNORE INTO skills (user_id,skill_name) VALUES(?,?)", id, skill);
            }
        }

        System.out.println("[Seed] Done — " + entries.size() + " demo profiles inserted.");
    }

    // ── Simple data holder ────────────────────────────────────────────────────
    private record SeedEntry(
        String firstName, String lastName, String email, String role,
        String institution, String degree, String year,
        String company, String jobTitle, String experience,
        String bio, String location, String[] skills,
        String linkedin, String github
    ) {}
}
