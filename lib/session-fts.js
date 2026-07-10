/**
 * SQLite FTS5 helpers for offline transcript / notes search.
 * Indexes plaintext only — never ciphertext.
 */

function buildSessionFtsBody(session) {
  if (!session) return '';
  const transcript = Array.isArray(session.transcript)
    ? session.transcript
      .map((segment) => {
        const speaker = segment.speaker || 'Speaker';
        const stamp = segment.timestamp || '';
        const text = segment.text || '';
        return `[${stamp}] ${speaker}: ${text}`;
      })
      .join('\n')
    : '';

  return [
    session.title,
    session.description,
    session.summary,
    session.mixNotes,
    session.enhancedNotes,
    session.actionItems,
    Array.isArray(session.tags) ? session.tags.join(' ') : '',
    transcript
  ]
    .filter(Boolean)
    .join('\n')
    .trim();
}

/**
 * Convert a user search string into a safe FTS5 MATCH query.
 * Uses prefix matching on each token for friendlier UX.
 */
function buildFtsMatchQuery(rawTerm) {
  const term = String(rawTerm || '').trim();
  if (!term) return '';

  // Quoted phrase → keep as phrase query.
  const phraseMatch = term.match(/^"([^"]+)"$/);
  if (phraseMatch) {
    const phrase = phraseMatch[1].replace(/"/g, ' ').trim();
    return phrase ? `"${phrase}"` : '';
  }

  const tokens = term
    .replace(/[^\p{L}\p{N}\s_-]+/gu, ' ')
    .split(/\s+/)
    .map((t) => t.trim())
    .filter((t) => t.length >= 2);

  if (!tokens.length) return '';

  return tokens
    .map((token) => {
      const safe = token.replace(/"/g, '');
      return `${safe}*`;
    })
    .join(' AND ');
}

function createSessionFtsHelpers({ dbRun, dbGet, dbAll }) {
  let ftsReady = false;

  async function ensureFtsTables() {
    if (ftsReady) return true;
    try {
      await dbRun('CREATE VIRTUAL TABLE IF NOT EXISTS _fts5_probe USING fts5(x)');
      await dbRun('DROP TABLE IF EXISTS _fts5_probe');
      await dbRun(`
        CREATE TABLE IF NOT EXISTS session_fts_map (
          session_id TEXT PRIMARY KEY,
          rowid INTEGER NOT NULL UNIQUE
        )
      `);
      await dbRun(`
        CREATE VIRTUAL TABLE IF NOT EXISTS sessions_fts USING fts5(
          body,
          tokenize = 'porter unicode61'
        )
      `);
      ftsReady = true;
      return true;
    } catch (err) {
      console.error('FTS5 unavailable — transcript search disabled:', err.message);
      ftsReady = false;
      return false;
    }
  }

  async function deleteSessionFts(sessionId) {
    if (!(await ensureFtsTables())) return;
    const map = await dbGet('SELECT rowid FROM session_fts_map WHERE session_id = ?', [sessionId]);
    if (!map) return;
    await dbRun('DELETE FROM sessions_fts WHERE rowid = ?', [map.rowid]);
    await dbRun('DELETE FROM session_fts_map WHERE session_id = ?', [sessionId]);
  }

  async function upsertSessionFts(session) {
    if (!session?.id) return;
    if (!(await ensureFtsTables())) return;

    const body = buildSessionFtsBody(session);
    if (!body) {
      await deleteSessionFts(session.id);
      return;
    }

    const existing = await dbGet('SELECT rowid FROM session_fts_map WHERE session_id = ?', [session.id]);
    if (existing?.rowid != null) {
      await dbRun('DELETE FROM sessions_fts WHERE rowid = ?', [existing.rowid]);
      await dbRun('DELETE FROM session_fts_map WHERE session_id = ?', [session.id]);
    }

    const insert = await dbRun('INSERT INTO sessions_fts(body) VALUES (?)', [body]);
    const rowid = insert.lastID;
    await dbRun(
      'INSERT OR REPLACE INTO session_fts_map (session_id, rowid) VALUES (?, ?)',
      [session.id, rowid]
    );
  }

  async function searchSessionsFts(rawTerm, { limit = 200 } = {}) {
    if (!(await ensureFtsTables())) return [];
    const matchQuery = buildFtsMatchQuery(rawTerm);
    if (!matchQuery) return [];

    try {
      return await dbAll(`
        SELECT
          m.session_id AS id,
          snippet(sessions_fts, 0, '[[', ']]', '…', 48) AS snippet,
          bm25(sessions_fts) AS rank
        FROM sessions_fts
        JOIN session_fts_map m ON m.rowid = sessions_fts.rowid
        WHERE sessions_fts MATCH ?
        ORDER BY rank
        LIMIT ?
      `, [matchQuery, limit]);
    } catch (err) {
      console.error('FTS search failed:', err.message);
      return [];
    }
  }

  async function rebuildAllSessionsFts(loadSessionById) {
    if (!(await ensureFtsTables())) return { indexed: 0 };
    const rows = await dbAll('SELECT id, encrypted FROM sessions');
    let indexed = 0;
    for (const row of rows) {
      if (row.encrypted === 1) {
        await deleteSessionFts(row.id);
        continue;
      }
      try {
        const session = await loadSessionById(row.id);
        if (session) {
          await upsertSessionFts(session);
          indexed += 1;
        }
      } catch (err) {
        console.error(`FTS rebuild failed for ${row.id}`, err.message);
      }
    }
    return { indexed };
  }

  return {
    ensureFtsTables,
    upsertSessionFts,
    deleteSessionFts,
    searchSessionsFts,
    rebuildAllSessionsFts,
    buildSessionFtsBody,
    buildFtsMatchQuery
  };
}

module.exports = {
  buildSessionFtsBody,
  buildFtsMatchQuery,
  createSessionFtsHelpers
};
