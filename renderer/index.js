// Check Mode (Mini Widget vs Main Application)
const urlParams = new URLSearchParams(window.location.search);
const isMiniMode = urlParams.get('mode') === 'mini';

// Active Session Cache
let activeSession = null;
let activeFolderId = 'all';
let recordingInterval = null;
let recordingSeconds = 0;

// Calendar state (declared early — renderCalendar() is called during init)
let calendarCurrentDate = new Date();
let calendarActiveView = 'grid';
let calendarSortOrder = 'asc';
let contextMenuTargetDeadline = null;

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

  // Surface the latest transcript line in the mini widget.
  window.api.onTranscriptionUpdate((segment) => {
    const miniStatus = document.getElementById('mini-status');
    if (!miniStatus || !segment?.text) return;
    const speaker = segment.speaker ? `${segment.speaker}: ` : '';
    const text = String(segment.text).trim();
    miniStatus.textContent = `${speaker}${text}`.slice(0, 96);
  });
} else {
  // Setup Main mode
  mainApp.classList.remove('hidden');
  miniWidget.classList.add('hidden');
  globalTooltip = document.getElementById('global-tooltip');

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

  function waitForLlmStream(requestId, onUpdate) {
    return new Promise((resolve, reject) => {
      llmStreamBuffers.set(requestId, '');
      llmStreamWaiters.set(requestId, { onUpdate, resolve, reject });
    });
  }

  function initThemeToggle() {
    const btnDark = document.getElementById('btn-theme-dark');
    const btnLight = document.getElementById('btn-theme-light');
    if (!btnDark || !btnLight) return;

    function applyTheme(theme) {
      document.documentElement.setAttribute('data-theme', theme);
      localStorage.setItem('trailmix-theme', theme);
      btnDark.classList.toggle('active', theme === 'dark');
      btnLight.classList.toggle('active', theme === 'light');
    }

    const savedTheme = localStorage.getItem('trailmix-theme') || 'dark';
    applyTheme(savedTheme);

    btnDark.addEventListener('click', () => applyTheme('dark'));
    btnLight.addEventListener('click', () => applyTheme('light'));
  }

  function initTrailSidebar() {
    const sidebar = document.getElementById('sidebar');
    const toggle = document.getElementById('btn-trail-toggle');
    const appLayout = document.getElementById('main-app');
    if (!sidebar || !toggle) return;

    const savedCollapsed = localStorage.getItem('trailmix-trail-collapsed') === 'true';
    if (savedCollapsed) sidebar.classList.add('collapsed');

    function toggleSidebar() {
      sidebar.classList.toggle('collapsed');
      localStorage.setItem('trailmix-trail-collapsed', sidebar.classList.contains('collapsed'));
      updateSidebarOverlay();
    }

    function updateSidebarOverlay() {
      const isNarrow = window.innerWidth < 960;
      const editorActive = document.querySelector('.analysis-pane.mix-focused');
      const shouldOverlay = isNarrow && editorActive && !sidebar.classList.contains('collapsed');

      sidebar.classList.toggle('sidebar-overlay', shouldOverlay);
      if (appLayout) {
        appLayout.classList.toggle('sidebar-overlay-active', shouldOverlay);
      }
    }

    toggle.addEventListener('click', toggleSidebar);
    window.addEventListener('resize', updateSidebarOverlay);

    document.addEventListener('click', (event) => {
      if (!sidebar.classList.contains('sidebar-overlay')) return;
      if (sidebar.contains(event.target)) return;
      sidebar.classList.add('collapsed');
      localStorage.setItem('trailmix-trail-collapsed', 'true');
      updateSidebarOverlay();
    });

    window.toggleTrailSidebar = toggleSidebar;
    window.updateSidebarOverlay = updateSidebarOverlay;
    updateSidebarOverlay();
  }

  initThemeToggle();
  initTrailSidebar();
  
  // Navigation Tabs
  const navHome = document.getElementById('nav-home');
  const navCalendar = document.getElementById('nav-calendar');
  const navActionItems = document.getElementById('nav-action-items');
  const navHistory = document.getElementById('nav-history');
  const navSettings = document.getElementById('nav-settings');
  const tabHubHome = document.getElementById('tab-hub-home');
  const tabCalendar = document.getElementById('tab-calendar');
  const tabActionItems = document.getElementById('tab-action-items');
  const tabHistory = document.getElementById('tab-history');
  const tabSettings = document.getElementById('tab-settings');
  
  const tabs = [
    { nav: navHome, pane: tabHubHome },
    { nav: navCalendar, pane: tabCalendar },
    { nav: navActionItems, pane: tabActionItems },
    { nav: navHistory, pane: tabHistory },
    { nav: navSettings, pane: tabSettings }
  ].filter((tab) => tab.pane);

  function hideGlobalTooltip() {
    if (tooltipTimeout) {
      clearTimeout(tooltipTimeout);
      tooltipTimeout = null;
    }
    if (globalTooltip) {
      globalTooltip.classList.remove('visible');
      globalTooltip.classList.add('hidden');
    }
  }

  function activateTab(tab) {
    if (!tab?.pane) return;
    hideGlobalTooltip();
    tabs.forEach((t) => {
      t.nav?.classList.remove('active');
      t.pane?.classList.remove('active');
    });
    tab.nav?.classList.add('active');
    tab.pane.classList.add('active');

    if (tab.nav === navHistory) {
      loadHistoryList();
    } else if (tab.nav === navCalendar) {
      renderCalendar();
    } else if (tab.nav === navActionItems) {
      renderActionItems();
    }
  }
  
  function openMeetingForCall(call) {
    const sessionId = call.id || (call.filePath ? call.filePath.replace(/\.trail.*$/, '').split('/').pop() : null);
    if (sessionId) window.api.openMeeting(sessionId);
  }

  function escapeHtml(value) {
    return String(value ?? '')
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  function renderHubHome(calls = []) {
    const host = document.getElementById('hub-recent-sessions');
    if (!host) return;
    const recent = [...calls]
      .sort((a, b) => (b.mtimeMs || 0) - (a.mtimeMs || 0))
      .slice(0, 8);

    if (!recent.length) {
      host.innerHTML = `
        <div class="hub-empty-state">
          <p>No meetings yet. Start your first Trail and TrailMix will keep everything local.</p>
          <button type="button" class="btn-primary" id="btn-hub-empty-new">Start a meeting</button>
        </div>`;
      document.getElementById('btn-hub-empty-new')?.addEventListener('click', startNewMeeting);
      return;
    }

    host.innerHTML = `
      <div class="hub-recent-header">
        <h2>Recent meetings</h2>
      </div>
      <div class="hub-recent-grid"></div>`;
    const grid = host.querySelector('.hub-recent-grid');
    recent.forEach((call) => {
      const card = document.createElement('article');
      card.className = 'history-card glassmorphic hub-recent-card';
      const summary = call.encrypted && !call.unlocked
        ? 'Encrypted session'
        : (call.summary || call.description || 'No summary yet.');
      card.innerHTML = `
        <div class="history-card-header">
          <h3>${escapeHtml(call.title || 'Meeting Session')}</h3>
          ${call.encrypted ? '<span class="lock-badge">🔒</span>' : ''}
        </div>
        <div class="history-card-date">${escapeHtml(call.date || '')}</div>
        <p class="history-card-desc">${escapeHtml(summary)}</p>
        <div class="hub-card-actions">
          <button type="button" class="btn-secondary btn-hub-open">Open</button>
          <button type="button" class="btn-primary btn-hub-resume">Resume Trail</button>
        </div>`;
      card.querySelector('.btn-hub-open')?.addEventListener('click', (e) => {
        e.stopPropagation();
        openMeetingForCall(call);
      });
      card.querySelector('.btn-hub-resume')?.addEventListener('click', (e) => {
        e.stopPropagation();
        window.api.resumeCallTranscription(call.id).then((res) => {
          if (res?.requirePassword) {
            handleCallSelect(call);
            return;
          }
          if (res?.conflict) {
            alert(`Another Trail is already recording (${res.activeSessionId}).`);
            return;
          }
          if (res?.success === false) {
            alert(res.error || 'Could not resume this Trail.');
            return;
          }
          openMeetingForCall(call);
        }).catch((err) => alert(err?.message || 'Could not resume this Trail.'));
      });
      card.addEventListener('click', () => openMeetingForCall(call));
      grid.appendChild(card);
    });
  }

  const btnNewMeeting = document.getElementById('btn-new-meeting');
  const startNewMeeting = () => window.api.createMeeting();
  if (btnNewMeeting) btnNewMeeting.addEventListener('click', startNewMeeting);
  document.getElementById('btn-hub-new-meeting')?.addEventListener('click', startNewMeeting);
  document.getElementById('btn-hub-browse-trail')?.addEventListener('click', () => {
    activateTab({ nav: navHistory, pane: tabHistory });
  });
  
  tabs.forEach(tab => {
    if (!tab.nav) return;
    tab.nav.addEventListener('click', () => activateTab(tab));
  });

  // Meeting capture UI lives in renderer/meeting.js (hub no longer embeds record controls).


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
  let selectedHistoryTags = new Set();

  const historyTagFilters = document.getElementById('history-tag-filters');
  const historyTagPills = document.getElementById('history-tag-pills');
  const btnClearTagFilters = document.getElementById('btn-clear-tag-filters');
  const btnBulkDecrypt = document.getElementById('btn-bulk-decrypt');
  const bulkDecryptModal = document.getElementById('bulk-decrypt-modal');
  const bulkDecryptPassword = document.getElementById('bulk-decrypt-password');
  const bulkDecryptError = document.getElementById('bulk-decrypt-error');
  const bulkDecryptCount = document.getElementById('bulk-decrypt-count');
  const btnBulkDecryptCancel = document.getElementById('btn-bulk-decrypt-cancel');
  const btnBulkDecryptSubmit = document.getElementById('btn-bulk-decrypt-submit');

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
    if (btnBulkDecrypt) {
      const lockedSelected = Array.from(checked).filter((cb) => {
        const item = cb.closest('.call-list-item');
        return item?.querySelector('.call-lock-btn');
      });
      btnBulkDecrypt.disabled = lockedSelected.length < 1;
    }
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
  
  if (btnBulkDecrypt) {
    btnBulkDecrypt.addEventListener('click', () => {
      const checked = Array.from(document.querySelectorAll('.call-item-checkbox:checked'));
      const lockedIds = checked
        .map((cb) => cb.closest('.call-list-item')?.getAttribute('data-callid'))
        .filter((id, index) => id && checked[index].closest('.call-list-item')?.querySelector('.call-lock-btn'));
      if (!lockedIds.length) return;
      bulkDecryptCount.textContent = `Unlock ${lockedIds.length} encrypted session(s) with one key.`;
      bulkDecryptPassword.value = '';
      bulkDecryptError.classList.add('hidden');
      bulkDecryptModal.classList.remove('hidden');
      bulkDecryptModal.dataset.sessionIds = JSON.stringify(lockedIds);
    });
  }

  btnBulkDecryptCancel?.addEventListener('click', () => {
    bulkDecryptModal.classList.add('hidden');
  });

  btnBulkDecryptSubmit?.addEventListener('click', () => {
    const password = bulkDecryptPassword?.value;
    if (!password) return;
    let sessionIds = [];
    try {
      sessionIds = JSON.parse(bulkDecryptModal.dataset.sessionIds || '[]');
    } catch (_) {
      return;
    }
    window.api.decryptMultipleCalls(sessionIds, password).then((res) => {
      if (res.failed?.length) {
        bulkDecryptError.textContent = `Unlocked ${res.unlocked?.length || 0}. Failed: ${res.failed.map((f) => f.sessionId).join(', ')}`;
        bulkDecryptError.classList.remove('hidden');
        loadHistoryList();
      } else {
        bulkDecryptModal.classList.add('hidden');
        loadHistoryList();
      }
    });
  });

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

  function renderHistoryTagFilters(tags) {
    if (!historyTagFilters || !historyTagPills) return;
    if (!tags.length) {
      historyTagFilters.classList.add('hidden');
      historyTagPills.innerHTML = '';
      return;
    }
    historyTagFilters.classList.remove('hidden');
    historyTagPills.innerHTML = '';
    tags.forEach((tag) => {
      const pill = document.createElement('button');
      pill.type = 'button';
      pill.className = `history-tag-pill ${selectedHistoryTags.has(tag) ? 'active' : ''}`;
      pill.textContent = `#${tag}`;
      pill.addEventListener('click', () => {
        if (selectedHistoryTags.has(tag)) selectedHistoryTags.delete(tag);
        else selectedHistoryTags.add(tag);
        loadHistoryList();
      });
      historyTagPills.appendChild(pill);
    });
  }

  if (btnClearTagFilters) {
    btnClearTagFilters.addEventListener('click', () => {
      selectedHistoryTags.clear();
      loadHistoryList();
    });
  }

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
        headerTitle.textContent = 'The Trail';
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
      
      // Filter by active folder (filesystem-backed)
      if (activeFolderId && activeFolderId !== 'all') {
        filteredCalls = filteredCalls.filter((c) => {
          const folderId = c.folder_id || 'fs:';
          if (activeFolderId === 'fs:') {
            return !folderId || folderId === 'fs:' || folderId === 'work';
          }
          return folderId === activeFolderId;
        });
      }

      // Collect tags for history filter UI
      const tagSet = new Set();
      calls.forEach((c) => {
        (c.tags || []).forEach((tag) => tagSet.add(tag));
      });
      renderHistoryTagFilters([...tagSet].sort());

      if (selectedHistoryTags.size > 0) {
        filteredCalls = filteredCalls.filter((c) => {
          const callTags = c.tags || [];
          return [...selectedHistoryTags].every((tag) => callTags.includes(tag));
        });
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
        sidebarCallsList.innerHTML = '<div class="empty-state">No matching calls.</div>';
        historyGrid.innerHTML = '<div class="empty-state">No matching calls.</div>';
        renderHubHome(calls);
        return;
      }
      
      filteredCalls.forEach((call) => {
        const item = document.createElement('div');
        const isSelected = activeSession && activeSession.id === call.id;
        item.className = `call-list-item ${isSelected ? 'selected' : ''}`;
        item.setAttribute('data-callid', call.id);
        
        let checkboxHtml = '';
        if (isMultiSelectMode) {
          checkboxHtml = `<input type="checkbox" class="call-item-checkbox" data-filepath="${call.filePath}">`;
        }
        
        const tagline = call.title || 'Meeting Session';
        const description = call.encrypted && !call.unlocked
          ? '🔒 Encrypted — click to unlock'
          : (call.encrypted ? 'Encrypted session' : (call.description || 'No description available.'));
        
        let tagsHtml = '';
        const tags = call.tags || [];
        const suggestedTags = call.suggestedTags || [];
        
        if (tags.length > 0 || suggestedTags.length > 0) {
          tagsHtml = '<div class="call-tags-container">';
          tags.forEach(tag => {
            tagsHtml += `<span class="tag-pill">#${tag}</span>`;
          });
          if (!isMultiSelectMode) {
            suggestedTags.forEach(tag => {
              tagsHtml += `<span class="suggested-tag-pill" data-tag="${tag}">+ ${tag}</span>`;
            });
          }
          tagsHtml += '</div>';
        }

        let processingHtml = '';
        if (call.processing && call.processing.status && call.processing.status !== 'complete') {
          const label = call.processing.label || 'Processing…';
          const progress = call.processing.progress || 0;
          const failed = call.processing.status === 'failed';
          processingHtml = `
            <div class="processing-badge ${failed ? 'failed' : ''}"><span class="processing-badge-dot"></span>${label}</div>
            <div class="processing-progress-track"><div class="processing-progress-fill" style="width: ${progress}%"></div></div>
            ${failed ? `<button type="button" class="processing-retry-btn" data-retry-id="${call.id}">Retry</button>` : ''}
          `;
        }
        
        item.innerHTML = `
          ${checkboxHtml}
          <div class="call-item-details">
            <div class="call-item-title">${call.encrypted && !call.unlocked ? '<button type="button" class="call-lock-btn" title="Unlock">🔒</button> ' : (call.encrypted ? '🔒 ' : '')}${tagline}</div>
            <div class="call-item-date">${call.date || ''}</div>
            <div class="call-item-desc">${description}</div>
            ${tagsHtml}
            ${processingHtml}
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
            if (call.encrypted && !call.unlocked) {
              pendingCallToDecrypt = call;
              modalPasswordInput.value = '';
              modalErrorMessage.classList.add('hidden');
              passwordModal.classList.remove('hidden');
            } else {
              handleCallSelect(call);
            }
          }
        });

        const lockBtn = item.querySelector('.call-lock-btn');
        if (lockBtn) {
          lockBtn.addEventListener('click', (e) => {
            e.stopPropagation();
            pendingCallToDecrypt = call;
            modalPasswordInput.value = '';
            modalErrorMessage.classList.add('hidden');
            passwordModal.classList.remove('hidden');
          });
        }

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

        const retryBtn = item.querySelector('.processing-retry-btn');
        if (retryBtn) {
          retryBtn.addEventListener('click', (e) => {
            e.stopPropagation();
            const sid = retryBtn.getAttribute('data-retry-id');
            if (!sid || !window.api.retryProcessing) return;
            retryBtn.disabled = true;
            retryBtn.textContent = 'Retrying…';
            window.api.retryProcessing(sid).then(() => {
              scheduleHistoryRefresh();
            }).catch(() => {
              retryBtn.disabled = false;
              retryBtn.textContent = 'Retry';
            });
          });
        }

        // History tab grid
        if (!relatedCallsList) {
          const card = document.createElement('div');
          card.className = 'history-card glassmorphic';
          card.innerHTML = `
            <div class="history-card-header">
              <h3>${escapeHtml(call.title || 'Meeting Session')}</h3>
              ${call.encrypted ? '<span class="lock-badge">🔒 Locked</span>' : ''}
            </div>
            <div class="history-card-date">${escapeHtml(call.date || '')}</div>
            <p class="history-card-desc">${escapeHtml(call.encrypted ? 'Encrypted session' : (call.summary || 'No summary available.'))}</p>
          `;
          card.addEventListener('click', () => {
            activateTab({ nav: navHome, pane: tabHubHome });
            openMeetingForCall(call);
          });
          historyGrid.appendChild(card);
        }
      });

      renderHubHome(filteredCalls);
      
      if (relatedCallsList && historyGrid) {
        historyGrid.innerHTML = '<div class="empty-state">Filtered for related calls. View the sidebar list.</div>';
      }
    }).catch((err) => {
      console.error('Failed to load history list', err);
      sidebarCallsList.innerHTML = '<div class="empty-state">Could not load sessions.</div>';
    });
  }

  let historyRefreshTimer = null;
  function scheduleHistoryRefresh(delayMs = 350) {
    clearTimeout(historyRefreshTimer);
    historyRefreshTimer = setTimeout(() => loadHistoryList(), delayMs);
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
          <span>All notes</span>
        </div>
      `;
      allItem.addEventListener('click', () => {
        activeFolderId = 'all';
        renderFolders();
        loadHistoryList();
      });
      folderList.appendChild(allItem);

      // Render filesystem folders from save location
      folders.forEach(folder => {
        const item = document.createElement('div');
        const isSelected = activeFolderId === folder.id;
        const isRootTrail = folder.id === 'fs:';
        const canManage = folder.id.startsWith('fs:') && !isRootTrail;
        item.className = `folder-list-item flex items-center justify-between px-2.5 py-1.5 rounded-lg cursor-pointer transition text-xs ${
          isSelected 
            ? 'bg-trail-500/10 text-trail-400 font-semibold border border-trail-500/20' 
            : 'text-slate-400 border border-transparent hover:bg-slate-800/30 hover:text-slate-200'
        }`;
        
        item.innerHTML = `
          <div class="folder-item-main flex items-center gap-2 flex-grow truncate" title="${folder.description || ''}">
            <span class="folder-icon">${folder.icon || '📁'}</span>
            <div class="folder-text truncate">
              <span class="truncate">${folder.name}</span>
              ${folder.description ? `<span class="folder-description">${folder.description}</span>` : ''}
            </div>
          </div>
          <div class="flex items-center gap-1.5 folder-actions opacity-60 hover:opacity-100 transition">
            ${canManage ? '<button class="btn-folder-edit p-0.5 text-slate-400 hover:text-trail-400 transition" title="Rename folder" data-folderid="' + folder.id + '">✏️</button>' : ''}
            ${canManage ? '<button class="btn-folder-export p-0.5 text-slate-400 hover:text-trail-400 transition" title="Export Folder to Obsidian" data-folderid="' + folder.id + '">📤</button>' : ''}
            ${canManage ? '<button class="btn-folder-delete p-0.5 text-slate-400 hover:text-red-400 transition" title="Delete Folder" data-folderid="' + folder.id + '">🗑️</button>' : ''}
          </div>
        `;
        
        item.addEventListener('click', (e) => {
          if (e.target.closest('.folder-actions')) return;
          activeFolderId = folder.id;
          renderFolders();
          loadHistoryList();
        });

        const editBtn = item.querySelector('.btn-folder-edit');
        if (editBtn) {
          editBtn.addEventListener('click', (e) => {
            e.stopPropagation();
            openFolderModal({ mode: 'edit', folder });
          });
        }

        const deleteBtn = item.querySelector('.btn-folder-delete');
        if (deleteBtn) {
          deleteBtn.addEventListener('click', (e) => {
            e.stopPropagation();
            if (confirm(`Delete folder "${folder.name}"? It must be empty first.`)) {
              window.api.deleteFolder(folder.id).then((res) => {
                if (res?.success === false) {
                  alert(res.error || 'Could not delete folder');
                  return;
                }
                if (activeFolderId === folder.id) activeFolderId = 'all';
                renderFolders();
                loadHistoryList();
              });
            }
          });
        }

        const exportBtn = item.querySelector('.btn-folder-export');
        if (exportBtn) {
          exportBtn.addEventListener('click', (e) => {
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
        }

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

  // Folder modal (Electron does not support prompt())
  const folderModal = document.getElementById('folder-modal');
  const folderModalTitle = document.getElementById('folder-modal-title');
  const folderModalName = document.getElementById('folder-modal-name');
  const folderModalIcon = document.getElementById('folder-modal-icon');
  const folderModalDescription = document.getElementById('folder-modal-description');
  const btnFolderModalCancel = document.getElementById('btn-folder-modal-cancel');
  const btnFolderModalSave = document.getElementById('btn-folder-modal-save');
  let folderModalMode = 'create';
  let folderModalTargetId = null;

  function openFolderModal({ mode = 'create', folder = null } = {}) {
    if (!folderModal) return;
    folderModalMode = mode;
    folderModalTargetId = folder?.id || null;
    folderModalTitle.textContent = mode === 'edit' ? 'Edit Folder' : 'New Folder';
    folderModalName.value = folder?.name || '';
    folderModalIcon.value = folder?.icon || '📁';
    folderModalDescription.value = folder?.description || '';
    folderModal.classList.remove('hidden');
    folderModalName.focus();
  }

  function closeFolderModal() {
    if (!folderModal) return;
    folderModal.classList.add('hidden');
    folderModalTargetId = null;
  }

  function saveFolderModal() {
    const name = folderModalName?.value.trim();
    if (!name) return;
    const icon = folderModalIcon?.value.trim() || '📁';
    const description = folderModalDescription?.value.trim() || '';

    if (folderModalMode === 'edit' && folderModalTargetId) {
      window.api.updateFolder({ id: folderModalTargetId, name, icon, description }).then((res) => {
        if (res.success) {
          closeFolderModal();
          renderFolders();
        }
      });
      return;
    }

    window.api.createFolder({ name, icon, description }).then((res) => {
      if (res.success) {
        closeFolderModal();
        renderFolders();
      } else {
        alert('Error creating folder: ' + res.error);
      }
    });
  }

  btnFolderModalCancel?.addEventListener('click', closeFolderModal);
  btnFolderModalSave?.addEventListener('click', saveFolderModal);
  folderModalName?.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') saveFolderModal();
  });

  // Add Folder Button
  const btnAddFolder = document.getElementById('btn-add-folder');
  if (btnAddFolder) {
    btnAddFolder.addEventListener('click', () => openFolderModal({ mode: 'create' }));
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
      scheduleHistoryRefresh();
    });
  }

  if (window.api.onProcessingJobsUpdated) {
    window.api.onProcessingJobsUpdated(() => {
      scheduleHistoryRefresh(250);
    });
  }

  if (window.api.onProcessingTranscriptUpdated) {
    window.api.onProcessingTranscriptUpdated(() => {
      scheduleHistoryRefresh(400);
    });
  }

  // Listen to background summary updates
  if (window.api.onSessionSummaryReady) {
    window.api.onSessionSummaryReady((session) => {
      console.log('Received session summary ready:', session);
      // Auto-refresh active session if it matches
      if (activeSession && session.id === activeSession.id) {
        scheduleHistoryRefresh(200);
      } else {
        scheduleHistoryRefresh(500);
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
      window.api.loadCall(call.filePath).then(res => {
        if (res.success) {
          openMeetingForCall(res.session || call);
        } else if (res.requirePassword) {
          pendingCallToDecrypt = call;
          modalPasswordInput.value = '';
          modalErrorMessage.classList.add('hidden');
          passwordModal.classList.remove('hidden');
        }
      });
    } else {
      openMeetingForCall(call);
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
        openMeetingForCall(res.session || pendingCallToDecrypt);
        loadHistoryList();
      } else {
        modalErrorMessage.classList.remove('hidden');
      }
    });
  });

  function displaySession(session) {
    document.querySelectorAll('.call-list-item').forEach(item => {
      item.classList.toggle('selected', item.getAttribute('data-callid') === session.id);
    });
    openMeetingForCall(session);
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
  const settingsDirtyHint = document.getElementById('settings-dirty-hint');
  
  function markSettingsDirty() {
    if (btnSaveSettings) btnSaveSettings.disabled = false;
    if (settingsDirtyHint) settingsDirtyHint.style.opacity = '1';
  }

  function resetSettingsDirtyState() {
    if (btnSaveSettings) btnSaveSettings.disabled = true;
    if (settingsDirtyHint) settingsDirtyHint.style.opacity = '0';
  }
  
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
    const selectAecMode = document.getElementById('select-aec-mode');
    if (selectAecMode) selectAecMode.value = saved.aecMode || 'os';
    const checkAutoUpdate = document.getElementById('check-auto-update');
    if (checkAutoUpdate) checkAutoUpdate.checked = saved.autoUpdateEnabled !== false;
    const labelAppVersion = document.getElementById('label-app-version');
    if (labelAppVersion && window.api.getAppVersion) {
      window.api.getAppVersion().then((version) => {
        labelAppVersion.textContent = version || '—';
      });
    }
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
            markSettingsDirty();
          }
        });
      });
    }

    const inputSidebarSearch = document.getElementById('input-sidebar-search');
    if (inputSidebarSearch) {
      let searchDebounceTimer = null;
      inputSidebarSearch.addEventListener('input', () => {
        clearTimeout(searchDebounceTimer);
        searchDebounceTimer = setTimeout(() => {
          loadHistoryList();
        }, 250);
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

    resetSettingsDirtyState();
  });

  const settingsInputs = [
    selectWhisperModel, selectLlmModel, selectMicDevice, selectSysDevice,
    checkEncryptDefault, inputEncryptPassword,
    document.getElementById('check-color-deadlines'),
    document.getElementById('input-user-name'),
    document.getElementById('check-noise-cancel'),
    document.getElementById('select-aec-mode'),
    document.getElementById('check-auto-update'),
    document.getElementById('input-storage-path'),
    selectNoteStyle, textareaNotePrompt, textareaSummaryPrompt, textareaActionPrompt
  ].filter(Boolean);

  settingsInputs.forEach((el) => {
    el.addEventListener('change', markSettingsDirty);
    el.addEventListener('input', markSettingsDirty);
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
      aecMode: document.getElementById('select-aec-mode')?.value || 'os',
      autoUpdateEnabled: document.getElementById('check-auto-update')?.checked !== false,
      customStoragePath: document.getElementById('input-storage-path').value,
      selectedNoteStyle: selectNoteStyle ? selectNoteStyle.value : 'executive',
      notePromptTemplate: textareaNotePrompt ? textareaNotePrompt.value : '',
      summaryPromptTemplate: textareaSummaryPrompt ? textareaSummaryPrompt.value : '',
      actionPromptTemplate: textareaActionPrompt ? textareaActionPrompt.value : ''
    };
    
    window.api.saveSettings(updated).then(() => {
      activeSettings = updated;
      resetSettingsDirtyState();
      if (settingsDirtyHint) {
        settingsDirtyHint.textContent = 'Settings saved';
        settingsDirtyHint.style.opacity = '1';
        setTimeout(() => {
          if (settingsDirtyHint.textContent === 'Settings saved') {
            settingsDirtyHint.style.opacity = '0';
            settingsDirtyHint.textContent = 'Unsaved changes';
          }
        }, 1800);
      }
    }).catch((err) => {
      console.error('Failed to save settings', err);
      alert(err?.message || 'Failed to save settings.');
    });
  });

  // Software updates UI
  const updatesStatusText = document.getElementById('updates-status-text');
  const updatesProgressContainer = document.getElementById('updates-progress-container');
  const updatesProgressPercent = document.getElementById('updates-progress-percent');
  const updatesProgressFill = document.getElementById('updates-progress-fill');
  const btnCheckUpdates = document.getElementById('btn-check-updates');
  const btnInstallUpdate = document.getElementById('btn-install-update');

  function renderUpdateStatus(status) {
    if (!status || !updatesStatusText) return;
    if (status.currentVersion) {
      const labelAppVersion = document.getElementById('label-app-version');
      if (labelAppVersion) labelAppVersion.textContent = status.currentVersion;
    }
    switch (status.state) {
      case 'checking':
        updatesStatusText.textContent = 'Checking for updates…';
        break;
      case 'available':
        updatesStatusText.textContent = `Update available: v${status.availableVersion}`;
        break;
      case 'downloading':
        updatesStatusText.textContent = `Downloading update… ${status.progress || 0}%`;
        if (updatesProgressContainer) updatesProgressContainer.classList.remove('hidden');
        if (updatesProgressPercent) updatesProgressPercent.textContent = `${status.progress || 0}%`;
        if (updatesProgressFill) updatesProgressFill.style.width = `${status.progress || 0}%`;
        break;
      case 'ready':
        updatesStatusText.textContent = `Update v${status.availableVersion} ready to install.`;
        if (btnInstallUpdate) btnInstallUpdate.classList.remove('hidden');
        if (updatesProgressContainer) updatesProgressContainer.classList.add('hidden');
        break;
      case 'up-to-date':
        updatesStatusText.textContent = 'You are on the latest version.';
        if (btnInstallUpdate) btnInstallUpdate.classList.add('hidden');
        break;
      case 'error':
        updatesStatusText.textContent = status.error || 'Update check failed.';
        break;
      default:
        updatesStatusText.textContent = 'Ready to check for updates.';
    }
  }

  if (window.api.onUpdateStatus) {
    window.api.onUpdateStatus(renderUpdateStatus);
  }
  if (window.api.getUpdateStatus) {
    window.api.getUpdateStatus().then(renderUpdateStatus);
  }
  if (btnCheckUpdates) {
    btnCheckUpdates.addEventListener('click', () => {
      window.api.checkForUpdates({ manual: true });
    });
  }
  if (btnInstallUpdate) {
    btnInstallUpdate.addEventListener('click', () => {
      window.api.installUpdate();
    });
  }

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
  const chatRecipesContainer = document.getElementById('chat-recipes');
  const globalAskBar = document.getElementById('global-ask-bar');
  const inputGlobalAsk = document.getElementById('input-global-ask');
  const btnGlobalAskOpen = document.getElementById('btn-global-ask-open');

  const CHAT_WELCOME_HTML = `
    <div class="msg system">
      Hi! I'm Mix-Master, your offline meeting AI. Ask questions or use a recipe above to analyze your notes and transcript.
    </div>
  `;

  function clearChatHistory() {
    if (!chatMessages) return;
    chatMessages.innerHTML = CHAT_WELCOME_HTML;
    messageCounter = 0;
  }

  function toggleChatSidebar(isOpen) {
    if (!chatAgentWidget) return;
    if (isOpen) {
      chatAgentWidget.classList.remove('closed');
      if (btnChatSidebarToggle) btnChatSidebarToggle.classList.add('hidden');
      if (globalAskBar) globalAskBar.classList.add('chat-open');
    } else {
      chatAgentWidget.classList.add('closed');
      if (btnChatSidebarToggle) btnChatSidebarToggle.classList.remove('hidden');
      if (globalAskBar) globalAskBar.classList.remove('chat-open');
      // Keep chat history when closing so toggling Mix-Master is non-destructive.
    }
  }

  if (btnChatToggle) {
    btnChatToggle.addEventListener('click', () => {
      toggleChatSidebar(false);
    });
  }

  if (btnChatSidebarToggle) {
    btnChatSidebarToggle.addEventListener('click', () => {
      toggleChatSidebar(true);
    });
  }

  if (btnChatSend) btnChatSend.addEventListener('click', submitChatQuery);
  if (inputChatQuery) {
    inputChatQuery.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') submitChatQuery();
    });
  }

  if (inputGlobalAsk) {
    inputGlobalAsk.addEventListener('focus', () => toggleChatSidebar(true));
    inputGlobalAsk.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') {
        const text = inputGlobalAsk.value.trim();
        if (!text) return;
        toggleChatSidebar(true);
        inputChatQuery.value = text;
        inputGlobalAsk.value = '';
        submitChatQuery();
      }
    });
  }

  if (btnGlobalAskOpen) {
    btnGlobalAskOpen.addEventListener('click', () => toggleChatSidebar(true));
  }

  function renderChatRecipes() {
    if (!chatRecipesContainer || !window.api.getChatRecipes) return;
    window.api.getChatRecipes().then((recipes) => {
      chatRecipesContainer.innerHTML = '';
      recipes.forEach((recipe) => {
        const pill = document.createElement('button');
        pill.type = 'button';
        pill.className = 'chat-recipe-pill';
        pill.textContent = `${recipe.icon || ''} ${recipe.label}`.trim();
        pill.addEventListener('click', () => {
          toggleChatSidebar(true);
          runRecipeQuery(recipe);
        });
        chatRecipesContainer.appendChild(pill);
      });
    });
  }

  function runRecipeQuery(recipe) {
    appendChatMessage('user', `${recipe.icon || ''} ${recipe.label}`.trim());
    const loadingId = appendChatMessage('assistant', 'Running recipe locally…');

    const transcriptText = activeSession && activeSession.transcript
      ? activeSession.transcript.map(t => `[${t.timestamp}] ${t.speaker}: ${t.text}`).join('\n')
      : 'No transcript active.';

    window.api.chatQuery(recipe.id, transcriptText).then((result) => {
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

  renderChatRecipes();

  document.addEventListener('keydown', (event) => {
    const isMeta = event.ctrlKey || event.metaKey;
    const target = event.target;
    const tag = target?.tagName;
    const typing = tag === 'INPUT' || tag === 'TEXTAREA' || target?.isContentEditable;

    if (event.key === 'Escape') {
      passwordModal?.classList.add('hidden');
      bulkDecryptModal?.classList.add('hidden');
      callsContextMenu?.classList.add('hidden');
      historyMenuDropdown?.classList.add('hidden');
      document.getElementById('folder-modal')?.classList.add('hidden');
      return;
    }

    if (!isMeta && event.key === '/' && !typing) {
      event.preventDefault();
      const search = document.getElementById('input-sidebar-search');
      search?.focus();
      search?.select();
      return;
    }

    if (!isMeta) return;

    if (event.key.toLowerCase() === 'b') {
      event.preventDefault();
      if (typeof window.toggleTrailSidebar === 'function') {
        window.toggleTrailSidebar();
      }
    }

    if (event.key.toLowerCase() === 'j') {
      event.preventDefault();
      toggleChatSidebar(true);
      if (inputChatQuery) inputChatQuery.focus();
    }

    if (event.key.toLowerCase() === 'n') {
      if (typing) return;
      event.preventDefault();
      document.getElementById('btn-new-meeting')?.click();
    }
  });

  // Initialize closed state
  toggleChatSidebar(false);

  function submitChatQuery() {
    const text = inputChatQuery.value.trim();
    if (!text) return;
    
    appendChatMessage('user', text);
    inputChatQuery.value = '';
    if (inputGlobalAsk) inputGlobalAsk.value = '';
    
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
        openMeetingForCall(call);
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
