/*
 * Minimal ZIP reader/writer.
 *
 * Written by hand rather than pulled from npm so the manager stays a folder of
 * files you can open with no install step and no third-party code to audit.
 *
 * Photos are already JPEG, which does not compress further, so archives are
 * written STORED (method 0). Reading also handles DEFLATE (method 8) so an
 * .eelens file produced by any other tool still opens.
 */

const CRC_TABLE = (() => {
  const table = new Uint32Array(256);
  for (let i = 0; i < 256; i++) {
    let c = i;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    table[i] = c >>> 0;
  }
  return table;
})();

function crc32(bytes) {
  let c = 0xffffffff;
  for (let i = 0; i < bytes.length; i++) c = CRC_TABLE[(c ^ bytes[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

const utf8 = new TextEncoder();

function dosTime(date) {
  const time = ((date.getHours() & 31) << 11) | ((date.getMinutes() & 63) << 5) | ((date.getSeconds() / 2) & 31);
  const day = (((date.getFullYear() - 1980) & 127) << 9) | (((date.getMonth() + 1) & 15) << 5) | (date.getDate() & 31);
  return { time, day };
}

/**
 * @param {{name: string, data: Uint8Array}[]} files
 * @returns {Blob} a STORED zip archive
 */
function zipStore(files) {
  const { time, day } = dosTime(new Date());
  const chunks = [];
  const central = [];
  let offset = 0;

  for (const file of files) {
    const nameBytes = utf8.encode(file.name);
    const crc = crc32(file.data);
    const size = file.data.length;

    const local = new DataView(new ArrayBuffer(30));
    local.setUint32(0, 0x04034b50, true);
    local.setUint16(4, 20, true); // version needed
    local.setUint16(6, 0x0800, true); // UTF-8 names
    local.setUint16(8, 0, true); // stored
    local.setUint16(10, time, true);
    local.setUint16(12, day, true);
    local.setUint32(14, crc, true);
    local.setUint32(18, size, true);
    local.setUint32(22, size, true);
    local.setUint16(26, nameBytes.length, true);
    local.setUint16(28, 0, true);

    chunks.push(new Uint8Array(local.buffer), nameBytes, file.data);

    const dir = new DataView(new ArrayBuffer(46));
    dir.setUint32(0, 0x02014b50, true);
    dir.setUint16(4, 20, true);
    dir.setUint16(6, 20, true);
    dir.setUint16(8, 0x0800, true);
    dir.setUint16(10, 0, true);
    dir.setUint16(12, time, true);
    dir.setUint16(14, day, true);
    dir.setUint32(16, crc, true);
    dir.setUint32(20, size, true);
    dir.setUint32(24, size, true);
    dir.setUint16(28, nameBytes.length, true);
    dir.setUint32(42, offset, true);
    central.push(new Uint8Array(dir.buffer), nameBytes);

    offset += 30 + nameBytes.length + size;
  }

  let centralSize = 0;
  for (const part of central) centralSize += part.length;

  const end = new DataView(new ArrayBuffer(22));
  end.setUint32(0, 0x06054b50, true);
  end.setUint16(8, files.length, true);
  end.setUint16(10, files.length, true);
  end.setUint32(12, centralSize, true);
  end.setUint32(16, offset, true);

  return new Blob([...chunks, ...central, new Uint8Array(end.buffer)], {
    type: 'application/zip'
  });
}

/**
 * @param {ArrayBuffer} buffer
 * @returns {Promise<Map<string, Uint8Array>>} entry name -> bytes
 */
async function unzip(buffer) {
  const bytes = new Uint8Array(buffer);
  const view = new DataView(buffer);

  // The end-of-central-directory record lives in the last 64 KB.
  let end = -1;
  const from = Math.max(0, bytes.length - 66560);
  for (let i = bytes.length - 22; i >= from; i--) {
    if (view.getUint32(i, true) === 0x06054b50) { end = i; break; }
  }
  if (end < 0) throw new Error('This file is not a valid .eelens package.');

  const count = view.getUint16(end + 10, true);
  let pointer = view.getUint32(end + 16, true);
  const out = new Map();

  for (let i = 0; i < count; i++) {
    if (view.getUint32(pointer, true) !== 0x02014b50) {
      throw new Error('This package is damaged: its index does not line up.');
    }
    const method = view.getUint16(pointer + 10, true);
    const compressed = view.getUint32(pointer + 20, true);
    const nameLen = view.getUint16(pointer + 28, true);
    const extraLen = view.getUint16(pointer + 30, true);
    const commentLen = view.getUint16(pointer + 32, true);
    const localAt = view.getUint32(pointer + 42, true);
    const name = new TextDecoder().decode(bytes.subarray(pointer + 46, pointer + 46 + nameLen));

    const localNameLen = view.getUint16(localAt + 26, true);
    const localExtraLen = view.getUint16(localAt + 28, true);
    const dataAt = localAt + 30 + localNameLen + localExtraLen;
    const raw = bytes.subarray(dataAt, dataAt + compressed);

    if (method === 0) {
      out.set(name, raw);
    } else if (method === 8) {
      if (typeof DecompressionStream === 'undefined') {
        throw new Error('This package is compressed and this browser cannot expand it.');
      }
      const stream = new Blob([raw]).stream().pipeThrough(new DecompressionStream('deflate-raw'));
      out.set(name, new Uint8Array(await new Response(stream).arrayBuffer()));
    } else {
      throw new Error(`Unsupported compression in "${name}".`);
    }

    pointer += 46 + nameLen + extraLen + commentLen;
  }
  return out;
}
