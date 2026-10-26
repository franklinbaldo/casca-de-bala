package org.workspace.band7;
import java.nio.file.*;
import java.util.*;
import java.math.BigInteger;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.crypto.params.*;
public final class CryptoBridgeSmoke {
 static void check(boolean b){if(!b)throw new AssertionError("public envelope/JNI contract");}
 public static void main(String[] args)throws Exception{
  List<String> v=Files.readAllLines(Paths.get(args[0]));byte[] payload=new byte[67];payload[0]=0x10;payload[1]=4;payload[2]=1;
  System.arraycopy(HuamiSessionCrypto.little24(new BigInteger(v.get(4),16)),0,payload,19,24);System.arraycopy(HuamiSessionCrypto.little24(new BigInteger(v.get(5),16)),0,payload,43,24);
  check(NativeBridge.authReplyKind(0x82,payload)==1);
  var c=CustomNamedCurves.getByName("sect163r2");byte[] publicTestAuth=HuamiCryptoTest.hex("000102030405060708090a0b0c0d0e0f");
  try(HuamiSessionCrypto provider=new HuamiSessionCrypto(publicTestAuth,new ECPrivateKeyParameters(new BigInteger(v.get(0),16),new ECDomainParameters(c.getCurve(),c.getG(),c.getN(),c.getH())));HuamiSessionCrypto.Proof proof=provider.respond(Arrays.copyOfRange(payload,3,19),Arrays.copyOfRange(payload,19,67))){check(proof.copyBytes().length==32);}
  check(NativeBridge.authReplyKind(0x82,new byte[]{0x10,5,1})==2);check(NativeBridge.authReplyKind(0x82,new byte[]{0x10,5,0x25})==3);
  check(NativeBridge.authReplyKind(0,new byte[]{0x10,5,1})==0);check(NativeBridge.authReplyKind(0x82,null)==0);check(NativeBridge.authReplyKind(-1,payload)==0);check(NativeBridge.authReplyKind(65536,payload)==0);check(NativeBridge.authReplyKind(0x82,new byte[68])==0);
  for(int i=0;i<67;i++)check(NativeBridge.authReplyKind(0x82,Arrays.copyOf(payload,i))==0);
  System.out.println("PASS host -Xcheck:jni: public fixture->JNI->Rust AUTH shape->BC1.86 proof; key remains Java-only; null/endpoint/limit/truncation. No ART/BLE, no authenticated operation enabled.");
 }
}
