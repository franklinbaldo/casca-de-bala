// SPDX-License-Identifier: AGPL-3.0-or-later
package org.workspace.band7;
import java.util.*;
public final class LocalCredentialTest {
 static void check(boolean b){if(!b)throw new AssertionError("ephemeral input contract");}
 public static void main(String[] args){run();}
 public static void run(){
  CharSequence input=new CharSequence(){public int length(){return 32;}public char charAt(int n){return n%2==0?'A':'1';}public CharSequence subSequence(int a,int b){throw new AssertionError("no immutable copy");}public String toString(){throw new AssertionError("no String conversion");}};
  final byte[][] seen={null};LocalCredential.use(input,key->{check(key.length==16);for(byte b:key)check((b&255)==161);seen[0]=key;});check(Arrays.equals(seen[0],new byte[16]));
  boolean threw=false;try{LocalCredential.use(input,key->{seen[0]=key;throw new IllegalStateException("fixture");});}catch(IllegalStateException expected){threw=true;}check(threw&&Arrays.equals(seen[0],new byte[16]));
  for(CharSequence bad:new CharSequence[]{null,"","11","0000000000000000000000000000000g","000000000000000000000000000000000","000000000000000000000000000000 0"}){boolean invalid=false;try{LocalCredential.use(bad,key->{throw new AssertionError("invalid accepted");});}catch(IllegalArgumentException expected){invalid=true;check(!expected.getMessage().contains("0000"));}check(invalid);}
  System.out.println("PASS ephemeral credential: exact32 hex, no String conversion, no callback for invalid, temporary bytes wiped after success/exception. Synthetic input only; no JVM-wide erase guarantee.");
 }
}
