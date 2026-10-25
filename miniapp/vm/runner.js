// Pure API1-style JS: no eval, Function, modules, BLE, files or side effects.
// Package into a future miniapp only after the API1 build path is verified.
// Exact signed-i32 multiplication using 16-bit limbs; no Math.imul dependency.
function band7MulI32(a, b) {
  var ah = (a >>> 16) & 65535;
  var al = a & 65535;
  var bh = (b >>> 16) & 65535;
  var bl = b & 65535;
  return (al * bl + ((ah * bl + al * bh) << 16)) | 0;
}
function band7VmRun(input) {
  var maxInstructions = 32;
  var maxStack = 16;
  if (!input || typeof input.length !== 'number' || input.length < 8 ||
      input.length > 8 + 5 * maxInstructions) {
    return { status: 'error', error: 'invalid_length' };
  }
  for (var b = 0; b < input.length; b++) {
    if (typeof input[b] !== 'number' || input[b] < 0 || input[b] > 255 ||
        input[b] !== Math.floor(input[b])) {
      return { status: 'error', error: 'invalid_byte' };
    }
  }
  if (input[0] !== 66 || input[1] !== 55 || input[2] !== 86 || input[3] !== 77) {
    return { status: 'error', error: 'magic' };
  }
  if (input[4] !== 1 || input[5] !== 0) {
    return { status: 'error', error: 'version' };
  }
  var count = input[6] | (input[7] << 8);
  if (count < 2 || count > maxInstructions || input.length !== 8 + 5 * count) {
    return { status: 'error', error: 'invalid_length' };
  }
  var stack = [];
  for (var pc = 0; pc < count; pc++) {
    var offset = 8 + pc * 5;
    var op = input[offset];
    var arg = (input[offset + 1] | (input[offset + 2] << 8) |
      (input[offset + 3] << 16) | (input[offset + 4] << 24)) | 0;
    if (op === 0) {
      if (arg !== 0 || pc !== count - 1 || stack.length !== 1) {
        return { status: 'error', error: 'invalid_halt' };
      }
      return { status: 'ok', value: stack[0], steps: pc + 1 };
    }
    if (op !== 1 && arg !== 0) {
      return { status: 'error', error: 'reserved_operand' };
    }
    if (op === 1) {
      if (stack.length === maxStack) return { status: 'error', error: 'stack_overflow' };
      stack.push(arg);
    } else if (op === 2 || op === 3 || op === 4 || op === 7) {
      if (stack.length < 2) return { status: 'error', error: 'stack_underflow' };
      var right = stack.pop();
      var left = stack.pop();
      if (op === 2) stack.push((left + right) | 0);
      if (op === 3) stack.push((left - right) | 0);
      if (op === 4) stack.push(band7MulI32(left, right) | 0);
      if (op === 7) stack.push(left > right ? 1 : 0);
    } else if (op === 5) {
      if (!stack.length) return { status: 'error', error: 'stack_underflow' };
      if (stack.length === maxStack) return { status: 'error', error: 'stack_overflow' };
      stack.push(stack[stack.length - 1]);
    } else if (op === 6) {
      if (!stack.length) return { status: 'error', error: 'stack_underflow' };
      stack.pop();
    } else {
      return { status: 'error', error: 'opcode' };
    }
  }
  return { status: 'error', error: 'invalid_halt' };
}
