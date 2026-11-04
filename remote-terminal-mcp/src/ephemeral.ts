import { generateKeyPairSync, randomBytes } from 'node:crypto';

export type EphemeralKey = {
  /** OpenSSH unencrypted private key, only ever held in this process's memory. */
  privateKey: Buffer;
  /** authorized_keys style single line, sent to the cloud to be certified. */
  publicKeyLine: string;
};

function sshString(value: Buffer): Buffer {
  const length = Buffer.alloc(4);
  length.writeUInt32BE(value.length);
  return Buffer.concat([length, value]);
}

/**
 * ssh2 cannot read PKCS8 Ed25519 keys, so the key is serialised in the
 * openssh-key-v1 format (PROTOCOL.key) with no cipher and no passphrase.
 */
export function generateEphemeralKey(comment = 'remote-terminal-mcp'): EphemeralKey {
  const { publicKey, privateKey } = generateKeyPairSync('ed25519');
  const seed = privateKey.export({ type: 'pkcs8', format: 'der' }).subarray(-32);
  const raw = publicKey.export({ type: 'spki', format: 'der' }).subarray(-32);
  const publicBlob = Buffer.concat([sshString(Buffer.from('ssh-ed25519')), sshString(raw)]);
  const check = randomBytes(4);
  const body = Buffer.concat([
    check, check,
    sshString(Buffer.from('ssh-ed25519')), sshString(raw), sshString(Buffer.concat([seed, raw])),
    sshString(Buffer.from(comment)),
  ]);
  const padding = Buffer.from(Array.from({ length: (8 - (body.length % 8)) % 8 }, (_unused, index) => index + 1));
  const blob = Buffer.concat([
    Buffer.from('openssh-key-v1\0'),
    sshString(Buffer.from('none')), sshString(Buffer.from('none')), sshString(Buffer.alloc(0)),
    Buffer.from([0, 0, 0, 1]), sshString(publicBlob),
    sshString(Buffer.concat([body, padding])),
  ]);
  const lines = blob.toString('base64').match(/.{1,70}/g) ?? [];
  const label = 'OPENSSH PRIVATE KEY';
  const pem = ['-----BEGIN ' + label + '-----', ...lines, '-----END ' + label + '-----', ''].join('\n');
  return { privateKey: Buffer.from(pem), publicKeyLine: `ssh-ed25519 ${publicBlob.toString('base64')} ${comment}` };
}
