// SPDX-License-Identifier: AGPL-3.0-or-later
package org.workspace.band7;
import java.util.*;import java.nio.file.*;import java.math.BigInteger;
import android.bluetooth.BluetoothGattCharacteristic;
import nodomain.freeyourgadget.gadgetbridge.service.devices.huami.*;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
/** Differential against pinned, unmodified primary encoder/decoder. */
public final class EncryptedReferenceTest {
 public static void main(String[] args)throws Exception{
  List<String> v=Files.readAllLines(Paths.get(args[0]));byte[] shared=HuamiSessionCrypto.little24(new BigInteger(v.get(6),16)),key=new byte[16];for(int i=0;i<16;i++)key[i]=(byte)(shared[i+8]^HuamiCryptoTest.TEST_KEY[i]);
  int counter=(shared[0]&255)|((shared[1]&255)<<8)|((shared[2]&255)<<16)|((shared[3]&255)<<24);
  for(int mtu:new int[]{23,247,515})try(HuamiSessionCrypto c=EncryptedFramesTest.ready(v);HuamiEncryptedFrames codec=c.claimEncryptedFrames(0,HuamiEncryptedFrames.RxPolicy.REQUIRE_TX_LAYOUT)){
   Huami2021ChunkedEncoder encoder=new Huami2021ChunkedEncoder(new BluetoothGattCharacteristic(),true,mtu);encoder.setEncryptionParameters(counter,key.clone());
   final byte[][] decoded={null};final int[] endpoint={-1};Huami2021ChunkedDecoder decoder=new Huami2021ChunkedDecoder((e,p)->{endpoint[0]=e&65535;decoded[0]=p;},true);decoder.setEncryptionParameters(key.clone());
   for(int n:new int[]{1,8,9,16,33,52,67,500}){
    byte[] p=new byte[n];for(int i=0;i<n;i++)p[i]=(byte)i;TransactionBuilder b=new TransactionBuilder();encoder.write(b,(short)0x29,p,true,true);List<byte[]> actual=codec.encode(0x29,mtu,p);
    HuamiCryptoTest.check(actual.size()==b.frames.size());for(int i=0;i<actual.size();i++)HuamiCryptoTest.check(Arrays.equals(actual.get(i),b.frames.get(i)));
    for(byte[] f:actual)decoder.decode(f);HuamiCryptoTest.check(endpoint[0]==0x29&&Arrays.equals(decoded[0],p));
   }
  }
  // Deliberately malformed encrypted trailers using only the PUBLIC fixture key.
  // The original RX decoder does not enforce the encoder's CRC/counter/padding.
  for(int mode=0;mode<3;mode++){
   Huami2021ChunkedEncoder encoder=new Huami2021ChunkedEncoder(new BluetoothGattCharacteristic(),true,247);encoder.setEncryptionParameters(counter,key.clone());TransactionBuilder b=new TransactionBuilder();byte[] payload={3};encoder.write(b,(short)0x29,payload,true,true);
   byte[] frame=b.frames.get(0).clone(),messageKey=key.clone();for(int i=0;i<16;i++)messageKey[i]^=frame[3];
   byte[] plain=nodomain.freeyourgadget.gadgetbridge.util.CryptoUtils.decryptAES(Arrays.copyOfRange(frame,11,frame.length),messageKey);
   plain[mode==0?5:mode==1?1:9]^=1; // CRC, counter, zero-fill respectively
   byte[] changed=nodomain.freeyourgadget.gadgetbridge.util.CryptoUtils.encryptAES(plain,messageKey);System.arraycopy(changed,0,frame,11,changed.length);
   final byte[][] decoded={null};Huami2021ChunkedDecoder decoder=new Huami2021ChunkedDecoder((e,q)->decoded[0]=q,true);decoder.setEncryptionParameters(key.clone());decoder.decode(frame);HuamiCryptoTest.check(Arrays.equals(decoded[0],payload));
   try(HuamiSessionCrypto c=EncryptedFramesTest.ready(v);HuamiEncryptedFrames compatibility=c.claimEncryptedFrames(0,HuamiEncryptedFrames.RxPolicy.UPSTREAM_PAYLOAD_ONLY)){HuamiCryptoTest.check(Arrays.equals(compatibility.receive(0,frame).payload,payload));}
   try(HuamiSessionCrypto c=EncryptedFramesTest.ready(v);HuamiEncryptedFrames strict=c.claimEncryptedFrames(0,HuamiEncryptedFrames.RxPolicy.REQUIRE_TX_LAYOUT)){EncryptedFramesTest.rejected(()->strict.receive(0,frame));}
   Arrays.fill(plain,(byte)0);Arrays.fill(messageKey,(byte)0);Arrays.fill(changed,(byte)0);
  }
  Arrays.fill(key,(byte)0);Arrays.fill(shared,(byte)0);
  System.out.println("PASS differential: encrypted TX bytes match pinned unmodified Gadgetbridge encoder at MTU23/247/515; upstream decoder accepts our frames. Public fixtures only, not a Band capture/interoperability proof; support shims explicit. CRC/counter/padding-negative trailers accepted by original RX and source-compatible policy, rejected by experimental TX-layout policy.");
 }
}
