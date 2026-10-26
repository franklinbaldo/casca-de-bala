// SPDX-License-Identifier: AGPL-3.0-or-later
// Wire reference: Gadgetbridge ZeppOsServicesService, a0948ee1..., Jose Rebelo2023-2024.
package org.workspace.band7;
import java.util.*;
/** Definition/parser only. Not dispatched by AUTH-only pilot adapter. A future
 * caller needs explicit post-auth probe consent; never initialize returned services.
 */
public final class ServiceListProbe {
 public static final int ENDPOINT=0;public enum Encryption { PLAINTEXT,ENCRYPTED,UNKNOWN }
 private ServiceListProbe(){}
 public static byte[] request(){return new byte[]{3};}
 public static Map<Integer,Encryption> decode(byte[] p){
  if(p==null||p.length<3||p[0]!=4)throw new IllegalArgumentException("services header");int n=(p[1]&255)|((p[2]&255)<<8);
  if(n>128||p.length!=3+n*3)throw new IllegalArgumentException("services length");Map<Integer,Encryption> out=new LinkedHashMap<>();
  for(int i=0;i<n;i++){int at=3+i*3,endpoint=(p[at]&255)|((p[at+1]&255)<<8),flag=p[at+2]&255;if(out.containsKey(endpoint))throw new IllegalArgumentException("duplicate service");out.put(endpoint,flag==0?Encryption.PLAINTEXT:flag==1?Encryption.ENCRYPTED:Encryption.UNKNOWN);}
  return Collections.unmodifiableMap(out);
 }
}
