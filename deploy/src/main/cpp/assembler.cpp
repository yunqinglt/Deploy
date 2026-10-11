#include "native_support.h"
#include <keystone/keystone.h>
#include <capstone/capstone.h>
#include <memory>
#include <vector>
#include <limits>

namespace {
struct Target { ks_arch ka; int km; cs_arch ca; cs_mode cm; unsigned alignment; };
Target target(const std::string& arch) {
    if (arch == "aarch64") return {KS_ARCH_ARM64, KS_MODE_LITTLE_ENDIAN, CS_ARCH_AARCH64, CS_MODE_LITTLE_ENDIAN, 4};
    if (arch == "arm") return {KS_ARCH_ARM, KS_MODE_ARM, CS_ARCH_ARM, CS_MODE_ARM, 4};
    if (arch == "thumb") return {KS_ARCH_ARM, KS_MODE_THUMB, CS_ARCH_ARM, CS_MODE_THUMB, 2};
    if (arch == "x86") return {KS_ARCH_X86, KS_MODE_32, CS_ARCH_X86, CS_MODE_32, 1};
    if (arch == "x86_64") return {KS_ARCH_X86, KS_MODE_64, CS_ARCH_X86, CS_MODE_64, 1};
    throw std::runtime_error("unsupported architecture");
}
std::string bytesHex(const unsigned char* data, size_t count) {
    std::string out; const char* h = "0123456789abcdef";
    for (size_t i=0;i<count;++i) { out += h[data[i]>>4]; out += h[data[i]&15]; }
    return out;
}
void validateAddress(const Target& t, const std::string& arch, uint64_t address, size_t size) {
    if (address % t.alignment) throw std::runtime_error("address is not instruction-aligned");
    if (size > std::numeric_limits<uint64_t>::max() - address) throw std::runtime_error("address range overflows");
    if ((arch == "x86" || arch == "arm" || arch == "thumb") && address + size > 0x100000000ULL)
        throw std::runtime_error("address is outside the 32-bit architecture");
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_deploy_core_DeployCore_nativeAssemble(JNIEnv* env, jclass, jstring arch, jstring source, jstring address) {
    std::string output;
    ks_engine* engine = nullptr;
    unsigned char* encoded = nullptr;
    try {
        auto a=deploy::string(env,arch); auto s=deploy::string(env,source);
        auto base=std::stoull(deploy::string(env,address),nullptr,16); auto t=target(a);
        validateAddress(t,a,base,0);
        if (ks_open(t.ka, static_cast<ks_mode>(t.km), &engine) != KS_ERR_OK) throw std::runtime_error("Keystone could not initialize");
        size_t size=0,count=0;
        if (ks_asm(engine,s.c_str(),base,&encoded,&size,&count) != 0) throw std::runtime_error(ks_strerror(ks_errno(engine)));
        if (size==0 || size>4096) throw std::runtime_error("assembled code must contain 1..4096 bytes");
        validateAddress(t,a,base,size);
        output="{\"backend\":\"keystone\",\"arch\":"+deploy::quote(a)+",\"address\":"+deploy::quote(deploy::hex(base))
            +",\"bytes\":"+deploy::quote(bytesHex(encoded,size))+",\"size\":"+std::to_string(size)
            +",\"statements\":"+std::to_string(count)+",\"executed\":false}";
    } catch(const std::exception& e) { output=deploy::error(e.what(),"ASSEMBLY_FAILED"); }
    if(encoded) ks_free(encoded);
    if(engine) ks_close(engine);
    return deploy::result(env,output);
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_deploy_core_DeployCore_nativeDisassembleBytes(JNIEnv* env,jclass,jbyteArray bytes,jstring arch,jstring address,jint max) {
    std::string output;
    csh engine=0; cs_insn* insn=nullptr; size_t count=0;
    try {
        auto a=deploy::string(env,arch); auto t=target(a);
        auto base=std::stoull(deploy::string(env,address),nullptr,16);
        size_t length=env->GetArrayLength(bytes);
        if (!length || length>4096) throw std::runtime_error("byte window must contain 1..4096 bytes");
        validateAddress(t,a,base,length);
        std::vector<unsigned char> data(length);
        env->GetByteArrayRegion(bytes,0,length,reinterpret_cast<jbyte*>(data.data()));
        if(cs_open(t.ca,t.cm,&engine)!=CS_ERR_OK) throw std::runtime_error("Capstone could not initialize");
        count=cs_disasm(engine,data.data(),length,base,max,&insn);
        if(!count) throw std::runtime_error("no instruction decoded at this address");
        output="{\"backend\":\"capstone\",\"arch\":"+deploy::quote(a)+",\"address\":"+deploy::quote(deploy::hex(base))
            +",\"addressKind\":\"buffer_virtual_address\",\"instructions\":[";
        size_t used=0;
        for(size_t i=0;i<count;++i) {
            if(i)output+=',';
            output+="{\"address\":"+deploy::quote(deploy::hex(insn[i].address))+",\"bytes\":"+deploy::quote(bytesHex(insn[i].bytes,insn[i].size))
                +",\"size\":"+std::to_string(insn[i].size)+",\"mnemonic\":"+deploy::quote(insn[i].mnemonic)
                +",\"operands\":"+deploy::quote(insn[i].op_str)+"}";
            used+=insn[i].size;
        }
        output+="],\"count\":"+std::to_string(count)+",\"decodedBytes\":"+std::to_string(used)
            +",\"nextAddress\":"+deploy::quote(deploy::hex(base+used))+",\"stopReason\":"
            +deploy::quote(used==length?"window_end":count==static_cast<size_t>(max)?"instruction_limit":"undecodable_bytes")+"}";
    }catch(const std::exception& e){output=deploy::error(e.what(),"DISASSEMBLY_FAILED");}
    if(insn)cs_free(insn,count);
    if(engine)cs_close(&engine);
    return deploy::result(env,output);
}
