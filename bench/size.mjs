// Size-only reference for the same program, not a BLE throughput benchmark.
import fs from 'node:fs';
const vectors = JSON.parse(fs.readFileSync(new URL('../miniapp/vm/vectors.json', import.meta.url), 'utf8'));
for (const vector of vectors) {
  const binary = Buffer.from(vector.hex, 'hex');
  const json = Buffer.from(JSON.stringify({ version: 1, instructions: vector.instructions }), 'utf8');
  console.log(vector.name + ': B7VM=' + binary.length + 'B JSON=' + json.length + 'B');
}
console.log('Size-only reference: not FlatBuffers/Protobuf/CBOR/MessagePack, and not BLE throughput.');
