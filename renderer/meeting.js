(function () {
  const urlParams = new URLSearchParams(window.location.search);
  const sessionId = urlParams.get('sessionId');
  const focusSegmentId = urlParams.get('segmentId');

  let activeSession = null;
  let recordingInterval = null;
  let recordingSeconds = 0;
  let isUserScrolledUp = false;
  let lastSegmentTimeMs = null;
  let jotEditor = null;
  let messageCounter = 0;
  let pendingRecordingStart = false;
  let activeChatRequestId = null;
  let saveStatusTimer = null;
  let transcriptSearchMatches = [];
  let transcriptSearchIndex = -1;
  let sessionEncryptionPassword = null;
  let encryptPasswordResolver = null;
  let isLiveRecording = false;
  let enhanceMode = 'enhance'; // enhance | stop-enhance | back-live | show-enhanced
  let isEnhancingPipeline = false;

  const llmStreamBuffers = new Map();
  const llmStreamRenderTimers = new Map();
  const llmStreamWaiters = new Map();

  function notify(message, type = 'info') {
    if (window.TrailMixToast) {
      window.TrailMixToast.show(message, { type });
      return;
    }
    console.log(`[toast:${type}]`, message);
  }

  function formatTimerSeconds(totalSeconds) {
    const minutes = Math.floor(totalSeconds / 60).toString().padStart(2, '0');
    const seconds = (totalSeconds % 60).toString().padStart(2, '0');
    return `${minutes}:${seconds}`;
  }

  function getSegmentTimestampMs(segment) {
    return segment.wallTimeMs || segment.timestampMs;
  }

  function formatGapDuration(timeDiffMs) {
    const minutes = Math.floor(timeDiffMs / 60000);
    const seconds = Math.floor((timeDiffMs % 60000) / 1000);
    return `${minutes > 0 ? `${minutes}m ` : ''}${seconds}s`;
  }

  function formatAISummary(text) {
    if (!text) return '';
    return text
      .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
      .replace(/^### (.+)$/gm, '<h3>$1</h3>')
      .replace(/^## (.+)$/gm, '<h2>$1</h2>')
      .replace(/^- (.+)$/gm, '<li>$1</li>')
      .replace(/\n/g, '<br>');
  }

  function waitForLlmStream(requestId, onUpdate) {
    return new Promise((resolve, reject) => {
      llmStreamBuffers.set(requestId, '');
      llmStreamWaiters.set(requestId, { onUpdate, resolve, reject });
    });
  }

  window.api.onLlmStreamChunk((payload) => {
    const waiter = llmStreamWaiters.get(payload.requestId);
    if (!waiter) return;
    if (payload.token) {
      const next = (llmStreamBuffers.get(payload.requestId) || '') + payload.token;
      llmStreamBuffers.set(payload.requestId, next);
      if (!llmStreamRenderTimers.has(payload.requestId)) {
        llmStreamRenderTimers.set(payload.requestId, requestAnimationFrame(() => {
          llmStreamRenderTimers.delete(payload.requestId);
          waiter.onUpdate(llmStreamBuffers.get(payload.requestId) || '');
        }));
      }
    }
    if (payload.done) {
      const finalText = payload.fullResponse || llmStreamBuffers.get(payload.requestId) || '';
      llmStreamWaiters.delete(payload.requestId);
      llmStreamBuffers.delete(payload.requestId);
      llmStreamRenderTimers.delete(payload.requestId);
      waiter.onUpdate(finalText);
      payload.error ? waiter.reject(new Error(payload.error)) : waiter.resolve(finalText);
    }
  });

  function normalizeEditorDocument(session) {
    if (session?.editorDocument) {
      let doc = session.editorDocument;
      if (typeof doc === 'string') {
        try { doc = JSON.parse(doc); } catch (e) { doc = null; }
      }
      if (doc?.mode === 'mixed' && doc.spans?.length) {
        doc.rawAiText = doc.rawAiText || session.enhancedNotes || '';
        return doc;
      }
      return {
        version: 1,
        mode: 'plain',
        plainText: doc?.plainText || session.mixNotes || '',
        spans: [],
        enhancedAt: null,
        rawAiText: session.enhancedNotes || ''
      };
    }
    return {
      version: 1,
      mode: 'plain',
      plainText: session?.mixNotes || '',
      spans: [],
      enhancedAt: null,
      rawAiText: session?.enhancedNotes || ''
    };
  }

  function syncSessionFromEditorDocument(session, document) {
    session.editorDocument = document;
    session.mixNotes = document.plainText || '';
  }

  const inputMeetingTitle = document.getElementById('input-meeting-title');
  const meetingTitleHint = document.getElementById('meeting-title-hint');
  const btnRecordToggle = document.getElementById('btn-record-toggle');
  const btnPauseToggle = document.getElementById('btn-pause-toggle');
  const recIndicator = document.getElementById('rec-indicator');
  const recTitle = document.getElementById('rec-title');
  const recTimer = document.getElementById('rec-timer');
  const transcriptContainer = document.getElementById('transcript-container');
  const btnJumpLatest = document.getElementById('btn-jump-latest');
  const editorHost = document.getElementById('editor-component-root');
  const btnMixEnhance = document.getElementById('btn-mix-enhance');
  const btnMixLabel = btnMixEnhance?.querySelector('.btn-mix-label');
  const editorLegend = document.getElementById('editor-legend');
  const editorSaveStatus = document.getElementById('editor-save-status');
  const editorWaveformBars = document.getElementById('editor-waveform-bars');
  const conflictModal = document.getElementById('recording-conflict-modal');
  const conflictMessage = document.getElementById('recording-conflict-message');
  const queuePressureBanner = document.getElementById('queue-pressure-banner');
  const transcriptSearchBar = document.getElementById('transcript-search-bar');
  const inputTranscriptSearch = document.getElementById('input-transcript-search');
  const transcriptSearchCount = document.getElementById('transcript-search-count');
  const btnChatCancel = document.getElementById('btn-chat-cancel');
  const postMeetingPanel = document.getElementById('post-meeting-panel');
  const postPanelActions = document.getElementById('post-panel-actions');
  const postPanelRecap = document.getElementById('post-panel-recap');
  const meetingTagChips = document.getElementById('meeting-tag-chips');
  const btnAddTag = document.getElementById('btn-add-tag');
  const chipParticipant = document.getElementById('chip-participant');

  function setSaveStatus(text) {
    if (!editorSaveStatus) return;
    editorSaveStatus.textContent = text || '';
    clearTimeout(saveStatusTimer);
    if (text && text.startsWith('Saved')) {
      saveStatusTimer = setTimeout(() => {
        if (editorSaveStatus.textContent === text) editorSaveStatus.textContent = '';
      }, 1800);
    }
  }

  const encryptPasswordModal = document.getElementById('encrypt-password-modal');
  const encryptPasswordInput = document.getElementById('encrypt-password-input');
  const encryptPasswordConfirm = document.getElementById('encrypt-password-confirm');
  const encryptPasswordError = document.getElementById('encrypt-password-error');

  function hideEncryptPasswordModal() {
    encryptPasswordModal?.classList.add('hidden');
    if (encryptPasswordInput) encryptPasswordInput.value = '';
    if (encryptPasswordConfirm) encryptPasswordConfirm.value = '';
    encryptPasswordError?.classList.add('hidden');
  }

  function ensureEncryptionPassword() {
    if (!activeSession?.encrypted) return Promise.resolve(null);
    if (sessionEncryptionPassword) return Promise.resolve(sessionEncryptionPassword);
    return new Promise((resolve) => {
      encryptPasswordResolver = resolve;
      encryptPasswordModal?.classList.remove('hidden');
      encryptPasswordInput?.focus();
    });
  }

  document.getElementById('btn-encrypt-password-cancel')?.addEventListener('click', () => {
    hideEncryptPasswordModal();
    if (encryptPasswordResolver) {
      encryptPasswordResolver(null);
      encryptPasswordResolver = null;
    }
  });

  document.getElementById('btn-encrypt-password-submit')?.addEventListener('click', () => {
    const pw = encryptPasswordInput?.value || '';
    const confirm = encryptPasswordConfirm?.value || '';
    if (pw.length < 4 || pw !== confirm) {
      encryptPasswordError?.classList.remove('hidden');
      return;
    }
    sessionEncryptionPassword = pw;
    hideEncryptPasswordModal();
    if (encryptPasswordResolver) {
      encryptPasswordResolver(pw);
      encryptPasswordResolver = null;
    }
  });

  async function persistSession(options = {}) {
    if (!activeSession) return false;
    let password = sessionEncryptionPassword;
    if (activeSession.encrypted && !password) {
      password = await ensureEncryptionPassword();
      if (!password) {
        setSaveStatus('Encryption password required');
        return false;
      }
      sessionEncryptionPassword = password;
    }
    setSaveStatus('Saving…');
    try {
      const result = activeSession.encrypted
        ? await window.api.saveCall(activeSession, password)
        : await window.api.saveCallSilently(activeSession, password || undefined);
      if (result?.needsPassword) {
        sessionEncryptionPassword = null;
        setSaveStatus('Encryption password required');
        return false;
      }
      if (result === false || result?.success === false) {
        setSaveStatus('Save failed');
        return false;
      }
      setSaveStatus('Saved');
      return true;
    } catch (err) {
      setSaveStatus('Save failed');
      return false;
    }
  }

  function updateTitleUi() {
    if (!activeSession) return;
    inputMeetingTitle.value = activeSession.title || 'New Meeting';
    const showHint = activeSession.titleAutoGenerated && !activeSession.titleUserEdited;
    meetingTitleHint.classList.toggle('hidden', !showHint);
  }

  inputMeetingTitle.addEventListener('change', () => {
    if (!activeSession) return;
    const title = inputMeetingTitle.value.trim() || 'New Meeting';
    activeSession.title = title;
    activeSession.titleUserEdited = true;
    activeSession.titleAutoGenerated = false;
    updateTitleUi();
    window.api.setSessionTitle(activeSession.id, title, true);
  });

  function isMicSpeaker(name) {
    const n = String(name || '').toLowerCase();
    return n === 'you' || n === 'me';
  }

  function getDisplaySpeakerName(speakerName) {
    const normalizedSpeaker = String(speakerName || '').toLowerCase();
    if (normalizedSpeaker === 'you' || normalizedSpeaker === 'me') return 'Me';
    if (normalizedSpeaker === 'speaker 1') return 'Them';
    return speakerName;
  }

  function applySpeakerLabelMapping(transcript, mapping) {
    let updatedAny = false;
    transcript.forEach((segment, index) => {
      let label = mapping[segment.id];
      if (!label) {
        label = mapping[(index + 1).toString()] || mapping[index + 1];
      }
      if (!label) return;
      if (label !== 'You' && String(segment.speaker || '').toLowerCase() === 'you') return;
      if (segment.speaker !== label) {
        segment.speaker = label;
        updatedAny = true;
      }
    });
    return updatedAny;
  }

  function appendTranscriptLine(segment, container = transcriptContainer) {
    const empty = container.querySelector('.transcript-empty-state');
    if (empty) empty.remove();

    // Backend coalesces contiguous speech into the same segment id with merged:true.
    if (segment.merged) {
      const existingLine = document.getElementById(`line-${segment.id}`);
      if (existingLine) {
        const textDiv = existingLine.querySelector('.line-text');
        if (textDiv) textDiv.textContent = segment.text;
        existingLine.setAttribute('data-timestamp-ms', segment.timestampMs || 0);
        if (!isUserScrolledUp && container === transcriptContainer) {
          container.scrollTop = container.scrollHeight;
        }
        return;
      }
    }

    const isMic = isMicSpeaker(segment.speaker);
    const displaySpeakerName = getDisplaySpeakerName(segment.speaker);
    const lastLineDiv = container.lastElementChild;
    let merged = false;

    if (lastLineDiv && lastLineDiv.classList.contains('transcript-line')) {
      const speakerSpan = lastLineDiv.querySelector('.line-speaker');
      const textDiv = lastLineDiv.querySelector('.line-text');
      if (speakerSpan && textDiv) {
        const lastSpeaker = speakerSpan.textContent.trim();
        const lastTimestampMs = parseInt(lastLineDiv.getAttribute('data-timestamp-ms') || '0', 10);
        const timeDiffMs = (segment.timestampMs || 0) - lastTimestampMs;
        if (lastSpeaker.toLowerCase() === displaySpeakerName.toLowerCase() && timeDiffMs < 12000) {
          textDiv.textContent += ` ${segment.text}`;
          lastLineDiv.setAttribute('data-timestamp-ms', segment.timestampMs || 0);
          lastLineDiv.classList.add(`subline-${segment.id}`);
          merged = true;
        }
      }
    }

    if (!merged) {
      const lineDiv = document.createElement('div');
      lineDiv.id = `line-${segment.id}`;
      lineDiv.className = `transcript-line transcript-bubble ${isMic ? 'bubble-mic' : 'bubble-system'}`;
      lineDiv.setAttribute('data-timestamp-ms', segment.timestampMs || 0);
      lineDiv.setAttribute('data-segment-id', segment.id || '');

      const metaDiv = document.createElement('div');
      metaDiv.className = 'line-meta';

      const speakerSpan = document.createElement('span');
      speakerSpan.className = `line-speaker ${isMic ? 'mic' : 'system'}`;
      speakerSpan.textContent = displaySpeakerName;

      const timeSpan = document.createElement('span');
      timeSpan.className = 'line-time';
      timeSpan.textContent = segment.timestamp || '';

      metaDiv.appendChild(speakerSpan);
      metaDiv.appendChild(timeSpan);

      const textDiv = document.createElement('div');
      textDiv.className = 'line-text';
      textDiv.textContent = segment.text || '';

      lineDiv.appendChild(metaDiv);
      lineDiv.appendChild(textDiv);
      container.appendChild(lineDiv);
    }

    if (!isUserScrolledUp && container === transcriptContainer) {
      container.scrollTop = container.scrollHeight;
    }
  }

  function renderTranscript(transcript) {
    transcriptContainer.innerHTML = '';
    if (!transcript?.length) {
      const empty = document.createElement('div');
      empty.className = 'transcript-empty-state';
      empty.innerHTML = `<span class="empty-icon">${iconHtml('headphones', 28)}</span><p>Start the Trail to begin live transcription.</p>`;
      transcriptContainer.appendChild(empty);
      return;
    }
    lastSegmentTimeMs = null;
    transcript.forEach((segment) => {
      const currentTime = getSegmentTimestampMs(segment);
      if (lastSegmentTimeMs && currentTime - lastSegmentTimeMs > 30000) {
        const divider = document.createElement('div');
        divider.className = 'transcript-break-divider';
        divider.textContent = formatGapDuration(currentTime - lastSegmentTimeMs);
        transcriptContainer.appendChild(divider);
      }
      lastSegmentTimeMs = currentTime;
      appendTranscriptLine(segment);
    });
  }

  function hasEnhancedDocument() {
    const doc = jotEditor?.getDocument?.() || activeSession?.editorDocument;
    return Boolean(doc?.mode === 'mixed' && doc?.spans?.length);
  }

  function isViewingEnhanced() {
    return hasEnhancedDocument() && jotEditor?.viewMode === 'mixed';
  }

  function iconHtml(name, size = 14) {
    return window.TrailMixIcons ? window.TrailMixIcons.icon(name, { size }) : '';
  }

  function setButtonWithIcon(button, iconName, label) {
    if (!button) return;
    button.innerHTML = `<span class="btn-icon">${iconHtml(iconName, 14)}</span> ${label}`;
  }

  function setEnhanceButtonState(mode, { mixing = false } = {}) {
    if (!btnMixEnhance) return;
    enhanceMode = mode;
    btnMixEnhance.classList.toggle('is-stop-enhance', mode === 'stop-enhance');
    btnMixEnhance.classList.toggle('is-back-live', mode === 'back-live');
    btnMixEnhance.classList.toggle('is-mixing', mixing);
    btnMixEnhance.disabled = mixing;
    btnMixEnhance.setAttribute('aria-busy', mixing ? 'true' : 'false');

    const labels = {
      'stop-enhance': { icon: 'square', text: 'Stop & Enhance' },
      'back-live': { icon: 'undo-2', text: 'Back to live' },
      'show-enhanced': { icon: 'sparkles', text: 'Show enhanced' },
      enhance: { icon: 'sparkles', text: 'Enhance' }
    };
    const conf = labels[mode] || labels.enhance;
    const label = mixing ? 'Enhancing…' : conf.text;
    btnMixEnhance.innerHTML = `${iconHtml(conf.icon, 14)}<span class="btn-mix-label">${label}</span><span class="btn-mix-spinner" aria-hidden="true"></span>`;
  }

  function syncEnhanceButton() {
    if (isEnhancingPipeline) {
      setEnhanceButtonState(enhanceMode === 'stop-enhance' ? 'stop-enhance' : enhanceMode, { mixing: true });
      return;
    }
    if (isLiveRecording) {
      setEnhanceButtonState('stop-enhance');
      btnRecordToggle?.classList.add('hidden');
      return;
    }
    btnRecordToggle?.classList.remove('hidden');
    if (hasEnhancedDocument() && isViewingEnhanced()) {
      setEnhanceButtonState('back-live');
    } else if (hasEnhancedDocument()) {
      setEnhanceButtonState('show-enhanced');
    } else {
      setEnhanceButtonState('enhance');
    }
  }

  function escapeHtmlLite(value) {
    return String(value ?? '')
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;');
  }

  function formatEndedMeta() {
    const seconds = recordingSeconds || getElapsedSecondsFromTranscript(activeSession?.transcript);
    const mins = Math.max(1, Math.round(seconds / 60));
    let when = 'Today';
    try {
      const d = activeSession?.date ? new Date(activeSession.date) : new Date();
      when = d.toLocaleString(undefined, { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' });
    } catch (_) { /* ignore */ }
    return `Ended · ${mins} min · ${when}`;
  }

  function renderTagChips() {
    if (!meetingTagChips) return;
    const tags = activeSession?.tags || [];
    meetingTagChips.innerHTML = tags.map((tag) => (
      `<span class="meeting-chip meeting-chip-tag">#${escapeHtmlLite(tag)}</span>`
    )).join('');
  }

  function parseActionItems(raw) {
    if (!raw) return [];
    if (Array.isArray(raw)) {
      return raw.map((item) => (typeof item === 'string' ? item : (item?.text || item?.title || ''))).filter(Boolean);
    }
    return String(raw)
      .split('\n')
      .map((line) => line.replace(/^[\s\-\[\]xX*•◦]+/, '').trim())
      .filter(Boolean);
  }

  function renderPostMeetingPanel() {
    if (!postMeetingPanel) return;
    const ended = !isLiveRecording && Boolean(activeSession?.transcript?.length);
    postMeetingPanel.classList.toggle('hidden', !ended);
    if (!ended) return;

    const items = parseActionItems(activeSession.actionItems);
    if (postPanelActions) {
      if (!items.length) {
        postPanelActions.innerHTML = '<p class="post-empty-hint">Action items will appear here after processing finishes.</p>';
      } else {
        postPanelActions.innerHTML = items.map((text) => (
          `<div class="post-action-item"><span class="post-action-check" aria-hidden="true"></span><span>${escapeHtmlLite(text)}</span></div>`
        )).join('');
      }
    }
    if (postPanelRecap) {
      const recap = (activeSession.summary || '').trim() || 'One-line recap will appear here after processing finishes.';
      postPanelRecap.innerHTML = `<p class="post-recap-text">${escapeHtmlLite(recap)}</p>`;
    }
  }

  function updateEndedStatusLine() {
    if (isLiveRecording || !activeSession?.transcript?.length) return;
    if (recTitle) recTitle.textContent = 'Ended';
    const parts = formatEndedMeta().replace(/^Ended · /, '');
    if (recTimer) recTimer.textContent = parts;
  }

  async function runMixEnhance(templateKey = null) {
    if (!activeSession || !jotEditor) return;
    const currentDocument = jotEditor.getDocument();
    if (!currentDocument.plainText.trim()) {
      isEnhancingPipeline = false;
      syncEnhanceButton();
      notify('Add some notes before running Enhance.', 'info');
      return;
    }
    syncSessionFromEditorDocument(activeSession, currentDocument);
    isEnhancingPipeline = true;
    setEnhanceButtonState(isLiveRecording ? 'stop-enhance' : enhanceMode, { mixing: true });
    jotEditor.setEnhancing(true);
    try {
      const result = await window.api.mixEnhance({
        plainText: currentDocument.plainText,
        editorDocument: currentDocument,
        transcript: activeSession.transcript || [],
        template: templateKey || jotEditor.getSelectedTemplate()
      });
      if (!result?.success) throw new Error(result?.error || 'Enhancement failed');
      activeSession.editorDocument = result.editorDocument;
      activeSession.enhancedNotes = result.enhancedNotes;
      jotEditor.applyEnhancedDocument(result.editorDocument);
      editorLegend?.classList.remove('hidden');
      await persistSession();
    } catch (err) {
      notify(err.message, 'error');
    } finally {
      isEnhancingPipeline = false;
      jotEditor.setEnhancing(false);
      syncEnhanceButton();
      renderPostMeetingPanel();
    }
  }

  async function stopAndEnhance() {
    isEnhancingPipeline = true;
    setEnhanceButtonState('stop-enhance', { mixing: true });
    try {
      await window.api.stopRecording();
    } catch (err) {
      isEnhancingPipeline = false;
      syncEnhanceButton();
      notify(err?.message || 'Could not stop recording.', 'error');
      return;
    }
    await runMixEnhance();
  }

  async function handleEnhancePrimaryClick() {
    if (enhanceMode === 'stop-enhance') {
      await stopAndEnhance();
      return;
    }
    if (enhanceMode === 'back-live') {
      jotEditor?.setViewMode('mixins');
      editorLegend?.classList.add('hidden');
      syncEnhanceButton();
      return;
    }
    if (enhanceMode === 'show-enhanced') {
      jotEditor?.setViewMode('mixed');
      editorLegend?.classList.remove('hidden');
      syncEnhanceButton();
      return;
    }
    await runMixEnhance();
  }

  if (editorHost && window.EditorComponent) {
    jotEditor = new window.EditorComponent(editorHost, {
      onChange: (document) => {
        if (!activeSession) return;
        syncSessionFromEditorDocument(activeSession, document);
        void persistSession();
      },
      onTraceTranscript: (ref) => {
        if (!ref?.segmentId) return;
        const line = document.getElementById(`line-${ref.segmentId}`);
        if (line) line.scrollIntoView({ behavior: 'smooth', block: 'center' });
      },
      onEnhanceRequest: (template) => runMixEnhance(template),
      onRegenerateRequest: (template) => runMixEnhance(template)
    });
  }

  btnMixEnhance?.addEventListener('click', () => handleEnhancePrimaryClick());

  document.getElementById('tab-post-actions')?.addEventListener('click', () => {
    document.getElementById('tab-post-actions')?.classList.add('active');
    document.getElementById('tab-post-recap')?.classList.remove('active');
    document.getElementById('tab-post-actions')?.setAttribute('aria-selected', 'true');
    document.getElementById('tab-post-recap')?.setAttribute('aria-selected', 'false');
    postPanelActions?.classList.remove('hidden');
    postPanelRecap?.classList.add('hidden');
  });

  document.getElementById('tab-post-recap')?.addEventListener('click', () => {
    document.getElementById('tab-post-recap')?.classList.add('active');
    document.getElementById('tab-post-actions')?.classList.remove('active');
    document.getElementById('tab-post-recap')?.setAttribute('aria-selected', 'true');
    document.getElementById('tab-post-actions')?.setAttribute('aria-selected', 'false');
    postPanelRecap?.classList.remove('hidden');
    postPanelActions?.classList.add('hidden');
  });

  btnAddTag?.addEventListener('click', async () => {
    if (!activeSession) return;
    const tag = window.prompt('Add a tag (no # needed):');
    if (!tag) return;
    const cleaned = tag.replace(/^#/, '').trim();
    if (!cleaned) return;
    if (!Array.isArray(activeSession.tags)) activeSession.tags = [];
    if (!activeSession.tags.includes(cleaned)) activeSession.tags.push(cleaned);
    renderTagChips();
    await persistSession();
  });

  function showConflictModal(currentSessionId) {
    conflictMessage.textContent = `Trail "${currentSessionId}" is currently recording. Stop it and start here, or open the active Trail.`;
    conflictModal.classList.remove('hidden');
    pendingRecordingStart = true;
  }

  function hideConflictModal() {
    conflictModal.classList.add('hidden');
    pendingRecordingStart = false;
  }

  document.getElementById('btn-conflict-cancel')?.addEventListener('click', hideConflictModal);
  document.getElementById('btn-conflict-open-current')?.addEventListener('click', async () => {
    const status = await window.api.getRecordingStatus();
    if (status?.sessionId) {
      const focused = await window.api.focusMeeting(status.sessionId);
      if (!focused?.success) window.api.openMeeting(status.sessionId);
    }
    hideConflictModal();
  });
  document.getElementById('btn-conflict-stop-current')?.addEventListener('click', async () => {
    await window.api.stopRecording();
    hideConflictModal();
    if (pendingRecordingStart) startRecordingFlow();
  });

  function canResumeSession() {
    return Boolean(activeSession?.transcript?.length);
  }

  function updateIdleRecordButton() {
    if (!btnRecordToggle) return;
    if (canResumeSession()) {
      btnRecordToggle.className = 'btn-record start';
      setButtonWithIcon(btnRecordToggle, 'play', 'Resume Trail');
    } else {
      btnRecordToggle.className = 'btn-record start';
      setButtonWithIcon(btnRecordToggle, 'circle', 'Start Trail');
    }
  }

  async function resumeRecordingFlow() {
    const targetSessionId = activeSession?.id || sessionId;
    const result = await window.api.resumeCallTranscription(targetSessionId);
    if (result?.conflict) {
      showConflictModal(result.activeSessionId);
      return;
    }
    if (result?.requirePassword) {
      notify('Unlock this Trail with your password before resuming.', 'info');
      return;
    }
    if (!result?.success) {
      notify(result?.error || 'Could not resume this Trail.', 'error');
      return;
    }
    if (activeSession && result?.sessionId) {
      activeSession.id = result.sessionId;
    }
  }

  async function startRecordingFlow() {
    if (canResumeSession()) {
      await resumeRecordingFlow();
      return;
    }
    const result = await window.api.startRecording(sessionId);
    if (result?.conflict) {
      showConflictModal(result.activeSessionId);
      return;
    }
    if (result?.sessionId && activeSession) {
      activeSession.id = result.sessionId;
    }
  }

  btnRecordToggle?.addEventListener('click', async () => {
    if (btnRecordToggle.classList.contains('start')) {
      await startRecordingFlow();
    } else {
      await window.api.stopRecording();
    }
  });

  btnPauseToggle?.addEventListener('click', () => {
    const label = btnPauseToggle.textContent || '';
    if (label.includes('Pause')) window.api.pauseRecording();
    else window.api.resumeRecording();
  });

  if (transcriptContainer) {
    transcriptContainer.addEventListener('scroll', () => {
      const distanceFromBottom = transcriptContainer.scrollHeight - transcriptContainer.scrollTop - transcriptContainer.clientHeight;
      isUserScrolledUp = distanceFromBottom > 50;
      if (btnJumpLatest) btnJumpLatest.classList.toggle('hidden', !isUserScrolledUp);
    });
  }

  btnJumpLatest?.addEventListener('click', () => {
    if (transcriptContainer) transcriptContainer.scrollTop = transcriptContainer.scrollHeight;
    isUserScrolledUp = false;
    btnJumpLatest?.classList.add('hidden');
  });

  function getElapsedSecondsFromTranscript(transcript) {
    if (!transcript?.length) return 0;
    const lastSegment = transcript[transcript.length - 1];
    return Math.max(0, Math.floor((lastSegment.timestampMs || 0) / 1000));
  }

  function applyRecordingStatus(status) {
    if (status.sessionId && status.sessionId !== sessionId
      && status.sessionId !== activeSession?.id && status.isRecording) return;

    if (status.isRecording && !status.isPaused) {
      isLiveRecording = true;
      btnRecordToggle.className = 'btn-record stop';
      setButtonWithIcon(btnRecordToggle, 'square', 'Stop Trail');
      btnPauseToggle.className = 'btn-record pause';
      setButtonWithIcon(btnPauseToggle, 'pause', 'Pause');
      btnPauseToggle.classList.remove('hidden');
      recIndicator.className = 'rec-indicator-active';
      recTitle.textContent = 'On the trail';

      if (status.isNewSession) {
        recordingSeconds = 0;
      } else if (!recordingSeconds) {
        recordingSeconds = getElapsedSecondsFromTranscript(activeSession?.transcript);
      }
      recTimer.textContent = formatTimerSeconds(recordingSeconds);

      clearInterval(recordingInterval);
      recordingInterval = setInterval(() => {
        recordingSeconds += 1;
        recTimer.textContent = formatTimerSeconds(recordingSeconds);
      }, 1000);
      editorWaveformBars?.classList.remove('hidden');
      syncEnhanceButton();
      renderPostMeetingPanel();
      return;
    }

    if (!status.isRecording && status.isPaused) {
      isLiveRecording = true;
      btnRecordToggle.className = 'btn-record stop';
      setButtonWithIcon(btnRecordToggle, 'square', 'Stop Trail');
      btnPauseToggle.className = 'btn-record start';
      setButtonWithIcon(btnPauseToggle, 'play', 'Resume');
      btnPauseToggle.classList.remove('hidden');
      recIndicator.className = 'rec-indicator-static';
      recTitle.textContent = 'Paused';
      clearInterval(recordingInterval);
      editorWaveformBars?.classList.add('hidden');
      syncEnhanceButton();
      renderPostMeetingPanel();
      return;
    }

    isLiveRecording = false;
    updateIdleRecordButton();
    btnPauseToggle.classList.add('hidden');
    recIndicator.className = 'rec-indicator-static';
    clearInterval(recordingInterval);
    editorWaveformBars?.classList.add('hidden');
    if (activeSession?.transcript?.length) {
      updateEndedStatusLine();
    } else {
      recTitle.textContent = 'Engine Idle';
    }
    syncEnhanceButton();
    renderPostMeetingPanel();
  }

  window.api.onRecordingStatus(applyRecordingStatus);

  window.api.onSpeakerLabelsUpdated?.((mapping) => {
    if (!activeSession?.transcript?.length || !mapping) return;
    const updated = applySpeakerLabelMapping(activeSession.transcript, mapping);
    if (updated) renderTranscript(activeSession.transcript);
  });

  function removeTranscriptLines(segmentIds) {
    segmentIds.forEach((segmentId) => {
      document.getElementById(`line-${segmentId}`)?.remove();
    });
    if (activeSession?.transcript) {
      const removed = new Set(segmentIds);
      activeSession.transcript = activeSession.transcript.filter((segment) => !removed.has(segment.id));
    }
  }

  window.api.onTranscriptionCorrection?.((payload) => {
    if (!payload?.removedSegmentIds?.length) return;
    if (payload.sessionId && payload.sessionId !== sessionId && payload.sessionId !== activeSession?.id) return;
    removeTranscriptLines(payload.removedSegmentIds);
  });

  window.api.onTranscriptionUpdate((segment) => {
    if (!activeSession) return;
    if (!activeSession.transcript) activeSession.transcript = [];

    if (segment.merged) {
      const idx = activeSession.transcript.findIndex((entry) => entry.id === segment.id);
      if (idx >= 0) {
        activeSession.transcript[idx] = { ...activeSession.transcript[idx], ...segment };
      } else {
        activeSession.transcript.push(segment);
      }
    } else {
      const currentTime = getSegmentTimestampMs(segment);
      if (lastSegmentTimeMs && currentTime - lastSegmentTimeMs > 30000) {
        const divider = document.createElement('div');
        divider.className = 'transcript-break-divider';
        divider.textContent = formatGapDuration(currentTime - lastSegmentTimeMs);
        transcriptContainer.appendChild(divider);
      }
      lastSegmentTimeMs = currentTime;
      activeSession.transcript.push(segment);
    }

    appendTranscriptLine(segment);
  });

  function isSameMeetingSession(session) {
    if (!session) return false;
    return session.id === sessionId
      || session.id === activeSession?.id
      || session.previousId === sessionId;
  }

  window.api.onSessionUpdated((session) => {
    if (!isSameMeetingSession(session)) return;
    activeSession = session;
    if (session.id && session.id !== sessionId) {
      const nextUrl = new URL(window.location.href);
      nextUrl.searchParams.set('sessionId', session.id);
      window.history.replaceState({}, '', nextUrl.toString());
    }
    updateTitleUi();
    if (session.transcript) renderTranscript(session.transcript);
    if (jotEditor) jotEditor.loadDocument(normalizeEditorDocument(session));
    updateIdleRecordButton();
  });

  window.api.onAudioLevels?.((levels) => {
    if (!editorWaveformBars) return;
    editorWaveformBars.querySelectorAll('.waveform-bar').forEach((bar, index) => {
      const level = index % 2 === 0 ? levels.left : levels.right;
      bar.style.height = `${Math.max(8, Math.min(100, (level || 0) * 100))}%`;
    });
  });

  window.api.onQueuePressure?.((pressure) => {
    if (!queuePressureBanner) return;
    const depth = pressure?.depth || 0;
    const dropped = pressure?.droppedChunks || 0;
    if (depth < 8 && !pressure?.droppedChunkIndex) {
      queuePressureBanner.classList.add('hidden');
      return;
    }
    queuePressureBanner.classList.remove('hidden');
    queuePressureBanner.textContent = dropped > 0
      ? `Whisper is falling behind — queue ${depth}, dropped ${dropped} chunk(s). Consider a smaller model.`
      : `Whisper is catching up — transcription queue depth ${depth}.`;
  });

  window.api.onTranscriptionError?.((payload) => {
    if (!queuePressureBanner) return;
    queuePressureBanner.classList.remove('hidden');
    queuePressureBanner.textContent = `Transcription error on chunk ${payload?.chunkIndex ?? '?'}: ${payload?.error || 'unknown'}`;
  });

  function clearTranscriptSearchHighlights() {
    transcriptContainer?.querySelectorAll('.transcript-line.search-match, .transcript-line.search-match-active')
      .forEach((el) => {
        el.classList.remove('search-match', 'search-match-active');
      });
    transcriptSearchMatches = [];
    transcriptSearchIndex = -1;
    if (transcriptSearchCount) transcriptSearchCount.textContent = '';
  }

  function focusTranscriptSearchMatch(index) {
    if (!transcriptSearchMatches.length) return;
    transcriptSearchMatches.forEach((el) => el.classList.remove('search-match-active'));
    transcriptSearchIndex = ((index % transcriptSearchMatches.length) + transcriptSearchMatches.length) % transcriptSearchMatches.length;
    const el = transcriptSearchMatches[transcriptSearchIndex];
    el.classList.add('search-match-active');
    el.scrollIntoView({ behavior: 'smooth', block: 'center' });
    if (transcriptSearchCount) {
      transcriptSearchCount.textContent = `${transcriptSearchIndex + 1} / ${transcriptSearchMatches.length}`;
    }
  }

  function runTranscriptSearch(query) {
    clearTranscriptSearchHighlights();
    const q = String(query || '').trim().toLowerCase();
    if (!q || !transcriptContainer) {
      if (transcriptSearchCount) transcriptSearchCount.textContent = q ? '0' : '';
      return;
    }
    const lines = [...transcriptContainer.querySelectorAll('.transcript-line')];
    transcriptSearchMatches = lines.filter((line) => {
      const text = line.querySelector('.line-text')?.textContent || '';
      const hit = text.toLowerCase().includes(q);
      if (hit) line.classList.add('search-match');
      return hit;
    });
    if (!transcriptSearchMatches.length) {
      if (transcriptSearchCount) transcriptSearchCount.textContent = '0';
      return;
    }
    focusTranscriptSearchMatch(0);
  }

  function openTranscriptSearch() {
    if (!transcriptSearchBar) return;
    transcriptSearchBar.classList.remove('hidden');
    inputTranscriptSearch?.focus();
    inputTranscriptSearch?.select();
  }

  function closeTranscriptSearch() {
    transcriptSearchBar?.classList.add('hidden');
    clearTranscriptSearchHighlights();
    if (inputTranscriptSearch) inputTranscriptSearch.value = '';
  }

  document.getElementById('btn-transcript-search')?.addEventListener('click', openTranscriptSearch);
  document.getElementById('btn-transcript-search-close')?.addEventListener('click', closeTranscriptSearch);
  document.getElementById('btn-transcript-search-next')?.addEventListener('click', () => {
    focusTranscriptSearchMatch(transcriptSearchIndex + 1);
  });
  document.getElementById('btn-transcript-search-prev')?.addEventListener('click', () => {
    focusTranscriptSearchMatch(transcriptSearchIndex - 1);
  });
  inputTranscriptSearch?.addEventListener('input', () => {
    runTranscriptSearch(inputTranscriptSearch.value);
  });
  inputTranscriptSearch?.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      focusTranscriptSearchMatch(e.shiftKey ? transcriptSearchIndex - 1 : transcriptSearchIndex + 1);
    }
    if (e.key === 'Escape') {
      e.preventDefault();
      closeTranscriptSearch();
    }
  });

  // Meeting chat (always visible in layout)
  const chatWidget = document.getElementById('meeting-chat-widget');
  const chatMessages = document.getElementById('chat-messages');
  const inputChatQuery = document.getElementById('input-chat-query');
  const CHAT_WELCOME = chatMessages?.innerHTML || '';

  function clearChat() {
    if (chatMessages) chatMessages.innerHTML = CHAT_WELCOME;
    messageCounter = 0;
  }

  function appendChatMessage(sender, text) {
    messageCounter += 1;
    const id = `msg-${messageCounter}`;
    const div = document.createElement('div');
    div.className = `msg ${sender}`;
    div.id = id;
    div.innerHTML = (sender === 'assistant' || sender === 'system') ? formatAISummary(text) : text;
    chatMessages.appendChild(div);
    chatMessages.scrollTop = chatMessages.scrollHeight;
    return id;
  }

  function buildTranscriptTextForChat() {
    return (activeSession?.transcript || [])
      .map((segment) => `[${segment.timestamp}] ${segment.speaker}: ${segment.text}`)
      .join('\n');
  }

  async function submitChatQueryPayload(queryText) {
    const text = String(queryText || '').trim();
    if (!text) return;
    appendChatMessage('user', text);
    const loadingId = appendChatMessage('assistant', 'Thinking…');
    const transcriptText = buildTranscriptTextForChat();
    btnChatCancel?.classList.remove('hidden');

    window.api.chatQuery({
      query: text,
      sessionId: activeSession?.id || sessionId,
      scope: 'meeting',
      transcriptText
    }).then((result) => {
      if (!result?.requestId) {
        const node = document.getElementById(loadingId);
        if (node) node.innerHTML = formatAISummary(result?.error || 'Could not reach local AI.');
        btnChatCancel?.classList.add('hidden');
        return;
      }
      activeChatRequestId = result.requestId;
      waitForLlmStream(result.requestId, (partial) => {
        const node = document.getElementById(loadingId);
        if (node) node.innerHTML = formatAISummary(partial);
      }).catch((err) => {
        const node = document.getElementById(loadingId);
        if (node) node.innerHTML = formatAISummary(`Error: ${err.message}`);
      }).finally(() => {
        activeChatRequestId = null;
        btnChatCancel?.classList.add('hidden');
      });
    }).catch((err) => {
      const node = document.getElementById(loadingId);
      if (node) node.innerHTML = formatAISummary(`Error: ${err.message}`);
      btnChatCancel?.classList.add('hidden');
    });
  }

  function submitChat() {
    const text = inputChatQuery.value.trim();
    if (!text) return;
    inputChatQuery.value = '';
    submitChatQueryPayload(text);
  }

  async function runRecipeQuery(recipe) {
    appendChatMessage('user', recipe.label);
    const loadingId = appendChatMessage('assistant', 'Running recipe locally…');
    const transcriptText = buildTranscriptTextForChat();

    window.api.chatQuery({
      query: recipe.id,
      sessionId: activeSession?.id || sessionId,
      scope: 'meeting',
      transcriptText
    }).then((result) => {
      if (!result?.requestId) {
        const node = document.getElementById(loadingId);
        if (node) node.innerHTML = formatAISummary(result?.error || 'Could not reach local AI.');
        return;
      }
      waitForLlmStream(result.requestId, (partial) => {
        const node = document.getElementById(loadingId);
        if (node) node.innerHTML = formatAISummary(partial);
      }).catch((err) => {
        const node = document.getElementById(loadingId);
        if (node) node.innerHTML = formatAISummary(`Error: ${err.message}`);
      });
    });
  }

  document.getElementById('btn-chat-send')?.addEventListener('click', submitChat);
  btnChatCancel?.addEventListener('click', () => {
    if (!activeChatRequestId) return;
    window.api.cancelLlmStream?.(activeChatRequestId);
    btnChatCancel.classList.add('hidden');
  });
  inputChatQuery?.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') submitChat();
  });

  document.addEventListener('keydown', (e) => {
    const isMeta = e.ctrlKey || e.metaKey;
    if (isMeta && e.key.toLowerCase() === 'j') {
      e.preventDefault();
      inputChatQuery?.focus();
    }
    if (isMeta && e.key.toLowerCase() === 'f') {
      e.preventDefault();
      openTranscriptSearch();
    }
    if (isMeta && e.key.toLowerCase() === 's') {
      e.preventDefault();
      if (activeSession && jotEditor) {
        syncSessionFromEditorDocument(activeSession, jotEditor.getDocument());
        void persistSession();
      }
    }
    if (e.key === 'Escape') {
      if (encryptPasswordModal && !encryptPasswordModal.classList.contains('hidden')) {
        document.getElementById('btn-encrypt-password-cancel')?.click();
        return;
      }
      if (transcriptSearchBar && !transcriptSearchBar.classList.contains('hidden')) {
        closeTranscriptSearch();
        return;
      }
      if (conflictModal && !conflictModal.classList.contains('hidden')) {
        hideConflictModal();
      }
    }
  });

  if (window.api.getChatRecipes) {
    window.api.getChatRecipes().then((recipes) => {
      const container = document.getElementById('chat-recipes');
      if (!container) return;
      const preferred = ['action-items', 'follow-up-email', 'qa'];
      const labelOverrides = {
        'action-items': 'List action items',
        'follow-up-email': 'Write follow-up email',
        qa: 'List Q&A',
        decisions: 'List Q&A'
      };
      const ordered = preferred
        .map((id) => recipes.find((r) => r.id === id))
        .filter(Boolean);
      const fallback = recipes.filter((r) => !ordered.includes(r));
      const shown = (ordered.length ? ordered : recipes).concat(
        ordered.length < 3 ? fallback.slice(0, 3 - ordered.length) : []
      ).slice(0, 3);

      shown.forEach((recipe) => {
        const pill = document.createElement('button');
        pill.type = 'button';
        pill.className = 'chat-recipe-pill';
        pill.textContent = labelOverrides[recipe.id] || recipe.label;
        pill.addEventListener('click', () => {
          runRecipeQuery(recipe);
        });
        container.appendChild(pill);
      });
    });
  }

  function focusTranscriptSegment(segmentId) {
    if (!segmentId) return false;
    const line = document.getElementById(`line-${segmentId}`) || document.querySelector(`.subline-${segmentId}`);
    if (!line) return false;
    line.scrollIntoView({ behavior: 'smooth', block: 'center' });
    line.classList.add('highlight-flash');
    setTimeout(() => line.classList.remove('highlight-flash'), 2000);
    return true;
  }

  window.api.onNeedsEncryptionPassword?.(() => {
    void ensureEncryptionPassword().then((pw) => {
      if (pw) void persistSession();
    });
  });

  window.api.onFocusSegment?.((payload) => {
    focusTranscriptSegment(payload?.segmentId);
  });

  window.addEventListener('beforeunload', () => {
    if (!activeSession) return;
    // Flush debounced silent saves so Mix notes / title edits are not lost on close.
    if (activeSession.encrypted) {
      if (sessionEncryptionPassword) {
        window.api.saveCall(activeSession, sessionEncryptionPassword);
      }
      return;
    }
    window.api.saveCall(activeSession);
  });

  // Init session
  window.api.loadMeetingSession(sessionId).then((session) => {
    if (!session) {
      notify('Could not load this meeting session.', 'error');
      return;
    }
    activeSession = session;
    updateTitleUi();
    renderTranscript(session.transcript || []);
    if (jotEditor) jotEditor.loadDocument(normalizeEditorDocument(session));
    renderTagChips();
    updateIdleRecordButton();
    syncEnhanceButton();
    renderPostMeetingPanel();
    if (session.transcript?.length) updateEndedStatusLine();
    if (focusSegmentId) {
      setTimeout(() => focusTranscriptSegment(focusSegmentId), 120);
    }
    if (session.encrypted && !sessionEncryptionPassword) {
      void ensureEncryptionPassword();
    }
    window.api.getSettings?.().then((settings) => {
      if (chipParticipant && settings?.userName) {
        chipParticipant.textContent = settings.userName;
      }
    }).catch(() => {});
    window.api.getRecordingStatus().then((status) => {
      if (status?.sessionId === sessionId && (status.isRecording || status.isPaused)) {
        applyRecordingStatus({
          isRecording: status.isRecording,
          isPaused: status.isPaused,
          isNewSession: false,
          sessionId: status.sessionId
        });
      }
    });
  }).catch((err) => {
    console.error('Failed to load meeting session', err);
    notify(err?.message || 'Could not load this meeting session.', 'error');
  });
})();

// ── Quiet-canvas rail & transcript overlay (redesign 2a, 2026-07-11) ──
// Self-contained wiring for the static controls the redesigned layout added:
// the "View full transcript" overlay toggle and the Share Notes pills.
(function () {
  const drawer = document.getElementById('meeting-transcript-drawer');
  const btnView = document.getElementById('btn-view-transcript');
  const btnClose = document.getElementById('btn-transcript-close');

  function setTranscriptOpen(open) {
    if (!drawer) return;
    drawer.classList.toggle('open', open);
    drawer.setAttribute('aria-hidden', String(!open));
    if (btnView) btnView.setAttribute('aria-expanded', String(open));
  }

  btnView?.addEventListener('click', () => setTranscriptOpen(!drawer?.classList.contains('open')));
  btnClose?.addEventListener('click', () => setTranscriptOpen(false));
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && drawer?.classList.contains('open')) {
      const searchBar = document.getElementById('transcript-search-bar');
      if (searchBar && !searchBar.classList.contains('hidden')) return;
      setTranscriptOpen(false);
    }
  });

  function toast(message, type) {
    if (window.TrailMixToast) window.TrailMixToast.show(message, { type: type || 'info' });
  }

  async function copyText(text, label) {
    if (!text || !text.trim()) {
      toast(`Nothing to copy yet — ${label} is empty.`, 'info');
      return;
    }
    try {
      await navigator.clipboard.writeText(text);
      toast(`${label} copied to clipboard.`, 'success');
    } catch (err) {
      console.error('Clipboard write failed', err);
      toast('Could not copy to clipboard.', 'error');
    }
  }

  function getNotesText() {
    const root = document.getElementById('editor-component-root');
    return root ? root.innerText : '';
  }

  function getSessionIdFromUrl() {
    return new URLSearchParams(window.location.search).get('sessionId') || '';
  }

  document.getElementById('btn-copy-link')?.addEventListener('click', () => {
    const id = getSessionIdFromUrl();
    copyText(id ? `trailmix://session/${id}` : '', 'Session link');
  });

  document.getElementById('btn-copy-notes')?.addEventListener('click', () => {
    copyText(getNotesText(), 'Notes');
  });

  document.getElementById('btn-export-notes')?.addEventListener('click', async () => {
    const id = getSessionIdFromUrl();
    if (!id || !window.api.exportCalls) {
      toast('Export is unavailable for this session.', 'info');
      return;
    }
    try {
      const res = await window.api.exportCalls([id]);
      if (res?.success === false) throw new Error(res.error || 'Export failed');
      toast(res?.filePath ? `Exported to ${res.filePath}` : 'Exported.', 'success');
    } catch (err) {
      toast(err?.message || 'Export failed.', 'error');
    }
  });

  document.getElementById('btn-share-notes')?.addEventListener('click', async () => {
    const title = document.getElementById('input-meeting-title')?.value || 'TrailMix notes';
    const body = getNotesText().trim();
    const payload = `${title}\n\n${body}`.trim();
    await copyText(payload, 'Shareable notes');
  });
})();
