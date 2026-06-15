/** @module workflow — event bus webhook triggers */
module.exports = {
  id: 'workflow',
  version: '1.0.0',
  channels: [
    'workflow:register-trigger',
    'workflow:emit'
  ]
};
