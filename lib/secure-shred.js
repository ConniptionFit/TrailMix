const fs = require('fs');
const crypto = require('crypto');
const path = require('path');

async function secureShredFile(filePath) {
  try {
    await fs.promises.access(filePath);
    const stats = await fs.promises.stat(filePath);

    if (stats.isFile()) {
      const randomData = crypto.randomBytes(stats.size);
      await fs.promises.writeFile(filePath, randomData);
      await fs.promises.unlink(filePath);
      console.log(`Secured shredded file: ${path.basename(filePath)}`);
    }
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
