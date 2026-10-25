import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
const source = fs.readFileSync(new URL('../vm/runner.js', import.meta.url), 'utf8');
const vectors = JSON.parse(fs.readFileSync(new URL('../vm/vectors.json', import.meta.url), 'utf8'));
const sandbox = vm.createContext({ Math });
vm.runInContext(source, sandbox);
const run = (bytes) => sandbox.band7VmRun(bytes);
const decode = (hex) => [...Buffer.from(hex, 'hex')];
function pack(ops) {
  const bytes = [66, 55, 86, 77, 1, 0, ops.length & 255, ops.length >> 8];
  for (const [op, arg = 0] of ops) {
    bytes.push(op, arg & 255, (arg >> 8) & 255, (arg >> 16) & 255, (arg >> 24) & 255);
  }
  return bytes;
}
test('golden programs execute deterministically without device APIs', () => {
  for (const vector of vectors) {
    const result = run(decode(vector.hex));
    assert.equal(result.status, 'ok', vector.name);
    assert.equal(result.value, vector.value, vector.name);
    assert.equal(result.steps, vector.steps, vector.name);
  }
});
test('program framing and integrity reject malformed byte streams', () => {
  const valid = decode(vectors[0].hex);
  for (const bad of [[], [0], valid.slice(0, -1), [...valid, 0],
    valid.map((n, i) => i === 0 ? 0 : n),
    valid.map((n, i) => i === 4 ? 2 : n),
    valid.map((n, i) => i === 5 ? 1 : n),
    valid.map((n, i) => i === 6 ? 0 : n),
    valid.map((n, i) => i === 8 ? 255 : n),
    valid.map((n, i) => i === 19 ? 1 : n),
    valid.map((n, i) => i === 9 ? 300 : n)]) {
    assert.equal(run(bad).status, 'error');
  }
});
test('validation enforces stack and program budgets', () => {
  const cases = [
    pack([[2], [0]]),
    pack([[1, 1], [6], [0]]),
    pack([[1, 1], [0], [1, 2]]),
    pack([[1, 1], [5], [0]]),
    pack([...Array.from({ length: 17 }, () => [1, 1]), [0]]),
    pack([...Array.from({ length: 32 }, () => [1, 1]), [0]]),
    pack([[1, 1], [7], [0]]),
  ];
  for (const bytes of cases) assert.equal(run(bytes).status, 'error');
});
test('unknown opcodes and unused operands are rejected', () => {
  for (const code of [8, 255]) assert.equal(run(pack([[1, 1], [code], [0]])).status, 'error');
  assert.equal(run(pack([[1, 1], [5, 1], [2], [0]])).error, 'reserved_operand');
});
