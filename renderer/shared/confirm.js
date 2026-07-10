/**
 * Lightweight confirm dialog for destructive actions.
 * Usage: const ok = await TrailMixConfirm.ask('Delete this?');
 */
(function (global) {
  function ensureModal() {
    let modal = document.getElementById('trailmix-confirm-modal');
    if (modal) return modal;
    modal = document.createElement('div');
    modal.id = 'trailmix-confirm-modal';
    modal.className = 'modal hidden';
    modal.innerHTML = `
      <div class="modal-content glassmorphic">
        <h2 id="trailmix-confirm-title">Confirm</h2>
        <p id="trailmix-confirm-message"></p>
        <div class="modal-actions">
          <button id="trailmix-confirm-cancel" class="btn-secondary" type="button">Cancel</button>
          <button id="trailmix-confirm-ok" class="btn-primary" type="button">Confirm</button>
        </div>
      </div>`;
    document.body.appendChild(modal);
    return modal;
  }

  function ask(message, options = {}) {
    const modal = ensureModal();
    const title = modal.querySelector('#trailmix-confirm-title');
    const msg = modal.querySelector('#trailmix-confirm-message');
    const btnOk = modal.querySelector('#trailmix-confirm-ok');
    const btnCancel = modal.querySelector('#trailmix-confirm-cancel');
    if (title) title.textContent = options.title || 'Confirm';
    if (msg) msg.textContent = String(message || '');
    if (btnOk) btnOk.textContent = options.confirmLabel || 'Confirm';
    modal.classList.remove('hidden');

    return new Promise((resolve) => {
      const finish = (value) => {
        modal.classList.add('hidden');
        btnOk?.removeEventListener('click', onOk);
        btnCancel?.removeEventListener('click', onCancel);
        resolve(value);
      };
      const onOk = () => finish(true);
      const onCancel = () => finish(false);
      btnOk?.addEventListener('click', onOk);
      btnCancel?.addEventListener('click', onCancel);
    });
  }

  global.TrailMixConfirm = { ask };
})(window);
