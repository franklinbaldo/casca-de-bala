/* SPDX-License-Identifier: AGPL-3.0-or-later */
#ifdef B7_HOST_JNI_SUBSET
#include "jni_host_subset.h"
#else
#include <jni.h>
#endif
#include "../ffi/band7.h"
#include <stdlib.h>
#include <string.h>

JNIEXPORT jbyteArray JNICALL Java_org_workspace_band7_NativeBridge_gatt(JNIEnv *env,jclass klass,jbyteArray input){
    (void)klass;
    if((*env)->ExceptionCheck(env)||!input)return NULL;
    jsize length=(*env)->GetArrayLength(env,input);
    if((*env)->ExceptionCheck(env)||length<=0||length>32768)return NULL;
    /* Stateless operation; allocation failure cannot leave a partial mutation. */
    jbyte *copy=malloc((size_t)length);
    uint8_t *result=malloc(32768);
    if(!copy||!result){free(copy);free(result);return NULL;}
    (*env)->GetByteArrayRegion(env,input,0,length,copy);
    if((*env)->ExceptionCheck(env)){free(copy);free(result);return NULL;}
    size_t written=0;
    int32_t status=b7_gatt_json((const uint8_t*)copy,(size_t)length,result,32768,&written);
    volatile jbyte *wipe=copy;for(jsize i=0;i<length;i++)wipe[i]=0;
    free(copy);
    jbyteArray output=NULL;
    if(status==0&&written<=32768){
        output=(*env)->NewByteArray(env,(jsize)written);
        if(!(*env)->ExceptionCheck(env)&&output){
            (*env)->SetByteArrayRegion(env,output,0,(jsize)written,(const jbyte*)result);
        }
    }
    volatile uint8_t *clear=result;for(size_t i=0;i<32768;i++)clear[i]=0;
    free(result);
    if((*env)->ExceptionCheck(env))return NULL;
    return output;
}

JNIEXPORT jlongArray JNICALL Java_org_workspace_band7_NativeBridge_invoke(JNIEnv *env,jclass klass,jbyteArray input){
    (void)klass;
    if((*env)->ExceptionCheck(env))return NULL;
    /* Allocate managed result BEFORE mutating native state. No pinned arrays. */
    jlongArray output=(*env)->NewLongArray(env,8);
    if((*env)->ExceptionCheck(env)||!output)return NULL;
    B7Result result={0};
    result.status=1;
    if(input){
        jsize length=(*env)->GetArrayLength(env,input);
        if((*env)->ExceptionCheck(env))return NULL;
        if(length>0&&length<=70000){
            jbyte *copy=malloc((size_t)length);
            if(!copy){result.status=7;}else{
                (*env)->GetByteArrayRegion(env,input,0,length,copy);
                if((*env)->ExceptionCheck(env)){free(copy);return NULL;}
                b7_call((const uint8_t*)copy,(size_t)length,&result);
                /* Clear transient payload, never retain JVM refs/env or pointers. */
                volatile jbyte *wipe=copy;for(jsize i=0;i<length;i++)wipe[i]=0;
                free(copy);
            }
        }
    }
    jlong handle_bits; memcpy(&handle_bits,&result.handle,sizeof(handle_bits));
    jlong values[8]={result.status,result.has_cursor,handle_bits,result.cursor,
        result.inserted,result.records,result.missing,result.reserved};
    (*env)->SetLongArrayRegion(env,output,0,8,values);
    if((*env)->ExceptionCheck(env))return NULL;
    return output;
}

/* AUTH peer envelope only; never copies auth key, private EC or session key. */
JNIEXPORT jint JNICALL Java_org_workspace_band7_NativeBridge_authReplyKind(JNIEnv *env,jclass klass,jint endpoint,jbyteArray input){
    (void)klass;
    if((*env)->ExceptionCheck(env)||!input||endpoint<0||endpoint>65535)return 0;
    jsize length=(*env)->GetArrayLength(env,input);
    if((*env)->ExceptionCheck(env)||length<=0||length>67)return 0;
    jbyte bytes[67];
    (*env)->GetByteArrayRegion(env,input,0,length,bytes);
    if((*env)->ExceptionCheck(env))return 0;
    return (jint)b7_auth_reply_kind((uint16_t)endpoint,(const uint8_t*)bytes,(size_t)length);
}

static void b7_le32(uint8_t *p,uint32_t v){for(unsigned i=0;i<4;i++)p[i]=(uint8_t)(v>>(8*i));}
static void b7_le64(uint8_t *p,uint64_t v){for(unsigned i=0;i<8;i++)p[i]=(uint8_t)(v>>(8*i));}
JNIEXPORT jbyteArray JNICALL Java_org_workspace_band7_NativeBridge_authSession(JNIEnv *env,jclass klass,jint op,jlong handle,jlong generation,jlong now,jint endpoint,jbyteArray input){
    (void)klass;
    if((*env)->ExceptionCheck(env))return NULL;
    jbyteArray output=(*env)->NewByteArray(env,536);if(!output||(*env)->ExceptionCheck(env))return NULL;
    uint8_t result_bytes[536]={0};B7AuthResult result={.status=8,.stage=6};
    jsize length=input?(*env)->GetArrayLength(env,input):0;if((*env)->ExceptionCheck(env))return NULL;
    if(op>=0&&op<=7&&generation>0&&now>=0&&endpoint>=0&&endpoint<=65535&&length>=0&&length<=512){
        jbyte bytes[512]={0};if(input&&length)(*env)->GetByteArrayRegion(env,input,0,length,bytes);if((*env)->ExceptionCheck(env))return NULL;
        uint64_t h;memcpy(&h,&handle,sizeof(h));
        b7_auth_session((uint32_t)op,h,(uint64_t)generation,(uint64_t)now,(uint16_t)endpoint,(const uint8_t*)bytes,(size_t)length,result_bytes+24,512,&result);
    }
    b7_le32(result_bytes,(uint32_t)result.status);b7_le32(result_bytes+4,result.stage);b7_le64(result_bytes+8,result.handle);b7_le32(result_bytes+16,(uint32_t)result.written);b7_le32(result_bytes+20,result.ack);
    (*env)->SetByteArrayRegion(env,output,0,536,(const jbyte*)result_bytes);if((*env)->ExceptionCheck(env))return NULL;return output;
}

/* Stateless bounded decoder/plain read-only framing. No key bytes. */
JNIEXPORT jbyteArray JNICALL Java_org_workspace_band7_NativeBridge_zepp(JNIEnv *env,jclass klass,jint op,jint endpoint,jint handle,jbyteArray input){
    (void)klass;
    if((*env)->ExceptionCheck(env)||!input||op<0||op>4||endpoint<0||endpoint>65535||handle<0||handle>255)return NULL;
    jsize length=(*env)->GetArrayLength(env,input);
    if((*env)->ExceptionCheck(env)||length<1||length>512)return NULL;
    jbyte bytes[512]={0};uint8_t result[512]={0};size_t written=0;
    (*env)->GetByteArrayRegion(env,input,0,length,bytes);if((*env)->ExceptionCheck(env))return NULL;
    int32_t status=b7_zepp((uint32_t)op,(uint16_t)endpoint,(uint8_t)handle,(const uint8_t*)bytes,(size_t)length,result,512,&written);
    if(status!=0||written>512)return NULL;
    jbyteArray output=(*env)->NewByteArray(env,(jsize)written);if(!output||(*env)->ExceptionCheck(env))return NULL;
    (*env)->SetByteArrayRegion(env,output,0,(jsize)written,(const jbyte*)result);
    return (*env)->ExceptionCheck(env)?NULL:output;
}
