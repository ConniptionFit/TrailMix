/**
 * Lightweight in-process event bus for workflow automation hooks.
 * Prepares export endpoints for future integrations (webhooks, etc.).
 */
const { EventEmitter } = require('events');

class AppEventBus extends EventEmitter {
  constructor() {
    super();
    this.workflowTriggers = new Map();
  }

  /**
   * Register a workflow trigger for a named event criteria.
   * @param {string} eventName - e.g. 'session:saved'
   * @param {object} trigger - { type: 'webhook', url, headers?, criteria? }
   */
  registerWorkflowTrigger(eventName, trigger) {
    if (!this.workflowTriggers.has(eventName)) {
      this.workflowTriggers.set(eventName, []);
    }
    this.workflowTriggers.get(eventName).push(trigger);
  }

  listWorkflowTriggers(eventName) {
    return this.workflowTriggers.get(eventName) || [];
  }

  /**
   * Emit an app event and evaluate registered workflow triggers.
   */
  async emitWorkflowEvent(eventName, payload = {}) {
    this.emit(eventName, payload);

    const triggers = this.workflowTriggers.get(eventName) || [];
    const results = [];

    for (const trigger of triggers) {
      if (trigger.type === 'webhook' && trigger.url) {
        try {
          const response = await fetch(trigger.url, {
            method: trigger.method || 'POST',
            headers: {
              'Content-Type': 'application/json',
              ...(trigger.headers || {})
            },
            body: JSON.stringify({ event: eventName, payload, timestamp: new Date().toISOString() })
          });
          results.push({ trigger, ok: response.ok, status: response.status });
        } catch (err) {
          results.push({ trigger, ok: false, error: err.message });
        }
      }
    }

    return results;
  }
}

const appEventBus = new AppEventBus();

module.exports = {
  AppEventBus,
  appEventBus
};
