// SPDX-License-Identifier: AGPL-3.0-or-later
package org.workspace.band7;
import java.util.*;import java.nio.file.*;
/** Public-fixture composition only. No captured peer traffic or IO. */
public final class EncryptedFramesTest {
 static HuamiSessionCrypto ready(List<String> v){HuamiSessionCrypto c=HuamiCryptoTest.fixed(v);try(HuamiSessionCrypto.Proof p=c.respond(HuamiCryptoTest.NONCE,HuamiCryptoTest.point(v.get(4),v.get(5)))){HuamiCryptoTest.check(p.copyBytes().length==32);}return c;}
 static void check(boolean b){HuamiCryptoTest.check(b);}
 static void rejected(Runnable r){boolean fail=false;try{r.run();}catch(IllegalArgumentException|IllegalStateException expected){fail=true;}check(fail);}
 public static void main(String[] args)throws Exception{run(Files.readAllLines(Paths.get(args[0])));}
 @SuppressWarnings("try") // Intentional mid-lifecycle close regression.
 public static void run(List<String> v){
  for(int n:new int[]{1,8,9,16,33,52,67,500})try(HuamiSessionCrypto c=ready(v);HuamiEncryptedFrames tx=c.claimEncryptedFrames(2,HuamiEncryptedFrames.RxPolicy.REQUIRE_TX_LAYOUT);HuamiSessionCrypto d=ready(v);HuamiEncryptedFrames rx=d.claimEncryptedFrames(2,HuamiEncryptedFrames.RxPolicy.REQUIRE_TX_LAYOUT)){
   byte[] bytes=new byte[n];Arrays.fill(bytes,(byte)0x5a);List<byte[]> frames=tx.encode(0x29,23,bytes);check((frames.get(0)[1]&9)==9&&(frames.get(0)[3]&255)==3&&(frames.get(0)[5]&255)==(n&255));HuamiEncryptedFrames.Received got=null;
   for(byte[] f:frames){check(f.length<=20);got=rx.receive(1,f);}check(got!=null&&got.crcChecked&&Arrays.equals(got.payload,bytes)&&got.endpoint==0x29&&got.sequence!=null);check(Arrays.equals(got.ack,new byte[]{4,0,3,1,(byte)(frames.size()-1)}));
   final HuamiEncryptedFrames target=rx;rejected(()->{for(byte[] f:frames)target.receive(2,f);}); // repeated counter rejected only under strict policy
  }
  for(int mode=0;mode<6;mode++)try(HuamiSessionCrypto c=ready(v);HuamiEncryptedFrames codec=c.claimEncryptedFrames(2,HuamiEncryptedFrames.RxPolicy.REQUIRE_TX_LAYOUT)){
   List<byte[]> frames=codec.encode(0x29,23,new byte[40]);final int m=mode;
   rejected(()->{if(m==0){codec.receive(0,frames.get(0));codec.receive(1,frames.get(2));}
    if(m==1){codec.receive(0,frames.get(0));codec.tick(5000);}
    if(m==2){frames.get(0)[3]++;for(byte[] f:frames)codec.receive(0,f);}
    if(m==3){frames.get(frames.size()-1)[frames.get(frames.size()-1).length-1]^=1;for(byte[] f:frames)codec.receive(0,f);}
    if(m==4){frames.get(0)[5]=(byte)255;frames.get(0)[6]=127;codec.receive(0,frames.get(0));}
    if(m==5){codec.receive(1,frames.get(0));codec.tick(0);}});
  }
  try(HuamiSessionCrypto c=ready(v);HuamiEncryptedFrames codec=c.claimEncryptedFrames(255,HuamiEncryptedFrames.RxPolicy.UPSTREAM_PAYLOAD_ONLY)){rejected(()->codec.encode(0,23,new byte[]{3}));}
  try(HuamiSessionCrypto c=ready(v);HuamiEncryptedFrames codec=c.claimEncryptedFrames(2,HuamiEncryptedFrames.RxPolicy.UPSTREAM_PAYLOAD_ONLY)){
   rejected(()->c.claimEncryptedFrames(2,HuamiEncryptedFrames.RxPolicy.UPSTREAM_PAYLOAD_ONLY));c.close();rejected(()->codec.encode(0,23,new byte[]{3}));
  }
  // Counter exhaustion is stricter local pilot policy; do not wrap/reuse.
  try(HuamiSessionCrypto c=ready(v);HuamiEncryptedFrames codec=new HuamiEncryptedFrames(c,2,0xffffffffL,HuamiEncryptedFrames.RxPolicy.REQUIRE_TX_LAYOUT)){
   check(!codec.encode(0,23,new byte[]{3}).isEmpty());rejected(()->codec.encode(0,23,new byte[]{3}));
  }
  // Endpoint/header is not CRC-covered upstream, even with TX-layout checks.
  try(HuamiSessionCrypto c=ready(v);HuamiEncryptedFrames codec=c.claimEncryptedFrames(2,HuamiEncryptedFrames.RxPolicy.REQUIRE_TX_LAYOUT)){
   List<byte[]> frames=codec.encode(0x29,23,new byte[]{3});frames.get(0)[9]=0x30;HuamiEncryptedFrames.Received r=null;for(byte[] f:frames)r=codec.receive(0,f);check(r!=null&&r.endpoint==0x30&&r.crcChecked);
  }
  // Source-compatible RX deliberately does NOT promise CRC/replay checks.
  try(HuamiSessionCrypto c=ready(v);HuamiEncryptedFrames codec=c.claimEncryptedFrames(2,HuamiEncryptedFrames.RxPolicy.UPSTREAM_PAYLOAD_ONLY)){
   List<byte[]> frames=codec.encode(0,23,new byte[]{3});for(int i=0;i<2;i++){HuamiEncryptedFrames.Received r=null;for(byte[] f:frames)r=codec.receive(i,f);check(r!=null&&!r.crcChecked&&r.sequence==null);}
  }
  System.out.println("PASS encrypted synthetic framing/roundtrip, explicit RX policies, counter replay under TX-layout policy, corrupt/sequence/length/deadline/close/ownership negatives; not Band interoperability or AEAD.");
 }
}
