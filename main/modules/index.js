/**
 * Built-in module manifest — register all core domains.
 */
const audio = require('./audio');
const settings = require('./settings');
const sessions = require('./sessions');
const meetings = require('./meetings');
const calls = require('./calls');
const chat = require('./chat');
const tasks = require('./tasks');
const folders = require('./folders');
const processing = require('./processing');
const updates = require('./updates');
const models = require('./models');
const windows = require('./windows');
const workflow = require('./workflow');

const BUILTIN_MODULES = [
  windows,
  settings,
  audio,
  sessions,
  meetings,
  calls,
  chat,
  tasks,
  folders,
  processing,
  models,
  updates,
  workflow
];

function loadBuiltinModules(registry) {
  for (const mod of BUILTIN_MODULES) {
    registry.register(mod);
  }
}

module.exports = { loadBuiltinModules, BUILTIN_MODULES };
