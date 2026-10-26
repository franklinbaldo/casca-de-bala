// SPDX-License-Identifier: AGPL-3.0-or-later
package org.workspace.band7;
import java.util.Arrays;import java.util.Objects;import java.util.function.Consumer;
/** No storage, logging, String conversion or clipboard access. Best-effort local
 * buffer wipe only: managed-runtime/IME/crypto-provider copies are not guaranteed erased. */
public final class LocalCredential {
 private LocalCredential(){}
 public static void use(CharSequence input,Consumer<byte[]> consumer){
  Objects.requireNonNull(consumer);if(input==null||input.length()!=32)throw new IllegalArgumentException("credential format");
  byte[] bytes=new byte[16];try{for(int i=0;i<32;i++){char c=input.charAt(i);int n=c>='0'&&c<='9'?c-'0':c>='a'&&c<='f'?c-'a'+10:c>='A'&&c<='F'?c-'A'+10:-1;if(n<0)throw new IllegalArgumentException("credential format");bytes[i/2]|=(byte)(n<<(i%2==0?4:0));}consumer.accept(bytes);}finally{Arrays.fill(bytes,(byte)0);}
 }
}
