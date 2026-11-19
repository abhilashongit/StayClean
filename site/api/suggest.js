// Anonymous idea box: turns a plain form post into a GitHub issue.
// The token lives only in the GITHUB_TOKEN environment variable on Vercel.
// Nothing about the visitor (name, email, IP) is read into the issue.
const REPO = process.env.GITHUB_REPO || 'abhilashongit/StayClean';
const hits = new Map();                       // best-effort throttle, per warm instance

module.exports = async (req, res) => {
  res.setHeader('Cache-Control', 'no-store');
  if (req.method !== 'POST') { res.setHeader('Allow', 'POST'); return res.status(405).json({ error: 'method' }); }

  const token = process.env.GITHUB_TOKEN;
  if (!token) return res.status(503).json({ error: 'not_configured' });

  let b = req.body;
  if (typeof b === 'string') { try { b = JSON.parse(b); } catch (e) { b = {}; } }
  b = b || {};

  if (b.website) return res.status(200).json({ ok: true });            // honeypot: pretend success
  const age = Date.now() - Number(b.t || 0);
  if (!(age > 2500 && age < 6 * 3600 * 1000)) return res.status(400).json({ error: 'timing' });

  const idea = String(b.idea || '').replace(/\r\n/g, '\n').trim();
  if (idea.length < 10 || idea.length > 1200) return res.status(400).json({ error: 'length' });
  if ((idea.match(/https?:\/\//gi) || []).length > 2) return res.status(400).json({ error: 'links' });

  const ip = String((req.headers['x-forwarded-for'] || '').split(',')[0] || 'x');
  const now = Date.now();
  const recent = (hits.get(ip) || []).filter(t => now - t < 3600 * 1000);
  if (recent.length >= 3) return res.status(429).json({ error: 'rate' });
  recent.push(now); hits.set(ip, recent);
  if (hits.size > 500) hits.clear();

  // keep GitHub from notifying anyone who happens to be @mentioned
  const safe = idea.replace(/@(?=[A-Za-z0-9])/g, '@​');
  const first = safe.split('\n')[0].replace(/\s+/g, ' ');
  const title = 'Idea: ' + (first.length > 70 ? first.slice(0, 67) + '...' : first);
  const body = safe + '\n\n---\n_Sent anonymously from the Stay Clean website. No name or contact details were collected._';

  try {
    const r = await fetch('https://api.github.com/repos/' + REPO + '/issues', {
      method: 'POST',
      headers: {
        Authorization: 'Bearer ' + token,
        Accept: 'application/vnd.github+json',
        'X-GitHub-Api-Version': '2022-11-28',
        'User-Agent': 'stayclean-site-idea-box',
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({ title, body, labels: ['idea'] })
    });
    if (!r.ok) return res.status(502).json({ error: 'github', status: r.status });
    const j = await r.json();
    return res.status(200).json({ ok: true, url: j.html_url, number: j.number });
  } catch (e) {
    return res.status(502).json({ error: 'network' });
  }
};
