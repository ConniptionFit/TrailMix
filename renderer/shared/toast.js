/**
 * Lightweight toast notifications for the hub / meeting renderers.
 * Usage: window.TrailMixToast.show('Saved', { type: 'success' })
 */
(function (global) {
  const HOST_ID = 'trailmix-toast-host';

  function ensureHost() {
    let host = document.getElementById(HOST_ID);
    if (host) return host;
    host = document.createElement('div');
    host.id = HOST_ID;
    host.className = 'trailmix-toast-host';
    host.setAttribute('aria-live', 'polite');
    host.setAttribute('aria-relevant', 'additions');
    document.body.appendChild(host);
    return host;
  }

  function show(message, options = {}) {
    const text = String(message || '').trim();
    if (!text) return;
    const type = options.type || 'info';
    const duration = options.duration ?? 2800;
    const host = ensureHost();
    const toast = document.createElement('div');
    toast.className = `trailmix-toast trailmix-toast-${type}`;
    toast.textContent = text;
    host.appendChild(toast);
    requestAnimationFrame(() => toast.classList.add('visible'));
    setTimeout(() => {
      toast.classList.remove('visible');
      setTimeout(() => toast.remove(), 220);
    }, duration);
  }

  global.TrailMixToast = { show };
})(window);
