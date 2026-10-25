#define _POSIX_C_SOURCE 200809L
#include "band7.h"
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
static B7Result call(const char *json,int status){B7Result r;assert(b7_call((const uint8_t*)json,strlen(json),&r)==status);assert(r.status==status);return r;}
int main(void){
    _Static_assert(sizeof(B7Result)==40,"ABI size mismatch");
    char directory[]="/tmp/band7-ffi-synthetic-XXXXXX";assert(mkdtemp(directory));
    char file[256],request[1024];snprintf(file,sizeof file,"%s/synthetic.sqlite",directory);
    snprintf(request,sizeof request,"{\"version\":1,\"command\":{\"op\":\"open\",\"path\":\"%s\",\"device_ref\":\"synthetic\",\"firmware\":\"fixture-v1\",\"synthetic\":true,\"start\":120,\"end\":240}}",file);
    B7Result r=call(request,0);uint64_t h=r.handle;assert(h>0);
    char operation[1024];snprintf(operation,sizeof operation,"{\"version\":1,\"command\":{\"op\":\"ingest\",\"handle\":%llu,\"first\":120,\"utc_offset_seconds\":0,\"activity8_hex\":\"010203ff05060708\"}}",(unsigned long long)h);
    r=call(operation,0);assert(r.inserted==1&&r.has_cursor&&r.cursor==180);
    r=call(operation,0);assert(r.inserted==0);
    snprintf(operation,sizeof operation,"{\"version\":1,\"command\":{\"op\":\"close\",\"handle\":%llu}}",(unsigned long long)h);call(operation,0);call(operation,2);
    r=call(request,0);assert(r.handle!=h);h=r.handle;
    snprintf(operation,sizeof operation,"{\"version\":1,\"command\":{\"op\":\"coverage\",\"handle\":%llu}}",(unsigned long long)h);
    r=call(operation,0);assert(r.records==1&&r.missing==1&&r.has_cursor&&r.cursor==180);
    call("{\"version\":99,\"command\":{\"op\":\"coverage\",\"handle\":1}}",1);
    call("not-json",1);
    snprintf(operation,sizeof operation,"{\"version\":1,\"command\":{\"op\":\"close\",\"handle\":%llu}}",(unsigned long long)h);call(operation,0);
    assert(unlink(file)==0);assert(rmdir(directory)==0);
    puts("PASS C ABI -> Rust decoder -> SQLite -> close/reopen -> coverage; replay and errors");
}
