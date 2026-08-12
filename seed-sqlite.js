/**
 * seed-sqlite.js
 * Inserts the 6 demo profiles into techconnect_java.sqlite
 * if they don't already exist (safe to run multiple times).
 */

const fs      = require('fs');
const path    = require('path');
const initSQL = require('sql.js');
const bcrypt  = require('bcryptjs');

const SQLITE_PATH = path.join(__dirname, 'techconnect_java.sqlite');

function uuidv4() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
    const r = Math.random() * 16 | 0;
    return (c === 'x' ? r : (r & 0x3 | 0x8)).toString(16);
  });
}

const SEEDS = [
  { firstName:'Emma',   lastName:'Thompson', email:'emma@demo.tc',   role:'student',
    institution:'MIT',               degree:'B.Sc. Computer Science', year:'3rd Year',
    company:'', jobTitle:'', experience:'',
    bio:'Passionate about ML and AI-driven products. Looking to connect with industry mentors.',
    location:'Cambridge, MA',    skills:['Python','Machine Learning','React','TensorFlow'],
    linkedin:'https://linkedin.com/in/emma-demo',   github:'' },

  { firstName:'Liam',   lastName:'Park',     email:'liam@demo.tc',   role:'pro',
    institution:'', degree:'', year:'',
    company:'Google', jobTitle:'Senior Software Engineer', experience:'5–10 years',
    bio:'Senior engineer specialising in distributed systems and cloud-native architecture.',
    location:'Mountain View, CA', skills:['Java','Kubernetes','GCP','Go'],
    linkedin:'https://linkedin.com/in/liam-demo',   github:'' },

  { firstName:'Priya',  lastName:'Sharma',   email:'priya@demo.tc',  role:'student',
    institution:'Stanford University', degree:'M.Sc. Data Science', year:'Postgraduate',
    company:'', jobTitle:'', experience:'',
    bio:'Data science student with a love for visualisation and storytelling through data.',
    location:'Palo Alto, CA',    skills:['Python','SQL','Tableau','R'],
    linkedin:'', github:'https://github.com/priya-demo' },

  { firstName:'Marcus', lastName:'Reid',     email:'marcus@demo.tc', role:'pro',
    institution:'', degree:'', year:'',
    company:'Microsoft', jobTitle:'Cloud Architect', experience:'10+ years',
    bio:'Full-stack developer and cloud architect focused on .NET and Azure solutions.',
    location:'Redmond, WA',      skills:['C#','.NET','Azure','TypeScript'],
    linkedin:'https://linkedin.com/in/marcus-demo',  github:'' },

  { firstName:'Sofia',  lastName:'Chen',     email:'sofia@demo.tc',  role:'student',
    institution:'UC Berkeley', degree:'B.Sc. Information Security', year:'4th Year',
    company:'', jobTitle:'', experience:'',
    bio:'Cybersecurity enthusiast and CTF player, interning at a security consultancy.',
    location:'Berkeley, CA',     skills:['Python','Cybersecurity','Linux','Networking'],
    linkedin:'', github:'https://github.com/sofia-demo' },

  { firstName:'James',  lastName:'Okafor',   email:'james@demo.tc',  role:'pro',
    institution:'', degree:'', year:'',
    company:'Amazon', jobTitle:'DevOps Engineer', experience:'3–5 years',
    bio:'DevOps engineer passionate about infrastructure-as-code and fast deployments.',
    location:'Seattle, WA',      skills:['DevOps','Terraform','Docker','AWS'],
    linkedin:'https://linkedin.com/in/james-demo',   github:'' },
];

(async () => {
  const hash = await bcrypt.hash('demo123', 10);
  const SQL  = await initSQL();
  const db   = new SQL.Database(fs.readFileSync(SQLITE_PATH));

  let inserted = 0, skipped = 0;

  for (const s of SEEDS) {
    const q    = s.email.replace(/'/g, "''");
    const chk  = db.exec(`SELECT id FROM users WHERE email = '${q}'`);
    if (chk.length > 0 && chk[0].values.length > 0) {
      console.log(`  SKIP  ${s.email}`);
      skipped++; continue;
    }

    const id = uuidv4();
    const esc = v => (v || '').replace(/'/g, "''");

    db.run(`INSERT INTO users (id,email,password_hash,role,first_name,last_name)
            VALUES ('${id}','${esc(s.email)}','${esc(hash)}','${s.role}',
                    '${esc(s.firstName)}','${esc(s.lastName)}')`);

    db.run(`INSERT INTO profiles
              (user_id,institution,degree,year,company,job_title,experience,
               bio,location,linkedin,github)
            VALUES
              ('${id}','${esc(s.institution)}','${esc(s.degree)}','${esc(s.year)}',
               '${esc(s.company)}','${esc(s.jobTitle)}','${esc(s.experience)}',
               '${esc(s.bio)}','${esc(s.location)}','${esc(s.linkedin)}','${esc(s.github)}')`);

    for (const skill of s.skills) {
      db.run(`INSERT OR IGNORE INTO skills (user_id,skill_name)
              VALUES ('${id}','${skill.replace(/'/g,"''")}')`);
    }

    console.log(`  OK    ${s.email}`);
    inserted++;
  }

  fs.writeFileSync(SQLITE_PATH, Buffer.from(db.export()));
  db.close();
  console.log(`\nSeeding complete: ${inserted} inserted, ${skipped} skipped.`);
})();
