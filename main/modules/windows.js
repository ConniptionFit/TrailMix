/** @module windows — Hub, Meeting, Mini window management */
module.exports = {
  id: 'windows',
  version: '1.0.0',
  channels: [
    'app:minimize',
    'app:relaunch',
    'app:get-version'
  ],
  register(ctx) {
    ctx.windows = {
      createHub: ctx.runtime.createHubWindow,
      openMeeting: ctx.runtime.openMeetingWindow,
      getHub: ctx.runtime.getHubWindow,
      setupTray: ctx.runtime.setupTray
    };
  }
};
