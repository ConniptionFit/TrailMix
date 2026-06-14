/**
 * Workflow trigger registration API for future automation integrations.
 * Consumers register webhook handlers against named event criteria.
 */

const { appEventBus } = require('./event-bus');

const WORKFLOW_EVENTS = {
  NOTE_ADDED_TO_FOLDER: 'note:added-to-folder',
  SESSION_SAVED: 'session:saved',
  ENHANCEMENT_COMPLETE: 'enhancement:complete'
};

function registerWebhookTrigger(eventName, url, options = {}) {
  appEventBus.registerWorkflowTrigger(eventName, {
    type: 'webhook',
    url,
    method: options.method || 'POST',
    headers: options.headers || {},
    criteria: options.criteria || null
  });
}

module.exports = {
  WORKFLOW_EVENTS,
  registerWebhookTrigger,
  appEventBus
};
