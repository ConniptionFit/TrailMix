const crypto = require('crypto');

/**
 * Derives a key from a password and salt using PBKDF2.
 * @param {string} password
 * @param {Buffer} salt
 * @returns {Buffer} 32-byte key
 */
function deriveKey(password, salt) {
  return crypto.pbkdf2Sync(password, salt, 100000, 32, 'sha256');
}

const keyCache = new Map();

function cacheKeyId(password, saltHex) {
  return `${password.length}:${saltHex}`;
}

function getCachedKey(password, salt) {
  const saltHex = Buffer.isBuffer(salt) ? salt.toString('hex') : String(salt);
  const id = cacheKeyId(password, saltHex);
  let key = keyCache.get(id);
  if (!key) {
    key = deriveKey(password, Buffer.isBuffer(salt) ? salt : Buffer.from(saltHex, 'hex'));
    keyCache.set(id, key);
  }
  return key;
}

/**
 * Encrypts cleartext using AES-256-GCM.
 * Reuses salt/key when `reuseEnvelope` from a prior encrypt of the same session is provided,
 * avoiding a fresh 100k-iteration PBKDF2 on every autosave.
 * @param {string} text
 * @param {string} password
 * @param {{ salt?: string } | null} reuseEnvelope
 * @returns {string} JSON string containing salt, iv, tag, and ciphertext
 */
function encrypt(text, password, reuseEnvelope = null) {
  const salt = reuseEnvelope?.salt
    ? Buffer.from(reuseEnvelope.salt, 'hex')
    : crypto.randomBytes(16);
  const iv = crypto.randomBytes(12);
  const key = getCachedKey(password, salt);
  const cipher = crypto.createCipheriv('aes-256-gcm', key, iv);

  let encrypted = cipher.update(text, 'utf8', 'hex');
  encrypted += cipher.final('hex');
  const tag = cipher.getAuthTag().toString('hex');

  return JSON.stringify({
    salt: salt.toString('hex'),
    iv: iv.toString('hex'),
    tag: tag.toString('hex'),
    encrypted: encrypted
  });
}

/**
 * Decrypts ciphertext using AES-256-GCM.
 * @param {string} encryptedJson
 * @param {string} password
 * @returns {string} Cleartext
 */
function decrypt(encryptedJson, password) {
  try {
    const { salt, iv, tag, encrypted } = JSON.parse(encryptedJson);
    const key = getCachedKey(password, salt);
    const decipher = crypto.createDecipheriv('aes-256-gcm', key, Buffer.from(iv, 'hex'));
    decipher.setAuthTag(Buffer.from(tag, 'hex'));

    let decrypted = decipher.update(encrypted, 'hex', 'utf8');
    decrypted += decipher.final('utf8');
    return decrypted;
  } catch (error) {
    throw new Error('Decryption failed: Invalid password or corrupted file.');
  }
}

function clearKeyCache() {
  keyCache.clear();
}

module.exports = {
  encrypt,
  decrypt,
  clearKeyCache
};
