#pragma once
#include <jni.h>
#include <string>
#include <sstream>
#include <stdexcept>

namespace deploy {
inline std::string quote(const std::string& s) {
    std::string out = "\"";
    for (unsigned char c : s) {
        if (c == '"' || c == '\\') { out += '\\'; out += c; }
        else if (c < 32) {
            const char* hex = "0123456789abcdef";
            out += "\\u00"; out += hex[c >> 4]; out += hex[c & 15];
        } else out += c;
    }
    return out + '"';
}
inline std::string hex(uint64_t value) {
    std::ostringstream s; s << "0x" << std::hex << value; return s.str();
}
inline std::string string(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* p = env->GetStringUTFChars(s, nullptr);
    if (!p) throw std::runtime_error("could not read string");
    std::string out(p); env->ReleaseStringUTFChars(s, p); return out;
}
inline std::string error(const std::string& message, const std::string& code) {
    return "{\"error\":" + quote(message) + ",\"code\":" + quote(code) + "}";
}
inline jstring result(JNIEnv* env, const std::string& s) {
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(s.size()));
    if (!bytes) return nullptr;
    env->SetByteArrayRegion(bytes, 0, s.size(), reinterpret_cast<const jbyte*>(s.data()));
    jclass cls = env->FindClass("java/lang/String");
    jmethodID ctor = env->GetMethodID(cls, "<init>", "([BLjava/lang/String;)V");
    jstring encoding = env->NewStringUTF("UTF-8");
    return static_cast<jstring>(env->NewObject(cls, ctor, bytes, encoding));
}
}
