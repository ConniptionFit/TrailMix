/**
 * TrailMix Module Registry — plugin system for expandable functionality.
 *
 * Modules export:
 *   id: string
 *   version?: string
 *   channels?: string[]       — IPC channels owned by this module (metadata)
 *   dependencies?: string[]   — other module ids required first
 *   register?(ctx)            — called before IPC wiring
 *   onReady?(ctx)             — called after app.whenReady init
 *   onBeforeQuit?(ctx)        — cleanup hook
 */
class ModuleRegistry {
  constructor() {
    this.modules = new Map();
    this.order = [];
    this._resolved = null;
  }

  register(mod) {
    if (!mod?.id) throw new Error('Module must have an id');
    if (this.modules.has(mod.id)) {
      console.warn(`[ModuleRegistry] Module "${mod.id}" already registered — skipping`);
      return;
    }
    this.modules.set(mod.id, mod);
    this.order.push(mod.id);
    this._resolved = null;
  }

  resolveOrder() {
    if (this._resolved) return this._resolved;

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
    this._resolved = resolved.map((id) => this.modules.get(id));
    return this._resolved;
  }

  registerAll(ctx) {
    for (const mod of this.resolveOrder()) {
      if (typeof mod.register === 'function') {
        mod.register(ctx);
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
