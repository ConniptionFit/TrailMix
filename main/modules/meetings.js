/** @module meetings — dual-window meeting lifecycle */
module.exports = {
  id: 'meetings',
  version: '1.0.0',
  dependencies: ['sessions', 'windows'],
  channels: [
    'meetings:new',
    'meetings:open',
    'meetings:focus'
  ],
  register(ctx) {
    ctx.meetings = {
      open: ctx.runtime.openMeetingWindow,
      create: ctx.runtime.createNewSession
    };
  }
};
