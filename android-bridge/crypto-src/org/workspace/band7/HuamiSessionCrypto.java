// SPDX-License-Identifier: AGPL-3.0-or-later
package org.workspace.band7;
import java.math.BigInteger;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.*;
import org.bouncycastle.crypto.agreement.ECDHBasicAgreement;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.crypto.generators.ECKeyPairGenerator;
import org.bouncycastle.crypto.params.*;

/** Local crypto only, no BLE/credential storage/serialization. Wire derivation:
 * Gadgetbridge InitOperation2021, a0948ee1cbc2a870f91d313f8e37df5f524465f7,
 * Andreas Shimokawa/Jose Rebelo. No initialized/authenticated state is implied.
 * close() is best-effort cleanup; JVM/BC/JCA copies cannot be guaranteed erased.
 */
public final class HuamiSessionCrypto implements AutoCloseable {
    private static final X9ECParameters CURVE=CustomNamedCurves.getByName("sect163r2");
    private static final ECDomainParameters DOMAIN=new ECDomainParameters(CURVE.getCurve(),CURVE.getG(),CURVE.getN(),CURVE.getH());
    private byte[] authKey,sessionKey;private long transportSequence;private boolean framesClaimed;private ECPrivateKeyParameters ephemeral;private boolean closed;
    /** Owns a copy; caller remains responsible for its original byte buffer. */
    public static HuamiSessionCrypto open(byte[] authKey){
        require(authKey,16);ECKeyPairGenerator generator=new ECKeyPairGenerator();
        generator.init(new ECKeyGenerationParameters(DOMAIN,new SecureRandom()));
        AsymmetricCipherKeyPair pair=generator.generateKeyPair();
        return new HuamiSessionCrypto(authKey,(ECPrivateKeyParameters)pair.getPrivate());
    }
    // Package scope permits public-fixture deterministic testing, not UI input.
    HuamiSessionCrypto(byte[] key,ECPrivateKeyParameters scalar){require(key,16);Objects.requireNonNull(scalar);
        if(!scalar.getParameters().equals(DOMAIN))throw new IllegalArgumentException("curve");
        this.authKey=key.clone();this.ephemeral=scalar;
    }
    private static void require(byte[] b,int n){if(b==null||b.length!=n)throw new IllegalArgumentException("length");}
    static byte[] little24(BigInteger n){byte[] be=n.toByteArray(),out=new byte[24];if(n.signum()<0||n.bitLength()>163)throw new IllegalArgumentException("coordinate");for(int i=0;i<Math.min(be.length,24);i++)out[i]=be[be.length-1-i];return out;}
    static BigInteger coordinate(byte[] b,int start){byte[] be=new byte[24];for(int i=0;i<24;i++)be[23-i]=b[start+i];BigInteger n=new BigInteger(1,be);Arrays.fill(be,(byte)0);if(n.bitLength()>163)throw new IllegalArgumentException("coordinate");return n;}
    public byte[] publicKey(){if(closed||ephemeral==null)throw new IllegalStateException("closed or consumed");
        org.bouncycastle.math.ec.ECPoint q=DOMAIN.getG().multiply(ephemeral.getD()).normalize();byte[] out=new byte[48];
        System.arraycopy(little24(q.getAffineXCoord().toBigInteger()),0,out,0,24);System.arraycopy(little24(q.getAffineYCoord().toBigInteger()),0,out,24,24);return out;
    }
    /** Exactly once per ephemeral key; invalid peer material closes the context.
     * Returns the two encrypted nonce blocks, NOT the raw auth/session keys.
     * No peer identity or completed transport authentication is asserted here.
     */
    public Proof respond(byte[] nonce,byte[] peer){
        if(closed||ephemeral==null)throw new IllegalStateException("closed or consumed");
        byte[] shared=null,one=null,two=null;
        try{
            require(nonce,16);require(peer,48);
            ECPublicKeyParameters remote=new ECPublicKeyParameters(CURVE.getCurve().createPoint(coordinate(peer,0),coordinate(peer,24)),DOMAIN);
            ECDHBasicAgreement agreement=new ECDHBasicAgreement();agreement.init(ephemeral);
            shared=little24(agreement.calculateAgreement(remote));
            boolean nonzero=false;for(byte b:shared)nonzero|=b!=0;if(!nonzero)throw new IllegalArgumentException("shared point");
            sessionKey=new byte[16];for(int i=0;i<16;i++)sessionKey[i]=(byte)(shared[i+8]^authKey[i]);
            Cipher cipher=Cipher.getInstance("AES/ECB/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(authKey,"AES"));one=cipher.doFinal(nonce);
            cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(sessionKey,"AES"));two=cipher.doFinal(nonce);
            byte[] proof=new byte[32];System.arraycopy(one,0,proof,0,16);System.arraycopy(two,0,proof,16,16);
            int sequence=(shared[0]&255)|((shared[1]&255)<<8)|((shared[2]&255)<<16)|((shared[3]&255)<<24);
            transportSequence=Integer.toUnsignedLong(sequence);ephemeral=null;Arrays.fill(authKey,(byte)0);authKey=null;
            return new Proof(proof,sequence);
        }catch(GeneralSecurityException e){close();throw new IllegalStateException("crypto unavailable",e);}
        catch(RuntimeException e){close();throw e;}
        finally{if(shared!=null)Arrays.fill(shared,(byte)0);if(one!=null)Arrays.fill(one,(byte)0);if(two!=null)Arrays.fill(two,(byte)0);}
    }
    /** Local codec ownership only; does not grant authenticated-operation permission. */
    public HuamiEncryptedFrames claimEncryptedFrames(int lastWriteHandle,HuamiEncryptedFrames.RxPolicy policy){
        if(closed||sessionKey==null||framesClaimed)throw new IllegalStateException("session unavailable or claimed");
        HuamiEncryptedFrames codec=new HuamiEncryptedFrames(this,lastWriteHandle,transportSequence,policy);framesClaimed=true;return codec;
    }
    byte[] cryptMessage(int handle,byte[] input,boolean encrypt){
        if(closed||sessionKey==null)throw new IllegalStateException("session unavailable");
        byte[] key=sessionKey.clone();try{for(int i=0;i<key.length;i++)key[i]^=(byte)handle;
            Cipher c=Cipher.getInstance("AES/ECB/NoPadding");c.init(encrypt?Cipher.ENCRYPT_MODE:Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"));return c.doFinal(input);
        }catch(GeneralSecurityException e){throw new IllegalStateException("cipher unavailable",e);}finally{Arrays.fill(key,(byte)0);}
    }
    public boolean isClosed(){return closed;}
    @Override public void close(){closed=true;ephemeral=null;if(authKey!=null)Arrays.fill(authKey,(byte)0);authKey=null;if(sessionKey!=null)Arrays.fill(sessionKey,(byte)0);sessionKey=null;}
    public static final class Proof implements AutoCloseable {
        private byte[] bytes;public final int sequenceBase;
        private Proof(byte[] b,int s){bytes=b;sequenceBase=s;}
        public byte[] copyBytes(){if(bytes==null)throw new IllegalStateException("closed");return bytes.clone();}
        @Override public void close(){if(bytes!=null)Arrays.fill(bytes,(byte)0);bytes=null;}
    }
}
