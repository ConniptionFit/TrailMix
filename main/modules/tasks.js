/** @module tasks — global action items */
module.exports = {
  id: 'tasks',
  version: '1.0.0',
  channels: [
    'tasks:get',
    'tasks:toggle',
    'tasks:delete',
    'tasks:set-omitted',
    'tasks:delete-multiple'
  ],
  register(ctx) {
    ctx.tasks = {
      list: ctx.runtime.getTasksListFromDb,
      save: ctx.runtime.saveTaskToDb,
      extract: ctx.runtime.extractTasksFromActionItems
    };
  }
};
