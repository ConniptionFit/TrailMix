/**
 * Semantic editor for Jot & Enhance notes with provenance tracking.
 *
 * Models TipTap-style custom marks via span origins:
 * - user spans: solid default text (black in light mode)
 * - ai spans: gray supporting prose with optional transcript traceability
 *
 * Editing within an AI span instantly reverts that block to user origin.
 */
class EditorComponent {
  constructor(container, options = {}) {
    this.container = container;
    this.options = {
      placeholder: 'Jot quick thoughts during the meeting…',
      onChange: null,
      onTraceTranscript: null,
      onEnhanceRequest: null,
      onRegenerateRequest: null,
      debounceMs: 400,
      ...options
    };

    this.document = null;
    this.saveTimeout = null;
    this.root = null;
    this.surface = null;
    this.placeholderEl = null;
    this.statusEl = null;
    this.toolbarEl = null;
    this.templateMenuEl = null;
    this.viewMode = 'enhanced';
    this.isEnhancing = false;
    this.selectedTemplate = 'executive';
    this.spanSnapshots = new Map();

    this.renderShell();
    this.bindEvents();
  }

  renderShell() {
    this.container.innerHTML = '';
    this.container.classList.add('editor-component');

    this.root = document.createElement('div');
    this.root.className = 'editor-component-root';

    this.toolbarEl = document.createElement('div');
    this.toolbarEl.className = 'editor-overlay-toolbar';
    this.toolbarEl.innerHTML = `
      <div class="editor-toolbar-left">
        <button type="button" class="editor-toolbar-btn" data-action="enhance-menu" title="Enhanced notes templates">✨ Enhanced notes</button>
        <button type="button" class="editor-toolbar-btn" data-action="view-toggle" title="Toggle raw vs enhanced view">☰</button>
      </div>
      <div class="editor-toolbar-right">
        <span class="editor-view-badge" data-view-badge>Enhanced view</span>
      </div>
    `;

    this.templateMenuEl = document.createElement('div');
    this.templateMenuEl.className = 'editor-template-menu hidden';
    this.templateMenuEl.innerHTML = `
      <div class="editor-template-menu-header">Note template</div>
      <button type="button" class="editor-template-option" data-template="executive">Executive Summary</button>
      <button type="button" class="editor-template-option" data-template="technical">Technical Specs</button>
      <button type="button" class="editor-template-option" data-template="action">Action Checklist</button>
      <button type="button" class="editor-template-option" data-template="minutes">Meeting Minutes</button>
      <button type="button" class="editor-template-option" data-template="custom">Custom Template</button>
      <div class="editor-template-menu-divider"></div>
      <button type="button" class="editor-template-option editor-template-regenerate" data-action="regenerate">🔁 Regenerate</button>
    `;

    this.statusEl = document.createElement('div');
    this.statusEl.className = 'editor-component-status';
    this.statusEl.textContent = 'Your jots — saved as you type';

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

    this.root.appendChild(this.toolbarEl);
    this.root.appendChild(this.templateMenuEl);
    this.root.appendChild(this.statusEl);
    this.root.appendChild(this.surface);
    this.root.appendChild(this.placeholderEl);
    this.container.appendChild(this.root);

    this.bindToolbarEvents();
  }

  bindToolbarEvents() {
    const enhanceBtn = this.toolbarEl.querySelector('[data-action="enhance-menu"]');
    const viewToggleBtn = this.toolbarEl.querySelector('[data-action="view-toggle"]');

    if (enhanceBtn) {
      enhanceBtn.addEventListener('click', (event) => {
        event.stopPropagation();
        this.templateMenuEl.classList.toggle('hidden');
      });
    }

    if (viewToggleBtn) {
      viewToggleBtn.addEventListener('click', () => {
        if (!this.document || this.document.mode !== 'mixed') return;
        this.viewMode = this.viewMode === 'enhanced' ? 'raw' : 'enhanced';
        this.renderDocument();
      });
    }

    this.templateMenuEl.querySelectorAll('[data-template]').forEach((button) => {
      button.addEventListener('click', () => {
        this.selectedTemplate = button.dataset.template;
        this.templateMenuEl.classList.add('hidden');
        this.templateMenuEl.querySelectorAll('[data-template]').forEach((opt) => {
          opt.classList.toggle('active', opt.dataset.template === this.selectedTemplate);
        });
        if (typeof this.options.onEnhanceRequest === 'function') {
          this.options.onEnhanceRequest(this.selectedTemplate);
        }
      });
    });

    const regenerateBtn = this.templateMenuEl.querySelector('[data-action="regenerate"]');
    if (regenerateBtn) {
      regenerateBtn.addEventListener('click', () => {
        this.templateMenuEl.classList.add('hidden');
        if (typeof this.options.onRegenerateRequest === 'function') {
          this.options.onRegenerateRequest(this.selectedTemplate);
        }
      });
    }

    document.addEventListener('click', (event) => {
      if (!this.root.contains(event.target)) {
        this.templateMenuEl.classList.add('hidden');
      }
    });
  }

  bindEvents() {
    this.surface.addEventListener('input', () => {
      if (!this.document) return;

      if (this.document.mode === 'plain') {
        this.updatePlaceholderVisibility();
        this.scheduleChange();
        return;
      }

      this.handleMixedModeInput();
    });

    this.surface.addEventListener('keydown', (event) => {
      if (this.document?.mode === 'mixed' && this.viewMode === 'raw') {
        event.preventDefault();
      }
    });
  }

  captureSpanSnapshots() {
    this.spanSnapshots.clear();
    this.surface.querySelectorAll('.editor-span').forEach((node) => {
      const spanId = node.dataset.spanId;
      if (spanId) {
        this.spanSnapshots.set(spanId, {
          text: node.textContent || '',
          origin: node.dataset.origin || 'user'
        });
      }
    });
  }

  handleMixedModeInput() {
    if (this.viewMode === 'raw') return;

    const changedSpanIds = new Set();

    this.surface.querySelectorAll('.editor-span').forEach((node) => {
      const spanId = node.dataset.spanId;
      if (!spanId) return;

      const previous = this.spanSnapshots.get(spanId);
      const currentText = node.textContent || '';
      const currentOrigin = node.dataset.origin || 'user';

      if (!previous) {
        this.spanSnapshots.set(spanId, { text: currentText, origin: currentOrigin });
        return;
      }

      if (previous.text !== currentText && previous.origin === 'ai') {
        node.dataset.origin = 'user';
        node.classList.remove('editor-span-ai');
        node.classList.add('editor-span-user');
        const traceBtn = node.parentElement?.querySelector?.('.editor-trace-btn');
        if (traceBtn) traceBtn.remove();
        changedSpanIds.add(spanId);
      }

      this.spanSnapshots.set(spanId, {
        text: currentText,
        origin: node.dataset.origin || 'user'
      });
    });

    if (changedSpanIds.size > 0) {
      this.syncDocumentFromDom();
      this.scheduleChange();
    }
  }

  syncDocumentFromDom() {
    if (!this.document || this.document.mode !== 'mixed') return;

    const spans = [];
    this.surface.querySelectorAll('.editor-span').forEach((node) => {
      spans.push({
        id: node.dataset.spanId,
        origin: node.dataset.origin === 'ai' ? 'ai' : 'user',
        text: node.textContent || '',
        transcriptRef: this.document.spans?.find((span) => span.id === node.dataset.spanId)?.transcriptRef || null
      });
    });

    this.document.spans = spans;
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
    if (this.document?.mode === 'mixed') {
      this.viewMode = 'enhanced';
    }
    this.renderDocument();
  }

  renderDocument() {
    if (!this.document) return;

    if (this.document.mode === 'mixed' && this.document.spans?.length) {
      if (this.viewMode === 'raw') {
        this.renderPlainDocument(this.document.plainText || '');
        this.updateViewBadge('Raw jots');
        return;
      }
      this.renderMixedDocument();
      this.updateViewBadge('Enhanced view');
      return;
    }

    this.viewMode = 'enhanced';
    this.renderPlainDocument(this.document.plainText || '');
    this.updateViewBadge('Plain notes');
  }

  updateViewBadge(label) {
    const badge = this.toolbarEl.querySelector('[data-view-badge]');
    if (badge) badge.textContent = label;
  }

  renderPlainDocument(plainText = '') {
    this.surface.classList.remove('editor-mode-mixed');
    this.surface.classList.add('editor-mode-plain');
    this.surface.contentEditable = 'true';
    this.surface.innerHTML = this.renderMarkdownHtml(plainText || '');
    if (!plainText) {
      this.surface.textContent = '';
    }
    this.statusEl.textContent = 'Your jots — saved as you type';
    this.updatePlaceholderVisibility();
    this.spanSnapshots.clear();
  }

  renderMarkdownHtml(text) {
    if (!text) return '';

    const escaped = text
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;');

    return escaped
      .replace(/^### (.+)$/gm, '<h3>$1</h3>')
      .replace(/^## (.+)$/gm, '<h2>$1</h2>')
      .replace(/^# (.+)$/gm, '<h1>$1</h1>')
      .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
      .replace(/\*(.+?)\*/g, '<em>$1</em>')
      .replace(/^- \[x\] (.+)$/gim, '<div class="md-checkbox checked">☑ $1</div>')
      .replace(/^- \[ \] (.+)$/gim, '<div class="md-checkbox">☐ $1</div>')
      .replace(/^- (.+)$/gm, '<div class="md-bullet">• $1</div>')
      .replace(/\n/g, '<br>');
  }

  renderMixedDocument(animate = false) {
    this.surface.classList.remove('editor-mode-plain', 'is-transitioning');
    this.surface.classList.add('editor-mode-mixed');
    this.surface.contentEditable = 'true';
    this.surface.innerHTML = '';

    this.document.spans.forEach((span) => {
      const wrapper = document.createElement('span');
      wrapper.className = 'editor-span-wrap';
      wrapper.dataset.spanId = span.id;

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
      wrapper.appendChild(spanEl);

      if (isAi && span.transcriptRef) {
        const traceBtn = document.createElement('button');
        traceBtn.type = 'button';
        traceBtn.className = 'editor-trace-btn';
        traceBtn.title = 'Jump to transcript source';
        traceBtn.setAttribute('aria-label', 'Trace to transcript');
        traceBtn.textContent = '🔍';
        traceBtn.addEventListener('click', (event) => {
          event.preventDefault();
          event.stopPropagation();
          if (typeof this.options.onTraceTranscript === 'function') {
            this.options.onTraceTranscript(span.transcriptRef);
          }
        });
        wrapper.appendChild(traceBtn);
      }

      this.surface.appendChild(wrapper);
    });

    this.captureSpanSnapshots();

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
    this.statusEl.textContent = `Enhanced notes · black = validated by you, gray = AI context (${enhancedAt})`;
    this.placeholderEl.classList.add('hidden');
  }

  updatePlaceholderVisibility() {
    const isEmpty = !(this.surface.textContent || '').trim();
    this.placeholderEl.classList.toggle('hidden', !isEmpty || this.document?.mode === 'mixed');
  }

  getPlainText() {
    if (this.document?.mode === 'mixed' && this.viewMode === 'raw') {
      return this.document.plainText || '';
    }

    if (this.document?.mode === 'mixed') {
      return this.document.plainText || '';
    }

    return (this.surface.textContent || '').replace(/\u00A0/g, ' ');
  }

  getDocument() {
    if (this.document?.mode === 'mixed') {
      this.syncDocumentFromDom();
    }

    const plainText = this.getPlainText();
    return {
      version: 1,
      mode: this.document?.mode === 'mixed' ? 'mixed' : 'plain',
      plainText,
      spans: this.document?.mode === 'mixed' ? (this.document.spans || []) : [],
      enhancedAt: this.document?.mode === 'mixed' ? (this.document.enhancedAt || null) : null
    };
  }

  getSelectedTemplate() {
    return this.selectedTemplate;
  }

  setEnhancing(isEnhancing) {
    this.isEnhancing = isEnhancing;
    this.surface.classList.toggle('is-enhancing', isEnhancing);
    if (isEnhancing) {
      this.statusEl.textContent = 'Running enhancement — local AI is blending your jots with the transcript…';
    } else if (this.document?.mode !== 'mixed') {
      this.statusEl.textContent = 'Your jots — saved as you type';
    }
  }

  applyEnhancedDocument(document) {
    this.document = document;
    this.viewMode = 'enhanced';
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
    this.viewMode = 'enhanced';
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
