const Datastore = require('@seald-io/nedb');
const bcrypt    = require('bcryptjs');
const path      = require('path');
const fs        = require('fs');

const dataDir = path.join(__dirname, 'data');
if (!fs.existsSync(dataDir)) fs.mkdirSync(dataDir, { recursive: true });

const users    = new Datastore({ filename: path.join(dataDir, 'users.db'),    autoload: true });
const profiles = new Datastore({ filename: path.join(dataDir, 'profiles.db'), autoload: true });

async function init() {
  await users.ensureIndexAsync({ fieldName: 'email',   unique: true });
  await profiles.ensureIndexAsync({ fieldName: 'user_id', unique: true });
  await seed();
}

// ── Demo seed data (inserted once on first boot) ───────────────────────────────
const SEED_PROFILES = [
  {
    firstName: 'Emma', lastName: 'Thompson',
    email: 'emma@demo.tc', role: 'student',
    institution: 'MIT', bio: 'Passionate about machine learning and building AI-driven products. Looking to connect with industry mentors.',
    location: 'Cambridge, MA', skills: ['Python', 'Machine Learning', 'React', 'TensorFlow'],
    degree: 'B.Sc. Computer Science', year: '3rd Year', linkedin: 'https://linkedin.com/in/emma-demo',
  },
  {
    firstName: 'Liam', lastName: 'Park',
    email: 'liam@demo.tc', role: 'pro',
    company: 'Google', bio: 'Senior software engineer specialising in distributed systems and cloud-native architecture.',
    location: 'Mountain View, CA', skills: ['Java', 'Kubernetes', 'GCP', 'Go'],
    jobTitle: 'Senior Software Engineer', experience: '5–10 years', linkedin: 'https://linkedin.com/in/liam-demo',
  },
  {
    firstName: 'Priya', lastName: 'Sharma',
    email: 'priya@demo.tc', role: 'student',
    institution: 'Stanford University', bio: 'Data science student with a love for visualisation and storytelling through data.',
    location: 'Palo Alto, CA', skills: ['Python', 'SQL', 'Tableau', 'R'],
    degree: 'M.Sc. Data Science', year: 'Postgraduate', github: 'https://github.com/priya-demo',
  },
  {
    firstName: 'Marcus', lastName: 'Reid',
    email: 'marcus@demo.tc', role: 'pro',
    company: 'Microsoft', bio: 'Full-stack developer and cloud architect with a focus on .NET and Azure solutions.',
    location: 'Redmond, WA', skills: ['C#', '.NET', 'Azure', 'TypeScript'],
    jobTitle: 'Cloud Architect', experience: '10+ years', linkedin: 'https://linkedin.com/in/marcus-demo',
  },
  {
    firstName: 'Sofia', lastName: 'Chen',
    email: 'sofia@demo.tc', role: 'student',
    institution: 'UC Berkeley', bio: 'Cybersecurity enthusiast and CTF player. Interning at a security consultancy this summer.',
    location: 'Berkeley, CA', skills: ['Python', 'Cybersecurity', 'Linux', 'Networking'],
    degree: 'B.Sc. Information Security', year: '4th Year', github: 'https://github.com/sofia-demo',
  },
  {
    firstName: 'James', lastName: 'Okafor',
    email: 'james@demo.tc', role: 'pro',
    company: 'Amazon', bio: 'DevOps engineer passionate about infrastructure-as-code and helping teams ship faster.',
    location: 'Seattle, WA', skills: ['DevOps', 'Terraform', 'Docker', 'AWS'],
    jobTitle: 'DevOps Engineer', experience: '3–5 years', linkedin: 'https://linkedin.com/in/james-demo',
  },
];

async function seed() {
  const count = await users.countAsync({});
  if (count > 0) return;                                     // already seeded

  console.log('[DB] Seeding demo profiles...');
  const hash = await bcrypt.hash('demo123', 10);             // shared password for all seeds

  for (const s of SEED_PROFILES) {
    const user = await users.insertAsync({
      email:         s.email,
      password_hash: hash,
      role:          s.role,
      first_name:    s.firstName,
      last_name:     s.lastName,
      created_at:    new Date(),
    });

    await profiles.insertAsync({
      user_id:     user._id,
      phone:       '',
      location:    s.location    || '',
      bio:         s.bio         || '',
      institution: s.institution || '',
      degree:      s.degree      || '',
      year:        s.year        || '',
      company:     s.company     || '',
      job_title:   s.jobTitle    || '',
      experience:  s.experience  || '',
      linkedin:    s.linkedin    || '',
      github:      s.github      || '',
      skills:      s.skills      || [],
      updated_at:  new Date(),
    });
  }
  console.log(`[DB] Seeded ${SEED_PROFILES.length} demo profiles.`);
}

module.exports = { users, profiles, init };
