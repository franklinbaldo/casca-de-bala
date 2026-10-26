// SPDX-License-Identifier: AGPL-3.0-or-later
// Wire-layout reference: Gadgetbridge InitOperation2021 and Huami2021Service,
// revision a0948ee1cbc2a870f91d313f8e37df5f524465f7.
// Copyright (C) 2021-2024 Andreas Shimokawa, José Rebelo (upstream).
// Rust envelope validation: Casca de Bala contributors, 2026.
//! Receive-only AUTH payload shapes, AFTER a trusted adapter has selected the
//! endpoint and reassembled the message. Not a GATT packet decoder, key exchange,
//! cipher, proof verifier or authenticated session. No writes or key storage.

pub const AUTH_ENDPOINT: u16 = 0x0082;

#[derive(Debug, PartialEq, Eq)]
pub enum ParseError {
    Endpoint,
    Length,
    Header,
    Unsupported,
}

/// Untrusted peer fields. No Debug/Serialize implementation: do not log them.
/// Wire lengths do NOT establish curve validity, identity or proof possession.
pub struct Challenge<'a> {
    nonce: &'a [u8; 16],
    public_key: &'a [u8; 48],
}
impl Challenge<'_> {
    pub fn nonce(&self) -> &[u8; 16] {
        self.nonce
    }
    pub fn unvalidated_public_key(&self) -> &[u8; 48] {
        self.public_key
    }
}

/// Peer-reported status alone must never unlock protected operations.
pub enum Reply<'a> {
    Challenge(Challenge<'a>),
    PeerReportedSuccess,
    PeerReportedBadKey,
}

pub fn decode_reply(endpoint: u16, payload: &[u8]) -> Result<Reply<'_>, ParseError> {
    if endpoint != AUTH_ENDPOINT {
        return Err(ParseError::Endpoint);
    }
    if payload.len() < 3 {
        return Err(ParseError::Length);
    }
    if payload[0] != 0x10 {
        return Err(ParseError::Header);
    }
    match (payload[1], payload[2]) {
        (0x04, 0x01) => {
            if payload.len() != 67 {
                return Err(ParseError::Length);
            }
            Ok(Reply::Challenge(Challenge {
                nonce: payload[3..19].try_into().map_err(|_| ParseError::Length)?,
                public_key: payload[19..67].try_into().map_err(|_| ParseError::Length)?,
            }))
        }
        (0x05, status @ (0x01 | 0x25)) => {
            if payload.len() != 3 {
                return Err(ParseError::Length);
            }
            Ok(if status == 0x01 {
                Reply::PeerReportedSuccess
            } else {
                Reply::PeerReportedBadKey
            })
        }
        _ => Err(ParseError::Unsupported),
    }
}
