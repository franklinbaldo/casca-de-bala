/* SPDX-License-Identifier: AGPL-3.0-or-later */
#ifdef B7_HOST_JNI_SUBSET
#include "jni_host_subset.h"
#else
#include <jni.h>
#endif
#include "../ffi/band7.h"
#include <stdlib.h>
#include <string.h>

typedef int32_t (*json_call)(const uint8_t*,size_t,uint8_t*,size_t,size_t*);

/* JSON in, JSON out through a stateless copy; shared by gatt and mcp. */
static jbyteArray json_bridge(JNIEnv *env,jbyteArray input,jsize limit,json_call call){
    if((*env)->ExceptionCheck(env)||!input)return NULL;
    jsize length=(*env)->GetArrayLength(env,input);
    if((*env)->ExceptionCheck(env)||length<=0||length>limit)return NULL;
    /* Stateless operation; allocation failure cannot leave a partial mutation. */
    jbyte *copy=malloc((size_t)length);
    uint8_t *result=malloc(32768);
    if(!copy||!result){free(copy);free(result);return NULL;}
    (*env)->GetByteArrayRegion(env,input,0,length,copy);
    if((*env)->ExceptionCheck(env)){free(copy);free(result);return NULL;}
    size_t written=0;
    int32_t status=call((const uint8_t*)copy,(size_t)length,result,32768,&written);
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

JNIEXPORT jbyteArray JNICALL Java_org_workspace_band7_NativeBridge_gatt(JNIEnv *env,jclass klass,jbyteArray input){
    (void)klass;
    return json_bridge(env,input,32768,b7_gatt_json);
}

JNIEXPORT jbyteArray JNICALL Java_org_workspace_band7_NativeBridge_mcp(JNIEnv *env,jclass klass,jbyteArray input){
    (void)klass;
    return json_bridge(env,input,70000,b7_mcp_json);
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
