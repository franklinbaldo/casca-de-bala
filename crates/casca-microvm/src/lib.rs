//! Experimental, side-effect-free bytecode for a future Band 7 JavaScript miniapp.
//! Not a Xiaomi GATT packet, firmware program, or MCP wire format.

pub const MAX_INSTRUCTIONS: usize = 32;
pub const MAX_STACK: usize = 16;
pub const MAX_BYTES: usize = 8 + 5 * MAX_INSTRUCTIONS;
const MAGIC: &[u8; 4] = b"B7VM";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Instruction {
    Halt,
    Push(i32),
    Add,
    Sub,
    Mul,
    Dup,
    Drop,
    GreaterThan,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum VmError {
    InvalidLength,
    Magic,
    Version,
    Opcode,
    ReservedOperand,
    InvalidHalt,
    StackUnderflow,
    StackOverflow,
}

fn verify(instructions: &[Instruction]) -> Result<(), VmError> {
    if !(2..=MAX_INSTRUCTIONS).contains(&instructions.len()) {
        return Err(VmError::InvalidLength);
    }
    let mut depth = 0_usize;
    for (index, instruction) in instructions.iter().enumerate() {
        match instruction {
            Instruction::Halt => {
                if index != instructions.len() - 1 || depth != 1 {
                    return Err(VmError::InvalidHalt);
                }
            }
            Instruction::Push(_) | Instruction::Dup => {
                if matches!(instruction, Instruction::Dup) && depth == 0 {
                    return Err(VmError::StackUnderflow);
                }
                depth += 1;
                if depth > MAX_STACK {
                    return Err(VmError::StackOverflow);
                }
            }
            Instruction::Add | Instruction::Sub | Instruction::Mul | Instruction::GreaterThan => {
                if depth < 2 {
                    return Err(VmError::StackUnderflow);
                }
                depth -= 1;
            }
            Instruction::Drop => {
                if depth == 0 {
                    return Err(VmError::StackUnderflow);
                }
                depth -= 1;
            }
        }
    }
    if !matches!(instructions.last(), Some(Instruction::Halt)) {
        return Err(VmError::InvalidHalt);
    }
    Ok(())
}

pub fn encode(instructions: &[Instruction]) -> Result<Vec<u8>, VmError> {
    verify(instructions)?;
    let mut bytes = Vec::with_capacity(8 + 5 * instructions.len());
    bytes.extend_from_slice(MAGIC);
    bytes.extend_from_slice(&[1, 0]);
    bytes.extend_from_slice(&(instructions.len() as u16).to_le_bytes());
    for instruction in instructions {
        let (opcode, operand) = match instruction {
            Instruction::Halt => (0_u8, 0),
            Instruction::Push(value) => (1, *value),
            Instruction::Add => (2, 0),
            Instruction::Sub => (3, 0),
            Instruction::Mul => (4, 0),
            Instruction::Dup => (5, 0),
            Instruction::Drop => (6, 0),
            Instruction::GreaterThan => (7, 0),
        };
        bytes.push(opcode);
        bytes.extend_from_slice(&operand.to_le_bytes());
    }
    Ok(bytes)
}

pub fn decode(bytes: &[u8]) -> Result<Vec<Instruction>, VmError> {
    if bytes.len() < 8 || bytes.len() > MAX_BYTES {
        return Err(VmError::InvalidLength);
    }
    if &bytes[..4] != MAGIC {
        return Err(VmError::Magic);
    }
    if bytes[4] != 1 || bytes[5] != 0 {
        return Err(VmError::Version);
    }
    let count = usize::from(u16::from_le_bytes([bytes[6], bytes[7]]));
    if !(2..=MAX_INSTRUCTIONS).contains(&count) || bytes.len() != 8 + 5 * count {
        return Err(VmError::InvalidLength);
    }
    let mut instructions = Vec::with_capacity(count);
    for chunk in bytes[8..].as_chunks::<5>().0 {
        let operand = i32::from_le_bytes([chunk[1], chunk[2], chunk[3], chunk[4]]);
        if chunk[0] != 1 && operand != 0 {
            return Err(VmError::ReservedOperand);
        }
        let instruction = match chunk[0] {
            0 => Instruction::Halt,
            1 => Instruction::Push(operand),
            2 => Instruction::Add,
            3 => Instruction::Sub,
            4 => Instruction::Mul,
            5 => Instruction::Dup,
            6 => Instruction::Drop,
            7 => Instruction::GreaterThan,
            _ => return Err(VmError::Opcode),
        };
        instructions.push(instruction);
    }
    verify(&instructions)?;
    Ok(instructions)
}

/// Reference evaluation for differential tests; the JS interpreter is the target.
pub fn execute(bytes: &[u8]) -> Result<i32, VmError> {
    let instructions = decode(bytes)?;
    let mut stack = Vec::<i32>::with_capacity(MAX_STACK);
    for instruction in instructions {
        match instruction {
            Instruction::Push(value) => stack.push(value),
            Instruction::Halt => return stack.pop().ok_or(VmError::StackUnderflow),
            Instruction::Dup => {
                let value = *stack.last().ok_or(VmError::StackUnderflow)?;
                stack.push(value);
            }
            Instruction::Drop => {
                stack.pop().ok_or(VmError::StackUnderflow)?;
            }
            Instruction::Add | Instruction::Sub | Instruction::Mul | Instruction::GreaterThan => {
                let right = stack.pop().ok_or(VmError::StackUnderflow)?;
                let left = stack.pop().ok_or(VmError::StackUnderflow)?;
                let result = match instruction {
                    Instruction::Add => left.wrapping_add(right),
                    Instruction::Sub => left.wrapping_sub(right),
                    Instruction::Mul => left.wrapping_mul(right),
                    Instruction::GreaterThan => i32::from(left > right),
                    _ => unreachable!(),
                };
                stack.push(result);
            }
        }
    }
    Err(VmError::InvalidHalt)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde::Deserialize;

    #[derive(Deserialize)]
    struct Vector {
        name: String,
        hex: String,
        value: i32,
    }
    fn from_hex(hex: &str) -> Vec<u8> {
        assert!(hex.len().is_multiple_of(2));
        (0..hex.len())
            .step_by(2)
            .map(|i| u8::from_str_radix(&hex[i..i + 2], 16).unwrap())
            .collect()
    }
    #[test]
    fn shared_golden_vectors_round_trip_and_execute() {
        let vectors: Vec<Vector> =
            serde_json::from_str(include_str!("../../../miniapp/vm/vectors.json")).unwrap();
        for vector in vectors {
            let bytes = from_hex(&vector.hex);
            let instructions = decode(&bytes).unwrap();
            assert_eq!(encode(&instructions).unwrap(), bytes, "{}", vector.name);
            assert_eq!(execute(&bytes).unwrap(), vector.value, "{}", vector.name);
        }
    }
    #[test]
    fn reject_unsafe_programs_and_bad_frames() {
        for bad in [
            vec![],
            vec![0; MAX_BYTES + 1],
            vec![66, 55, 86, 77, 2, 0, 0, 0],
        ] {
            assert!(decode(&bad).is_err());
        }
        assert_eq!(
            encode(&[Instruction::Add, Instruction::Halt]),
            Err(VmError::StackUnderflow)
        );
        assert_eq!(
            encode(&[Instruction::Push(1), Instruction::Drop, Instruction::Halt]),
            Err(VmError::InvalidHalt)
        );
        assert_eq!(
            encode(&[
                Instruction::Push(1),
                Instruction::Halt,
                Instruction::Push(2)
            ]),
            Err(VmError::InvalidHalt)
        );
        let mut bytes = encode(&[Instruction::Push(7), Instruction::Halt]).unwrap();
        bytes[13] = 0xff;
        assert_eq!(decode(&bytes), Err(VmError::Opcode));
    }
}
