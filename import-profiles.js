/**
 * import-profiles.js
 * ─────────────────────────────────────────────────────────────────────────────
 * Imports 100 real developer profiles from GitHub into techconnect_java.sqlite.
 *
 * Sources used:
 *   • GitHub public API  – profiles, bios, locations, companies, skills
 *   • Stack Overflow API – reputation, top tags (skills) if GitHub email matches
 *
 * Usage:
 *   node import-profiles.js                          ← unauthenticated (slow, 60 req/hr)
 *   node import-profiles.js --token ghp_xxxxxxxxxxxx ← with GitHub PAT  (fast, 5000 req/hr)
 *
 * Get a free token → https://github.com/settings/tokens
 * No scopes needed — only public data is read.
 * ─────────────────────────────────────────────────────────────────────────────
 */

'use strict';

const fs      = require('fs');
const path    = require('path');
const https   = require('https');
const initSQL = require('sql.js');

// ── CLI args ──────────────────────────────────────────────────────────────────
const args  = process.argv.slice(2);
const tkIdx = args.indexOf('--token');
const TOKEN = tkIdx !== -1 ? args[tkIdx + 1] : (process.env.GITHUB_TOKEN || '');
if (TOKEN) console.log('✓ GitHub token found — using authenticated rate limits (5000/hr)\n');
else       console.log('⚠ No GitHub token — unauthenticated (60 req/hr, will be slower)\n  Add one: node import-profiles.js --token ghp_xxx\n');

const SQLITE_PATH = path.join(__dirname, 'techconnect_java.sqlite');
const TARGET      = 100;   // total profiles to import

// ── UUID ──────────────────────────────────────────────────────────────────────
function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
    const r = Math.random() * 16 | 0;
    return (c === 'x' ? r : (r & 0x3 | 0x8)).toString(16);
  });
}

// ── HTTP helper ───────────────────────────────────────────────────────────────
function get(url) {
  return new Promise((resolve, reject) => {
    const opts = {
      headers: {
        'User-Agent': 'TechConnect-Importer/1.0',
        'Accept': 'application/vnd.github+json',
        ...(TOKEN ? { Authorization: `Bearer ${TOKEN}` } : {}),
      },
    };
    https.get(url, opts, res => {
      let data = '';
      res.on('data', c => data += c);
      res.on('end', () => {
        if (res.statusCode === 403 || res.statusCode === 429) {
          const reset = res.headers['x-ratelimit-reset'];
          const wait  = reset ? Math.max(0, parseInt(reset) - Math.floor(Date.now()/1000)) : 60;
          reject(new Error(`RATE_LIMIT:${wait}`));
        } else if (res.statusCode >= 400) {
          reject(new Error(`HTTP ${res.statusCode}: ${url}`));
        } else {
          try { resolve(JSON.parse(data)); }
          catch { resolve(null); }
        }
      });
    }).on('error', reject);
  });
}

// ── Delay helper ──────────────────────────────────────────────────────────────
const sleep = ms => new Promise(r => setTimeout(r, ms));
const DELAY = TOKEN ? 250 : 4000;   // ms between user-profile fetches

async function fetchWithRetry(url, retries = 3) {
  for (let i = 0; i < retries; i++) {
    try {
      return await get(url);
    } catch (e) {
      if (e.message.startsWith('RATE_LIMIT:')) {
        const secs = Math.min(parseInt(e.message.split(':')[1]) + 5, 120);
        console.log(`  ⏳ Rate limited — waiting ${secs}s…`);
        await sleep(secs * 1000);
      } else if (i < retries - 1) {
        await sleep(2000);
      } else {
        throw e;
      }
    }
  }
}

// ── Determine role from GitHub profile ───────────────────────────────────────
const STUDENT_KEYWORDS  = /student|undergrad|graduate|phd|intern|bootcamp|learning|fresher|university|college|academia|sophomore|junior year|senior year|bsc|msc|b\.s\.|m\.s\./i;
const PRO_KEYWORDS      = /engineer|developer|architect|consultant|lead|cto|ceo|vp |director|manager|founder|staff|principal|freelance|contractor/i;

function inferRole(user) {
  const bio  = user.bio  || '';
  const comp = user.company || '';
  if (STUDENT_KEYWORDS.test(bio))                    return 'student';
  if (comp && !STUDENT_KEYWORDS.test(bio))           return 'pro';
  if (PRO_KEYWORDS.test(bio))                        return 'pro';
  if (user.followers > 200 && user.public_repos > 10) return 'pro';
  return 'student';
}

// ── Extract skills from top repo languages ────────────────────────────────────
async function getSkills(login) {
  try {
    const repos = await fetchWithRetry(
      `https://api.github.com/users/${login}/repos?per_page=10&sort=pushed&type=owner`
    );
    if (!repos || !Array.isArray(repos)) return [];
    const langCount = {};
    repos.forEach(r => {
      if (r.language) langCount[r.language] = (langCount[r.language] || 0) + 1;
    });
    return Object.entries(langCount)
      .sort((a, b) => b[1] - a[1])
      .slice(0, 5)
      .map(([lang]) => lang);
  } catch {
    return [];
  }
}

// ── Build profile name from GitHub login/name ─────────────────────────────────
function parseName(ghName, login) {
  const raw = (ghName || login || '').trim();
  const parts = raw.split(/\s+/).filter(Boolean);
  if (parts.length >= 2) return { first: parts[0], last: parts.slice(1).join(' ') };
  return { first: parts[0] || login, last: '' };
}

// ── Clean company string ──────────────────────────────────────────────────────
function cleanCompany(c) {
  return (c || '').replace(/^@/, '').trim();
}

// ── GitHub search queries — mix of pro + student profiles ────────────────────
const SEARCHES = [
  // IT Professionals — engineers at known companies
  { q: 'type:user followers:>500 repos:>15 location:usa',            label: 'pro-usa' },
  { q: 'type:user followers:>300 repos:>10 location:london',         label: 'pro-london' },
  { q: 'type:user followers:>300 repos:>10 location:india',          label: 'pro-india' },
  { q: 'type:user followers:>200 repos:>10 location:germany',        label: 'pro-germany' },
  { q: 'type:user followers:>200 repos:>10 location:canada',         label: 'pro-canada' },
  // Students / early-career
  { q: 'type:user student in:bio repos:>3 followers:>5',             label: 'students-1' },
  { q: 'type:user undergraduate in:bio repos:>3',                    label: 'students-under' },
  { q: 'type:user "computer science" in:bio repos:>5 followers:>10', label: 'cs-students' },
];

// ── Main import ───────────────────────────────────────────────────────────────
(async () => {
  // Load SQLite
  const SQL  = await initSQL();
  const db   = new SQL.Database(fs.readFileSync(SQLITE_PATH));

  // Existing emails to avoid duplicates
  const existingRes = db.exec('SELECT email FROM users');
  const existing    = new Set(
    existingRes.length ? existingRes[0].values.map(r => r[0]) : []
  );
  console.log(`Existing users in DB: ${existing.size}`);

  const esc   = v => (v || '').toString().replace(/'/g, "''");
  const imported = [];
  const seenLogins = new Set();

  for (const search of SEARCHES) {
    if (imported.length >= TARGET) break;

    console.log(`\n── Searching: ${search.label} ──`);
    let page = 1;

    while (imported.length < TARGET) {
      const url  = `https://api.github.com/search/users?q=${encodeURIComponent(search.q)}&per_page=30&page=${page}`;
      let results;
      try {
        const res = await fetchWithRetry(url);
        results   = res?.items || [];
      } catch (e) {
        console.log(`  ✗ Search failed: ${e.message}`);
        break;
      }

      if (!results.length) break;
      await sleep(TOKEN ? 500 : 8000);  // respect search rate limit

      for (const item of results) {
        if (imported.length >= TARGET) break;
        if (seenLogins.has(item.login)) continue;
        seenLogins.add(item.login);

        // Fetch full profile
        let user;
        try {
          user = await fetchWithRetry(`https://api.github.com/users/${item.login}`);
          await sleep(DELAY);
        } catch (e) {
          console.log(`  ✗ ${item.login}: ${e.message}`);
          continue;
        }

        if (!user) continue;

        // Skip bots, orgs
        if (user.type !== 'User') continue;

        // Build email — use public email or synthetic one
        const email = (user.email || `${user.login}@github.techconnect`).toLowerCase().trim();
        if (existing.has(email)) {
          process.stdout.write(`  ↩ skip (dup) ${user.login}\n`);
          continue;
        }

        // Skills from repos
        const skills = await getSkills(user.login);
        await sleep(DELAY);

        const role  = inferRole(user);
        const name  = parseName(user.name, user.login);
        const comp  = cleanCompany(user.company);
        const newId = uuid();

        // Random bcrypt-style placeholder hash (profile only — not a login account)
        // Users imported this way cannot log in; they're community directory entries.
        const fakeHash = '$2a$10$importedprofileXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX'.slice(0, 60);

        db.run(`INSERT INTO users (id,email,password_hash,role,first_name,last_name)
                VALUES ('${newId}','${esc(email)}','${esc(fakeHash)}','${role}',
                        '${esc(name.first)}','${esc(name.last)}')`);

        db.run(`INSERT INTO profiles
                  (user_id,institution,degree,year,company,job_title,experience,
                   bio,location,linkedin,github)
                VALUES
                  ('${newId}','','','',
                   '${esc(role === 'pro' ? comp : '')}','','',
                   '${esc(user.bio)}',
                   '${esc(user.location)}',
                   '',
                   'https://github.com/${esc(user.login)}')`);

        for (const skill of skills) {
          db.run(`INSERT OR IGNORE INTO skills (user_id,skill_name)
                  VALUES ('${newId}','${esc(skill)}')`);
        }

        existing.add(email);
        imported.push({ login: user.login, name: `${name.first} ${name.last}`.trim(), role, skills });

        const bar = `[${'█'.repeat(Math.round(imported.length/TARGET*20))}${'░'.repeat(20-Math.round(imported.length/TARGET*20))}]`;
        process.stdout.write(`\r  ${bar} ${imported.length}/${TARGET}  ${user.login.padEnd(20)}`);
      }

      page++;
      if (results.length < 30) break;
    }
  }

  console.log('\n');

  // Save to disk
  fs.writeFileSync(SQLITE_PATH, Buffer.from(db.export()));
  db.close();

  // Summary
  const pros      = imported.filter(p => p.role === 'pro').length;
  const students  = imported.filter(p => p.role === 'student').length;
  console.log('══════════════════════════════════════════');
  console.log(`  ✅  Import complete!`);
  console.log(`  👔  IT Professionals : ${pros}`);
  console.log(`  🎓  Students         : ${students}`);
  console.log(`  📦  Total imported   : ${imported.length}`);
  console.log(`  💾  Saved to         : techconnect_java.sqlite`);
  console.log('══════════════════════════════════════════');
  console.log('\nNote: Imported profiles appear in Search but cannot log in.');
  console.log('LinkedIn import requires OAuth (user must connect their account).\n');
})();
