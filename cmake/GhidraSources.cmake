# Source groups from Ghidra 11.3.2's native decompiler Makefile (Apache-2.0).
set(GHIDRA_CORE xml marshal space float address pcoderaw translate opcodes globalcontext)
set(GHIDRA_SLEIGH sleigh pcodeparse pcodecompile sleighbase slghsymbol
    slghpatexpress slghpattern semantics context slaformat compression filemanage)
set(GHIDRA_DECOMP capability architecture options graph cover block cast typeop database cpool
    comment stringmanage modelrules fspec action loadimage grammar varnode op type variable
    varmap jumptable emulate emulateutil flow userop multiprecision funcdata funcdata_block
    funcdata_op funcdata_varnode unionresolve pcodeinject heritage prefersplit rangeutil
    ruleaction subflow blockaction merge double transform constseq coreaction condexe
    override dynamic crc32 prettyprint printlanguage printc printjava memstate opbehavior
    paramid signature sleigh_arch inject_sleigh)
function(ghidra_sources output)
    set(result)
    foreach(name IN LISTS ARGN)
        list(APPEND result "${GHIDRA_CPP}/${name}.cc")
    endforeach()
    set(${output} ${result} PARENT_SCOPE)
endfunction()
