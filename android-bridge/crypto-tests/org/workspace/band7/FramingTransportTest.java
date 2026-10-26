package org.workspace.band7;
import java.nio.file.*;import java.util.*;import java.util.concurrent.atomic.AtomicLong;
/** Framing/state/BC/JNI real; port and peer replies strictly synthetic. */
public final class FramingTransportTest {
 static void check(boolean b){if(!b)throw new AssertionError("framing/pump contract");}
 static final class Port implements HuamiAuthTransport.Port {
  final List<byte[]> data=new ArrayList<>();final List<Integer> target=new ArrayList<>();final List<Long> tickets=new ArrayList<>();int closes;
  public boolean write(long g,long t,int which,byte[] frame){data.add(frame.clone());target.add(which);tickets.add(t);return true;}
  public void close(){closes++;}
 }
 static List<byte[]> chunks(byte[] p,int handle){List<byte[]> out=new ArrayList<>();int offset=0,seq=0;while(offset<p.length){int n=Math.min(seq==0?9:15,p.length-offset);byte[] b=new byte[n+(seq==0?11:5)];b[0]=3;b[1]=(byte)((seq==0?1:0)|(offset+n==p.length?6:0));b[3]=(byte)handle;b[4]=(byte)seq;if(seq==0){b[5]=(byte)p.length;b[6]=(byte)(p.length>>8);b[9]=(byte)0x82;}System.arraycopy(p,offset,b,seq==0?11:5,n);out.add(b);offset+=n;seq++;}return out;}
 public static void main(String[] args)throws Exception {
  List<String> v=Files.readAllLines(Paths.get(args[0]));AtomicLong clock=new AtomicLong();Port p=new Port();HuamiAuthTransport wire=new HuamiAuthTransport(p,clock::get);HuamiSessionCrypto crypto=HuamiCryptoTest.fixed(v);HuamiAuthOwner owner=new HuamiAuthOwner(crypto,wire,clock::get);wire.bind(owner);owner.start();
  byte[] publicCommand=new byte[52];publicCommand[0]=4;publicCommand[1]=2;publicCommand[3]=2;System.arraycopy(crypto.publicKey(),0,publicCommand,4,48);List<byte[]> expected=chunks(publicCommand,1);
  for(int i=0;i<expected.size();i++){check(p.data.size()==i+1&&Arrays.equals(p.data.get(i),expected.get(i)));wire.completed(owner.generation(),p.tickets.get(i),true);}
  byte[] challenge=new byte[67];challenge[0]=0x10;challenge[1]=4;challenge[2]=1;System.arraycopy(HuamiCryptoTest.point(v.get(4),v.get(5)),0,challenge,19,48);
  List<byte[]> peer=chunks(challenge,11);for(byte[] frame:peer)wire.notified(owner.generation(),frame);
  // Real source ACK:04 00 handle 01 count, directed to RX characteristic.
  int at=expected.size();check(p.target.get(at)==HuamiAuthTransport.ACK);check(Arrays.equals(p.data.get(at),new byte[]{4,0,11,1,(byte)(peer.size()-1)}));
  wire.completed(owner.generation(),p.tickets.get(at),true);
  while(owner.stageCode()==3){int last=p.tickets.size()-1;wire.completed(owner.generation(),p.tickets.get(last),true);}
  // Encrypted peer result: public-fixture session, real decrypt -> native AUTH.
  try(HuamiSessionCrypto peerCrypto=EncryptedFramesTest.ready(v);HuamiEncryptedFrames peerWire=peerCrypto.claimEncryptedFrames(11,HuamiEncryptedFrames.RxPolicy.UPSTREAM_PAYLOAD_ONLY)){
   for(byte[] frame:peerWire.encode(0x82,23,new byte[]{0x10,5,1}))wire.notified(owner.generation(),frame);
  }
  check(owner.status()==HuamiAuthOwner.Status.PEER_ACCEPTED);owner.cancel();check(crypto.isClosed()&&p.closes==1);
  wire.completed(owner.generation(),1,true);wire.notified(owner.generation(),peer.get(0));check(p.closes==1);
  // A missing Android write callback must terminate, not reuse the GATT queue.
  for(int mode=0;mode<4;mode++){
   AtomicLong c=new AtomicLong();Port q=new Port();HuamiAuthTransport w=new HuamiAuthTransport(q,c::get);HuamiSessionCrypto k=HuamiCryptoTest.fixed(v);HuamiAuthOwner o=new HuamiAuthOwner(k,w,c::get);w.bind(o);o.start();int count=q.data.size();
   w.completed(o.generation()+1,q.tickets.get(0),true);w.completed(o.generation(),999,true);check(q.data.size()==count);
   if(mode==0){c.set(5000);w.tick();check(w.reason()==HuamiAuthTransport.Reason.WRITE_TIMEOUT);}
   if(mode==1){w.notified(o.generation(),new byte[]{3,9,0,1,0});check(w.reason()==HuamiAuthTransport.Reason.CIPHER_UNSUPPORTED);}
   if(mode==2){o.background();}
   if(mode==3){w.notified(o.generation(),new byte[]{4,0,1,1,0});check(q.data.size()==count&&o.status()!=HuamiAuthOwner.Status.PEER_ACCEPTED);o.cancel();}
   check(q.closes==1&&k.isClosed());w.completed(o.generation(),q.tickets.get(0),true);check(q.closes==1);
  }
  Map<Integer,ServiceListProbe.Encryption> list=ServiceListProbe.decode(new byte[]{4,2,0,0x29,0,1,7,0,9});check(list.get(0x29)==ServiceListProbe.Encryption.ENCRYPTED&&list.get(7)==ServiceListProbe.Encryption.UNKNOWN);check(ServiceListProbe.request()[0]==3);
  for(byte[] bad:new byte[][]{{4,1,0},{4,0,0,1},{4,(byte)129,0},{4,2,0,7,0,0,7,0,1}}){boolean rejected=false;try{ServiceListProbe.decode(bad);}catch(IllegalArgumentException e){rejected=true;}check(rejected);}
  System.out.println("PASS Rust plaintext TX/RX + serial ACK pump/real BC owner; bounded source-layout fixtures and read-only service-list definition; port simulated, not GATT/hardware.");
 }
}
