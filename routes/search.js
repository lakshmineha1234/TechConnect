const express = require('express');
const { users, profiles } = require('../database');

const router = express.Router();

/**
 * GET /api/search
 * Query params:
 *   q     – free-text search (name, bio, institution, company, location, skills)
 *   role  – 'all' | 'student' | 'pro'   (default: 'all')
 *   limit – max results (default 20, max 50)
 */
router.get('/', async (req, res) => {
  try {
    const q     = (req.query.q     || '').trim().toLowerCase();
    const role  = (req.query.role  || 'all');
    const limit = Math.min(parseInt(req.query.limit) || 20, 50);

    // ── 1. Fetch users (optionally filtered by role) ──────────────────────────
    const userQuery = (role !== 'all') ? { role } : {};
    const allUsers  = await users.findAsync(userQuery);

    // ── 2. Fetch profiles for those users in one pass ─────────────────────────
    const userIds    = allUsers.map(u => u._id);
    const allProfs   = await profiles.findAsync({ user_id: { $in: userIds } });
    const profileMap = Object.fromEntries(allProfs.map(p => [p.user_id, p]));

    // ── 3. Filter by free-text query ──────────────────────────────────────────
    const matched = q
      ? allUsers.filter(u => {
          const p   = profileMap[u._id] || {};
          const hay = [
            u.first_name, u.last_name,
            p.bio, p.institution, p.company, p.location,
            ...(p.skills || []),
          ].filter(Boolean).join(' ').toLowerCase();
          return hay.includes(q);
        })
      : allUsers;

    // ── 4. Format and return ──────────────────────────────────────────────────
    const results = matched.slice(0, limit).map(u => formatResult(u, profileMap[u._id]));
    res.json({ results, total: results.length });

  } catch (err) {
    console.error('search error:', err);
    res.status(500).json({ error: 'Search failed.' });
  }
});

function formatResult(user, profile = {}) {
  const name = [user.first_name, user.last_name].filter(Boolean).join(' ') || user.email;
  return {
    id:          user._id,
    name,
    firstName:   user.first_name  || '',
    lastName:    user.last_name   || '',
    role:        user.role,
    bio:         profile.bio          || '',
    institution: profile.institution  || '',
    company:     profile.company      || '',
    location:    profile.location     || '',
    skills:      profile.skills       || [],
  };
}

module.exports = router;
