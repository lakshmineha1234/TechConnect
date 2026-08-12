/**
 * migrate.js
 * Reads real user accounts from the Node.js nedb files and inserts any
 * that don't already exist into the Java SQLite database.
 *
 * Run once:  node migrate.js
 */

const fs      = require('fs');
const path    = require('path');
const initSQL = require('sql.js');
// simple UUID v4 generator — no dependency needed
function uuidv4() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
    const r = Math.random() * 16 | 0;
    return (c === 'x' ? r : (r & 0x3 | 0x8)).toString(16);
  });
}

const NEDB_USERS    = path.join(__dirname, 'data', 'users.db');
const NEDB_PROFILES = path.join(__dirname, 'data', 'profiles.db');
const SQLITE_PATH   = path.join(__dirname, 'techconnect_java.sqlite');

// ── 1. Parse nedb file (each line is a JSON doc or index record) ──────────────
function parseNedb(filePath) {
  const lines = fs.readFileSync(filePath, 'utf8')
    .split('\n')
    .map(l => l.trim())
    .filter(Boolean);

  return lines
    .map(l => { try { return JSON.parse(l); } catch { return null; } })
    .filter(doc => doc && !doc.$$indexCreated && !doc.$$deleted);
}

// ── 2. Load both nedb stores ──────────────────────────────────────────────────
const nedbUsers    = parseNedb(NEDB_USERS);
const nedbProfiles = parseNedb(NEDB_PROFILES);

const profileMap = {};
nedbProfiles.forEach(p => { profileMap[p.user_id] = p; });

// Strip out the 6 demo seed emails — they're already in SQLite
const SEED_EMAILS = new Set([
  'emma@demo.tc', 'liam@demo.tc', 'priya@demo.tc',
  'marcus@demo.tc', 'sofia@demo.tc', 'james@demo.tc',
]);

const realUsers = nedbUsers.filter(u => !SEED_EMAILS.has(u.email));
console.log(`Found ${nedbUsers.length} total nedb users, ${realUsers.length} non-seed user(s) to migrate.`);

if (realUsers.length === 0) {
  console.log('Nothing to migrate. Exiting.');
  process.exit(0);
}

// ── 3. Open SQLite with sql.js ─────────────────────────────────────────────────
initSQL().then(SQL => {
  const fileBuffer = fs.readFileSync(SQLITE_PATH);
  const db = new SQL.Database(fileBuffer);

  let inserted = 0;
  let skipped  = 0;

  for (const u of realUsers) {
    // Check if email already exists in SQLite
    const exists = db.exec(
      `SELECT id FROM users WHERE email = '${u.email.replace(/'/g, "''")}'`
    );
    if (exists.length > 0 && exists[0].values.length > 0) {
      console.log(`  SKIP  ${u.email} (already exists in SQLite)`);
      skipped++;
      continue;
    }

    const newId  = uuidv4();
    const p      = profileMap[u._id] || {};
    const fn     = (u.first_name  || '').replace(/'/g, "''");
    const ln     = (u.last_name   || '').replace(/'/g, "''");
    const email  = u.email.replace(/'/g, "''");
    const hash   = u.password_hash.replace(/'/g, "''");
    const role   = u.role || 'student';

    // Insert user
    db.run(`INSERT INTO users (id,email,password_hash,role,first_name,last_name)
            VALUES ('${newId}','${email}','${hash}','${role}','${fn}','${ln}')`);

    // Insert profile
    const bio         = (p.bio         || '').replace(/'/g, "''");
    const location    = (p.location    || '').replace(/'/g, "''");
    const institution = (p.institution || '').replace(/'/g, "''");
    const degree      = (p.degree      || '').replace(/'/g, "''");
    const year        = (p.year        || '').replace(/'/g, "''");
    const company     = (p.company     || '').replace(/'/g, "''");
    const jobTitle    = (p.job_title   || '').replace(/'/g, "''");
    const experience  = (p.experience  || '').replace(/'/g, "''");
    const linkedin    = (p.linkedin    || '').replace(/'/g, "''");
    const github      = (p.github      || '').replace(/'/g, "''");

    db.run(`INSERT INTO profiles
              (user_id,institution,degree,year,company,job_title,experience,bio,location,linkedin,github)
            VALUES
              ('${newId}','${institution}','${degree}','${year}',
               '${company}','${jobTitle}','${experience}',
               '${bio}','${location}','${linkedin}','${github}')`);

    // Insert skills
    const skills = Array.isArray(p.skills) ? p.skills : [];
    for (const skill of skills) {
      const s = skill.replace(/'/g, "''");
      db.run(`INSERT OR IGNORE INTO skills (user_id,skill_name) VALUES ('${newId}','${s}')`);
    }

    console.log(`  OK    ${u.email} → ${newId}  (${skills.length} skill(s))`);
    inserted++;
  }

  // ── 4. Save the updated database back to disk ─────────────────────────────
  const data = db.export();
  fs.writeFileSync(SQLITE_PATH, Buffer.from(data));
  db.close();

  console.log(`\nMigration complete: ${inserted} inserted, ${skipped} skipped.`);
  console.log(`SQLite database saved to: ${SQLITE_PATH}`);
});
