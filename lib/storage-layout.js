const fs = require('fs');
const path = require('path');

const ROOT_FOLDER_ID = 'all';
const UNCATEGORIZED_FOLDER_ID = 'fs:';

function isTrailFile(name) {
  return name.endsWith('.trail') || name.endsWith('.trail.bak');
}

function sessionIdFromFilename(filename) {
  return filename.replace(/\.trail\.bak$/, '').replace(/\.trail$/, '');
}

function folderIdFromRelativePath(relativePath) {
  if (!relativePath) return UNCATEGORIZED_FOLDER_ID;
  return `fs:${relativePath}`;
}

function relativePathFromFolderId(folderId) {
  if (!folderId || folderId === ROOT_FOLDER_ID || folderId === UNCATEGORIZED_FOLDER_ID) {
    return '';
  }
  if (folderId.startsWith('fs:')) {
    return folderId.slice(3);
  }
  return folderId;
}

function resolveSessionFilePath(callsDir, sessionId, folderId = UNCATEGORIZED_FOLDER_ID) {
  const relative = relativePathFromFolderId(folderId);
  const dir = relative ? path.join(callsDir, relative) : callsDir;
  return path.join(dir, `${sessionId}.trail.bak`);
}

function listTrailFilesInDir(dir, relativeFolder = '') {
  const results = [];
  if (!fs.existsSync(dir)) return results;

  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (!entry.isFile() || !isTrailFile(entry.name)) continue;
    const sessionId = sessionIdFromFilename(entry.name);
    results.push({
      sessionId,
      filePath: path.join(dir, entry.name),
      folderId: folderIdFromRelativePath(relativeFolder),
      relativeFolder
    });
  }
  return results;
}

function scanStorageLayout(callsDir) {
  const folders = [
    {
      id: ROOT_FOLDER_ID,
      name: 'All notes',
      icon: '📂',
      description: 'Every note in your Trail',
      fsPath: null
    },
    {
      id: UNCATEGORIZED_FOLDER_ID,
      name: 'Trail (root)',
      icon: '🥣',
      description: 'Notes stored in the main save folder',
      fsPath: callsDir
    }
  ];

  const trailFiles = listTrailFilesInDir(callsDir, '');

  if (!fs.existsSync(callsDir)) {
    return { folders, trailFiles };
  }

  for (const entry of fs.readdirSync(callsDir, { withFileTypes: true })) {
    if (!entry.isDirectory()) continue;
    if (entry.name.startsWith('.')) continue;

    const fsPath = path.join(callsDir, entry.name);
    folders.push({
      id: folderIdFromRelativePath(entry.name),
      name: entry.name,
      icon: '📁',
      description: `Folder in ${callsDir}`,
      fsPath
    });
    trailFiles.push(...listTrailFilesInDir(fsPath, entry.name));
  }

  return { folders, trailFiles };
}

function ensureFolderDir(callsDir, folderName) {
  const safeName = folderName.trim().replace(/[\\/]/g, '_');
  if (!safeName) throw new Error('Folder name is required');
  const target = path.join(callsDir, safeName);
  if (!fs.existsSync(target)) {
    fs.mkdirSync(target, { recursive: true });
  }
  return {
    id: folderIdFromRelativePath(safeName),
    name: safeName,
    icon: '📁',
    description: '',
    fsPath: target
  };
}

function moveSessionFile(callsDir, sessionId, fromFolderId, toFolderId) {
  const fromPath = resolveSessionFilePath(callsDir, sessionId, fromFolderId);
  const toDir = relativePathFromFolderId(toFolderId)
    ? path.join(callsDir, relativePathFromFolderId(toFolderId))
    : callsDir;
  if (!fs.existsSync(toDir)) fs.mkdirSync(toDir, { recursive: true });
  const toPath = path.join(toDir, `${sessionId}.trail.bak`);

  if (!fs.existsSync(fromPath)) {
    return { success: false, error: 'Source file not found' };
  }
  if (fromPath === toPath) {
    return { success: true, filePath: toPath };
  }
  fs.renameSync(fromPath, toPath);
  return { success: true, filePath: toPath };
}

function moveStorageContents(srcDir, destDir) {
  if (!fs.existsSync(srcDir)) return { moved: 0 };
  if (!fs.existsSync(destDir)) fs.mkdirSync(destDir, { recursive: true });

  let moved = 0;
  for (const entry of fs.readdirSync(srcDir, { withFileTypes: true })) {
    const src = path.join(srcDir, entry.name);
    const dest = path.join(destDir, entry.name);
    if (fs.existsSync(dest)) continue;
    fs.renameSync(src, dest);
    moved += 1;
  }
  return { moved };
}

module.exports = {
  ROOT_FOLDER_ID,
  UNCATEGORIZED_FOLDER_ID,
  isTrailFile,
  sessionIdFromFilename,
  folderIdFromRelativePath,
  relativePathFromFolderId,
  resolveSessionFilePath,
  scanStorageLayout,
  ensureFolderDir,
  moveSessionFile,
  moveStorageContents,
  listTrailFilesInDir
};
