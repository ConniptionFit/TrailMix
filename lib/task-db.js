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

function createTaskDbHelpers({ dbRun, dbAll }) {
  async function saveTaskToDb(task) {
    await dbRun(`INSERT INTO tasks (
      id, text, assignee, completed, dueDate, omitted,
      sourceCallId, sourceCallTitle, sourceSegmentId, sourceTimestamp
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`, [
      task.id,
      task.text,
      task.assignee || 'Unassigned',
      task.completed ? 1 : 0,
      task.dueDate || '',
      task.omitted ? 1 : 0,
      task.sourceCallId,
      task.sourceCallTitle || '',
      task.sourceSegmentId || '',
      task.sourceTimestamp || ''
    ]);
  }

  async function getTasksListFromDb() {
    try {
      const rows = await dbAll('SELECT * FROM tasks');
      return rows.map(formatTaskRow);
    } catch (err) {
      console.error('Error getting tasks from DB:', err);
      return [];
    }
  }

  return { saveTaskToDb, getTasksListFromDb };
}

module.exports = {
  formatTaskRow,
  createTaskDbHelpers
};
