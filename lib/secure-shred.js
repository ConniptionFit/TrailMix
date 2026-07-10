const fs = require('fs');
const crypto = require('crypto');
const path = require('path');

const CHUNK_SIZE = 1024 * 1024; // 1MB

async function secureShredFile(filePath) {
  try {
    await fs.promises.access(filePath);
    const stats = await fs.promises.stat(filePath);

    if (!stats.isFile()) return;

    // Chunked overwrite avoids allocating randomBytes(fileSize) which can OOM on large WAVs.
    if (stats.size > 0) {
      const handle = await fs.promises.open(filePath, 'r+');
      try {
        let remaining = stats.size;
        let offset = 0;
        while (remaining > 0) {
          const length = Math.min(CHUNK_SIZE, remaining);
          const randomData = crypto.randomBytes(length);
          await handle.write(randomData, 0, length, offset);
          offset += length;
          remaining -= length;
        }
        await handle.sync();
      } finally {
        await handle.close();
      }
    }

    await fs.promises.unlink(filePath);
    console.log(`Secured shredded file: ${path.basename(filePath)}`);
  } catch (err) {
    console.error(`Shred failed for ${filePath}, attempting direct deletion`, err);
    try {
      await fs.promises.unlink(filePath);
    } catch (unlinkErr) {
      // File may already be gone.
    }
  }
}

module.exports = {
  secureShredFile
};
