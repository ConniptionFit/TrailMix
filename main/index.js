/**
 * TrailMix v0.5 — Modular main process entry point.
 *
 * Boot sequence:
 *   1. Load runtime core (services, state, business logic)
 *   2. Create AppContext + register built-in modules
 *   3. Wire IPC handlers
 *   4. Register lifecycle hooks
 */
const runtime = require('./core/runtime');
const { createAppContext } = require('./context/AppContext');
const { loadBuiltinModules } = require('./modules');
const { registerIpcHandlers } = require('./ipc/register-all');
const { registerLifecycle } = require('./lifecycle');

const ctx = createAppContext(runtime);
loadBuiltinModules(ctx.registry);
ctx.registry.registerAll(ctx);

registerIpcHandlers(runtime);
registerLifecycle(runtime, ctx);

if (process.env.TRAILMIX_DEBUG_MODULES) {
  console.log('[TrailMix] Registered modules:', ctx.registry.list());
}

module.exports = { ctx, runtime };
