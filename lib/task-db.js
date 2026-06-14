/**
 * Shared helpers for normalizing task rows from SQLite.
 */

function formatTaskRow(row) {
  if (!row) return null;

  return {
    ...row,
    completed: row.completed === 1,
    omitted: row.omitted === 1
  };
}

module.exports = {
  formatTaskRow
};
