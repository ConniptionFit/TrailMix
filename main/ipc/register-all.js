/**
 * IPC handler registration — domain-split ready.
 */
const { IPC } = require('../../lib/ipc-channels');

function registerIpcHandlers(rt) {
  const {
    ipcMain, app, dialog, shell, path, fs, os, encryption, spawn, BrowserWindow,
    dbRun, dbAll, dbGet, DATA_DIR, PROJECT_DIR, XDG_CONFIG_DIR, WHISPER_DIR,
    getHubWindow, broadcastToSession, broadcastRecordingStatus,
    queryAudioDevices, startRecordingHandler, pauseRecordingHandler,
    stopRecordingHandler, resumeCallTranscriptionHandler,
    getSession, setSession, createNewSession, deleteSession,
    loadSessionPayloadFromDb, saveSessionToDbPromise, saveSessionToFileSilently,
    scheduleSilentSessionSave, flushSilentSessionSave,
    saveSessionToFile, resolveSessionRecord, syncDatabaseWithFiles,
    getCallsDir, watchCallsDirectory, openMeetingWindow, saveSettings,
    getHardwareSpecs, runSessionEnrichment, extractTasksFromActionItems,
    CHAT_RECIPES, buildRecipePrompt, llmService, sessionProcessingService,
    enhanceNotesService, updateService,
    getTasksListFromDb, saveTaskToDb, upsertSessionFromTrailFile,
    parseDeadlineDate, appEventBus,
    UNCATEGORIZED_FOLDER_ID, moveStorageContents, resolveSessionFilePath,
    secureShredFile, formatTaskRow, formatTimestamp, documentFromSession,
    splitTranscriptIntoBlocks, PROCESSING_STATUS, decryptionKeys,
    activeRecordingSessionId, isRecording, isPaused, activeSession, windowManager,
    searchSessionsFts, deleteSessionFts, upsertSessionFts
  } = rt;

  let callsListSyncedOnce = false;

  const DEFAULT_PROMPTS = rt.DEFAULT_PROMPTS;

// ----------------------------------------------------

ipcMain.handle(IPC.AUDIO_GET_DEVICES, () => {
  return queryAudioDevices();
});

ipcMain.handle(IPC.SETTINGS_GET, () => {
  const { encryptionPassword, ...safe } = rt.settings || {};
  return safe;
});

ipcMain.handle(IPC.SETTINGS_GET_DEFAULT_PROMPTS, () => {
  return DEFAULT_PROMPTS;
});

ipcMain.handle(IPC.SETTINGS_SELECT_DIRECTORY, async () => {
  if (!getHubWindow()) return null;
  const { filePaths } = await dialog.showOpenDialog(getHubWindow(), {
    properties: ['openDirectory', 'createDirectory']
  });
  if (filePaths && filePaths.length > 0) {
    return filePaths[0];
  }
  return null;
});

ipcMain.handle(IPC.SETTINGS_SAVE, async (event, newSettings) => {
  const oldStoragePath = rt.settings.customStoragePath || path.join(DATA_DIR, 'calls');
  const newStoragePath = newSettings.customStoragePath || path.join(DATA_DIR, 'calls');
  
  let moveFiles = false;
  if (oldStoragePath !== newStoragePath) {
    const oldExists = fs.existsSync(oldStoragePath);
    const filesToMove = oldExists ? fs.readdirSync(oldStoragePath).filter(f => f.endsWith('.trail') || f.endsWith('.trail.bak')) : [];
    
    if (filesToMove.length > 0) {
      const choice = await dialog.showMessageBox(getHubWindow(), {
        type: 'question',
        buttons: ['Yes', 'No'],
        defaultId: 0,
        title: 'Move Existing Transcripts',
        message: 'Would you like to move your existing transcripts to the new location?',
        detail: `This will move ${filesToMove.length} files to ${newStoragePath}.`
      });
      if (choice.response === 0) {
        moveFiles = true;
      }
    }
    
    if (!fs.existsSync(newStoragePath)) {
      fs.mkdirSync(newStoragePath, { recursive: true });
    }
    
    if (moveFiles) {
      for (const entry of fs.readdirSync(oldStoragePath)) {
        const src = path.join(oldStoragePath, entry);
        const dest = path.join(newStoragePath, entry);
        try {
          if (fs.existsSync(dest)) continue;
          fs.renameSync(src, dest);
        } catch (e) {
          console.error(`Failed to move ${entry}:`, e);
        }
      }
    }
  }
  
  const incoming = { ...(newSettings || {}) };
  delete incoming.encryptionPassword;
  rt.settings = { ...rt.settings, ...incoming };
  delete rt.settings.encryptionPassword;
  saveSettings();

  if (rt.settings.autoUpdateEnabled !== false) {
    void updateService.checkForUpdates({ manual: false });
  }
  
  // Start watcher on the new directory
  watchCallsDirectory();
  
  // Sync the database with the directory state
  await syncDatabaseWithFiles();
  
  // Reload call list on frontend
  if (getHubWindow()) {
    getHubWindow().webContents.send(IPC.CALLS_LIST_UPDATED);
  }
  return true;
});

ipcMain.handle(IPC.AUDIO_START_RECORDING, async (event, sessionId) => {
  return await startRecordingHandler(sessionId);
});

ipcMain.handle(IPC.AUDIO_STOP_RECORDING, async () => {
  try {
    await stopRecordingHandler();
    return { success: true };
  } catch (err) {
    console.error('Stop recording failed:', err);
    return { success: false, error: err.message };
  }
});

ipcMain.handle(IPC.AUDIO_PAUSE_RECORDING, () => {
  pauseRecordingHandler();
  return true;
});

ipcMain.handle(IPC.AUDIO_RESUME_RECORDING, async (event, sessionId) => {
  if (!isPaused) return false;
  return await startRecordingHandler(sessionId || activeRecordingSessionId);
});

ipcMain.handle(IPC.AUDIO_GET_RECORDING_STATUS, () => ({
  isRecording,
  isPaused,
  sessionId: activeRecordingSessionId
}));

ipcMain.handle(IPC.MEETINGS_OPEN, async (event, sessionId, options = {}) => {
  openMeetingWindow(sessionId, options || {});
  return { success: true };
});

ipcMain.handle(IPC.MEETINGS_NEW, async () => {
  const session = createNewSession({ encryptByDefault: rt.settings.encryptByDefault });
  // Defer first disk write for encrypted sessions until a password is provided.
  if (!session.encrypted) {
    await saveSessionToDbPromise(session);
  } else {
    setSession(session);
  }
  openMeetingWindow(session.id);
  return { success: true, sessionId: session.id };
});

ipcMain.handle(IPC.MEETINGS_FOCUS, async (event, sessionId) => {
  let win = windowManager?.getMeetingWindow(sessionId);
  if (!win) {
    openMeetingWindow(sessionId);
    win = windowManager?.getMeetingWindow(sessionId);
  }
  if (win) {
    win.focus();
    if (activeRecordingSessionId === sessionId && isRecording) {
      broadcastRecordingStatus({ isNewSession: false });
    }
    return { success: true };
  }
  return { success: false };
});

ipcMain.handle(IPC.SESSION_LOAD_MEETING, async (event, sessionId) => {
  let session = getSession(sessionId);
  if (!session) {
    session = await loadSessionPayloadFromDb(sessionId);
    if (session) setSession(session);
  }
  return session;
});

ipcMain.handle(IPC.SESSION_SET_TITLE, async (event, sessionId, title, userEdited = true) => {
  let session = getSession(sessionId) || await loadSessionPayloadFromDb(sessionId);
  if (!session) return { success: false };
  session.title = title;
  session.titleUserEdited = userEdited;
  if (userEdited) session.titleAutoGenerated = false;
  setSession(session);
  await saveSessionToDbPromise(session);
  broadcastToSession(sessionId, IPC.SESSION_UPDATED, session);
  const hub = getHubWindow();
  if (hub) hub.webContents.send(IPC.CALLS_LIST_UPDATED);
  return { success: true };
});

ipcMain.handle(IPC.PROCESSING_GET_JOBS, async () => {
  return sessionProcessingService.getJobMap();
});

ipcMain.handle(IPC.PROCESSING_RETRY, async (event, sessionId) => {
  await sessionProcessingService.createJob(sessionId, splitTranscriptIntoBlocks((await loadSessionPayloadFromDb(sessionId))?.transcript || []).length);
  return { success: true };
});

ipcMain.handle(IPC.CALLS_RESUME_TRANSCRIPTION, async (event, sessionId) => {
  return await resumeCallTranscriptionHandler(sessionId);
});

function formatTranscriptForChat(transcript) {
  if (!Array.isArray(transcript) || transcript.length === 0) return '';
  return transcript
    .map((segment) => `[${segment.timestamp || '00:00'}] ${segment.speaker}: ${segment.text}`)
    .join('\n');
}

async function resolveTranscriptForChat(sessionId, fallbackText = '') {
  let session = null;
  if (sessionId) {
    session = getSession(sessionId) || await loadSessionPayloadFromDb(sessionId);
  }
  if (!session && activeSession && (!sessionId || activeSession.id === sessionId)) {
    session = activeSession;
  }

  const transcriptText = formatTranscriptForChat(session?.transcript);
  if (transcriptText) return transcriptText;
  return String(fallbackText || '').trim();
}

function buildMeetingNotesContext(session) {
  if (!session) return '';
  const parts = [];
  if (session.mixNotes?.trim()) {
    parts.push(`Mix notes:\n${session.mixNotes.trim()}`);
  }
  if (session.enhancedNotes?.trim()) {
    parts.push(`Enhanced notes:\n${session.enhancedNotes.trim()}`);
  }
  return parts.join('\n\n');
}

ipcMain.handle(IPC.CHAT_QUERY, async (event, payload, legacyTranscriptText) => {
  try {
    const model = rt.settings.selectedLlm;
    const isObjectPayload = payload && typeof payload === 'object';
    const query = isObjectPayload ? payload.query : payload;
    const sessionId = isObjectPayload ? payload.sessionId : null;
    const scope = isObjectPayload ? (payload.scope || 'hub') : 'hub';
    const fallbackTranscriptText = isObjectPayload
      ? (payload.transcriptText || '')
      : (legacyTranscriptText || '');

    const recipePrompt = buildRecipePrompt(query);
    const isRecipe = Boolean(recipePrompt);
    const resolvedQuery = recipePrompt || query;

    const sessionForContext = sessionId
      ? (getSession(sessionId) || await loadSessionPayloadFromDb(sessionId) || activeSession)
      : activeSession;
    const activeTranscriptText = await resolveTranscriptForChat(sessionId, fallbackTranscriptText);
    const notesContext = scope === 'meeting' ? buildMeetingNotesContext(sessionForContext) : '';

    const oneWeekAgo = Date.now() - 7 * 24 * 60 * 60 * 1000;
    // Bound chat context: recent/limited rows only (avoids loading every session into the prompt).
    const rows = await dbAll(
      "SELECT title, date, summary, actionItems, mixNotes, enhancedNotes, mtimeMs FROM sessions ORDER BY mtimeMs DESC LIMIT 40"
    );

    let historyContext = '';
    if (scope === 'meeting' && !isRecipe) {
      historyContext = 'You are answering about the CURRENT MEETING first. Past sessions are secondary context unless the user explicitly asks about history (e.g. "past week", "recent discussions").\n\n';
      const recent = rows.filter((r) => (r.mtimeMs || 0) >= oneWeekAgo).slice(0, 12);
      historyContext += 'Recent sessions (past 7 days):\n';
      recent.forEach((r) => {
        historyContext += `- [${r.date}] ${r.title}\n`;
        if (r.summary) historyContext += `  Summary: ${String(r.summary).slice(0, 280)}\n`;
      });
    } else if (scope !== 'meeting') {
      historyContext = 'Here is the context of past calls (most recent first):\n';
      rows.slice(0, 20).forEach((r) => {
        historyContext += `- [${r.date}] Title: ${r.title}\n`;
        if (r.summary) historyContext += `  Summary: ${String(r.summary).slice(0, 400)}\n`;
        if (r.actionItems) historyContext += `  Action Items: ${String(r.actionItems).slice(0, 300)}\n`;
        historyContext += '\n';
      });
    }

    let systemPrompt = scope === 'meeting'
      ? 'You are an offline AI meeting assistant focused on the active meeting transcript. Use past session context only when the user asks about broader history or trends. Respond in clean Markdown.'
      : 'You are an offline AI meeting assistant with access to session history. Respond in clean Markdown.';

    if (isRecipe) {
      systemPrompt += ' You are executing a structured meeting recipe. Provide a detailed, substantive answer using the transcript and notes. Never reply with only "Okay", "Sure", "Subject", or other one-word acknowledgments.';
    }

    const userPrompt = `${historyContext}${notesContext ? `${notesContext}\n\n` : ''}Current Meeting Transcript (primary):
${activeTranscriptText || 'No active transcript.'}

User Question:
${resolvedQuery}`;

    const requestId = `chat_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`;
    const targetWindow = BrowserWindow.fromWebContents(event.sender);
    void llmService.streamToWindow(targetWindow, requestId, userPrompt, model, systemPrompt).catch((err) => {
      console.error('Chat stream error', err);
    });
    return { requestId };
  } catch (err) {
    console.error('Chat query error', err);
    return {
      requestId: null,
      error: 'Could not query local AI model. Please verify Ollama is running.'
    };
  }
});

ipcMain.handle(IPC.CHAT_GET_RECIPES, async () => {
  return CHAT_RECIPES;
});

ipcMain.handle(IPC.CHAT_MIX_ENHANCE, async (event, payload) => {
  try {
    const transcript = Array.isArray(payload?.transcript) ? payload.transcript : [];
    const editorDocument = documentFromSession({
      mixNotes: payload?.plainText || payload?.jots || '',
      editorDocument: payload?.editorDocument || null
    });

    const templateKey = payload?.template || rt.settings.selectedNoteStyle || 'executive';
    const systemPrompt = templateKey === 'custom'
      ? (rt.settings.notePromptTemplate || DEFAULT_PROMPTS.executive)
      : (DEFAULT_PROMPTS[templateKey] || rt.settings.notePromptTemplate || DEFAULT_PROMPTS.executive);

    const result = await enhanceNotesService.enhanceDocument({
      editorDocument,
      fullTranscript: enhanceNotesService.buildFullTranscript(transcript),
      transcriptSegments: transcript,
      model: rt.settings.selectedLlm,
      systemPrompt
    });

    if (!result) {
      return { success: false, error: 'Add some jots before enhancing notes.' };
    }

    return { success: true, ...result };
  } catch (err) {
    console.error("Mix & Enhance error", err);
    return {
      success: false,
      error: "Failed to run Mix & Enhance. Please ensure Ollama is running and the model is loaded."
    };
  }
});

ipcMain.handle(IPC.LLM_CANCEL, (event, requestId) => {
  return { success: llmService.cancelStream(requestId) };
});

ipcMain.handle(IPC.MODELS_GET_SPECS, () => {
  return getHardwareSpecs();
});

ipcMain.handle(IPC.MODELS_GET_OLLAMA, async () => {
  try {
    const res = await fetch('http://localhost:11434/api/tags');
    if (res.ok) {
      const data = await res.json();
      return data.models || [];
    }
  } catch (e) {
    console.error('Ollama is not running');
  }
  return [];
});

ipcMain.handle(IPC.MODELS_DOWNLOAD_WHISPER, async (event, modelName) => {
  return new Promise((resolve, reject) => {
    // Download Whisper GGUF Model using the download script
    const downloadScript = path.join(WHISPER_DIR, 'models', 'download-ggml-model.sh');
    if (!fs.existsSync(downloadScript)) {
      reject('Whisper models setup script not found.');
      return;
    }
    
    // Spawn script execution
    const proc = spawn('bash', [downloadScript, modelName], { cwd: path.join(WHISPER_DIR, 'models') });
    
    proc.stdout.on('data', (data) => {
      // Send download progress to frontend
      const output = data.toString();
      if (getHubWindow()) getHubWindow().webContents.send(IPC.MODELS_ON_DOWNLOAD_PROGRESS, output);
    });
    
    proc.on('close', (code) => {
      if (code === 0) {
        resolve(true);
      } else {
        reject(`Download script exited with code ${code}`);
      }
    });
  });
});

ipcMain.handle(IPC.CALLS_DECRYPT_MULTIPLE, async (event, sessionIds, password) => {
  const unlocked = [];
  const failed = [];

  for (const sessionId of sessionIds || []) {
    try {
      const session = await dbGet('SELECT * FROM sessions WHERE id = ?', [sessionId]);
      if (!session || session.encrypted !== 1) {
        unlocked.push(sessionId);
        continue;
      }
      const decrypted = encryption.decrypt(session.encrypted_payload, password);
      const sessionData = JSON.parse(decrypted);
      sessionData.folder_id = session.folder_id;
      decryptionKeys.set(sessionData.id, password);
      await saveSessionToDbPromise(sessionData);
      unlocked.push(sessionId);
    } catch (err) {
      failed.push({ sessionId, error: err.message });
    }
  }

  if (getHubWindow()) getHubWindow().webContents.send(IPC.CALLS_LIST_UPDATED);
  return { success: failed.length === 0, unlocked, failed };
});

ipcMain.handle(IPC.CALLS_GET_LIST, async () => {
  try {
    // Full filesystem sync is expensive; do it once per process, then rely on watchers/saves.
    if (!callsListSyncedOnce) {
      await syncDatabaseWithFiles();
      callsListSyncedOnce = true;
    }
    const processingJobs = await sessionProcessingService.getJobMap();
    const rows = await dbAll("SELECT * FROM sessions ORDER BY mtimeMs DESC");
    return rows.map(row => {
      let tags = [];
      let suggestedTags = [];
      try { tags = JSON.parse(row.tags || '[]'); } catch (e) {}
      try { suggestedTags = JSON.parse(row.suggestedTags || '[]'); } catch (e) {}
      
      return {
        id: row.id,
        title: row.title || 'Meeting Session',
        date: row.date,
        encrypted: row.encrypted === 1,
        unlocked: row.encrypted !== 1 || decryptionKeys.has(row.id),
        filePath: row.id,
        storagePath: resolveSessionFilePath(getCallsDir(), row.id, row.folder_id || UNCATEGORIZED_FOLDER_ID),
        summary: row.summary,
        description: row.description || 'No description available.',
        tags: tags,
        suggestedTags: suggestedTags,
        folder_id: row.folder_id,
        mtimeMs: row.mtimeMs,
        processing: processingJobs[row.id] || null
      };
    });
  } catch (err) {
    console.error(err);
    return [];
  }
});

ipcMain.handle(IPC.SEARCH_SESSIONS, async (event, rawTerm, options = {}) => {
  try {
    const term = String(rawTerm || '').trim();
    if (!term) return [];
    const hits = await searchSessionsFts(term, { limit: options.limit || 200 });
    if (!hits.length) return [];

    const processingJobs = await sessionProcessingService.getJobMap();
    const ids = hits.map((hit) => hit.id);
    const placeholders = ids.map(() => '?').join(',');
    const rows = await dbAll(`SELECT * FROM sessions WHERE id IN (${placeholders})`, ids);
    const rowById = new Map(rows.map((row) => [row.id, row]));
    const results = [];
    for (const hit of hits) {
      const row = rowById.get(hit.id);
      if (!row) continue;
      let tags = [];
      let suggestedTags = [];
      try { tags = JSON.parse(row.tags || '[]'); } catch (e) {}
      try { suggestedTags = JSON.parse(row.suggestedTags || '[]'); } catch (e) {}
      results.push({
        id: row.id,
        title: row.title || 'Meeting Session',
        date: row.date,
        encrypted: row.encrypted === 1,
        unlocked: row.encrypted !== 1 || decryptionKeys.has(row.id),
        filePath: row.id,
        storagePath: resolveSessionFilePath(getCallsDir(), row.id, row.folder_id || UNCATEGORIZED_FOLDER_ID),
        summary: row.summary,
        description: row.description || 'No description available.',
        tags,
        suggestedTags,
        folder_id: row.folder_id,
        mtimeMs: row.mtimeMs,
        processing: processingJobs[row.id] || null,
        snippet: hit.snippet || '',
        rank: hit.rank
      });
    }
    return results;
  } catch (err) {
    console.error('search:sessions failed', err);
    return [];
  }
});

ipcMain.handle(IPC.CALLS_DECRYPT, async (event, sessionId, password) => {
  try {
    const session = await dbGet("SELECT * FROM sessions WHERE id = ?", [sessionId]);
    if (!session) return { success: false, error: 'Session not found' };
    
    const decrypted = encryption.decrypt(session.encrypted_payload, password);
    const sessionData = JSON.parse(decrypted);
    sessionData.folder_id = session.folder_id;
    
    decryptionKeys.set(sessionData.id, password);
    try {
      await upsertSessionFts(sessionData);
    } catch (ftsErr) {
      console.error('FTS reindex after decrypt failed', ftsErr);
    }
    return { success: true, session: sessionData };
  } catch (error) {
    return { success: false, error: error.message };
  }
});

ipcMain.handle(IPC.CALLS_LOAD, async (event, sessionId, password) => {
  try {
    const session = await dbGet("SELECT * FROM sessions WHERE id = ?", [sessionId]);
    if (!session) return { success: false, error: 'Session not found' };
    
    if (session.encrypted) {
      const cachedKey = password || decryptionKeys.get(sessionId);
      if (!cachedKey) {
        return { success: false, requirePassword: true };
      }
      
      const decrypted = encryption.decrypt(session.encrypted_payload, cachedKey);
      const sessionData = JSON.parse(decrypted);
      sessionData.folder_id = session.folder_id;
      decryptionKeys.set(sessionData.id, cachedKey);
      return { success: true, session: sessionData };
    } else {
      let sessionData = {};
      try {
        sessionData = JSON.parse(session.encrypted_payload);
      } catch (e) {
        sessionData = {
          id: session.id,
          date: session.date,
          title: session.title,
          description: session.description,
          summary: session.summary,
          actionItems: session.actionItems,
          mixNotes: session.mixNotes,
          enhancedNotes: session.enhancedNotes,
          encrypted: false,
          folder_id: session.folder_id,
          tags: JSON.parse(session.tags || '[]'),
          suggestedTags: JSON.parse(session.suggestedTags || '[]')
        };
      }
      return { success: true, session: sessionData };
    }
  } catch (error) {
    return { success: false, error: error.message };
  }
});

ipcMain.handle(IPC.CALLS_SAVE, async (event, callData, password) => {
  try {
    const encryptionPassword = password || decryptionKeys.get(callData.id);
    if (encryptionPassword) {
      callData.encrypted = true;
      decryptionKeys.set(callData.id, encryptionPassword);
    }

    const result = await saveSessionToDbPromise(callData, { password: encryptionPassword || undefined });
    if (result?.needsPassword) {
      return { success: false, needsPassword: true };
    }
    if (getHubWindow()) getHubWindow().webContents.send(IPC.CALLS_LIST_UPDATED);
    return { success: true };
  } catch (err) {
    console.error(err);
    return { success: false, error: err.message };
  }
});

ipcMain.handle(IPC.CALLS_SAVE_SILENTLY, async (event, callData, password) => {
  try {
    if (password) {
      callData.encrypted = true;
      decryptionKeys.set(callData.id, password);
    }
    if (callData.encrypted && !decryptionKeys.get(callData.id) && !password) {
      return { success: false, needsPassword: true };
    }
    // Debounce high-frequency editor/autosave writes; FTS runs on flush/explicit save.
    setSession(callData);
    scheduleSilentSessionSave(callData);
    return { success: true };
  } catch (err) {
    console.error(err);
    return { success: false, error: err.message };
  }
});

ipcMain.handle(IPC.CALLS_DELETE, async (event, sessionId) => {
  try {
    await dbRun("DELETE FROM sessions WHERE id = ?", [sessionId]);
    await deleteSessionFts(sessionId);
    if (getHubWindow()) getHubWindow().webContents.send(IPC.CALLS_LIST_UPDATED);
    return { success: true };
  } catch (e) {
    return { success: false, error: e.message };
  }
});

ipcMain.handle(IPC.CALLS_DELETE_MULTIPLE, async (event, sessionIds) => {
  try {
    if (sessionIds.length === 0) return { success: true };
    const placeholders = sessionIds.map(() => '?').join(',');
    await dbRun(`DELETE FROM sessions WHERE id IN (${placeholders})`, sessionIds);
    for (const id of sessionIds) {
      await deleteSessionFts(id);
    }
    if (getHubWindow()) getHubWindow().webContents.send(IPC.CALLS_LIST_UPDATED);
    return { success: true };
  } catch (e) {
    return { success: false, error: e.message };
  }
});

ipcMain.handle(IPC.CALLS_MERGE, async (event, sessionIds) => {
  try {
    const sessions = [];
    for (const id of sessionIds) {
      const res = await dbGet("SELECT * FROM sessions WHERE id = ?", [id]);
      if (res) {
        if (res.encrypted) {
          const cachedKey = decryptionKeys.get(id);
          if (cachedKey) {
            const decrypted = encryption.decrypt(res.encrypted_payload, cachedKey);
            sessions.push(JSON.parse(decrypted));
          } else {
            throw new Error(`Cannot merge encrypted session ${id} without cached password.`);
          }
        } else {
          sessions.push(JSON.parse(res.encrypted_payload || '{}'));
        }
      }
    }

    if (sessions.length < 2) {
      return { success: false, error: 'Need at least 2 sessions to merge.' };
    }

    sessions.sort((a, b) => {
      const dateA = new Date(a.date);
      const dateB = new Date(b.date);
      return dateA - dateB;
    });

    const mergedTranscript = [];
    let cumulativeTimeMs = 0;
    
    sessions.forEach((sess, idx) => {
      if (idx > 0) {
        cumulativeTimeMs += 5000;
      }
      const startOffsetMs = cumulativeTimeMs;
      
      if (sess.transcript) {
        sess.transcript.forEach((seg) => {
          mergedTranscript.push({
            ...seg,
            id: `merged_${sess.id}_${seg.id}`,
            timestampMs: startOffsetMs + seg.timestampMs,
            timestamp: formatTimestamp(startOffsetMs + seg.timestampMs)
          });
        });
      }
      
      if (sess.transcript && sess.transcript.length > 0) {
        const lastSeg = sess.transcript[sess.transcript.length - 1];
        cumulativeTimeMs += lastSeg.timestampMs + 3000;
      }
    });

    const titles = sessions.map(s => s.title).join(' & ');
    const newSession = {
      id: 'merged_' + Date.now(),
      date: new Date().toLocaleString(),
      title: `Merged: ${titles}`,
      transcript: mergedTranscript,
      summary: sessions.map(s => `--- ${s.title} ---\n${s.summary || ''}`).join('\n\n'),
      actionItems: sessions.map(s => `--- ${s.title} ---\n${s.actionItems || ''}`).join('\n\n'),
      encrypted: false,
      folder_id: 'work'
    };

    await saveSessionToDbPromise(newSession);
    if (getHubWindow()) getHubWindow().webContents.send(IPC.CALLS_LIST_UPDATED);
    return { success: true, session: newSession };
  } catch (e) {
    return { success: false, error: e.message };
  }
});

ipcMain.handle(IPC.CALLS_EXPORT, async (event, sessionIds) => {
  try {
    let combinedMarkdown = '';
    
    for (const id of sessionIds) {
      const res = await dbGet("SELECT * FROM sessions WHERE id = ?", [id]);
      if (res) {
        let session = null;
        if (res.encrypted) {
          const cachedKey = decryptionKeys.get(id);
          if (cachedKey) {
            const decrypted = encryption.decrypt(res.encrypted_payload, cachedKey);
            session = JSON.parse(decrypted);
          }
        } else {
          session = JSON.parse(res.encrypted_payload || '{}');
        }
        
        if (session) {
          combinedMarkdown += `# ${session.title}\n`;
          combinedMarkdown += `Date: ${session.date}\n\n`;
          combinedMarkdown += `## Transcript\n`;
          if (session.transcript) {
            session.transcript.forEach((t) => {
              combinedMarkdown += `[${t.timestamp}] ${t.speaker}: ${t.text}\n`;
            });
          }
          combinedMarkdown += `\n## Highlights Summary\n${session.summary || 'No summary'}\n\n`;
          combinedMarkdown += `## Action Items\n${session.actionItems || 'No action items'}\n`;
          combinedMarkdown += `\n---\n\n`;
        }
      }
    }

    const { filePath } = await dialog.showSaveDialog(getHubWindow(), {
      title: 'Export Transcripts',
      defaultPath: path.join(app.getPath('downloads'), 'trailmix_export.md'),
      filters: [{ name: 'Markdown/Text Files', extensions: ['md', 'txt'] }]
    });

    if (filePath) {
      fs.writeFileSync(filePath, combinedMarkdown, 'utf8');
      return { success: true, filePath };
    }
    return { success: false, error: 'Export canceled' };
  } catch (e) {
    return { success: false, error: e.message };
  }
});

ipcMain.handle(IPC.CALLS_FIND_RELATED, async (event, sessionId) => {
  try {
    const targetRow = await dbGet("SELECT * FROM sessions WHERE id = ?", [sessionId]);
    if (!targetRow) return [];

    const targetTitle = String(targetRow.title || '').toLowerCase();
    const targetWords = new Set(targetTitle.split(/\s+/).filter((w) => w.length > 4));
    let targetTags = [];
    try { targetTags = JSON.parse(targetRow.tags || '[]'); } catch (e) { targetTags = []; }
    const targetTagSet = new Set(targetTags.map((t) => String(t).toLowerCase()));

    // Prefer metadata scoring to avoid decrypting/parsing every session payload.
    const rows = await dbAll(
      "SELECT id, title, date, summary, tags, mtimeMs, encrypted FROM sessions WHERE id != ? ORDER BY mtimeMs DESC LIMIT 200",
      [sessionId]
    );
    const related = [];

    for (const r of rows) {
      let score = 0;
      const titleWords = String(r.title || '').toLowerCase().split(/\s+/).filter((w) => w.length > 4);
      titleWords.forEach((w) => {
        if (targetWords.has(w)) score += 3;
      });

      let tags = [];
      try { tags = JSON.parse(r.tags || '[]'); } catch (e) { tags = []; }
      tags.forEach((tag) => {
        if (targetTagSet.has(String(tag).toLowerCase())) score += 4;
      });

      const summary = String(r.summary || '').toLowerCase();
      targetWords.forEach((w) => {
        if (summary.includes(w)) score += 1;
      });

      if (score > 0) {
        related.push({
          id: r.id,
          title: r.title,
          date: r.date,
          score,
          filePath: r.id,
          encrypted: r.encrypted === 1
        });
      }
    }

    related.sort((a, b) => b.score - a.score);
    return related.slice(0, 40);
  } catch (e) {
    console.error(e);
    return [];
  }
});

// Obsidian vault exports (v0.2 Compatibility)
ipcMain.handle(IPC.CALLS_EXPORT_OBSIDIAN, async (event, folderId, exportDir) => {
  try {
    if (!fs.existsSync(exportDir)) {
      fs.mkdirSync(exportDir, { recursive: true });
    }
    
    let sql = "SELECT * FROM sessions";
    let params = [];
    if (folderId && folderId !== 'all') {
      sql = "SELECT * FROM sessions WHERE folder_id = ?";
      params = [folderId];
    }
    
    const sessions = await dbAll(sql, params);
    let count = 0;
    
    for (const sessionRow of sessions) {
      let session = null;
      if (sessionRow.encrypted) {
        const cachedKey = decryptionKeys.get(sessionRow.id);
        if (cachedKey) {
          session = JSON.parse(encryption.decrypt(sessionRow.encrypted_payload, cachedKey));
        }
      } else {
        session = JSON.parse(sessionRow.encrypted_payload || '{}');
      }
      
      if (session) {
        const cleanTitle = sessionRow.title.replace(/[^a-zA-Z0-9\s_-]/g, '').trim() || 'Session';
        const mdName = `${cleanTitle}_${sessionRow.id}.md`;
        const mdPath = path.join(exportDir, mdName);
        
        let mdContent = `# ${sessionRow.title}\n`;
        mdContent += `Date: ${sessionRow.date}\n`;
        if (session.tags && session.tags.length > 0) {
          mdContent += `Tags: ${session.tags.map(t => `#${t}`).join(' ')}\n`;
        }
        mdContent += `\n---\n\n`;
        mdContent += `## Jots (Manual Notes)\n${session.mixNotes || session.editorDocument?.plainText || 'No manual jots.'}\n\n`;
        mdContent += `## Blended Notes (AI Enhanced)\n${session.enhancedNotes || 'No enhanced notes.'}\n\n`;
        mdContent += `## Highlights Summary\n${session.summary || 'No summary.'}\n\n`;
        mdContent += `## Action Items\n${session.actionItems || 'No action items.'}\n`;
        
        fs.writeFileSync(mdPath, mdContent, 'utf8');
        count++;
      }
    }
    return { success: true, count };
  } catch (err) {
    return { success: false, error: err.message };
  }
});

ipcMain.handle(IPC.WORKFLOW_REGISTER, async (event, eventName, trigger) => {
  if (!eventName || typeof eventName !== 'string') {
    return { success: false, error: 'Invalid event name' };
  }
  if (!trigger || typeof trigger !== 'object') {
    return { success: false, error: 'Invalid trigger payload' };
  }

  const url = String(trigger.url || trigger.webhookUrl || '');
  if (url) {
    let parsed;
    try {
      parsed = new URL(url);
    } catch (err) {
      return { success: false, error: 'Invalid webhook URL' };
    }
    const allowedHost = parsed.hostname === '127.0.0.1'
      || parsed.hostname === 'localhost'
      || parsed.hostname === '::1';
    if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
      return { success: false, error: 'Webhook URL must be http(s)' };
    }
    if (!allowedHost) {
      return { success: false, error: 'Webhook URL must target localhost' };
    }
  }

  appEventBus.registerWorkflowTrigger(eventName, trigger);
  return { success: true };
});

ipcMain.handle(IPC.TASKS_GET, async () => {
  return await getTasksListFromDb();
});

ipcMain.handle(IPC.TASKS_TOGGLE, async (event, taskId) => {
  try {
    const task = formatTaskRow(await dbGet("SELECT * FROM tasks WHERE id = ?", [taskId]));
    if (task) {
      task.completed = !task.completed;
      await dbRun("UPDATE tasks SET completed = ? WHERE id = ?", [task.completed ? 1 : 0, taskId]);
      return { success: true, task };
    }
    return { success: false, error: 'Task not found' };
  } catch (err) {
    return { success: false, error: err.message };
  }
});

ipcMain.handle(IPC.TASKS_DELETE, async (event, taskId) => {
  try {
    await dbRun("DELETE FROM tasks WHERE id = ?", [taskId]);
    return { success: true };
  } catch (err) {
    return { success: false, error: err.message };
  }
});

ipcMain.handle(IPC.TASKS_SET_OMITTED, async (event, taskId, omitted) => {
  try {
    const omittedVal = omitted ? 1 : 0;
    await dbRun("UPDATE tasks SET omitted = ? WHERE id = ?", [omittedVal, taskId]);
    const task = formatTaskRow(await dbGet("SELECT * FROM tasks WHERE id = ?", [taskId]));
    if (task) {
      return { success: true, task };
    }
    return { success: false, error: 'Task not found' };
  } catch (err) {
    return { success: false, error: err.message };
  }
});

ipcMain.handle(IPC.TASKS_DELETE_MULTIPLE, async (event, taskIds) => {
  try {
    if (taskIds.length === 0) return { success: true };
    const placeholders = taskIds.map(() => '?').join(',');
    await dbRun(`DELETE FROM tasks WHERE id IN (${placeholders})`, taskIds);
    return { success: true };
  } catch (err) {
    return { success: false, error: err.message };
  }
});

ipcMain.handle(IPC.CALLS_OPEN_FILE_LOCATION, () => {
  shell.openPath(XDG_CONFIG_DIR);
  return { success: true };
});

ipcMain.on(IPC.APP_MINIMIZE, () => {
  if (getHubWindow()) getHubWindow().minimize();
});

ipcMain.on(IPC.APP_RELAUNCH, () => {
  if (getHubWindow()) {
    getHubWindow().restore();
    getHubWindow().focus();
  }
  if (windowManager && activeRecordingSessionId) {
    windowManager.restoreMeetingFromMini(activeRecordingSessionId);
  }
});

ipcMain.handle(IPC.APP_GET_VERSION, () => app.getVersion());

ipcMain.handle(IPC.UPDATES_CHECK, async (event, options = {}) => {
  return updateService.checkForUpdates(options);
});

ipcMain.handle(IPC.UPDATES_INSTALL, async () => {
  updateService.quitAndInstall();
  return { success: true };
});

ipcMain.handle(IPC.UPDATES_GET_STATUS, () => {
  return updateService.getStatus(app.getVersion());
});
}

module.exports = { registerIpcHandlers };
