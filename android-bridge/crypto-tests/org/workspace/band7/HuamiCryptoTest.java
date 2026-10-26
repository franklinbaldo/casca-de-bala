package org.workspace.band7;
import java.nio.file.*;
import java.math.BigInteger;
import java.util.*;
import java.lang.reflect.*;
import javax.crypto.*;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.crypto.params.*;
/** Public OpenSSL3.5.7 test scalar/point/X plus FIPS197 public AES example.
 * Combined Huami proof is a synthetic composition, NOT a Band transcript.
 */
public final class HuamiCryptoTest {
 static byte[] hex(String s){if(s.length()%2!=0)throw new IllegalArgumentException("hex");byte[] out=new byte[s.length()/2];for(int i=0;i<out.length;i++){int a=Character.digit(s.charAt(i*2),16),b=Character.digit(s.charAt(i*2+1),16);if(a<0||b<0)throw new IllegalArgumentException("hex");out[i]=(byte)((a<<4)|b);}return out;}
 static final byte[] TEST_KEY=hex("000102030405060708090a0b0c0d0e0f");
 static final byte[] NONCE=hex("00112233445566778899aabbccddeeff");
 static void check(boolean b){if(!b)throw new AssertionError("crypto contract mismatch; no values logged");}
 static HuamiSessionCrypto fixed(List<String> v){var c=CustomNamedCurves.getByName("sect163r2");return new HuamiSessionCrypto(TEST_KEY,new ECPrivateKeyParameters(new BigInteger(v.get(0),16),new ECDomainParameters(c.getCurve(),c.getG(),c.getN(),c.getH())));}
 static byte[] point(String x,String y){byte[] p=new byte[48];System.arraycopy(HuamiSessionCrypto.little24(new BigInteger(x,16)),0,p,0,24);System.arraycopy(HuamiSessionCrypto.little24(new BigInteger(y,16)),0,p,24,24);return p;}
 static boolean zero(byte[] b){for(byte v:b)if(v!=0)return false;return true;}
 static void rejected(List<String> v,byte[] n,byte[] p){HuamiSessionCrypto c=fixed(v);boolean failed=false;try{c.respond(n,p);}catch(IllegalArgumentException e){failed=true;}finally{check(c.isClosed());c.close();}check(failed);}
 public static void main(String[] args)throws Exception{
  List<String> v=Files.readAllLines(Paths.get(args[0]));byte[] peer=point(v.get(4),v.get(5));
  HuamiSessionCrypto c=fixed(v);check(Arrays.equals(c.publicKey(),point(v.get(1),v.get(2))));
  byte[] shared=HuamiSessionCrypto.little24(new BigInteger(v.get(6),16)),derived=new byte[16];for(int i=0;i<16;i++)derived[i]=(byte)(shared[i+8]^TEST_KEY[i]);
  Cipher aes=Cipher.getInstance("AES/ECB/NoPadding");aes.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(derived,"AES"));byte[] expectedSecond=aes.doFinal(NONCE);
  try(HuamiSessionCrypto.Proof proof=c.respond(NONCE,peer)){
   byte[] p=proof.copyBytes();check(p.length==32);check(Arrays.equals(Arrays.copyOf(p,16),hex("69c4e0d86a7b0430d8cdb78070b4c55a")));check(Arrays.equals(Arrays.copyOfRange(p,16,32),expectedSecond));
   check(proof.sequenceBase==((shared[0]&255)|((shared[1]&255)<<8)|((shared[2]&255)<<16)|((shared[3]&255)<<24)));
  }
  boolean repeated=false;try{c.respond(NONCE,peer);}catch(IllegalStateException consumed){repeated=true;}check(repeated);
  Field f=HuamiSessionCrypto.class.getDeclaredField("sessionKey");f.setAccessible(true);byte[] owned=(byte[])f.get(c);c.close();check(zero(owned)&&f.get(c)==null);c.close();
  check(Arrays.equals(TEST_KEY,hex("000102030405060708090a0b0c0d0e0f"))); // caller buffer not owned
  rejected(v,new byte[15],peer);rejected(v,new byte[17],peer);rejected(v,null,peer);rejected(v,NONCE,new byte[47]);rejected(v,NONCE,null);rejected(v,NONCE,new byte[48]);
  byte[] bad=new byte[48];Arrays.fill(bad,(byte)0xff);rejected(v,NONCE,bad);
  bad=peer.clone();bad[23]=1;rejected(v,NONCE,bad);
  var curve=CustomNamedCurves.getByName("sect163r2");rejected(v,NONCE,point("0",curve.getCurve().getB().sqrt().toBigInteger().toString(16)));
  boolean keyLength=false;try{HuamiSessionCrypto.open(new byte[15]);}catch(IllegalArgumentException invalid){keyLength=true;}check(keyLength);
  // Lifecycle validation uses only published test scalars; no new key generation.
  System.out.println("PASS BC1.86 provider host: published public-point/X and AES KAT composition, wire24/48/32/LE sequence, length/canonicality/off-curve/infinity/order2 rejection, one-shot/close/buffer ownership. No real keys/ART/Band transcript; JVM erasure not guaranteed.");
 }
}
