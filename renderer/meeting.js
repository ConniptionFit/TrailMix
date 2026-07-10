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

  const llmStreamBuffers = new Map();
  const llmStreamRenderTimers = new Map();
  const llmStreamWaiters = new Map();

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
      empty.innerHTML = '<span class="empty-icon">🎧</span><p>Start the Trail to begin live transcription.</p>';
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

  async function runMixEnhance(templateKey = null) {
    if (!activeSession || !jotEditor) return;
    const currentDocument = jotEditor.getDocument();
    if (!currentDocument.plainText.trim()) {
      alert('Add some Mix-Ins before running Mix notes.');
      return;
    }
    syncSessionFromEditorDocument(activeSession, currentDocument);
    btnMixEnhance.disabled = true;
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
      editorLegend.classList.remove('hidden');
      await window.api.saveCall(activeSession);
      setSaveStatus('Saved');
    } catch (err) {
      alert(err.message);
    } finally {
      jotEditor.setEnhancing(false);
      btnMixEnhance.disabled = false;
    }
  }

  if (editorHost && window.EditorComponent) {
    jotEditor = new window.EditorComponent(editorHost, {
      onChange: (document) => {
        if (!activeSession) return;
        syncSessionFromEditorDocument(activeSession, document);
        setSaveStatus('Saving…');
        Promise.resolve(window.api.saveCallSilently(activeSession))
          .then((ok) => {
            setSaveStatus(ok === false ? 'Save failed' : 'Saved');
          })
          .catch(() => setSaveStatus('Save failed'));
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

  btnMixEnhance?.addEventListener('click', () => runMixEnhance());

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
      btnRecordToggle.innerHTML = '<span class="btn-icon">⏯</span> Resume Trail';
    } else {
      btnRecordToggle.className = 'btn-record start';
      btnRecordToggle.innerHTML = '<span class="btn-icon">⏺</span> Start Trail';
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
      alert('Unlock this Trail with your password before resuming.');
      return;
    }
    if (!result?.success) {
      alert(result?.error || 'Could not resume this Trail.');
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
    if (btnPauseToggle.textContent.includes('Pause')) window.api.pauseRecording();
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
      btnRecordToggle.className = 'btn-record stop';
      btnRecordToggle.innerHTML = '<span class="btn-icon">⏹</span> Stop Trail';
      btnPauseToggle.className = 'btn-record pause';
      btnPauseToggle.innerHTML = '<span class="btn-icon">⏸</span> Pause';
      btnPauseToggle.classList.remove('hidden');
      recIndicator.className = 'rec-indicator-active';
      recTitle.textContent = 'Transcribing…';

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
      return;
    }

    if (!status.isRecording && status.isPaused) {
      btnRecordToggle.className = 'btn-record stop';
      btnRecordToggle.innerHTML = '<span class="btn-icon">⏹</span> Stop Trail';
      btnPauseToggle.className = 'btn-record start';
      btnPauseToggle.innerHTML = '<span class="btn-icon">▶</span> Resume';
      btnPauseToggle.classList.remove('hidden');
      recIndicator.className = 'rec-indicator-static';
      recTitle.textContent = 'Paused';
      clearInterval(recordingInterval);
      editorWaveformBars?.classList.add('hidden');
      return;
    }

    updateIdleRecordButton();
    btnPauseToggle.classList.add('hidden');
    recIndicator.className = 'rec-indicator-static';
    recTitle.textContent = 'Engine Idle';
    clearInterval(recordingInterval);
    editorWaveformBars?.classList.add('hidden');
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
    appendChatMessage('user', `${recipe.icon || ''} ${recipe.label}`.trim());
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
        setSaveStatus('Saving…');
        Promise.resolve(window.api.saveCallSilently(activeSession))
          .then((ok) => setSaveStatus(ok === false ? 'Save failed' : 'Saved'))
          .catch(() => setSaveStatus('Save failed'));
      }
    }
    if (e.key === 'Escape') {
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
      recipes.forEach((recipe) => {
        const pill = document.createElement('button');
        pill.type = 'button';
        pill.className = 'chat-recipe-pill';
        pill.textContent = `${recipe.icon || ''} ${recipe.label}`.trim();
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

  window.api.onFocusSegment?.((payload) => {
    focusTranscriptSegment(payload?.segmentId);
  });

  // Init session
  window.api.loadMeetingSession(sessionId).then((session) => {
    if (!session) {
      if (window.TrailMixToast) window.TrailMixToast.show('Could not load this meeting session.', { type: 'error' });
      else alert('Could not load this meeting session.');
      return;
    }
    activeSession = session;
    updateTitleUi();
    renderTranscript(session.transcript || []);
    if (jotEditor) jotEditor.loadDocument(normalizeEditorDocument(session));
    updateIdleRecordButton();
    if (focusSegmentId) {
      setTimeout(() => focusTranscriptSegment(focusSegmentId), 120);
    }
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
    if (window.TrailMixToast) window.TrailMixToast.show(err?.message || 'Could not load this meeting session.', { type: 'error' });
    else alert(err?.message || 'Could not load this meeting session.');
  });
})();
