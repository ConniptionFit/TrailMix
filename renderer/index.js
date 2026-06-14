// Check Mode (Mini Widget vs Main Application)
const urlParams = new URLSearchParams(window.location.search);
const isMiniMode = urlParams.get('mode') === 'mini';

// Active Session Cache
let activeSession = null;
let activeFolderId = 'all';
let recordingInterval = null;
let recordingSeconds = 0;

// Global Tooltip variables
let tooltipTimeout = null;
let globalTooltip = null;

// DOM Elements
const mainApp = document.getElementById('main-app');
const miniWidget = document.getElementById('mini-widget');

if (isMiniMode) {
  // Setup Mini mode
  mainApp.classList.add('hidden');
  miniWidget.classList.remove('hidden');
  document.body.style.width = '260px';
  document.body.style.height = '90px';
  document.body.style.background = 'transparent';
  
  const miniToggle = document.getElementById('mini-toggle');
  const miniRelaunch = document.getElementById('mini-relaunch');
  
  miniToggle.addEventListener('click', () => {
    window.api.stopRecording();
  });
  
  miniRelaunch.addEventListener('click', () => {
    window.api.relaunch();
  });

  // Listen to transcription updates in mini mode to know what's happening
  window.api.onTranscriptionUpdate((segment) => {
    console.log('Mini-mode transcription:', segment.text);
  });
} else {
  // Setup Main mode
  mainApp.classList.remove('hidden');
  miniWidget.classList.add('hidden');
  globalTooltip = document.getElementById('global-tooltip');

  function formatTimerSeconds(totalSeconds) {
    const minutes = Math.floor(totalSeconds / 60).toString().padStart(2, '0');
    const seconds = (totalSeconds % 60).toString().padStart(2, '0');
    return `${minutes}:${seconds}`;
  }

  function formatGapDuration(timeDiffMs) {
    const minutes = Math.floor(timeDiffMs / 60000);
    const seconds = Math.floor((timeDiffMs % 60000) / 1000);
    let text = '';
    if (minutes > 0) text += `${minutes}m `;
    text += `${seconds}s`;
    return text;
  }

  function getSegmentTimestampMs(segment) {
    return segment.wallTimeMs || segment.timestampMs;
  }

  function applySpeakerLabelMapping(transcript, mapping) {
    let updatedAny = false;

    transcript.forEach((segment, index) => {
      let label = mapping[segment.id];
      if (!label) {
        label = mapping[(index + 1).toString()] || mapping[index + 1];
      }

      if (!label) return;

      if (label !== 'You' && segment.speaker.toLowerCase() === 'you') {
        return;
      }

      if (segment.speaker !== label) {
        segment.speaker = label;
        updatedAny = true;
      }
    });

    return updatedAny;
  }

  function getDisplaySpeakerName(speakerName) {
    const normalizedSpeaker = speakerName.toLowerCase();
    if (normalizedSpeaker === 'you' || normalizedSpeaker === 'me') {
      return 'Me';
    }
    if (normalizedSpeaker === 'speaker 1') {
      return 'Them';
    }
    return speakerName;
  }

  const llmStreamBuffers = new Map();
  const llmStreamRenderTimers = new Map();
  const llmStreamWaiters = new Map();

  window.api.onLlmStreamChunk((payload) => {
    const waiter = llmStreamWaiters.get(payload.requestId);
    if (!waiter) return;

    if (payload.token) {
      const nextBuffer = (llmStreamBuffers.get(payload.requestId) || '') + payload.token;
      llmStreamBuffers.set(payload.requestId, nextBuffer);

      if (!llmStreamRenderTimers.has(payload.requestId)) {
        llmStreamRenderTimers.set(payload.requestId, requestAnimationFrame(() => {
          llmStreamRenderTimers.delete(payload.requestId);
          waiter.onUpdate(llmStreamBuffers.get(payload.requestId) || '');
        }));
      }
    }

    if (payload.done) {
      const finalText = payload.fullResponse || llmStreamBuffers.get(payload.requestId) || '';
      llmStreamBuffers.delete(payload.requestId);
      llmStreamRenderTimers.delete(payload.requestId);
      llmStreamWaiters.delete(payload.requestId);
      waiter.onUpdate(finalText);

      if (payload.error) {
        waiter.reject(new Error(payload.error));
      } else {
        waiter.resolve(finalText);
      }
    }
  });

  function normalizeEditorDocument(session) {
    if (!session) {
      return { version: 1, mode: 'plain', plainText: '', spans: [], enhancedAt: null };
    }

    if (session.editorDocument) {
      let rawDocument = session.editorDocument;
      if (typeof rawDocument === 'string') {
        try {
          rawDocument = JSON.parse(rawDocument);
        } catch (err) {
          rawDocument = null;
        }
      }

      if (rawDocument?.mode === 'mixed' && Array.isArray(rawDocument.spans) && rawDocument.spans.length > 0) {
        return rawDocument;
      }

      return {
        version: 1,
        mode: 'plain',
        plainText: rawDocument?.plainText || session.mixNotes || '',
        spans: [],
        enhancedAt: null
      };
    }

    return {
      version: 1,
      mode: 'plain',
      plainText: session.mixNotes || '',
      spans: [],
      enhancedAt: null
    };
  }

  function syncSessionFromEditorDocument(session, document) {
    session.mixNotes = document.plainText || '';
    session.editorDocument = document;
    if (document.mode !== 'mixed') {
      session.enhancedNotes = '';
    }
    return session;
  }

  function waitForLlmStream(requestId, onUpdate) {
    return new Promise((resolve, reject) => {
      llmStreamBuffers.set(requestId, '');
      llmStreamWaiters.set(requestId, { onUpdate, resolve, reject });
    });
  }
  
  // Navigation Tabs
  const navDashboard = document.getElementById('nav-dashboard');
  const navCalendar = document.getElementById('nav-calendar');
  const navActionItems = document.getElementById('nav-action-items');
  const navHistory = document.getElementById('nav-history');
  const navSettings = document.getElementById('nav-settings');
  
  const tabDashboard = document.getElementById('tab-dashboard');
  const tabCalendar = document.getElementById('tab-calendar');
  const tabActionItems = document.getElementById('tab-action-items');
  const tabHistory = document.getElementById('tab-history');
  const tabSettings = document.getElementById('tab-settings');
  
  const tabs = [
    { nav: navDashboard, pane: tabDashboard },
    { nav: navCalendar, pane: tabCalendar },
    { nav: navActionItems, pane: tabActionItems },
    { nav: navHistory, pane: tabHistory },
    { nav: navSettings, pane: tabSettings }
  ];
  
  tabs.forEach(tab => {
    tab.nav.addEventListener('click', () => {
      tabs.forEach(t => {
        t.nav.classList.remove('active');
        t.pane.classList.remove('active');
      });
      tab.nav.classList.add('active');
      tab.pane.classList.add('active');
      
      // Refresh context when switching tabs
      if (tab.nav === navHistory) {
        loadHistoryList();
      } else if (tab.nav === navCalendar) {
        renderCalendar();
      } else if (tab.nav === navActionItems) {
        renderActionItems();
      }
    });
  });

  // Audio Device Info
  window.api.getAudioDevices().then(devices => {
    document.getElementById('label-mic-device').textContent = devices.sourceDesc || 'None';
    document.getElementById('label-sys-device').textContent = devices.sinkDesc || 'None';
  });

  // Recording Controls
  const btnRecordToggle = document.getElementById('btn-record-toggle');
  const btnPauseToggle = document.getElementById('btn-pause-toggle');
  const btnResumePast = document.getElementById('btn-resume-past');
  const recIndicator = document.getElementById('rec-indicator');
  const recTitle = document.getElementById('rec-title');
  const recTimer = document.getElementById('rec-timer');
  const transcriptContainer = document.getElementById('transcript-container');
  const btnJumpLatest = document.getElementById('btn-jump-latest');
  
  let lastSegmentTimeMs = null;
  let isUserScrolledUp = false;

  if (transcriptContainer) {
    transcriptContainer.addEventListener('scroll', () => {
      const distanceFromBottom = transcriptContainer.scrollHeight - transcriptContainer.scrollTop - transcriptContainer.clientHeight;
      if (distanceFromBottom > 50) {
        isUserScrolledUp = true;
        if (btnJumpLatest) {
          btnJumpLatest.classList.remove('hidden');
        }
      } else {
        isUserScrolledUp = false;
        if (btnJumpLatest) {
          btnJumpLatest.classList.add('hidden');
        }
      }
    });
  }

  if (btnJumpLatest) {
    btnJumpLatest.addEventListener('click', () => {
      if (transcriptContainer) {
        transcriptContainer.scrollTop = transcriptContainer.scrollHeight;
      }
      isUserScrolledUp = false;
      btnJumpLatest.classList.add('hidden');
    });
  }

  btnRecordToggle.addEventListener('click', () => {
    if (btnRecordToggle.classList.contains('start')) {
      // Clear active session to start a brand new one
      activeSession = {
        id: 'live',
        transcript: [],
        summary: '',
        actionItems: '',
        tags: [],
        suggestedTags: []
      };
      // Clear sidebar selected class
      document.querySelectorAll('.call-list-item').forEach(item => item.classList.remove('selected'));
      isUserScrolledUp = false;
      if (btnJumpLatest) btnJumpLatest.classList.add('hidden');
      transcriptContainer.innerHTML = '';
      document.getElementById('summary-content').innerHTML = '<p class="placeholder-text">AI Highlights will be generated automatically at the end of the transcription session.</p>';
      document.getElementById('action-content').innerHTML = '<p class="placeholder-text">Action items will be extracted at the end of the transcription session.</p>';
      
      if (jotEditor) jotEditor.resetPlain();
      if (editorLegend) editorLegend.classList.add('hidden');
      
      lastSegmentTimeMs = null;
      window.api.startRecording().then(sessionId => {
        if (activeSession && activeSession.id === 'live' && sessionId) {
          activeSession.id = sessionId;
        }
      });
    } else {
      window.api.stopRecording();
    }
  });

  btnPauseToggle.addEventListener('click', () => {
    if (btnPauseToggle.innerHTML.includes('Pause')) {
      window.api.pauseRecording();
    } else {
      window.api.resumeRecording();
    }
  });

  btnResumePast.addEventListener('click', () => {
    if (activeSession && activeSession.filePath) {
      window.api.resumeCallTranscription(activeSession.filePath).then(res => {
        if (res.success) {
          console.log("Resumed session successfully");
        } else if (res.requirePassword) {
          alert("This call is encrypted. Please enter the password to decrypt it first.");
        } else {
          alert("Error resuming: " + res.error);
        }
      });
    }
  });

  // Recording Status listener
  window.api.onRecordingStatus((status) => {
    const { isRecording, isPaused } = status;

    if (isRecording && !isPaused) {
      // Active Transcribing State
      btnRecordToggle.className = 'btn-record stop';
      btnRecordToggle.innerHTML = '<span class="btn-icon">⏹</span> Stop Transcribing';
      btnRecordToggle.classList.remove('hidden');
      
      btnPauseToggle.className = 'btn-record pause';
      btnPauseToggle.innerHTML = '<span class="btn-icon">⏸</span> Pause';
      btnPauseToggle.classList.remove('hidden');
      
      btnResumePast.classList.add('hidden');
      
      recIndicator.className = 'rec-indicator-active';
      recTitle.textContent = 'Transcribing Call...';
      
      // Start or Resume Timer
      if (!recordingInterval) {
        recordingSeconds = 0;
        recTimer.textContent = '00:00';
      }
      clearInterval(recordingInterval);
      recordingInterval = setInterval(() => {
        recordingSeconds++;
        recTimer.textContent = formatTimerSeconds(recordingSeconds);
      }, 1000);
      
      if (!activeSession || activeSession.id === 'live') {
        activeSession = {
          id: 'live',
          transcript: [],
          summary: '',
          actionItems: ''
        };
      }
    } else if (!isRecording && isPaused) {
      // Paused State
      btnRecordToggle.className = 'btn-record stop';
      btnRecordToggle.innerHTML = '<span class="btn-icon">⏹</span> Stop Transcribing';
      btnRecordToggle.classList.remove('hidden');
      
      btnPauseToggle.className = 'btn-record start'; // Green accent for resume
      btnPauseToggle.innerHTML = '<span class="btn-icon">▶</span> Resume';
      btnPauseToggle.classList.remove('hidden');
      
      btnResumePast.classList.add('hidden');
      
      recIndicator.className = 'rec-indicator-static';
      recTitle.textContent = 'Transcription Paused';
      
      clearInterval(recordingInterval);
    } else {
      // Idle UI State
      btnRecordToggle.className = 'btn-record start';
      btnRecordToggle.innerHTML = '<span class="btn-icon">⏺</span> New Transcript';
      btnRecordToggle.classList.remove('hidden');
      
      btnPauseToggle.classList.add('hidden');
      
      if (activeSession && activeSession.id !== 'live' && activeSession.id !== 'call_placeholder') {
        btnResumePast.classList.remove('hidden');
      } else {
        btnResumePast.classList.add('hidden');
      }
      
      recIndicator.className = 'rec-indicator-static';
      if (!activeSession || activeSession.id === 'live') {
        recTitle.textContent = 'Engine Idle';
        recTimer.textContent = '00:00';
      }
      
      clearInterval(recordingInterval);
      recordingInterval = null;
    }
  });

  // Transcription Update Listener
  window.api.onTranscriptionUpdate((segment) => {
    if (!activeSession) return;
    
    // Extract real session ID from segment ID during live call
    if (activeSession.id === 'live' && segment.id && segment.id.startsWith('call_')) {
      const match = segment.id.match(/^(call_\d+)/);
      if (match) {
        activeSession.id = match[1];
      }
    }
    
    // Remove empty placeholder if present
    const emptyState = transcriptContainer.querySelector('.transcript-empty-state');
    if (emptyState) emptyState.remove();
    
    // Render visual gap divider if a large silence/break occurred (> 30 seconds)
    const currentSegmentTime = getSegmentTimestampMs(segment);
    if (lastSegmentTimeMs !== null) {
      const timeDiff = currentSegmentTime - lastSegmentTimeMs;
      if (timeDiff > 30000) {
        appendBreakDivider(formatGapDuration(timeDiff));
      }
    }
    
    activeSession.transcript.push(segment);
    appendTranscriptLine(segment);
    lastSegmentTimeMs = currentSegmentTime;
  });

  function appendBreakDivider(durationText) {
    const div = document.createElement('div');
    div.className = 'transcript-break-divider';
    div.innerHTML = `<span>⏳ [Break: ${durationText}]</span>`;
    transcriptContainer.appendChild(div);
  }

  // Speaker Label Updates Listener
  window.api.onSpeakerLabelsUpdated((mapping) => {
    console.log('Received speaker labels mapping update:', mapping);
    if (!activeSession?.transcript) return;

    const updatedAny = applySpeakerLabelMapping(activeSession.transcript, mapping);
    if (!updatedAny) return;

    console.log('Speakers updated. Re-rendering transcript pane to split/merge bubbles...');
    lastSegmentTimeMs = renderTranscriptWithBreaks(activeSession.transcript, { preserveScroll: true });
  });

  const speakerColors = {};
  const colorPalette = [
    'var(--primary)', // Moss/Lime Green (for You)
    '#39c6b7', // Teal/Cyan
    '#3d8bff', // Neon Blue
    '#ff7675', // Soft Coral/Red
    '#fdcb6e', // Peach/Orange
    '#a29bfe', // Lavender/Purple
    '#00cec9', // Mint/Turquoise
    '#e84393', // Deep Pink
    '#ffeaa7', // Pale Yellow
    '#0984e3'  // Sky Blue
  ];

  function getSpeakerColor(speakerName) {
    const nameLower = speakerName.toLowerCase();
    if (nameLower === 'you' || nameLower === 'me') {
      return 'var(--primary)';
    }
    if (nameLower === 'speaker 1' || nameLower === 'them') {
      return '#3d8bff';
    }
    if (!speakerColors[speakerName]) {
      const index = (Object.keys(speakerColors).length % (colorPalette.length - 1)) + 1;
      speakerColors[speakerName] = colorPalette[index];
    }
    return speakerColors[speakerName];
  }

  function appendTranscriptLine(segment) {
    const lastLineDiv = transcriptContainer.lastElementChild;
    let merged = false;
    
    const isMe = segment.speaker.toLowerCase() === 'you' || segment.speaker.toLowerCase() === 'me';
    const displaySpeakerName = getDisplaySpeakerName(segment.speaker);
    
    if (lastLineDiv && lastLineDiv.classList.contains('transcript-line')) {
      const speakerSpan = lastLineDiv.querySelector('.line-speaker');
      const textDiv = lastLineDiv.querySelector('.line-text');
      
      if (speakerSpan && textDiv) {
        const lastSpeaker = speakerSpan.textContent.trim();
        const lastTimestampMs = parseInt(lastLineDiv.getAttribute('data-timestamp-ms') || '0', 10);
        const timeDiffMs = segment.timestampMs - lastTimestampMs;
        
        if (lastSpeaker.toLowerCase() === displaySpeakerName.toLowerCase() && timeDiffMs < 8000) {
          textDiv.textContent += ' ' + segment.text;
          lastLineDiv.setAttribute('data-timestamp-ms', segment.timestampMs);
          lastLineDiv.classList.add(`subline-${segment.id}`);
          merged = true;
        }
      }
    }
    
    if (!merged) {
      const lineDiv = document.createElement('div');
      lineDiv.id = `line-${segment.id}`;
      lineDiv.setAttribute('data-timestamp-ms', segment.timestampMs);
      
      lineDiv.className = `transcript-line ${isMe ? 'align-left' : 'align-right'}`;
      
      const speakerClass = isMe ? 'you' : 'inbound';
      const speakerColor = getSpeakerColor(displaySpeakerName);
      
      lineDiv.innerHTML = `
        <div class="line-meta">
          <span class="line-speaker ${speakerClass}" style="color: ${speakerColor}">${displaySpeakerName}</span>
          <span class="line-time">${segment.timestamp}</span>
        </div>
        <div class="line-text">${segment.text}</div>
      `;
      
      transcriptContainer.appendChild(lineDiv);
    }
    
    if (!isUserScrolledUp) {
      transcriptContainer.scrollTop = transcriptContainer.scrollHeight;
    }
  }

  function renderTranscriptWithBreaks(transcript, options = {}) {
    const { preserveScroll = false } = options;
    const currentScrollTop = transcriptContainer.scrollTop;
    const wasAtBottom = (transcriptContainer.scrollHeight - transcriptContainer.scrollTop - transcriptContainer.clientHeight) < 50;

    transcriptContainer.innerHTML = '';
    let lastTime = null;

    transcript.forEach((segment) => {
      const segmentTime = getSegmentTimestampMs(segment);
      if (lastTime !== null) {
        const timeDiff = segmentTime - lastTime;
        if (timeDiff > 30000) {
          appendBreakDivider(formatGapDuration(timeDiff));
        }
      }
      appendTranscriptLine(segment);
      lastTime = segmentTime;
    });

    if (preserveScroll) {
      transcriptContainer.scrollTop = wasAtBottom
        ? transcriptContainer.scrollHeight
        : currentScrollTop;
    } else if (!isUserScrolledUp) {
      transcriptContainer.scrollTop = transcriptContainer.scrollHeight;
    }

    return lastTime;
  }

  // Summary Tab Toggle
  const btnShowSummary = document.getElementById('btn-show-summary');
  const btnShowActions = document.getElementById('btn-show-actions');
  const btnShowMix = document.getElementById('btn-show-mix');
  const summaryContent = document.getElementById('summary-content');
  const actionContent = document.getElementById('action-content');
  const mixContent = document.getElementById('mix-content');
  const editorHost = document.getElementById('editor-component-root');
  const btnMixEnhance = document.getElementById('btn-mix-enhance');
  const editorLegend = document.getElementById('editor-legend');

  let jotEditor = null;
  if (editorHost && window.EditorComponent) {
    jotEditor = new window.EditorComponent(editorHost, {
      onChange: (document) => {
        if (!activeSession) return;
        syncSessionFromEditorDocument(activeSession, document);
        window.api.saveCallSilently(activeSession);
      }
    });
  }

  if (editorLegend) {
    editorLegend.classList.add('hidden');
  }

  btnShowSummary.addEventListener('click', () => {
    btnShowSummary.classList.add('active');
    btnShowActions.classList.remove('active');
    if (btnShowMix) btnShowMix.classList.remove('active');
    summaryContent.classList.remove('hidden');
    actionContent.classList.add('hidden');
    if (mixContent) mixContent.classList.add('hidden');
  });

  btnShowActions.addEventListener('click', () => {
    btnShowActions.classList.add('active');
    btnShowSummary.classList.remove('active');
    if (btnShowMix) btnShowMix.classList.remove('active');
    actionContent.classList.remove('hidden');
    summaryContent.classList.add('hidden');
    if (mixContent) mixContent.classList.add('hidden');
  });

  if (btnShowMix) {
    btnShowMix.addEventListener('click', () => {
      btnShowMix.classList.add('active');
      btnShowSummary.classList.remove('active');
      btnShowActions.classList.remove('active');
      if (mixContent) mixContent.classList.remove('hidden');
      summaryContent.classList.add('hidden');
      actionContent.classList.add('hidden');
    });
  }

  if (btnMixEnhance) {
    btnMixEnhance.addEventListener('click', async () => {
      if (!activeSession || !jotEditor) return;

      const currentDocument = jotEditor.getDocument();
      if (!currentDocument.plainText.trim()) {
        alert('Please enter some jots first!');
        return;
      }

      syncSessionFromEditorDocument(activeSession, currentDocument);

      btnMixEnhance.disabled = true;
      btnMixEnhance.innerHTML = '✨ Enhancing...';
      jotEditor.setEnhancing(true);

      try {
        const result = await window.api.mixEnhance({
          plainText: currentDocument.plainText,
          editorDocument: currentDocument,
          transcript: activeSession.transcript || []
        });

        if (!result?.success) {
          throw new Error(result?.error || 'Failed to enhance notes.');
        }

        activeSession.editorDocument = result.editorDocument;
        activeSession.enhancedNotes = result.enhancedNotes;
        activeSession.mixNotes = result.editorDocument.plainText || currentDocument.plainText;
        jotEditor.applyEnhancedDocument(result.editorDocument);

        if (editorLegend) {
          editorLegend.classList.remove('hidden');
        }

        await window.api.saveCall(activeSession);
      } catch (err) {
        console.error(err);
        alert(err.message || 'Failed to enhance notes.');
        jotEditor.setEnhancing(false);
      } finally {
        btnMixEnhance.disabled = false;
        btnMixEnhance.innerHTML = '✨ Enhance Notes';
      }
    });
  }

  // ----------------------------------------------------
  // Sidebar and History Loading
  // ----------------------------------------------------
  const sidebarCallsList = document.getElementById('sidebar-calls-list');
  const historyGrid = document.getElementById('history-grid');
  
  let isMultiSelectMode = false;
  let relatedCallsList = null;
  let contextMenuTargetCall = null;
  const callsContextMenu = document.getElementById('calls-context-menu');
  
  const sidebarBulkActions = document.getElementById('sidebar-bulk-actions');
  const btnBulkMerge = document.getElementById('btn-bulk-merge');
  const btnBulkExport = document.getElementById('btn-bulk-export');
  const btnBulkDelete = document.getElementById('btn-bulk-delete');
  const btnBulkCancel = document.getElementById('btn-bulk-cancel');
  
  let historySortOrder = 'date-newest'; // default

  const btnHistoryMenu = document.getElementById('btn-history-menu');
  const historyMenuDropdown = document.getElementById('history-menu-dropdown');
  const menuOptMultiselect = document.getElementById('menu-opt-multiselect');
  const menuOptSortNewest = document.getElementById('menu-opt-sort-newest');
  const menuOptSortOldest = document.getElementById('menu-opt-sort-oldest');
  const menuOptSortTitleAsc = document.getElementById('menu-opt-sort-title-asc');
  const menuOptSortTitleDesc = document.getElementById('menu-opt-sort-title-desc');
  
  if (btnHistoryMenu && historyMenuDropdown) {
    btnHistoryMenu.addEventListener('click', (e) => {
      e.stopPropagation();
      historyMenuDropdown.classList.toggle('hidden');
    });
    document.addEventListener('click', () => {
      historyMenuDropdown.classList.add('hidden');
    });
  }

  function updateSortActiveUI() {
    [menuOptSortNewest, menuOptSortOldest, menuOptSortTitleAsc, menuOptSortTitleDesc].forEach(opt => {
      if (opt) opt.classList.remove('active-sort');
    });
    if (historySortOrder === 'date-newest' && menuOptSortNewest) menuOptSortNewest.classList.add('active-sort');
    if (historySortOrder === 'date-oldest' && menuOptSortOldest) menuOptSortOldest.classList.add('active-sort');
    if (historySortOrder === 'title-asc' && menuOptSortTitleAsc) menuOptSortTitleAsc.classList.add('active-sort');
    if (historySortOrder === 'title-desc' && menuOptSortTitleDesc) menuOptSortTitleDesc.classList.add('active-sort');
  }

  if (menuOptMultiselect) {
    menuOptMultiselect.addEventListener('click', (e) => {
      e.stopPropagation();
      historyMenuDropdown.classList.add('hidden');
      isMultiSelectMode = !isMultiSelectMode;
      toggleMultiSelectModeUI();
    });
  }

  if (menuOptSortNewest) {
    menuOptSortNewest.addEventListener('click', (e) => {
      e.stopPropagation();
      historySortOrder = 'date-newest';
      updateSortActiveUI();
      historyMenuDropdown.classList.add('hidden');
      loadHistoryList();
    });
  }
  if (menuOptSortOldest) {
    menuOptSortOldest.addEventListener('click', (e) => {
      e.stopPropagation();
      historySortOrder = 'date-oldest';
      updateSortActiveUI();
      historyMenuDropdown.classList.add('hidden');
      loadHistoryList();
    });
  }
  if (menuOptSortTitleAsc) {
    menuOptSortTitleAsc.addEventListener('click', (e) => {
      e.stopPropagation();
      historySortOrder = 'title-asc';
      updateSortActiveUI();
      historyMenuDropdown.classList.add('hidden');
      loadHistoryList();
    });
  }
  if (menuOptSortTitleDesc) {
    menuOptSortTitleDesc.addEventListener('click', (e) => {
      e.stopPropagation();
      historySortOrder = 'title-desc';
      updateSortActiveUI();
      historyMenuDropdown.classList.add('hidden');
      loadHistoryList();
    });
  }
  
  if (btnBulkCancel) {
    btnBulkCancel.addEventListener('click', () => {
      isMultiSelectMode = false;
      toggleMultiSelectModeUI();
    });
  }
  
  function toggleMultiSelectModeUI() {
    if (isMultiSelectMode) {
      sidebarBulkActions.classList.remove('hidden');
    } else {
      sidebarBulkActions.classList.add('hidden');
    }
    loadHistoryList();
    updateBulkSelectState();
  }
  
  function updateBulkSelectState() {
    const checked = document.querySelectorAll('.call-item-checkbox:checked');
    const countSpan = document.getElementById('bulk-select-count');
    if (countSpan) {
      countSpan.textContent = `${checked.length} selected`;
    }
    
    // Enable/disable actions
    if (btnBulkMerge) btnBulkMerge.disabled = checked.length < 2;
    if (btnBulkExport) btnBulkExport.disabled = checked.length < 1;
    if (btnBulkDelete) btnBulkDelete.disabled = checked.length < 1;
  }
  
  if (btnBulkMerge) {
    btnBulkMerge.addEventListener('click', () => {
      const checked = Array.from(document.querySelectorAll('.call-item-checkbox:checked'));
      if (checked.length < 2) return;
      const paths = checked.map(cb => cb.getAttribute('data-filepath'));
      window.api.mergeCalls(paths).then(res => {
        if (res.success) {
          alert(`Successfully merged selected calls into "${res.session.title}"`);
          isMultiSelectMode = false;
          toggleMultiSelectModeUI();
          loadHistoryList();
        } else {
          alert("Merge failed: " + res.error);
        }
      });
    });
  }
  
  if (btnBulkExport) {
    btnBulkExport.addEventListener('click', () => {
      const checked = Array.from(document.querySelectorAll('.call-item-checkbox:checked'));
      if (checked.length < 1) return;
      const paths = checked.map(cb => cb.getAttribute('data-filepath'));
      window.api.exportCalls(paths).then(res => {
        if (res.success) {
          alert(`Successfully exported transcripts to: ${res.filePath}`);
        } else {
          alert("Export failed: " + res.error);
        }
        isMultiSelectMode = false;
        toggleMultiSelectModeUI();
      });
    });
  }
  
  if (btnBulkDelete) {
    btnBulkDelete.addEventListener('click', () => {
      const checked = Array.from(document.querySelectorAll('.call-item-checkbox:checked'));
      if (checked.length < 1) return;
      if (confirm(`Are you sure you want to delete the ${checked.length} selected transcripts?`)) {
        const paths = checked.map(cb => cb.getAttribute('data-filepath'));
        window.api.deleteMultipleCalls(paths).then(res => {
          isMultiSelectMode = false;
          toggleMultiSelectModeUI();
          loadHistoryList();
        });
      }
    });
  }
  
  // Right-click items handlers
  const ctxOpenFile = document.getElementById('ctx-open-file');
  if (ctxOpenFile) {
    ctxOpenFile.addEventListener('click', () => {
      if (contextMenuTargetCall) {
        window.api.openFileLocation(contextMenuTargetCall.filePath);
      }
      callsContextMenu.classList.add('hidden');
    });
  }
  
  const ctxFindRelated = document.getElementById('ctx-find-related');
  if (ctxFindRelated) {
    ctxFindRelated.addEventListener('click', () => {
      if (contextMenuTargetCall) {
        window.api.findRelatedCalls(contextMenuTargetCall.filePath).then(related => {
          relatedCallsList = related;
          loadHistoryList();
        });
      }
      callsContextMenu.classList.add('hidden');
    });
  }
  
  const ctxSelectMode = document.getElementById('ctx-select-mode');
  if (ctxSelectMode) {
    ctxSelectMode.addEventListener('click', () => {
      isMultiSelectMode = true;
      toggleMultiSelectModeUI();
      if (contextMenuTargetCall) {
        setTimeout(() => {
          const checkboxes = document.querySelectorAll('.call-item-checkbox');
          checkboxes.forEach(cb => {
            if (cb.getAttribute('data-filepath') === contextMenuTargetCall.filePath) {
              cb.checked = true;
            }
          });
          updateBulkSelectState();
        }, 50);
      }
      callsContextMenu.classList.add('hidden');
    });
  }
  
  const ctxDeleteCall = document.getElementById('ctx-delete-call');
  if (ctxDeleteCall) {
    ctxDeleteCall.addEventListener('click', () => {
      if (contextMenuTargetCall) {
        if (confirm(`Are you sure you want to delete "${contextMenuTargetCall.title}"?`)) {
          window.api.deleteCall(contextMenuTargetCall.filePath).then((res) => {
            if (res.success) {
              loadHistoryList();
              if (activeSession && activeSession.filePath === contextMenuTargetCall.filePath) {
                activeSession = null;
                document.getElementById('transcript-container').innerHTML = `
                  <div class="transcript-empty-state">
                    <span class="empty-icon">🎧</span>
                    <p>Click "New Transcript" or select a past session from the history sidebar to begin.</p>
                  </div>
                `;
                document.getElementById('summary-content').innerHTML = '<p class="placeholder-text">AI Highlights will be generated automatically at the end of the transcription session.</p>';
                document.getElementById('action-content').innerHTML = '<p class="placeholder-text">Action items will be extracted at the end of the transcription session.</p>';
              }
            } else {
              alert("Error deleting call: " + res.error);
            }
          });
        }
      }
      callsContextMenu.classList.add('hidden');
    });
  }
  
  document.addEventListener('click', (e) => {
    if (callsContextMenu && !callsContextMenu.contains(e.target)) {
      callsContextMenu.classList.add('hidden');
    }
  });

  function loadHistoryList() {
    // Update header first
    const headerTitle = document.querySelector('.sidebar-history-header h3');
    if (headerTitle) {
      if (relatedCallsList) {
        headerTitle.innerHTML = `Related Calls <span id="btn-clear-related" style="cursor:pointer;font-size:10px;color:var(--primary);margin-left:6px;text-decoration:underline;">(Reset)</span>`;
        const btnClearRelated = document.getElementById('btn-clear-related');
        if (btnClearRelated) {
          btnClearRelated.addEventListener('click', (e) => {
            e.stopPropagation();
            relatedCallsList = null;
            loadHistoryList();
          });
        }
      } else {
        headerTitle.textContent = 'Recent Transcriptions';
      }
    }

    const fetchPromise = relatedCallsList 
      ? Promise.resolve(relatedCallsList) 
      : window.api.getCallList();

    const searchInput = document.getElementById('input-sidebar-search');
    const searchTerm = searchInput ? searchInput.value.trim().toLowerCase() : '';

    fetchPromise.then((calls) => {
      sidebarCallsList.innerHTML = '';
      historyGrid.innerHTML = '';
      
      let filteredCalls = calls;
      
      // Filter by active folder
      if (activeFolderId && activeFolderId !== 'all') {
        filteredCalls = filteredCalls.filter(c => c.folder_id === activeFolderId);
      }
      
      // Sort calls
      if (historySortOrder === 'date-newest') {
        filteredCalls.sort((a, b) => (b.mtimeMs || 0) - (a.mtimeMs || 0));
      } else if (historySortOrder === 'date-oldest') {
        filteredCalls.sort((a, b) => (a.mtimeMs || 0) - (b.mtimeMs || 0));
      } else if (historySortOrder === 'title-asc') {
        filteredCalls.sort((a, b) => (a.title || '').localeCompare(b.title || ''));
      } else if (historySortOrder === 'title-desc') {
        filteredCalls.sort((a, b) => (b.title || '').localeCompare(a.title || ''));
      }
      
      if (searchTerm) {
        if (searchTerm.startsWith('#')) {
          const targetTag = searchTerm.substring(1);
          filteredCalls = filteredCalls.filter(c => c.tags && c.tags.some(t => t.toLowerCase() === targetTag));
        } else {
          filteredCalls = filteredCalls.filter(c => 
            (c.title && c.title.toLowerCase().includes(searchTerm)) ||
            (c.summary && c.summary.toLowerCase().includes(searchTerm)) ||
            (c.description && c.description.toLowerCase().includes(searchTerm)) ||
            (c.tags && c.tags.some(t => t.toLowerCase().includes(searchTerm)))
          );
        }
      }
      
      if (filteredCalls.length === 0) {
        sidebarCallsList.innerHTML = '<div class="text-center text-xs text-slate-500 py-6">No matching calls.</div>';
        historyGrid.innerHTML = '<div class="text-center text-xs text-slate-500 py-6">No matching calls.</div>';
        return;
      }
      
      filteredCalls.forEach((call) => {
        // Sidebar list
        const item = document.createElement('div');
        
        // Highlight active past transcript
        const isSelected = activeSession && activeSession.id === call.id;
        item.className = `call-list-item ${isSelected ? 'selected' : ''}`;
        
        item.setAttribute('data-callid', call.id);
        
        let checkboxHtml = '';
        if (isMultiSelectMode) {
          checkboxHtml = `<input type="checkbox" class="call-item-checkbox mt-1 rounded bg-slate-950 border-white/10 text-trail-500 focus:ring-0" data-filepath="${call.filePath}">`;
        }
        
        const tagline = call.title || 'Meeting Session';
        const description = call.encrypted ? 'Encrypted session' : (call.description || 'No description available.');
        
        // Render tags HTML
        let tagsHtml = '';
        const tags = call.tags || [];
        const suggestedTags = call.suggestedTags || [];
        
        if (tags.length > 0 || suggestedTags.length > 0) {
          tagsHtml = `<div class="call-tags-container flex flex-wrap gap-1 mt-2">`;
          tags.forEach(tag => {
            tagsHtml += `<span class="tag-pill bg-trail-500/10 border border-trail-500/20 text-trail-400 rounded px-1.5 py-0.5 text-[9px] font-medium">#${tag}</span>`;
          });
          
          if (!isMultiSelectMode) {
            suggestedTags.forEach(tag => {
              tagsHtml += `<span class="suggested-tag-pill bg-white/5 border border-dashed border-white/20 text-slate-400 hover:text-white rounded px-1.5 py-0.5 text-[9px] font-medium cursor-pointer transition" data-tag="${tag}">+ ${tag}</span>`;
            });
          }
          tagsHtml += `</div>`;
        }
        
        item.innerHTML = `
          ${checkboxHtml}
          <div class="call-item-details flex-1 min-w-0 cursor-pointer">
            <div class="flex items-center justify-between">
              <div class="call-item-title font-semibold text-xs truncate group-hover:text-white transition">${call.encrypted ? '🔒 ' : ''}${tagline}</div>
            </div>
            <div class="call-item-date text-[9px] text-slate-500 mt-0.5 font-medium">${call.date}</div>
            <div class="call-item-desc text-[10px] text-slate-400 mt-1 line-clamp-2 leading-relaxed break-words">${description}</div>
            ${tagsHtml}
          </div>
        `;
        
        // Handle select or check
        item.addEventListener('click', (e) => {
          if (tooltipTimeout) clearTimeout(tooltipTimeout);
          if (globalTooltip) {
            globalTooltip.classList.remove('visible');
            globalTooltip.classList.add('hidden');
          }

          if (isMultiSelectMode) {
            const cb = item.querySelector('.call-item-checkbox');
            if (cb && e.target !== cb) {
              cb.checked = !cb.checked;
            }
            updateBulkSelectState();
          } else {
            handleCallSelect(call);
          }
        });

        // Hover tooltip logic with 1-second delay
        item.addEventListener('mouseenter', () => {
          if (tooltipTimeout) clearTimeout(tooltipTimeout);
          
          tooltipTimeout = setTimeout(() => {
            if (!globalTooltip) return;
            const rect = item.getBoundingClientRect();
            
            // Position tooltip to the right of the item
            let left = rect.right + 10;
            if (left + 240 > window.innerWidth) {
              left = rect.left - 230;
            }
            
            const top = rect.top + rect.height / 2;
            
            globalTooltip.innerHTML = `
              <strong>${tagline}</strong><br>
              <span>${description}</span>
            `;
            
            globalTooltip.style.left = `${left}px`;
            globalTooltip.style.top = `${top}px`;
            globalTooltip.style.transform = 'translateY(-50%)';
            globalTooltip.classList.remove('hidden');
            globalTooltip.offsetHeight;
            globalTooltip.classList.add('visible');
          }, 1000);
        });
        
        item.addEventListener('mouseleave', () => {
          if (tooltipTimeout) clearTimeout(tooltipTimeout);
          if (globalTooltip) {
            globalTooltip.classList.remove('visible');
            globalTooltip.classList.add('hidden');
          }
        });
        
        // Bind click on suggested tags
        if (!isMultiSelectMode) {
          const sugPills = item.querySelectorAll('.suggested-tag-pill');
          sugPills.forEach(pill => {
            pill.addEventListener('click', (e) => {
              e.stopPropagation();
              const tagToAdd = pill.getAttribute('data-tag');
              
              window.api.loadCall(call.filePath).then(res => {
                if (res.success) {
                  const callData = res.session;
                  if (!callData.tags) callData.tags = [];
                  if (!callData.tags.includes(tagToAdd)) {
                    callData.tags.push(tagToAdd);
                  }
                  if (callData.suggestedTags) {
                    callData.suggestedTags = callData.suggestedTags.filter(t => t !== tagToAdd);
                  }
                  
                  window.api.saveCall(callData).then(() => {
                    loadHistoryList();
                    if (activeSession && activeSession.id === callData.id) {
                      activeSession.tags = callData.tags;
                      activeSession.suggestedTags = callData.suggestedTags;
                    }
                  });
                } else if (res.requirePassword) {
                  pendingCallToDecrypt = call;
                  modalPasswordInput.value = '';
                  modalErrorMessage.classList.add('hidden');
                  passwordModal.classList.remove('hidden');
                }
              });
            });
          });
        }
        
        if (isMultiSelectMode) {
          const cb = item.querySelector('.call-item-checkbox');
          if (cb) {
            cb.addEventListener('change', () => {
              updateBulkSelectState();
            });
          }
        }
        
        // Context menu listener
        item.addEventListener('contextmenu', (e) => {
          e.preventDefault();
          contextMenuTargetCall = call;
          if (callsContextMenu) {
            callsContextMenu.style.left = `${e.clientX}px`;
            callsContextMenu.style.top = `${e.clientY}px`;
            callsContextMenu.classList.remove('hidden');
          }
        });
        
        sidebarCallsList.appendChild(item);

        // History tab grid
        if (!relatedCallsList) {
          const card = document.createElement('div');
          card.className = 'history-card glassmorphic p-4 rounded-xl border border-white/5 bg-slate-900/20 hover:bg-slate-900/40 hover:border-trail-500/20 cursor-pointer transition flex flex-col gap-2';
          card.innerHTML = `
            <div class="flex justify-between items-start gap-2">
              <h3 class="text-sm font-bold text-white leading-tight">${call.title}</h3>
              ${call.encrypted ? '<span class="px-2 py-0.5 rounded text-[9px] font-bold bg-amber-500/10 text-amber-400">🔒 Locked</span>' : ''}
            </div>
            <div class="text-[10px] text-slate-500 font-semibold">${call.date}</div>
            <p class="text-xs text-slate-400 line-clamp-3 leading-relaxed">${call.encrypted ? 'Encrypted session' : (call.summary || 'No summary available.')}</p>
          `;
          card.addEventListener('click', () => {
            tabs.forEach(t => {
              t.nav.classList.remove('active');
              t.pane.classList.remove('active');
            });
            navDashboard.classList.add('active');
            tabDashboard.classList.add('active');
            handleCallSelect(call);
          });
          historyGrid.appendChild(card);
        }
      });
      
      if (relatedCallsList && historyGrid) {
        historyGrid.innerHTML = '<div class="empty-state">Filtered for related calls. View the sidebar list.</div>';
      }
    });
  }

  // Folders Rendering and Management (v0.2)
  function renderFolders() {
    window.api.getFolders().then((folders) => {
      const folderList = document.getElementById('sidebar-folders-list');
      if (!folderList) return;
      folderList.innerHTML = '';

      // All Preserves default item
      const allItem = document.createElement('div');
      allItem.className = `folder-list-item flex items-center justify-between px-2.5 py-1.5 rounded-lg cursor-pointer transition text-xs ${
        activeFolderId === 'all' 
          ? 'bg-trail-500/10 text-trail-400 font-semibold border border-trail-500/20' 
          : 'text-slate-400 border border-transparent hover:bg-slate-800/30 hover:text-slate-200'
      }`;
      allItem.innerHTML = `
        <div class="flex items-center gap-2">
          <span>📂</span>
          <span>All Preserves</span>
        </div>
      `;
      allItem.addEventListener('click', () => {
        activeFolderId = 'all';
        renderFolders();
        loadHistoryList();
      });
      folderList.appendChild(allItem);

      // Render database folders
      folders.forEach(folder => {
        const item = document.createElement('div');
        const isSelected = activeFolderId === folder.id;
        item.className = `folder-list-item flex items-center justify-between px-2.5 py-1.5 rounded-lg cursor-pointer transition text-xs ${
          isSelected 
            ? 'bg-trail-500/10 text-trail-400 font-semibold border border-trail-500/20' 
            : 'text-slate-400 border border-transparent hover:bg-slate-800/30 hover:text-slate-200'
        }`;
        
        item.innerHTML = `
          <div class="flex items-center gap-2 flex-grow truncate">
            <span>📁</span>
            <span class="truncate">${folder.name}</span>
          </div>
          <div class="flex items-center gap-1.5 folder-actions opacity-60 hover:opacity-100 transition">
            <button class="btn-folder-export p-0.5 text-slate-400 hover:text-trail-400 transition" title="Export Folder to Obsidian" data-folderid="${folder.id}">📤</button>
            <button class="btn-folder-delete p-0.5 text-slate-400 hover:text-red-400 transition" title="Delete Folder" data-folderid="${folder.id}">🗑️</button>
          </div>
        `;
        
        item.addEventListener('click', (e) => {
          if (e.target.closest('.folder-actions')) return;
          activeFolderId = folder.id;
          renderFolders();
          loadHistoryList();
        });

        // Delete Folder
        item.querySelector('.btn-folder-delete').addEventListener('click', (e) => {
          e.stopPropagation();
          if (confirm(`Delete folder "${folder.name}"? Notes inside will not be deleted but will become uncategorized.`)) {
            window.api.deleteFolder(folder.id).then(() => {
              if (activeFolderId === folder.id) activeFolderId = 'all';
              renderFolders();
              loadHistoryList();
            });
          }
        });

        // Export Folder to Obsidian
        item.querySelector('.btn-folder-export').addEventListener('click', (e) => {
          e.stopPropagation();
          window.api.selectDirectory().then(dir => {
            if (dir) {
              window.api.exportObsidian(folder.id, dir).then(res => {
                if (res.success) {
                  alert(`Successfully exported ${res.count} notes to your Obsidian vault at: ${dir}`);
                } else {
                  alert(`Export failed: ${res.error}`);
                }
              });
            }
          });
        });

        folderList.appendChild(item);
      });
      
      // Update the Move-To dropdown inside the active call view
      updateMoveFolderDropdown(folders);
    });
  }

  function updateMoveFolderDropdown(folders) {
    const dropdown = document.getElementById('select-move-folder');
    if (!dropdown) return;
    dropdown.innerHTML = '<option value="">📁 Move to Folder...</option>';
    
    folders.forEach(f => {
      const opt = document.createElement('option');
      opt.value = f.id;
      opt.textContent = f.name;
      if (activeSession && activeSession.folder_id === f.id) {
        opt.selected = true;
      }
      dropdown.appendChild(opt);
    });
  }

  // Bind dropdown movement change
  const selectMoveFolder = document.getElementById('select-move-folder');
  if (selectMoveFolder) {
    selectMoveFolder.addEventListener('change', () => {
      if (!activeSession || activeSession.id === 'live') return;
      const folderId = selectMoveFolder.value;
      window.api.moveToFolder(activeSession.id, folderId || null).then(res => {
        if (res.success) {
          activeSession.folder_id = folderId || null;
          loadHistoryList();
        } else {
          alert('Error moving note: ' + res.error);
        }
      });
    });
  }

  // Add Folder Button
  const btnAddFolder = document.getElementById('btn-add-folder');
  if (btnAddFolder) {
    btnAddFolder.addEventListener('click', () => {
      const name = prompt("Enter new folder name:");
      if (name && name.trim()) {
        window.api.createFolder(name.trim()).then(res => {
          if (res.success) {
            renderFolders();
          } else {
            alert("Error creating folder: " + res.error);
          }
        });
      }
    });
  }

  // Single note Obsidian export
  const btnExportObsidianNote = document.getElementById('btn-export-obsidian-note');
  if (btnExportObsidianNote) {
    btnExportObsidianNote.addEventListener('click', () => {
      if (!activeSession || activeSession.id === 'live') {
        alert("Please select a saved call session first.");
        return;
      }
      window.api.selectDirectory().then(dir => {
        if (dir) {
          window.api.exportObsidian(activeSession.id, dir).then(res => {
            if (res.success) {
              alert("Successfully exported note to your Obsidian vault!");
            } else {
              alert("Failed to export: " + res.error);
            }
          });
        }
      });
    });
  }

  // Collapsible Live Trail drawer toggle
  const btnToggleLiveTrail = document.getElementById('btn-toggle-live-trail');
  const btnCloseLiveTrail = document.getElementById('btn-close-live-trail');
  const liveTrailDrawer = document.getElementById('live-trail-drawer');

  if (btnToggleLiveTrail && liveTrailDrawer) {
    btnToggleLiveTrail.addEventListener('click', () => {
      liveTrailDrawer.classList.toggle('hidden');
    });
  }
  if (btnCloseLiveTrail && liveTrailDrawer) {
    btnCloseLiveTrail.addEventListener('click', () => {
      liveTrailDrawer.classList.add('hidden');
    });
  }
  // Initial load
  renderFolders();
  loadHistoryList();
  renderCalendar();
  renderActionItems();

  // Listen to call list updates from main process (including directory watcher)
  if (window.api.onCallListUpdated) {
    window.api.onCallListUpdated(() => {
      console.log('Received calls list update from main process. Reloading...');
      loadHistoryList();
    });
  }

  // Listen to background summary updates
  if (window.api.onSessionSummaryReady) {
    window.api.onSessionSummaryReady((session) => {
      console.log('Received session summary ready:', session);
      // Auto-refresh active session if it matches
      if (activeSession && (activeSession.id === 'live' || activeSession.id === session.id || session.id.includes(activeSession.id) || activeSession.id.includes(session.id))) {
        displaySession(session);
      }
    });
  }
  
  // ----------------------------------------------------
  // Encryption & Password Modal
  // ----------------------------------------------------
  const passwordModal = document.getElementById('password-modal');
  const modalPasswordInput = document.getElementById('modal-password-input');
  const modalErrorMessage = document.getElementById('modal-error-message');
  const btnModalCancel = document.getElementById('btn-modal-cancel');
  const btnModalDecrypt = document.getElementById('btn-modal-decrypt');
  
  let pendingCallToDecrypt = null;

  function handleCallSelect(call) {
    if (call.encrypted) {
      // Check if we can load it directly (checks in-memory key cache inside backend)
      window.api.loadCall(call.filePath).then(res => {
        if (res.success) {
          displaySession(res.session);
        } else if (res.requirePassword) {
          // Open password modal
          pendingCallToDecrypt = call;
          modalPasswordInput.value = '';
          modalErrorMessage.classList.add('hidden');
          passwordModal.classList.remove('hidden');
        }
      });
    } else {
      window.api.loadCall(call.filePath).then(res => {
        if (res.success) {
          displaySession(res.session);
        }
      });
    }
  }

  btnModalCancel.addEventListener('click', () => {
    passwordModal.classList.add('hidden');
    pendingCallToDecrypt = null;
  });

  btnModalDecrypt.addEventListener('click', () => {
    const password = modalPasswordInput.value;
    if (!password) return;
    
    window.api.decryptCall(pendingCallToDecrypt.filePath, password).then((res) => {
      if (res.success) {
        passwordModal.classList.add('hidden');
        displaySession(res.session);
        loadHistoryList(); // Refresh title and summary
      } else {
        modalErrorMessage.classList.remove('hidden');
      }
    });
  });

  function displaySession(session) {
    activeSession = session;
    isUserScrolledUp = false;
    if (btnJumpLatest) btnJumpLatest.classList.add('hidden');
    
    // Highlight in the sidebar
    document.querySelectorAll('.call-list-item').forEach(item => {
      if (item.getAttribute('data-callid') === session.id) {
        item.classList.add('selected');
      } else {
        item.classList.remove('selected');
      }
    });
    
    // Set Header
    recTitle.textContent = session.title;
    recTimer.textContent = session.date;
    recIndicator.className = 'rec-indicator-static';

    // Show Resume Call button since we loaded a past session
    if (session.id !== 'live') {
      btnResumePast.classList.remove('hidden');
    } else {
      btnResumePast.classList.add('hidden');
    }
    
    // Load transcript
    transcriptContainer.innerHTML = '';
    if (session.transcript && session.transcript.length > 0) {
      lastSegmentTimeMs = renderTranscriptWithBreaks(session.transcript);
    } else {
      transcriptContainer.innerHTML = `
        <div class="transcript-empty-state">
          <span class="empty-icon">📝</span>
          <p>This session has no recorded transcription text.</p>
        </div>
      `;
      lastSegmentTimeMs = null;
    }
    
    // Load summaries
    renderSummaries();
  }

  function renderSummaries() {
    if (!activeSession) return;

    // Render Highlights
    if (activeSession.summary) {
      summaryContent.innerHTML = formatAISummary(activeSession.summary);
    } else {
      summaryContent.innerHTML = '<p class="placeholder-text">No summary generated.</p>';
    }

    // Render Action items
    if (activeSession.actionItems) {
      actionContent.innerHTML = formatAISummary(activeSession.actionItems);
    } else {
      actionContent.innerHTML = '<p class="placeholder-text">No action items extracted.</p>';
    }

    if (jotEditor) {
      const editorDocument = normalizeEditorDocument(activeSession);
      jotEditor.loadDocument(editorDocument);
      if (editorLegend) {
        editorLegend.classList.toggle('hidden', editorDocument.mode !== 'mixed');
      }
    }
  }

  // Format AI bullet points to include Jump-to-Context anchors and parse custom Markdown
  function formatAISummary(text) {
    if (!text) return '';
    
    // Split the text into lines
    const lines = text.split('\n');
    let insideList = false;
    let htmlOutput = [];
    
    lines.forEach((line) => {
      let trimmed = line.trim();
      if (!trimmed) {
        if (insideList) {
          htmlOutput.push('</ul>');
          insideList = false;
        }
        return;
      }
      
      // Parse Headers (##, ###)
      if (trimmed.startsWith('#')) {
        if (insideList) {
          htmlOutput.push('</ul>');
          insideList = false;
        }
        const level = (trimmed.match(/^#+/) || ['#'])[0].length;
        const headerText = trimmed.replace(/^#+\s*/, '');
        const cleanText = parseInlineMarkdown(headerText);
        htmlOutput.push(`<h${Math.min(level + 1, 6)} style="margin-top: 12px; margin-bottom: 6px; font-weight: 600; color: var(--text-main);">${cleanText}</h${Math.min(level + 1, 6)}>`);
        return;
      }
      
      // Parse List Items
      if (trimmed.startsWith('-') || trimmed.startsWith('*')) {
        const itemContent = trimmed.substring(1).trim();
        
        if (!insideList) {
          htmlOutput.push('<ul style="margin-bottom: 10px; padding-left: 20px; list-style-type: disc;">');
          insideList = true;
        }
        
        // Find segment keywords to create a link to context
        let matchingSegId = null;
        if (activeSession && activeSession.transcript) {
          const words = itemContent.toLowerCase().split(/\s+/).filter(w => w.replace(/[^\w]/g, '').length > 4);
          for (let seg of activeSession.transcript) {
            const segTextLower = seg.text.toLowerCase();
            const matchesCount = words.filter(word => segTextLower.includes(word)).length;
            if (matchesCount >= 2) {
              matchingSegId = seg.id;
              break;
            }
          }
        }
        
        const cleanContent = parseInlineMarkdown(itemContent);
        if (matchingSegId) {
          htmlOutput.push(`<li style="margin-bottom: 4px;">${cleanContent} <span class="context-link" onclick="scrollToTranscriptSegment('${matchingSegId}')" style="cursor: pointer; color: var(--primary); font-size: 10px; margin-left: 6px; font-weight: 500; text-decoration: underline;">[Context]</span></li>`);
        } else {
          htmlOutput.push(`<li style="margin-bottom: 4px;">${cleanContent}</li>`);
        }
        return;
      }
      
      // Plain text paragraph
      if (insideList) {
        htmlOutput.push('</ul>');
        insideList = false;
      }
      const cleanPara = parseInlineMarkdown(trimmed);
      htmlOutput.push(`<p style="margin-bottom: 8px; line-height: 1.5;">${cleanPara}</p>`);
    });
    
    if (insideList) {
      htmlOutput.push('</ul>');
    }
    
    return htmlOutput.join('');
  }
  
  function parseInlineMarkdown(text) {
    // Escape HTML first
    let escaped = text
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;');
      
    // Parse Bold: **text**
    escaped = escaped.replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>');
    
    // Parse Italic: *text* or _text_
    escaped = escaped.replace(/\*(.*?)\*/g, '<em>$1</em>');
    escaped = escaped.replace(/_(.*?)_/g, '<em>$1</em>');
    
    return escaped;
  }

  // Make scrolling context global so onclick can find it
  window.scrollToTranscriptSegment = function(segmentId) {
    const line = document.getElementById(`line-${segmentId}`);
    if (line) {
      line.scrollIntoView({ behavior: 'smooth', block: 'center' });
      
      // Trigger a flash effect
      line.classList.add('highlight-flash');
      setTimeout(() => {
        line.classList.remove('highlight-flash');
      }, 2000);
    }
  };

  // ----------------------------------------------------
  // Settings Tab Integrations
  // ----------------------------------------------------
  const hwCpu = document.getElementById('hw-cpu');
  const hwRam = document.getElementById('hw-ram');
  const hwGpu = document.getElementById('hw-gpu');
  const hwRecommendation = document.getElementById('hw-recommendation');
  
  const selectWhisperModel = document.getElementById('select-whisper-model');
  const selectLlmModel = document.getElementById('select-llm-model');
  
  const selectMicDevice = document.getElementById('select-mic-device');
  const selectSysDevice = document.getElementById('select-sys-device');
  
  const checkEncryptDefault = document.getElementById('check-encrypt-default');
  const inputEncryptPassword = document.getElementById('input-encrypt-password');
  
  const btnDownloadWhisper = document.getElementById('btn-download-whisper');
  const btnSaveSettings = document.getElementById('btn-save-settings');
  
  const downloadProgressContainer = document.getElementById('download-progress-container');
  const downloadProgressPercent = document.getElementById('download-progress-percent');
  const downloadProgressFill = document.getElementById('download-progress-fill');
  const downloadProgressLog = document.getElementById('download-progress-log');

  const selectNoteStyle = document.getElementById('select-note-style');
  const textareaNotePrompt = document.getElementById('textarea-note-prompt');
  const textareaSummaryPrompt = document.getElementById('textarea-summary-prompt');
  const textareaActionPrompt = document.getElementById('textarea-action-prompt');
  const btnRestoreDefaults = document.getElementById('btn-restore-defaults');
  
  let defaultPrompts = null;
  window.api.getDefaultPrompts().then((defaults) => {
    defaultPrompts = defaults;
  });

  // Load hardware specs
  window.api.getSpecs().then((specs) => {
    hwCpu.textContent = specs.cpuInfo;
    hwRam.textContent = `${specs.totalRamGb} GB`;
    hwGpu.textContent = specs.gpuInfo;
    hwRecommendation.textContent = specs.recommendedModel;
  });

  // Load saved settings & audio devices list
  let activeSettings = null;
  window.api.getSettings().then((saved) => {
    activeSettings = saved;
    checkEncryptDefault.checked = saved.encryptByDefault;
    inputEncryptPassword.value = saved.encryptionPassword || '';
    document.getElementById('check-color-deadlines').checked = saved.colorCodeDeadlines || false;
    document.getElementById('input-user-name').value = saved.userName || '';
    document.getElementById('check-noise-cancel').checked = saved.enableNoiseCancellation !== false;
    document.getElementById('input-storage-path').value = saved.customStoragePath || '';
    
    if (selectNoteStyle) selectNoteStyle.value = saved.selectedNoteStyle || 'executive';
    if (textareaNotePrompt) textareaNotePrompt.value = saved.notePromptTemplate || '';
    if (textareaSummaryPrompt) textareaSummaryPrompt.value = saved.summaryPromptTemplate || '';
    if (textareaActionPrompt) textareaActionPrompt.value = saved.actionPromptTemplate || '';
    
    const btnBrowseStorage = document.getElementById('btn-browse-storage');
    if (btnBrowseStorage) {
      btnBrowseStorage.addEventListener('click', () => {
        window.api.selectDirectory().then((dirPath) => {
          if (dirPath) {
            document.getElementById('input-storage-path').value = dirPath;
          }
        });
      });
    }

    const inputSidebarSearch = document.getElementById('input-sidebar-search');
    if (inputSidebarSearch) {
      inputSidebarSearch.addEventListener('input', () => {
        loadHistoryList();
      });
    }
    
    // Select correct Whisper model
    for (let opt of selectWhisperModel.options) {
      if (opt.value === saved.selectedModel) {
        opt.selected = true;
      }
    }

    // Load devices and populate lists
    window.api.getAudioDevices().then((devices) => {
      // Set label in main dashboard
      const activeSource = saved.selectedMic === 'default' ? devices.source : saved.selectedMic;
      const activeSink = saved.selectedSink === 'default' ? devices.sink : saved.selectedSink;
      document.getElementById('label-mic-device').textContent = activeSource ? activeSource.split('.').pop() : 'None';
      document.getElementById('label-sys-device').textContent = activeSink ? activeSink.split('.').pop() : 'None';

      // Populate Mic dropdown
      selectMicDevice.innerHTML = '<option value="default">Default Active Microphone</option>';
      devices.microphones.forEach((mic) => {
        const opt = document.createElement('option');
        opt.value = mic.id;
        opt.textContent = mic.name;
        if (mic.id === saved.selectedMic) opt.selected = true;
        selectMicDevice.appendChild(opt);
      });

      // Populate System monitor dropdown
      selectSysDevice.innerHTML = '<option value="default">Default Active Output Monitor</option>';
      devices.outputs.forEach((out) => {
        const opt = document.createElement('option');
        opt.value = out.id;
        opt.textContent = out.name;
        if (out.id === saved.selectedSink) opt.selected = true;
        selectSysDevice.appendChild(opt);
      });
    });
  });

  // Load Ollama models list
  window.api.getOllamaModels().then((models) => {
    selectLlmModel.innerHTML = '';
    
    if (models.length === 0) {
      selectLlmModel.innerHTML = '<option value="">(Ollama not running or empty)</option>';
      return;
    }
    
    models.forEach((m) => {
      const opt = document.createElement('option');
      opt.value = m.name;
      opt.textContent = `${m.name} (${(m.size / (1024 * 1024 * 1024)).toFixed(2)} GB)`;
      
      if (activeSettings && m.name === activeSettings.selectedLlm) {
        opt.selected = true;
      } else if (!activeSettings && (m.name.includes('gemma3') || m.name.includes('gemma'))) {
        opt.selected = true;
      }
      
      selectLlmModel.appendChild(opt);
    });
  });

  // Download Whisper model
  btnDownloadWhisper.addEventListener('click', () => {
    const modelSel = selectWhisperModel.value;
    const modelShort = modelSel.replace('ggml-', '').replace('.bin', '');
    
    downloadProgressContainer.classList.remove('hidden');
    downloadProgressPercent.textContent = '0%';
    downloadProgressFill.style.width = '0%';
    downloadProgressLog.textContent = `Starting download for model '${modelShort}'...\n`;
    
    window.api.downloadWhisper(modelShort)
      .then(() => {
        downloadProgressPercent.textContent = '100%';
        downloadProgressFill.style.width = '100%';
        downloadProgressLog.textContent += '\nDownload complete! Model loaded successfully.';
        alert('Whisper model downloaded successfully!');
      })
      .catch((err) => {
        downloadProgressLog.textContent += `\nError during model download: ${err}`;
      });
  });

  // Watch for download progress reports from backend
  window.api.onDownloadProgress((data) => {
    downloadProgressLog.textContent += data;
    downloadProgressLog.scrollTop = downloadProgressLog.scrollHeight;
    
    const match = data.match(/(\d+)%/);
    if (match) {
      const pct = match[1];
      downloadProgressPercent.textContent = `${pct}%`;
      downloadProgressFill.style.width = `${pct}%`;
    }
  });

  // Style dropdown change handler
  if (selectNoteStyle) {
    selectNoteStyle.addEventListener('change', () => {
      const style = selectNoteStyle.value;
      if (style !== 'custom' && defaultPrompts && defaultPrompts[style]) {
        textareaNotePrompt.value = defaultPrompts[style];
      }
    });
  }

  // Textarea input handler (switch to custom when edited manually)
  if (textareaNotePrompt && selectNoteStyle) {
    textareaNotePrompt.addEventListener('input', () => {
      selectNoteStyle.value = 'custom';
    });
  }

  // Restore Defaults handler
  if (btnRestoreDefaults) {
    btnRestoreDefaults.addEventListener('click', () => {
      if (defaultPrompts) {
        if (selectNoteStyle) selectNoteStyle.value = 'executive';
        if (textareaNotePrompt) textareaNotePrompt.value = defaultPrompts.executive;
        if (textareaSummaryPrompt) textareaSummaryPrompt.value = defaultPrompts.summary;
        if (textareaActionPrompt) textareaActionPrompt.value = defaultPrompts.actionItems;
        
        // Auto-save restored settings
        btnSaveSettings.click();
      }
    });
  }

  // Save Settings handler
  btnSaveSettings.addEventListener('click', () => {
    const updated = {
      selectedModel: selectWhisperModel.value,
      selectedLlm: selectLlmModel.value,
      selectedMic: selectMicDevice.value,
      selectedSink: selectSysDevice.value,
      encryptByDefault: checkEncryptDefault.checked,
      encryptionPassword: inputEncryptPassword.value,
      colorCodeDeadlines: document.getElementById('check-color-deadlines').checked,
      userName: document.getElementById('input-user-name').value.trim(),
      enableNoiseCancellation: document.getElementById('check-noise-cancel').checked,
      customStoragePath: document.getElementById('input-storage-path').value,
      selectedNoteStyle: selectNoteStyle ? selectNoteStyle.value : 'executive',
      notePromptTemplate: textareaNotePrompt ? textareaNotePrompt.value : '',
      summaryPromptTemplate: textareaSummaryPrompt ? textareaSummaryPrompt.value : '',
      actionPromptTemplate: textareaActionPrompt ? textareaActionPrompt.value : ''
    };
    
    window.api.saveSettings(updated).then(() => {
      alert('Settings saved successfully!');
      // Update main dashboard badges
      window.api.getAudioDevices().then((devices) => {
        let activeSourceDesc = devices.sourceDesc;
        if (updated.selectedMic !== 'default') {
          const mic = devices.microphones.find(m => m.id === updated.selectedMic);
          if (mic) activeSourceDesc = mic.name;
        }

        let activeSinkDesc = devices.sinkDesc;
        if (updated.selectedSink !== 'default') {
          const sink = devices.outputs.find(o => o.id === updated.selectedSink);
          if (sink) activeSinkDesc = sink.name;
        }
        
        document.getElementById('label-mic-device').textContent = activeSourceDesc || 'None';
        document.getElementById('label-sys-device').textContent = activeSinkDesc || 'None';
      });
    });
  });

  // ----------------------------------------------------
  // AI Chat Agent Widget
  // ----------------------------------------------------
  const chatAgentWidget = document.getElementById('chat-agent-widget');
  const btnChatToggle = document.getElementById('btn-chat-toggle');
  const btnChatSidebarToggle = document.getElementById('btn-chat-sidebar-toggle');
  const chatHeader = document.getElementById('chat-header');
  const chatMessages = document.getElementById('chat-messages');
  const inputChatQuery = document.getElementById('input-chat-query');
  const btnChatSend = document.getElementById('btn-chat-send');
  
  const btnChatMiss = document.getElementById('btn-chat-miss');
  const btnChatAction = document.getElementById('btn-chat-action');

  function toggleChatSidebar(isOpen) {
    if (isOpen) {
      chatAgentWidget.classList.remove('closed');
      btnChatSidebarToggle.classList.add('hidden');
    } else {
      chatAgentWidget.classList.add('closed');
      btnChatSidebarToggle.classList.remove('hidden');
    }
  }

  if (btnChatToggle) {
    btnChatToggle.addEventListener('click', () => {
      toggleChatSidebar(false);
    });
  }

  btnChatSidebarToggle.addEventListener('click', () => {
    toggleChatSidebar(true);
  });

  btnChatSend.addEventListener('click', submitChatQuery);
  inputChatQuery.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') submitChatQuery();
  });

  btnChatMiss.addEventListener('click', () => {
    toggleChatSidebar(true);
    runPresetQuery('miss', '⏱️ What did I miss in the last few minutes?');
  });

  btnChatAction.addEventListener('click', () => {
    toggleChatSidebar(true);
    runPresetQuery('action', '📋 What are the action items?');
  });

  // Initialize closed state
  toggleChatSidebar(false);

  function submitChatQuery() {
    const text = inputChatQuery.value.trim();
    if (!text) return;
    
    appendChatMessage('user', text);
    inputChatQuery.value = '';
    
    const loadingId = appendChatMessage('assistant', 'Generating local response...');
    
    const transcriptText = activeSession && activeSession.transcript 
      ? activeSession.transcript.map(t => `[${t.timestamp}] ${t.speaker}: ${t.text}`).join('\n') 
      : 'No transcript active.';
      
    window.api.chatQuery(text, transcriptText).then((result) => {
      if (!result || !result.requestId) {
        updateChatMessage(loadingId, result?.error || 'Could not query local AI model.');
        return;
      }

      waitForLlmStream(result.requestId, (partialText) => {
        updateChatMessage(loadingId, partialText || '');
      }).catch((err) => {
        updateChatMessage(loadingId, `Error: ${err.message}`);
      });
    });
  }

  function runPresetQuery(presetType, label) {
    appendChatMessage('user', label);
    const loadingId = appendChatMessage('assistant', 'Analyzing session...');
    
    const transcriptText = activeSession && activeSession.transcript 
      ? activeSession.transcript.map(t => `[${t.timestamp}] ${t.speaker}: ${t.text}`).join('\n') 
      : 'No transcript active.';
      
    window.api.chatQuery(presetType, transcriptText).then((result) => {
      if (!result || !result.requestId) {
        updateChatMessage(loadingId, result?.error || 'Could not query local AI model.');
        return;
      }

      waitForLlmStream(result.requestId, (partialText) => {
        updateChatMessage(loadingId, partialText || '');
      }).catch((err) => {
        updateChatMessage(loadingId, `Error: ${err.message}`);
      });
    });
  }

  let messageCounter = 0;
  function appendChatMessage(sender, text) {
    messageCounter++;
    const id = `msg-${messageCounter}`;
    
    const msgDiv = document.createElement('div');
    msgDiv.className = `msg ${sender}`;
    msgDiv.id = id;
    
    if (sender === 'assistant' || sender === 'system') {
      msgDiv.innerHTML = formatAISummary(text);
    } else {
      msgDiv.textContent = text;
    }
    
    chatMessages.appendChild(msgDiv);
    chatBodyScrollBottom();
    return id;
  }

  function updateChatMessage(id, text) {
    const msgDiv = document.getElementById(id);
    if (msgDiv) {
      if (msgDiv.classList.contains('assistant') || msgDiv.classList.contains('system')) {
        msgDiv.innerHTML = formatAISummary(text);
      } else {
        msgDiv.textContent = text;
      }
      chatBodyScrollBottom();
    }
  }

  function chatBodyScrollBottom() {
    const chatBody = document.querySelector('.chat-body');
    chatBody.scrollTop = chatBody.scrollHeight;
  }

  // ----------------------------------------------------
  // Deadline Calendar & Action Items Tab Logic
  // ----------------------------------------------------
  let calendarCurrentDate = new Date();
  let calendarActiveView = 'grid'; // 'grid', 'list', 'omitted'
  let calendarSortOrder = 'asc'; // 'asc', 'desc'
  let contextMenuTargetDeadline = null;
  const deadlinesContextMenu = document.getElementById('deadlines-context-menu');
  
  const btnCalendarGridView = document.getElementById('btn-calendar-grid-view');
  const btnCalendarListView = document.getElementById('btn-calendar-list-view');
  const btnCalendarOmittedView = document.getElementById('btn-calendar-omitted-view');
  
  const calendarGridContainer = document.getElementById('calendar-grid-container');
  const calendarListContainer = document.getElementById('calendar-list-container');
  const calendarOmittedContainer = document.getElementById('calendar-omitted-container');
  const calendarSortControls = document.getElementById('calendar-sort-controls');
  
  function switchCalendarView(viewName) {
    calendarActiveView = viewName;
    
    btnCalendarGridView.classList.remove('active');
    btnCalendarListView.classList.remove('active');
    btnCalendarOmittedView.classList.remove('active');
    
    calendarGridContainer.classList.add('hidden');
    calendarListContainer.classList.add('hidden');
    calendarOmittedContainer.classList.add('hidden');
    calendarSortControls.classList.add('hidden');
    
    if (viewName === 'grid') {
      btnCalendarGridView.classList.add('active');
      calendarGridContainer.classList.remove('hidden');
    } else if (viewName === 'list') {
      btnCalendarListView.classList.add('active');
      calendarListContainer.classList.remove('hidden');
      calendarSortControls.classList.remove('hidden');
    } else if (viewName === 'omitted') {
      btnCalendarOmittedView.classList.add('active');
      calendarOmittedContainer.classList.remove('hidden');
    }
    
    renderCalendar();
  }
  
  if (btnCalendarGridView) btnCalendarGridView.addEventListener('click', () => switchCalendarView('grid'));
  if (btnCalendarListView) btnCalendarListView.addEventListener('click', () => switchCalendarView('list'));
  if (btnCalendarOmittedView) btnCalendarOmittedView.addEventListener('click', () => switchCalendarView('omitted'));
  
  const selectDeadlineSort = document.getElementById('select-deadline-sort');
  if (selectDeadlineSort) {
    selectDeadlineSort.addEventListener('change', () => {
      calendarSortOrder = selectDeadlineSort.value;
      renderCalendar();
    });
  }
  
  const btnPrevMonth = document.getElementById('btn-prev-month');
  const btnNextMonth = document.getElementById('btn-next-month');
  
  if (btnPrevMonth) {
    btnPrevMonth.addEventListener('click', () => {
      calendarCurrentDate.setMonth(calendarCurrentDate.getMonth() - 1);
      renderCalendar();
    });
  }
  if (btnNextMonth) {
    btnNextMonth.addEventListener('click', () => {
      calendarCurrentDate.setMonth(calendarCurrentDate.getMonth() + 1);
      renderCalendar();
    });
  }
  
  function getUrgencyClass(dueDateString) {
    const now = new Date();
    now.setHours(0, 0, 0, 0);
    const due = new Date(dueDateString);
    if (isNaN(due.getTime())) return 'deadline-upcoming';
    
    due.setHours(0, 0, 0, 0);
    const diffDays = Math.ceil((due.getTime() - now.getTime()) / (1000 * 60 * 60 * 24));
    
    if (diffDays < 0) {
      return 'deadline-overdue';
    } else if (diffDays <= 1) {
      return 'deadline-critical';
    } else if (diffDays <= 3) {
      return 'deadline-warning';
    } else {
      return 'deadline-upcoming';
    }
  }
  
  function getFormattedDateString(year, month, day) {
    const d = new Date(year, month, day);
    const yyyy = d.getFullYear();
    const mm = (d.getMonth() + 1).toString().padStart(2, '0');
    const dd = d.getDate().toString().padStart(2, '0');
    return `${yyyy}-${mm}-${dd}`;
  }
  
  function showDeadlinesContextMenu(e, dl) {
    contextMenuTargetDeadline = dl;
    
    if (deadlinesContextMenu) {
      deadlinesContextMenu.style.left = `${e.clientX}px`;
      deadlinesContextMenu.style.top = `${e.clientY}px`;
      deadlinesContextMenu.classList.remove('hidden');
      
      const ctxHide = document.getElementById('ctx-hide-deadline');
      const ctxShow = document.getElementById('ctx-show-deadline');
      
      if (dl.omitted) {
        if (ctxHide) ctxHide.classList.add('hidden');
        if (ctxShow) ctxShow.classList.remove('hidden');
      } else {
        if (ctxHide) ctxHide.classList.remove('hidden');
        if (ctxShow) ctxShow.classList.add('hidden');
      }
    }
  }
  
  const ctxHideDeadline = document.getElementById('ctx-hide-deadline');
  const ctxShowDeadline = document.getElementById('ctx-show-deadline');
  const ctxDeleteDeadline = document.getElementById('ctx-delete-deadline');
  
  if (ctxHideDeadline) {
    ctxHideDeadline.addEventListener('click', () => {
      if (contextMenuTargetDeadline) {
        window.api.setTaskOmitted(contextMenuTargetDeadline.id, true).then(res => {
          if (res.success) {
            renderCalendar();
            renderActionItems();
          }
        });
      }
      if (deadlinesContextMenu) deadlinesContextMenu.classList.add('hidden');
    });
  }
  
  if (ctxShowDeadline) {
    ctxShowDeadline.addEventListener('click', () => {
      if (contextMenuTargetDeadline) {
        window.api.setTaskOmitted(contextMenuTargetDeadline.id, false).then(res => {
          if (res.success) {
            renderCalendar();
            renderActionItems();
          }
        });
      }
      if (deadlinesContextMenu) deadlinesContextMenu.classList.add('hidden');
    });
  }
  
  if (ctxDeleteDeadline) {
    ctxDeleteDeadline.addEventListener('click', () => {
      if (contextMenuTargetDeadline) {
        if (confirm(`Are you sure you want to delete this action item: "${contextMenuTargetDeadline.text}"?`)) {
          window.api.deleteTask(contextMenuTargetDeadline.id).then(res => {
            if (res.success) {
              renderCalendar();
              renderActionItems();
            }
          });
        }
      }
      if (deadlinesContextMenu) deadlinesContextMenu.classList.add('hidden');
    });
  }
  
  document.addEventListener('click', (e) => {
    if (deadlinesContextMenu && !deadlinesContextMenu.contains(e.target)) {
      deadlinesContextMenu.classList.add('hidden');
    }
  });
  
  function renderCalendar() {
    window.api.getTasks().then((tasks) => {
      // Filter tasks to only date-specific items
      const deadlineTasks = tasks.filter(t => t.dueDate);
      
      const activeDeadlines = deadlineTasks.filter(t => !t.omitted);
      const omittedDeadlines = deadlineTasks.filter(t => t.omitted);
      
      if (calendarActiveView === 'grid') {
        renderCalendarGrid(activeDeadlines);
      } else if (calendarActiveView === 'list') {
        renderCalendarList(activeDeadlines);
      } else if (calendarActiveView === 'omitted') {
        renderCalendarOmitted(omittedDeadlines);
      }
    });
  }
  
  function renderCalendarGrid(deadlines) {
    const monthYearLabel = document.getElementById('calendar-month-year-label');
    const monthGrid = document.getElementById('calendar-month-grid');
    if (!monthGrid) return;
    
    const year = calendarCurrentDate.getFullYear();
    const month = calendarCurrentDate.getMonth();
    
    const monthNames = [
      'January', 'February', 'March', 'April', 'May', 'June',
      'July', 'August', 'September', 'October', 'November', 'December'
    ];
    if (monthYearLabel) {
      monthYearLabel.textContent = `${monthNames[month]} ${year}`;
    }
    
    monthGrid.innerHTML = '';
    
    // Day headers
    const weekdays = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
    weekdays.forEach(day => {
      const header = document.createElement('div');
      header.className = 'calendar-day-header';
      header.textContent = day;
      monthGrid.appendChild(header);
    });
    
    const firstDayIndex = new Date(year, month, 1).getDay();
    const totalDays = new Date(year, month + 1, 0).getDate();
    const prevMonthTotalDays = new Date(year, month, 0).getDate();
    
    const cells = [];
    
    // Prev month days
    for (let i = firstDayIndex - 1; i >= 0; i--) {
      cells.push({
        day: prevMonthTotalDays - i,
        isCurrentMonth: false,
        dateString: getFormattedDateString(year, month - 1, prevMonthTotalDays - i)
      });
    }
    
    // Current month days
    for (let i = 1; i <= totalDays; i++) {
      cells.push({
        day: i,
        isCurrentMonth: true,
        dateString: getFormattedDateString(year, month, i)
      });
    }
    
    // Next month days to pad to 42 cells
    const paddingCells = 42 - cells.length;
    for (let i = 1; i <= paddingCells; i++) {
      cells.push({
        day: i,
        isCurrentMonth: false,
        dateString: getFormattedDateString(year, month + 1, i)
      });
    }
    
    const todayStr = new Date().toISOString().split('T')[0];
    
    cells.forEach(cell => {
      const cellDiv = document.createElement('div');
      cellDiv.className = 'calendar-day-cell';
      if (!cell.isCurrentMonth) {
        cellDiv.classList.add('other-month');
      }
      if (cell.dateString === todayStr) {
        cellDiv.classList.add('today');
      }
      
      cellDiv.innerHTML = `<span class="day-number">${cell.day}</span>`;
      
      const dayDeadlines = deadlines.filter(d => d.dueDate === cell.dateString);
      dayDeadlines.forEach(dl => {
        const badge = document.createElement('div');
        badge.className = `calendar-deadline-badge ${dl.completed ? 'completed' : ''}`;
        badge.textContent = dl.text;
        
        const colorCoding = document.getElementById('check-color-deadlines').checked;
        if (colorCoding && !dl.completed) {
          const urgency = getUrgencyClass(dl.dueDate);
          badge.classList.add(urgency);
        }
        
        badge.addEventListener('click', (e) => {
          e.stopPropagation();
          navigateToCallContext(dl.sourceCallId, dl.sourceSegmentId);
        });
        
        badge.addEventListener('contextmenu', (e) => {
          e.preventDefault();
          e.stopPropagation();
          showDeadlinesContextMenu(e, dl);
        });
        
        cellDiv.appendChild(badge);
      });
      
      monthGrid.appendChild(cellDiv);
    });
  }
  
  function renderCalendarList(deadlines) {
    const listWrapper = document.getElementById('calendar-deadlines-list');
    if (!listWrapper) return;
    listWrapper.innerHTML = '';
    
    if (deadlines.length === 0) {
      listWrapper.innerHTML = '<div class="empty-state">No upcoming deadlines found.</div>';
      return;
    }
    
    const sorted = [...deadlines].sort((a, b) => {
      const dateA = new Date(a.dueDate);
      const dateB = new Date(b.dueDate);
      return calendarSortOrder === 'asc' ? dateA - dateB : dateB - dateA;
    });
    
    const colorCoding = document.getElementById('check-color-deadlines').checked;
    
    sorted.forEach(dl => {
      const itemDiv = document.createElement('div');
      itemDiv.className = `timeline-task-item checklist-item ${dl.completed ? 'completed' : ''}`;
      
      let urgencyClass = '';
      if (colorCoding && !dl.completed) {
        urgencyClass = getUrgencyClass(dl.dueDate);
      }
      
      itemDiv.innerHTML = `
        <label class="checkbox-container" style="margin-bottom: 0;">
          <input type="checkbox" ${dl.completed ? 'checked' : ''} class="deadline-checkbox">
          <span class="checkmark"></span>
          <div class="task-details">
            <span class="task-source">${dl.sourceCallTitle}</span>
            <p class="task-text">${dl.text}</p>
          </div>
        </label>
        <span class="task-date ${urgencyClass}" style="cursor: pointer;">${dl.dueDate}</span>
      `;
      
      const cb = itemDiv.querySelector('.deadline-checkbox');
      cb.addEventListener('change', () => {
        window.api.toggleTask(dl.id).then(res => {
          if (res.success) {
            renderCalendar();
            renderActionItems();
          }
        });
      });
      
      itemDiv.querySelector('.task-details').addEventListener('click', (e) => {
        if (e.target.tagName !== 'INPUT' && !e.target.classList.contains('checkmark')) {
          navigateToCallContext(dl.sourceCallId, dl.sourceSegmentId);
        }
      });
      
      itemDiv.querySelector('.task-date').addEventListener('click', () => {
        navigateToCallContext(dl.sourceCallId, dl.sourceSegmentId);
      });
      
      itemDiv.addEventListener('contextmenu', (e) => {
        e.preventDefault();
        showDeadlinesContextMenu(e, dl);
      });
      
      listWrapper.appendChild(itemDiv);
    });
  }
  
  function renderCalendarOmitted(omittedDeadlines) {
    const listWrapper = document.getElementById('calendar-omitted-list');
    if (!listWrapper) return;
    listWrapper.innerHTML = '';
    
    if (omittedDeadlines.length === 0) {
      listWrapper.innerHTML = '<div class="empty-state">No omitted deadlines.</div>';
      return;
    }
    
    omittedDeadlines.forEach(dl => {
      const itemDiv = document.createElement('div');
      itemDiv.className = `timeline-task-item checklist-item ${dl.completed ? 'completed' : ''}`;
      
      itemDiv.innerHTML = `
        <label class="checkbox-container" style="margin-bottom: 0;">
          <input type="checkbox" ${dl.completed ? 'checked' : ''} class="omitted-checkbox">
          <span class="checkmark"></span>
          <div class="task-details">
            <span class="task-source">${dl.sourceCallTitle}</span>
            <p class="task-text">${dl.text} <span style="font-style: italic; opacity: 0.6;">(Omitted)</span></p>
          </div>
        </label>
        <span class="task-date" style="cursor: pointer;">${dl.dueDate}</span>
      `;
      
      const cb = itemDiv.querySelector('.omitted-checkbox');
      cb.addEventListener('change', () => {
        window.api.toggleTask(dl.id).then(res => {
          if (res.success) {
            renderCalendar();
            renderActionItems();
          }
        });
      });
      
      itemDiv.querySelector('.task-details').addEventListener('click', (e) => {
        if (e.target.tagName !== 'INPUT' && !e.target.classList.contains('checkmark')) {
          navigateToCallContext(dl.sourceCallId, dl.sourceSegmentId);
        }
      });
      
      itemDiv.querySelector('.task-date').addEventListener('click', () => {
        navigateToCallContext(dl.sourceCallId, dl.sourceSegmentId);
      });
      
      itemDiv.addEventListener('contextmenu', (e) => {
        e.preventDefault();
        showDeadlinesContextMenu(e, dl);
      });
      
      listWrapper.appendChild(itemDiv);
    });
  }
  
  function renderActionItems() {
    window.api.getTasks().then((tasks) => {
      const listWrapper = document.getElementById('action-items-list');
      if (!listWrapper) return;
      listWrapper.innerHTML = '';
      
      if (tasks.length === 0) {
        listWrapper.innerHTML = '<div class="empty-state">No action items found.</div>';
        return;
      }
      
      const sortedTasks = [...tasks].sort((a, b) => {
        if (a.completed !== b.completed) {
          return a.completed ? 1 : -1;
        }
        const assigneeA = (a.assignee || 'Unassigned').toLowerCase();
        const assigneeB = (b.assignee || 'Unassigned').toLowerCase();
        const comp = assigneeA.localeCompare(assigneeB);
        if (comp !== 0) return comp;
        return new Date(b.id.split('_').pop()) - new Date(a.id.split('_').pop());
      });
      
      sortedTasks.forEach(task => {
        const block = document.createElement('div');
        block.className = `action-item-block ${task.completed ? 'completed' : ''}`;
        
        const dueDateHtml = task.dueDate ? `<span class="task-date" style="margin-left: 10px; font-size: 11px;">📅 Due: ${task.dueDate}</span>` : '';
        const assigneeHtml = `<span class="assignee-badge" style="background: rgba(164,198,57,0.12); border: 1px solid rgba(164,198,57,0.25); color: var(--primary); padding: 2px 6px; border-radius: 4px; font-size: 10px; font-weight: bold; margin-right: 8px;">${task.assignee || 'Unassigned'}</span>`;
        
        let contextRowHtml = '';
        if (task.sourceCallTitle) {
          contextRowHtml = `
            <div class="action-context-row" style="margin-top: 8px; font-size: 11px; display: flex; gap: 10px;">
              <span>Mentioned in: <a class="action-call-link" style="color: var(--primary); text-decoration: none; cursor: pointer;">${task.sourceCallTitle}</a></span>
              ${task.sourceTimestamp ? `<span class="action-timestamp-badge" style="background: rgba(255, 255, 255, 0.05); padding: 1px 6px; border-radius: 4px; font-family: monospace; cursor: pointer;">${task.sourceTimestamp}</span>` : ''}
            </div>
          `;
        }
        
        block.innerHTML = `
          <div class="action-item-header" style="padding: 12px; display: flex; justify-content: space-between; align-items: center; cursor: pointer;">
            <div class="action-header-left" style="display: flex; align-items: center; gap: 10px; flex: 1;">
              <label class="checkbox-container" style="margin-bottom: 0;">
                <input type="checkbox" ${task.completed ? 'checked' : ''} class="action-item-checkbox">
                <span class="checkmark"></span>
              </label>
              <h3 class="action-headline" style="font-size: 14px; color: var(--text-main); margin: 0; display: flex; align-items: center;">${assigneeHtml} ${task.text} ${dueDateHtml}</h3>
            </div>
            <span class="action-expand-icon">▼</span>
          </div>
          <div class="action-item-content" style="padding: 0 12px 12px 34px; border-top: 1px solid rgba(255, 255, 255, 0.05); font-size: 12.5px; color: var(--text-muted); display: none;">
            <p style="margin: 0 0 8px 0; opacity: 0.8;">Action item extracted from call context.</p>
            ${contextRowHtml}
          </div>
        `;
        
        const header = block.querySelector('.action-item-header');
        header.addEventListener('click', (e) => {
          if (e.target.tagName !== 'INPUT' && !e.target.classList.contains('checkmark')) {
            block.classList.toggle('expanded');
          }
        });
        
        const cb = block.querySelector('.action-item-checkbox');
        cb.addEventListener('change', () => {
          window.api.toggleTask(task.id).then(res => {
            if (res.success) {
              renderActionItems();
              renderCalendar();
            }
          });
        });
        
        const callLink = block.querySelector('.action-call-link');
        if (callLink) {
          callLink.addEventListener('click', (e) => {
            e.preventDefault();
            e.stopPropagation();
            navigateToCallContext(task.sourceCallId, task.sourceSegmentId);
          });
        }
        
        const tsBadge = block.querySelector('.action-timestamp-badge');
        if (tsBadge) {
          tsBadge.addEventListener('click', (e) => {
            e.stopPropagation();
            navigateToCallContext(task.sourceCallId, task.sourceSegmentId);
          });
        }
        
        listWrapper.appendChild(block);
      });
    });
  }
  
  function navigateToCallContext(sourceCallId, sourceSegmentId) {
    window.api.getCallList().then((calls) => {
      const call = calls.find(c => c.id === sourceCallId);
      if (call) {
        tabs.forEach(t => {
          t.nav.classList.remove('active');
          t.pane.classList.remove('active');
        });
        navDashboard.classList.add('active');
        tabDashboard.classList.add('active');
        
        handleCallSelect(call).then(() => {
          if (sourceSegmentId) {
            setTimeout(() => {
              window.scrollToTranscriptSegment(sourceSegmentId);
            }, 250);
          }
        });
      } else {
        alert("Source call not found (it might have been deleted).");
      }
    });
  }
  
  window.scrollToTranscriptSegment = function(segmentId) {
    const line = document.getElementById(`line-${segmentId}`) || document.querySelector(`.subline-${segmentId}`);
    if (line) {
      line.scrollIntoView({ behavior: 'smooth', block: 'center' });
      line.classList.add('highlight-flash');
      setTimeout(() => {
        line.classList.remove('highlight-flash');
      }, 2000);
    }
  };
  
  window.toggleTaskStatus = function(taskId) {
    window.api.toggleTask(taskId).then(res => {
      if (res.success) {
        renderCalendar();
        renderActionItems();
      }
    });
  };
}
