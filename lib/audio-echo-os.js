const { execSync } = require('child_process');

let loadedEchoCancelModuleId = null;

function listShortModules() {
  try {
    return execSync('pactl list short modules 2>/dev/null', { encoding: 'utf8' });
  } catch (err) {
    return '';
  }
}

function unloadTrackedModule() {
  if (!loadedEchoCancelModuleId) return;
  try {
    execSync(`pactl unload-module ${loadedEchoCancelModuleId}`);
    console.log(`Unloaded echo-cancel module #${loadedEchoCancelModuleId}`);
  } catch (err) {
    // Already unloaded.
  }
  loadedEchoCancelModuleId = null;
}

/**
 * Enable OS-level echo cancellation via PulseAudio/PipeWire-Pulse module-echo-cancel.
 * Returns the virtual source name to use for capture, or null on failure.
 */
function enableOsEchoCancellation({ micSource, sinkName }) {
  unloadTrackedModule();

  if (!micSource || !sinkName) {
    return { ok: false, error: 'Missing mic or sink device' };
  }

  const cleanSink = sinkName.replace(/\.monitor$/, '');

  try {
    const output = execSync(
      `pactl load-module module-echo-cancel ` +
      `source_master=${micSource} sink_master=${cleanSink} ` +
      `aec_method=webrtc use_master_format=1 latency_msec=20 2>/dev/null`,
      { encoding: 'utf8' }
    ).trim();

    const moduleId = parseInt(output, 10);
    if (!Number.isFinite(moduleId)) {
      return { ok: false, error: 'Could not load module-echo-cancel' };
    }

    loadedEchoCancelModuleId = moduleId;

    const sources = execSync('pactl list short sources 2>/dev/null', { encoding: 'utf8' });
    const echoSourceLine = sources
      .split('\n')
      .find((line) => /echo-cancel/i.test(line) && line.includes(micSource.split('.')[0]));

    const virtualSource = echoSourceLine
      ? echoSourceLine.split('\t')[1]
      : null;

    return {
      ok: true,
      moduleId,
      virtualSource,
      message: 'OS echo cancellation enabled (webrtc)'
    };
  } catch (err) {
    return { ok: false, error: err.message };
  }
}

function disableOsEchoCancellation() {
  unloadTrackedModule();
  return { ok: true };
}

module.exports = {
  enableOsEchoCancellation,
  disableOsEchoCancellation,
  unloadTrackedModule
};
