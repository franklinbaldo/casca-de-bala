/* Self-authored host-only subset from the normative JNI function table.
 * Not an Android/NDK header. Android builds MUST use official jni.h instead.
 * JNI spec: https://docs.oracle.com/en/java/javase/21/docs/specs/jni/functions.html */
#ifndef B7_JNI_HOST_SUBSET_H
#define B7_JNI_HOST_SUBSET_H
#if !defined(__linux__) || !defined(__x86_64__)
#error Host subset validated only for Linux x86_64; use official jni.h elsewhere
#endif
#include <stdint.h>
#include <stddef.h>
typedef int32_t jint;
typedef jint jsize;
typedef int64_t jlong;
typedef int8_t jbyte;
typedef uint8_t jboolean;
typedef struct B7JObject *jobject;
typedef jobject jclass;
typedef jobject jarray;
typedef jarray jbyteArray;
typedef jarray jlongArray;
struct B7JniTable;
typedef const struct B7JniTable *JNIEnv;
struct B7JniTable {
    void *unused_0_170[171];
    jsize (*GetArrayLength)(JNIEnv *,jarray); /* 171 */
    void *unused_172_175[4];
    jbyteArray (*NewByteArray)(JNIEnv *,jsize); /* 176 */
    void *unused_177_179[3];
    jlongArray (*NewLongArray)(JNIEnv *,jsize); /* 180 */
    void *unused_181_199[19];
    void (*GetByteArrayRegion)(JNIEnv *,jbyteArray,jsize,jsize,jbyte *); /* 200 */
    void *unused_201_207[7];
    void (*SetByteArrayRegion)(JNIEnv *,jbyteArray,jsize,jsize,const jbyte *); /* 208 */
    void *unused_209_211[3];
    void (*SetLongArrayRegion)(JNIEnv *,jlongArray,jsize,jsize,const jlong *); /* 212 */
    void *unused_213_227[15];
    jboolean (*ExceptionCheck)(JNIEnv *); /* 228 */
};
#define B7_SLOT(field,index) _Static_assert(offsetof(struct B7JniTable,field)==(index)*sizeof(void*),"JNI slot mismatch")
B7_SLOT(GetArrayLength,171); B7_SLOT(NewLongArray,180); B7_SLOT(GetByteArrayRegion,200);
B7_SLOT(SetLongArrayRegion,212); B7_SLOT(ExceptionCheck,228);
B7_SLOT(NewByteArray,176); B7_SLOT(SetByteArrayRegion,208);
#define JNIEXPORT __attribute__((visibility("default")))
#define JNICALL
#endif
