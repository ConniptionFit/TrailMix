/** @module sessions — session payload CRUD */
module.exports = {
  id: 'sessions',
  version: '1.0.0',
  dependencies: ['settings'],
  channels: [
    'session:load-meeting',
    'session:set-title',
    'session:updated'
  ],
  register(ctx) {
    ctx.sessions = {
      get: ctx.runtime.getSession,
      set: ctx.runtime.setSession,
      create: ctx.runtime.createNewSession,
      load: ctx.runtime.loadSessionPayloadFromDb,
      save: ctx.runtime.saveSessionToDbPromise
    };
  }
};
