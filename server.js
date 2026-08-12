const express = require('express');
const session = require('express-session');
const path    = require('path');

const { init: initDb } = require('./database');
const authRoutes       = require('./routes/auth');
const profileRoutes    = require('./routes/profile');
const searchRoutes     = require('./routes/search');

const app  = express();
const PORT = process.env.PORT || 3000;

app.use(express.json());
app.use(express.urlencoded({ extended: true }));

app.use(session({
  secret:            process.env.SESSION_SECRET || 'tc-dev-secret-change-in-production',
  resave:            false,
  saveUninitialized: false,
  cookie: {
    secure:   false,                    // set true behind HTTPS in production
    httpOnly: true,
    maxAge:   7 * 24 * 60 * 60 * 1000, // 7 days
  },
}));

app.use('/api/auth',    authRoutes);
app.use('/api/profile', profileRoutes);
app.use('/api/search',  searchRoutes);

app.use(express.static(path.join(__dirname)));

app.get('*', (_req, res) => {
  res.sendFile(path.join(__dirname, 'index.html'));
});

(async () => {
  await initDb();
  app.listen(PORT, () => {
    console.log(`TechConnect running at http://localhost:${PORT}`);
  });
})();
