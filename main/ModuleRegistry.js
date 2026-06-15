/**
 * TrailMix Module Registry — plugin system for expandable functionality.
 *
 * Modules export:
 *   id: string
 *   version: string
 *   channels?: string[]       — IPC channels owned by this module
 *   dependencies?: string[]   — other module ids required first
 *   register(ctx)            — called before IPC wiring (optional)
 *   registerIpc?(ctx)         — register IPC handlers (optional, future split)
 *   onReady?(ctx)             — called after app.whenReady init (optional)
 *   onBeforeQuit?(ctx)        — cleanup hook (optional)
 */
class ModuleRegistry {
  constructor() {
    this.modules = new Map();
    this.order = [];
  }

  register(mod) {
    if (!mod?.id) throw new Error('Module must have an id');
    if (this.modules.has(mod.id)) {
      console.warn(`[ModuleRegistry] Module "${mod.id}" already registered — skipping`);
      return;
    }
    this.modules.set(mod.id, mod);
    this.order.push(mod.id);
  }

  resolveOrder() {
    const resolved = [];
    const visiting = new Set();
    const visited = new Set();

    const visit = (id) => {
      if (visited.has(id)) return;
      if (visiting.has(id)) throw new Error(`Circular module dependency: ${id}`);
      visiting.add(id);
      const mod = this.modules.get(id);
      for (const dep of mod.dependencies || []) {
        if (!this.modules.has(dep)) {
          throw new Error(`Module "${id}" depends on unknown module "${dep}"`);
        }
        visit(dep);
      }
      visiting.delete(id);
      visited.add(id);
      resolved.push(id);
    };

    for (const id of this.order) visit(id);
    return resolved.map((id) => this.modules.get(id));
  }

  registerAll(ctx) {
    for (const mod of this.resolveOrder()) {
      if (typeof mod.register === 'function') {
        mod.register(ctx);
      }
    }
  }

  registerAllIpc(ctx) {
    for (const mod of this.resolveOrder()) {
      if (typeof mod.registerIpc === 'function') {
        mod.registerIpc(ctx);
      }
    }
  }

  async runOnReady(ctx) {
    for (const mod of this.resolveOrder()) {
      if (typeof mod.onReady === 'function') {
        await mod.onReady(ctx);
      }
    }
  }

  runOnBeforeQuit(ctx) {
    for (const mod of this.resolveOrder()) {
      if (typeof mod.onBeforeQuit === 'function') {
        mod.onBeforeQuit(ctx);
      }
    }
  }

  list() {
    return this.resolveOrder().map((m) => ({
      id: m.id,
      version: m.version || '0.0.0',
      channels: m.channels || []
    }));
  }
}

module.exports = { ModuleRegistry };
