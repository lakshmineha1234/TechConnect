const express = require('express');
const { users, profiles } = require('../database');

const router = express.Router();

function requireAuth(req, res, next) {
  if (!req.session.userId) return res.status(401).json({ error: 'Not authenticated.' });
  next();
}

// GET /api/profile
router.get('/', requireAuth, async (req, res) => {
  try {
    const uid = req.session.userId;

    const [user, profile] = await Promise.all([
      users.findOneAsync({ _id: uid }),
      profiles.findOneAsync({ user_id: uid }),
    ]);

    if (!user || !profile) return res.status(404).json({ error: 'Profile not found.' });

    res.json({
      firstName:   user.first_name,
      lastName:    user.last_name,
      email:       user.email,
      role:        user.role,
      phone:       profile.phone        || '',
      location:    profile.location     || '',
      bio:         profile.bio          || '',
      institution: profile.institution  || '',
      degree:      profile.degree       || '',
      year:        profile.year         || '',
      company:     profile.company      || '',
      jobTitle:    profile.job_title    || '',
      experience:  profile.experience   || '',
      linkedin:    profile.linkedin     || '',
      github:      profile.github       || '',
      skills:      profile.skills       || [],
    });
  } catch (err) {
    console.error('profile GET error:', err);
    res.status(500).json({ error: 'Failed to load profile.' });
  }
});

// PUT /api/profile
router.put('/', requireAuth, async (req, res) => {
  try {
    const uid = req.session.userId;
    const {
      firstName, lastName, email,
      phone, location, bio,
      institution, degree, year,
      company, jobTitle, experience,
      linkedin, github, skills,
    } = req.body;

    const userUpdate = {};
    if (firstName !== undefined) userUpdate.first_name = firstName.trim();
    if (lastName  !== undefined) userUpdate.last_name  = lastName.trim();
    if (email     !== undefined) userUpdate.email      = email.trim().toLowerCase();

    if (Object.keys(userUpdate).length > 0) {
      await users.updateAsync({ _id: uid }, { $set: userUpdate });
    }

    const profileUpdate = { updated_at: new Date() };
    if (phone       !== undefined) profileUpdate.phone       = phone       || '';
    if (location    !== undefined) profileUpdate.location    = location    || '';
    if (bio         !== undefined) profileUpdate.bio         = bio         || '';
    if (institution !== undefined) profileUpdate.institution = institution || '';
    if (degree      !== undefined) profileUpdate.degree      = degree      || '';
    if (year        !== undefined) profileUpdate.year        = year        || '';
    if (company     !== undefined) profileUpdate.company     = company     || '';
    if (jobTitle    !== undefined) profileUpdate.job_title   = jobTitle    || '';
    if (experience  !== undefined) profileUpdate.experience  = experience  || '';
    if (linkedin    !== undefined) profileUpdate.linkedin    = linkedin    || '';
    if (github      !== undefined) profileUpdate.github      = github      || '';
    if (Array.isArray(skills))    profileUpdate.skills      = skills.map(s => s.trim()).filter(Boolean);

    await profiles.updateAsync({ user_id: uid }, { $set: profileUpdate });

    res.json({ ok: true });
  } catch (err) {
    console.error('profile PUT error:', err);
    res.status(500).json({ error: 'Failed to save profile.' });
  }
});

module.exports = router;
