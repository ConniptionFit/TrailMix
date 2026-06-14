/**
 * Native contenteditable editor for Jot & Enhance notes.
 *
 * Library choice: native contenteditable + span document model.
 * ProseMirror/Lexical would require a bundler; this keeps the Electron
 * renderer dependency-free while still supporting mixed user/AI origins.
 */
class EditorComponent {
  constructor(container, options = {}) {
    this.container = container;
    this.options = {
      placeholder: 'Jot quick thoughts during the meeting…',
      onChange: null,
      debounceMs: 400,
      ...options
    };

    this.document = null;
    this.saveTimeout = null;
    this.root = null;
    this.surface = null;
    this.placeholderEl = null;
    this.statusEl = null;

    this.renderShell();
    this.bindEvents();
  }

  renderShell() {
    this.container.innerHTML = '';
    this.container.classList.add('editor-component');

    this.root = document.createElement('div');
    this.root.className = 'editor-component-root';

    this.statusEl = document.createElement('div');
    this.statusEl.className = 'editor-component-status';
    this.statusEl.textContent = 'Plain jots — saved automatically';

    this.surface = document.createElement('div');
    this.surface.className = 'editor-component-surface editor-mode-plain';
    this.surface.setAttribute('role', 'textbox');
    this.surface.setAttribute('aria-multiline', 'true');
    this.surface.setAttribute('data-placeholder', this.options.placeholder);
    this.surface.contentEditable = 'true';
    this.surface.spellcheck = true;

    this.placeholderEl = document.createElement('div');
    this.placeholderEl.className = 'editor-component-placeholder';
    this.placeholderEl.textContent = this.options.placeholder;

    this.root.appendChild(this.statusEl);
    this.root.appendChild(this.surface);
    this.root.appendChild(this.placeholderEl);
    this.container.appendChild(this.root);
  }

  bindEvents() {
    this.surface.addEventListener('input', () => {
      if (!this.document || this.document.mode !== 'plain') return;
      this.updatePlaceholderVisibility();
      this.scheduleChange();
    });

    this.surface.addEventListener('keydown', (event) => {
      if (this.document?.mode === 'mixed' && !event.metaKey && !event.ctrlKey) {
        event.preventDefault();
      }
    });
  }

  scheduleChange() {
    clearTimeout(this.saveTimeout);
    this.saveTimeout = setTimeout(() => {
      if (typeof this.options.onChange === 'function') {
        this.options.onChange(this.getDocument());
      }
    }, this.options.debounceMs);
  }

  loadDocument(document) {
    this.document = document;
    this.renderDocument();
  }

  renderDocument() {
    if (!this.document) return;

    if (this.document.mode === 'mixed' && this.document.spans?.length) {
      this.renderMixedDocument();
      return;
    }

    this.renderPlainDocument();
  }

  renderPlainDocument() {
    this.surface.classList.remove('editor-mode-mixed');
    this.surface.classList.add('editor-mode-plain');
    this.surface.contentEditable = 'true';
    this.surface.innerHTML = '';
    this.surface.textContent = this.document.plainText || '';
    this.statusEl.textContent = 'Your jots — saved as you type';
    this.updatePlaceholderVisibility();
  }

  renderMixedDocument(animate = false) {
    this.surface.classList.remove('editor-mode-plain', 'is-transitioning');
    this.surface.classList.add('editor-mode-mixed');
    this.surface.contentEditable = 'false';
    this.surface.innerHTML = '';

    this.document.spans.forEach((span) => {
      const spanEl = document.createElement('span');
      const isAi = span.origin !== 'user';
      spanEl.className = isAi
        ? 'editor-span editor-span-ai'
        : 'editor-span editor-span-user';
      if (animate && isAi) {
        spanEl.classList.add('is-new');
      }
      spanEl.dataset.origin = span.origin;
      spanEl.dataset.spanId = span.id;
      spanEl.textContent = span.text;
      this.surface.appendChild(spanEl);
    });

    if (animate) {
      this.surface.classList.add('is-transitioning');
      window.setTimeout(() => {
        this.surface.classList.remove('is-transitioning');
        this.surface.querySelectorAll('.editor-span-ai.is-new').forEach((node) => {
          node.classList.remove('is-new');
        });
      }, 400);
    }

    const enhancedAt = this.document.enhancedAt
      ? new Date(this.document.enhancedAt).toLocaleString()
      : 'just now';
    this.statusEl.textContent = `The Mix is complete · your jots in bold, AI context in gray (${enhancedAt})`;
    this.placeholderEl.classList.add('hidden');
  }

  updatePlaceholderVisibility() {
    const isEmpty = !(this.surface.textContent || '').trim();
    this.placeholderEl.classList.toggle('hidden', !isEmpty || this.document?.mode === 'mixed');
  }

  getPlainText() {
    if (this.document?.mode === 'mixed') {
      return this.document.plainText || '';
    }
    return (this.surface.textContent || '').replace(/\u00A0/g, ' ');
  }

  getDocument() {
    const plainText = this.getPlainText();
    return {
      version: 1,
      mode: 'plain',
      plainText,
      spans: this.document?.mode === 'mixed' ? (this.document.spans || []) : [],
      enhancedAt: this.document?.mode === 'mixed' ? (this.document.enhancedAt || null) : null
    };
  }

  setEnhancing(isEnhancing) {
    this.surface.classList.toggle('is-enhancing', isEnhancing);
    if (isEnhancing) {
      this.statusEl.textContent = 'Running The Mix — local AI is blending your jots with the transcript…';
    } else if (this.document?.mode !== 'mixed') {
      this.statusEl.textContent = 'Your jots — saved as you type';
    }
  }

  applyEnhancedDocument(document) {
    this.document = document;
    if (this.document.mode === 'mixed' && this.document.spans?.length) {
      this.renderMixedDocument(true);
      return;
    }
    this.renderDocument();
  }

  resetPlain() {
    this.document = {
      version: 1,
      mode: 'plain',
      plainText: '',
      spans: [],
      enhancedAt: null
    };
    this.renderDocument();
  }

  destroy() {
    clearTimeout(this.saveTimeout);
    this.container.innerHTML = '';
  }
}

if (typeof module !== 'undefined' && module.exports) {
  module.exports = { EditorComponent };
}

if (typeof window !== 'undefined') {
  window.EditorComponent = EditorComponent;
}
