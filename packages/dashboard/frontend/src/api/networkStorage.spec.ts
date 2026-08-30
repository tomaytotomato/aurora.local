import { describe, it, expect } from 'vitest';
import { protocolLabel } from './disks';

/**
 * The catalogue of protocol names is the one place this feature can leak
 * plumbing at a household user: "_smb._tcp" means nothing to anyone who
 * has not read an RFC, and "SMB" is barely better.
 */
describe('protocolLabel', () => {
  it('says what the protocol means to a person, not what it is called', () => {
    expect(protocolLabel('smb')).toBe('Windows / Mac file sharing');
    expect(protocolLabel('time-machine')).toBe('Time Machine backups');
    expect(protocolLabel('afp')).toContain('older');
  });

  it('passes an unknown protocol through rather than hiding the device', () => {
    // A device offering something we have no label for is still a device
    // the owner can see on their network; dropping it would be worse.
    expect(protocolLabel('webdav')).toBe('webdav');
  });
});
