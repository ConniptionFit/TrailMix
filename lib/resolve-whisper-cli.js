const fs = require('fs');
const path = require('path');

function resolveWhisperCli(whisperDir) {
  const candidates = [
    path.join(whisperDir, 'build', 'bin', 'whisper-cli'),
    path.join(whisperDir, 'build', 'bin', 'main'),
    path.join(whisperDir, 'main'),
    path.join(whisperDir, 'build', 'bin', 'whisper')
  ];

  for (const candidate of candidates) {
    if (fs.existsSync(candidate)) {
      return candidate;
    }
  }

  return candidates[0];
}

module.exports = {
  resolveWhisperCli
};
