const { contextBridge, ipcRenderer } = require('electron');

const noopUnsub = () => {};

const DEMO_CALLS = [
  {
    id: 'demo-standup-2026',
    filePath: '/demo/weekly-standup.trail',
    title: 'Weekly Standup — Product Sync',
    description: 'Sprint planning, API milestones, and launch timeline review.',
    date: '2026-06-14T09:00:00.000Z',
    tags: ['standup', 'product'],
    encrypted: false
  },
  {
    id: 'demo-interview-2026',
    filePath: '/demo/user-research.trail',
    title: 'User Research Interview',
    description: 'Feedback on onboarding flow and offline-first expectations.',
    date: '2026-06-13T14:30:00.000Z',
    tags: ['research', 'ux'],
    encrypted: false
  },
  {
    id: 'demo-1on1-2026',
    filePath: '/demo/1on1.trail',
    title: '1:1 with Alex',
    description: 'Career growth, project ownership, and Q3 goals.',
    date: '2026-06-12T11:00:00.000Z',
    tags: ['1on1'],
    encrypted: true
  }
];

const DEMO_TASKS = [
  {
    id: 'task-1',
    text: '[You] - Send revised API spec to the team by Friday',
    completed: false,
    omitted: false,
    sessionTitle: 'Weekly Standup — Product Sync',
    deadline: '2026-06-16'
  },
  {
    id: 'task-2',
    text: '[Alex] - Schedule follow-up user interviews',
    completed: true,
    omitted: false,
    sessionTitle: 'User Research Interview',
    deadline: null
  },
  {
    id: 'task-3',
    text: '[Unassigned] - Update README with install screenshots',
    completed: false,
    omitted: false,
    sessionTitle: 'Weekly Standup — Product Sync',
    deadline: '2026-06-14'
  }
];

const DEMO_SESSION = {
  id: 'demo-live',
  title: 'Weekly Standup — Product Sync',
  description: 'Sprint planning and launch timeline',
  tags: ['standup', 'product'],
  summary: 'The team reviewed sprint progress, discussed API blockers, and aligned on a June launch window. Action items were assigned for documentation and user testing.',
  actionItems: '- [You] - Send revised API spec by Friday\n- [Alex] - Schedule follow-up interviews',
  transcript: [
    { id: 's1', timestamp: '00:12', timestampMs: 12000, speaker: 'Alex', text: 'Good morning everyone. Let us start with sprint updates.' },
    { id: 's2', timestamp: '00:28', timestampMs: 28000, speaker: 'You', text: 'API integration is on track. I will share the revised spec today.' },
    { id: 's3', timestamp: '00:45', timestampMs: 45000, speaker: 'Jordan', text: 'Design mocks for the onboarding flow are ready for review.' },
    { id: 's4', timestamp: '01:02', timestampMs: 62000, speaker: 'Alex', text: 'Great. Let us target a soft launch in the third week of June.' }
  ],
  editorDocument: {
    version: 1,
    plainJots: 'Need to follow up on API spec\nLaunch window: 3rd week of June',
    spans: [
      { origin: 'user', text: 'Need to follow up on API spec' },
      { origin: 'ai', text: ' — Alex asked for the revised API documentation during sprint review.' },
      { origin: 'user', text: 'Launch window: 3rd week of June' },
      { origin: 'ai', text: ' — The team aligned on a soft launch timeline after discussing onboarding readiness.' }
    ],
    enhancedAt: '2026-06-14T09:32:00.000Z'
  }
};

function invokeHandler(channel, ...args) {
  switch (channel) {
    case 'audio:get-devices':
      return Promise.resolve({
        sources: [{ name: 'default', description: 'Built-in Microphone' }],
        sinks: [{ name: 'default', description: 'Monitor of Built-in Audio Analog Stereo' }],
        sourceDesc: 'Built-in Microphone',
        sinkDesc: 'Monitor of Built-in Audio'
      });
    case 'settings:get':
      return Promise.resolve({
        selectedModel: 'ggml-base.bin',
        selectedLlm: 'gemma3:1b',
        encryptByDefault: false,
        encryptionPassword: '',
        selectedMic: 'default',
        selectedSink: 'default',
        colorCodeDeadlines: true,
        userName: 'You',
        enableNoiseCancellation: true,
        customStoragePath: '',
        selectedNoteStyle: 'executive',
        notePromptTemplate: 'Executive summary template',
        summaryPromptTemplate: 'Summary template',
        actionPromptTemplate: 'Action items template'
      });
    case 'settings:get-default-prompts':
      return Promise.resolve({
        executive: 'Executive prompt',
        summary: 'Summary prompt',
        actionItems: 'Action items prompt'
      });
    case 'settings:select-directory':
      return Promise.resolve('/home/user/Documents/TrailMix');
    case 'settings:save':
      return Promise.resolve({ success: true });
    case 'models:get-specs':
      return Promise.resolve({
        cpu: 'Intel Core i7-12700H (14 cores)',
        ram: '16 GB',
        gpu: 'Integrated Intel Iris Xe',
        recommendation: 'ggml-base.bin + llama3.2:3b'
      });
    case 'models:get-ollama-models':
      return Promise.resolve(['gemma3:1b', 'llama3.2:3b', 'mistral:7b']);
    case 'calls:get-list':
      return Promise.resolve(DEMO_CALLS);
    case 'calls:load':
      return Promise.resolve({ success: true, data: DEMO_SESSION });
    case 'calls:save':
    case 'calls:save-silently':
      return Promise.resolve({ success: true });
    case 'folders:get':
      return Promise.resolve([
        { id: 'folder-work', name: 'Work Meetings' },
        { id: 'folder-research', name: 'Research' }
      ]);
    case 'tasks:get':
      return Promise.resolve(DEMO_TASKS);
    case 'tasks:toggle':
    case 'tasks:delete':
    case 'tasks:set-omitted':
    case 'tasks:delete-multiple':
      return Promise.resolve({ success: true });
    case 'audio:start-recording':
      return Promise.resolve('demo-live');
    case 'audio:stop-recording':
    case 'audio:pause-recording':
    case 'audio:resume-recording':
      return Promise.resolve({ success: true });
    default:
      return Promise.resolve({ success: true });
  }
}

contextBridge.exposeInMainWorld('api', {
  minimize: noopUnsub,
  relaunch: noopUnsub,
  getAudioDevices: () => invokeHandler('audio:get-devices'),
  startRecording: () => invokeHandler('audio:start-recording'),
  stopRecording: () => invokeHandler('audio:stop-recording'),
  pauseRecording: () => invokeHandler('audio:pause-recording'),
  resumeRecording: () => invokeHandler('audio:resume-recording'),
  onTranscriptionUpdate: () => noopUnsub,
  onRecordingStatus: () => noopUnsub,
  onSpeakerLabelsUpdated: () => noopUnsub,
  onCallListUpdated: () => noopUnsub,
  getSpecs: () => invokeHandler('models:get-specs'),
  getOllamaModels: () => invokeHandler('models:get-ollama-models'),
  downloadWhisper: () => Promise.resolve({ success: true }),
  onDownloadProgress: () => noopUnsub,
  getCallList: () => invokeHandler('calls:get-list'),
  saveCall: () => invokeHandler('calls:save'),
  loadCall: () => invokeHandler('calls:load'),
  decryptCall: () => invokeHandler('calls:load'),
  getSettings: () => invokeHandler('settings:get'),
  saveSettings: () => invokeHandler('settings:save'),
  selectDirectory: () => invokeHandler('settings:select-directory'),
  getDefaultPrompts: () => invokeHandler('settings:get-default-prompts'),
  resumeCallTranscription: () => Promise.resolve({ success: true }),
  getTasks: () => invokeHandler('tasks:get'),
  toggleTask: () => invokeHandler('tasks:toggle'),
  deleteTask: () => invokeHandler('tasks:delete'),
  setTaskOmitted: () => invokeHandler('tasks:set-omitted'),
  deleteMultipleTasks: () => invokeHandler('tasks:delete-multiple'),
  openFileLocation: noopUnsub,
  deleteCall: () => Promise.resolve({ success: true }),
  deleteMultipleCalls: () => Promise.resolve({ success: true }),
  mergeCalls: () => Promise.resolve({ success: true }),
  exportCalls: () => Promise.resolve({ success: true }),
  findRelatedCalls: () => Promise.resolve([]),
  onSessionSummaryReady: () => noopUnsub,
  chatQuery: () => Promise.resolve({ response: 'Based on the transcript, the team agreed on a June launch window and assigned follow-up tasks for the API spec and user interviews.' }),
  saveCallSilently: () => invokeHandler('calls:save-silently'),
  mixEnhance: () => Promise.resolve({ success: true, document: DEMO_SESSION.editorDocument }),
  onLlmStreamChunk: () => noopUnsub,
  cancelLlmStream: () => Promise.resolve({ success: true }),
  getFolders: () => invokeHandler('folders:get'),
  createFolder: () => Promise.resolve({ success: true }),
  deleteFolder: () => Promise.resolve({ success: true }),
  moveToFolder: () => Promise.resolve({ success: true }),
  exportObsidian: () => Promise.resolve({ success: true, path: '/home/user/Obsidian/TrailMix' })
});
