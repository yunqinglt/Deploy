#include "native_support.h"
#include <LIEF/ELF.hpp>
#include "sleigh_arch.hh"
#include "funcdata.hh"
#include "flow.hh"
#include "printc.hh"
#include <algorithm>
#include <cstring>
#include <memory>
#include <mutex>

namespace {
using namespace ghidra;
using Binary = LIEF::ELF::Binary;
using Segment = LIEF::ELF::Segment;

class ElfImage final : public LoadImage {
    const Binary& binary;
public:
    explicit ElfImage(const Binary& b) : LoadImage("deploy-elf"), binary(b) {}
    void loadFill(uint1* out, int4 count, const Address& address) override {
        uint64_t va = address.getOffset();
        const uint64_t first = va;
        while (count > 0) {
            const Segment* found = nullptr;
            for (const auto& seg : binary.segments()) {
                if (seg.type() == Segment::TYPE::LOAD && va >= seg.virtual_address()
                        && va - seg.virtual_address() < seg.virtual_size()) { found = &seg; break; }
            }
            if (!found) {
                // SLEIGH reads a 16-byte lookahead even for the final 4-byte
                // instruction. Match RawLoadImage: only pad after real bytes;
                // a request starting at an unmapped address still fails.
                if (va == first) throw DataUnavailError("Unmapped ELF address " + deploy::hex(va));
                std::memset(out, 0, count);
                return;
            }
            const uint64_t offset = va - found->virtual_address();
            size_t size = std::min<uint64_t>(count, found->virtual_size() - offset);
            auto data = found->content();
            size_t backed = offset < data.size() ? std::min<uint64_t>(size, data.size() - offset) : 0;
            if (backed) std::memcpy(out, data.data() + offset, backed);
            std::memset(out + backed, 0, size - backed);
            count -= size; out += size; va += size;
        }
    }
    std::string getArchType() const override { return "AARCH64:LE:64:v8A:default"; }
    void adjustVma(long) override { throw LowlevelError("ELF relocation is not supported"); }
};

class ElfArchitecture final : public SleighArchitecture {
    const Binary& binary;
protected:
    void buildLoader(DocumentStorage&) override {
        collectSpecFiles(*errorstream);
        loader = new ElfImage(binary);
    }
public:
    ElfArchitecture(const Binary& b, std::ostream* errors)
        : SleighArchitecture("deploy-elf", "AARCH64:LE:64:v8A:default", errors), binary(b) {}
};

std::mutex engineLock;
bool initialized = false;

std::string decompile(const std::string& path, const std::string& name,
        const std::string& address, const std::string& specs, int maxInstructions) {
    auto binary = LIEF::ELF::Parser::parse(path);
    if (!binary) throw std::runtime_error("LIEF could not parse this ELF");
    if (binary->header().machine_type() != LIEF::ELF::ARCH::AARCH64
            || binary->header().identity_data() != LIEF::ELF::Header::ELF_DATA::LSB)
        return deploy::error("The bundled Ghidra language supports little-endian AArch64", "DECOMPILE_ARCH_UNSUPPORTED");
    uint64_t start;
    std::string function = name;
    if (!name.empty()) {
        const auto* symbol = binary->get_dynamic_symbol(name);
        if (!symbol) symbol = binary->get_symtab_symbol(name);
        if (!symbol || symbol->shndx() == LIEF::ELF::Symbol::UNDEF)
            throw std::runtime_error("symbol is missing or undefined");
        if (symbol->type() != LIEF::ELF::Symbol::TYPE::FUNC && symbol->type() != LIEF::ELF::Symbol::TYPE::GNU_IFUNC)
            throw std::runtime_error("symbol is not a function");
        start = symbol->value();
    } else {
        start = std::stoull(address, nullptr, 16);
        function = "FUN_" + deploy::hex(start).substr(2);
    }
    bool executable = false;
    for (const auto& seg : binary->segments())
        if (seg.type() == Segment::TYPE::LOAD && seg.has(Segment::FLAGS::X)
                && start >= seg.virtual_address() && start - seg.virtual_address() < seg.content().size()) executable = true;
    if (!executable || start % 4) throw std::runtime_error("entry is not aligned, file-backed executable code");
    std::lock_guard<std::mutex> lock(engineLock);
    if (!initialized) {
        AttributeId::initialize(); ElementId::initialize(); CapabilityPoint::initializeAll();
        SleighArchitecture::specpaths.addDir2Path(specs);
        initialized = true;
    }
    std::ostringstream warnings, output;
    ElfArchitecture arch(*binary, &warnings);
    DocumentStorage store;
    arch.init(store);
    arch.max_instructions = maxInstructions;
    arch.max_jumptable_size = 1024;
    arch.flowoptions |= FlowInfo::error_toomanyinstructions;
    auto* fd = arch.symboltab->getGlobalScope()->addFunction(Address(arch.getDefaultCodeSpace(), start), function)->getFunction();
    arch.allacts.getCurrent()->reset(*fd);
    try {
        if (arch.allacts.getCurrent()->perform(*fd) < 0)
            throw std::runtime_error("decompiler stopped before completion");
    } catch (const LowlevelError& e) {
        if (e.explain.find("maximum allowable instructions") != std::string::npos)
            return "{\"error\":\"Function exceeds the instruction budget; retry with a larger max or inspect ASM pages.\","
                "\"code\":\"DECOMPILE_INSTRUCTION_LIMIT\",\"instructionLimit\":" + std::to_string(maxInstructions)
                + ",\"maxInstructionLimit\":100000,\"complete\":false}";
        throw;
    }
    arch.print->setOutputStream(&output);
    arch.print->setMarkup(false);
    arch.print->docFunction(fd);
    if (output.str().size() > 262144) throw std::runtime_error("pseudocode exceeds output limit");
    return "{\"backend\":\"ghidra\",\"engineVersion\":\"11.3.2\",\"op\":\"decompile\",\"path\":" + deploy::quote(path)
        + ",\"name\":" + deploy::quote(function) + ",\"address\":" + deploy::quote(deploy::hex(start))
        + ",\"instructionLimit\":" + std::to_string(maxInstructions)
        + ",\"addressKind\":\"elf_virtual_address\",\"language\":\"C\",\"pseudocode\":" + deploy::quote(output.str())
        + ",\"warnings\":" + deploy::quote(warnings.str())
        + ",\"note\":\"Types and prototypes are inferred. This is pseudocode, not original source or execution evidence.\"}";
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_deploy_core_DeployCore_nativeDecompile(JNIEnv* env, jclass, jstring path,
        jstring name, jstring address, jstring specs, jint maxInstructions) {
    std::string output;
    try {
        output = decompile(deploy::string(env, path), deploy::string(env, name),
                deploy::string(env, address), deploy::string(env, specs), maxInstructions);
    } catch (const ghidra::LowlevelError& e) { output = deploy::error(e.explain, "DECOMPILE_FAILED"); }
    catch (const std::exception& e) { output = deploy::error(e.what(), "DECOMPILE_FAILED"); }
    catch (...) { output = deploy::error("unknown decompiler error", "DECOMPILE_FAILED"); }
    return deploy::result(env, output);
}
