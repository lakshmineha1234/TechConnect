const express = require('express');
const bcrypt  = require('bcryptjs');
const { users, profiles } = require('../database');

const router = express.Router();

// POST /api/auth/register
router.post('/register', async (req, res) => {
  try {
    const { firstName, lastName, email, password, role, institution, company } = req.body;

    if (!firstName || !email || !password || !role) {
      return res.status(400).json({ error: 'Missing required fields.' });
    }
    if (!['student', 'pro'].includes(role)) {
      return res.status(400).json({ error: 'Invalid role.' });
    }

    const existing = await users.findOneAsync({ email: email.trim().toLowerCase() });
    if (existing) {
      return res.status(409).json({ error: 'An account with that email already exists.' });
    }

    const hash = await bcrypt.hash(password, 12);
    const user = await users.insertAsync({
      email:         email.trim().toLowerCase(),
      password_hash: hash,
      role,
      first_name:    firstName.trim(),
      last_name:     (lastName || '').trim(),
      created_at:    new Date(),
    });

    await profiles.insertAsync({
      user_id:     user._id,
      phone:       '',
      location:    '',
      bio:         '',
      institution: (institution || '').trim(),
      degree:      '',
      year:        '',
      company:     (company || '').trim(),
      job_title:   '',
      experience:  '',
      linkedin:    '',
      github:      '',
      skills:      [],
      updated_at:  new Date(),
    });

    req.session.userId = user._id;
    res.status(201).json({ user: formatUser(user) });
  } catch (err) {
    console.error('register error:', err);
    res.status(500).json({ error: 'Registration failed. Please try again.' });
  }
});

// POST /api/auth/login
router.post('/login', async (req, res) => {
  try {
    const { email, password } = req.body;

    if (!email || !password) {
      return res.status(400).json({ error: 'Email and password are required.' });
    }

    const user = await users.findOneAsync({ email: email.trim().toLowerCase() });
    if (!user) {
      return res.status(401).json({ error: 'Invalid email or password.' });
    }

    const match = await bcrypt.compare(password, user.password_hash);
    if (!match) {
      return res.status(401).json({ error: 'Invalid email or password.' });
    }

    req.session.userId = user._id;
    res.json({ user: formatUser(user) });
  } catch (err) {
    console.error('login error:', err);
    res.status(500).json({ error: 'Login failed. Please try again.' });
  }
});

// POST /api/auth/logout
router.post('/logout', (req, res) => {
  req.session.destroy(() => res.json({ ok: true }));
});

// GET /api/auth/me
router.get('/me', async (req, res) => {
  if (!req.session.userId) {
    return res.status(401).json({ error: 'Not authenticated.' });
  }
  try {
    const user = await users.findOneAsync({ _id: req.session.userId });
    if (!user) {
      req.session.destroy(() => {});
      return res.status(401).json({ error: 'User not found.' });
    }
    res.json({ user: formatUser(user) });
  } catch (err) {
    console.error('me error:', err);
    res.status(500).json({ error: 'Server error.' });
  }
});

function formatUser(user) {
  return {
    id:        user._id,
    email:     user.email,
    role:      user.role,
    firstName: user.first_name,
    lastName:  user.last_name,
    name:      [user.first_name, user.last_name].filter(Boolean).join(' ') || user.email,
  };
}

module.exports = router;
