/**
 * Shared application context — DI surface for modules.
 * Expand services/paths here when modules need them; runtime remains the source of truth.
 */
const { ModuleRegistry } = require('../ModuleRegistry');

function createAppContext(runtime) {
  return {
    runtime,
    registry: new ModuleRegistry()
  };
}

module.exports = { createAppContext };
